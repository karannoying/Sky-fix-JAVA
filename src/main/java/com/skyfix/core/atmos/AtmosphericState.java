package com.skyfix.core.atmos;

/**
 * Thermodynamic state of the air at one altitude. All values SI.
 *
 * @param temperatureK  temperature, K
 * @param pressurePa    pressure, Pa
 * @param densityKgM3   density, kg/m^3
 * @param speedOfSoundMs speed of sound, m/s
 */
public record AtmosphericState(
        double temperatureK,
        double pressurePa,
        double densityKgM3,
        double speedOfSoundMs) {
}
