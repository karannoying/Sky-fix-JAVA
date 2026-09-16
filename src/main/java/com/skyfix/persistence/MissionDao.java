package com.skyfix.persistence;

import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves missions (ADR-2).
 *
 * <p>Every statement is a {@link PreparedStatement} with bound parameters — no SQL is assembled by
 * concatenation anywhere in this class, which T-S1 verifies mechanically (CLAUDE.md rule 4).
 */
public final class MissionDao implements Repository<Mission, Long> {

    private static final String INSERT =
            "INSERT INTO mission (name, launch_lat, launch_lon, launch_alt_m, ground_elev_m, "
                    + "launch_epoch_utc) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT_BY_ID =
            "SELECT id, name, launch_lat, launch_lon, launch_alt_m, ground_elev_m, "
                    + "launch_epoch_utc FROM mission WHERE id = ?";
    private static final String SELECT_BY_NAME =
            "SELECT id, name, launch_lat, launch_lon, launch_alt_m, ground_elev_m, "
                    + "launch_epoch_utc FROM mission WHERE name = ?";
    private static final String SELECT_ALL =
            "SELECT id, name, launch_lat, launch_lon, launch_alt_m, ground_elev_m, "
                    + "launch_epoch_utc FROM mission ORDER BY id";
    private static final String DELETE = "DELETE FROM mission WHERE id = ?";
    private static final String COUNT = "SELECT COUNT(*) FROM mission";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public MissionDao(Database database) {
        this.database = database;
    }

    @Override
    public Mission save(Mission mission) throws PersistenceException {
        try (PreparedStatement ps = database.connection()
                .prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, mission.name());
            ps.setDouble(2, mission.launch().latitudeDeg());
            ps.setDouble(3, mission.launch().longitudeDeg());
            ps.setDouble(4, mission.launch().altitudeM());
            ps.setDouble(5, mission.groundElevationM());
            ps.setString(6, mission.launchEpochUtc());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return mission.withId(keys.getLong(1));
                }
            }
            throw new PersistenceException("mission insert returned no generated key");
        } catch (SQLException e) {
            throw new PersistenceException(
                    "cannot save mission \"" + mission.name() + "\": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Mission> findById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read mission " + id, e);
        }
    }

    /**
     * Looks a mission up by its unique name, which is how the CLI refers to one.
     *
     * @param name the mission name
     * @return the mission, or empty if no mission has that name
     * @throws PersistenceException if the query fails
     */
    public Optional<Mission> findByName(String name) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_BY_NAME)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read mission \"" + name + "\"", e);
        }
    }

    @Override
    public List<Mission> findAll() throws PersistenceException {
        List<Mission> all = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(map(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new PersistenceException("cannot list missions", e);
        }
    }

    /**
     * Deletes a mission.
     *
     * <p>Balloon configurations and flight logs cascade away with it. Runs do <em>not</em>:
     * {@code run.mission_id} is declared without {@code ON DELETE CASCADE}, deliberately, because
     * a run record is the provenance of a result that has been reported (NFR-5) and deleting a
     * mission must not quietly destroy it. Deleting a mission that still has runs therefore fails,
     * and the message says why rather than surfacing a raw constraint name.
     *
     * @param id the mission
     * @return {@code true} if a row was deleted
     * @throws PersistenceException if the mission still has runs, or the delete fails
     */
    @Override
    public boolean deleteById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(DELETE)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("FOREIGN KEY")) {
                throw new PersistenceException("cannot delete mission " + id
                        + ": it still has run records, which are kept as the provenance of "
                        + "results already reported. Delete those runs first if that is "
                        + "really intended.", e);
            }
            throw new PersistenceException("cannot delete mission " + id, e);
        }
    }

    @Override
    public long count() throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new PersistenceException("cannot count missions", e);
        }
    }

    private static Mission map(ResultSet rs) throws SQLException {
        return new Mission(
                rs.getLong("id"),
                rs.getString("name"),
                new GeoPoint(rs.getDouble("launch_lat"), rs.getDouble("launch_lon"),
                        rs.getDouble("launch_alt_m")),
                rs.getDouble("ground_elev_m"),
                rs.getString("launch_epoch_utc"));
    }
}
