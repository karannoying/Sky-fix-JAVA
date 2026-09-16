package com.skyfix.core.atmos;

/**
 * A wind vector in the local east-north-up frame (CLAUDE.md rule 3).
 *
 * @param eastMs       eastward component, m/s
 * @param northMs      northward component, m/s
 * @param extrapolated whether this sample came from outside the wind field's known height range
 *                     and was therefore held at the nearest known value (FR-2.2)
 */
public record WindSample(double eastMs, double northMs, boolean extrapolated) {

    /** A dead-calm sample inside the known range. */
    public static final WindSample CALM = new WindSample(0.0, 0.0, false);

    /** @return wind speed, m/s */
    public double speedMs() {
        return Math.hypot(eastMs, northMs);
    }

    /**
     * Meteorological wind direction — the bearing the wind blows <em>from</em>, which is how
     * radiosonde files report it.
     *
     * @return direction in degrees clockwise from true north, in [0, 360)
     */
    public double fromDirectionDeg() {
        double toDirection = Math.toDegrees(Math.atan2(eastMs, northMs));
        double from = toDirection + 180.0;
        double wrapped = from % 360.0;
        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }

    /**
     * Returns this sample with both components scaled — how the ensemble disperses wind error
     * into the footprint (ADR-7).
     *
     * @param factor multiplier applied to both components
     * @return the scaled sample, carrying the same extrapolation flag
     */
    public WindSample scaled(double factor) {
        return new WindSample(eastMs * factor, northMs * factor, extrapolated);
    }
}
