package com.skyfix.domain;

/**
 * Lifting gas. Mirrors the {@code gas} CHECK constraint on {@code balloon_config}.
 *
 * <p>verify: the molar masses below are the conventional values for the two gases; the report must
 * cite IUPAC standard atomic weights for them before quoting the figures. They enter the model only
 * through the gas density, so a fourth-digit revision moves a landing point by metres.
 */
public enum LiftGas {

    /** Helium, monatomic. */
    HELIUM(4.002602e-3),

    /** Hydrogen, diatomic — more lift per unit mass, and flammable. */
    HYDROGEN(2.01588e-3);

    private final double molarMassKgPerMol;

    LiftGas(double molarMassKgPerMol) {
        this.molarMassKgPerMol = molarMassKgPerMol;
    }

    /**
     * Molar mass of the gas.
     *
     * @return molar mass in kg/mol
     */
    public double molarMassKgPerMol() {
        return molarMassKgPerMol;
    }

    /**
     * Specific gas constant R* / M for this gas.
     *
     * @return the specific gas constant in J/(kg K)
     */
    public double specificGasConstant() {
        return Units.UNIVERSAL_GAS_CONSTANT / molarMassKgPerMol;
    }

    /**
     * Density of this gas at ambient pressure and temperature.
     *
     * <p>A latex sounding balloon is unpressurised: the gas sits at ambient pressure and, to the
     * accuracy this model claims, ambient temperature (see ADR-1 consequences in the report).
     *
     * @param pressurePa    ambient pressure, Pa
     * @param temperatureK  ambient temperature, K
     * @return gas density in kg/m^3
     */
    public double densityAt(double pressurePa, double temperatureK) {
        return pressurePa / (specificGasConstant() * temperatureK);
    }
}
