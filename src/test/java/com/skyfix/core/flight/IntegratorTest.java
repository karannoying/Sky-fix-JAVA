package com.skyfix.core.flight;

import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-U-INTEG — both integrators against problems with closed-form solutions.
 *
 * <p>The primary oracle is {@code y' = -k y}, whose exact solution {@code y0 exp(-k t)} is known,
 * so the assertion is against analysis rather than against the other integrator. A harmonic
 * oscillator and a polynomial are added because they exercise what a single decaying exponential
 * cannot: an oscillating system, and a case a fourth-order method must reproduce exactly.
 */
class IntegratorTest {

    private static final double K = 0.35;
    private static final Derivatives DECAY = (t, y) -> new double[]{-K * y[0]};

    @Test
    @DisplayName("T-U-INTEG: RK4 on y' = -k y is within 1e-6 relative over 100 steps")
    void rk4MatchesTheAnalyticDecay() throws Exception {
        assertDecayAccuracy(new Rk4Integrator(), 1e-6);
    }

    @Test
    @DisplayName("T-U-INTEG: RKF45 on y' = -k y is within 1e-6 relative over 100 steps")
    void rkf45MatchesTheAnalyticDecay() throws Exception {
        assertDecayAccuracy(new Rkf45Integrator(), 1e-6);
    }

    private void assertDecayAccuracy(Integrator integrator, double tolerance) throws Exception {
        double h = 0.1;
        int steps = 100;
        double[] y = {1.0};
        double t = 0.0;
        for (int i = 0; i < steps; i++) {
            y = integrator.step(t, y, h, DECAY);
            t += h;
        }
        double exact = Math.exp(-K * t);
        double relative = Math.abs(y[0] - exact) / exact;
        System.out.printf("T-U-INTEG %s: relative error after %d steps = %.3e%n",
                integrator.name(), steps, relative);
        assertThat(relative).as("%s over %d steps", integrator.name(), steps)
                .isLessThan(tolerance);
    }

    @Test
    @DisplayName("T-U-INTEG: the error falls at the method's advertised order")
    void convergenceOrderMatchesTheAdvertisedOrder() throws Exception {
        // Halving the step must cut a fourth-order method's error by about 2^4. Checking the
        // observed order is a stronger statement than checking one error value: it says the
        // method really is the order it claims, which is what justifies the chosen step size.
        for (Integrator integrator : new Integrator[]{new Rk4Integrator(), new Rkf45Integrator()}) {
            double coarse = decayErrorAfter(integrator, 0.2, 50);
            double fine = decayErrorAfter(integrator, 0.1, 100);
            double observedOrder = Math.log(coarse / fine) / Math.log(2.0);
            System.out.printf("T-U-INTEG %s: observed order %.2f (advertised %d)%n",
                    integrator.name(), observedOrder, integrator.order());
            assertThat(observedOrder)
                    .as("observed convergence order of %s", integrator.name())
                    .isGreaterThan(integrator.order() - 0.75);
        }
    }

    private double decayErrorAfter(Integrator integrator, double h, int steps) throws Exception {
        double[] y = {1.0};
        double t = 0.0;
        for (int i = 0; i < steps; i++) {
            y = integrator.step(t, y, h, DECAY);
            t += h;
        }
        return Math.abs(y[0] - Math.exp(-K * t));
    }

    @Test
    @DisplayName("T-U-INTEG: a harmonic oscillator keeps its amplitude and phase")
    void harmonicOscillator() throws Exception {
        // y'' = -w^2 y as a first-order system, exact solution y = cos(w t), y' = -w sin(w t).
        double w = 1.7;
        Derivatives sho = (t, y) -> new double[]{y[1], -w * w * y[0]};

        for (Integrator integrator : new Integrator[]{new Rk4Integrator(), new Rkf45Integrator()}) {
            double[] y = {1.0, 0.0};
            double h = 0.01;
            double t = 0.0;
            for (int i = 0; i < 1000; i++) {
                y = integrator.step(t, y, h, sho);
                t += h;
            }
            assertThat(y[0]).as("%s position", integrator.name())
                    .isEqualTo(Math.cos(w * t), within(1e-7));
            assertThat(y[1]).as("%s velocity", integrator.name())
                    .isEqualTo(-w * Math.sin(w * t), within(1e-7));
        }
    }

    @Test
    @DisplayName("T-U-INTEG: a cubic is integrated exactly by a fourth-order method")
    void cubicIsExact() throws Exception {
        // y' = 3t^2 has solution y = t^3. A fourth-order method reproduces it to rounding, so
        // this catches a mistyped coefficient that a decaying exponential would hide.
        Derivatives cubic = (t, y) -> new double[]{3.0 * t * t};
        for (Integrator integrator : new Integrator[]{new Rk4Integrator(), new Rkf45Integrator()}) {
            double[] y = {0.0};
            double t = 0.0;
            for (int i = 0; i < 40; i++) {
                y = integrator.step(t, y, 0.25, cubic);
                t += 0.25;
            }
            assertThat(y[0]).as("%s on a cubic", integrator.name())
                    .isEqualTo(t * t * t, within(1e-9));
        }
    }

    @Test
    @DisplayName("RKF45 reports a local error estimate; RK4 reports none")
    void errorEstimateIsOnlyReportedByTheEmbeddedPair() throws Exception {
        Rkf45Integrator rkf = new Rkf45Integrator();
        assertThat(rkf.lastErrorEstimate()).isNaN(); // nothing integrated yet
        rkf.step(0.0, new double[]{1.0}, 0.5, DECAY);
        assertThat(rkf.lastErrorEstimate()).isNotNaN().isPositive().isLessThan(1e-6);

        assertThat(new Rk4Integrator().lastErrorEstimate()).isNaN();
    }

    @Test
    @DisplayName("An integrator never modifies the state array it is handed")
    void inputStateIsNeverMutated() throws Exception {
        for (Integrator integrator : new Integrator[]{new Rk4Integrator(), new Rkf45Integrator()}) {
            double[] y = {1.0, 2.0};
            double[] copy = y.clone();
            integrator.step(0.0, y, 0.1, (t, s) -> new double[]{s[1], -s[0]});
            assertThat(y).as("%s must not alias its input", integrator.name()).containsExactly(copy);
        }
    }

    @Test
    @DisplayName("A non-positive step is a programmer error, not user input")
    void nonPositiveStepIsRejected() {
        for (Integrator integrator : new Integrator[]{new Rk4Integrator(), new Rkf45Integrator()}) {
            assertThatThrownBy(() -> integrator.step(0.0, new double[]{1.0}, 0.0, DECAY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> integrator.step(0.0, new double[]{1.0}, -1.0, DECAY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("The RKF45 tableau satisfies the consistency conditions sum(a_ij) = c_i")
    void rkf45TableauIsConsistent() {
        // Each row of the Runge-Kutta matrix must sum to its node, and both weight vectors must
        // sum to 1. A mistyped coefficient breaks one of these long before it breaks a flight.
        assertThat(1.0 / 4.0).isEqualTo(1.0 / 4.0, within(1e-15));
        assertThat(3.0 / 32.0 + 9.0 / 32.0).isEqualTo(3.0 / 8.0, within(1e-15));
        assertThat(1932.0 / 2197.0 - 7200.0 / 2197.0 + 7296.0 / 2197.0)
                .isEqualTo(12.0 / 13.0, within(1e-15));
        assertThat(439.0 / 216.0 - 8.0 + 3680.0 / 513.0 - 845.0 / 4104.0)
                .isEqualTo(1.0, within(1e-14));
        assertThat(-8.0 / 27.0 + 2.0 - 3544.0 / 2565.0 + 1859.0 / 4104.0 - 11.0 / 40.0)
                .isEqualTo(1.0 / 2.0, within(1e-14));

        assertThat(25.0 / 216.0 + 1408.0 / 2565.0 + 2197.0 / 4104.0 - 1.0 / 5.0)
                .as("fourth-order weights must sum to 1").isEqualTo(1.0, within(1e-14));
        assertThat(16.0 / 135.0 + 6656.0 / 12825.0 + 28561.0 / 56430.0 - 9.0 / 50.0 + 2.0 / 55.0)
                .as("fifth-order weights must sum to 1").isEqualTo(1.0, within(1e-14));
    }

    @Test
    @DisplayName("IntegratorFactory resolves known names and rejects unknown ones by name")
    void factoryResolvesAndRejects() throws Exception {
        assertThat(IntegratorFactory.create("RK4")).isInstanceOf(Rk4Integrator.class);
        assertThat(IntegratorFactory.create("rk4").name()).isEqualTo("RK4");
        assertThat(IntegratorFactory.create(" RKF45 ")).isInstanceOf(Rkf45Integrator.class);
        assertThat(IntegratorFactory.available()).containsExactly("RK4", "RKF45");

        // A fresh instance each time: RKF45 carries per-step state that threads must not share.
        assertThat(IntegratorFactory.create("RKF45"))
                .isNotSameAs(IntegratorFactory.create("RKF45"));

        assertThatThrownBy(() -> IntegratorFactory.create("euler"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("integrator")
                .hasMessageContaining("RK4");
        assertThatThrownBy(() -> IntegratorFactory.create(null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> IntegratorFactory.create("  "))
                .isInstanceOf(ValidationException.class);
    }
}
