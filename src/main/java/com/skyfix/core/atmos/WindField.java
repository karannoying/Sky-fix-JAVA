package com.skyfix.core.atmos;

import com.skyfix.domain.error.SkyfixException;

/**
 * Strategy for the horizontal wind the balloon is advected by (FR-2.2, BLUEPRINT §8).
 *
 * <p>{@link ConstantWindField} is the analytic test oracle — with a constant wind the drift is
 * exactly speed times time, so a simulated trajectory can be checked against arithmetic.
 * {@link SoundingWindField} is what flies.
 */
public interface WindField {

    /**
     * Queries the wind at a time and altitude.
     *
     * @param timeSeconds seconds since launch
     * @param altitudeM   geometric altitude above MSL, metres
     * @return the wind sample, with its extrapolation flag set truthfully
     * @throws SkyfixException if the query cannot be answered at all
     */
    WindSample at(double timeSeconds, double altitudeM) throws SkyfixException;

    /** @return a short name for run records and console output */
    String name();
}
