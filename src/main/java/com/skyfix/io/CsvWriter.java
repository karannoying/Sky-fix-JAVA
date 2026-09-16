package com.skyfix.io;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.FlightParameters;
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
 * Writes trajectories and landing points as CSV — the MVP's export format (BLUEPRINT §11).
 *
 * <p>Numbers are formatted with {@link Locale#ROOT}, so a machine with a comma decimal separator
 * produces the same file. Every export is deterministic for a given run, which is what lets T-R1
 * compare two executions byte for byte.
 */
public final class CsvWriter {

    private CsvWriter() {
    }

    /**
     * Writes a trajectory, one row per retained state.
     *
     * @param path    the file to write; parent directories are created
     * @param history the trajectory
     * @return how many data rows were written
     * @throws PersistenceException if the file cannot be written
     */
    public static int writeTrajectory(Path path, StateHistory history)
            throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("t_s,lat_deg,lon_deg,alt_m,vz_ms,diameter_m,phase,wind_extrapolated");
                out.newLine();
                int rows = 0;
                for (BalloonState s : history) {
                    out.write(String.format(Locale.ROOT,
                            "%.3f,%.7f,%.7f,%.3f,%.4f,%.4f,%s,%d",
                            s.timeSeconds(), s.latitudeDeg(), s.longitudeDeg(), s.altitudeM(),
                            s.verticalRateMs(), s.diameterM(), s.phase(),
                            s.windExtrapolated() ? 1 : 0));
                    out.newLine();
                    rows++;
                }
                return rows;
            }
        } catch (IOException e) {
            throw new PersistenceException("cannot write trajectory CSV to " + path, e);
        }
    }

    /**
     * Writes the landing scatter — one row per ensemble member, with the parameters it flew.
     *
     * <p>This is the file PL-2 plots and the one a re-analysis re-fits an ellipse from, so it
     * carries the dispersed parameters alongside the landing point: a member that landed far out
     * is only interesting if you can see what draw produced it.
     *
     * @param path     the file to write; parent directories are created
     * @param ensemble the ensemble
     * @return how many data rows were written
     * @throws PersistenceException if the file cannot be written
     */
    public static int writeLandingScatter(Path path, Ensemble ensemble)
            throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("member_index,landing_lat,landing_lon,burst_alt_m,free_lift_kg,"
                        + "ascent_cd,burst_diameter_m,chute_cd,wind_scale,status");
                out.newLine();
                int rows = 0;
                for (Ensemble.Member m : ensemble.members()) {
                    FlightParameters p = m.parameters();
                    out.write(String.format(Locale.ROOT,
                            "%d,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%s",
                            m.index(),
                            m.landing().map(g -> String.format(Locale.ROOT, "%.7f",
                                    g.latitudeDeg())).orElse(""),
                            m.landing().map(g -> String.format(Locale.ROOT, "%.7f",
                                    g.longitudeDeg())).orElse(""),
                            Double.isNaN(m.burstAltM()) ? ""
                                    : String.format(Locale.ROOT, "%.1f", m.burstAltM()),
                            p.freeLiftKg(), p.ascentCd(), p.burstDiameterM(), p.chuteCd(),
                            p.windScale(),
                            m.succeeded() ? "OK" : "FAILED"));
                    out.newLine();
                    rows++;
                }
                return rows;
            }
        } catch (IOException e) {
            throw new PersistenceException("cannot write landing scatter CSV to " + path, e);
        }
    }

    /**
     * Writes the fitted ellipses, one row per confidence level.
     *
     * @param path     the file to write
     * @param runId    the run these belong to
     * @param ellipses the ellipses
     * @throws PersistenceException if the file cannot be written
     */
    public static void writeEllipses(Path path, long runId, List<LandingEllipse> ellipses)
            throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("run_id,confidence,center_lat,center_lon,semi_major_m,semi_minor_m,"
                        + "azimuth_deg,area_km2,member_count");
                out.newLine();
                for (LandingEllipse e : ellipses) {
                    out.write(String.format(Locale.ROOT,
                            "%d,%.4f,%.7f,%.7f,%.2f,%.2f,%.3f,%.4f,%d",
                            runId, e.confidence(), e.centre().latitudeDeg(),
                            e.centre().longitudeDeg(), e.semiMajorM(), e.semiMinorM(),
                            e.azimuthDeg(), e.areaKm2(), e.memberCount()));
                    out.newLine();
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("cannot write ellipse CSV to " + path, e);
        }
    }

    /**
     * Writes a one-line summary of a flight: where it burst, where it landed, how long it took.
     *
     * @param path    the file to write
     * @param runId   the run this summary belongs to
     * @param history the trajectory
     * @throws PersistenceException if the file cannot be written
     */
    public static void writeSummary(Path path, long runId, StateHistory history)
            throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("run_id,burst_alt_m,burst_t_s,landing_lat,landing_lon,duration_s,"
                        + "apogee_m,steps,wind_extrapolated_steps");
                out.newLine();
                out.write(String.format(Locale.ROOT, "%d,%s,%s,%s,%s,%.1f,%.1f,%d,%d",
                        runId,
                        history.burstAltitudeM().map(a -> String.format(Locale.ROOT, "%.1f", a))
                                .orElse(""),
                        history.burst().map(b -> String.format(Locale.ROOT, "%.1f",
                                b.timeSeconds())).orElse(""),
                        history.landingPoint().map(p -> String.format(Locale.ROOT, "%.7f",
                                p.latitudeDeg())).orElse(""),
                        history.landingPoint().map(p -> String.format(Locale.ROOT, "%.7f",
                                p.longitudeDeg())).orElse(""),
                        history.durationSeconds(), history.apogeeM(),
                        history.stepCount(), history.windExtrapolatedCount()));
                out.newLine();
            }
        } catch (IOException e) {
            throw new PersistenceException("cannot write run summary to " + path, e);
        }
    }
}
