package com.skyfix.persistence;

import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.error.PersistenceException;
import com.skyfix.domain.error.ValidationException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves balloon configurations (ADR-2, ADR-10).
 *
 * <p>The {@code config_hash} column is UNIQUE, so storing the same configuration twice is an
 * error the database catches rather than a silent duplicate. {@link #findOrSave} is what callers
 * normally want: the same configuration maps to the same row.
 */
public final class BalloonConfigDao implements Repository<StoredBalloonConfig, Long> {

    private static final String INSERT =
            "INSERT INTO balloon_config (mission_id, name, payload_mass_kg, envelope_mass_kg, "
                    + "launch_diameter_m, burst_diameter_m, free_lift_kg, ascent_cd, "
                    + "chute_area_m2, chute_cd, gas, config_hash) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_BY_ID =
            "SELECT id, mission_id, name, payload_mass_kg, envelope_mass_kg, "
                    + "launch_diameter_m, burst_diameter_m, free_lift_kg, ascent_cd, "
                    + "chute_area_m2, chute_cd, gas, config_hash "
                    + "FROM balloon_config WHERE id = ?";
    private static final String SELECT_BY_HASH =
            "SELECT id, mission_id, name, payload_mass_kg, envelope_mass_kg, "
                    + "launch_diameter_m, burst_diameter_m, free_lift_kg, ascent_cd, "
                    + "chute_area_m2, chute_cd, gas, config_hash "
                    + "FROM balloon_config WHERE config_hash = ?";
    private static final String SELECT_ALL =
            "SELECT id, mission_id, name, payload_mass_kg, envelope_mass_kg, "
                    + "launch_diameter_m, burst_diameter_m, free_lift_kg, ascent_cd, "
                    + "chute_area_m2, chute_cd, gas, config_hash "
                    + "FROM balloon_config ORDER BY id";
    private static final String DELETE = "DELETE FROM balloon_config WHERE id = ?";
    private static final String COUNT = "SELECT COUNT(*) FROM balloon_config";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public BalloonConfigDao(Database database) {
        this.database = database;
    }

    @Override
    public StoredBalloonConfig save(StoredBalloonConfig stored) throws PersistenceException {
        BalloonConfig c = stored.config();
        try (PreparedStatement ps = database.connection()
                .prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, stored.missionId());
            ps.setString(2, c.name());
            ps.setDouble(3, c.payloadMassKg());
            ps.setDouble(4, c.envelopeMassKg());
            ps.setDouble(5, c.launchDiameterM());
            ps.setDouble(6, c.burstDiameterM());
            ps.setDouble(7, c.freeLiftKg());
            ps.setDouble(8, c.ascentCd());
            ps.setDouble(9, c.chuteAreaM2());
            ps.setDouble(10, c.chuteCd());
            ps.setString(11, c.gas().name());
            ps.setString(12, c.configHash());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return stored.withId(keys.getLong(1));
                }
            }
            throw new PersistenceException("balloon_config insert returned no generated key");
        } catch (SQLException e) {
            throw new PersistenceException(
                    "cannot save balloon config \"" + c.name() + "\": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the row for this configuration, inserting it if it is not stored yet.
     *
     * <p>Identity is the config hash (ADR-10), so re-running with an unchanged configuration
     * reuses the row rather than creating a near-duplicate.
     *
     * @param missionId the owning mission
     * @param config    the configuration
     * @return the stored row, carrying its key
     * @throws PersistenceException if the query or insert fails
     */
    public StoredBalloonConfig findOrSave(long missionId, BalloonConfig config)
            throws PersistenceException {
        Optional<StoredBalloonConfig> existing = findByHash(config.configHash());
        return existing.isPresent()
                ? existing.get()
                : save(new StoredBalloonConfig(null, missionId, config));
    }

    /**
     * Looks a configuration up by its SHA-256 identity (ADR-10).
     *
     * @param configHash the hex digest
     * @return the row, or empty if this exact configuration has never been stored
     * @throws PersistenceException if the query fails
     */
    public Optional<StoredBalloonConfig> findByHash(String configHash)
            throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_BY_HASH)) {
            ps.setString(1, configHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read balloon config by hash", e);
        }
    }

    @Override
    public Optional<StoredBalloonConfig> findById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read balloon config " + id, e);
        }
    }

    @Override
    public List<StoredBalloonConfig> findAll() throws PersistenceException {
        List<StoredBalloonConfig> all = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(map(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new PersistenceException("cannot list balloon configs", e);
        }
    }

    @Override
    public boolean deleteById(Long id) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(DELETE)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("cannot delete balloon config " + id, e);
        }
    }

    @Override
    public long count() throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new PersistenceException("cannot count balloon configs", e);
        }
    }

    private static StoredBalloonConfig map(ResultSet rs) throws PersistenceException {
        try {
            BalloonConfig config = BalloonConfig.builder()
                    .name(rs.getString("name"))
                    .payloadMassKg(rs.getDouble("payload_mass_kg"))
                    .envelopeMassKg(rs.getDouble("envelope_mass_kg"))
                    .launchDiameterM(rs.getDouble("launch_diameter_m"))
                    .burstDiameterM(rs.getDouble("burst_diameter_m"))
                    .freeLiftKg(rs.getDouble("free_lift_kg"))
                    .ascentCd(rs.getDouble("ascent_cd"))
                    .chuteAreaM2(rs.getDouble("chute_area_m2"))
                    .chuteCd(rs.getDouble("chute_cd"))
                    .gas(LiftGas.valueOf(rs.getString("gas")))
                    .build();

            // A row that no longer rebuilds to its stored hash means the file was edited behind
            // the application's back, which would break every reproducibility claim built on it.
            String storedHash = rs.getString("config_hash");
            if (!config.configHash().equals(storedHash)) {
                throw new PersistenceException("balloon_config row " + rs.getLong("id")
                        + " does not rebuild to its stored config_hash; the database has been "
                        + "modified outside SKYFIX and runs referencing it are not reproducible");
            }
            return new StoredBalloonConfig(rs.getLong("id"), rs.getLong("mission_id"), config);
        } catch (SQLException e) {
            throw new PersistenceException("cannot map a balloon_config row", e);
        } catch (ValidationException e) {
            throw new PersistenceException(
                    "a stored balloon_config row is no longer valid: " + e.getMessage(), e);
        }
    }
}
