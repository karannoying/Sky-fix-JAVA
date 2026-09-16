package com.skyfix.domain;

/**
 * One instant of the flight — the row shape of {@code run_state}.
 *
 * <p>Immutable, so a history can be handed to a reporter or a listener without defensive copying.
 *
 * @param timeSeconds      seconds since launch
 * @param position         geodetic position; altitude is geometric above MSL, metres
 * @param verticalRateMs   vertical rate, m/s, positive upwards
 * @param diameterM        envelope diameter at this instant, m; after burst it is the burst
 *                         diameter, retained so the record stays interpretable
 * @param phase            which stage of flight this state belongs to
 * @param windExtrapolated whether the wind used here came from outside the sounding's height
 *                         range, and so was held at the last value (FR-2.2)
 */
public record BalloonState(
        double timeSeconds,
        GeoPoint position,
        double verticalRateMs,
        double diameterM,
        Phase phase,
        boolean windExtrapolated) {

    /** @return geometric altitude above MSL, metres */
    public double altitudeM() {
        return position.altitudeM();
    }

    /** @return latitude in degrees */
    public double latitudeDeg() {
        return position.latitudeDeg();
    }

    /** @return longitude in degrees */
    public double longitudeDeg() {
        return position.longitudeDeg();
    }

    /**
     * Returns a copy of this state in a different phase.
     *
     * @param newPhase the phase to stamp
     * @return the derived state
     */
    public BalloonState withPhase(Phase newPhase) {
        return new BalloonState(timeSeconds, position, verticalRateMs, diameterM, newPhase,
                windExtrapolated);
    }
}
