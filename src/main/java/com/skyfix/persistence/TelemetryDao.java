package com.skyfix.persistence;

import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves telemetry logs and their samples (FR-1.2, ADR-2).
 *
 * <p>Samples go in as one batch inside the log's transaction, so a log is stored whole or not at
 * all. The {@code UNIQUE (flight_log_id, packet_id)} constraint means a duplicate packet cannot
 * reach the table even if a caller skips the reader's de-duplication.
 */
public final class TelemetryDao {

    private static final String INSERT_LOG =
            "INSERT INTO flight_log (mission_id, name, source_kind, file_sha256, sample_count, "
                    + "truth_json) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_SAMPLE =
            "INSERT INTO telemetry_sample (flight_log_id, epoch_utc, lat, lon, alt_gps_m, "
                    + "pressure_pa, temperature_k, packet_id, quality_flags) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_LOG_BY_ID =
            "SELECT id, mission_id, name, source_kind, file_sha256, sample_count, truth_json "
                    + "FROM flight_log WHERE id = ?";
    private static final String SELECT_LOG_BY_NAME =
            "SELECT id, mission_id, name, source_kind, file_sha256, sample_count, truth_json "
                    + "FROM flight_log WHERE mission_id = ? AND name = ?";
    private static final String SELECT_LOGS_FOR_MISSION =
            "SELECT id, mission_id, name, source_kind, file_sha256, sample_count, truth_json "
                    + "FROM flight_log WHERE mission_id = ? ORDER BY name";
    private static final String SELECT_SAMPLES =
            "SELECT epoch_utc, lat, lon, alt_gps_m, pressure_pa, temperature_k, packet_id, "
                    + "quality_flags FROM telemetry_sample WHERE flight_log_id = ? "
                    + "ORDER BY epoch_utc, packet_id";
    private static final String COUNT_SAMPLES =
            "SELECT COUNT(*) FROM telemetry_sample WHERE flight_log_id = ?";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public TelemetryDao(Database database) {
        this.database = database;
    }

    /**
     * Stores a log and every sample in it.
     *
     * @param log    the log metadata
     * @param series the samples
     * @return the log, carrying its assigned key
     * @throws PersistenceException if the insert fails, in which case nothing is written
     */
    public FlightLog save(FlightLog log, TelemetrySeries series) throws PersistenceException {
        return database.inTransaction(connection -> {
            long id;
            try (PreparedStatement ps = connection
                    .prepareStatement(INSERT_LOG, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, log.missionId());
                ps.setString(2, log.name());
                ps.setString(3, log.sourceKind());
                ps.setString(4, log.fileSha256());
                ps.setInt(5, series.size());
                if (log.truthJson() == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, log.truthJson());
                }
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new PersistenceException("flight_log insert returned no key");
                    }
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SAMPLE)) {
                for (TelemetrySample s : series) {
                    ps.setLong(1, id);
                    ps.setString(2, s.epochUtc().toString());
                    ps.setDouble(3, s.latitudeDeg());
                    ps.setDouble(4, s.longitudeDeg());
                    setNullable(ps, 5, s.altitudeGpsM());
                    setNullable(ps, 6, s.pressurePa());
                    setNullable(ps, 7, s.temperatureK());
                    ps.setInt(8, s.packetId());
                    ps.setInt(9, s.qualityFlags());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return log.withId(id);
        });
    }

    /**
     * Looks a log up by key.
     *
     * @param id the log
     * @return the log, or empty if no log has that key
     * @throws PersistenceException if the query fails
     */
    public Optional<FlightLog> findById(long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_LOG_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapLog(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read flight log " + id, e);
        }
    }

    /**
     * Looks a log up by its name within a mission, which is how the CLI refers to one.
     *
     * @param missionId the mission
     * @param name      the log name
     * @return the log, or empty if it has not been imported
     * @throws PersistenceException if the query fails
     */
    public Optional<FlightLog> findByName(long missionId, String name)
            throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_LOG_BY_NAME)) {
            ps.setLong(1, missionId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapLog(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read flight log \"" + name + "\"", e);
        }
    }

    /**
     * Lists the logs belonging to a mission.
     *
     * @param missionId the mission
     * @return the logs, ordered by name
     * @throws PersistenceException if the query fails
     */
    public List<FlightLog> findForMission(long missionId) throws PersistenceException {
        List<FlightLog> logs = new ArrayList<>();
        try (PreparedStatement ps = database.connection()
                .prepareStatement(SELECT_LOGS_FOR_MISSION)) {
            ps.setLong(1, missionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    logs.add(mapLog(rs));
                }
            }
            return logs;
        } catch (SQLException e) {
            throw new PersistenceException("cannot list flight logs for mission " + missionId, e);
        }
    }

    /**
     * Reads a log's samples back as a series.
     *
     * <p>The anomaly counts are not stored per-log, so they come back as zero: the counts describe
     * the <em>file</em> that was imported, and the stored samples are the cleaned result. Re-read
     * the file if the counts are needed.
     *
     * @param flightLogId the log
     * @return the samples in time order
     * @throws PersistenceException if the query fails
     */
    public TelemetrySeries findSamples(long flightLogId) throws PersistenceException {
        List<TelemetrySample> samples = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_SAMPLES)) {
            ps.setLong(1, flightLogId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    samples.add(new TelemetrySample(
                            Instant.parse(rs.getString("epoch_utc")),
                            rs.getInt("packet_id"),
                            rs.getDouble("lat"),
                            rs.getDouble("lon"),
                            nullableDouble(rs, "alt_gps_m"),
                            nullableDouble(rs, "pressure_pa"),
                            nullableDouble(rs, "temperature_k"),
                            rs.getInt("quality_flags")));
                }
            }
            return new TelemetrySeries(samples, 0, 0, 0, List.of());
        } catch (SQLException e) {
            throw new PersistenceException("cannot read samples for flight log " + flightLogId, e);
        }
    }

    /**
     * Counts the samples stored for a log.
     *
     * @param flightLogId the log
     * @return the row count
     * @throws PersistenceException if the query fails
     */
    public long countSamples(long flightLogId) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT_SAMPLES)) {
            ps.setLong(1, flightLogId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot count samples for log " + flightLogId, e);
        }
    }

    private static FlightLog mapLog(ResultSet rs) throws SQLException {
        return new FlightLog(rs.getLong("id"), rs.getLong("mission_id"), rs.getString("name"),
                rs.getString("source_kind"), rs.getString("file_sha256"),
                rs.getInt("sample_count"), rs.getString("truth_json"));
    }

    private static void setNullable(PreparedStatement ps, int index, double value)
            throws SQLException {
        if (Double.isNaN(value)) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }
}
