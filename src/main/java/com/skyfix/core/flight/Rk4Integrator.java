package com.skyfix.core.flight;

import com.skyfix.domain.error.SkyfixException;

/**
 * Classical fourth-order Runge-Kutta, fixed step (ADR-1 family, BLUEPRINT §10).
 *
 * <pre>
 *   k1 = f(t,         y)
 *   k2 = f(t + h/2,   y + h k1 / 2)
 *   k3 = f(t + h/2,   y + h k2 / 2)
 *   k4 = f(t + h,     y + h k3)
 *   y(t + h) = y + h (k1 + 2 k2 + 2 k3 + k4) / 6
 * </pre>
 *
 * <p>Chosen over Euler because Euler needs a step below 0.1 s for comparable accuracy on this
 * problem, which would put NFR-1's 30 s ensemble budget out of reach. Verified by T-U-INTEG
 * against the analytic solution of {@code y' = -k y}.
 */
public final class Rk4Integrator implements Integrator {

    @Override
    public double[] step(double t, double[] y, double h, Derivatives derivative)
            throws SkyfixException {
        if (!(h > 0.0)) {
            // A non-positive step here is a programmer error: user-supplied steps are screened
            // by SimSettings.Builder, which raises a ValidationException naming the field.
            throw new IllegalArgumentException("step size must be positive, was " + h);
        }
        int n = y.length;

        double[] k1 = derivative.at(t, y);
        double[] k2 = derivative.at(t + h / 2.0, axpy(y, k1, h / 2.0, n));
        double[] k3 = derivative.at(t + h / 2.0, axpy(y, k2, h / 2.0, n));
        double[] k4 = derivative.at(t + h, axpy(y, k3, h, n));

        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = y[i] + h * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]) / 6.0;
        }
        return out;
    }

    @Override
    public String name() {
        return "RK4";
    }

    @Override
    public int order() {
        return 4;
    }

    /** Returns {@code y + a * k}, allocated fresh so the caller's arrays are never aliased. */
    private static double[] axpy(double[] y, double[] k, double a, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = y[i] + a * k[i];
        }
        return out;
    }
}
