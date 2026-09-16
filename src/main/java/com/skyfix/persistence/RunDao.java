package com.skyfix.persistence;

import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves run records (NFR-5, ADR-2).
 *
 * <p>This is the table that makes T-D1 possible: a stored run carries its seed, git SHA, config
 * hash, integrator, step size, member count and host cores, so it can be re-materialised and
 * compared without re-simulating anything.
 */
public final class RunDao implements Repository<RunRecord, Long> {

    private static final String INSERT =
            "INSERT INTO run (mission_id, balloon_config_id, sounding_id, flight_log_id, "
                    + "run_kind, integrator, step_s, member_count, rng_seed, git_sha, config_hash, "
                    + "host_cores, started_utc, wall_clock_ms, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_COMPLETION =
            "UPDATE run SET wall_clock_ms = ?, status = ? WHERE id = ?";
    private static final String SELECT_BY_ID = "SELECT id, mission_id, balloon_config_id, sounding_id, flight_log_id, "
                    + "run_kind, integrator, step_s, member_count, rng_seed, "
                    + "git_sha, config_hash, host_cores, started_utc, "
                    + "wall_clock_ms, status FROM run WHERE id = ?";
    private static final String SELECT_ALL = "SELECT id, mission_id, balloon_config_id, sounding_id, flight_log_id, "
                    + "run_kind, integrator, step_s, member_count, rng_seed, "
                    + "git_sha, config_hash, host_cores, started_utc, "
                    + "wall_clock_ms, status FROM run ORDER BY id";
    private static final String SELECT_BY_MISSION_AND_KIND =
            "SELECT id, mission_id, balloon_config_id, sounding_id, flight_log_id, "
                    + "run_kind, integrator, step_s, member_count, rng_seed, "
                    + "git_sha, config_hash, host_cores, started_utc, "
                    + "wall_clock_ms, status FROM run WHERE mission_id = ? "
                    + "AND run_kind = ? ORDER BY started_utc DESC";
    private static final String DELETE = "DELETE FROM run WHERE id = ?";
    private static final String COUNT = "SELECT COUNT(*) FROM run";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public RunDao(Database database) {
        this.database = database;
    }

    @Override
    public RunRecord save(RunRecord run) throws PersistenceException {
        try (PreparedStatement ps = database.connection()
                .prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, run.missionId());
            ps.setLong(2, run.balloonConfigId());
            setNullableLong(ps, 3, run.soundingId());
            setNullableLong(ps, 4, run.flightLogId());
            ps.setString(5, run.runKind());
            ps.setString(6, run.integrator());
            ps.setDouble(7, run.stepSeconds());
            ps.setInt(8, run.memberCount());
            ps.setLong(9, run.rngSeed());
            ps.setString(10, run.gitSha());
            ps.setString(11, run.configHash());
            ps.setInt(12, run.hostCores());
            ps.setString(13, run.startedUtc());
            setNullableLong(ps, 14, run.wallClockMs());
            ps.setString(15, run.status());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return run.withId(keys.getLong(1));
                }
            }
            throw new PersistenceException("run insert returned no generated key");
        } catch (SQLException e) {
            throw new PersistenceException("cannot save run: " + e.getMessage(), e);
        }
    }

    /**
     * Marks a run finished, recording its wall-clock time and final status.
     *
     * @param runId     the run
     * @param elapsedMs wall-clock milliseconds
     * @param status    {@link RunRecord#OK} or {@link RunRecord#FAILED}
     * @throws PersistenceException if the update fails
     */
    public void finish(long runId, long elapsedMs, String status) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(UPDATE_COMPLETION)) {
            ps.setLong(1, elapsedMs);
            ps.setString(2, status);
            ps.setLong(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("cannot finish run " + runId, e);
        }
    }

    /**
     * Lists the runs of one kind for one mission, most recent first.
     *
     * @param missionId the mission
     * @param runKind   PREFLIGHT, REPLAY, SYNTH or VALIDATION
     * @return the matching runs
     * @throws PersistenceException if the query fails
     */
    public List<RunRecord> findByMissionAndKind(long missionId, String runKind)
            throws PersistenceException {
        List<RunRecord> runs = new ArrayList<>();
        try (PreparedStatement ps = database.connection()
                .prepareStatement(SELECT_BY_MISSION_AND_KIND)) {
            ps.setLong(1, missionId);
            ps.setString(2, runKind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(map(rs));
                }
            }
            return runs;
        } catch (SQLException e) {
            throw new PersistenceException("cannot list runs for mission " + missionId, e);
        }
    }

    @Override
    public Optional<RunRecord> findById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read run " + id, e);
        }
    }

    @Override
    public List<RunRecord> findAll() throws PersistenceException {
        List<RunRecord> all = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(map(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new PersistenceException("cannot list runs", e);
        }
    }

    @Override
    public boolean deleteById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(DELETE)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("cannot delete run " + id, e);
        }
    }

    @Override
    public long count() throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new PersistenceException("cannot count runs", e);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static RunRecord map(ResultSet rs) throws SQLException {
        return new RunRecord(
                rs.getLong("id"),
                rs.getLong("mission_id"),
                rs.getLong("balloon_config_id"),
                nullableLong(rs, "sounding_id"),
                nullableLong(rs, "flight_log_id"),
                rs.getString("run_kind"),
                rs.getString("integrator"),
                rs.getDouble("step_s"),
                rs.getInt("member_count"),
                rs.getLong("rng_seed"),
                rs.getString("git_sha"),
                rs.getString("config_hash"),
                rs.getInt("host_cores"),
                rs.getString("started_utc"),
                nullableLong(rs, "wall_clock_ms"),
                rs.getString("status"));
    }
}
