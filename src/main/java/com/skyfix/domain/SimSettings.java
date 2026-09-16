package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;

/**
 * Integration settings for a single flight (FR-2.3).
 *
 * <p>Every state record SKYFIX stores carries the integrator name and step size that produced it
 * (CLAUDE.md rule 3), and those come from here.
 */
public final class SimSettings {

    private final double stepSeconds;
    private final String integrator;
    private final double endSeconds;
    private final double groundElevationM;
    private final int stateSampleStride;

    private SimSettings(Builder b) {
        this.stepSeconds = b.stepSeconds;
        this.integrator = b.integrator;
        this.endSeconds = b.endSeconds;
        this.groundElevationM = b.groundElevationM;
        this.stateSampleStride = b.stateSampleStride;
    }

    /**
     * Starts a new builder with the documented defaults: RK4, a 0.25 s step (see ADR-13 for why
     * not 1 s), a 6 h ceiling on flight time, sea-level ground and every 10th state retained.
     *
     * @return a builder holding the defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return integration step size in seconds */
    public double stepSeconds() {
        return stepSeconds;
    }

    /** @return the integrator name, as resolved by {@code IntegratorFactory} */
    public String integrator() {
        return integrator;
    }

    /** @return the flight-time ceiling in seconds; exceeding it is a convergence failure */
    public double endSeconds() {
        return endSeconds;
    }

    /** @return ground elevation above MSL in metres; the flight ends on reaching it */
    public double groundElevationM() {
        return groundElevationM;
    }

    /**
     * @return how many integration steps pass between retained state records. A 1 s step over a
     *         3 h flight is ~11k states per member; retaining every 10th keeps a stored run to the
     *         ~40k rows BLUEPRINT §9 budgets for.
     */
    public int stateSampleStride() {
        return stateSampleStride;
    }

    /**
     * Returns a copy with a different step size. Revalidated, so a derived instance can never be
     * less valid than the one it came from.
     *
     * @param v step size, seconds
     * @return the derived settings
     * @throws ValidationException if the new step size breaks a rule
     */
    public SimSettings withStepSeconds(double v) throws ValidationException {
        return toBuilder().stepSeconds(v).build();
    }

    /**
     * Returns a copy using a different integrator — the T-V3 cross-check runs the same flight
     * twice this way.
     *
     * @param v integrator name
     * @return the derived settings
     * @throws ValidationException if the new integrator name is blank
     */
    public SimSettings withIntegrator(String v) throws ValidationException {
        return toBuilder().integrator(v).build();
    }

    /**
     * Returns a builder pre-loaded with these settings.
     *
     * @return a builder that would rebuild equal settings
     */
    public Builder toBuilder() {
        return new Builder()
                .stepSeconds(stepSeconds)
                .integrator(integrator)
                .endSeconds(endSeconds)
                .groundElevationM(groundElevationM)
                .stateSampleStride(stateSampleStride);
    }

    @Override
    public String toString() {
        return "SimSettings[" + integrator + ", dt=" + stepSeconds + " s, tEnd=" + endSeconds
                + " s, ground=" + groundElevationM + " m]";
    }

    /** Collects and validates integration settings. */
    public static final class Builder {

        /**
         * Default step, seconds. Chosen from the descent stability criterion, not by habit:
         * RK4 at a 1 s step leaves its stability region below roughly 6 km on a typical
         * parachute descent and oscillates (ADR-13). 0.25 s leaves about a threefold margin.
         */
        private double stepSeconds = 0.25;
        private String integrator = "RK4";
        private double endSeconds = 6.0 * 3600.0;
        private double groundElevationM = 0.0;
        private int stateSampleStride = 10;

        /** @param v step size, seconds @return this builder */
        public Builder stepSeconds(double v) {
            this.stepSeconds = v;
            return this;
        }

        /** @param v integrator name, RK4 or RKF45 @return this builder */
        public Builder integrator(String v) {
            this.integrator = v;
            return this;
        }

        /** @param v flight-time ceiling, seconds @return this builder */
        public Builder endSeconds(double v) {
            this.endSeconds = v;
            return this;
        }

        /** @param v ground elevation above MSL, metres @return this builder */
        public Builder groundElevationM(double v) {
            this.groundElevationM = v;
            return this;
        }

        /** @param v steps between retained state records @return this builder */
        public Builder stateSampleStride(int v) {
            this.stateSampleStride = v;
            return this;
        }

        /**
         * Validates and builds.
         *
         * @return the validated settings
         * @throws ValidationException naming the offending field (FR-2.3)
         */
        public SimSettings build() throws ValidationException {
            if (!(stepSeconds > 0.0)) {
                throw ValidationException.field("step_s", stepSeconds, "must be greater than 0");
            }
            if (!(endSeconds > 0.0)) {
                throw ValidationException.field("t_end_s", endSeconds,
                        "must be greater than the start time (0 s)");
            }
            if (stepSeconds > endSeconds) {
                throw ValidationException.field("step_s", stepSeconds,
                        "must not exceed t_end_s (" + endSeconds + ")");
            }
            if (integrator == null || integrator.isBlank()) {
                throw ValidationException.field("integrator", integrator, "must not be blank");
            }
            if (stateSampleStride < 1) {
                throw ValidationException.field("state_sample_stride", stateSampleStride,
                        "must be at least 1");
            }
            return new SimSettings(this);
        }
    }
}
