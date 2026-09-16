package com.skyfix.core.flight;

import com.skyfix.domain.error.SkyfixException;

/**
 * Runge-Kutta-Fehlberg 4(5), fixed step.
 *
 * <p>Six stages produce an embedded pair: a fourth-order and a fifth-order estimate of the same
 * step. This integrator propagates the fifth-order solution and keeps the difference between the
 * two as a local truncation-error estimate, readable through {@link #lastErrorEstimate()}.
 *
 * <p><strong>Step size is not adapted.</strong> FR-2.3 specifies a fixed step, and a run is only
 * reproducible to the 1e-9 that T-R1 demands if every member takes the same steps. So what this
 * class buys is not adaptivity but independence: it is a different scheme with different
 * coefficients and a different stage count, which is what makes T-V3 — the same flight integrated
 * both ways, landing within 50 m — a real cross-check rather than a restatement.
 *
 * <p>The coefficients are Fehlberg's classical tableau, written as exact rationals so they can be
 * read against a published table; {@code Rkf45IntegratorTest} asserts the row sums and the
 * consistency conditions the tableau has to satisfy.
 */
public final class Rkf45Integrator implements Integrator {

    // Nodes (c)
    private static final double C2 = 1.0 / 4.0;
    private static final double C3 = 3.0 / 8.0;
    private static final double C4 = 12.0 / 13.0;
    private static final double C5 = 1.0;
    private static final double C6 = 1.0 / 2.0;

    // Runge-Kutta matrix (a)
    private static final double A21 = 1.0 / 4.0;
    private static final double A31 = 3.0 / 32.0;
    private static final double A32 = 9.0 / 32.0;
    private static final double A41 = 1932.0 / 2197.0;
    private static final double A42 = -7200.0 / 2197.0;
    private static final double A43 = 7296.0 / 2197.0;
    private static final double A51 = 439.0 / 216.0;
    private static final double A52 = -8.0;
    private static final double A53 = 3680.0 / 513.0;
    private static final double A54 = -845.0 / 4104.0;
    private static final double A61 = -8.0 / 27.0;
    private static final double A62 = 2.0;
    private static final double A63 = -3544.0 / 2565.0;
    private static final double A64 = 1859.0 / 4104.0;
    private static final double A65 = -11.0 / 40.0;

    // Fourth-order weights (b)
    private static final double B1 = 25.0 / 216.0;
    private static final double B3 = 1408.0 / 2565.0;
    private static final double B4 = 2197.0 / 4104.0;
    private static final double B5 = -1.0 / 5.0;

    // Fifth-order weights (b-hat)
    private static final double H1 = 16.0 / 135.0;
    private static final double H3 = 6656.0 / 12825.0;
    private static final double H4 = 28561.0 / 56430.0;
    private static final double H5 = -9.0 / 50.0;
    private static final double H6 = 2.0 / 55.0;

    private double lastErrorEstimate = Double.NaN;

    @Override
    public double[] step(double t, double[] y, double h, Derivatives derivative)
            throws SkyfixException {
        if (!(h > 0.0)) {
            throw new IllegalArgumentException("step size must be positive, was " + h);
        }
        int n = y.length;

        double[] k1 = derivative.at(t, y);
        double[] k2 = derivative.at(t + C2 * h, combine(y, h, n, new double[][]{k1},
                new double[]{A21}));
        double[] k3 = derivative.at(t + C3 * h, combine(y, h, n, new double[][]{k1, k2},
                new double[]{A31, A32}));
        double[] k4 = derivative.at(t + C4 * h, combine(y, h, n, new double[][]{k1, k2, k3},
                new double[]{A41, A42, A43}));
        double[] k5 = derivative.at(t + C5 * h, combine(y, h, n, new double[][]{k1, k2, k3, k4},
                new double[]{A51, A52, A53, A54}));
        double[] k6 = derivative.at(t + C6 * h, combine(y, h, n, new double[][]{k1, k2, k3, k4, k5},
                new double[]{A61, A62, A63, A64, A65}));

        double[] fifth = new double[n];
        double error = 0.0;
        for (int i = 0; i < n; i++) {
            double fourthIncrement = B1 * k1[i] + B3 * k3[i] + B4 * k4[i] + B5 * k5[i];
            double fifthIncrement =
                    H1 * k1[i] + H3 * k3[i] + H4 * k4[i] + H5 * k5[i] + H6 * k6[i];
            fifth[i] = y[i] + h * fifthIncrement;
            error = Math.max(error, Math.abs(h * (fifthIncrement - fourthIncrement)));
        }
        lastErrorEstimate = error;
        return fifth;
    }

    @Override
    public String name() {
        return "RKF45";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public double lastErrorEstimate() {
        return lastErrorEstimate;
    }

    private static double[] combine(double[] y, double h, int n, double[][] stages,
                                    double[] weights) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            for (int s = 0; s < stages.length; s++) {
                sum += weights[s] * stages[s][i];
            }
            out[i] = y[i] + h * sum;
        }
        return out;
    }
}
