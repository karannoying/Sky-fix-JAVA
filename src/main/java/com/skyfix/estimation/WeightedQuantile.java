package com.skyfix.estimation;

import java.util.Arrays;

/**
 * Quantiles of a weighted sample (FR-3.2).
 *
 * <p>One definition, shared by the filter and by the bank that pools filters, so a band means the
 * same thing wherever it is reported.
 *
 * <p>No interpolation between neighbouring samples: the value returned is one the sample actually
 * holds. At hundreds of particles the gap between adjacent order statistics is far below any
 * tolerance in this project, and an interpolated value would be a number no hypothesis in the set
 * ever proposed.
 */
public final class WeightedQuantile {

    private WeightedQuantile() {
    }

    /**
     * The weighted quantile of a sample.
     *
     * @param values  the sample values
     * @param weights weights, one per value, summing to one
     * @param p       the cumulative probability, in (0, 1)
     * @return the smallest value whose running weight reaches {@code p}
     * @throws IllegalArgumentException if the arrays differ in length or are empty
     */
    public static double of(double[] values, double[] weights, double p) {
        return of(values, weights, order(values), p);
    }

    /**
     * The weighted quantile, reusing an ordering already computed.
     *
     * <p>Worth having separately because a band is three quantiles of the same sample, and sorting
     * once rather than three times is the difference between a cheap summary and a hot loop — the
     * filter computes one per parameter per update, thousands of times a replay.
     *
     * @param values  the sample values
     * @param weights weights, one per value, summing to one
     * @param order   indices of {@code values} in ascending value order
     * @param p       the cumulative probability, in (0, 1)
     * @return the smallest value whose running weight reaches {@code p}
     */
    public static double of(double[] values, double[] weights, int[] order, double p) {
        if (values.length != weights.length || values.length == 0) {
            throw new IllegalArgumentException("values and weights must be the same non-zero length");
        }
        double cumulative = 0.0;
        for (int k = 0; k < order.length; k++) {
            cumulative += weights[order[k]];
            if (cumulative >= p) {
                return values[order[k]];
            }
        }
        return values[order[order.length - 1]];
    }

    /**
     * Indices of a sample in ascending value order.
     *
     * @param values the sample
     * @return the ordering
     */
    public static int[] order(double[] values) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (x, y) -> Double.compare(values[x], values[y]));
        int[] order = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            order[i] = boxed[i];
        }
        return order;
    }
}
