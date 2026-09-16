package com.skyfix.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the filter believes about the flight's parameters after an update (FR-3.2).
 *
 * <p>A median and a 5–95% band per parameter, rather than a point estimate, because the whole
 * claim of the project is that uncertainty is quantified rather than assumed away. The effective
 * sample size and resample count travel with it: a posterior whose ESS has collapsed to a handful
 * of particles is not worth the same as one drawn from a healthy set, and a reader has to be able
 * to tell.
 *
 * <p>Parameter names match the {@code estimate} table's CHECK constraint exactly, so a posterior
 * maps to rows without translation.
 */
public record Posterior(Instant epochUtc, Map<String, Band> parameters, double effectiveSampleSize,
                        int resampleCount, int particleCount) {

    /** Free lift at launch, kg. */
    public static final String FREE_LIFT = "FREE_LIFT";
    /** Ascent drag coefficient, dimensionless. */
    public static final String ASCENT_CD = "ASCENT_CD";
    /** Burst diameter as a multiple of the catalogue figure, dimensionless. */
    public static final String BURST_SCALE = "BURST_SCALE";
    /** Parachute drag coefficient, dimensionless. */
    public static final String CHUTE_CD = "CHUTE_CD";

    /**
     * @param epochUtc            the telemetry epoch this posterior was computed at
     * @param parameters          one band per estimated parameter, keyed by the names above
     * @param effectiveSampleSize Kish's ESS of the weights; ranges from 1 to the particle count
     * @param resampleCount       how many times the filter has resampled so far this flight
     * @param particleCount       how many particles the filter carries
     */
    public Posterior {
        parameters = Map.copyOf(parameters);
    }

    /**
     * A parameter's posterior summary.
     *
     * @param median the weighted median
     * @param p05    the weighted 5th percentile
     * @param p95    the weighted 95th percentile
     */
    public record Band(double median, double p05, double p95) {

        /**
         * @throws IllegalArgumentException if the percentiles are not ordered — the {@code estimate}
         *                                  table enforces {@code p05 <= median <= p95} as a CHECK,
         *                                  so a band that breaks it could not be stored anyway
         */
        public Band {
            if (!(p05 <= median && median <= p95)) {
                throw new IllegalArgumentException(
                        "band must satisfy p05 <= median <= p95, got " + p05 + ", " + median
                                + ", " + p95);
            }
        }

        /** @return the width of the 5-95% interval */
        public double width() {
            return p95 - p05;
        }

        /**
         * Whether a value falls inside the 5–95% band.
         *
         * @param value the value to test, typically a known truth
         * @return {@code true} if the band covers it
         */
        public boolean covers(double value) {
            return value >= p05 && value <= p95;
        }
    }

    /**
     * A parameter's band.
     *
     * @param name one of the parameter-name constants
     * @return the band
     * @throws IllegalArgumentException if the posterior does not carry that parameter
     */
    public Band band(String name) {
        Band band = parameters.get(name);
        if (band == null) {
            throw new IllegalArgumentException("no band for " + name + "; have "
                    + parameters.keySet());
        }
        return band;
    }

    /**
     * The posterior median as flight parameters, for re-prediction.
     *
     * @param nominalBurstDiameterM the catalogue burst diameter the scale multiplies
     * @param windScale             the wind scale to carry through; the filter does not estimate it
     * @return the median parameter set
     */
    public FlightParameters medianParameters(double nominalBurstDiameterM, double windScale) {
        return new FlightParameters(
                band(FREE_LIFT).median(),
                band(ASCENT_CD).median(),
                band(BURST_SCALE).median() * nominalBurstDiameterM,
                band(CHUTE_CD).median(),
                windScale);
    }

    /**
     * Whether the particle set has degenerated.
     *
     * <p>An ESS below a tenth of the particle count means almost all the weight sits on a few
     * particles, so the "posterior" is really a handful of samples wearing a distribution's
     * clothes. WARN-worthy rather than fatal (NFR-5).
     *
     * @return {@code true} if the effective sample size has collapsed
     */
    public boolean isDegenerate() {
        return effectiveSampleSize < particleCount / 10.0;
    }

    /**
     * Builds a posterior from per-parameter bands.
     *
     * @param epochUtc      the telemetry epoch
     * @param freeLift      free-lift band
     * @param ascentCd      ascent-Cd band
     * @param burstScale    burst-scale band
     * @param chuteCd       parachute-Cd band
     * @param ess           effective sample size
     * @param resampleCount resamples so far
     * @param particleCount particle count
     * @return the posterior
     */
    public static Posterior of(Instant epochUtc, Band freeLift, Band ascentCd, Band burstScale,
                               Band chuteCd, double ess, int resampleCount, int particleCount) {
        Map<String, Band> bands = new LinkedHashMap<>();
        bands.put(FREE_LIFT, freeLift);
        bands.put(ASCENT_CD, ascentCd);
        bands.put(BURST_SCALE, burstScale);
        bands.put(CHUTE_CD, chuteCd);
        return new Posterior(epochUtc, bands, ess, resampleCount, particleCount);
    }
}
