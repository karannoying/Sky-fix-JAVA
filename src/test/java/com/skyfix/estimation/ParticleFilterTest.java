package com.skyfix.estimation;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Distribution;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.Phase;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;
import com.skyfix.io.CsvTelemetryReader;
import com.skyfix.io.SyntheticFlightWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The particle filter (FR-3.2, ADR-3).
 *
 * <p>The recovery test here is a fast single-flight smoke test on the quantity the filter actually
 * resolves — burst altitude. The full twenty-flight T-V5 evaluation is a separate, slower thing;
 * ADR-3 records why ascent Cd is reported rather than gated.
 */
class ParticleFilterTest {

    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);
    private static final Instant LAUNCH_EPOCH = Instant.parse("2026-09-14T04:30:00Z");

    private static BalloonConfig config;
    private static SimSettings settings;
    private static FlightSimulator simulator;

    @BeforeAll
    static void setUp() throws SkyfixException {
        config = BalloonConfig.builder()
                .name("test-1200g")
                .payloadMassKg(1.2)
                .envelopeMassKg(1.2)
                .launchDiameterM(1.8)
                .burstDiameterM(7.0)
                .freeLiftKg(1.1)
                .ascentCd(0.45)
                .chuteAreaM2(1.0)
                .chuteCd(1.4)
                .gas(LiftGas.HELIUM)
                .build();
        settings = SimSettings.builder().groundElevationM(500.0).build();
        simulator = new FlightSimulator(new Ussa1976Atmosphere(),
                new ConstantWindField(12.0, -4.0));
    }

    private ParticleFilter.Builder filterBuilder() throws SkyfixException {
        return ParticleFilter.builder()
                .config(config)
                .simulator(simulator)
                .settings(settings)
                .prior(DispersionSpec.preflightDefault(config))
                .seed(20260914L);
    }

    @Test
    @DisplayName("T-U-FILTER: recovers burst altitude from a noisy synthetic flight")
    void recoversBurstAltitude(@TempDir Path dir) throws Exception {
        FlightParameters truth = new FlightParameters(1.0119, 0.4409, 7.7528, 1.3050, 1.0);
        Path log = dir.resolve("flight.csv");
        var written = new SyntheticFlightWriter(new Ussa1976Atmosphere(),
                new ConstantWindField(12.0, -4.0))
                .write(log, config, truth, settings, LAUNCH, LAUNCH_EPOCH,
                        NoiseSpec.standard(), 20260914L);

        ParticleFilter filter = filterBuilder()
                .particleCount(60)
                .measurementModel(GaussianMeasurementModel.standard()
                        .withWindDrift(LAUNCH, 0.20))
                .build();

        Posterior posterior = replay(filter, log, 40);

        // What the filter is actually being asked for: where the envelope failed.
        FlightParameters recovered = posterior.medianParameters(config.burstDiameterM(), 1.0);
        double recoveredBurstM = simulator.run(config, recovered, settings, LAUNCH)
                .burstAltitudeM().orElseThrow();
        assertThat(Math.abs(recoveredBurstM - written.burstAltitudeM()))
                .as("burst altitude error against T-V5's 500 m, from a 10 m GPS and a 10%% prior")
                .isLessThan(500.0);

        // Parachute drag is the one parameter the descent determines on its own: the mass is
        // known, so the terminal rate maps straight onto it.
        assertThat(posterior.band(Posterior.CHUTE_CD).median())
                .isCloseTo(truth.chuteCd(), org.assertj.core.api.Assertions.within(0.15));
    }

    @Test
    @DisplayName("the burst-scale prior stays open until burst is observed, then collapses")
    void burstScaleStaysOpenUntilBurst(@TempDir Path dir) throws Exception {
        FlightParameters truth = new FlightParameters(1.0119, 0.4409, 7.7528, 1.3050, 1.0);
        Path log = dir.resolve("flight.csv");
        new SyntheticFlightWriter(new Ussa1976Atmosphere(), new ConstantWindField(12.0, -4.0))
                .write(log, config, truth, settings, LAUNCH, LAUNCH_EPOCH, NoiseSpec.standard(),
                        20260914L);

        ParticleFilter filter = filterBuilder().particleCount(60).build();
        TelemetrySeries series = new CsvTelemetryReader().read(log);
        List<Observation> stream = Observation.streamOf(series);
        BurstDetector detector = new BurstDetector();

        filter.start(LAUNCH, stream.get(0).epochUtc());
        double widthBeforeBurst = Double.NaN;
        for (int i = 0; i < stream.size(); i += 40) {
            for (int j = i; j < Math.min(i + 40, series.size()); j++) {
                if (detector.observe(series.samples().get(j)).isPresent()) {
                    filter.burstObserved();
                }
            }
            Posterior p = filter.update(stream.get(i));
            if (!filter.hasObservedBurst()) {
                widthBeforeBurst = p.band(Posterior.BURST_SCALE).width();
            }
        }

        // Before burst the parameter is unobservable, so the band must still be a real band; after
        // burst the one measurement of it collapses the band hard. ADR-3 §4.
        assertThat(widthBeforeBurst)
                .as("burst-scale band is still open at the last pre-burst update")
                .isGreaterThan(0.05);
        assertThat(filter.posterior().band(Posterior.BURST_SCALE).width())
                .as("burst-scale band collapses once the telemetry has shown a burst")
                .isLessThan(widthBeforeBurst / 5.0);
    }

    @Test
    @DisplayName("no ascending particle ever holds a burst diameter it has already passed")
    void censoredRedrawRespectsTheEnvelope(@TempDir Path dir) throws Exception {
        FlightParameters truth = new FlightParameters(1.0119, 0.4409, 7.7528, 1.3050, 1.0);
        Path log = dir.resolve("flight.csv");
        new SyntheticFlightWriter(new Ussa1976Atmosphere(), new ConstantWindField(12.0, -4.0))
                .write(log, config, truth, settings, LAUNCH, LAUNCH_EPOCH, NoiseSpec.standard(),
                        20260914L);

        ParticleFilter filter = filterBuilder().particleCount(60).build();
        List<Observation> stream = Observation.streamOf(new CsvTelemetryReader().read(log));
        filter.start(LAUNCH, stream.get(0).epochUtc());

        for (int i = 0; i < stream.size(); i += 40) {
            filter.update(stream.get(i));
            for (Particle p : filter.particles()) {
                if (p.state().phase() == Phase.ASCENT) {
                    // An envelope past its own burst diameter would have burst. A redraw that
                    // ignored the censoring would produce exactly this, and the flight would be
                    // lost without any test failing. ADR-3 §4.
                    assertThat(p.state().diameterM())
                            .as("ascending particle's envelope against its own burst diameter")
                            .isLessThanOrEqualTo(p.parameters().burstDiameterM() + 1e-9);
                }
            }
        }
    }

    @Test
    @DisplayName("the same seed reproduces the same posterior exactly")
    void isReproducible(@TempDir Path dir) throws Exception {
        FlightParameters truth = new FlightParameters(1.0119, 0.4409, 7.7528, 1.3050, 1.0);
        Path log = dir.resolve("flight.csv");
        new SyntheticFlightWriter(new Ussa1976Atmosphere(), new ConstantWindField(12.0, -4.0))
                .write(log, config, truth, settings, LAUNCH, LAUNCH_EPOCH, NoiseSpec.standard(),
                        20260914L);

        Posterior first = replay(filterBuilder().particleCount(40).build(), log, 80);
        Posterior second = replay(filterBuilder().particleCount(40).build(), log, 80);

        for (String name : first.parameters().keySet()) {
            assertThat(second.band(name).median())
                    .as("median of " + name)
                    .isEqualTo(first.band(name).median());
        }
        assertThat(second.effectiveSampleSize()).isEqualTo(first.effectiveSampleSize());
        assertThat(second.resampleCount()).isEqualTo(first.resampleCount());
    }

    @Test
    @DisplayName("a prior with no width in an estimated dimension is refused, naming the field")
    void refusesADegeneratePrior() throws Exception {
        DispersionSpec flat = DispersionSpec.around(config).build();
        assertThatThrownBy(() -> filterBuilder().prior(flat).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("has no width");
    }

    @Test
    @DisplayName("builder validation names the missing or out-of-range field")
    void builderValidatesItsFields() throws Exception {
        assertThatThrownBy(() -> ParticleFilter.builder().build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("balloon configuration");
        assertThatThrownBy(() -> filterBuilder().particleCount(1).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least two particles");
        assertThatThrownBy(() -> filterBuilder().resampleThresholdFraction(1.5).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("(0, 1]");
        assertThatThrownBy(() -> filterBuilder().shrinkage(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("(0, 1]");
    }

    @Test
    @DisplayName("an observation before the launch epoch is refused, naming both epochs")
    void refusesAnObservationBeforeLaunch() throws Exception {
        ParticleFilter filter = filterBuilder().particleCount(8).build();
        filter.start(LAUNCH, LAUNCH_EPOCH);
        Observation early = new Observation(LAUNCH_EPOCH.minusSeconds(30), 23.26, 77.41, 500.0,
                0.0, false);
        assertThatThrownBy(() -> filter.update(early))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("precedes the launch epoch");
    }

    @Test
    @DisplayName("update before start is a programmer error, not a user error")
    void refusesUpdateBeforeStart() throws Exception {
        ParticleFilter filter = filterBuilder().particleCount(8).build();
        assertThatThrownBy(() -> filter.update(
                new Observation(LAUNCH_EPOCH, 23.26, 77.41, 500.0, 0.0, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the prior set starts spread across the prior, not clustered on its mean")
    void priorSetSpansThePrior() throws Exception {
        ParticleFilter filter = filterBuilder().particleCount(200).build();
        filter.start(LAUNCH, LAUNCH_EPOCH);

        Distribution liftPrior = DispersionSpec.preflightDefault(config).freeLiftKg();
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (Particle p : filter.particles()) {
            low = Math.min(low, p.parameters().freeLiftKg());
            high = Math.max(high, p.parameters().freeLiftKg());
            assertThat(p.state().phase()).isEqualTo(Phase.ASCENT);
            assertThat(p.state().timeSeconds()).isZero();
        }
        // Latin hypercube over 200 members should reach well into both tails.
        assertThat(high - low).isGreaterThan(4.0 * liftPrior.spread());
        assertThat(low).isGreaterThanOrEqualTo(liftPrior.minimum());
        assertThat(high).isLessThanOrEqualTo(liftPrior.maximum());
    }

    /** Replays a log into a filter at a given sample stride and returns the final posterior. */
    private static Posterior replay(ParticleFilter filter, Path log, int stride)
            throws SkyfixException {
        TelemetrySeries series = new CsvTelemetryReader().read(log);
        List<Observation> stream = Observation.streamOf(series);
        BurstDetector detector = new BurstDetector();

        filter.start(LAUNCH, stream.get(0).epochUtc());
        Posterior posterior = filter.posterior();
        for (int i = 0; i < stream.size(); i += stride) {
            for (int j = i; j < Math.min(i + stride, series.size()); j++) {
                if (detector.observe(series.samples().get(j)).isPresent()) {
                    filter.burstObserved();
                }
            }
            posterior = filter.update(stream.get(i));
        }
        return posterior;
    }

    @Test
    @DisplayName("advanceTo in legs reproduces one continuous run")
    void advanceToMatchesAContinuousRun() throws Exception {
        FlightParameters truth = FlightParameters.nominal(config);
        StateHistory continuous = simulator.run(config, truth, settings, LAUNCH);

        BalloonState state = simulator.launchState(config, truth, LAUNCH);
        for (double t = 100.0; t <= 3000.0; t += 100.0) {
            state = simulator.advanceTo(config, truth, settings, state, t);
        }

        // The legs land on step boundaries, so the integration is the identical sequence of steps
        // and the two should agree to rounding, not merely to a tolerance.
        BalloonState reference = null;
        for (BalloonState s : continuous) {
            if (Math.abs(s.timeSeconds() - 3000.0) < 1e-9) {
                reference = s;
            }
        }
        assertThat(reference).as("continuous run retains a state at t = 3000 s").isNotNull();
        assertThat(state.altitudeM()).isCloseTo(reference.altitudeM(),
                org.assertj.core.api.Assertions.within(1e-6));
        assertThat(state.verticalRateMs()).isCloseTo(reference.verticalRateMs(),
                org.assertj.core.api.Assertions.within(1e-9));
        assertThat(state.diameterM()).isCloseTo(reference.diameterM(),
                org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("advanceTo past the landing returns the landed state and then stands still")
    void advanceToStopsAtLanding() throws Exception {
        FlightParameters truth = FlightParameters.nominal(config);
        BalloonState landed = simulator.advanceTo(config, truth, settings,
                simulator.launchState(config, truth, LAUNCH), 20_000.0);
        assertThat(landed.phase()).isEqualTo(Phase.LANDED);
        assertThat(landed.altitudeM()).isCloseTo(500.0,
                org.assertj.core.api.Assertions.within(1e-6));

        BalloonState again = simulator.advanceTo(config, truth, settings, landed, 30_000.0);
        assertThat(again).isEqualTo(landed);
    }
}
