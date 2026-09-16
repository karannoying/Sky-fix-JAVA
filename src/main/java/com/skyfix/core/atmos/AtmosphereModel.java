package com.skyfix.core.atmos;

import com.skyfix.domain.error.ModelDomainException;

/**
 * Strategy for the atmosphere (BLUEPRINT §8).
 *
 * <p>Two implementations ship: {@link Ussa1976Atmosphere}, the model SKYFIX flies with, and
 * {@link ExponentialAtmosphere}, the cheap approximation it is compared against. Having both
 * behind one interface is what lets T-V1 and the report quantify what the simpler model costs
 * rather than assert it.
 *
 * <p>Altitudes are geometric, above mean sea level, in metres.
 */
public interface AtmosphereModel {

    /**
     * Queries the full thermodynamic state at an altitude.
     *
     * @param altitudeM geometric altitude above MSL, metres
     * @return the atmospheric state there
     * @throws ModelDomainException if the altitude is outside the model's valid range
     */
    AtmosphericState stateAt(double altitudeM) throws ModelDomainException;

    /**
     * Air temperature at an altitude.
     *
     * @param altitudeM geometric altitude above MSL, metres
     * @return temperature in K
     * @throws ModelDomainException if the altitude is outside the model's valid range
     */
    default double temperatureK(double altitudeM) throws ModelDomainException {
        return stateAt(altitudeM).temperatureK();
    }

    /**
     * Air pressure at an altitude.
     *
     * @param altitudeM geometric altitude above MSL, metres
     * @return pressure in Pa
     * @throws ModelDomainException if the altitude is outside the model's valid range
     */
    default double pressurePa(double altitudeM) throws ModelDomainException {
        return stateAt(altitudeM).pressurePa();
    }

    /**
     * Air density at an altitude.
     *
     * @param altitudeM geometric altitude above MSL, metres
     * @return density in kg/m^3
     * @throws ModelDomainException if the altitude is outside the model's valid range
     */
    default double densityKgM3(double altitudeM) throws ModelDomainException {
        return stateAt(altitudeM).densityKgM3();
    }

    /** @return the lowest geometric altitude this model is valid at, metres */
    double minimumAltitudeM();

    /** @return the highest geometric altitude this model is valid at, metres */
    double maximumAltitudeM();

    /** @return a short name for run records and console output */
    String name();
}
