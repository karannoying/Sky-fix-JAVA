package com.skyfix.core.flight;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.atmos.WindField;
import com.skyfix.core.atmos.WindSample;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.Phase;
import com.skyfix.domain.Units;
import com.skyfix.domain.error.SkyfixException;

/**
 * One stage of flight, as a Template Method (BLUEPRINT §8).
 *
 * <p>{@link #derivative()} fixes the sequence every phase follows — query the atmosphere, query
 * the wind, form the vertical net force, advect horizontally — and subclasses supply only the two
 * things that actually differ between ascending under a balloon and descending under a parachute:
 * {@link #netVerticalForceN} and {@link #massKg()}. Burst therefore becomes a change of phase
 * object rather than a special case threaded through the simulator.
 *
 * <p>The state vector is {@code [latitude deg, longitude deg, altitude m, vertical rate m/s]}.
 */
public abstract class FlightPhase {

    /** Index of latitude, degrees, in the state vector. */
    public static final int LAT = 0;
    /** Index of longitude, degrees, in the state vector. */
    public static final int LON = 1;
    /** Index of geometric altitude above MSL, metres, in the state vector. */
    public static final int ALT = 2;
    /** Index of vertical rate, m/s positive upwards, in the state vector. */
    public static final int VZ = 3;
    /** Length of the state vector. */
    public static final int STATE_SIZE = 4;

    /** The atmosphere model this phase queries. */
    protected final AtmosphereModel atmosphere;
    /** The wind field this phase is advected by. */
    protected final WindField windField;
    /** The balloon configuration; immutable and shared across ensemble threads. */
    protected final BalloonConfig config;
    /** The dispersed or estimated parameters for this particular flight. */
    protected final FlightParameters parameters;

    /** Whether the most recent wind query fell outside the wind field's known range. */
    private boolean lastWindExtrapolated;

    /**
     * @param atmosphere the atmosphere model
     * @param windField  the wind field
     * @param config     the balloon configuration
     * @param parameters the flight parameters for this member
     */
    protected FlightPhase(AtmosphereModel atmosphere, WindField windField, BalloonConfig config,
                          FlightParameters parameters) {
        this.atmosphere = atmosphere;
        this.windField = windField;
        this.config = config;
        this.parameters = parameters;
    }

    /**
     * The derivative of the state vector, assembled in the fixed order this template defines.
     *
     * @return the right-hand side, ready to hand to an {@link Integrator}
     */
    public final Derivatives derivative() {
        return (t, y) -> {
            AtmosphericState air = atmosphere.stateAt(y[ALT]);
            WindSample wind = windField.at(t, y[ALT]).scaled(parameters.windScale());
            if (wind.extrapolated()) {
                lastWindExtrapolated = true;
            }

            double[] dy = new double[STATE_SIZE];
            // Horizontal advection: the balloon is carried by the wind with no slip.
            dy[LAT] = wind.northMs() / Geodesy.metresPerDegreeLatitude();
            dy[LON] = wind.eastMs() / Geodesy.metresPerDegreeLongitude(y[LAT]);
            dy[ALT] = y[VZ];
            dy[VZ] = netVerticalForceN(air, y[ALT], y[VZ]) / massKg();
            return dy;
        };
    }

    /**
     * Net upward force on the vehicle, in newtons.
     *
     * <p>The drag term is written {@code -0.5 rho Cd A vz |vz|} in both subclasses, which gives a
     * force opposing motion whichever way the vehicle is moving, with no sign test.
     *
     * @param air        the atmospheric state at the current altitude
     * @param altitudeM  geometric altitude above MSL, metres
     * @param verticalRateMs vertical rate, m/s positive upwards
     * @return the net upward force in newtons
     * @throws SkyfixException if the force cannot be evaluated
     */
    protected abstract double netVerticalForceN(AtmosphericState air, double altitudeM,
                                                double verticalRateMs) throws SkyfixException;

    /** @return the mass being accelerated, kg */
    protected abstract double massKg();

    /**
     * How fast vertical-velocity perturbations are damped at this state, in 1/s.
     *
     * <p>Differentiating the quadratic drag term with respect to velocity gives
     * {@code d(a)/d(vz) = -rho Cd A |vz| / m}, a real negative eigenvalue. Its magnitude is the
     * damping rate returned here, and it is what decides whether a given step size is stable:
     * an explicit integrator needs {@code rate * h} below its stability limit (ADR-13).
     *
     * <p>The rate grows as the vehicle falls into denser air, so descent is the stiff part of the
     * flight and the reason the default step is what it is.
     *
     * @param air            the atmospheric state at the current altitude
     * @param verticalRateMs vertical rate, m/s
     * @return the damping rate in 1/s, never negative
     * @throws SkyfixException if the rate cannot be evaluated
     */
    public abstract double dampingRatePerSecond(AtmosphericState air, double verticalRateMs)
            throws SkyfixException;

    /**
     * The envelope or canopy diameter at the current atmospheric state, in metres.
     *
     * @param air the atmospheric state at the current altitude
     * @return the diameter in metres
     * @throws SkyfixException if the diameter cannot be evaluated
     */
    public abstract double diameterM(AtmosphericState air) throws SkyfixException;

    /** @return which phase this is, for the stored state record */
    public abstract Phase phase();

    /**
     * Whether this phase has ended and the simulator should hand over to the next one.
     *
     * @param air       the atmospheric state at the current altitude
     * @param altitudeM geometric altitude above MSL, metres
     * @param groundElevationM ground elevation above MSL, metres
     * @return {@code true} if the phase is complete
     * @throws SkyfixException if the test cannot be evaluated
     */
    public abstract boolean isComplete(AtmosphericState air, double altitudeM,
                                       double groundElevationM) throws SkyfixException;

    /**
     * Whether any wind query since the last {@link #clearWindExtrapolated()} fell outside the
     * wind field's known height range (FR-2.2).
     *
     * @return {@code true} if the wind was extrapolated
     */
    public final boolean windExtrapolated() {
        return lastWindExtrapolated;
    }

    /** Resets the extrapolation flag, so the simulator can attribute it to a single step. */
    public final void clearWindExtrapolated() {
        lastWindExtrapolated = false;
    }

    /**
     * The drag force magnitude with the sign that opposes motion.
     *
     * @param densityKgM3    air density, kg/m^3
     * @param dragCoefficient drag coefficient, dimensionless
     * @param areaM2         reference area, m^2
     * @param verticalRateMs vertical rate, m/s positive upwards
     * @return the drag contribution to the net upward force, newtons
     */
    protected static double dragN(double densityKgM3, double dragCoefficient, double areaM2,
                                  double verticalRateMs) {
        return -0.5 * densityKgM3 * dragCoefficient * areaM2
                * verticalRateMs * Math.abs(verticalRateMs);
    }

    /** @return standard gravity, m/s^2 */
    protected static double gravity() {
        return Units.STANDARD_GRAVITY;
    }
}
