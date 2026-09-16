package com.skyfix.domain;

/**
 * Spherical-earth geodesy: great-circle distance, bearing and advection (ADR-9).
 *
 * <p>SKYFIX advects the balloon on a sphere and scores landing error with the haversine formula
 * rather than a full WGS-84 geodesic. Over the ~400 km drift of the longest planned mission the
 * difference is far below the wind-field error that dominates the prediction; {@code GeodesyTest}
 * measures it against an independent Vincenty implementation and the report quotes that number as
 * a bounded error term rather than asserting it (ADR-9).
 *
 * <p>All angles are degrees at the API surface and radians internally. All distances are metres.
 */
public final class Geodesy {

    /** WGS-84 defining semi-major axis, metres. */
    public static final double WGS84_SEMI_MAJOR_AXIS_M = 6378137.0;

    /** WGS-84 defining inverse flattening, dimensionless. */
    public static final double WGS84_INVERSE_FLATTENING = 298.257223563;

    /** WGS-84 semi-minor axis, metres, derived from the two defining constants above. */
    public static final double WGS84_SEMI_MINOR_AXIS_M =
            WGS84_SEMI_MAJOR_AXIS_M * (1.0 - 1.0 / WGS84_INVERSE_FLATTENING);

    /**
     * Mean earth radius R1 = (2a + b) / 3, metres — derived here from the WGS-84 defining
     * constants rather than transcribed, so it cannot drift from them.
     */
    public static final double EARTH_MEAN_RADIUS_M =
            (2.0 * WGS84_SEMI_MAJOR_AXIS_M + WGS84_SEMI_MINOR_AXIS_M) / 3.0;

    private Geodesy() {
    }

    /**
     * Great-circle distance between two surface positions, by the haversine formula.
     *
     * <p>Altitude is ignored: this is the along-surface separation, which is what a recovery team
     * drives and what FR-4.1 scores.
     *
     * @param from first position
     * @param to   second position
     * @return distance in metres, never negative
     */
    public static double haversineMetres(GeoPoint from, GeoPoint to) {
        return haversineMetres(from.latitudeDeg(), from.longitudeDeg(),
                to.latitudeDeg(), to.longitudeDeg());
    }

    /**
     * Great-circle distance between two lat/lon pairs, by the haversine formula.
     *
     * @param lat1Deg latitude of the first point, degrees
     * @param lon1Deg longitude of the first point, degrees
     * @param lat2Deg latitude of the second point, degrees
     * @param lon2Deg longitude of the second point, degrees
     * @return distance in metres, never negative
     */
    public static double haversineMetres(double lat1Deg, double lon1Deg,
                                         double lat2Deg, double lon2Deg) {
        double phi1 = Math.toRadians(lat1Deg);
        double phi2 = Math.toRadians(lat2Deg);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lon2Deg - lon1Deg);

        double sinHalfDPhi = Math.sin(dPhi / 2.0);
        double sinHalfDLambda = Math.sin(dLambda / 2.0);
        double a = sinHalfDPhi * sinHalfDPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinHalfDLambda * sinHalfDLambda;
        // clamp guards against a rounding overshoot past 1 for antipodal-ish pairs
        double c = 2.0 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return EARTH_MEAN_RADIUS_M * c;
    }

    /**
     * Initial bearing (forward azimuth) along the great circle from one point to another.
     *
     * @param lat1Deg latitude of the origin, degrees
     * @param lon1Deg longitude of the origin, degrees
     * @param lat2Deg latitude of the destination, degrees
     * @param lon2Deg longitude of the destination, degrees
     * @return bearing in degrees clockwise from true north, normalised to [0, 360)
     */
    public static double initialBearingDeg(double lat1Deg, double lon1Deg,
                                           double lat2Deg, double lon2Deg) {
        double phi1 = Math.toRadians(lat1Deg);
        double phi2 = Math.toRadians(lat2Deg);
        double dLambda = Math.toRadians(lon2Deg - lon1Deg);

        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return normaliseDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    /**
     * Initial bearing from one position to another.
     *
     * @param from origin
     * @param to   destination
     * @return bearing in degrees clockwise from true north, normalised to [0, 360)
     */
    public static double initialBearingDeg(GeoPoint from, GeoPoint to) {
        return initialBearingDeg(from.latitudeDeg(), from.longitudeDeg(),
                to.latitudeDeg(), to.longitudeDeg());
    }

    /**
     * Advances a position along a great circle by a distance on a given bearing.
     *
     * <p>Used by the tests as an independent way to construct a point a known distance away;
     * the simulator advects by integrating local east-north velocity instead.
     *
     * @param origin     starting position; its altitude is carried through unchanged
     * @param bearingDeg bearing in degrees clockwise from true north
     * @param distanceM  distance along the great circle, metres
     * @return the destination position at the same altitude as {@code origin}
     */
    public static GeoPoint destination(GeoPoint origin, double bearingDeg, double distanceM) {
        double phi1 = Math.toRadians(origin.latitudeDeg());
        double lambda1 = Math.toRadians(origin.longitudeDeg());
        double theta = Math.toRadians(bearingDeg);
        double delta = distanceM / EARTH_MEAN_RADIUS_M;

        double sinPhi2 = Math.sin(phi1) * Math.cos(delta)
                + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta);
        double phi2 = Math.asin(Math.max(-1.0, Math.min(1.0, sinPhi2)));
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * sinPhi2);

        return new GeoPoint(Math.toDegrees(phi2), normaliseLongitude(Math.toDegrees(lambda2)),
                origin.altitudeM());
    }

    /**
     * Metres per degree of latitude at the spherical earth radius — constant with latitude.
     *
     * @return metres per degree of latitude
     */
    public static double metresPerDegreeLatitude() {
        return EARTH_MEAN_RADIUS_M * Math.PI / 180.0;
    }

    /**
     * Metres per degree of longitude at a given latitude.
     *
     * @param latitudeDeg latitude, degrees
     * @return metres per degree of longitude; approaches zero at the poles
     */
    public static double metresPerDegreeLongitude(double latitudeDeg) {
        return EARTH_MEAN_RADIUS_M * Math.cos(Math.toRadians(latitudeDeg)) * Math.PI / 180.0;
    }

    /**
     * Normalises a longitude to the half-open interval [-180, 180).
     *
     * @param longitudeDeg longitude in degrees, any magnitude
     * @return the equivalent longitude in [-180, 180)
     */
    public static double normaliseLongitude(double longitudeDeg) {
        double wrapped = (longitudeDeg + 180.0) % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        return wrapped - 180.0;
    }

    /**
     * Normalises an angle to [0, 360).
     *
     * @param degrees angle in degrees, any magnitude
     * @return the equivalent angle in [0, 360)
     */
    public static double normaliseDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }
}
