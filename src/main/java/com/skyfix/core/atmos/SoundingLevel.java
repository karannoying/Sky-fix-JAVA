package com.skyfix.core.atmos;

/**
 * One level of a radiosonde ascent, in SI. The row shape of {@code sounding_level}.
 *
 * @param heightGeopotentialM geopotential height, metres — the vertical coordinate radiosonde
 *                            files report and the one levels are sorted and interpolated on
 * @param pressurePa          pressure, Pa
 * @param temperatureK        temperature, K
 * @param windEastMs          eastward wind component, m/s
 * @param windNorthMs         northward wind component, m/s
 */
public record SoundingLevel(
        double heightGeopotentialM,
        double pressurePa,
        double temperatureK,
        double windEastMs,
        double windNorthMs) {

    /**
     * Converts a meteorological wind report to east-north components.
     *
     * <p>Radiosonde files give the direction the wind blows <em>from</em>, so the components point
     * 180 degrees the other way — the sign convention this method exists to get right once.
     *
     * @param heightGeopotentialM geopotential height, metres
     * @param pressurePa          pressure, Pa
     * @param temperatureK        temperature, K
     * @param windFromDeg         direction the wind blows from, degrees clockwise from true north
     * @param windSpeedMs         wind speed, m/s
     * @return the level with east-north wind components
     */
    public static SoundingLevel fromMeteorological(double heightGeopotentialM, double pressurePa,
                                                   double temperatureK, double windFromDeg,
                                                   double windSpeedMs) {
        double fromRad = Math.toRadians(windFromDeg);
        double east = -windSpeedMs * Math.sin(fromRad);
        double north = -windSpeedMs * Math.cos(fromRad);
        return new SoundingLevel(heightGeopotentialM, pressurePa, temperatureK, east, north);
    }

    /** @return wind speed at this level, m/s */
    public double windSpeedMs() {
        return Math.hypot(windEastMs, windNorthMs);
    }
}
