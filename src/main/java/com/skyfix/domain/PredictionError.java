package com.skyfix.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * How far a prediction was from where the payload actually landed, update by update (FR-4.1, O4).
 *
 * <p>This is the evidence for the project's fourth objective: that watching the flight and
 * re-predicting is worth doing, rather than merely possible. A frozen pre-flight footprint and a
 * series of in-flight ones are scored against the same truth by the same haversine, so the
 * comparison is like for like.
 *
 * @param runId       the replay this scores
 * @param actual      where the payload actually landed
 * @param frozenErrorM the pre-flight prediction's error in metres, the baseline everything is
 *                    measured against
 * @param updates     one entry per in-flight re-prediction, in time order
 */
public record PredictionError(long runId, GeoPoint actual, double frozenErrorM,
                              List<Update> updates) {

    /** @throws IllegalArgumentException if the baseline is not a usable distance */
    public PredictionError {
        if (!(frozenErrorM >= 0.0)) {
            throw new IllegalArgumentException(
                    "the frozen prediction's error must be a non-negative distance, was "
                            + frozenErrorM);
        }
        updates = List.copyOf(updates);
    }

    /**
     * One scored re-prediction.
     *
     * @param epochUtc      the telemetry epoch it was made at
     * @param flightSeconds seconds since the first telemetry sample
     * @param errorM        haversine distance from the predicted centre to the actual landing
     * @param semiMajorM    the 95% ellipse's semi-major axis, so a reader can see whether the
     *                      error is inside the uncertainty the prediction claimed
     * @param afterBurst    whether the telemetry had already shown a burst at this epoch
     */
    public record Update(Instant epochUtc, double flightSeconds, double errorM, double semiMajorM,
                         boolean afterBurst) {

        /** @return whether the actual landing fell inside the 95% ellipse's semi-major distance */
        public boolean insideClaimedUncertainty() {
            return errorM <= semiMajorM;
        }
    }

    /**
     * The fractional reduction in error against the frozen prediction.
     *
     * <p>Positive means the in-flight prediction was better. Reported as a fraction rather than a
     * distance because flights differ enormously in how far they drift — a 2 km improvement on a
     * 400 km flight is not the same achievement as a 2 km improvement on a 30 km one.
     *
     * @param errorM the in-flight error to compare
     * @return the reduction as a fraction of the frozen error, or 0 if the frozen error was zero
     */
    public double reductionAgainstFrozen(double errorM) {
        return frozenErrorM <= 0.0 ? 0.0 : (frozenErrorM - errorM) / frozenErrorM;
    }

    /**
     * The first re-prediction made after the telemetry showed a burst.
     *
     * <p>The operationally decisive moment: burst is when a recovery team commits to a drive, and
     * it is the last point at which the whole descent — where most of the landing uncertainty lives
     * — is still ahead. Scoring there is a real test, where scoring at the final update would
     * flatter the result by asking how well the model predicts a payload that has nearly landed.
     *
     * @return the first post-burst update, or empty if no burst was detected
     */
    public Optional<Update> atBurst() {
        return updates.stream().filter(Update::afterBurst).findFirst();
    }

    /** @return the last re-prediction of the run, if there was one */
    public Optional<Update> last() {
        return updates.isEmpty() ? Optional.empty() : Optional.of(updates.get(updates.size() - 1));
    }

    /**
     * The re-prediction closest to a given fraction of the flight.
     *
     * @param fraction how far through the flight, in [0, 1]
     * @return the nearest update, or empty if there were none
     */
    public Optional<Update> atFraction(double fraction) {
        if (updates.isEmpty()) {
            return Optional.empty();
        }
        double target = updates.get(updates.size() - 1).flightSeconds() * fraction;
        Update best = updates.get(0);
        for (Update u : updates) {
            if (Math.abs(u.flightSeconds() - target) < Math.abs(best.flightSeconds() - target)) {
                best = u;
            }
        }
        return Optional.of(best);
    }
}
