package com.skyfix.persistence;

import com.skyfix.domain.error.PersistenceException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the SQLite connection (ADR-2).
 *
 * <p>Deliberately <strong>not</strong> a singleton: a singleton hides the connection's lifetime
 * and makes per-test isolation impossible. One {@code Database} is constructed in {@code Main}
 * and injected into the DAOs; each test constructs its own against a temporary file.
 *
 * <p>Implements {@link AutoCloseable}, so callers close it with try-with-resources.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;
    private final String url;

    private Database(Connection connection, String url) {
        this.connection = connection;
        this.url = url;
    }

    /**
     * Opens, or creates, a database file.
     *
     * @param path the database file; parent directories must already exist
     * @return the open database, with foreign keys enforced
     * @throws PersistenceException if the file cannot be opened
     */
    public static Database openFile(Path path) throws PersistenceException {
        return open("jdbc:sqlite:" + path.toAbsolutePath());
    }

    /**
     * Opens a private in-memory database, for tests that do not need a file.
     *
     * @return the open database, with foreign keys enforced
     * @throws PersistenceException if the database cannot be opened
     */
    public static Database openInMemory() throws PersistenceException {
        return open("jdbc:sqlite::memory:");
    }

    private static Database open(String url) throws PersistenceException {
        try {
            Connection connection = DriverManager.getConnection(url);
            // SQLite does not enforce foreign keys unless asked, per connection. Without this
            // the ON DELETE CASCADE rules in V1__init.sql would be inert and T-D3 would be
            // testing nothing.
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            return new Database(connection, url);
        } catch (SQLException e) {
            throw new PersistenceException("cannot open database at " + url, e);
        }
    }

    /**
     * The live JDBC connection.
     *
     * @return the connection; callers must not close it, the {@code Database} owns it
     */
    public Connection connection() {
        return connection;
    }

    /** @return the JDBC URL this database was opened with, for run records */
    public String url() {
        return url;
    }

    /**
     * Whether foreign-key enforcement is actually on.
     *
     * <p>Worth being able to assert: a silently-off PRAGMA would turn every cascade test green
     * for the wrong reason.
     *
     * @return {@code true} if the connection enforces foreign keys
     * @throws PersistenceException if the pragma cannot be read
     */
    public boolean foreignKeysEnforced() throws PersistenceException {
        try (Statement s = connection.createStatement();
             java.sql.ResultSet rs = s.executeQuery("PRAGMA foreign_keys")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            throw new PersistenceException("cannot read the foreign_keys pragma", e);
        }
    }

    /**
     * Runs a unit of work inside a transaction, committing on success and rolling back on any
     * failure (T-D4).
     *
     * @param work the work to run; it receives the connection
     * @param <T>  what the work returns
     * @return whatever {@code work} returned
     * @throws PersistenceException if the work fails, wrapping the cause after the rollback
     */
    public <T> T inTransaction(TransactionalWork<T> work) throws PersistenceException {
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new PersistenceException("cannot begin a transaction", e);
        }
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                PersistenceException wrapped = new PersistenceException(
                        "transaction failed and the rollback failed too", e);
                wrapped.addSuppressed(rollbackFailure);
                throw wrapped;
            }
            if (e instanceof PersistenceException persistence) {
                throw persistence;
            }
            throw new PersistenceException("transaction rolled back: " + e.getMessage(), e);
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
                // The connection is being torn down anyway; nothing useful to do here.
            }
        }
    }

    @Override
    public void close() throws PersistenceException {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("cannot close the database", e);
        }
    }

    /**
     * A unit of work to run inside a transaction.
     *
     * @param <T> what the work returns
     */
    @FunctionalInterface
    public interface TransactionalWork<T> {
        /**
         * Performs the work.
         *
         * @param connection the transactional connection
         * @return the result
         * @throws Exception if the work fails, which rolls the transaction back
         */
        T run(Connection connection) throws Exception;
    }
}
