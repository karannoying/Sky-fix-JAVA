package com.skyfix.core.flight;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.core.atmos.SoundingWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Distribution;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.ConvergenceException;
import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The ensemble runner: correctness, reproducibility (T-R1) and timing (T-P1).
 *
 * <p>The timing tests carry {@code @Tag("perf")} and run under {@code -Pperf}, as the blueprint
 * specifies, so the everyday suite stays quick.
 */
class EnsembleRunnerTest {

    private static final Ussa1976Atmosphere ATMOSPHERE = new Ussa1976Atmosphere();
    private static final ConstantWindField WIND = new ConstantWindField(12.0, -4.0);
    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);

    /**
     * A sounding-backed wind field for the timing tests.
     *
     * <p>NFR-1's budget is for a real run, and a real run interpolates a sounding: every
     * derivative evaluation does a binary search over the profile, which a constant wind does not.
     * Timing against {@link ConstantWindField} would flatter the result by roughly a factor of
     * two, so the perf tests use this instead.
     */
    static SoundingWindField soundingWind() throws Exception {
        List<SoundingLevel> levels = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            double height = i * 900.0;
            double jet = 45.0 * Math.exp(-Math.pow((height - 12_000.0) / 4_200.0, 2));
            double speed = Math.max(1.0, 6.0 + jet - 12.0 * Math.max(0, (height - 22_000) / 10_000.0));
            double direction = (250.0 + 40.0 * height / 30_000.0) % 360.0;
            levels.add(SoundingLevel.fromMeteorological(height, 101_325 * Math.exp(-height / 7_640.0),
                    Math.max(200.0, 288.15 - 0.0065 * Math.min(height, 11_000)), direction, speed));
        }
        return SoundingWindField.of("PERF", levels);
    }

    static BalloonConfig config() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g").payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4).gas(LiftGas.HELIUM)
                .build();
    }

    static SimSettings settings() throws Exception {
        return SimSettings.builder()
                .groundElevationM(LAUNCH.altitudeM())
                .stateSampleStride(40)
                .build();
    }

    @Test
    @DisplayName("An ensemble disperses: members land in different places, around the nominal")
    void ensembleProducesASpread() throws Exception {
        BalloonConfig config = config();
        EnsembleRunner.Result result = new EnsembleRunner(ATMOSPHERE, WIND)
                .run(config, DispersionSpec.preflightDefault(config), settings(), LAUNCH,
                        80, 42L, 1);

        Ensemble ensemble = result.ensemble();
        assertThat(ensemble.members()).hasSize(80);
        assertThat(ensemble.successCount()).isEqualTo(80);
        assertThat(ensemble.failureCount()).isZero();
        assertThat(ensemble.failureRate()).isZero();

        List<GeoPoint> landings = ensemble.landingPoints();
        assertThat(landings).hasSize(80);
        assertThat(landings.stream().map(GeoPoint::latitudeDeg).distinct())
                .as("members must not all land on the same point").hasSizeGreaterThan(70);

        // A dispersed ensemble straddles the nominal flight rather than sitting to one side.
        GeoPoint nominal = new FlightSimulator(ATMOSPHERE, WIND)
                .run(config, settings(), LAUNCH).landingPoint().orElseThrow();
        assertThat(landings.stream().anyMatch(p -> p.latitudeDeg() > nominal.latitudeDeg()))
                .isTrue();
        assertThat(landings.stream().anyMatch(p -> p.latitudeDeg() < nominal.latitudeDeg()))
                .isTrue();

        assertThat(ensemble.meanBurstAltitudeM()).isBetween(20_000.0, 40_000.0);
        assertThat(ensemble.wallClockMs()).isNotNegative();
    }

    @Test
    @DisplayName("T-R1: the same seed reproduces identical ellipse parameters to 1e-9")
    void sameSeedReproducesIdenticalEllipse() throws Exception {
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.preflightDefault(config);

        // Different pool sizes on purpose: if any result depended on completion order, the two
        // runs would diverge here. That is exactly what ADR-6 exists to prevent.
        LandingEllipse first = fitFrom(new EnsembleRunner(ATMOSPHERE, WIND, 1)
                .run(config, spec, settings(), LAUNCH, 60, 20260914L, 0));
        LandingEllipse second = fitFrom(new EnsembleRunner(ATMOSPHERE, WIND, 4)
                .run(config, spec, settings(), LAUNCH, 60, 20260914L, 0));

        assertThat(second.semiMajorM()).isEqualTo(first.semiMajorM(), within(1e-9));
        assertThat(second.semiMinorM()).isEqualTo(first.semiMinorM(), within(1e-9));
        assertThat(second.azimuthDeg()).isEqualTo(first.azimuthDeg(), within(1e-9));
        assertThat(second.centre().latitudeDeg())
                .isEqualTo(first.centre().latitudeDeg(), within(1e-9));
        assertThat(second.centre().longitudeDeg())
                .isEqualTo(first.centre().longitudeDeg(), within(1e-9));

        System.out.printf("T-R1: 1-thread and 4-thread runs agree — "
                        + "semi-major %.6f m, semi-minor %.6f m, azimuth %.6f deg%n",
                first.semiMajorM(), first.semiMinorM(), first.azimuthDeg());
    }

    @Test
    @DisplayName("T-R1: member k gets the same parameters regardless of pool size")
    void memberParametersAreIndependentOfScheduling() throws Exception {
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.preflightDefault(config);
        Ensemble single = new EnsembleRunner(ATMOSPHERE, WIND, 1)
                .run(config, spec, settings(), LAUNCH, 40, 7L, 0).ensemble();
        Ensemble parallel = new EnsembleRunner(ATMOSPHERE, WIND, 4)
                .run(config, spec, settings(), LAUNCH, 40, 7L, 0).ensemble();

        for (int k = 0; k < 40; k++) {
            assertThat(parallel.members().get(k).parameters())
                    .as("member %d parameters", k)
                    .isEqualTo(single.members().get(k).parameters());
            assertThat(parallel.members().get(k).index()).isEqualTo(k);
        }
    }

    @Test
    @DisplayName("A different seed gives a different ensemble, but a comparable footprint")
    void differentSeedGivesDifferentButComparableFootprint() throws Exception {
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.preflightDefault(config);
        // 200 members, not fewer: this test's whole subject is that the footprint is stable
        // across seeds at a moderate sample size, so shrinking it would weaken the claim rather
        // than just speed the test up.
        LandingEllipse a = fitFrom(new EnsembleRunner(ATMOSPHERE, WIND)
                .run(config, spec, settings(), LAUNCH, 200, 1L, 0));
        LandingEllipse b = fitFrom(new EnsembleRunner(ATMOSPHERE, WIND)
                .run(config, spec, settings(), LAUNCH, 200, 2L, 0));

        assertThat(b.semiMajorM()).isNotEqualTo(a.semiMajorM());
        // Latin-hypercube stratification is meant to make the footprint stable across seeds; if
        // two seeds gave wildly different areas the sample size would be too small to trust.
        assertThat(b.areaKm2()).isCloseTo(a.areaKm2(), within(0.25 * a.areaKm2()));
    }

    @Test
    @DisplayName("Trajectories are retained only for the sampled members (BLUEPRINT §9)")
    void onlySampledMembersRetainTrajectories() throws Exception {
        BalloonConfig config = config();
        EnsembleRunner.Result result = new EnsembleRunner(ATMOSPHERE, WIND)
                .run(config, DispersionSpec.preflightDefault(config), settings(), LAUNCH,
                        50, 3L, 5);

        assertThat(result.histories()).hasSize(5);
        assertThat(result.historyOf(0)).isPresent();
        assertThat(result.historyOf(4)).isPresent();
        assertThat(result.historyOf(5)).as("beyond the sample, nothing is kept").isEmpty();
        assertThat(result.historyOf(49)).isEmpty();
        assertThat(result.historyOf(0).orElseThrow().landing()).isPresent();
    }

    @Test
    @DisplayName("BLUEPRINT §12: a few bad members are discarded, but a broken run raises")
    void failureThresholdIsEnforced() throws Exception {
        BalloonConfig config = config();
        // Force every member to fail by dispersing the burst diameter below the launch diameter,
        // so the envelope is already past burst at release and the flight cannot proceed as an
        // ascent. The run must refuse rather than return an ellipse fitted to nothing.
        DispersionSpec broken = DispersionSpec.around(config)
                .freeLiftKg(new Distribution.Fixed(-1.0))   // non-buoyant: every member fails
                .build();

        assertThatThrownBy(() -> new EnsembleRunner(ATMOSPHERE, WIND)
                .run(config, broken, settings(), LAUNCH, 20, 1L, 0))
                .isInstanceOfSatisfying(ConvergenceException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(5);
                    assertThat(e.getMessage()).contains("members failed")
                            .contains("threshold");
                    assertThat(e.context()).containsEntry("member_count", "20");
                });
    }

    @Test
    @DisplayName("A member count below one is rejected by name")
    void memberCountValidated() throws Exception {
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.preflightDefault(config);
        assertThatThrownBy(() -> new EnsembleRunner(ATMOSPHERE, WIND)
                .run(config, spec, settings(), LAUNCH, 0, 1L, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("member_count");
        assertThatThrownBy(() -> new EnsembleRunner(ATMOSPHERE, WIND, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The pool is shut down even when the run fails")
    void poolIsAlwaysShutDown() throws Exception {
        // Thread names are prefixed, so a leak shows up as live skyfix-member-* threads after the
        // call returns. A pool left running would keep the JVM alive after the CLI finished.
        BalloonConfig config = config();
        DispersionSpec broken = DispersionSpec.around(config)
                .freeLiftKg(new Distribution.Fixed(-1.0)).build();
        try {
            new EnsembleRunner(ATMOSPHERE, WIND).run(config, broken, settings(), LAUNCH,
                    10, 1L, 0);
        } catch (ConvergenceException expected) {
            // the point of this test is what happens afterwards
        }
        Thread.sleep(200);
        long alive = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("skyfix-member-"))
                .filter(Thread::isAlive)
                .count();
        assertThat(alive).as("no worker threads may outlive the run").isZero();
    }

    @Test
    @Tag("perf")
    @DisplayName("T-P1: 1,000 members finish inside 30 s on the available cores")
    void thousandMembersInsideThirtySeconds() throws Exception {
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.preflightDefault(config);
        EnsembleRunner runner = new EnsembleRunner(ATMOSPHERE, soundingWind());

        // NFR-1 specifies a warm JVM, so the measurement follows a warm-up pass.
        runner.run(config, spec, settings(), LAUNCH, 200, 1L, 0);

        long[] runs = new long[3];
        for (int i = 0; i < 3; i++) {
            runs[i] = runner.run(config, spec, settings(), LAUNCH, 1000, 42L, 20)
                    .ensemble().wallClockMs();
        }
        java.util.Arrays.sort(runs);
        long median = runs[1];

        System.out.printf("T-P1: 1,000 members on %d threads — %d / %d / %d ms, median %d ms "
                        + "(budget 30,000 ms)%n",
                runner.threadCount(), runs[0], runs[1], runs[2], median);
        assertThat(median).as("1,000-member ensemble, 3-run median").isLessThan(30_000L);
    }

    @Test
    @Tag("perf")
    @DisplayName("T-P2: a 200-member re-prediction finishes inside 5 s")
    void twoHundredMemberRepredictionInsideFiveSeconds() throws Exception {
        // FR-3.3 needs a re-prediction fast enough that a 1 Hz log replayed at 10x drops no
        // updates. Measuring it now means the estimator does not discover the budget in week 8.
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.preflightDefault(config);
        EnsembleRunner runner = new EnsembleRunner(ATMOSPHERE, soundingWind());
        runner.run(config, spec, settings(), LAUNCH, 100, 1L, 0);

        long elapsed = runner.run(config, spec, settings(), LAUNCH, 200, 5L, 0)
                .ensemble().wallClockMs();
        System.out.printf("T-P2: 200-member re-prediction — %d ms (budget 5,000 ms)%n", elapsed);
        assertThat(elapsed).isLessThan(5_000L);
    }

    private static LandingEllipse fitFrom(EnsembleRunner.Result result) throws Exception {
        return EllipseFitter.fit(result.ensemble().landingPoints(), 0.95, LAUNCH.altitudeM());
    }
}
