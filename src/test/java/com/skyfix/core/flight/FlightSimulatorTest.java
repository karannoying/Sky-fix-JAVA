package com.skyfix.core.flight;

import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.Phase;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.Units;
import com.skyfix.domain.error.ConvergenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-V2, T-V3 and T-V4 — the flight model against closed-form physics (FR-2.3, NFR-2).
 *
 * <p>Each oracle is analysis, not another simulation:
 *
 * <ul>
 *   <li><b>T-V2</b> compares the simulated ascent rate at five altitudes against the terminal
 *       velocity where buoyancy, weight and drag balance exactly — solved algebraically here from
 *       the atmosphere state, not read back from the model.</li>
 *   <li><b>T-V3</b> runs the same flight through RK4 and RKF45. The two schemes have different
 *       coefficients, stage counts and orders, so agreement is evidence about the derivative
 *       rather than about either integrator.</li>
 *   <li><b>T-V4</b> recovers the lifting-gas mass from each stored diameter and checks it against
 *       the mass sealed in at launch. The diameter goes into the state record through a cube root
 *       and comes back through a cube, so this exercises that round trip rather than restating
 *       the gas law.</li>
 * </ul>
 */
class FlightSimulatorTest {

    private static final Ussa1976Atmosphere ATMOSPHERE = new Ussa1976Atmosphere();
    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);

    /** A ~30 km HabSat-class flight: 1200 g envelope, 1.2 kg payload, helium. */
    static BalloonConfig habSat() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g")
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
    }

    static SimSettings settings(double step, String integrator) throws Exception {
        return SimSettings.builder()
                .stepSeconds(step)
                .integrator(integrator)
                .groundElevationM(LAUNCH.altitudeM())
                .stateSampleStride(1)
                .endSeconds(6 * 3600)
                .build();
    }

    @Test
    @DisplayName("A nominal flight ascends, bursts and lands, in that order")
    void nominalFlightCompletes() throws Exception {
        StateHistory h = new FlightSimulator(ATMOSPHERE, ConstantWindField.calm())
                .run(habSat(), settings(0.25, "RK4"), LAUNCH);

        assertThat(h.burst()).as("the envelope must burst").isPresent();
        assertThat(h.landing()).as("the payload must land").isPresent();
        assertThat(h.burstAltitudeM()).hasValueSatisfying(
                alt -> assertThat(alt).isBetween(20_000.0, 40_000.0));
        assertThat(h.apogeeM()).isEqualTo(h.burstAltitudeM().orElseThrow(), within(50.0));
        assertThat(h.landing().orElseThrow().altitudeM())
                .isEqualTo(LAUNCH.altitudeM(), within(1e-6));
        assertThat(h.landing().orElseThrow().phase()).isEqualTo(Phase.LANDED);

        // With no wind at all, the payload comes down exactly where it went up.
        assertThat(Geodesy.haversineMetres(LAUNCH, h.landingPoint().orElseThrow()))
                .as("no wind means no drift").isLessThan(1e-6);

        System.out.printf("nominal flight: burst %.0f m at %.0f s, landed at %.0f s%n",
                h.burstAltitudeM().orElseThrow(), h.burst().orElseThrow().timeSeconds(),
                h.landing().orElseThrow().timeSeconds());
    }

    @Test
    @DisplayName("T-V2: ascent rate is within 2 % of the analytic terminal velocity at 5 altitudes")
    void ascentRateMatchesAnalyticTerminalVelocity() throws Exception {
        BalloonConfig config = habSat();
        FlightParameters parameters = FlightParameters.nominal(config);
        StateHistory h = new FlightSimulator(ATMOSPHERE, ConstantWindField.calm())
                .run(config, parameters, settings(0.25, "RK4"), LAUNCH);

        double gasMass = FlightSimulator.gasMassFor(config, parameters,
                ATMOSPHERE.stateAt(LAUNCH.altitudeM()));

        double[] checkAltitudes = {2_000, 8_000, 14_000, 20_000, 26_000};
        double worst = 0.0;

        for (double target : checkAltitudes) {
            BalloonState s = firstAscentStateAbove(h, target);
            AtmosphericState air = ATMOSPHERE.stateAt(s.altitudeM());

            // Terminal velocity: buoyancy - weight - drag = 0, solved for v.
            //   rho V g - m g - 0.5 rho Cd A v^2 = 0
            //   v = sqrt( 2 g (rho V - m) / (rho Cd A) )
            double volume = gasMass * config.gas().specificGasConstant() * air.temperatureK()
                    / air.pressurePa();
            double diameter = Math.cbrt(6.0 * volume / Math.PI);
            double area = Math.PI * diameter * diameter / 4.0;
            double mass = config.dryMassKg() + gasMass;
            double analytic = Math.sqrt(2.0 * Units.STANDARD_GRAVITY
                    * (air.densityKgM3() * volume - mass)
                    / (air.densityKgM3() * parameters.ascentCd() * area));

            double relative = Math.abs(s.verticalRateMs() - analytic) / analytic;
            worst = Math.max(worst, relative);
            System.out.printf("T-V2 %6.0f m: simulated %.3f m/s, analytic %.3f m/s, %.3f %%%n",
                    s.altitudeM(), s.verticalRateMs(), analytic, relative * 100);

            assertThat(relative).as("ascent rate at %.0f m", s.altitudeM()).isLessThan(0.02);
        }
        System.out.printf("T-V2 worst relative error: %.3f %% (tolerance 2 %%)%n", worst * 100);
    }

    @Test
    @DisplayName("T-V3: RK4 and RKF45 land within 50 m of each other at every stable dt <= 1 s")
    void integratorsAgreeOnTheLandingPoint() throws Exception {
        BalloonConfig config = habSat();
        // A real wind, so the two integrations have somewhere to diverge horizontally.
        ConstantWindField wind = new ConstantWindField(12.0, -4.0);

        for (double dt : new double[]{0.5, 0.25, 0.125}) {
            GeoPoint rk4 = new FlightSimulator(ATMOSPHERE, wind)
                    .run(config, settings(dt, "RK4"), LAUNCH).landingPoint().orElseThrow();
            GeoPoint rkf45 = new FlightSimulator(ATMOSPHERE, wind)
                    .run(config, settings(dt, "RKF45"), LAUNCH).landingPoint().orElseThrow();

            double separation = Geodesy.haversineMetres(rk4, rkf45);
            System.out.printf("T-V3 dt=%.3f s: RK4 vs RKF45 landing separation %.3f m "
                    + "(tolerance 50 m)%n", dt, separation);
            assertThat(separation).as("RK4 vs RKF45 landing separation at dt = %.3f s", dt)
                    .isLessThan(50.0);
        }
    }

    @Test
    @DisplayName("ADR-13: RK4 at a 1 s step is refused, naming the largest step that would work")
    void unstableStepIsRefusedRatherThanSilentlyWrong() throws Exception {
        // The blueprint originally specified T-V3 "at dt <= 1 s". Measured, RK4 at a 1 s step
        // leaves its stability region partway down the parachute descent: the landing came out
        // 39 s late and ~470 m off in a 12 m/s wind, with the descent rate visibly oscillating.
        // A wrong answer that does not look wrong is the worst outcome, so the simulator refuses.
        BalloonConfig config = habSat();
        ConstantWindField wind = new ConstantWindField(12.0, -4.0);

        assertThatThrownBy(() -> new FlightSimulator(ATMOSPHERE, wind)
                .run(config, settings(1.0, "RK4"), LAUNCH))
                .isInstanceOfSatisfying(ConvergenceException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(5);
                    assertThat(e.getMessage()).contains("stability limit").contains("RK4");
                    assertThat(e.context()).containsKey("max_stable_step_s");
                    assertThat(Double.parseDouble(e.context().get("max_stable_step_s")))
                            .as("the message must name a step that is actually smaller")
                            .isLessThan(1.0);
                });

        // RKF45's stability region is wider, so the same step is accepted for it. That is the
        // whole asymmetry: the tolerance was never the problem, the method's stability was.
        assertThat(new FlightSimulator(ATMOSPHERE, wind)
                .run(config, settings(1.0, "RKF45"), LAUNCH).landing()).isPresent();
    }

    @Test
    @DisplayName("ADR-13: the stability limits are derived from the methods, not transcribed")
    void stabilityLimitsAreDerivedFromTheMethods() {
        double rk4 = new Rk4Integrator().realAxisStabilityLimit();
        double rkf45 = new Rkf45Integrator().realAxisStabilityLimit();
        System.out.printf("ADR-13: real-axis stability limits — RK4 %.9f, RKF45 %.9f%n",
                rk4, rkf45);

        // Independent check of the bisection: RK4's stability function is the fourth-order
        // truncation of exp(z), so |R(-limit)| must be exactly 1. Evaluating that polynomial
        // directly is a different computation from stepping the integrator.
        double z = -rk4;
        double r = 1 + z + z * z / 2 + z * z * z / 6 + z * z * z * z / 24;
        assertThat(Math.abs(r)).as("|R(z)| at RK4's stability boundary").isEqualTo(1.0, within(1e-9));

        // And just inside the boundary the method must damp, just outside it must grow.
        assertThat(Math.abs(polyRk4(-rk4 * 0.99))).isLessThan(1.0);
        assertThat(Math.abs(polyRk4(-rk4 * 1.01))).isGreaterThan(1.0);

        // RKF45's six stages buy a wider region, which is exactly why it tolerates a step RK4
        // cannot.
        assertThat(rkf45).isGreaterThan(rk4);
    }

    private static double polyRk4(double z) {
        return 1 + z + z * z / 2 + z * z * z / 6 + z * z * z * z / 24;
    }

    @Test
    @DisplayName("T-V4: gas mass recovered from the stored diameter drifts by under 1e-6")
    void gasLawInvariantHoldsAcrossTheFlight() throws Exception {
        BalloonConfig config = habSat();
        FlightParameters parameters = FlightParameters.nominal(config);
        StateHistory h = new FlightSimulator(ATMOSPHERE, new ConstantWindField(8.0, 3.0))
                .run(config, parameters, settings(0.25, "RK4"), LAUNCH);

        double sealedIn = FlightSimulator.gasMassFor(config, parameters,
                ATMOSPHERE.stateAt(LAUNCH.altitudeM()));
        double worst = 0.0;

        for (BalloonState s : h) {
            if (s.phase() != Phase.ASCENT) {
                continue; // after burst the gas is gone and there is nothing to conserve
            }
            AtmosphericState air = ATMOSPHERE.stateAt(s.altitudeM());
            // Recover the gas mass from the diameter that was actually stored: volume from the
            // cube, gas density from the ambient state. A unit slip or a cube-root error anywhere
            // in that path shows up here.
            double volume = BalloonConfig.volumeOfDiameter(s.diameterM());
            double recovered = volume * config.gas().densityAt(air.pressurePa(),
                    air.temperatureK());
            worst = Math.max(worst, Math.abs(recovered - sealedIn) / sealedIn);
        }

        System.out.printf("T-V4: worst gas-mass drift %.3e (tolerance 1e-6)%n", worst);
        assertThat(worst).as("gas-law mass/volume invariant").isLessThan(1e-6);
    }

    @Test
    @DisplayName("Wind advects the payload by exactly speed x time, as arithmetic predicts")
    void constantWindDriftMatchesArithmetic() throws Exception {
        // A constant wind makes the horizontal problem exactly solvable: displacement is the
        // wind vector times the flight duration, independent of the vertical dynamics.
        BalloonConfig config = habSat();
        double eastMs = 15.0;
        double northMs = -6.0;

        StateHistory h = new FlightSimulator(ATMOSPHERE, new ConstantWindField(eastMs, northMs))
                .run(config, settings(0.25, "RK4"), LAUNCH);

        double duration = h.landing().orElseThrow().timeSeconds();
        GeoPoint landing = h.landingPoint().orElseThrow();

        double expectedEast = eastMs * duration;
        double expectedNorth = northMs * duration;
        double actualEast = (landing.longitudeDeg() - LAUNCH.longitudeDeg())
                * Geodesy.metresPerDegreeLongitude(LAUNCH.latitudeDeg());
        double actualNorth = (landing.latitudeDeg() - LAUNCH.latitudeDeg())
                * Geodesy.metresPerDegreeLatitude();

        // The tolerance is 0.5 %: the longitude scale changes slightly as latitude changes over
        // the drift, which arithmetic on a fixed latitude cannot capture.
        assertThat(actualEast).as("eastward drift").isCloseTo(expectedEast,
                within(0.005 * Math.abs(expectedEast)));
        assertThat(actualNorth).as("northward drift").isCloseTo(expectedNorth,
                within(0.005 * Math.abs(expectedNorth)));
    }

    @Test
    @DisplayName("Descent settles onto the analytic parachute terminal velocity")
    void descentMatchesParachuteTerminalVelocity() throws Exception {
        BalloonConfig config = habSat();
        FlightParameters parameters = FlightParameters.nominal(config);
        StateHistory h = new FlightSimulator(ATMOSPHERE, ConstantWindField.calm())
                .run(config, parameters, settings(0.25, "RK4"), LAUNCH);

        DescentPhase descent = new DescentPhase(ATMOSPHERE, ConstantWindField.calm(), config,
                parameters);

        // Sample well below burst, where the parachute has had time to settle.
        for (double target : new double[]{10_000, 5_000, 2_000}) {
            BalloonState s = lastDescentStateAbove(h, target);
            AtmosphericState air = ATMOSPHERE.stateAt(s.altitudeM());
            double analytic = descent.terminalRateMs(air.densityKgM3());
            // At a stable step the parachute tracks its terminal velocity to about 0.01 %; the
            // residual is the genuine physical lag of falling into denser air, not numerics.
            assertThat(s.verticalRateMs()).as("descent rate at %.0f m", s.altitudeM())
                    .isCloseTo(analytic, within(0.005 * Math.abs(analytic)));
        }

        double landingRate = h.landing().orElseThrow().verticalRateMs();
        System.out.printf("landing rate %.2f m/s%n", landingRate);
        assertThat(landingRate).as("touchdown rate").isBetween(-12.0, -2.0);
    }

    @Test
    @DisplayName("A larger burst diameter bursts higher and a smaller one lower")
    void burstDiameterDrivesBurstAltitude() throws Exception {
        BalloonConfig config = habSat();
        FlightSimulator sim = new FlightSimulator(ATMOSPHERE, ConstantWindField.calm());
        SimSettings st = settings(0.25, "RK4");

        double low = sim.run(config, FlightParameters.nominal(config).withBurstDiameterM(6.0),
                st, LAUNCH).burstAltitudeM().orElseThrow();
        double high = sim.run(config, FlightParameters.nominal(config).withBurstDiameterM(8.0),
                st, LAUNCH).burstAltitudeM().orElseThrow();

        assertThat(high).as("a bigger envelope reaches further before it bursts")
                .isGreaterThan(low + 1_000.0);
    }

    @Test
    @DisplayName("More free lift ascends faster; more drag ascends slower")
    void parametersMoveTheFlightInTheExpectedDirection() throws Exception {
        BalloonConfig config = habSat();
        FlightSimulator sim = new FlightSimulator(ATMOSPHERE, ConstantWindField.calm());
        SimSettings st = settings(0.25, "RK4");
        FlightParameters nominal = FlightParameters.nominal(config);

        double baseline = sim.run(config, nominal, st, LAUNCH).burst().orElseThrow().timeSeconds();
        double moreLift = sim.run(config, nominal.withFreeLiftKg(1.6), st, LAUNCH)
                .burst().orElseThrow().timeSeconds();
        double moreDrag = sim.run(config, nominal.withAscentCd(0.7), st, LAUNCH)
                .burst().orElseThrow().timeSeconds();

        assertThat(moreLift).as("more free lift reaches burst sooner").isLessThan(baseline);
        assertThat(moreDrag).as("more drag reaches burst later").isGreaterThan(baseline);
    }

    @Test
    @DisplayName("runFrom re-predicts from a measured state and reaches the same landing point")
    void runFromContinuesAnInFlightState() throws Exception {
        BalloonConfig config = habSat();
        FlightParameters parameters = FlightParameters.nominal(config);
        ConstantWindField wind = new ConstantWindField(10.0, 2.0);
        FlightSimulator sim = new FlightSimulator(ATMOSPHERE, wind);
        SimSettings st = settings(0.25, "RK4");

        StateHistory full = sim.run(config, parameters, st, LAUNCH);
        // Pick a state partway up the ascent and continue the flight from there.
        BalloonState midAscent = firstAscentStateAbove(full, 12_000.0);
        StateHistory continued = sim.runFrom(config, parameters, st, midAscent);

        double separation = Geodesy.haversineMetres(
                full.landingPoint().orElseThrow(), continued.landingPoint().orElseThrow());
        System.out.printf("runFrom: re-prediction lands %.1f m from the full flight%n", separation);
        assertThat(separation).as("a re-prediction from a state on the flight path must agree")
                .isLessThan(50.0);
        assertThat(continued.burst()).isPresent();
    }

    @Test
    @DisplayName("runFrom after burst continues under the parachute, not the balloon")
    void runFromAfterBurstDescends() throws Exception {
        BalloonConfig config = habSat();
        FlightParameters parameters = FlightParameters.nominal(config);
        FlightSimulator sim = new FlightSimulator(ATMOSPHERE, ConstantWindField.calm());
        SimSettings st = settings(0.25, "RK4");

        StateHistory full = sim.run(config, parameters, st, LAUNCH);
        BalloonState afterBurst = full.burst().orElseThrow();
        StateHistory continued = sim.runFrom(config, parameters, st, afterBurst);

        assertThat(continued.burst()).as("it has already burst").isEmpty();
        assertThat(continued.landing()).isPresent();
        assertThat(continued.states()).allSatisfy(
                s -> assertThat(s.phase()).isNotEqualTo(Phase.ASCENT));
    }

    private static BalloonState firstAscentStateAbove(StateHistory h, double altitudeM) {
        for (BalloonState s : h) {
            if (s.phase() == Phase.ASCENT && s.altitudeM() >= altitudeM) {
                return s;
            }
        }
        throw new AssertionError("no ascent state above " + altitudeM + " m");
    }

    private static BalloonState lastDescentStateAbove(StateHistory h, double altitudeM) {
        BalloonState found = null;
        for (BalloonState s : h) {
            if (s.phase() == Phase.DESCENT && s.altitudeM() >= altitudeM) {
                found = s;
            }
        }
        if (found == null) {
            throw new AssertionError("no descent state above " + altitudeM + " m");
        }
        return found;
    }
}
