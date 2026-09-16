package com.skyfix.core.flight;

import com.skyfix.domain.error.ValidationException;

import java.util.List;
import java.util.Locale;

/**
 * Resolves an integrator name from configuration to an implementation (BLUEPRINT §8).
 *
 * <p>The single place an unknown name becomes a {@link ValidationException} naming the field and
 * listing what is available, rather than a null or a silent default.
 */
public final class IntegratorFactory {

    private IntegratorFactory() {
    }

    /**
     * Creates the named integrator.
     *
     * <p>A fresh instance per call: {@link Rkf45Integrator} carries a per-step error estimate, so
     * sharing one across ensemble threads would race on it.
     *
     * @param name integrator name, case-insensitive; {@code RK4} or {@code RKF45}
     * @return a new integrator
     * @throws ValidationException if the name is blank or not one of the known integrators
     */
    public static Integrator create(String name) throws ValidationException {
        if (name == null || name.isBlank()) {
            throw ValidationException.field("integrator", name,
                    "must be one of " + available());
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "RK4" -> new Rk4Integrator();
            case "RKF45" -> new Rkf45Integrator();
            default -> throw ValidationException.field("integrator", name,
                    "is not a known integrator; expected one of " + available());
        };
    }

    /** @return the integrator names this factory accepts, in the order the report lists them */
    public static List<String> available() {
        return List.of("RK4", "RKF45");
    }
}
