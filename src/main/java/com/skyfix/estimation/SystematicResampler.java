package com.skyfix.estimation;

import java.util.SplittableRandom;

/**
 * Systematic resampling: one random offset, then a regular comb through the cumulative weights
 * (FR-3.2).
 *
 * <p>The alternative — drawing N independent uniforms — adds far more variance for no benefit.
 * Systematic resampling draws a single uniform {@code u} in {@code [0, 1/N)} and takes the
 * positions {@code (u + i)/N}, so the comb is evenly spaced and a particle holding a fraction
 * {@code w} of the weight is selected either {@code floor(wN)} or {@code ceil(wN)} times. It is
 * O(N) with one random number, and its selection is much closer to proportional than multinomial
 * sampling, which is exactly what a filter that resamples often needs.
 *
 * <p>The cost is that the selections are no longer independent, which matters for some theoretical
 * guarantees and not at all for this application.
 */
public final class SystematicResampler implements Resampler {

    @Override
    public int[] resample(double[] normalisedWeights, SplittableRandom random) {
        int n = normalisedWeights.length;
        if (n == 0) {
            throw new IllegalArgumentException("cannot resample an empty particle set");
        }
        int[] chosen = new int[n];
        double step = 1.0 / n;
        double position = random.nextDouble() * step;

        double cumulative = normalisedWeights[0];
        int source = 0;
        for (int i = 0; i < n; i++) {
            double target = position + i * step;
            while (target > cumulative && source < n - 1) {
                source++;
                cumulative += normalisedWeights[source];
            }
            chosen[i] = source;
        }
        return chosen;
    }

    @Override
    public String name() {
        return "SYSTEMATIC";
    }

    /**
     * Kish's effective sample size of a weight vector.
     *
     * <p>{@code ESS = 1 / sum(w^2)} for normalised weights: it is N when the weights are uniform
     * and 1 when a single particle holds everything, so it is the number this filter watches to
     * decide when the set has degenerated enough to need resampling (FR-3.2 resamples below N/2).
     *
     * @param normalisedWeights weights summing to one
     * @return the effective sample size, between 1 and the weight count
     */
    public static double effectiveSampleSize(double[] normalisedWeights) {
        double sumSquares = 0.0;
        for (double w : normalisedWeights) {
            sumSquares += w * w;
        }
        return sumSquares <= 0.0 ? 0.0 : 1.0 / sumSquares;
    }

    /**
     * Normalises log-weights into probabilities, in a way that does not underflow.
     *
     * <p>Log-likelihoods for a five-hundred-particle set routinely span hundreds of nats, so
     * exponentiating them directly gives zero for every particle and a division by zero. Shifting
     * by the maximum first — the standard log-sum-exp trick — leaves the largest weight at exactly
     * 1 before normalisation and loses nothing, because only the ratios matter.
     *
     * @param logWeights unnormalised log-weights
     * @return weights summing to one
     */
    public static double[] normaliseLogWeights(double[] logWeights) {
        int n = logWeights.length;
        double max = Double.NEGATIVE_INFINITY;
        for (double w : logWeights) {
            if (!Double.isNaN(w)) {
                max = Math.max(max, w);
            }
        }
        double[] weights = new double[n];
        if (max == Double.NEGATIVE_INFINITY) {
            // Every particle is impossible. Falling back to uniform keeps the filter alive so the
            // caller can see the collapse in the ESS rather than meeting a NaN posterior.
            java.util.Arrays.fill(weights, 1.0 / n);
            return weights;
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            weights[i] = Double.isNaN(logWeights[i]) ? 0.0 : Math.exp(logWeights[i] - max);
            sum += weights[i];
        }
        if (sum <= 0.0) {
            java.util.Arrays.fill(weights, 1.0 / n);
            return weights;
        }
        for (int i = 0; i < n; i++) {
            weights[i] /= sum;
        }
        return weights;
    }
}
