package com.skyfix.estimation;

import com.skyfix.domain.BalloonState;

/**
 * Scores how well a predicted state explains an observation (FR-3.1, BLUEPRINT §8).
 *
 * <p>A strategy, so the particle filter can be tested against a model whose answer is known
 * analytically and the report can compare weighting schemes without touching the filter.
 *
 * <p>Works in log space throughout. A particle whose altitude is three hundred metres off has a
 * likelihood around {@code exp(-450)}, which is zero in a double; the log of it is an ordinary
 * number, and that is the difference between a filter that works and one whose weights all
 * underflow to zero at the first update.
 */
public interface MeasurementModel {

    /**
     * The log-likelihood of an observation given a predicted state.
     *
     * @param predicted what a particle says the balloon is doing
     * @param observed  what the telemetry says
     * @return the log-likelihood; higher is a better match, and the scale is arbitrary since only
     *         differences between particles matter
     */
    double logLikelihood(BalloonState predicted, Observation observed);

    /** @return a short name for run records */
    String name();
}
