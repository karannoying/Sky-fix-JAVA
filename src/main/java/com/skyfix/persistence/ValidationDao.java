package com.skyfix.persistence;

import com.skyfix.domain.error.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores the reference-case results the {@code validate} command produces (FR-4.4, ADR-2).
 *
 * <p>Persisting them means the validation table in report §11 is a query against a run rather
 * than a screenshot that has to be retaken whenever the model changes.
 */
public final class ValidationDao {

    private static final String INSERT =
            "INSERT INTO validation_result (run_id, case_id, quantity, model_value, "
                    + "reference_value, unit, tolerance, tolerance_kind, passed) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_FOR_RUN =
            "SELECT case_id, quantity, model_value, reference_value, unit, tolerance, "
                    + "tolerance_kind, passed FROM validation_result WHERE run_id = ? "
                    + "ORDER BY case_id, quantity";
    private static final String COUNT_FAILED =
            "SELECT COUNT(*) FROM validation_result WHERE run_id = ? AND passed = 0";

    private final Database database;

    /**
     * @param database the database to read and write
     */
    public ValidationDao(Database database) {
        this.database = database;
    }

    /**
     * Stores a batch of validation results in one transaction.
     *
     * @param runId   the validation run
     * @param results the results
     * @return how many rows were written
     * @throws PersistenceException if the batch fails, in which case nothing is written
     */
    public int saveAll(long runId, List<ValidationResult> results) throws PersistenceException {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                for (ValidationResult r : results) {
                    ps.setLong(1, runId);
                    ps.setString(2, r.caseId());
                    ps.setString(3, r.quantity());
                    ps.setDouble(4, r.modelValue());
                    ps.setDouble(5, r.referenceValue());
                    ps.setString(6, r.unit());
                    ps.setDouble(7, r.tolerance());
                    ps.setString(8, r.toleranceKind());
                    ps.setInt(9, r.passed() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
                return results.size();
            }
        });
    }

    /**
     * Reads back the results of a validation run.
     *
     * @param runId the run
     * @return the results, ordered by case then quantity
     * @throws PersistenceException if the query fails
     */
    public List<ValidationResult> findForRun(long runId) throws PersistenceException {
        List<ValidationResult> results = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_FOR_RUN)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ValidationResult(
                            rs.getString("case_id"),
                            rs.getString("quantity"),
                            rs.getDouble("model_value"),
                            rs.getDouble("reference_value"),
                            rs.getString("unit"),
                            rs.getDouble("tolerance"),
                            rs.getString("tolerance_kind"),
                            rs.getInt("passed") != 0));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new PersistenceException("cannot read validation results for run " + runId, e);
        }
    }

    /**
     * Counts breached tolerances, which is what decides the {@code validate} exit code (FR-4.4).
     *
     * @param runId the run
     * @return how many results failed
     * @throws PersistenceException if the query fails
     */
    public long countFailed(long runId) throws PersistenceException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT_FAILED)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new PersistenceException("cannot count failed validations for run " + runId, e);
        }
    }
}
