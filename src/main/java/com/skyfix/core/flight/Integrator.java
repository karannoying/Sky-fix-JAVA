package com.skyfix.core.flight;

import com.skyfix.domain.error.SkyfixException;

/**
 * Strategy for advancing an ODE system by one step (BLUEPRINT §8).
 *
 * <p>Two implementations ship. {@link Rk4Integrator} is the default; {@link Rkf45Integrator} is a
 * genuinely different scheme, and running the same flight through both is the T-V3 cross-check
 * that gives NFR-2 a leg it would otherwise lack.
 *
 * <p>Implementations are stateless with respect to the trajectory and therefore safe to share
 * across ensemble threads.
 */
public interface Integrator {

    /**
     * Advances the state by one step of size {@code h}.
     *
     * @param t          current independent variable, seconds
     * @param y          current state vector; left unmodified
     * @param h          step size, seconds; must be positive
     * @param derivative the system's right-hand side
     * @return a new array holding the state at {@code t + h}
     * @throws SkyfixException if the derivative cannot be evaluated anywhere in the step
     */
    double[] step(double t, double[] y, double h, Derivatives derivative) throws SkyfixException;

    /** @return the integrator's name, as it appears in run records and the config hash */
    String name();

    /** @return the order of the method, used only for reporting */
    int order();

    /**
     * Magnitude of the local truncation error estimated on the most recent step, or
     * {@link Double#NaN} for a method that does not produce one.
     *
     * <p>Not thread-safe by design: an integrator instance belongs to one member's integration.
     *
     * @return the estimate, in the units of the state vector's largest component
     */
    default double lastErrorEstimate() {
        return Double.NaN;
    }

    /**
     * The largest {@code |lambda| h} this method stays stable at on the negative real axis.
     *
     * <p>Parachute descent is a stiff, strongly damped problem: the drag force linearises to a
     * real negative eigenvalue {@code lambda = -rho Cd A |v| / m}, and that damping gets faster as
     * the payload falls into denser air. An explicit method whose step exceeds this limit does not
     * merely lose accuracy, it oscillates — which is what {@code FlightSimulator} uses this number
     * to refuse (see ADR-13).
     *
     * <p>The value is <em>measured from the method itself</em> rather than transcribed: the
     * integrator is applied to {@code y' = lambda y} with unit step, which yields exactly the
     * method's stability function {@code R(lambda)}, and the boundary {@code |R| = 1} is found by
     * bisection. So it stays correct for any integrator added later, with no table to maintain.
     *
     * @return the stability boundary, a positive dimensionless number
     */
    default double realAxisStabilityLimit() {
        // R(z) for an explicit RK method is what one unit step of y' = z y returns from y = 1.
        java.util.function.DoubleUnaryOperator amplification = z -> {
            try {
                return step(0.0, new double[]{1.0}, 1.0, (t, y) -> new double[]{z * y[0]})[0];
            } catch (SkyfixException e) {
                throw new IllegalStateException("linear test problem cannot fail", e);
            }
        };
        double stable = -0.1;                       // |R| < 1 here for every usable method
        double unstable = -10.0;                    // and > 1 here
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (stable + unstable);
            if (Math.abs(amplification.applyAsDouble(mid)) <= 1.0) {
                stable = mid;
            } else {
                unstable = mid;
            }
        }
        return Math.abs(stable);
    }
}
