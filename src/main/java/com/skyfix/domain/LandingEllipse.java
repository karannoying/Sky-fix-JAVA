package com.skyfix.domain;

/**
 * A landing footprint as a confidence ellipse — the answer this project exists to give
 * (FR-2.4, O2). Row shape of {@code landing_ellipse}.
 *
 * <p>A recovery lead reads this as: "the payload lands inside this ellipse with the stated
 * probability", which is a different and far more useful statement than a single predicted point.
 *
 * @param confidence   the confidence level, strictly between 0 and 1 — 0.5 and 0.95 are the levels
 *                     the blueprint reports
 * @param centre       the ellipse centre; its altitude is the ground elevation
 * @param semiMajorM   the longer semi-axis, metres
 * @param semiMinorM   the shorter semi-axis, metres, never larger than {@code semiMajorM}
 * @param azimuthDeg   bearing of the major axis, degrees clockwise from true north, in [0, 360)
 * @param memberCount  how many members the ellipse was fitted from
 */
public record LandingEllipse(
        double confidence,
        GeoPoint centre,
        double semiMajorM,
        double semiMinorM,
        double azimuthDeg,
        int memberCount) {

    /**
     * @throws IllegalArgumentException if the ellipse breaks one of the invariants the schema also
     *                                  enforces — confidence in (0, 1), positive axes, the major
     *                                  axis at least the minor, azimuth in range
     */
    public LandingEllipse {
        if (!(confidence > 0.0 && confidence < 1.0)) {
            throw new IllegalArgumentException("confidence must lie in (0, 1), was " + confidence);
        }
        if (!(semiMajorM > 0.0) || !(semiMinorM > 0.0)) {
            throw new IllegalArgumentException(
                    "semi-axes must be positive, were " + semiMajorM + " and " + semiMinorM);
        }
        if (semiMinorM > semiMajorM) {
            throw new IllegalArgumentException("semi-minor (" + semiMinorM
                    + ") must not exceed semi-major (" + semiMajorM + ")");
        }
        if (azimuthDeg < 0.0 || azimuthDeg > 360.0) {
            throw new IllegalArgumentException("azimuth must lie in [0, 360], was " + azimuthDeg);
        }
    }

    /**
     * The area the ellipse encloses.
     *
     * @return area in square kilometres — the unit a mission planner reasons in when checking a
     *         footprint against water, cities or restricted airspace (persona P3)
     */
    public double areaKm2() {
        return Math.PI * semiMajorM * semiMinorM / 1e6;
    }

    /**
     * The ratio of the axes, as a measure of how directional the uncertainty is.
     *
     * @return semi-major divided by semi-minor; 1 is a circle, and a large value means the
     *         uncertainty is dominated by one direction, usually along the wind
     */
    public double aspectRatio() {
        return semiMajorM / semiMinorM;
    }

    /**
     * Whether a point falls inside the ellipse.
     *
     * <p>Used to measure empirical containment (T-V7): a 95% ellipse that actually contains 70% of
     * the members is not a 95% ellipse, and only a check like this can tell.
     *
     * @param point the point to test
     * @return {@code true} if the point lies inside or on the ellipse
     */
    public boolean contains(GeoPoint point) {
        // Offsets in the local tangent plane, metres east and north of the centre.
        double east = (point.longitudeDeg() - centre.longitudeDeg())
                * Geodesy.metresPerDegreeLongitude(centre.latitudeDeg());
        double north = (point.latitudeDeg() - centre.latitudeDeg())
                * Geodesy.metresPerDegreeLatitude();

        // Rotate into the ellipse's own frame. Azimuth is measured clockwise from north, so the
        // major axis points along (sin a, cos a) in (east, north).
        double a = Math.toRadians(azimuthDeg);
        double alongMajor = east * Math.sin(a) + north * Math.cos(a);
        double alongMinor = east * Math.cos(a) - north * Math.sin(a);

        double u = alongMajor / semiMajorM;
        double v = alongMinor / semiMinorM;
        return u * u + v * v <= 1.0;
    }

    @Override
    public String toString() {
        return String.format("%.0f%% ellipse: %.4f, %.4f, %.0f x %.0f m at %.0f deg, %.1f km2",
                confidence * 100, centre.latitudeDeg(), centre.longitudeDeg(),
                semiMajorM, semiMinorM, azimuthDeg, areaKm2());
    }
}
