package com.skyfix.persistence;

import com.skyfix.domain.Posterior;
import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores and retrieves the filter's per-update posteriors (FR-3.2, ADR-2).
 *
 * <p>The {@code estimate} table is one row per parameter per update, keyed by
 * {@code (run_id, update_epoch_utc, param_name)}, so a {@link Posterior} becomes four rows written
 * together. Storing it long rather than wide is what lets the schema's CHECK constrain the
 * parameter names and the ordering of each band, and what lets the report plot a parameter's
 * history with one query rather than four columns of hand-written SQL.
 *
 * <p>Every statement is a {@link PreparedStatement} (CLAUDE.md rule 4), and a posterior is written
 * inside one transaction: four rows that disagree about which update they belong to would be
 * worse than no rows at all.
 */
public final class EstimateDao {

    private static final String INSERT =
            "INSERT INTO estimate (run_id, update_epoch_utc, param_name, median, p05, p95, ess, "
                    + "resample_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_FOR_RUN =
            "SELECT update_epoch_utc, param_name, median, p05, p95, ess, resample_count "
                    + "FROM estimate WHERE run_id = ? ORDER BY update_epoch_utc, param_name";
    private static final String SELECT_LATEST_EPOCH =
            "SELECT MAX(update_epoch_utc) AS latest FROM estimate WHERE run_id = ?";
    private static final String COUNT_UPDATES =
            "SELECT COUNT(DISTINCT update_epoch_utc) AS updates FROM estimate WHERE run_id = ?";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public EstimateDao(Database database) {
        this.database = database;
    }

    /**
     * Stores one posterior as one row per parameter.
     *
     * @param runId     the owning run
     * @param posterior the posterior to store
     * @throws PersistenceException if any row fails, in which case none are written
     */
    public void save(long runId, Posterior posterior) throws PersistenceException {
        saveAll(runId, List.of(posterior));
    }

    /**
     * Stores several posteriors in one transaction.
     *
     * <p>A replay produces one posterior per assimilated sample, and writing each in its own
     * transaction would make the commit cost dominate the run. Batched, a few hundred updates are
     * one round trip.
     *
     * @param runId      the owning run
     * @param posteriors the posteriors, in update order
     * @throws PersistenceException if any row fails, in which case none are written
     */
    public void saveAll(long runId, List<Posterior> posteriors) throws PersistenceException {
        if (posteriors.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                for (Posterior posterior : posteriors) {
                    for (Map.Entry<String, Posterior.Band> entry
                            : posterior.parameters().entrySet()) {
                        Posterior.Band band = entry.getValue();
                        ps.setLong(1, runId);
                        ps.setString(2, posterior.epochUtc().toString());
                        ps.setString(3, entry.getKey());
                        ps.setDouble(4, band.median());
                        ps.setDouble(5, band.p05());
                        ps.setDouble(6, band.p95());
                        ps.setDouble(7, posterior.effectiveSampleSize());
                        ps.setInt(8, posterior.resampleCount());
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
                return posteriors.size();
            }
        });
    }

    /**
     * Reads every posterior stored for a run, in update order.
     *
     * @param runId         the run
     * @param particleCount the particle count the run used; the table stores the ESS but not the N
     *                      it is a fraction of, since that belongs to the run rather than to each
     *                      of its thousands of estimate rows
     * @return one posterior per update epoch
     * @throws PersistenceException if the query fails
     */
    public List<Posterior> findForRun(long runId, int particleCount) throws PersistenceException {
        Map<String, Builder> byEpoch = new LinkedHashMap<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_FOR_RUN)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String epoch = rs.getString("update_epoch_utc");
                    Builder builder = byEpoch.computeIfAbsent(epoch,
                            e -> new Builder(e, rs(rs)));
                    builder.add(rs.getString("param_name"), new Posterior.Band(
                            rs.getDouble("median"), rs.getDouble("p05"), rs.getDouble("p95")));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read estimates for run " + runId, e);
        }

        List<Posterior> out = new ArrayList<>(byEpoch.size());
        for (Builder builder : byEpoch.values()) {
            out.add(builder.build(particleCount));
        }
        return out;
    }

    /**
     * The epoch of the most recent update stored for a run.
     *
     * <p>Epochs are stored as ISO-8601 UTC strings, whose lexicographic order is their
     * chronological order, so {@code MAX} is the latest update and not merely the largest text.
     *
     * @param runId the run
     * @return the latest update epoch, or {@code null} if the run has no estimates
     * @throws PersistenceException if the query fails
     */
    public Instant latestEpoch(long runId) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_LATEST_EPOCH)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String latest = rs.getString("latest");
                    return latest == null ? null : Instant.parse(latest);
                }
                return null;
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot read the latest estimate epoch for run "
                    + runId, e);
        }
    }

    /**
     * How many updates a run recorded.
     *
     * @param runId the run
     * @return the number of distinct update epochs
     * @throws PersistenceException if the query fails
     */
    public int updateCount(long runId) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT_UPDATES)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("updates") : 0;
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot count estimates for run " + runId, e);
        }
    }

    /** Reads the two per-update columns that are repeated on every row of an update. */
    private static double[] rs(ResultSet rs) {
        try {
            return new double[]{rs.getDouble("ess"), rs.getInt("resample_count")};
        } catch (SQLException e) {
            throw new IllegalStateException("estimate row is missing its per-update columns", e);
        }
    }

    /** Collects the four rows of one update back into a posterior. */
    private static final class Builder {

        private final String epoch;
        private final double ess;
        private final int resampleCount;
        private final Map<String, Posterior.Band> bands = new LinkedHashMap<>();

        private Builder(String epoch, double[] perUpdate) {
            this.epoch = epoch;
            this.ess = perUpdate[0];
            this.resampleCount = (int) perUpdate[1];
        }

        private void add(String name, Posterior.Band band) {
            bands.put(name, band);
        }

        private Posterior build(int particleCount) {
            return new Posterior(Instant.parse(epoch), bands, ess, resampleCount, particleCount);
        }
    }
}
