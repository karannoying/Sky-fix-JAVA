package com.skyfix.persistence;

/**
 * One model-versus-reference comparison. Row shape of {@code validation_result}.
 *
 * @param caseId         the test identifier, for example {@code T-V1}
 * @param quantity       what was compared, for example {@code density @ 20000 m}
 * @param modelValue     what SKYFIX computed, in {@code unit}
 * @param referenceValue what the reference says, in {@code unit}
 * @param unit           SI unit symbol
 * @param tolerance      the permitted difference
 * @param toleranceKind  {@code ABS} for an absolute tolerance, {@code REL} for a relative one
 * @param passed         whether the comparison is inside tolerance
 */
public record ValidationResult(
        String caseId,
        String quantity,
        double modelValue,
        double referenceValue,
        String unit,
        double tolerance,
        String toleranceKind,
        boolean passed) {

    /** Marker for an absolute tolerance. */
    public static final String ABSOLUTE = "ABS";
    /** Marker for a relative tolerance. */
    public static final String RELATIVE = "REL";

    /**
     * Compares a model value against a reference with a relative tolerance.
     *
     * @param caseId    the test identifier
     * @param quantity  what is being compared
     * @param model     the model value
     * @param reference the reference value
     * @param unit      SI unit symbol
     * @param tolerance the permitted relative difference, for example 0.001 for 0.1 %
     * @return the result, with {@code passed} already decided
     */
    public static ValidationResult relative(String caseId, String quantity, double model,
                                            double reference, String unit, double tolerance) {
        double error = Math.abs(model - reference) / Math.abs(reference);
        return new ValidationResult(caseId, quantity, model, reference, unit, tolerance,
                RELATIVE, error <= tolerance);
    }

    /**
     * Compares a model value against a reference with an absolute tolerance.
     *
     * @param caseId    the test identifier
     * @param quantity  what is being compared
     * @param model     the model value
     * @param reference the reference value
     * @param unit      SI unit symbol
     * @param tolerance the permitted absolute difference, in {@code unit}
     * @return the result, with {@code passed} already decided
     */
    public static ValidationResult absolute(String caseId, String quantity, double model,
                                            double reference, String unit, double tolerance) {
        return new ValidationResult(caseId, quantity, model, reference, unit, tolerance,
                ABSOLUTE, Math.abs(model - reference) <= tolerance);
    }

    /** @return the error in the units the tolerance is expressed in */
    public double error() {
        return RELATIVE.equals(toleranceKind)
                ? Math.abs(modelValue - referenceValue) / Math.abs(referenceValue)
                : Math.abs(modelValue - referenceValue);
    }
}
