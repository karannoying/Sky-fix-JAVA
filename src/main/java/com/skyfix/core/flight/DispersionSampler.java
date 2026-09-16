package com.skyfix.core.flight;

import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.FlightParameters;

import java.util.SplittableRandom;

/**
 * Latin-hypercube sampling over the five dispersed parameters (FR-2.4, ADR-6).
 *
 * <p>Plain Monte Carlo leaves gaps and clumps: with a thousand draws, whole regions of the
 * parameter space go unvisited while others are sampled three times over, and the resulting
 * ellipse wobbles from seed to seed. Latin-hypercube sampling removes that by construction. Each
 * dimension is cut into <i>N</i> equal-probability strata, exactly one sample is taken from each,
 * and the strata are then permuted independently per dimension. Every member is still a random
 * draw, but the marginal coverage of each parameter is guaranteed rather than hoped for.
 *
 * <p><b>Reproducibility.</b> Member <i>k</i> must be reproducible in isolation (ADR-6), so no
 * shared mutable {@code Random} appears anywhere here: the run seed is split once per member with
 * {@link SplittableRandom#split()}, and each member's jitter comes from its own generator. The
 * permutations come from a separate generator derived from the same seed, so the whole design is a
 * pure function of {@code (seed, memberCount, spec)} — which is what makes T-R1 achievable.
 *
 * <p>The sampler is stateless once constructed and safe to share.
 */
public final class DispersionSampler {

    private final DispersionSpec spec;

    /**
     * @param spec how far each parameter may vary
     */
    public DispersionSampler(DispersionSpec spec) {
        this.spec = spec;
    }

    /**
     * Draws a Latin-hypercube design.
     *
     * @param memberCount how many members to draw; must be at least one
     * @param seed        the run seed
     * @return one parameter set per member, in member-index order
     * @throws IllegalArgumentException if {@code memberCount} is not positive
     */
    public FlightParameters[] sample(int memberCount, long seed) {
        double[][] unit = unitDesign(memberCount, seed);
        FlightParameters[] members = new FlightParameters[memberCount];
        for (int i = 0; i < memberCount; i++) {
            members[i] = spec.at(unit[i]);
        }
        return members;
    }

    /**
     * Draws the design in the unit hypercube, before any distribution is applied.
     *
     * <p>Exposed because the stratification property is a statement about this array, not about
     * the parameter values it maps to: T-U-LHS checks that each dimension has exactly one sample
     * in each of the N strata, which is only meaningful here.
     *
     * @param memberCount how many members to draw
     * @param seed        the run seed
     * @return an array of {@code memberCount} points, each with one coordinate per dimension,
     *         every coordinate strictly inside (0, 1)
     * @throws IllegalArgumentException if {@code memberCount} is not positive
     */
    public double[][] unitDesign(int memberCount, long seed) {
        if (memberCount < 1) {
            throw new IllegalArgumentException(
                    "member count must be at least 1, was " + memberCount);
        }
        int dimensions = spec.dimensionCount();
        double[][] design = new double[memberCount][dimensions];

        // One generator per member for the within-stratum jitter, derived by splitting the run
        // seed. Member k's jitter therefore does not depend on how many members ran before it,
        // or on the order threads happened to finish in.
        SplittableRandom root = new SplittableRandom(seed);
        SplittableRandom[] perMember = new SplittableRandom[memberCount];
        for (int i = 0; i < memberCount; i++) {
            perMember[i] = root.split();
        }
        // A separate stream for the permutations, so changing the jitter cannot silently reshuffle
        // which stratum a member lands in.
        SplittableRandom permutationSource = root.split();

        for (int d = 0; d < dimensions; d++) {
            int[] strata = shuffledStrata(memberCount, permutationSource);
            for (int i = 0; i < memberCount; i++) {
                // Stratum s covers [s/N, (s+1)/N); take one point uniformly inside it.
                double jitter = perMember[i].nextDouble();
                double p = (strata[i] + jitter) / memberCount;
                // Nudge away from the open interval's ends: an exact 0 or 1 has no finite
                // quantile, and a stratum boundary can land on one after rounding.
                design[i][d] = Math.min(1.0 - 1e-12, Math.max(1e-12, p));
            }
        }
        return design;
    }

    /** Fisher-Yates over 0..n-1, using the supplied generator. */
    private static int[] shuffledStrata(int n, SplittableRandom random) {
        int[] strata = new int[n];
        for (int i = 0; i < n; i++) {
            strata[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = strata[i];
            strata[i] = strata[j];
            strata[j] = swap;
        }
        return strata;
    }

    /** @return the specification this sampler draws from */
    public DispersionSpec spec() {
        return spec;
    }
}
