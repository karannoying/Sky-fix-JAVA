package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;

import java.util.List;

/**
 * How far each of the five dispersed parameters is allowed to vary (FR-2.4, ADR-7).
 *
 * <p>This is the object that turns "the catalogue says Cd is 0.45" into "Cd is 0.45 give or take
 * twenty percent, and here is what that does to the landing footprint". Four of the five are the
 * parameters the particle filter will later estimate; the fifth, {@code windScale}, is not a
 * property of the balloon at all — it is where the single-sounding wind assumption's error is
 * carried, so the footprint widens to cover it rather than hiding it (ADR-7).
 *
 * <p>Immutable, so one spec is shared across every ensemble thread.
 */
public final class DispersionSpec {

    private final Distribution freeLiftKg;
    private final Distribution ascentCd;
    private final Distribution burstDiameterM;
    private final Distribution chuteCd;
    private final Distribution windScale;

    private DispersionSpec(Builder b) {
        this.freeLiftKg = b.freeLiftKg;
        this.ascentCd = b.ascentCd;
        this.burstDiameterM = b.burstDiameterM;
        this.chuteCd = b.chuteCd;
        this.windScale = b.windScale;
    }

    /**
     * Starts a builder with every dimension fixed, so a caller disperses only what they mean to.
     *
     * @param config the nominal configuration the fixed values come from
     * @return a builder holding no dispersion at all
     */
    public static Builder around(BalloonConfig config) {
        return new Builder()
                .freeLiftKg(new Distribution.Fixed(config.freeLiftKg()))
                .ascentCd(new Distribution.Fixed(config.ascentCd()))
                .burstDiameterM(new Distribution.Fixed(config.burstDiameterM()))
                .chuteCd(new Distribution.Fixed(config.chuteCd()))
                .windScale(new Distribution.Fixed(1.0));
    }

    /**
     * The default pre-flight dispersion: the spreads a catalogue leaves a team guessing at.
     *
     * <p>verify: these spreads are engineering estimates of how well each quantity is known before
     * flight, not measured figures, and they must be justified in the report.
     *
     * <p>An earlier note here proposed fitting them to the posterior the estimator recovers across
     * the DS-6 flights. <strong>That would be circular and the proposal is withdrawn.</strong>
     * DS-6's truth parameters are themselves drawn from this specification, so a spread fitted to
     * them measures this constructor and nothing else. The quantity these numbers describe — how
     * well a team knows its balloon <em>before</em> launch — cannot be established from synthetic
     * flights at all. It needs either a manufacturer's stated tolerance or a population of real
     * flights with recorded fill data, and until one exists these remain stated estimates.
     *
     * @param config the nominal configuration to disperse around
     * @return the default specification
     * @throws ValidationException if the configuration holds a non-positive nominal value
     */
    public static DispersionSpec preflightDefault(BalloonConfig config)
            throws ValidationException {
        double sigmaLimit = 3.0;
        return around(config)
                .freeLiftKg(Distribution.TruncatedNormal.relative(
                        config.freeLiftKg(), 0.15, sigmaLimit))
                .ascentCd(clampedNormal(config.ascentCd(), 0.20, sigmaLimit, 0.1, 2.0))
                .burstDiameterM(Distribution.TruncatedNormal.relative(
                        config.burstDiameterM(), 0.10, sigmaLimit))
                .chuteCd(clampedNormal(config.chuteCd(), 0.15, sigmaLimit, 0.1, 2.0))
                .windScale(Distribution.TruncatedNormal.symmetric(1.0, 0.20, sigmaLimit))
                .build();
    }

    /**
     * A normal truncated both by a sigma limit and by a hard physical range — used for the drag
     * coefficients, which the schema and the builder both confine to [0.1, 2.0].
     */
    private static Distribution clampedNormal(double nominal, double relativeSigma,
                                              double sigmaLimit, double hardLow, double hardHigh)
            throws ValidationException {
        Distribution.TruncatedNormal wide =
                Distribution.TruncatedNormal.relative(nominal, relativeSigma, sigmaLimit);
        return new Distribution.TruncatedNormal(wide.mean(), wide.standardDeviation(),
                Math.max(hardLow, wide.low()), Math.min(hardHigh, wide.high()));
    }

    /** @return the distribution for free lift, kg */
    public Distribution freeLiftKg() {
        return freeLiftKg;
    }

    /** @return the distribution for the ascent drag coefficient */
    public Distribution ascentCd() {
        return ascentCd;
    }

    /** @return the distribution for burst diameter, m */
    public Distribution burstDiameterM() {
        return burstDiameterM;
    }

    /** @return the distribution for the parachute drag coefficient */
    public Distribution chuteCd() {
        return chuteCd;
    }

    /** @return the distribution for the wind-error scale, dimensionless (ADR-7) */
    public Distribution windScale() {
        return windScale;
    }

    /**
     * The five dimensions in a fixed order.
     *
     * <p>The order is part of the reproducibility contract: Latin-hypercube sampling assigns
     * dimension <i>d</i> its own permutation, so reordering this list would change every member's
     * parameters for the same seed and break T-R1.
     *
     * @return the distributions, ordered free lift, ascent Cd, burst diameter, chute Cd, wind scale
     */
    public List<Distribution> dimensions() {
        return List.of(freeLiftKg, ascentCd, burstDiameterM, chuteCd, windScale);
    }

    /** @return the number of dispersed dimensions, always five */
    public int dimensionCount() {
        return 5;
    }

    /**
     * Builds the parameter set at a given point of the unit hypercube.
     *
     * @param unitPoint one probability per dimension, each strictly inside (0, 1), in the order
     *                  {@link #dimensions()} gives
     * @return the parameters for that point
     * @throws IllegalArgumentException if the point does not have one coordinate per dimension
     */
    public FlightParameters at(double[] unitPoint) {
        if (unitPoint.length != dimensionCount()) {
            throw new IllegalArgumentException(
                    "expected " + dimensionCount() + " coordinates, got " + unitPoint.length);
        }
        return new FlightParameters(
                freeLiftKg.quantile(unitPoint[0]),
                ascentCd.quantile(unitPoint[1]),
                burstDiameterM.quantile(unitPoint[2]),
                chuteCd.quantile(unitPoint[3]),
                windScale.quantile(unitPoint[4]));
    }

    @Override
    public String toString() {
        return "DispersionSpec[free_lift=" + freeLiftKg.describe()
                + ", ascent_cd=" + ascentCd.describe()
                + ", burst_diameter=" + burstDiameterM.describe()
                + ", chute_cd=" + chuteCd.describe()
                + ", wind_scale=" + windScale.describe() + "]";
    }

    /** Collects the five per-parameter distributions. */
    public static final class Builder {

        private Distribution freeLiftKg;
        private Distribution ascentCd;
        private Distribution burstDiameterM;
        private Distribution chuteCd;
        private Distribution windScale;

        /** @param d distribution for free lift, kg @return this builder */
        public Builder freeLiftKg(Distribution d) {
            this.freeLiftKg = d;
            return this;
        }

        /** @param d distribution for the ascent drag coefficient @return this builder */
        public Builder ascentCd(Distribution d) {
            this.ascentCd = d;
            return this;
        }

        /** @param d distribution for burst diameter, m @return this builder */
        public Builder burstDiameterM(Distribution d) {
            this.burstDiameterM = d;
            return this;
        }

        /** @param d distribution for the parachute drag coefficient @return this builder */
        public Builder chuteCd(Distribution d) {
            this.chuteCd = d;
            return this;
        }

        /** @param d distribution for the wind-error scale @return this builder */
        public Builder windScale(Distribution d) {
            this.windScale = d;
            return this;
        }

        /**
         * Validates that every dimension has a distribution and builds.
         *
         * @return the immutable specification
         * @throws ValidationException naming the first dimension left unset
         */
        public DispersionSpec build() throws ValidationException {
            require("free_lift_kg", freeLiftKg);
            require("ascent_cd", ascentCd);
            require("burst_diameter_m", burstDiameterM);
            require("chute_cd", chuteCd);
            require("wind_scale", windScale);
            return new DispersionSpec(this);
        }

        private static void require(String field, Distribution d) throws ValidationException {
            if (d == null) {
                throw ValidationException.field(field, "absent",
                        "needs a distribution; use Distribution.Fixed to hold it constant");
            }
        }
    }
}
