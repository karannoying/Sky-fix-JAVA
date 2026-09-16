package com.skyfix.core.flight;

import com.skyfix.domain.error.SkyfixException;

/**
 * The right-hand side of an ODE system, {@code y' = f(t, y)}.
 *
 * <p>Allowed to fail: evaluating the flight derivative queries the atmosphere and the wind field,
 * and either can refuse a state that has left its valid range (FR-2.1, FR-2.2).
 */
@FunctionalInterface
public interface Derivatives {

    /**
     * Evaluates the derivative of the state vector.
     *
     * @param t independent variable, seconds
     * @param y state vector; implementations must not modify it
     * @return a new array holding {@code dy/dt}, the same length as {@code y}
     * @throws SkyfixException if the state cannot be evaluated
     */
    double[] at(double t, double[] y) throws SkyfixException;
}
