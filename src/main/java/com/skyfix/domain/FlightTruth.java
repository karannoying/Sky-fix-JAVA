package com.skyfix.domain;

/**
 * What a synthetic flight really did — the answer T-V5 and T-V6 are scored against (FR-1.4, DS-6).
 *
 * <p>Stored alongside the telemetry as {@code flight_log.truth_json}. The estimator never sees it;
 * it exists so that "the posterior recovered ascent Cd within 5%" is a statement that can be
 * checked rather than asserted.
 *
 * @param seed            the seed this flight was generated from, so it regenerates exactly
 * @param parameters      the true flight parameters
 * @param burstAltitudeM  the altitude the envelope really burst at, metres
 * @param burstEpochUtc   when it burst, ISO-8601 UTC
 * @param landingLatDeg   where it really landed, degrees
 * @param landingLonDeg   where it really landed, degrees
 * @param landingEpochUtc when it landed, ISO-8601 UTC
 * @param sampleCount     how many telemetry samples the log holds
 */
public record FlightTruth(
        long seed,
        FlightParameters parameters,
        double burstAltitudeM,
        String burstEpochUtc,
        double landingLatDeg,
        double landingLonDeg,
        String landingEpochUtc,
        int sampleCount) {

    /** @return the true landing point */
    public GeoPoint landingPoint() {
        return new GeoPoint(landingLatDeg, landingLonDeg, 0.0);
    }

    /**
     * Landing error of a prediction against this truth.
     *
     * @param predicted the predicted landing point
     * @return the great-circle error in metres
     */
    public double landingErrorM(GeoPoint predicted) {
        return Geodesy.haversineMetres(landingPoint(), predicted);
    }
}
