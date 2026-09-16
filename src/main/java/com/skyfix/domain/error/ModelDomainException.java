package com.skyfix.domain.error;

/**
 * A model was queried outside the range it is valid over — for example the USSA-1976 atmosphere
 * above its 86 km ceiling (FR-2.1, ADR-1). Exit code 4.
 */
public class ModelDomainException extends SkyfixException {

    private static final long serialVersionUID = 1L;

    public ModelDomainException(String message) {
        super(message);
    }

    /**
     * Builds an out-of-range failure naming the quantity, the value and the valid interval.
     *
     * @param quantity what was queried, for example {@code "geometric altitude"}
     * @param value    the offending value, in SI units
     * @param min      inclusive lower bound of the valid range
     * @param max      inclusive upper bound of the valid range
     * @param unit     SI unit symbol, for example {@code "m"}
     * @return the exception, with the bounds in its context
     */
    public static ModelDomainException outOfRange(
            String quantity, double value, double min, double max, String unit) {
        ModelDomainException e = new ModelDomainException(
                quantity + " " + value + " " + unit + " is outside the valid range ["
                        + min + ", " + max + "] " + unit);
        e.with("quantity", quantity).with("value", value).with("min", min).with("max", max);
        return e;
    }

    @Override
    public int exitCode() {
        return 4;
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
    public ModelDomainException with(String key, Object value) {
        super.with(key, value);
        return this;
    }
}
