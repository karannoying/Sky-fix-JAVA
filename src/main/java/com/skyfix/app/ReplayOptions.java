package com.skyfix.app;

import com.skyfix.domain.error.ValidationException;
import com.skyfix.estimation.FilterBank;

/**
 * What a replay may trade against what it costs (FR-3.2, FR-3.3, ADR-3, ADR-17).
 *
 * <p>Every field here buys accuracy with wall clock, and the defaults are the ones the blueprint
 * and its ADRs specify. They are exposed rather than fixed because the trade is real: a three-hour
 * log at full fidelity is a minute of compute, and someone triaging a flight on a laptop is
 * entitled to a coarser answer sooner — as long as the coarseness is stated rather than hidden,
 * which is why the run record stores the particle count it actually used.
 *
 * @param particleCount            the <em>total</em> particle budget, divided between the bank's
 *                                 filters (ADR-18)
 * @param filterCount              independent filters to pool; one is a single filter, whose band
 *                                 is not a credible interval (ADR-18)
 * @param repredictMembers         members in each in-flight ensemble (FR-3.3)
 * @param repredictIntervalSeconds flight-time seconds between re-predictions (ADR-17)
 * @param assimilateEvery          assimilate every n-th telemetry sample; 1 uses them all
 */
public record ReplayOptions(int particleCount, int filterCount, int repredictMembers,
                            double repredictIntervalSeconds, int assimilateEvery) {

    /**
     * Flight-time seconds between re-predictions (ADR-17).
     *
     * <p>Derived, not chosen: FR-3.3 allows a re-prediction 5 s, and a 10x replay of a 1 Hz log
     * affords 100 ms of wall clock per second of flight time, so 5 s buys one re-prediction per
     * 50 s of flight. A per-sample re-prediction under the same budget is arithmetically
     * impossible, which is what ADR-17 records.
     */
    public static final double DEFAULT_REPREDICT_INTERVAL_SECONDS = 50.0;

    /** Members in each in-flight re-prediction (FR-3.3). */
    public static final int DEFAULT_REPREDICT_MEMBERS = 200;

    /**
     * Total particles across the bank (ADR-18).
     *
     * <p>Sixteen filters of 125. Measured on DS-6, this is where the 5-95% bands start containing
     * the truth about as often as they claim to, without the medians suffering for it.
     */
    public static final int DEFAULT_PARTICLE_BUDGET = 2_000;

    /**
     * @throws IllegalArgumentException if a field is out of range; these are programmer errors,
     *                                  since {@link #validated()} screens user input first
     */
    public ReplayOptions {
        if (particleCount < 2 || filterCount < 1 || repredictMembers < 1
                || repredictIntervalSeconds <= 0 || assimilateEvery < 1) {
            throw new IllegalArgumentException("replay options out of range: " + particleCount
                    + ", " + filterCount + ", " + repredictMembers + ", "
                    + repredictIntervalSeconds + ", " + assimilateEvery);
        }
    }

    /**
     * The measured defaults: 2,000 particles across 16 pooled filters, 200-member re-predictions
     * every 50 s of flight time, assimilating every sample.
     *
     * <p>The particle budget is larger than ADR-3's original 500 because it is now split across a
     * bank, and because the filter was never the cost that cap was protecting — a filter update
     * costs about 2 ms per second of flight time at 500 particles against a budget of 100 ms
     * (ADR-17). The re-prediction is the expensive half and is unchanged.
     *
     * @return the standard options
     */
    public static ReplayOptions standard() {
        return new ReplayOptions(DEFAULT_PARTICLE_BUDGET, FilterBank.DEFAULT_FILTER_COUNT,
                DEFAULT_REPREDICT_MEMBERS, DEFAULT_REPREDICT_INTERVAL_SECONDS, 1);
    }

    /** @param v particles to carry @return a copy with that particle count */
    public ReplayOptions withParticleCount(int v) {
        return new ReplayOptions(v, filterCount, repredictMembers, repredictIntervalSeconds,
                assimilateEvery);
    }

    /** @param v independent filters to pool @return a copy with that filter count */
    public ReplayOptions withFilterCount(int v) {
        return new ReplayOptions(particleCount, v, repredictMembers, repredictIntervalSeconds,
                assimilateEvery);
    }

    /** @param v members per re-prediction @return a copy with that member count */
    public ReplayOptions withRepredictMembers(int v) {
        return new ReplayOptions(particleCount, filterCount, v, repredictIntervalSeconds,
                assimilateEvery);
    }

    /** @param v flight-time seconds between re-predictions @return a copy with that interval */
    public ReplayOptions withRepredictIntervalSeconds(double v) {
        return new ReplayOptions(particleCount, filterCount, repredictMembers, v, assimilateEvery);
    }

    /** @param v assimilate every n-th sample @return a copy with that stride */
    public ReplayOptions withAssimilateEvery(int v) {
        return new ReplayOptions(particleCount, filterCount, repredictMembers,
                repredictIntervalSeconds, v);
    }

    /**
     * Checks the fields as <em>user</em> input, naming the offending one.
     *
     * <p>The compact constructor throws {@link IllegalArgumentException} because a bad value there
     * is a bug in the caller; this is what a CLI calls, where a bad value is a typo and deserves an
     * error message that names the flag (BLUEPRINT §12).
     *
     * @return these options
     * @throws ValidationException naming the first field out of range
     */
    public ReplayOptions validated() throws ValidationException {
        if (particleCount < 2) {
            throw ValidationException.field("particles", particleCount,
                    "at least two particles are needed for a distribution to mean anything");
        }
        if (filterCount < 1) {
            throw ValidationException.field("filters", filterCount, "must be at least 1");
        }
        if (particleCount / filterCount < 2) {
            throw ValidationException.field("particles", particleCount,
                    "split across " + filterCount + " filters leaves fewer than two each");
        }
        if (repredictMembers < 1) {
            throw ValidationException.field("members", repredictMembers, "must be at least 1");
        }
        if (repredictIntervalSeconds <= 0) {
            throw ValidationException.field("repredict_interval_s", repredictIntervalSeconds,
                    "must be positive");
        }
        if (assimilateEvery < 1) {
            throw ValidationException.field("every", assimilateEvery, "must be at least 1");
        }
        return this;
    }
}
