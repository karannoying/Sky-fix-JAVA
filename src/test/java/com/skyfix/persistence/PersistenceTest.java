package com.skyfix.persistence;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-D1, T-D3 and T-D4 — the database layer against a temporary file per test (ADR-2).
 *
 * <p>A file rather than an in-memory database, because file-backed is what actually ships and
 * because T-D1's claim is that a run survives being closed and reopened.
 */
class PersistenceTest {

    @TempDir
    Path tempDir;

    private Database database;
    private MissionDao missions;
    private BalloonConfigDao configs;
    private SoundingDao soundings;
    private RunDao runs;
    private RunStateDao states;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.openFile(tempDir.resolve("skyfix-test.db"));
        new SchemaInitializer(database).initialise();
        missions = new MissionDao(database);
        configs = new BalloonConfigDao(database);
        soundings = new SoundingDao(database);
        runs = new RunDao(database);
        states = new RunStateDao(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    static BalloonConfig config() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g").payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4).gas(LiftGas.HELIUM)
                .build();
    }

    private Mission storeMission() throws Exception {
        return missions.save(new Mission(null, "HabSat-1",
                new GeoPoint(23.2599, 77.4126, 500.0), 500.0, "2026-09-14T04:30:00Z"));
    }

    @Test
    @DisplayName("The schema applies, records its version, and applying it again is a no-op")
    void schemaIsAppliedIdempotently() throws Exception {
        SchemaInitializer initializer = new SchemaInitializer(database);
        assertThat(initializer.appliedVersion()).isEqualTo(SchemaInitializer.SCHEMA_VERSION);
        // Already applied in setUp, so a second call must do nothing rather than fail on
        // CREATE TABLE.
        assertThat(initializer.initialise()).isFalse();
        assertThat(initializer.appliedVersion()).isEqualTo(SchemaInitializer.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("All 13 tables in V1__init.sql exist after initialisation")
    void everyTableIsCreated() throws Exception {
        List<String> expected = List.of("schema_version", "mission", "balloon_config", "sounding",
                "sounding_level", "flight_log", "telemetry_sample", "run", "ensemble_member",
                "run_state", "estimate", "landing_ellipse", "validation_result");
        List<String> actual = new ArrayList<>();
        try (var ps = database.connection().prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                actual.add(rs.getString(1));
            }
        }
        assertThat(actual).containsAll(expected);
    }

    @Test
    @DisplayName("Foreign keys are actually enforced, not merely declared")
    void foreignKeysAreEnforced() throws Exception {
        // SQLite ignores foreign keys unless the pragma is set per connection. If this were off,
        // every cascade test below would pass for the wrong reason.
        assertThat(database.foreignKeysEnforced()).isTrue();

        assertThatThrownBy(() -> configs.save(
                new StoredBalloonConfig(null, 9_999L, config())))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("T-D1: a run re-materialises from the database without re-simulating")
    void runRematerialisesAfterCloseAndReopen() throws Exception {
        Path file = tempDir.resolve("rematerialise.db");
        long runId;
        GeoPoint originalLanding;
        int originalStateCount;

        // --- Session one: simulate and store, then close the database completely.
        try (Database first = Database.openFile(file)) {
            new SchemaInitializer(first).initialise();
            MissionDao m = new MissionDao(first);
            BalloonConfigDao c = new BalloonConfigDao(first);
            RunDao r = new RunDao(first);
            RunStateDao s = new RunStateDao(first);

            Mission mission = m.save(new Mission(null, "HabSat-1",
                    new GeoPoint(23.2599, 77.4126, 500.0), 500.0, "2026-09-14T04:30:00Z"));
            StoredBalloonConfig stored = c.findOrSave(mission.id(), config());

            SimSettings settings = SimSettings.builder()
                    .groundElevationM(500.0).stateSampleStride(10).build();
            StateHistory history = new FlightSimulator(new Ussa1976Atmosphere(),
                    new ConstantWindField(9.0, -3.0))
                    .run(config(), settings, mission.launch());
            originalLanding = history.landingPoint().orElseThrow();

            RunRecord run = r.save(new RunRecord(null, mission.id(), stored.id(), null, null,
                    "PREFLIGHT", settings.integrator(), settings.stepSeconds(), 1, 42L,
                    "abc1234", config().configHash(), Runtime.getRuntime().availableProcessors(),
                    Instant.now().toString(), null, RunRecord.RUNNING));
            runId = run.id();
            originalStateCount = s.saveHistory(runId, 0, history);
            r.finish(runId, 1234L, RunRecord.OK);
        }

        // --- Session two: a fresh connection, nothing simulated.
        try (Database second = Database.openFile(file)) {
            RunRecord run = new RunDao(second).findById(runId).orElseThrow();

            // Everything NFR-5 requires a run to persist must come back.
            assertThat(run.rngSeed()).isEqualTo(42L);
            assertThat(run.gitSha()).isEqualTo("abc1234");
            assertThat(run.configHash()).isEqualTo(config().configHash());
            assertThat(run.integrator()).isEqualTo("RK4");
            assertThat(run.stepSeconds()).isEqualTo(0.25);
            assertThat(run.memberCount()).isEqualTo(1);
            assertThat(run.hostCores()).isPositive();
            assertThat(run.wallClockMs()).isEqualTo(1234L);
            assertThat(run.status()).isEqualTo(RunRecord.OK);
            assertThat(run.soundingId()).isNull();

            List<BalloonState> trajectory = new RunStateDao(second).findHistory(runId, 0);
            assertThat(trajectory).hasSize(originalStateCount);
            BalloonState landing = trajectory.get(trajectory.size() - 1);
            assertThat(landing.latitudeDeg())
                    .isEqualTo(originalLanding.latitudeDeg(), within(1e-9));
            assertThat(landing.longitudeDeg())
                    .isEqualTo(originalLanding.longitudeDeg(), within(1e-9));

            // The configuration comes back rebuilt and still hashes to the same identity.
            StoredBalloonConfig stored = new BalloonConfigDao(second)
                    .findById(run.balloonConfigId()).orElseThrow();
            assertThat(stored.config().configHash()).isEqualTo(config().configHash());
            assertThat(stored.config().ascentCd()).isEqualTo(0.45);
            assertThat(stored.config().gas()).isEqualTo(LiftGas.HELIUM);
        }
    }

    @Test
    @DisplayName("T-D3: deleting a run cascades to its states; deleting a mission to its configs")
    void deleteCascades() throws Exception {
        Mission mission = storeMission();
        StoredBalloonConfig stored = configs.findOrSave(mission.id(), config());
        RunRecord run = runs.save(new RunRecord(null, mission.id(), stored.id(), null, null,
                "PREFLIGHT", "RK4", 0.25, 1, 7L, "sha", config().configHash(), 4,
                Instant.now().toString(), null, RunRecord.OK));

        StateHistory history = StateHistory.builder()
                .add(new BalloonState(0, mission.launch(), 5.0, 2.0,
                        com.skyfix.domain.Phase.ASCENT, false))
                .add(new BalloonState(1, mission.launch(), 5.0, 2.1,
                        com.skyfix.domain.Phase.ASCENT, false))
                .build();
        states.saveHistory(run.id(), 0, history);
        assertThat(states.countForRun(run.id())).isEqualTo(2);

        // run -> run_state cascades.
        assertThat(runs.deleteById(run.id())).isTrue();
        assertThat(states.countForRun(run.id())).as("run_state cascades from run").isZero();

        // mission -> balloon_config cascades, once nothing references it.
        assertThat(configs.count()).isEqualTo(1);
        assertThat(missions.deleteById(mission.id())).isTrue();
        assertThat(configs.count()).as("balloon_config cascades from mission").isZero();
    }

    @Test
    @DisplayName("T-D3: run records are NOT cascaded away — a mission with runs cannot be deleted")
    void runRecordsProtectTheirMission() throws Exception {
        // Every child of mission and run in V1__init.sql carries ON DELETE CASCADE except
        // run.mission_id and run.balloon_config_id, which are plain references. That asymmetry is
        // deliberate: a run is the provenance of a result that may already have been reported
        // (NFR-5), so deleting a mission must not silently destroy it.
        Mission mission = storeMission();
        StoredBalloonConfig stored = configs.findOrSave(mission.id(), config());
        runs.save(new RunRecord(null, mission.id(), stored.id(), null, null,
                "PREFLIGHT", "RK4", 0.25, 1, 7L, "sha", config().configHash(), 4,
                Instant.now().toString(), null, RunRecord.OK));

        assertThatThrownBy(() -> missions.deleteById(mission.id()))
                .isInstanceOfSatisfying(PersistenceException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(6);
                    // NFR-3: the message explains the refusal instead of leaking a constraint name.
                    assertThat(e.getMessage())
                            .contains("run records")
                            .contains("provenance");
                });

        assertThat(missions.count()).as("nothing was deleted").isEqualTo(1);
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-D3: deleting a sounding cascades to its levels")
    void deletingASoundingCascadesToLevels() throws Exception {
        StoredSounding stored = soundings.save(new StoredSounding(null, "VABB",
                "2026-09-14T00:00:00Z", "SYNTHETIC", "deadbeef", List.of(
                new SoundingLevel(0, 101_325, 300, 2, 1),
                new SoundingLevel(5_000, 54_000, 255, 12, -3))));

        assertThat(soundings.countLevels(stored.id())).isEqualTo(2);
        assertThat(soundings.deleteById(stored.id())).isTrue();
        assertThat(soundings.countLevels(stored.id())).isZero();
    }

    @Test
    @DisplayName("T-D4: a batch that violates a CHECK rolls back with zero partial rows")
    void failedBatchRollsBackCompletely() throws Exception {
        // The last level breaks the temperature CHECK (150-340 K). The schema enforces physics
        // as well as Java does, so this row cannot reach the table by any path -- and because
        // the levels go in as one batch inside one transaction, the two good rows must not
        // survive either.
        List<SoundingLevel> levels = List.of(
                new SoundingLevel(0, 101_325, 300, 2, 1),
                new SoundingLevel(5_000, 54_000, 255, 12, -3),
                new SoundingLevel(10_000, 26_500, 999, 30, -8));

        assertThatThrownBy(() -> soundings.save(new StoredSounding(null, "VABB",
                "2026-09-14T00:00:00Z", "SYNTHETIC", "deadbeef", levels)))
                .isInstanceOf(PersistenceException.class);

        assertThat(soundings.count()).as("the parent row must not survive").isZero();
        try (var ps = database.connection()
                .prepareStatement("SELECT COUNT(*) FROM sounding_level");
             var rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).as("no partial level rows may remain").isZero();
        }
    }

    @Test
    @DisplayName("T-D4: a transaction that throws leaves nothing behind")
    void transactionRollsBackOnAnyFailure() throws Exception {
        Mission mission = storeMission();
        long before = missions.count();

        assertThatThrownBy(() -> database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO mission (name, launch_lat, launch_lon, launch_alt_m, "
                            + "ground_elev_m, launch_epoch_utc) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, "doomed");
                ps.setDouble(2, 0);
                ps.setDouble(3, 0);
                ps.setDouble(4, 0);
                ps.setDouble(5, 0);
                ps.setString(6, "2026-09-14T00:00:00Z");
                ps.executeUpdate();
            }
            throw new IllegalStateException("work failed after the insert");
        })).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("rolled back");

        assertThat(missions.count()).isEqualTo(before);
        assertThat(missions.findByName("doomed")).isEmpty();
        assertThat(mission.id()).isNotNull();
    }

    @Test
    @DisplayName("The schema enforces physics: a CHECK refuses what the builder would refuse")
    void schemaEnforcesPhysicsIndependentlyOfJava() throws Exception {
        Mission mission = storeMission();
        // burst_diameter_m > launch_diameter_m is a CHECK as well as a builder rule. Writing the
        // row directly, bypassing the builder, must still fail.
        assertThatThrownBy(() -> database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO balloon_config (mission_id, name, payload_mass_kg, "
                            + "envelope_mass_kg, launch_diameter_m, burst_diameter_m, "
                            + "free_lift_kg, ascent_cd, chute_area_m2, chute_cd, gas, "
                            + "config_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, mission.id());
                ps.setString(2, "impossible");
                ps.setDouble(3, 1.2);
                ps.setDouble(4, 1.2);
                ps.setDouble(5, 7.0);   // launch diameter
                ps.setDouble(6, 1.8);   // burst diameter, smaller: forbidden
                ps.setDouble(7, 1.1);
                ps.setDouble(8, 0.45);
                ps.setDouble(9, 1.0);
                ps.setDouble(10, 1.4);
                ps.setString(11, "HELIUM");
                ps.setString(12, "nothash");
                return ps.executeUpdate();
            }
        })).isInstanceOf(PersistenceException.class);

        // And an unknown gas is refused by the CHECK constraint too.
        assertThat(configs.count()).isZero();
    }

    @Test
    @DisplayName("ADR-10: the same configuration maps to one row, a changed one to another")
    void configIdentityIsTheHash() throws Exception {
        Mission mission = storeMission();
        StoredBalloonConfig first = configs.findOrSave(mission.id(), config());
        StoredBalloonConfig again = configs.findOrSave(mission.id(), config());

        assertThat(again.id()).as("an unchanged config reuses its row").isEqualTo(first.id());
        assertThat(configs.count()).isEqualTo(1);

        StoredBalloonConfig changed = configs.findOrSave(mission.id(),
                config().toBuilder().ascentCd(0.46).build());
        assertThat(changed.id()).isNotEqualTo(first.id());
        assertThat(configs.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("A tampered config_hash is detected when the row is read back")
    void tamperedConfigHashIsDetected() throws Exception {
        Mission mission = storeMission();
        StoredBalloonConfig stored = configs.findOrSave(mission.id(), config());

        // Simulate someone editing the .db file directly: the stored fields no longer rebuild
        // to the stored hash, so every reproducibility claim resting on this row is void.
        database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "UPDATE balloon_config SET ascent_cd = ? WHERE id = ?")) {
                ps.setDouble(1, 0.99);
                ps.setLong(2, stored.id());
                return ps.executeUpdate();
            }
        });

        assertThatThrownBy(() -> configs.findById(stored.id()))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("config_hash")
                .hasMessageContaining("not reproducible");
    }

    @Test
    @DisplayName("Re-importing the same sounding is recognised by its natural key")
    void soundingReimportIsIdempotent() throws Exception {
        StoredSounding stored = soundings.save(new StoredSounding(null, "VABB",
                "2026-09-14T00:00:00Z", "WYOMING", "abc123", List.of(
                new SoundingLevel(0, 101_325, 300, 2, 1),
                new SoundingLevel(5_000, 54_000, 255, 12, -3))));

        assertThat(soundings.findByNaturalKey("VABB", "2026-09-14T00:00:00Z", "WYOMING"))
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.id()).isEqualTo(stored.id()));
        assertThat(soundings.findByNaturalKey("VABB", "2026-09-14T00:00:00Z", "IGRA")).isEmpty();

        // The UNIQUE constraint stops a duplicate getting in by a second route.
        assertThatThrownBy(() -> soundings.save(new StoredSounding(null, "VABB",
                "2026-09-14T00:00:00Z", "WYOMING", "abc123", List.of(
                new SoundingLevel(0, 101_325, 300, 2, 1),
                new SoundingLevel(5_000, 54_000, 255, 12, -3)))))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("Run listing filters by mission and kind, most recent first")
    void runsAreListedByMissionAndKind() throws Exception {
        Mission mission = storeMission();
        StoredBalloonConfig stored = configs.findOrSave(mission.id(), config());
        for (String kind : List.of("PREFLIGHT", "PREFLIGHT", "VALIDATION")) {
            runs.save(new RunRecord(null, mission.id(), stored.id(), null, null, kind, "RK4",
                    0.25, 1, 1L, "sha", config().configHash(), 4,
                    "2026-09-14T0" + kind.length() + ":00:00Z", null, RunRecord.OK));
        }
        assertThat(runs.findByMissionAndKind(mission.id(), "PREFLIGHT")).hasSize(2);
        assertThat(runs.findByMissionAndKind(mission.id(), "VALIDATION")).hasSize(1);
        assertThat(runs.findByMissionAndKind(mission.id(), "REPLAY")).isEmpty();
        assertThat(runs.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("Validation results round-trip and the failure count drives the exit code")
    void validationResultsRoundTrip() throws Exception {
        Mission mission = storeMission();
        StoredBalloonConfig stored = configs.findOrSave(mission.id(), config());
        RunRecord run = runs.save(new RunRecord(null, mission.id(), stored.id(), null, null,
                "VALIDATION", "RK4", 0.25, 1, 1L, "sha", config().configHash(), 4,
                Instant.now().toString(), null, RunRecord.OK));

        ValidationDao dao = new ValidationDao(database);
        dao.saveAll(run.id(), List.of(
                ValidationResult.relative("T-V1", "density @ 20000 m", 0.0889, 0.088908,
                        "kg/m3", 0.001),
                ValidationResult.absolute("T-V3", "landing separation", 0.003, 0.0, "m", 50.0),
                ValidationResult.relative("T-V2", "ascent rate @ 8000 m", 5.0, 4.393,
                        "m/s", 0.02)));

        List<ValidationResult> back = dao.findForRun(run.id());
        assertThat(back).hasSize(3);
        assertThat(dao.countFailed(run.id()))
                .as("the deliberately-out-of-tolerance T-V2 row must be counted").isEqualTo(1);
        assertThat(back).anySatisfy(r -> {
            assertThat(r.caseId()).isEqualTo("T-V1");
            assertThat(r.passed()).isTrue();
            assertThat(r.toleranceKind()).isEqualTo(ValidationResult.RELATIVE);
        });
    }

    @Test
    @DisplayName("Missing rows return empty rather than null, and deletes report honestly")
    void absentRowsAndDeletes() throws Exception {
        assertThat(missions.findById(404L)).isEmpty();
        assertThat(configs.findById(404L)).isEmpty();
        assertThat(soundings.findById(404L)).isEmpty();
        assertThat(runs.findById(404L)).isEmpty();
        assertThat(missions.findByName("nope")).isEmpty();
        assertThat(missions.deleteById(404L)).isFalse();
        assertThat(runs.deleteById(404L)).isFalse();
        assertThat(states.findHistory(404L, 0)).isEmpty();
    }

    @Test
    @DisplayName("An in-memory database works too, for tests that need no file")
    void inMemoryDatabaseWorks() throws Exception {
        try (Database memory = Database.openInMemory()) {
            assertThat(new SchemaInitializer(memory).initialise()).isTrue();
            assertThat(memory.foreignKeysEnforced()).isTrue();
            assertThat(memory.url()).contains(":memory:");
            assertThat(new MissionDao(memory).count()).isZero();
        }
    }
}
