package com.skyfix.core.flight;

import com.skyfix.domain.Gaussian;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.error.ValidationException;

import java.util.List;

/**
 * Fits a confidence ellipse to a scatter of landing points (FR-2.4).
 *
 * <p>This is the step that turns an ensemble into an answer. The landing points are projected onto
 * a local east-north tangent plane about their mean — so the covariance is in metres rather than
 * in degrees, which would be anisotropic away from the equator — and the 2x2 covariance matrix is
 * eigen-decomposed in closed form:
 *
 * <pre>
 *   C = [ a  b ]      trace T = a + c,  determinant D = ac - b^2
 *       [ b  c ]      lambda = T/2 +/- sqrt(T^2/4 - D)
 * </pre>
 *
 * <p>The eigenvalues are the variances along the principal axes, so the semi-axes are
 * {@code s * sqrt(lambda)} where {@code s} is the confidence scale factor from
 * {@link Gaussian#ellipseScaleFactor}. The eigenvector of the larger eigenvalue gives the
 * orientation.
 *
 * <p>A 2x2 symmetric eigenproblem has an exact closed form, so nothing here iterates and there is
 * no convergence criterion to tune — which is also why the whole decomposition is a dozen lines of
 * arithmetic rather than a reason to reach for a library (CLAUDE.md rule 1).
 */
public final class EllipseFitter {

    private EllipseFitter() {
    }

    /**
     * Fits an ellipse at a given confidence level.
     *
     * @param landingPoints the members' landing points; at least three are needed for a covariance
     *                      that describes a shape rather than a line
     * @param confidence    the confidence level, strictly between 0 and 1
     * @param groundElevationM ground elevation for the centre point, metres above MSL
     * @return the fitted ellipse
     * @throws ValidationException if there are too few points, or the confidence is out of range
     */
    public static LandingEllipse fit(List<GeoPoint> landingPoints, double confidence,
                                     double groundElevationM) throws ValidationException {
        if (landingPoints == null || landingPoints.size() < 3) {
            throw ValidationException.field("member_count",
                    landingPoints == null ? 0 : landingPoints.size(),
                    "must be at least 3 to fit an ellipse");
        }
        if (!(confidence > 0.0 && confidence < 1.0)) {
            throw ValidationException.field("confidence", confidence,
                    "must lie strictly between 0 and 1");
        }

        GeoPoint centre = centroid(landingPoints, groundElevationM);

        // Project onto a local east-north plane about the centre. Over a footprint a few tens of
        // kilometres across this is accurate to far better than the wind error that produced it.
        double metresPerDegreeLat = Geodesy.metresPerDegreeLatitude();
        double metresPerDegreeLon = Geodesy.metresPerDegreeLongitude(centre.latitudeDeg());

        int n = landingPoints.size();
        double sumEE = 0, sumNN = 0, sumEN = 0;
        for (GeoPoint p : landingPoints) {
            double east = Geodesy.normaliseLongitude(p.longitudeDeg() - centre.longitudeDeg())
                    * metresPerDegreeLon;
            double north = (p.latitudeDeg() - centre.latitudeDeg()) * metresPerDegreeLat;
            sumEE += east * east;
            sumNN += north * north;
            sumEN += east * north;
        }
        // Sample covariance, n-1 for the unbiased estimate.
        double a = sumEE / (n - 1);   // var(east)
        double c = sumNN / (n - 1);   // var(north)
        double b = sumEN / (n - 1);   // cov(east, north)

        // Closed-form eigenvalues of the symmetric 2x2 covariance.
        double trace = a + c;
        double determinant = a * c - b * b;
        double discriminant = Math.sqrt(Math.max(0.0, trace * trace / 4.0 - determinant));
        double lambdaMajor = trace / 2.0 + discriminant;
        double lambdaMinor = Math.max(0.0, trace / 2.0 - discriminant);

        double scale = Gaussian.ellipseScaleFactor(confidence);
        double semiMajor = scale * Math.sqrt(lambdaMajor);
        double semiMinor = scale * Math.sqrt(lambdaMinor);

        // A degenerate scatter -- every member landing on one point, or exactly on a line -- would
        // give a zero axis, which the schema forbids and which is meaningless as a footprint.
        // A floor of one centimetre keeps the record storable and is far below any real dispersion.
        semiMajor = Math.max(semiMajor, 0.01);
        semiMinor = Math.max(Math.min(semiMinor, semiMajor), 0.01);

        return new LandingEllipse(confidence, centre, semiMajor, semiMinor,
                azimuthOfMajorAxis(a, b, lambdaMajor), n);
    }

    /**
     * The mean landing position.
     *
     * @param points           the landing points
     * @param groundElevationM ground elevation, metres above MSL
     * @return the centroid, at the given ground elevation
     */
    public static GeoPoint centroid(List<GeoPoint> points, double groundElevationM) {
        double sumLat = 0;
        double sumLon = 0;
        for (GeoPoint p : points) {
            sumLat += p.latitudeDeg();
            sumLon += p.longitudeDeg();
        }
        return new GeoPoint(sumLat / points.size(),
                Geodesy.normaliseLongitude(sumLon / points.size()), groundElevationM);
    }

    /**
     * Fraction of points that fall inside an ellipse — the empirical containment T-V7 checks.
     *
     * <p>A stated 95% ellipse that actually holds 70% of the members is not calibrated, and
     * nothing but this measurement would reveal it.
     *
     * @param ellipse the ellipse
     * @param points  the points to test
     * @return the fraction contained, between 0 and 1
     */
    public static double empiricalContainment(LandingEllipse ellipse, List<GeoPoint> points) {
        if (points.isEmpty()) {
            return 0.0;
        }
        long inside = points.stream().filter(ellipse::contains).count();
        return (double) inside / points.size();
    }

    /**
     * Bearing of the major axis, degrees clockwise from true north.
     *
     * <p>The eigenvector for {@code lambdaMajor} solves {@code (a - lambda) e + b n = 0}, so
     * {@code (e, n) = (b, lambda - a)} up to scale — using the other row, {@code (lambda - c, b)},
     * when that one degenerates.
     */
    private static double azimuthOfMajorAxis(double varEast, double covariance,
                                             double lambdaMajor) {
        double east;
        double north;
        if (Math.abs(covariance) > 1e-15) {
            east = covariance;
            north = lambdaMajor - varEast;
        } else {
            // No correlation: the axes are already east-west and north-south. The major axis is
            // whichever direction has the larger variance.
            east = (lambdaMajor == varEast) ? 1.0 : 0.0;
            north = (lambdaMajor == varEast) ? 0.0 : 1.0;
        }
        // Bearing from north, clockwise: atan2(east, north). An axis is undirected, so the
        // result is folded into [0, 180) -- a major axis pointing north-east and one pointing
        // south-west describe the same ellipse.
        double degrees = Math.toDegrees(Math.atan2(east, north));
        double normalised = Geodesy.normaliseDegrees(degrees);
        return normalised >= 180.0 ? normalised - 180.0 : normalised;
    }
}
