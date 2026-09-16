package com.skyfix.core.flight;

import com.skyfix.domain.Gaussian;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-U-ELLIPSE — recovering a known ellipse from a synthetic cloud.
 *
 * <p>The oracle is construction: a cloud is generated with semi-axes and an orientation chosen in
 * advance, and the fitter has to recover them. That is a real inverse check — unlike asserting
 * that the fitter agrees with itself, it fails if the covariance, the eigen-decomposition or the
 * confidence scaling is wrong in any way that matters.
 *
 * <p>Containment is then measured separately, because axes that are right and a scale factor that
 * is wrong would still produce an ellipse holding the wrong fraction of the cloud.
 */
class EllipseFitterTest {

    private static final GeoPoint CENTRE = new GeoPoint(23.2599, 78.4126, 500.0);

    /**
     * Builds a cloud with a known covariance: independent normal draws with the given standard
     * deviations, rotated by {@code bearingDeg} clockwise from north, laid about {@code CENTRE}.
     */
    private static List<GeoPoint> cloud(int n, double sigmaMajorM, double sigmaMinorM,
                                        double bearingDeg, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        double a = Math.toRadians(bearingDeg);
        double metresPerDegreeLat = Geodesy.metresPerDegreeLatitude();
        double metresPerDegreeLon = Geodesy.metresPerDegreeLongitude(CENTRE.latitudeDeg());

        List<GeoPoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double major = sigmaMajorM * Gaussian.inverseCdf(random.nextDouble());
            double minor = sigmaMinorM * Gaussian.inverseCdf(random.nextDouble());
            // Major axis along (sin a, cos a) in (east, north), minor perpendicular to it.
            double east = major * Math.sin(a) + minor * Math.cos(a);
            double north = major * Math.cos(a) - minor * Math.sin(a);
            points.add(new GeoPoint(
                    CENTRE.latitudeDeg() + north / metresPerDegreeLat,
                    CENTRE.longitudeDeg() + east / metresPerDegreeLon,
                    500.0));
        }
        return points;
    }

    @Test
    @DisplayName("T-U-ELLIPSE: known semi-axes are recovered within 2 %")
    void recoversKnownSemiAxes() throws Exception {
        double sigmaMajor = 8_000.0;
        double sigmaMinor = 2_500.0;
        double bearing = 35.0;

        List<GeoPoint> points = cloud(20_000, sigmaMajor, sigmaMinor, bearing, 42L);
        LandingEllipse ellipse = EllipseFitter.fit(points, 0.95, 500.0);

        // The 95 % ellipse's semi-axes are s * sigma, with s the chi-square scale factor.
        double s = Gaussian.ellipseScaleFactor(0.95);
        System.out.printf("T-U-ELLIPSE: recovered %.1f x %.1f m at %.2f deg "
                        + "(built from %.1f x %.1f m at %.1f deg)%n",
                ellipse.semiMajorM(), ellipse.semiMinorM(), ellipse.azimuthDeg(),
                s * sigmaMajor, s * sigmaMinor, bearing);

        assertThat(ellipse.semiMajorM())
                .isCloseTo(s * sigmaMajor, within(0.02 * s * sigmaMajor));
        assertThat(ellipse.semiMinorM())
                .isCloseTo(s * sigmaMinor, within(0.02 * s * sigmaMinor));
        assertThat(ellipse.azimuthDeg()).isCloseTo(bearing, within(2.0));
        assertThat(ellipse.memberCount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("T-U-ELLIPSE: orientation is recovered at every bearing, including the wraps")
    void recoversOrientationAtEveryBearing() throws Exception {
        for (double bearing : new double[]{0, 20, 45, 90, 120, 170}) {
            List<GeoPoint> points = cloud(20_000, 9_000.0, 2_000.0, bearing, 7L);
            LandingEllipse ellipse = EllipseFitter.fit(points, 0.95, 500.0);
            // An axis is undirected, so 179 and 1 degrees are two degrees apart, not 178.
            double error = Math.abs(ellipse.azimuthDeg() - bearing);
            error = Math.min(error, 180.0 - error);
            assertThat(error).as("orientation error at bearing %.0f", bearing).isLessThan(2.0);
        }
    }

    @Test
    @DisplayName("T-U-ELLIPSE: a circular cloud gives an aspect ratio near one")
    void circularCloudIsCircular() throws Exception {
        LandingEllipse ellipse =
                EllipseFitter.fit(cloud(20_000, 5_000.0, 5_000.0, 0.0, 3L), 0.95, 500.0);
        assertThat(ellipse.aspectRatio()).isCloseTo(1.0, within(0.05));
        assertThat(ellipse.semiMinorM()).isLessThanOrEqualTo(ellipse.semiMajorM());
    }

    @Test
    @DisplayName("The ellipse contains the fraction of members it claims to")
    void empiricalContainmentMatchesTheStatedConfidence() throws Exception {
        // Axes can be right while the confidence scaling is wrong. Only counting members inside
        // catches that -- and this is the check T-V7 will make against real ensembles.
        List<GeoPoint> points = cloud(20_000, 6_000.0, 2_000.0, 60.0, 11L);

        for (double confidence : new double[]{0.5, 0.9, 0.95}) {
            LandingEllipse ellipse = EllipseFitter.fit(points, confidence, 500.0);
            double contained = EllipseFitter.empiricalContainment(ellipse, points);
            System.out.printf("  containment: stated %.0f %%, measured %.1f %%%n",
                    confidence * 100, contained * 100);
            assertThat(contained)
                    .as("empirical containment of the %.0f %% ellipse", confidence * 100)
                    .isCloseTo(confidence, within(0.02));
        }
    }

    @Test
    @DisplayName("Area and aspect ratio follow from the axes")
    void derivedQuantities() throws Exception {
        LandingEllipse ellipse =
                EllipseFitter.fit(cloud(5_000, 4_000.0, 1_000.0, 0.0, 5L), 0.95, 500.0);
        assertThat(ellipse.areaKm2())
                .isCloseTo(Math.PI * ellipse.semiMajorM() * ellipse.semiMinorM() / 1e6,
                        within(1e-9));
        assertThat(ellipse.aspectRatio())
                .isCloseTo(ellipse.semiMajorM() / ellipse.semiMinorM(), within(1e-9));
        assertThat(ellipse.toString()).contains("95%").contains("km2");
    }

    @Test
    @DisplayName("A larger confidence gives a strictly larger ellipse about the same centre")
    void confidenceScalesTheEllipse() throws Exception {
        List<GeoPoint> points = cloud(5_000, 3_000.0, 1_500.0, 10.0, 13L);
        LandingEllipse fifty = EllipseFitter.fit(points, 0.50, 500.0);
        LandingEllipse ninetyFive = EllipseFitter.fit(points, 0.95, 500.0);

        assertThat(ninetyFive.semiMajorM()).isGreaterThan(fifty.semiMajorM());
        assertThat(ninetyFive.areaKm2()).isGreaterThan(fifty.areaKm2());
        // The axes scale by exactly the ratio of the two scale factors -- the shape is the same.
        double expectedRatio = Gaussian.ellipseScaleFactor(0.95) / Gaussian.ellipseScaleFactor(0.5);
        assertThat(ninetyFive.semiMajorM() / fifty.semiMajorM())
                .isCloseTo(expectedRatio, within(1e-9));
        assertThat(ninetyFive.centre().latitudeDeg())
                .isEqualTo(fifty.centre().latitudeDeg(), within(1e-12));
        assertThat(ninetyFive.azimuthDeg()).isEqualTo(fifty.azimuthDeg(), within(1e-9));
    }

    @Test
    @DisplayName("The centre is the mean landing position")
    void centreIsTheMean() throws Exception {
        List<GeoPoint> points = cloud(10_000, 4_000.0, 4_000.0, 0.0, 17L);
        LandingEllipse ellipse = EllipseFitter.fit(points, 0.95, 750.0);

        double meanLat = points.stream().mapToDouble(GeoPoint::latitudeDeg).average().orElseThrow();
        assertThat(ellipse.centre().latitudeDeg()).isEqualTo(meanLat, within(1e-12));
        assertThat(ellipse.centre().altitudeM())
                .as("the centre sits at ground elevation").isEqualTo(750.0);
    }

    @Test
    @DisplayName("contains() agrees with the geometry at the axis endpoints")
    void containsIsGeometricallyCorrect() {
        LandingEllipse ellipse = new LandingEllipse(0.95, CENTRE, 10_000.0, 4_000.0, 90.0, 100);
        // Azimuth 90 means the major axis runs east-west.
        assertThat(ellipse.contains(CENTRE)).isTrue();
        assertThat(ellipse.contains(Geodesy.destination(CENTRE, 90.0, 9_900.0))).isTrue();
        assertThat(ellipse.contains(Geodesy.destination(CENTRE, 90.0, 10_100.0))).isFalse();
        assertThat(ellipse.contains(Geodesy.destination(CENTRE, 0.0, 3_900.0))).isTrue();
        assertThat(ellipse.contains(Geodesy.destination(CENTRE, 0.0, 4_100.0))).isFalse();
        // And symmetric in the opposite direction.
        assertThat(ellipse.contains(Geodesy.destination(CENTRE, 270.0, 9_900.0))).isTrue();
        assertThat(ellipse.contains(Geodesy.destination(CENTRE, 180.0, 4_100.0))).isFalse();
    }

    @Test
    @DisplayName("A degenerate scatter still yields a storable ellipse rather than a zero axis")
    void degenerateScatterIsHandled() throws Exception {
        // Every member landing in the same place would give zero variance, which the schema's
        // semi_major_m > 0 CHECK forbids. The fit floors the axes instead of producing a record
        // that cannot be written.
        List<GeoPoint> identical = List.of(CENTRE, CENTRE, CENTRE, CENTRE);
        LandingEllipse ellipse = EllipseFitter.fit(identical, 0.95, 500.0);
        assertThat(ellipse.semiMajorM()).isPositive();
        assertThat(ellipse.semiMinorM()).isPositive().isLessThanOrEqualTo(ellipse.semiMajorM());

        // Points exactly on a line: the minor axis collapses but the record stays valid.
        List<GeoPoint> collinear = new ArrayList<>();
        for (int i = -50; i <= 50; i++) {
            collinear.add(Geodesy.destination(CENTRE, 45.0, i * 100.0));
        }
        LandingEllipse line = EllipseFitter.fit(collinear, 0.95, 500.0);
        assertThat(line.aspectRatio()).isGreaterThan(100.0);
        assertThat(line.azimuthDeg()).isCloseTo(45.0, within(1.0));
    }

    @Test
    @DisplayName("Too few points, or a nonsense confidence, is rejected by name")
    void degenerateInputsRejected() {
        assertThatThrownBy(() -> EllipseFitter.fit(List.of(CENTRE, CENTRE), 0.95, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("member_count");
        assertThatThrownBy(() -> EllipseFitter.fit(null, 0.95, 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> EllipseFitter.fit(cloud(10, 1, 1, 0, 1L), 1.0, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("confidence");
        assertThatThrownBy(() -> EllipseFitter.fit(cloud(10, 1, 1, 0, 1L), 0.0, 0))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("LandingEllipse enforces the invariants the schema also enforces")
    void ellipseInvariants() {
        assertThatThrownBy(() -> new LandingEllipse(1.0, CENTRE, 100, 50, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LandingEllipse(0.95, CENTRE, 0, 50, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LandingEllipse(0.95, CENTRE, 50, 100, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semi-minor");
        assertThatThrownBy(() -> new LandingEllipse(0.95, CENTRE, 100, 50, 400, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azimuth");
    }
}
