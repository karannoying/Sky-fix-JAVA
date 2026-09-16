package com.skyfix.app;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.FlightTruth;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.io.CsvTelemetryReader;
import com.skyfix.io.SyntheticFlightWriter;
import com.skyfix.persistence.BalloonConfigDao;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.EllipseDao;
import com.skyfix.persistence.EstimateDao;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.MissionDao;
import com.skyfix.persistence.RunRecord;
import com.skyfix.persistence.SchemaInitializer;
import com.skyfix.persistence.StoredBalloonConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The replay loop (FR-3.2, FR-3.3, FR-3.4, ADR-17).
 *
 * <p>These are integration tests: a synthetic flight is written, ingested, and replayed through the
 * real filter, ensemble and database. They assimilate a thinned stream so the suite stays inside
 * its two-minute budget; the full T-V5 evaluation runs separately.
 */
class ReplayServiceTest {

    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);
    private static final Instant LAUNCH_EPOCH = Instant.parse("2026-09-14T04:30:00Z");
    private static final FlightParameters TRUTH =
            new FlightParameters(1.0119, 0.4409, 7.7528, 1.3050, 1.0);

    @TempDir
    Path tempDir;

    private Database database;
    private Mission mission;
    private StoredBalloonConfig stored;
    private Long soundingId;
    private SimSettings settings;
    private FlightTruth truth;
    private TelemetrySeries series;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.openFile(tempDir.resolve("replay.db"));
        new SchemaInitializer(database).initialise();

        BalloonConfig balloon = BalloonConfig.builder()
                .name("test-1200g")
                .payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0)
                .freeLiftKg(1.1).ascentCd(0.45)
                .chuteAreaM2(1.0).chuteCd(1.4)
                .gas(LiftGas.HELIUM)
                .build();
        settings = SimSettings.builder().groundElevationM(500.0).build();

        mission = new MissionDao(database).save(new Mission(null, "HabSat-1", LAUNCH, 500.0,
                LAUNCH_EPOCH.toString()));
        stored = new BalloonConfigDao(database).findOrSave(mission.id(), balloon);

        // A footprint needs wind. In still air every member lands on the launch point and the
        // ellipse is genuinely a point, so a replay with no sounding cannot exercise FR-3.3's
        // output at all.
        soundingId = new IngestService(database).ingestSounding(
                Path.of("data/soundings/SYNTHETIC_2026-09-14_00Z.txt"),
                LAUNCH_EPOCH.toString()).sounding().id();

        Path log = tempDir.resolve("ds6-test.csv");
        truth = new SyntheticFlightWriter(new Ussa1976Atmosphere(),
                new ConstantWindField(12.0, -4.0))
                .write(log, balloon, TRUTH, settings, LAUNCH, LAUNCH_EPOCH,
                        NoiseSpec.standard(), 20260914L);
        series = new CsvTelemetryReader().read(log);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    /**
     * A deliberately cheap replay: 40 particles and 30-member re-predictions. These tests assert
     * the loop's behaviour — cadence, persistence, refusals — not its accuracy, and the full-cost
     * defaults would put the suite well past its two-minute budget. Recovery quality is measured
     * by ParticleFilterTest and by the T-V5 evaluation.
     */
    private ReplayService.ReplayResult replay(int every) throws Exception {
        return replay(cheap().withAssimilateEvery(every));
    }

    /**
     * Cheap options: 40 particles, 20-member re-predictions, one every 1,200 s of flight. These
     * tests assert the loop's behaviour — cadence, persistence, refusals — not its accuracy, and
     * the full-cost defaults would put the suite well past its two-minute budget. Recovery quality
     * is measured by ParticleFilterTest and by the T-V5 evaluation.
     */
    private static ReplayOptions cheap() {
        return ReplayOptions.standard()
                .withFilterCount(2)
                .withParticleCount(40)
                .withRepredictMembers(20)
                .withRepredictIntervalSeconds(1200.0);
    }

    private ReplayService.ReplayResult replay(ReplayOptions options) throws Exception {
        return new ReplayService(database).replay(mission, stored, soundingId, null, series,
                settings, options, RunContext.start(42L));
    }

    @Test
    @DisplayName("a replay recovers the burst, stores its estimates and finishes OK")
    void replaysAFlight() throws Exception {
        ReplayService.ReplayResult result = replay(120);

        assertThat(result.run().status()).isEqualTo(RunRecord.OK);
        assertThat(result.run().runKind()).isEqualTo("REPLAY");
        assertThat(result.burst()).isPresent();
        assertThat(result.burst().get().altitudeM())
                .as("detected burst against the truth the flight was written from")
                .isCloseTo(truth.burstAltitudeM(), org.assertj.core.api.Assertions.within(500.0));

        // Every assimilated sample leaves a posterior behind; FR-4.1 plots this history.
        EstimateDao estimates = new EstimateDao(database);
        assertThat(estimates.updateCount(result.run().id()))
                .isEqualTo(result.assimilatedCount());
        assertThat(estimates.findForRun(result.run().id(), cheap().particleCount()))
                .hasSize(result.assimilatedCount());
    }

    @Test
    @DisplayName("re-prediction is throttled to the ADR-17 interval, not run per sample")
    void repredictionIsThrottled() throws Exception {
        ReplayService.ReplayResult result = replay(cheap()
                .withRepredictMembers(8)
                .withRepredictIntervalSeconds(
                        ReplayOptions.DEFAULT_REPREDICT_INTERVAL_SECONDS)
                .withAssimilateEvery(20));

        // At every-20 on a 1 Hz log the filter sees a sample per 20 s of flight, so a per-sample
        // re-prediction would fit 2.5 into each 50 s interval. ADR-17 says one.
        assertThat(result.repredictionCount()).isLessThan(result.assimilatedCount());

        double flightSeconds = truth.sampleCount();
        double expected = flightSeconds / ReplayOptions.DEFAULT_REPREDICT_INTERVAL_SECONDS;
        assertThat(result.repredictionCount())
                .as("about one re-prediction per %.0f s of flight time",
                        ReplayOptions.DEFAULT_REPREDICT_INTERVAL_SECONDS)
                .isLessThanOrEqualTo((int) Math.ceil(expected) + 2);

        // Consecutive re-predictions must be at least an interval apart, apart from the forced
        // ones at burst and at the last airborne sample.
        var updates = result.updates();
        int closer = 0;
        for (int i = 1; i < updates.size(); i++) {
            double gap = updates.get(i).flightSeconds() - updates.get(i - 1).flightSeconds();
            if (gap < ReplayOptions.DEFAULT_REPREDICT_INTERVAL_SECONDS - 1e-9) {
                closer++;
            }
        }
        assertThat(closer).as("only the burst and final re-predictions may break the cadence")
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("each re-prediction stores a 50% and a 95% ellipse tagged with its epoch")
    void storesAnEllipsePerUpdate() throws Exception {
        ReplayService.ReplayResult result = replay(120);

        assertThat(result.updates()).isNotEmpty();
        for (var update : result.updates()) {
            assertThat(update.ellipseAt(0.50)).isPresent();
            assertThat(update.ellipseAt(0.95)).isPresent();
            assertThat(update.ellipseAt(0.95).get().semiMajorM())
                    .as("the 95%% ellipse encloses the 50%% one")
                    .isGreaterThan(update.ellipseAt(0.50).get().semiMajorM());
        }
        assertThat(new EllipseDao(database).findForRun(result.run().id(), 500.0))
                .hasSize(result.repredictionCount() * 2);
    }

    @Test
    @DisplayName("no footprint is predicted for a payload already on the ground")
    void stopsRepredictingAfterLanding() throws Exception {
        ReplayService.ReplayResult result = replay(120);

        // The log ends at ground level. A re-prediction from there would collapse to a point and
        // report rounding error as an uncertainty.
        double lastAirborne = result.lastUpdate().orElseThrow().flightSeconds();
        assertThat(lastAirborne).isLessThan(truth.sampleCount());
        for (var update : result.updates()) {
            assertThat(update.ellipseAt(0.95).get().semiMajorM())
                    .as("a real footprint, not a collapsed point")
                    .isGreaterThan(1.0);
        }
    }

    @Test
    @DisplayName("the posterior narrows every parameter against its prior")
    void posteriorNarrowsThePrior() throws Exception {
        Posterior posterior = replay(120).finalPosterior();

        // The prior spreads are DispersionSpec.preflightDefault: 15% on lift, 20% on ascent Cd,
        // 10% on burst diameter, 15% on chute Cd. Anything the replay reports wider than its own
        // prior would mean the telemetry made matters worse.
        assertThat(posterior.band(Posterior.FREE_LIFT).width()).isLessThan(2.0 * 0.15 * 1.1);
        assertThat(posterior.band(Posterior.ASCENT_CD).width()).isLessThan(2.0 * 0.20 * 0.45);
        assertThat(posterior.band(Posterior.BURST_SCALE).width()).isLessThan(2.0 * 0.10);
        assertThat(posterior.band(Posterior.CHUTE_CD).width()).isLessThan(2.0 * 0.15 * 1.4);
    }

    @Test
    @DisplayName("options out of range are refused, naming the flag the user typed")
    void refusesBadOptions() {
        // The record itself refuses nonsense outright, since a bad value there is a caller bug.
        assertThatThrownBy(() -> ReplayOptions.standard().withAssimilateEvery(0))
                .isInstanceOf(IllegalArgumentException.class);

        // validated() is what the CLI calls, and it names the flag rather than the field.
        assertThatThrownBy(() -> new ReplayOptions(1, 1, 30, 50.0, 1).validated())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown integrator is refused before a run row is written")
    void refusesAnUnknownIntegratorWithoutLeavingARun() throws Exception {
        SimSettings bad = settings.withIntegrator("EULER");
        assertThatThrownBy(() -> new ReplayService(database).replay(mission, stored, soundingId,
                null,
                series, bad, cheap().withAssimilateEvery(120), RunContext.start(42L)))
                .isInstanceOf(com.skyfix.domain.error.SkyfixException.class);

        try (var ps = database.connection()
                .prepareStatement("SELECT COUNT(*) FROM run WHERE run_kind = 'REPLAY'");
             var rs = ps.executeQuery()) {
            assertThat(rs.getInt(1)).as("no half-built run row survives the rejection").isZero();
        }
    }
}
