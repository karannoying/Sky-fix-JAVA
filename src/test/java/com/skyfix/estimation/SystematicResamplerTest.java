package com.skyfix.estimation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Systematic resampling and the weight arithmetic around it (FR-3.2).
 *
 * <p>These are the routines where a subtle error does not crash anything — it quietly biases every
 * posterior the filter ever reports. So the properties are asserted exactly: a particle holding a
 * fraction {@code w} of the weight must be selected {@code floor(wN)} or {@code ceil(wN)} times,
 * never merely "about" that; and the log-weight normalisation must survive the hundreds of nats
 * that real log-likelihoods span.
 */
class SystematicResamplerTest {

    private final SystematicResampler resampler = new SystematicResampler();

    @Test
    @DisplayName("FR-3.2: selection counts are within one of proportional, for every particle")
    void selectionIsProportionalToWeight() {
        // This is systematic resampling's defining guarantee, and it is exact rather than
        // statistical: a particle with weight w out of N is chosen floor(wN) or ceil(wN) times.
        // Multinomial sampling satisfies it only on average, which is precisely why this scheme
        // was chosen over it.
        double[] weights = {0.40, 0.25, 0.20, 0.10, 0.04, 0.01};
        int n = weights.length;

        for (long seed = 0; seed < 200; seed++) {
            int[] chosen = resampler.resample(weights, new SplittableRandom(seed));
            assertThat(chosen).hasSize(n);

            int[] counts = new int[n];
            for (int index : chosen) {
                assertThat(index).isBetween(0, n - 1);
                counts[index]++;
            }
            for (int i = 0; i < n; i++) {
                double expected = weights[i] * n;
                assertThat(counts[i])
                        .as("seed %d, particle %d with weight %.2f", seed, i, weights[i])
                        .isBetween((int) Math.floor(expected), (int) Math.ceil(expected));
            }
        }
    }

    @Test
    @DisplayName("FR-3.2: uniform weights reproduce the set, keeping every particle")
    void uniformWeightsKeepEveryone() {
        int n = 64;
        double[] weights = new double[n];
        Arrays.fill(weights, 1.0 / n);

        for (long seed = 0; seed < 50; seed++) {
            int[] chosen = resampler.resample(weights, new SplittableRandom(seed));
            // With equal weights the comb lands once in each interval, so nothing is lost --
            // resampling an already-healthy set must not throw away diversity.
            assertThat(Arrays.stream(chosen).distinct().count()).isEqualTo(n);
        }
    }

    @Test
    @DisplayName("FR-3.2: a degenerate weight vector collapses onto the surviving particle")
    void degenerateWeightsCollapse() {
        double[] weights = new double[100];
        weights[42] = 1.0;
        int[] chosen = resampler.resample(weights, new SplittableRandom(1L));
        assertThat(Arrays.stream(chosen).distinct().toArray()).containsExactly(42);
    }

    @Test
    @DisplayName("FR-3.2: effective sample size is N when uniform and 1 when collapsed")
    void effectiveSampleSizeBounds() {
        int n = 500;
        double[] uniform = new double[n];
        Arrays.fill(uniform, 1.0 / n);
        assertThat(SystematicResampler.effectiveSampleSize(uniform)).isEqualTo(n, within(1e-9));

        double[] collapsed = new double[n];
        collapsed[0] = 1.0;
        assertThat(SystematicResampler.effectiveSampleSize(collapsed)).isEqualTo(1.0, within(1e-9));

        // Half the particles sharing everything equally gives exactly N/2 -- which is the
        // threshold FR-3.2 resamples at, so this pins the meaning of that rule.
        double[] half = new double[n];
        Arrays.fill(half, 0, n / 2, 2.0 / n);
        assertThat(SystematicResampler.effectiveSampleSize(half)).isEqualTo(n / 2.0, within(1e-9));
    }

    @Test
    @DisplayName("FR-3.2: log-weights spanning hundreds of nats normalise without underflowing")
    void logWeightsNormaliseWithoutUnderflow() {
        // Real log-likelihoods for a 500-particle set routinely span hundreds of nats.
        // Exponentiating them directly gives zero for every particle and then a division by zero;
        // shifting by the maximum first is what makes the filter possible at all.
        double[] logWeights = {-1_000.5, -1_000.0, -1_200.0, -5_000.0, -1_000.2};
        double[] weights = SystematicResampler.normaliseLogWeights(logWeights);

        assertThat(Arrays.stream(weights).sum()).isEqualTo(1.0, within(1e-12));
        assertThat(Arrays.stream(weights).allMatch(w -> w >= 0.0 && Double.isFinite(w)))
                .isTrue();
        // The best particle takes the most weight, and the hopeless one effectively none.
        assertThat(weights[1]).isGreaterThan(weights[0]).isGreaterThan(weights[2]);
        assertThat(weights[3]).isLessThan(1e-100);

        // Naively exponentiating would have produced nothing but zeros.
        assertThat(Math.exp(logWeights[1])).isEqualTo(0.0);
    }

    @Test
    @DisplayName("FR-3.2: normalisation preserves the ratios that carry the information")
    void normalisationPreservesRatios() {
        // Only differences between log-weights mean anything, so shifting them all by a constant
        // must leave the normalised weights untouched.
        double[] base = {-3.0, -1.0, -2.5, -0.5};
        double[] shifted = Arrays.stream(base).map(w -> w - 742.0).toArray();

        double[] a = SystematicResampler.normaliseLogWeights(base);
        double[] b = SystematicResampler.normaliseLogWeights(shifted);
        for (int i = 0; i < a.length; i++) {
            assertThat(b[i]).as("particle %d", i).isEqualTo(a[i], within(1e-12));
        }
        // And the ratio of two weights is exp of the difference of their log-weights.
        assertThat(a[1] / a[0]).isEqualTo(Math.exp(base[1] - base[0]), within(1e-12));
    }

    @Test
    @DisplayName("FR-3.2: an all-impossible particle set falls back to uniform, visibly")
    void allImpossibleFallsBackToUniform() {
        // If every particle is impossible the honest answer is "no information", not NaN. Falling
        // back to uniform keeps the filter alive so the collapse shows up in the ESS, where a
        // caller can see it, rather than as a posterior full of NaN.
        double[] allNegativeInfinity = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
        double[] weights = SystematicResampler.normaliseLogWeights(allNegativeInfinity);
        assertThat(Arrays.stream(weights).sum()).isEqualTo(1.0, within(1e-12));
        assertThat(weights).containsExactly(1.0 / 3, 1.0 / 3, 1.0 / 3);
        assertThat(SystematicResampler.effectiveSampleSize(weights)).isEqualTo(3.0, within(1e-9));

        // A NaN log-weight -- a particle whose simulation failed -- is treated as impossible
        // rather than poisoning the whole vector.
        double[] withNaN = {-1.0, Double.NaN, -1.0};
        double[] mixed = SystematicResampler.normaliseLogWeights(withNaN);
        assertThat(Arrays.stream(mixed).sum()).isEqualTo(1.0, within(1e-12));
        assertThat(mixed[1]).isEqualTo(0.0);
    }

    @Test
    @DisplayName("ADR-6: resampling is reproducible from its generator")
    void resamplingIsReproducible() {
        double[] weights = {0.3, 0.3, 0.2, 0.1, 0.1};
        assertThat(resampler.resample(weights, new SplittableRandom(99L)))
                .containsExactly(resampler.resample(weights, new SplittableRandom(99L)));
        assertThat(resampler.name()).isEqualTo("SYSTEMATIC");
    }

    @Test
    @DisplayName("An empty particle set is a programmer error, not a silent no-op")
    void emptySetRejected() {
        assertThatThrownBy(() -> resampler.resample(new double[0], new SplittableRandom(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
