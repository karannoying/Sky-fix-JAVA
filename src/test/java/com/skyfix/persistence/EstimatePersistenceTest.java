package com.skyfix.persistence;

import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Posterior;
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
 * The estimate table (FR-3.2, ADR-2): a replay's posterior history must come back out of the
 * database without re-running the filter.
 */
class EstimatePersistenceTest {

    @TempDir
    Path tempDir;

    private Database database;
    private EstimateDao estimates;
    private RunRecord run;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.openFile(tempDir.resolve("estimate.db"));
        new SchemaInitializer(database).initialise();
        Mission mission = new MissionDao(database).save(new Mission(null, "HabSat-1",
                new GeoPoint(23.2599, 77.4126, 500.0), 500.0, "2026-09-14T04:30:00Z"));
        StoredBalloonConfig config = new BalloonConfigDao(database)
                .findOrSave(mission.id(), PersistenceTest.config());
        run = new RunDao(database).save(new RunRecord(null, mission.id(), config.id(), null, null,
                "REPLAY", "RK4", 0.25, 500, 42L, "sha", config.config().configHash(), 4,
                Instant.now().toString(), null, RunRecord.RUNNING));
        estimates = new EstimateDao(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    @DisplayName("a posterior round-trips as four rows and comes back whole")
    void roundTripsOnePosterior() throws Exception {
        Posterior posterior = posteriorAt("2026-09-14T05:00:00Z", 1.05, 321.5, 7);
        estimates.save(run.id(), posterior);

        List<Posterior> read = estimates.findForRun(run.id(), 500);
        assertThat(read).hasSize(1);
        Posterior back = read.get(0);

        assertThat(back.epochUtc()).isEqualTo(posterior.epochUtc());
        assertThat(back.parameters().keySet()).isEqualTo(posterior.parameters().keySet());
        for (String name : posterior.parameters().keySet()) {
            assertThat(back.band(name).median())
                    .isCloseTo(posterior.band(name).median(), within(1e-12));
            assertThat(back.band(name).p05()).isCloseTo(posterior.band(name).p05(), within(1e-12));
            assertThat(back.band(name).p95()).isCloseTo(posterior.band(name).p95(), within(1e-12));
        }
        assertThat(back.effectiveSampleSize()).isCloseTo(321.5, within(1e-9));
        assertThat(back.resampleCount()).isEqualTo(7);
        assertThat(back.particleCount()).isEqualTo(500);
    }

    @Test
    @DisplayName("a whole replay's history is written in one batch and read back in order")
    void roundTripsAHistory() throws Exception {
        List<Posterior> history = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            history.add(posteriorAt(Instant.parse("2026-09-14T05:00:00Z").plusSeconds(i * 50L)
                    .toString(), 1.0 + i * 0.001, 400.0 - i, i / 4));
        }
        estimates.saveAll(run.id(), history);

        assertThat(estimates.updateCount(run.id())).isEqualTo(40);
        assertThat(estimates.latestEpoch(run.id()))
                .isEqualTo(history.get(39).epochUtc());

        List<Posterior> read = estimates.findForRun(run.id(), 500);
        assertThat(read).hasSize(40);
        // Epochs are stored as ISO-8601 text; the read must come back in chronological order, not
        // in whatever order SQLite happened to write the rows.
        for (int i = 1; i < read.size(); i++) {
            assertThat(read.get(i).epochUtc()).isAfter(read.get(i - 1).epochUtc());
        }
        assertThat(read.get(39).band(Posterior.FREE_LIFT).median())
                .isCloseTo(1.039, within(1e-9));
    }

    @Test
    @DisplayName("an empty history writes nothing rather than opening a transaction")
    void savingNothingIsANoOp() throws Exception {
        estimates.saveAll(run.id(), List.of());
        assertThat(estimates.updateCount(run.id())).isZero();
        assertThat(estimates.latestEpoch(run.id())).isNull();
    }

    @Test
    @DisplayName("an estimate for a run that does not exist is refused by the foreign key")
    void refusesAnOrphanEstimate() {
        assertThatThrownBy(() -> estimates.save(999_999L,
                posteriorAt("2026-09-14T05:00:00Z", 1.0, 100.0, 0)))
                .isInstanceOf(com.skyfix.domain.error.PersistenceException.class);
    }

    @Test
    @DisplayName("deleting a run takes its estimates with it")
    void cascadesFromRun() throws Exception {
        estimates.save(run.id(), posteriorAt("2026-09-14T05:00:00Z", 1.0, 100.0, 0));
        assertThat(estimates.updateCount(run.id())).isEqualTo(1);

        try (var ps = database.connection().prepareStatement("DELETE FROM run WHERE id = ?")) {
            ps.setLong(1, run.id());
            ps.executeUpdate();
        }
        assertThat(estimates.updateCount(run.id())).isZero();
    }

    private static Posterior posteriorAt(String epoch, double lift, double ess, int resamples) {
        return Posterior.of(Instant.parse(epoch),
                new Posterior.Band(lift, lift - 0.05, lift + 0.05),
                new Posterior.Band(0.45, 0.43, 0.47),
                new Posterior.Band(1.02, 0.98, 1.06),
                new Posterior.Band(1.40, 1.35, 1.45),
                ess, resamples, 500);
    }
}
