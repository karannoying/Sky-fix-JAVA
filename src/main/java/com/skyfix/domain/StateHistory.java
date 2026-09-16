package com.skyfix.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The recorded trajectory of one flight, plus the summary facts a run needs to be scored and
 * stored: where and when it landed, where it burst, and how often the wind field had to
 * extrapolate (FR-2.2, NFR-5).
 *
 * <p>Iterable over its retained states, so a reporter can walk a flight with a for-each loop.
 */
public final class StateHistory implements Iterable<BalloonState> {

    private final List<BalloonState> states;
    private final BalloonState landing;
    private final BalloonState burst;
    private final int windExtrapolatedCount;
    private final int stepCount;

    private StateHistory(List<BalloonState> states, BalloonState landing, BalloonState burst,
                         int windExtrapolatedCount, int stepCount) {
        this.states = List.copyOf(states);
        this.landing = landing;
        this.burst = burst;
        this.windExtrapolatedCount = windExtrapolatedCount;
        this.stepCount = stepCount;
    }

    /**
     * Starts a new builder.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the retained states in time order, unmodifiable */
    public List<BalloonState> states() {
        return states;
    }

    /**
     * @return the landing state, or empty if the flight never reached the ground inside the
     *         configured time ceiling
     */
    public Optional<BalloonState> landing() {
        return Optional.ofNullable(landing);
    }

    /** @return the state at which burst was detected, or empty if the balloon never burst */
    public Optional<BalloonState> burst() {
        return Optional.ofNullable(burst);
    }

    /** @return the landing position, or empty if the flight did not land */
    public Optional<GeoPoint> landingPoint() {
        return landing().map(BalloonState::position);
    }

    /** @return the burst altitude in metres, or empty if the balloon never burst */
    public Optional<Double> burstAltitudeM() {
        return burst().map(BalloonState::altitudeM);
    }

    /**
     * @return how many integration steps queried the wind field outside the sounding's height
     *         range. A non-zero count is a WARN in the run summary (NFR-5), because the wind above
     *         the top sounding level is held rather than known.
     */
    public int windExtrapolatedCount() {
        return windExtrapolatedCount;
    }

    /** @return the number of integration steps taken, including those not retained */
    public int stepCount() {
        return stepCount;
    }

    /** @return flight duration in seconds, measured to the last retained state */
    public double durationSeconds() {
        return states.isEmpty() ? 0.0 : states.get(states.size() - 1).timeSeconds();
    }

    /** @return the highest altitude reached, metres */
    public double apogeeM() {
        double max = Double.NEGATIVE_INFINITY;
        for (BalloonState s : states) {
            max = Math.max(max, s.altitudeM());
        }
        return max;
    }

    @Override
    public java.util.Iterator<BalloonState> iterator() {
        return states.iterator();
    }

    /** Accumulates states during an integration and freezes them into a history. */
    public static final class Builder {

        private final List<BalloonState> states = new ArrayList<>();
        private BalloonState landing;
        private BalloonState burst;
        private int windExtrapolatedCount;
        private int stepCount;

        /**
         * Retains one state.
         *
         * @param state the state to keep
         * @return this builder
         */
        public Builder add(BalloonState state) {
            states.add(state);
            return this;
        }

        /**
         * Records that a step was taken, whether or not its state was retained.
         *
         * @param windExtrapolated whether that step's wind query fell outside the sounding
         * @return this builder
         */
        public Builder countStep(boolean windExtrapolated) {
            stepCount++;
            if (windExtrapolated) {
                windExtrapolatedCount++;
            }
            return this;
        }

        /**
         * Records the burst state.
         *
         * @param state the state at which the envelope reached its burst diameter
         * @return this builder
         */
        public Builder burst(BalloonState state) {
            this.burst = state;
            return this;
        }

        /**
         * Records the landing state.
         *
         * @param state the state at ground contact
         * @return this builder
         */
        public Builder landing(BalloonState state) {
            this.landing = state;
            return this;
        }

        /**
         * Freezes the accumulated states into an immutable history.
         *
         * @return the history
         */
        public StateHistory build() {
            return new StateHistory(Collections.unmodifiableList(new ArrayList<>(states)),
                    landing, burst, windExtrapolatedCount, stepCount);
        }
    }
}
