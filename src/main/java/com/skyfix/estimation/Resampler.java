package com.skyfix.estimation;

import java.util.SplittableRandom;

/**
 * Chooses which particles survive a resampling step (FR-3.2, BLUEPRINT §8).
 *
 * <p>A strategy, because resampling schemes differ in the variance they add and the report should
 * be able to compare them on the same flight rather than asserting that one is better.
 */
public interface Resampler {

    /**
     * Picks indices to carry forward.
     *
     * @param normalisedWeights weights summing to one, one per particle
     * @param random            the source of randomness; a seeded generator keeps the run
     *                          reproducible (ADR-6)
     * @return {@code weights.length} indices into the particle set, with repeats where a particle
     *         is selected more than once
     */
    int[] resample(double[] normalisedWeights, SplittableRandom random);

    /** @return a short name for run records */
    String name();
}
