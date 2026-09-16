package com.skyfix.domain.error;

/**
 * Wraps a {@link java.sql.SQLException} with the statement context that caused it, so a database
 * failure names the operation rather than leaking a raw JDBC trace (NFR-3). Exit code 6.
 */
public class PersistenceException extends SkyfixException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }

    @Override
    public int exitCode() {
        return 6;
    }

    /**
     * Narrows {@link SkyfixException#with} to this type, so a throw site that has already
     * chosen a specific failure does not lose it by adding context.
     *
     * @param key   context key
     * @param value context value
     * @return this exception
     */
    @Override
    public PersistenceException with(String key, Object value) {
        super.with(key, value);
        return this;
    }
}
