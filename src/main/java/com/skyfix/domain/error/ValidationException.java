package com.skyfix.domain.error;

/**
 * User input violates a stated rule — a range, a cross-field physics constraint, a missing
 * required value. Exit code 2 (BLUEPRINT §12).
 *
 * <p>The message always names the offending field, for example
 * {@code burst_diameter_m (1.20) must exceed launch_diameter_m (1.60)}.
 */
public class ValidationException extends SkyfixException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Builds a field-range failure with a uniform message shape.
     *
     * @param field field name as it appears in the user's configuration file
     * @param value the rejected value
     * @param rule  human-readable rule the value broke, for example {@code "must be > 0"}
     * @return the exception, with {@code field} and {@code value} in its context
     */
    public static ValidationException field(String field, Object value, String rule) {
        ValidationException e = new ValidationException(field + " (" + value + ") " + rule);
        e.with("field", field).with("value", value);
        return e;
    }

    @Override
    public int exitCode() {
        return 2;
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
    public ValidationException with(String key, Object value) {
        super.with(key, value);
        return this;
    }
}
