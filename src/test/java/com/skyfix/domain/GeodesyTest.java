package com.skyfix.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * T-U-GEO — great-circle distance and bearing.
 *
 * <p>The oracles here are deliberately independent of the implementation under test:
 *
 * <ul>
 *   <li>five configurations whose separation is an exact multiple of the earth radius, so the
 *       expected distance is closed-form arithmetic rather than another haversine call;</li>
 *   <li>the spherical law of cosines, which reaches the same answer by different algebra;</li>
 *   <li>a Vincenty inverse solution on the WGS-84 ellipsoid, written in this test file, which
 *       measures the modelling error ADR-9 accepts instead of asserting it is small.</li>
 * </ul>
 */
class GeodesyTest {

    private static final double R = Geodesy.EARTH_MEAN_RADIUS_M;
    private static final double ONE_METRE = 1.0;

    @Test
    @DisplayName("T-U-GEO: earth radius is derived from the WGS-84 defining constants")
    void meanRadiusFollowsFromDefiningConstants() {
        // R1 = (2a + b) / 3 with b = a(1 - f). Recomputed here from the defining constants
        // alone, so a typo in either constant fails rather than propagating silently.
        double a = 6378137.0;
        double b = a * (1.0 - 1.0 / 298.257223563);
        assertThat(Geodesy.EARTH_MEAN_RADIUS_M).isEqualTo((2 * a + b) / 3.0, within(1e-9));
        assertThat(Geodesy.WGS84_SEMI_MINOR_AXIS_M).isEqualTo(b, within(1e-9));
    }

    @Test
    @DisplayName("T-U-GEO: five exactly-known separations agree within 1 m")
    void exactlyKnownSeparations() {
        // 1. A quarter of the equator is exactly (pi/2) R.
        assertThat(Geodesy.haversineMetres(0, 0, 0, 90))
                .isEqualTo(R * Math.PI / 2.0, within(ONE_METRE));

        // 2. Pole to pole down a meridian is exactly pi R.
        assertThat(Geodesy.haversineMetres(90, 0, -90, 0))
                .isEqualTo(R * Math.PI, within(ONE_METRE));

        // 3. Half the equator is exactly pi R.
        assertThat(Geodesy.haversineMetres(0, -90, 0, 90))
                .isEqualTo(R * Math.PI, within(ONE_METRE));

        // 4. One degree of latitude is exactly R * pi/180, at any longitude.
        assertThat(Geodesy.haversineMetres(45.0, 77.0, 46.0, 77.0))
                .isEqualTo(R * Math.PI / 180.0, within(ONE_METRE));

        // 5. A point is zero from itself.
        assertThat(Geodesy.haversineMetres(23.2599, 77.4126, 23.2599, 77.4126))
                .isEqualTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("T-U-GEO: haversine matches the spherical law of cosines")
    void matchesSphericalLawOfCosines() {
        double[][] pairs = {
                {23.2599, 77.4126, 23.4000, 77.9000},   // ~50 km, the HabSat drift scale
                {23.2599, 77.4126, 26.9124, 75.7873},   // ~450 km, the long-mission scale
                {-33.8688, 151.2093, -37.8136, 144.9631},
                {51.4779, -0.0015, 48.8584, 2.2945},
                {0.0, 179.9, 0.0, -179.9},              // across the antimeridian
        };
        for (double[] p : pairs) {
            assertThat(Geodesy.haversineMetres(p[0], p[1], p[2], p[3]))
                    .as("pair %s", java.util.Arrays.toString(p))
                    .isEqualTo(lawOfCosinesMetres(p[0], p[1], p[2], p[3]), within(ONE_METRE));
        }
    }

    @Test
    @DisplayName("T-U-GEO: bearings are exact on the cardinal directions and wrap correctly")
    void bearingsOnCardinalDirections() {
        assertThat(Geodesy.initialBearingDeg(0, 0, 10, 0)).isEqualTo(0.0, within(1e-9));
        assertThat(Geodesy.initialBearingDeg(0, 0, 0, 10)).isEqualTo(90.0, within(1e-9));
        assertThat(Geodesy.initialBearingDeg(0, 0, -10, 0)).isEqualTo(180.0, within(1e-9));
        assertThat(Geodesy.initialBearingDeg(0, 0, 0, -10)).isEqualTo(270.0, within(1e-9));

        // Due west is reported as 270, never as -90: the result is normalised to [0, 360).
        assertThat(Geodesy.initialBearingDeg(45, 10, 45, 9)).isBetween(260.0, 280.0);
        assertThat(Geodesy.normaliseDegrees(-90.0)).isEqualTo(270.0, within(1e-12));
        assertThat(Geodesy.normaliseDegrees(450.0)).isEqualTo(90.0, within(1e-12));
    }

    @Test
    @DisplayName("T-U-GEO: destination() inverts haversine and bearing")
    void destinationRoundTrips() {
        GeoPoint launch = new GeoPoint(23.2599, 77.4126, 500.0);
        for (double bearing : new double[]{0, 37, 90, 180, 271, 359}) {
            for (double distance : new double[]{1_000, 50_000, 400_000}) {
                GeoPoint there = Geodesy.destination(launch, bearing, distance);
                assertThat(Geodesy.haversineMetres(launch, there))
                        .as("%.0f m on bearing %.0f", distance, bearing)
                        .isEqualTo(distance, within(ONE_METRE));
                assertThat(Geodesy.initialBearingDeg(launch, there))
                        .isEqualTo(Geodesy.normaliseDegrees(bearing), within(1e-6));
                assertThat(there.altitudeM()).isEqualTo(launch.altitudeM());
            }
        }
    }

    @Test
    @DisplayName("T-U-GEO: longitude normalisation wraps across the antimeridian")
    void longitudeNormalisation() {
        assertThat(Geodesy.normaliseLongitude(181.0)).isEqualTo(-179.0, within(1e-12));
        assertThat(Geodesy.normaliseLongitude(-181.0)).isEqualTo(179.0, within(1e-12));
        assertThat(Geodesy.normaliseLongitude(77.0)).isEqualTo(77.0, within(1e-12));
        assertThat(Geodesy.normaliseLongitude(540.0)).isEqualTo(-180.0, within(1e-12));
    }

    @Test
    @DisplayName("ADR-9: spherical-vs-ellipsoidal error over 400 km is measured, not assumed")
    void sphericalErrorAgainstVincentyOverMissionScale() {
        // ADR-9 accepts a spherical earth and owes the report a number for what that costs.
        // Vincenty's inverse formula on the WGS-84 ellipsoid is implemented below as the oracle.
        // The assertion is loose on purpose: its job is to pin the order of magnitude so the
        // figure printed here cannot silently drift, not to re-derive geodesy.
        double[][] pairs = {
                {23.2599, 77.4126, 26.9124, 75.7873},
                {23.2599, 77.4126, 23.2599, 81.3200},
                {23.2599, 77.4126, 19.7515, 75.7139},
        };
        double worstRelative = 0.0;
        for (double[] p : pairs) {
            double sphere = Geodesy.haversineMetres(p[0], p[1], p[2], p[3]);
            double ellipsoid = vincentyMetres(p[0], p[1], p[2], p[3]);
            worstRelative = Math.max(worstRelative, Math.abs(sphere - ellipsoid) / ellipsoid);
        }
        System.out.printf("ADR-9 measured: haversine vs Vincenty over ~400 km "
                + "differs by at most %.4f %% %n", worstRelative * 100.0);
        assertThat(worstRelative)
                .as("spherical-earth error at mission scale")
                .isLessThan(0.01); // under 1 %, far below the wind-field error that dominates
    }

    /** Great-circle distance by the spherical law of cosines — a different algebraic path. */
    private static double lawOfCosinesMetres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double cos = Math.sin(p1) * Math.sin(p2) + Math.cos(p1) * Math.cos(p2) * Math.cos(dl);
        return R * Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
    }

    /**
     * Vincenty's inverse solution on the WGS-84 ellipsoid. Test-only: it is the independent
     * oracle for ADR-9 and never runs in {@code src/main}.
     */
    private static double vincentyMetres(double lat1, double lon1, double lat2, double lon2) {
        double a = Geodesy.WGS84_SEMI_MAJOR_AXIS_M;
        double f = 1.0 / Geodesy.WGS84_INVERSE_FLATTENING;
        double b = Geodesy.WGS84_SEMI_MINOR_AXIS_M;

        double lambdaDiff = Math.toRadians(lon2 - lon1);
        double u1 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat1)));
        double u2 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat2)));
        double sinU1 = Math.sin(u1), cosU1 = Math.cos(u1);
        double sinU2 = Math.sin(u2), cosU2 = Math.cos(u2);

        double lambda = lambdaDiff;
        double sinSigma = 0, cosSigma = 0, sigma = 0, cos2SigmaM = 0, cosSqAlpha = 0;
        for (int i = 0; i < 200; i++) {
            double sinLambda = Math.sin(lambda), cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt(Math.pow(cosU2 * sinLambda, 2)
                    + Math.pow(cosU1 * sinU2 - sinU1 * cosU2 * cosLambda, 2));
            if (sinSigma == 0) {
                return 0.0; // coincident points
            }
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
            double c = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
            double previous = lambda;
            lambda = lambdaDiff + (1 - c) * f * sinAlpha
                    * (sigma + c * sinSigma
                       * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
            if (Math.abs(lambda - previous) < 1e-12) {
                break;
            }
        }
        double uSq = cosSqAlpha * (a * a - b * b) / (b * b);
        double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        double deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4
                * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                   - bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma)
                     * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        return b * bigA * (sigma - deltaSigma);
    }
}
