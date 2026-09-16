package com.skyfix.persistence;

import com.skyfix.domain.Ensemble;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves ensemble members (FR-2.4, ADR-2).
 *
 * <p>Every member is stored, including the ones that failed — their landing columns are null.
 * BLUEPRINT §9 keeps the full landing set here precisely so a footprint can be re-fitted, or a
 * containment figure recomputed, without re-running the ensemble.
 *
 * <p>Members go in as one batch inside one transaction, so a run never has a partial member set.
 */
public final class EnsembleDao {

    private static final String INSERT =
            "INSERT INTO ensemble_member (run_id, member_index, free_lift_kg, ascent_cd, "
                    + "burst_diameter_m, chute_cd, wind_scale, burst_alt_m, landing_lat, "
                    + "landing_lon, landing_epoch_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_FOR_RUN =
            "SELECT member_index, free_lift_kg, ascent_cd, burst_diameter_m, chute_cd, "
                    + "wind_scale, burst_alt_m, landing_lat, landing_lon FROM ensemble_member "
                    + "WHERE run_id = ? ORDER BY member_index";
    private static final String COUNT_FOR_RUN =
            "SELECT COUNT(*) FROM ensemble_member WHERE run_id = ?";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public EnsembleDao(Database database) {
        this.database = database;
    }

    /**
     * Stores every member of an ensemble.
     *
     * @param runId            the owning run
     * @param ensemble         the ensemble
     * @param landingEpochUtc  the nominal landing time to stamp, ISO-8601 UTC, or {@code null}
     * @return how many member rows were written
     * @throws PersistenceException if the batch fails, in which case nothing is written
     */
    public int saveAll(long runId, Ensemble ensemble, String landingEpochUtc)
            throws PersistenceException {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                for (Ensemble.Member m : ensemble.members()) {
                    FlightParameters p = m.parameters();
                    ps.setLong(1, runId);
                    ps.setInt(2, m.index());
                    ps.setDouble(3, p.freeLiftKg());
                    ps.setDouble(4, p.ascentCd());
                    ps.setDouble(5, p.burstDiameterM());
                    ps.setDouble(6, p.chuteCd());
                    ps.setDouble(7, p.windScale());
                    setNullableDouble(ps, 8, Double.isNaN(m.burstAltM()) ? null : m.burstAltM());
                    setNullableDouble(ps, 9,
                            m.landing().map(GeoPoint::latitudeDeg).orElse(null));
                    setNullableDouble(ps, 10,
                            m.landing().map(GeoPoint::longitudeDeg).orElse(null));
                    if (m.succeeded() && landingEpochUtc != null) {
                        ps.setString(11, landingEpochUtc);
                    } else {
                        ps.setNull(11, Types.VARCHAR);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
                return ensemble.members().size();
            }
        });
    }

    /**
     * Reads an ensemble back.
     *
     * @param runId the run
     * @param seed  the seed to attach to the reconstructed ensemble
     * @return the members in index order, or an empty ensemble if the run stored none
     * @throws PersistenceException if the query fails
     */
    public Ensemble findForRun(long runId, long seed) throws PersistenceException {
        List<Ensemble.Member> members = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_FOR_RUN)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(map(rs));
                }
            }
            return new Ensemble(members, seed, 0L);
        } catch (SQLException e) {
            throw new PersistenceException("cannot read ensemble members for run " + runId, e);
        }
    }

    /**
     * Counts the member rows stored for a run.
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
            throw new PersistenceException("cannot count ensemble members for run " + runId, e);
        }
    }

    private static Ensemble.Member map(ResultSet rs) throws SQLException {
        FlightParameters parameters = new FlightParameters(
                rs.getDouble("free_lift_kg"),
                rs.getDouble("ascent_cd"),
                rs.getDouble("burst_diameter_m"),
                rs.getDouble("chute_cd"),
                rs.getDouble("wind_scale"));
        int index = rs.getInt("member_index");

        double lat = rs.getDouble("landing_lat");
        boolean noLanding = rs.wasNull();
        double lon = rs.getDouble("landing_lon");
        noLanding = noLanding || rs.wasNull();
        double burst = rs.getDouble("burst_alt_m");
        double burstAlt = rs.wasNull() ? Double.NaN : burst;

        if (noLanding) {
            return Ensemble.Member.failed(index, parameters, "no landing point stored");
        }
        return Ensemble.Member.landed(index, parameters, new GeoPoint(lat, lon, 0.0), burstAlt);
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }
}
