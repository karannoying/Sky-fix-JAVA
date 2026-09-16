package com.skyfix.persistence;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Phase;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores and retrieves trajectories (ADR-2, BLUEPRINT §9).
 *
 * <p>States go in as a single JDBC batch inside one transaction, which is both faster than
 * per-row inserts and the property T-D4 relies on: a partially written trajectory never survives
 * a failure.
 *
 * <p>Per BLUEPRINT §9 the caller decides which members are stored — the nominal member plus a
 * sample — so a 1,000-member run holds tens of thousands of rows rather than millions.
 */
public final class RunStateDao {

    private static final String INSERT =
            "INSERT INTO run_state (run_id, member_index, t_s, lat, lon, alt_m, vz_ms, "
                    + "diameter_m, phase, wind_extrapolated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_MEMBER =
            "SELECT t_s, lat, lon, alt_m, vz_ms, diameter_m, phase, wind_extrapolated "
                    + "FROM run_state WHERE run_id = ? AND member_index = ? ORDER BY t_s";
    private static final String COUNT_FOR_RUN =
            "SELECT COUNT(*) FROM run_state WHERE run_id = ?";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public RunStateDao(Database database) {
        this.database = database;
    }

    /**
     * Stores one member's trajectory as a single batch.
     *
     * @param runId       the owning run
     * @param memberIndex which ensemble member this is; 0 for a single deterministic flight
     * @param history     the trajectory
     * @return how many state rows were written
     * @throws PersistenceException if the batch fails, in which case nothing is written
     */
    public int saveHistory(long runId, int memberIndex, StateHistory history)
            throws PersistenceException {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                int rows = 0;
                for (BalloonState s : history) {
                    ps.setLong(1, runId);
                    ps.setInt(2, memberIndex);
                    ps.setDouble(3, s.timeSeconds());
                    ps.setDouble(4, s.latitudeDeg());
                    ps.setDouble(5, s.longitudeDeg());
                    ps.setDouble(6, s.altitudeM());
                    ps.setDouble(7, s.verticalRateMs());
                    ps.setDouble(8, s.diameterM());
                    ps.setString(9, s.phase().name());
                    ps.setInt(10, s.windExtrapolated() ? 1 : 0);
                    ps.addBatch();
                    rows++;
                }
                ps.executeBatch();
                return rows;
            }
        });
    }

    /**
     * Reads one member's trajectory back.
     *
     * @param runId       the run
     * @param memberIndex the member
     * @return the states in time order, empty if that member was not stored
     * @throws PersistenceException if the query fails
     */
    public List<BalloonState> findHistory(long runId, int memberIndex)
            throws PersistenceException {
        List<BalloonState> states = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_MEMBER)) {
            ps.setLong(1, runId);
            ps.setInt(2, memberIndex);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    states.add(new BalloonState(
                            rs.getDouble("t_s"),
                            new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon"),
                                    rs.getDouble("alt_m")),
                            rs.getDouble("vz_ms"),
                            rs.getDouble("diameter_m"),
                            Phase.valueOf(rs.getString("phase")),
                            rs.getInt("wind_extrapolated") != 0));
                }
            }
            return states;
        } catch (SQLException e) {
            throw new PersistenceException(
                    "cannot read trajectory for run " + runId + " member " + memberIndex, e);
        }
    }

    /**
     * Counts the state rows stored for a run, across all members.
     *
     * @param runId the run
     * @return the row count
     * @throws PersistenceException if the query fails
     */
    public long countForRun(long runId) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT_FOR_RUN)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot count states for run " + runId, e);
        }
    }
}
