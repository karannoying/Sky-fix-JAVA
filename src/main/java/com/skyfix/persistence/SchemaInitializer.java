package com.skyfix.persistence;

import com.skyfix.domain.error.PersistenceException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Applies {@code src/main/resources/schema/V1__init.sql} idempotently and records the applied
 * version in {@code schema_version} (ADR-2).
 *
 * <p>The DDL script is not written to be re-runnable — most of its {@code CREATE TABLE} statements
 * have no {@code IF NOT EXISTS} — so idempotency is handled the way a migration runner handles it:
 * the version table is consulted first, and the script runs only if its version has never been
 * recorded. That keeps the shipped schema file identical to the one in {@code docs/schema/} and
 * reviewable as a single artefact.
 */
public final class SchemaInitializer {

    private static final Logger LOG = Logger.getLogger(SchemaInitializer.class.getName());

    /** The schema version this release ships. */
    public static final int SCHEMA_VERSION = 1;

    private static final String SCRIPT = "/schema/V1__init.sql";

    private final Database database;

    /**
     * @param database the database to initialise
     */
    public SchemaInitializer(Database database) {
        this.database = database;
    }

    /**
     * Ensures the schema exists, applying it if it has not been applied already.
     *
     * @return {@code true} if the schema was applied by this call, {@code false} if it was
     *         already present
     * @throws PersistenceException if the script is missing or the DDL fails
     */
    public boolean initialise() throws PersistenceException {
        ensureVersionTable();
        if (appliedVersion() >= SCHEMA_VERSION) {
            return false;
        }
        String script = readScript();
        return database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : splitStatements(script)) {
                    statement.execute(ddl);
                }
            }
            // A PreparedStatement even here, where the values are ours: T-S1 scans src/main for
            // string-built SQL and the rule holds without exception (CLAUDE.md rule 4).
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_version (version, applied_utc) VALUES (?, ?)")) {
                insert.setInt(1, SCHEMA_VERSION);
                insert.setString(2, Instant.now().toString());
                insert.executeUpdate();
            }
            LOG.info(() -> "applied schema version " + SCHEMA_VERSION);
            return true;
        });
    }

    /**
     * The highest schema version recorded in this database.
     *
     * @return the version, or 0 if none has been applied
     * @throws PersistenceException if the version table cannot be read
     */
    public int appliedVersion() throws PersistenceException {
        try (PreparedStatement select = database.connection().prepareStatement(
                "SELECT COALESCE(MAX(version), 0) FROM schema_version");
             ResultSet rs = select.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new PersistenceException("cannot read schema_version", e);
        }
    }

    private void ensureVersionTable() throws PersistenceException {
        try (Statement s = database.connection().createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INTEGER PRIMARY KEY, applied_utc TEXT NOT NULL)");
        } catch (SQLException e) {
            throw new PersistenceException("cannot create schema_version", e);
        }
    }

    private static String readScript() throws PersistenceException {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new PersistenceException("schema script " + SCRIPT
                        + " is missing from the jar; the build did not include src/main/resources");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PersistenceException("cannot read schema script " + SCRIPT, e);
        }
    }

    /**
     * Splits the DDL script into executable statements.
     *
     * <p>Comment lines are stripped first so a {@code --} comment containing a semicolon cannot
     * split a statement in the middle.
     */
    static java.util.List<String> splitStatements(String script) {
        StringBuilder cleaned = new StringBuilder(script.length());
        for (String line : script.split("\\R")) {
            String withoutComment = line;
            int comment = withoutComment.indexOf("--");
            if (comment >= 0) {
                withoutComment = withoutComment.substring(0, comment);
            }
            cleaned.append(withoutComment).append('\n');
        }
        java.util.List<String> statements = new java.util.ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }
}
