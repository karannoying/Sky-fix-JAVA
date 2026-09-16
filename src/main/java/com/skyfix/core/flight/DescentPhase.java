package com.skyfix.core.flight;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.atmos.WindField;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.Phase;

/**
 * Burst to touchdown: weight against parachute drag (FR-2.3).
 *
 * <pre>
 *   F_up = -m g - 0.5 rho_air Cd_chute A_chute vz |vz|
 * </pre>
 *
 * <p>The lifting gas is gone, so there is no buoyancy term; the descending mass is the payload
 * plus the burst envelope, which stays attached. The parachute is modelled at constant drag
 * coefficient because descent above 25 km is drag-limited and fast, and the landing point is
 * dominated by wind advection through the last 10 km (BLUEPRINT §10).
 */
public final class DescentPhase extends FlightPhase {

    /**
     * @param atmosphere the atmosphere model
     * @param windField  the wind field
     * @param config     the balloon configuration
     * @param parameters the flight parameters for this member
     */
    public DescentPhase(AtmosphereModel atmosphere, WindField windField, BalloonConfig config,
                        FlightParameters parameters) {
        super(atmosphere, windField, config, parameters);
    }

    @Override
    protected double netVerticalForceN(AtmosphericState air, double altitudeM,
                                       double verticalRateMs) {
        double weight = massKg() * gravity();
        double drag = dragN(air.densityKgM3(), parameters.chuteCd(), config.chuteAreaM2(),
                verticalRateMs);
        return -weight + drag;
    }

    @Override
    protected double massKg() {
        // The burst envelope stays attached to the payload and descends with it.
        return config.dryMassKg();
    }

    @Override
    public double dampingRatePerSecond(AtmosphericState air, double verticalRateMs) {
        return air.densityKgM3() * parameters.chuteCd() * config.chuteAreaM2()
                * Math.abs(verticalRateMs) / massKg();
    }

    @Override
    public double diameterM(AtmosphericState air) {
        // The envelope has burst; the stored diameter stays at the burst diameter so a descent
        // record remains interpretable rather than reading as zero.
        return parameters.burstDiameterM();
    }

    @Override
    public Phase phase() {
        return Phase.DESCENT;
    }

    @Override
    public boolean isComplete(AtmosphericState air, double altitudeM, double groundElevationM) {
        return altitudeM <= groundElevationM;
    }

    /**
     * Terminal descent rate at a given air density — the closed form the descent is checked
     * against, and the figure a recovery team cares about.
     *
     * @param densityKgM3 air density, kg/m^3
     * @return the terminal rate in m/s, negative because it is downward
     */
    public double terminalRateMs(double densityKgM3) {
        return -Math.sqrt(2.0 * massKg() * gravity()
                / (densityKgM3 * parameters.chuteCd() * config.chuteAreaM2()));
    }
}
