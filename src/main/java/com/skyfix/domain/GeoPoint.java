package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;

/**
 * An immutable WGS-84 geodetic position with a geometric altitude above mean sea level.
 *
 * @param latitudeDeg  latitude in degrees, -90 to 90
 * @param longitudeDeg longitude in degrees, -180 to 180
 * @param altitudeM    geometric altitude above MSL, metres
 */
public record GeoPoint(double latitudeDeg, double longitudeDeg, double altitudeM) {

    /**
     * Creates a position, validating the angular ranges.
     *
     * @throws IllegalArgumentException if latitude or longitude is outside its range, or any
     *                                  component is NaN — these indicate a programmer error at the
     *                                  call site; user input is screened by
     *                                  {@link #of(double, double, double)} instead
     */
    public GeoPoint {
        if (Double.isNaN(latitudeDeg) || Double.isNaN(longitudeDeg) || Double.isNaN(altitudeM)) {
            throw new IllegalArgumentException("GeoPoint components must not be NaN");
        }
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude " + latitudeDeg + " outside [-90, 90]");
        }
        if (longitudeDeg < -180.0 || longitudeDeg > 180.0) {
            throw new IllegalArgumentException("longitude " + longitudeDeg + " outside [-180, 180]");
        }
    }

    /**
     * Creates a position from user-supplied values, reporting range failures as a
     * {@link ValidationException} that names the offending field.
     *
     * @param latitudeDeg  latitude in degrees
     * @param longitudeDeg longitude in degrees
     * @param altitudeM    geometric altitude above MSL, metres
     * @return the validated position
     * @throws ValidationException if latitude or longitude is out of range
     */
    public static GeoPoint of(double latitudeDeg, double longitudeDeg, double altitudeM)
            throws ValidationException {
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw ValidationException.field("latitude", latitudeDeg, "must be between -90 and 90");
        }
        if (longitudeDeg < -180.0 || longitudeDeg > 180.0) {
            throw ValidationException.field("longitude", longitudeDeg,
                    "must be between -180 and 180");
        }
        return new GeoPoint(latitudeDeg, longitudeDeg, altitudeM);
    }

    /**
     * Returns this position moved to a different altitude.
     *
     * @param newAltitudeM geometric altitude above MSL, metres
     * @return a new position with the same latitude and longitude
     */
    public GeoPoint atAltitude(double newAltitudeM) {
        return new GeoPoint(latitudeDeg, longitudeDeg, newAltitudeM);
    }

    /**
     * Great-circle distance from this position to another, ignoring altitude.
     *
     * @param other the other position
     * @return distance in metres
     */
    public double distanceTo(GeoPoint other) {
        return Geodesy.haversineMetres(this, other);
    }

    @Override
    public String toString() {
        return String.format("%.6f, %.6f @ %.1f m", latitudeDeg, longitudeDeg, altitudeM);
    }
}
