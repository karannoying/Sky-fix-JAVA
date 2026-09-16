package com.skyfix.core.atmos;

import com.skyfix.domain.error.ModelDomainException;

/**
 * A single-scale-height exponential atmosphere — the cheap alternative USSA-1976 was chosen over
 * (ADR-1), kept so the report can quantify the difference rather than assert it.
 *
 * <pre>
 *   rho(h) = rho_0 exp(-h / H)
 *   p(h)   = p_0   exp(-h / H)
 *   T(h)   = p / (R rho)  — constant, by construction
 * </pre>
 *
 * <p>Its isothermal temperature is exactly the respect in which it is wrong: around the tropopause
 * it misses the real profile by roughly a tenth, and that error maps almost linearly into ascent
 * rate. {@code AtmosphereComparisonTest} measures the gap at the altitudes that matter.
 */
public final class ExponentialAtmosphere implements AtmosphereModel {

    private final double seaLevelDensityKgM3;
    private final double seaLevelPressurePa;
    private final double scaleHeightM;
    private final double ceilingM;

    /**
     * Creates the model with the conventional sea-level state and a scale height.
     *
     * @param scaleHeightM the density scale height, metres
     */
    public ExponentialAtmosphere(double scaleHeightM) {
        this(1.225, Ussa1976Atmosphere.SEA_LEVEL_PRESSURE_PA, scaleHeightM,
                Ussa1976Atmosphere.CEILING_M);
    }

    /**
     * Creates the model with an explicit sea-level state, scale height and ceiling.
     *
     * @param seaLevelDensityKgM3 density at MSL, kg/m^3
     * @param seaLevelPressurePa  pressure at MSL, Pa
     * @param scaleHeightM        the density scale height, metres
     * @param ceilingM            the highest altitude the model may be queried at, metres
     */
    public ExponentialAtmosphere(double seaLevelDensityKgM3, double seaLevelPressurePa,
                                 double scaleHeightM, double ceilingM) {
        if (scaleHeightM <= 0.0) {
            throw new IllegalArgumentException("scale height must be positive");
        }
        this.seaLevelDensityKgM3 = seaLevelDensityKgM3;
        this.seaLevelPressurePa = seaLevelPressurePa;
        this.scaleHeightM = scaleHeightM;
        this.ceilingM = ceilingM;
    }

    @Override
    public AtmosphericState stateAt(double altitudeM) throws ModelDomainException {
        if (Double.isNaN(altitudeM) || altitudeM < 0.0 || altitudeM > ceilingM) {
            throw ModelDomainException.outOfRange(
                    "geometric altitude", altitudeM, 0.0, ceilingM, "m");
        }
        double factor = Math.exp(-altitudeM / scaleHeightM);
        double density = seaLevelDensityKgM3 * factor;
        double pressure = seaLevelPressurePa * factor;
        double temperature = pressure / (Ussa1976Atmosphere.SPECIFIC_GAS_CONSTANT * density);
        double speedOfSound = Math.sqrt(
                Ussa1976Atmosphere.GAMMA * Ussa1976Atmosphere.SPECIFIC_GAS_CONSTANT * temperature);
        return new AtmosphericState(temperature, pressure, density, speedOfSound);
    }

    /** @return the density scale height, metres */
    public double scaleHeightM() {
        return scaleHeightM;
    }

    @Override
    public double minimumAltitudeM() {
        return 0.0;
    }

    @Override
    public double maximumAltitudeM() {
        return ceilingM;
    }

    @Override
    public String name() {
        return "EXPONENTIAL";
    }
}
