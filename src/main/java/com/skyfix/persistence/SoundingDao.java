package com.skyfix.persistence;

import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves soundings and their levels (FR-1.1, ADR-2).
 *
 * <p>Levels go in as a JDBC batch inside the sounding's transaction, so a sounding is either
 * stored whole or not at all — the property T-D4 checks. Re-import is idempotent through the
 * UNIQUE (station, epoch, source) constraint, surfaced by {@link #findByNaturalKey}.
 */
public final class SoundingDao implements Repository<StoredSounding, Long> {

    private static final String INSERT_SOUNDING =
            "INSERT INTO sounding (station_id, epoch_utc, source, file_sha256, level_count) "
                    + "VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_LEVEL =
            "INSERT INTO sounding_level (sounding_id, height_gpm, pressure_pa, temperature_k, "
                    + "wind_u_ms, wind_v_ms) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT_BY_ID =
            "SELECT id, station_id, epoch_utc, source, file_sha256 FROM sounding WHERE id = ?";
    private static final String SELECT_BY_NATURAL_KEY =
            "SELECT id, station_id, epoch_utc, source, file_sha256 FROM sounding "
                    + "WHERE station_id = ? AND epoch_utc = ? AND source = ?";
    private static final String SELECT_ALL =
            "SELECT id, station_id, epoch_utc, source, file_sha256 FROM sounding ORDER BY id";
    private static final String SELECT_LEVELS =
            "SELECT height_gpm, pressure_pa, temperature_k, wind_u_ms, wind_v_ms "
                    + "FROM sounding_level WHERE sounding_id = ? ORDER BY height_gpm";
    private static final String DELETE = "DELETE FROM sounding WHERE id = ?";
    private static final String COUNT = "SELECT COUNT(*) FROM sounding";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public SoundingDao(Database database) {
        this.database = database;
    }

    @Override
    public StoredSounding save(StoredSounding sounding) throws PersistenceException {
        return database.inTransaction(connection -> {
            long id;
            try (PreparedStatement ps = connection
                    .prepareStatement(INSERT_SOUNDING, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, sounding.stationId());
                ps.setString(2, sounding.epochUtc());
                ps.setString(3, sounding.source());
                ps.setString(4, sounding.fileSha256());
                ps.setInt(5, sounding.levelCount());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new PersistenceException("sounding insert returned no generated key");
                    }
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(INSERT_LEVEL)) {
                for (SoundingLevel level : sounding.levels()) {
                    ps.setLong(1, id);
                    ps.setDouble(2, level.heightGeopotentialM());
                    ps.setDouble(3, level.pressurePa());
                    ps.setDouble(4, level.temperatureK());
                    ps.setDouble(5, level.windEastMs());
                    ps.setDouble(6, level.windNorthMs());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return sounding.withId(id);
        });
    }

    /**
     * Looks a sounding up by the natural key the schema makes unique.
     *
     * @param stationId the station
     * @param epochUtc  the observation time
     * @param source    the archive the file came from
     * @return the sounding with its levels, or empty if it has not been imported
     * @throws PersistenceException if the query fails
     */
    public Optional<StoredSounding> findByNaturalKey(String stationId, String epochUtc,
                                                     String source) throws PersistenceException {
        try (PreparedStatement ps = database.connection()
                .prepareStatement(SELECT_BY_NATURAL_KEY)) {
            ps.setString(1, stationId);
            ps.setString(2, epochUtc);
            ps.setString(3, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(withLevels(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read sounding by station and epoch", e);
        }
    }

    @Override
    public Optional<StoredSounding> findById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(withLevels(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read sounding " + id, e);
        }
    }

    @Override
    public List<StoredSounding> findAll() throws PersistenceException {
        List<StoredSounding> all = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(withLevels(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new PersistenceException("cannot list soundings", e);
        }
    }

    @Override
    public boolean deleteById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(DELETE)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("cannot delete sounding " + id, e);
        }
    }

    @Override
    public long count() throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new PersistenceException("cannot count soundings", e);
        }
    }

    /**
     * Counts the levels stored for one sounding.
     *
     * @param soundingId the sounding
     * @return how many level rows exist
     * @throws PersistenceException if the query fails
     */
    public long countLevels(long soundingId) throws PersistenceException {
        try (PreparedStatement ps = database.connection()
                .prepareStatement("SELECT COUNT(*) FROM sounding_level WHERE sounding_id = ?")) {
            ps.setLong(1, soundingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot count sounding levels", e);
        }
    }

    private StoredSounding withLevels(ResultSet rs) throws SQLException, PersistenceException {
        long id = rs.getLong("id");
        return new StoredSounding(id, rs.getString("station_id"), rs.getString("epoch_utc"),
                rs.getString("source"), rs.getString("file_sha256"), loadLevels(id));
    }

    private List<SoundingLevel> loadLevels(long soundingId) throws PersistenceException {
        List<SoundingLevel> levels = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_LEVELS)) {
            ps.setLong(1, soundingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    levels.add(new SoundingLevel(
                            rs.getDouble("height_gpm"),
                            rs.getDouble("pressure_pa"),
                            rs.getDouble("temperature_k"),
                            rs.getDouble("wind_u_ms"),
                            rs.getDouble("wind_v_ms")));
                }
            }
            return levels;
        } catch (SQLException e) {
            throw new PersistenceException("cannot read levels of sounding " + soundingId, e);
        }
    }
}
