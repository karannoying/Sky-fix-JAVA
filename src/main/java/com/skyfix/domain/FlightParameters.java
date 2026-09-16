package com.skyfix.domain;

/**
 * The four physical parameters SKYFIX estimates from telemetry, plus the wind-error scale
 * (FR-3.2, ADR-7).
 *
 * <p>These are exactly the quantities a pre-flight prediction has to guess from a catalogue and
 * that the particle filter recovers from the flight itself. Separating them from
 * {@link BalloonConfig} is what lets one immutable configuration be shared across an ensemble
 * while each member carries its own dispersed parameter draw.
 *
 * @param freeLiftKg     free lift at launch, kg
 * @param ascentCd       ascent drag coefficient of the envelope, dimensionless
 * @param burstDiameterM diameter at which the envelope bursts, m
 * @param chuteCd        parachute drag coefficient, dimensionless
 * @param windScale      multiplier on the sounding wind vector; 1.0 trusts the sounding exactly,
 *                       and dispersing it is how the single-sounding error is carried into the
 *                       footprint rather than hidden (ADR-7)
 */
public record FlightParameters(
        double freeLiftKg,
        double ascentCd,
        double burstDiameterM,
        double chuteCd,
        double windScale) {

    /**
     * The nominal parameter set implied by a configuration — what a pre-flight prediction uses
     * before any telemetry has been seen.
     *
     * @param config the balloon configuration
     * @return the nominal parameters, with a wind scale of exactly 1.0
     */
    public static FlightParameters nominal(BalloonConfig config) {
        return new FlightParameters(
                config.freeLiftKg(),
                config.ascentCd(),
                config.burstDiameterM(),
                config.chuteCd(),
                1.0);
    }

    /** @param v free lift, kg @return a copy with the free lift replaced */
    public FlightParameters withFreeLiftKg(double v) {
        return new FlightParameters(v, ascentCd, burstDiameterM, chuteCd, windScale);
    }

    /** @param v ascent drag coefficient @return a copy with the ascent Cd replaced */
    public FlightParameters withAscentCd(double v) {
        return new FlightParameters(freeLiftKg, v, burstDiameterM, chuteCd, windScale);
    }

    /** @param v burst diameter, m @return a copy with the burst diameter replaced */
    public FlightParameters withBurstDiameterM(double v) {
        return new FlightParameters(freeLiftKg, ascentCd, v, chuteCd, windScale);
    }

    /** @param v parachute drag coefficient @return a copy with the parachute Cd replaced */
    public FlightParameters withChuteCd(double v) {
        return new FlightParameters(freeLiftKg, ascentCd, burstDiameterM, v, windScale);
    }

    /** @param v wind scale multiplier @return a copy with the wind scale replaced */
    public FlightParameters withWindScale(double v) {
        return new FlightParameters(freeLiftKg, ascentCd, burstDiameterM, chuteCd, v);
    }
}
