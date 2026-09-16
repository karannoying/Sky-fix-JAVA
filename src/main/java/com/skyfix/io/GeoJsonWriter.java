package com.skyfix.io;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.PersistenceException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes a flight as GeoJSON, so a recovery team can drop the track and the landing point
 * straight into a map (BLUEPRINT §6, FR-2.4).
 *
 * <p>GeoJSON orders coordinates longitude-first, which is the opposite of how the rest of SKYFIX
 * writes a position; that inversion happens here and nowhere else. Altitude is carried as the
 * optional third coordinate.
 */
public final class GeoJsonWriter {

    /** Vertices used to trace an ellipse; 72 is a 5-degree step, smooth at any practical zoom. */
    private static final int ELLIPSE_VERTICES = 72;

    private GeoJsonWriter() {
    }

    /**
     * Writes a trajectory as a {@code FeatureCollection}: the track as a LineString, plus point
     * features for burst and landing.
     *
     * @param path    the file to write; parent directories are created
     * @param history the trajectory
     * @throws PersistenceException if the file cannot be written
     */
    public static void writeFlight(Path path, StateHistory history) throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("{\"type\":\"FeatureCollection\",\"features\":[");

                out.write("{\"type\":\"Feature\",\"properties\":{\"name\":\"track\"},"
                        + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
                boolean first = true;
                for (BalloonState s : history) {
                    if (!first) {
                        out.write(",");
                    }
                    out.write(coordinate(s.longitudeDeg(), s.latitudeDeg(), s.altitudeM()));
                    first = false;
                }
                out.write("]}}");

                if (history.burst().isPresent()) {
                    BalloonState b = history.burst().get();
                    out.write(",");
                    out.write(point("burst", b.longitudeDeg(), b.latitudeDeg(), b.altitudeM()));
                }
                if (history.landing().isPresent()) {
                    BalloonState l = history.landing().get();
                    out.write(",");
                    out.write(point("landing", l.longitudeDeg(), l.latitudeDeg(), l.altitudeM()));
                }
                out.write("]}");
                out.newLine();
            }
        } catch (IOException e) {
            throw new PersistenceException("cannot write GeoJSON to " + path, e);
        }
    }

    /**
     * Writes the footprint: each ellipse as a polygon, plus the landing scatter as points.
     *
     * <p>This is the file a recovery lead opens on a map. The ellipse is emitted as an explicit
     * polygon rather than a centre and axes, because no map tool draws a rotated ellipse from
     * parameters — but every one of them draws a polygon.
     *
     * @param path      the file to write; parent directories are created
     * @param ellipses  the fitted ellipses, largest confidence drawn first
     * @param ensemble  the ensemble whose members are drawn as points
     * @throws PersistenceException if the file cannot be written
     */
    public static void writeFootprint(Path path, List<LandingEllipse> ellipses, Ensemble ensemble)
            throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("{\"type\":\"FeatureCollection\",\"features\":[");
                boolean first = true;

                for (LandingEllipse ellipse : ellipses) {
                    if (!first) {
                        out.write(",");
                    }
                    out.write(ellipsePolygon(ellipse));
                    first = false;
                }
                for (LandingEllipse ellipse : ellipses) {
                    out.write(",");
                    out.write(point(String.format(Locale.ROOT, "centre %.0f%%",
                                    ellipse.confidence() * 100),
                            ellipse.centre().longitudeDeg(), ellipse.centre().latitudeDeg(),
                            ellipse.centre().altitudeM()));
                }
                for (Ensemble.Member m : ensemble.members()) {
                    if (m.landing().isEmpty()) {
                        continue;
                    }
                    GeoPoint g = m.landing().get();
                    out.write(",");
                    out.write(point("member " + m.index(), g.longitudeDeg(), g.latitudeDeg(),
                            g.altitudeM()));
                }
                out.write("]}");
                out.newLine();
            }
        } catch (IOException e) {
            throw new PersistenceException("cannot write footprint GeoJSON to " + path, e);
        }
    }

    /** Traces an ellipse as a closed polygon of {@value #ELLIPSE_VERTICES} vertices. */
    private static String ellipsePolygon(LandingEllipse ellipse) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"")
          .append(String.format(Locale.ROOT, "%.0f%% ellipse", ellipse.confidence() * 100))
          .append("\",\"confidence\":").append(ellipse.confidence())
          .append(",\"semi_major_m\":").append(String.format(Locale.ROOT, "%.1f",
                  ellipse.semiMajorM()))
          .append(",\"semi_minor_m\":").append(String.format(Locale.ROOT, "%.1f",
                  ellipse.semiMinorM()))
          .append(",\"area_km2\":").append(String.format(Locale.ROOT, "%.3f",
                  ellipse.areaKm2()))
          .append("},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[");

        double azimuth = Math.toRadians(ellipse.azimuthDeg());
        double metresPerDegreeLat = Geodesy.metresPerDegreeLatitude();
        double metresPerDegreeLon =
                Geodesy.metresPerDegreeLongitude(ellipse.centre().latitudeDeg());

        for (int i = 0; i <= ELLIPSE_VERTICES; i++) {
            // Parametrise around the ellipse, then rotate from its own frame into east-north.
            double t = 2.0 * Math.PI * i / ELLIPSE_VERTICES;
            double alongMajor = ellipse.semiMajorM() * Math.cos(t);
            double alongMinor = ellipse.semiMinorM() * Math.sin(t);
            double east = alongMajor * Math.sin(azimuth) + alongMinor * Math.cos(azimuth);
            double north = alongMajor * Math.cos(azimuth) - alongMinor * Math.sin(azimuth);

            if (i > 0) {
                sb.append(",");
            }
            sb.append(coordinate(
                    ellipse.centre().longitudeDeg() + east / metresPerDegreeLon,
                    ellipse.centre().latitudeDeg() + north / metresPerDegreeLat,
                    ellipse.centre().altitudeM()));
        }
        sb.append("]]}}");
        return sb.toString();
    }

    private static String point(String name, double lon, double lat, double alt) {
        return "{\"type\":\"Feature\",\"properties\":{\"name\":\"" + name + "\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":"
                + coordinate(lon, lat, alt) + "}}";
    }

    private static String coordinate(double lon, double lat, double alt) {
        // GeoJSON is [longitude, latitude, altitude] — the inversion lives here alone.
        return String.format(Locale.ROOT, "[%.7f,%.7f,%.1f]", lon, lat, alt);
    }
}
