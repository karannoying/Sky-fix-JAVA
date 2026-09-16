package com.skyfix.domain;

import java.time.Instant;

/**
 * One telemetry packet as received from the flight computer. Row shape of {@code telemetry_sample}.
 *
 * <p>Three fields are optional because a real packet often lacks them: a GPS fix can drop while the
 * pressure sensor keeps reporting, and some payloads carry no thermometer at all. Absence is
 * {@link Double#NaN} rather than a boxed null, so the arithmetic in the estimator does not have to
 * unbox on every sample, and a missing value propagates visibly instead of silently reading as
 * zero — a dropped GPS altitude treated as 0 m would look like a landing.
 *
 * @param epochUtc      when the packet was sampled
 * @param packetId      the flight computer's sequence number; unique within a log (FR-1.2)
 * @param latitudeDeg   latitude, degrees
 * @param longitudeDeg  longitude, degrees
 * @param altitudeGpsM  GPS altitude above MSL in metres, or NaN if the fix was lost
 * @param pressurePa    ambient pressure in Pa, or NaN if not reported
 * @param temperatureK  ambient temperature in K, or NaN if not reported
 * @param qualityFlags  bit field of {@link #FLAG_GPS_DROPOUT} and friends
 */
public record TelemetrySample(
        Instant epochUtc,
        int packetId,
        double latitudeDeg,
        double longitudeDeg,
        double altitudeGpsM,
        double pressurePa,
        double temperatureK,
        int qualityFlags) {

    /** The GPS fix was absent for this packet. */
    public static final int FLAG_GPS_DROPOUT = 1;
    /** This packet's timestamp arrived before its predecessor's. */
    public static final int FLAG_OUT_OF_ORDER = 1 << 1;
    /** Altitude was derived from pressure because the GPS fix was missing. */
    public static final int FLAG_PRESSURE_ALTITUDE = 1 << 2;

    /** @return whether a GPS altitude is present */
    public boolean hasGpsAltitude() {
        return !Double.isNaN(altitudeGpsM);
    }

    /** @return whether a pressure reading is present */
    public boolean hasPressure() {
        return !Double.isNaN(pressurePa);
    }

    /** @return whether a temperature reading is present */
    public boolean hasTemperature() {
        return !Double.isNaN(temperatureK);
    }

    /**
     * Whether a quality flag is set.
     *
     * @param flag one of the {@code FLAG_} constants
     * @return {@code true} if the flag is set
     */
    public boolean hasFlag(int flag) {
        return (qualityFlags & flag) != 0;
    }

    /**
     * Returns a copy with additional quality flags set.
     *
     * @param flags flags to add
     * @return the derived sample
     */
    public TelemetrySample withFlags(int flags) {
        return new TelemetrySample(epochUtc, packetId, latitudeDeg, longitudeDeg, altitudeGpsM,
                pressurePa, temperatureK, qualityFlags | flags);
    }

    /**
     * Returns a copy carrying an altitude derived from pressure, flagged as such.
     *
     * @param altitudeM the derived altitude, metres above MSL
     * @return the derived sample
     */
    public TelemetrySample withDerivedAltitude(double altitudeM) {
        return new TelemetrySample(epochUtc, packetId, latitudeDeg, longitudeDeg, altitudeM,
                pressurePa, temperatureK, qualityFlags | FLAG_PRESSURE_ALTITUDE);
    }

    /**
     * The position this sample reports.
     *
     * @return the position; its altitude is the GPS altitude, or 0 if the fix was lost
     */
    public GeoPoint position() {
        return new GeoPoint(latitudeDeg, longitudeDeg, hasGpsAltitude() ? altitudeGpsM : 0.0);
    }
}
