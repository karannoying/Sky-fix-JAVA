package com.skyfix.core.flight;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.atmos.WindField;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.Phase;

/**
 * Launch to burst: buoyancy minus weight minus drag, with the envelope expanding as ambient
 * pressure falls (FR-2.3).
 *
 * <pre>
 *   V(h)  = m_gas R_gas T(h) / p(h)          unpressurised envelope at ambient p and T
 *   F_up  = rho_air V g - m g - 0.5 rho_air Cd A vz |vz|
 * </pre>
 *
 * <p>The gas mass is fixed at launch by the free lift (ADR-11) and does not change: it is the
 * conserved quantity T-V4 checks.
 */
public final class AscentPhase extends FlightPhase {

    private final double gasMassKg;

    /**
     * @param atmosphere the atmosphere model
     * @param windField  the wind field
     * @param config     the balloon configuration
     * @param parameters the flight parameters for this member
     * @param gasMassKg  the sealed-in lifting gas mass, kg, fixed at launch
     */
    public AscentPhase(AtmosphereModel atmosphere, WindField windField, BalloonConfig config,
                       FlightParameters parameters, double gasMassKg) {
        super(atmosphere, windField, config, parameters);
        this.gasMassKg = gasMassKg;
    }

    @Override
    protected double netVerticalForceN(AtmosphericState air, double altitudeM,
                                       double verticalRateMs) {
        double volume = config.volumeAt(gasMassKg, air.pressurePa(), air.temperatureK());
        double diameter = BalloonConfig.diameterOfVolume(volume);
        double area = BalloonConfig.frontalAreaOfDiameter(diameter);

        double buoyancy = air.densityKgM3() * volume * gravity();
        double weight = massKg() * gravity();
        double drag = dragN(air.densityKgM3(), parameters.ascentCd(), area, verticalRateMs);

        return buoyancy - weight + drag;
    }

    @Override
    protected double massKg() {
        // Payload, envelope and the gas itself all accelerate together.
        return config.dryMassKg() + gasMassKg;
    }

    @Override
    public double dampingRatePerSecond(AtmosphericState air, double verticalRateMs) {
        double area = BalloonConfig.frontalAreaOfDiameter(diameterM(air));
        return air.densityKgM3() * parameters.ascentCd() * area * Math.abs(verticalRateMs)
                / massKg();
    }

    @Override
    public double diameterM(AtmosphericState air) {
        return BalloonConfig.diameterOfVolume(
                config.volumeAt(gasMassKg, air.pressurePa(), air.temperatureK()));
    }

    @Override
    public Phase phase() {
        return Phase.ASCENT;
    }

    @Override
    public boolean isComplete(AtmosphericState air, double altitudeM, double groundElevationM) {
        // Ascent ends at burst. The threshold is the estimated burst diameter, which makes the
        // manufacturer's figure a prior rather than a truth (BLUEPRINT §10).
        return diameterM(air) >= parameters.burstDiameterM();
    }

    /** @return the sealed-in gas mass, kg — the invariant T-V4 checks */
    public double gasMassKg() {
        return gasMassKg;
    }
}
