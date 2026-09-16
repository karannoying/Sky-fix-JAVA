package com.skyfix.persistence;

import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores and retrieves landing ellipses (FR-2.4, FR-3.3, ADR-2).
 *
 * <p>An ellipse carries an optional update epoch: a pre-flight footprint has none, while each
 * in-flight re-prediction is tagged with the telemetry epoch it was computed at, which is what
 * lets FR-4.1 plot error against time and O4 compare live against frozen.
 */
public final class EllipseDao {

    private static final String INSERT =
            "INSERT INTO landing_ellipse (run_id, update_epoch_utc, confidence, center_lat, "
                    + "center_lon, semi_major_m, semi_minor_m, azimuth_deg, area_km2) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_TIMELINE =
            "SELECT update_epoch_utc, confidence, center_lat, center_lon, semi_major_m, "
                    + "semi_minor_m, azimuth_deg FROM landing_ellipse "
                    + "WHERE run_id = ? ORDER BY update_epoch_utc, confidence";
    private static final String SELECT_FOR_RUN =
            "SELECT id, update_epoch_utc, confidence, center_lat, center_lon, semi_major_m, "
                    + "semi_minor_m, azimuth_deg, area_km2 FROM landing_ellipse "
                    + "WHERE run_id = ? ORDER BY confidence, update_epoch_utc";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public EllipseDao(Database database) {
        this.database = database;
    }

    /**
     * Stores one ellipse.
     *
     * @param runId          the owning run
     * @param ellipse        the ellipse
     * @param updateEpochUtc the telemetry epoch this re-prediction was made at, or {@code null}
     *                       for a pre-flight footprint
     * @param memberCount    how many members it was fitted from, for the record
     * @throws PersistenceException if the insert fails
     */
    public void save(long runId, LandingEllipse ellipse, String updateEpochUtc, int memberCount)
            throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(INSERT)) {
            ps.setLong(1, runId);
            if (updateEpochUtc == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, updateEpochUtc);
            }
            ps.setDouble(3, ellipse.confidence());
            ps.setDouble(4, ellipse.centre().latitudeDeg());
            ps.setDouble(5, ellipse.centre().longitudeDeg());
            ps.setDouble(6, ellipse.semiMajorM());
            ps.setDouble(7, ellipse.semiMinorM());
            ps.setDouble(8, ellipse.azimuthDeg());
            ps.setDouble(9, ellipse.areaKm2());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("cannot save landing ellipse for run " + runId
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Stores several ellipses for one run — the 50% and 95% pair, typically.
     *
     * @param runId          the owning run
     * @param ellipses       the ellipses
     * @param updateEpochUtc the epoch to tag them with, or {@code null} for pre-flight
     * @throws PersistenceException if any insert fails, in which case none are written
     */
    public void saveAll(long runId, List<LandingEllipse> ellipses, String updateEpochUtc)
            throws PersistenceException {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                for (LandingEllipse ellipse : ellipses) {
                    ps.setLong(1, runId);
                    if (updateEpochUtc == null) {
                        ps.setNull(2, Types.VARCHAR);
                    } else {
                        ps.setString(2, updateEpochUtc);
                    }
                    ps.setDouble(3, ellipse.confidence());
                    ps.setDouble(4, ellipse.centre().latitudeDeg());
                    ps.setDouble(5, ellipse.centre().longitudeDeg());
                    ps.setDouble(6, ellipse.semiMajorM());
                    ps.setDouble(7, ellipse.semiMinorM());
                    ps.setDouble(8, ellipse.azimuthDeg());
                    ps.setDouble(9, ellipse.areaKm2());
                    ps.addBatch();
                }
                ps.executeBatch();
                return ellipses.size();
            }
        });
    }

    /**
     * Reads back every ellipse stored for a run.
     *
     * @param runId            the run
     * @param groundElevationM ground elevation to give the reconstructed centres, metres
     * @return the ellipses, ordered by confidence then epoch
     * @throws PersistenceException if the query fails
     */
    public List<LandingEllipse> findForRun(long runId, double groundElevationM)
            throws PersistenceException {
        List<LandingEllipse> ellipses = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_FOR_RUN)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ellipses.add(new LandingEllipse(
                            rs.getDouble("confidence"),
                            new GeoPoint(rs.getDouble("center_lat"), rs.getDouble("center_lon"),
                                    groundElevationM),
                            rs.getDouble("semi_major_m"),
                            rs.getDouble("semi_minor_m"),
                            rs.getDouble("azimuth_deg"),
                            0));
                }
            }
            return ellipses;
        } catch (SQLException e) {
            throw new PersistenceException("cannot read ellipses for run " + runId, e);
        }
    }

    /**
     * Reads every ellipse stored for a run, each with the epoch it was computed at.
     *
     * <p>{@link #findForRun} drops the epoch, which is all a pre-flight footprint needs. A replay's
     * ellipses are a <em>series</em> — that is the whole point of FR-3.3 — and scoring one
     * (FR-4.1) or plotting error against time (PL-4) needs to know which moment each belongs to.
     *
     * @param runId            the run
     * @param groundElevationM ground elevation to give the reconstructed centres, metres
     * @return the ellipses in epoch order, each tagged; a pre-flight footprint has no epoch and is
     *         reported with {@code null}
     * @throws PersistenceException if the query fails
     */
    public List<TimedEllipse> findTimeline(long runId, double groundElevationM)
            throws PersistenceException {
        List<TimedEllipse> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_TIMELINE)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String epoch = rs.getString("update_epoch_utc");
                    out.add(new TimedEllipse(
                            epoch == null ? null : java.time.Instant.parse(epoch),
                            new LandingEllipse(
                                    rs.getDouble("confidence"),
                                    new GeoPoint(rs.getDouble("center_lat"),
                                            rs.getDouble("center_lon"), groundElevationM),
                                    rs.getDouble("semi_major_m"),
                                    rs.getDouble("semi_minor_m"),
                                    rs.getDouble("azimuth_deg"),
                                    0)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PersistenceException("cannot read the ellipse timeline for run " + runId, e);
        }
    }

    /**
     * One stored ellipse and the moment it was computed at.
     *
     * @param epochUtc the telemetry epoch this re-prediction was made at, or {@code null} for a
     *                 pre-flight footprint
     * @param ellipse  the ellipse
     */
    public record TimedEllipse(java.time.Instant epochUtc, LandingEllipse ellipse) {

        /** @return whether this is a pre-flight footprint rather than an in-flight re-prediction */
        public boolean isPreflight() {
            return epochUtc == null;
        }
    }
}
