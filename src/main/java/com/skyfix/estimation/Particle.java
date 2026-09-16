package com.skyfix.estimation;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.FlightParameters;

/**
 * One hypothesis the filter is carrying: a parameter set, where that parameter set says the
 * balloon currently is, and how well it has explained the telemetry so far (FR-3.2).
 *
 * <p>Immutable, so an update produces a new array rather than mutating the set the posterior was
 * just summarised from. At five hundred particles and a few thousand updates that is a few million
 * short-lived records, which is exactly the allocation pattern a generational collector is good at
 * — and it removes any question of whether the posterior and the particle set agree.
 *
 * <p>The weight is a <em>log</em> weight and is unnormalised. Log-likelihoods over a whole flight
 * run to hundreds of nats, so a linear weight would underflow to zero within a handful of updates
 * and every particle would look equally impossible. It accumulates across updates and is reset to
 * zero at each resampling, which is what makes the weights between resamples the correct
 * incremental importance weights.
 *
 * @param parameters this hypothesis' flight parameters
 * @param state      where this hypothesis says the balloon is, at the last update epoch
 * @param logWeight  unnormalised log weight accumulated since the last resampling;
 *                   {@link Double#NEGATIVE_INFINITY} marks a hypothesis that could not be
 *                   propagated at all and is therefore impossible
 */
public record Particle(FlightParameters parameters, BalloonState state, double logWeight) {

    /**
     * A fresh particle at unit weight.
     *
     * @param parameters the parameters
     * @param state      the initial state
     * @return the particle, with a log weight of zero
     */
    public static Particle of(FlightParameters parameters, BalloonState state) {
        return new Particle(parameters, state, 0.0);
    }

    /**
     * @param newState the propagated state
     * @return a copy advanced to a new state, keeping the accumulated weight
     */
    public Particle withState(BalloonState newState) {
        return new Particle(parameters, newState, logWeight);
    }

    /**
     * @param logLikelihood the log-likelihood of the latest observation under this particle
     * @return a copy whose accumulated log weight includes that observation
     */
    public Particle weighted(double logLikelihood) {
        return new Particle(parameters, state, logWeight + logLikelihood);
    }

    /** @return a copy marked impossible — it could not be propagated through the last interval */
    public Particle impossible() {
        return new Particle(parameters, state, Double.NEGATIVE_INFINITY);
    }

    /**
     * @param newParameters the jittered parameters
     * @return a copy carrying new parameters at unit weight, as resampling leaves it
     */
    public Particle resampledWith(FlightParameters newParameters) {
        return new Particle(newParameters, state, 0.0);
    }

    /** @return whether this particle has been ruled out entirely */
    public boolean isImpossible() {
        return logWeight == Double.NEGATIVE_INFINITY;
    }
}
