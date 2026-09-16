package com.skyfix.persistence;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.flight.EllipseFitter;
import com.skyfix.core.flight.EnsembleRunner;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-D1 extended to the ensemble: a footprint must re-materialise from the database without
 * re-running a single flight (FR-4.3).
 *
 * <p>That is the whole reason the full landing set is stored rather than just the fitted ellipse —
 * a later re-analysis can re-fit at a different confidence, or recompute containment, from the
 * stored members alone.
 */
class EnsemblePersistenceTest {

    @TempDir
    Path tempDir;

    private Database database;
    private Mission mission;
    private StoredBalloonConfig config;
    private RunRecord run;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.openFile(tempDir.resolve("ensemble.db"));
        new SchemaInitializer(database).initialise();
        mission = new MissionDao(database).save(new Mission(null, "HabSat-1",
                new GeoPoint(23.2599, 77.4126, 500.0), 500.0, "2026-09-14T04:30:00Z"));
        config = new BalloonConfigDao(database)
                .findOrSave(mission.id(), PersistenceTest.config());
        run = new RunDao(database).save(new RunRecord(null, mission.id(), config.id(), null, null,
                "PREFLIGHT", "RK4", 0.25, 40, 42L, "sha", config.config().configHash(), 4,
                Instant.now().toString(), null, RunRecord.RUNNING));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    private Ensemble ensemble(int members) throws Exception {
        BalloonConfig balloon = PersistenceTest.config();
        return new EnsembleRunner(new Ussa1976Atmosphere(), new ConstantWindField(11.0, -3.0))
                .run(balloon, DispersionSpec.preflightDefault(balloon),
                        SimSettings.builder().groundElevationM(500.0)
                                .stateSampleStride(Integer.MAX_VALUE).build(),
                        mission.launch(), members, 42L, 0)
                .ensemble();
    }

    @Test
    @DisplayName("T-D1: an ensemble and its ellipses round-trip through the database")
    void ensembleRoundTrips() throws Exception {
        Ensemble original = ensemble(40);
        EnsembleDao dao = new EnsembleDao(database);

        assertThat(dao.saveAll(run.id(), original, "2026-09-14T06:44:00Z")).isEqualTo(40);
        assertThat(dao.countForRun(run.id())).isEqualTo(40);

        Ensemble back = dao.findForRun(run.id(), 42L);
        assertThat(back.members()).hasSize(40);
        assertThat(back.successCount()).isEqualTo(original.successCount());

        for (int i = 0; i < 40; i++) {
            Ensemble.Member before = original.members().get(i);
            Ensemble.Member after = back.members().get(i);
            assertThat(after.index()).isEqualTo(before.index());
            assertThat(after.parameters().freeLiftKg())
                    .isEqualTo(before.parameters().freeLiftKg(), within(1e-9));
            assertThat(after.parameters().ascentCd())
                    .isEqualTo(before.parameters().ascentCd(), within(1e-9));
            assertThat(after.parameters().windScale())
                    .isEqualTo(before.parameters().windScale(), within(1e-9));
            assertThat(after.landing().orElseThrow().latitudeDeg())
                    .isEqualTo(before.landing().orElseThrow().latitudeDeg(), within(1e-9));
        }
    }

    @Test
    @DisplayName("T-D1: the ellipse re-fitted from stored members matches the original")
    void ellipseCanBeRefittedFromStoredMembers() throws Exception {
        // This is the property that justifies storing every landing point rather than only the
        // fitted ellipse: a later analysis can re-derive the footprint, or fit it at a different
        // confidence, without re-running a single flight.
        Ensemble original = ensemble(60);
        LandingEllipse fitted = EllipseFitter.fit(original.landingPoints(), 0.95, 500.0);

        new EnsembleDao(database).saveAll(run.id(), original, null);
        new EllipseDao(database).saveAll(run.id(),
                List.of(EllipseFitter.fit(original.landingPoints(), 0.50, 500.0), fitted), null);

        Ensemble back = new EnsembleDao(database).findForRun(run.id(), 42L);
        LandingEllipse refitted = EllipseFitter.fit(back.landingPoints(), 0.95, 500.0);

        assertThat(refitted.semiMajorM()).isEqualTo(fitted.semiMajorM(), within(1e-6));
        assertThat(refitted.semiMinorM()).isEqualTo(fitted.semiMinorM(), within(1e-6));
        assertThat(refitted.azimuthDeg()).isEqualTo(fitted.azimuthDeg(), within(1e-6));

        // A confidence level nobody stored can still be produced from the same rows.
        LandingEllipse ninetyNine = EllipseFitter.fit(back.landingPoints(), 0.99, 500.0);
        assertThat(ninetyNine.semiMajorM()).isGreaterThan(fitted.semiMajorM());
    }

    @Test
    @DisplayName("Stored ellipses come back with their axes, azimuth and area intact")
    void ellipsesRoundTrip() throws Exception {
        Ensemble original = ensemble(50);
        List<LandingEllipse> stored = List.of(
                EllipseFitter.fit(original.landingPoints(), 0.50, 500.0),
                EllipseFitter.fit(original.landingPoints(), 0.95, 500.0));
        new EllipseDao(database).saveAll(run.id(), stored, null);

        List<LandingEllipse> back = new EllipseDao(database).findForRun(run.id(), 500.0);
        assertThat(back).hasSize(2);
        assertThat(back.get(0).confidence()).isEqualTo(0.50);
        assertThat(back.get(1).confidence()).isEqualTo(0.95);
        assertThat(back.get(1).semiMajorM())
                .isEqualTo(stored.get(1).semiMajorM(), within(1e-9));
        assertThat(back.get(1).azimuthDeg())
                .isEqualTo(stored.get(1).azimuthDeg(), within(1e-9));
        assertThat(back.get(1).areaKm2())
                .isCloseTo(stored.get(1).areaKm2(), within(1e-6 * stored.get(1).areaKm2()));
    }

    @Test
    @DisplayName("Failed members are stored with null landings, so the failure count survives")
    void failedMembersAreStoredNotDropped() throws Exception {
        // BLUEPRINT §12 wants failures visible. A shorter member list would hide them; a row with
        // null landing columns does not.
        Ensemble withFailures = new Ensemble(List.of(
                Ensemble.Member.landed(0,
                        com.skyfix.domain.FlightParameters.nominal(PersistenceTest.config()),
                        new GeoPoint(23.0, 78.0, 500.0), 28_000.0),
                Ensemble.Member.failed(1,
                        com.skyfix.domain.FlightParameters.nominal(PersistenceTest.config()),
                        "never reached the ground")), 42L, 100L);

        new EnsembleDao(database).saveAll(run.id(), withFailures, null);
        Ensemble back = new EnsembleDao(database).findForRun(run.id(), 42L);

        assertThat(back.members()).hasSize(2);
        assertThat(back.successCount()).isEqualTo(1);
        assertThat(back.failureCount()).isEqualTo(1);
        assertThat(back.members().get(1).landing()).isEmpty();
        assertThat(back.failureRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("T-D3: ensemble members and ellipses cascade away with their run")
    void ensembleRowsCascadeWithTheRun() throws Exception {
        new EnsembleDao(database).saveAll(run.id(), ensemble(20), null);
        new EllipseDao(database).saveAll(run.id(),
                List.of(EllipseFitter.fit(ensemble(20).landingPoints(), 0.95, 500.0)), null);

        assertThat(new EnsembleDao(database).countForRun(run.id())).isEqualTo(20);
        assertThat(new RunDao(database).deleteById(run.id())).isTrue();
        assertThat(new EnsembleDao(database).countForRun(run.id())).isZero();
        assertThat(new EllipseDao(database).findForRun(run.id(), 500.0)).isEmpty();
    }

    @Test
    @DisplayName("The schema refuses an ellipse whose minor axis exceeds its major")
    void schemaEnforcesEllipseInvariants() {
        // semi_major_m >= semi_minor_m is a CHECK as well as a record invariant, so a row that
        // bypassed the Java type would still be refused.
        assertThatThrownBy(() -> database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO landing_ellipse (run_id, confidence, center_lat, center_lon, "
                            + "semi_major_m, semi_minor_m, azimuth_deg, area_km2) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, run.id());
                ps.setDouble(2, 0.95);
                ps.setDouble(3, 23.0);
                ps.setDouble(4, 78.0);
                ps.setDouble(5, 100.0);   // major
                ps.setDouble(6, 500.0);   // minor, larger: forbidden
                ps.setDouble(7, 45.0);
                ps.setDouble(8, 1.0);
                return ps.executeUpdate();
            }
        })).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("Re-reading a run with no ensemble gives an empty result, not a failure")
    void absentEnsembleIsEmpty() throws Exception {
        assertThat(new EnsembleDao(database).findForRun(9999L, 1L).members()).isEmpty();
        assertThat(new EnsembleDao(database).countForRun(9999L)).isZero();
        assertThat(new EllipseDao(database).findForRun(9999L, 0.0)).isEmpty();
    }
}
