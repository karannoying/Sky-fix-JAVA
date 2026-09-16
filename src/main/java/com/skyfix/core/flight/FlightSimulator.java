package com.skyfix.core.flight;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.atmos.WindField;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Phase;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.ConvergenceException;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Integrates one flight from launch to landing (FR-2.3).
 *
 * <p>The loop is deliberately small: ask the current {@link FlightPhase} for its derivative, take
 * one integrator step, ask the phase whether it is finished, and swap phases if so. All the
 * physics that differs between ascent and descent lives in the phase objects, so burst is a phase
 * swap here rather than a branch.
 *
 * <p>Stateless between calls and safe to share across ensemble threads: every call allocates its
 * own integrator and phase objects (ADR-5).
 */
public final class FlightSimulator {

    private static final Logger LOG = Logger.getLogger(FlightSimulator.class.getName());

    /** Below this speed at ground contact the landing is treated as reached, m/s. */
    private static final double GROUND_EPSILON_M = 1e-6;

    /** Flight times closer together than this are the same instant, seconds. */
    private static final double TIME_EPSILON_S = 1e-9;

    private final AtmosphereModel atmosphere;
    private final WindField windField;

    /**
     * @param atmosphere the atmosphere model every phase queries
     * @param windField  the wind field every phase is advected by
     */
    public FlightSimulator(AtmosphereModel atmosphere, WindField windField) {
        this.atmosphere = atmosphere;
        this.windField = windField;
    }

    /**
     * Runs a complete flight from the launch point, at rest, under the nominal parameters.
     *
     * @param config   the balloon configuration
     * @param settings integration settings
     * @param launch   launch position; its altitude is the release altitude
     * @return the flight history, including the landing point
     * @throws SkyfixException if the configuration is unflyable, a model is queried out of range,
     *                         or the flight does not land inside the time ceiling
     */
    public StateHistory run(BalloonConfig config, SimSettings settings, GeoPoint launch)
            throws SkyfixException {
        return run(config, FlightParameters.nominal(config), settings, launch);
    }

    /**
     * Runs a complete flight from the launch point, at rest, under given parameters.
     *
     * <p>This overload is what the ensemble calls: each member supplies its own dispersed
     * {@link FlightParameters} against one shared, immutable configuration.
     *
     * @param config     the balloon configuration
     * @param parameters the dispersed or estimated parameters for this member
     * @param settings   integration settings
     * @param launch     launch position; its altitude is the release altitude
     * @return the flight history, including the landing point
     * @throws SkyfixException if the configuration is unflyable, a model is queried out of range,
     *                         or the flight does not land inside the time ceiling
     */
    public StateHistory run(BalloonConfig config, FlightParameters parameters,
                            SimSettings settings, GeoPoint launch) throws SkyfixException {
        AtmosphericState launchAir = atmosphere.stateAt(launch.altitudeM());
        double gasMass = gasMassFor(config, parameters, launchAir);

        double[] y = {launch.latitudeDeg(), launch.longitudeDeg(), launch.altitudeM(), 0.0};
        FlightPhase phase = new AscentPhase(atmosphere, windField, config, parameters, gasMass);
        return integrate(config, parameters, settings, phase, 0.0, y);
    }

    /**
     * Re-predicts the rest of a flight from a measured state (FR-3.3).
     *
     * <p>Same integration, different starting point: the state comes from telemetry rather than
     * from a launch assumption, and the phase is taken from the state rather than assumed to be
     * ascent. This is the overload the in-flight re-prediction calls once the filter has a
     * posterior.
     *
     * @param config     the balloon configuration
     * @param parameters the estimated parameters
     * @param settings   integration settings
     * @param from       the measured state to continue from
     * @return the flight history from {@code from} to landing
     * @throws SkyfixException if a model is queried out of range, or the flight does not land
     *                         inside the time ceiling
     */
    public StateHistory runFrom(BalloonConfig config, FlightParameters parameters,
                                SimSettings settings, BalloonState from) throws SkyfixException {
        return integrate(config, parameters, settings, phaseFor(config, parameters, from),
                from.timeSeconds(), stateVector(from));
    }

    /**
     * Advances one state forward to a given flight time without building a history (FR-3.2).
     *
     * <p>This is what the particle filter propagates with. The alternative — re-simulating every
     * particle from launch at every telemetry sample — is quadratic in the log length and would
     * put a 500-particle filter over an 8,000-sample log at four billion integration steps. Here
     * each particle carries its own state forward by one observation interval, so the whole
     * replay costs one flight per particle.
     *
     * <p>The step is truncated at the target time rather than overshooting it, so the returned
     * state sits exactly on the observation epoch and the likelihood compares like with like. That
     * makes the step sequence depend on the observation spacing, which is a deliberate trade: a
     * truncated final step is a smaller step, and a smaller step is never less stable.
     *
     * <p>The returned state carries everything the next call needs — phase, diameter and rate — so
     * a particle is just a parameter set and a {@link BalloonState}, with no separate integrator
     * state to keep in step.
     *
     * @param config            the balloon configuration
     * @param parameters        this particle's parameters
     * @param settings          integration settings
     * @param from              the state to advance from
     * @param targetTimeSeconds the flight time to advance to, seconds since launch
     * @return the state at {@code targetTimeSeconds}, or the landed state if it reaches the ground
     *         first; {@code from} unchanged if it has already landed or is already at or past the
     *         target
     * @throws SkyfixException if a model is queried out of range or the step is unstable
     */
    public BalloonState advanceTo(BalloonConfig config, FlightParameters parameters,
                                  SimSettings settings, BalloonState from,
                                  double targetTimeSeconds) throws SkyfixException {
        if (from.phase() == Phase.LANDED || targetTimeSeconds - from.timeSeconds() <= TIME_EPSILON_S) {
            return from;
        }
        return advance(config, parameters, settings, phaseFor(config, parameters, from),
                from.timeSeconds(), stateVector(from), targetTimeSeconds, null);
    }

    /**
     * Rebuilds the phase object implied by a state.
     *
     * <p>Ascent needs its sealed-in gas mass back, and the only record of it in a
     * {@link BalloonState} is the envelope diameter — so it is recovered from the diameter and the
     * ambient conditions, which is exact because {@code V = m/rho} inverts {@code m = V rho}. This
     * is shared by {@link #runFrom} and {@link #advanceTo} so the two can never disagree about
     * what a state means.
     */
    private FlightPhase phaseFor(BalloonConfig config, FlightParameters parameters,
                                 BalloonState from) throws SkyfixException {
        if (from.phase() == Phase.DESCENT || from.phase() == Phase.BURST) {
            return new DescentPhase(atmosphere, windField, config, parameters);
        }
        AtmosphericState air = atmosphere.stateAt(from.altitudeM());
        double volume = BalloonConfig.volumeOfDiameter(from.diameterM());
        double gasMass = volume * config.gas().densityAt(air.pressurePa(), air.temperatureK());
        return new AscentPhase(atmosphere, windField, config, parameters, gasMass);
    }

    private static double[] stateVector(BalloonState from) {
        return new double[]{from.latitudeDeg(), from.longitudeDeg(), from.altitudeM(),
                from.verticalRateMs()};
    }

    /** @return the atmosphere model this simulator integrates against */
    public AtmosphereModel atmosphere() {
        return atmosphere;
    }

    /**
     * The at-rest ascent state a flight begins in (FR-3.2).
     *
     * <p>The filter needs this because a particle is a parameter set plus a state, and at launch
     * every particle's state differs: free lift fixes the gas mass, and the gas mass fixes the
     * envelope diameter the balloon leaves the ground at. Building it here rather than in the
     * filter keeps the one definition of "launch" in the one class that integrates from it.
     *
     * @param config     the balloon configuration
     * @param parameters this member's parameters
     * @param launch     the launch position; its altitude is the release altitude
     * @return the state at t = 0, at rest, in {@link Phase#ASCENT}
     * @throws SkyfixException if the gas is not buoyant at the launch site or the atmosphere is
     *                         queried out of range
     */
    public BalloonState launchState(BalloonConfig config, FlightParameters parameters,
                                    GeoPoint launch) throws SkyfixException {
        AtmosphericState air = atmosphere.stateAt(launch.altitudeM());
        double gasMass = gasMassFor(config, parameters, air);
        double diameter = BalloonConfig.diameterOfVolume(
                config.volumeAt(gasMass, air.pressurePa(), air.temperatureK()));
        return new BalloonState(0.0, launch, 0.0, diameter, Phase.ASCENT, false);
    }

    /**
     * The lifting gas mass implied by a member's free lift at the launch atmosphere.
     *
     * @param config     the balloon configuration
     * @param parameters the member's parameters; its free lift overrides the configuration's
     * @param launchAir  the atmospheric state at the launch altitude
     * @return the gas mass in kg
     * @throws ValidationException if the gas is not buoyant at the launch site
     */
    public static double gasMassFor(BalloonConfig config, FlightParameters parameters,
                                    AtmosphericState launchAir) throws ValidationException {
        BalloonConfig effective = config.toBuilder()
                .freeLiftKg(parameters.freeLiftKg())
                .build();
        return effective.gasMassKg(launchAir.densityKgM3(), launchAir.pressurePa(),
                launchAir.temperatureK());
    }

    /**
     * Runs a whole flight to landing and returns its history.
     *
     * @throws ConvergenceException if the flight is still airborne at the time ceiling
     */
    private StateHistory integrate(BalloonConfig config, FlightParameters parameters,
                                   SimSettings settings, FlightPhase initialPhase,
                                   double startTime, double[] initialState)
            throws SkyfixException {

        StateHistory.Builder history = StateHistory.builder();
        BalloonState last = advance(config, parameters, settings, initialPhase, startTime,
                initialState, settings.endSeconds(), history);

        if (last.phase() != Phase.LANDED) {
            throw new ConvergenceException(
                    "flight did not reach the ground within t_end_s (" + settings.endSeconds()
                            + " s); last altitude " + last.altitudeM() + " m")
                    .with("t_end_s", settings.endSeconds())
                    .with("last_altitude_m", last.altitudeM());
        }
        return history.build();
    }

    /**
     * The one integration loop, shared by the whole-flight and single-leg entry points.
     *
     * <p>It advances from {@code startTime} until either the vehicle lands or the clock reaches
     * {@code stopTime}, whichever comes first, and returns the state it stopped at. A landing is
     * reported by the returned state's phase being {@link Phase#LANDED}, which is what lets
     * {@link #advanceTo} tell "still flying at the target time" from "already down" without a
     * second return value.
     *
     * @param history where to record states, burst and landing, or {@code null} to record nothing
     *                — the filter propagates half a million legs and has no use for any of it
     */
    private BalloonState advance(BalloonConfig config, FlightParameters parameters,
                                 SimSettings settings, FlightPhase initialPhase,
                                 double startTime, double[] initialState, double stopTime,
                                 StateHistory.Builder history) throws SkyfixException {

        Integrator integrator = IntegratorFactory.create(settings.integrator());

        FlightPhase phase = initialPhase;
        double[] y = initialState.clone();
        double t = startTime;
        int step = 0;

        double stabilityLimit = integrator.realAxisStabilityLimit();
        AtmosphericState air = atmosphere.stateAt(y[FlightPhase.ALT]);
        if (history != null) {
            history.add(state(t, y, phase.diameterM(air), phase.phase(), false));
        }

        while (stopTime - t > TIME_EPSILON_S) {
            // Truncate the last step so the leg ends exactly on the requested time rather than
            // overshooting it by up to one step.
            double h = Math.min(settings.stepSeconds(), stopTime - t);

            requireStableStep(phase, air, y[FlightPhase.VZ], h, stabilityLimit,
                    integrator.name(), y[FlightPhase.ALT]);
            phase.clearWindExtrapolated();
            double[] next = integrator.step(t, y, h, phase.derivative());
            boolean extrapolated = phase.windExtrapolated();
            t += h;
            step++;
            if (history != null) {
                history.countStep(extrapolated);
            }

            AtmosphericState nextAir = atmosphere.stateAt(next[FlightPhase.ALT]);

            // Ground contact: interpolate to the crossing rather than overshooting by up to one
            // step, which at 1 s and 5 m/s would be a 5 m error in the reported landing altitude.
            if (phase.phase() == Phase.DESCENT
                    && next[FlightPhase.ALT] <= settings.groundElevationM()) {
                BalloonState landed = interpolateToGround(y, next, t - h, h,
                        settings.groundElevationM(), phase.diameterM(nextAir), extrapolated);
                if (history != null) {
                    history.add(landed).landing(landed);
                    LOG.log(Level.INFO, () -> String.format(
                            "landed after %.0f s at %s", landed.timeSeconds(), landed.position()));
                }
                return landed;
            }

            if (phase.isComplete(nextAir, next[FlightPhase.ALT], settings.groundElevationM())) {
                if (phase.phase() == Phase.ASCENT) {
                    BalloonState burst = state(t, next, phase.diameterM(nextAir), Phase.BURST,
                            extrapolated);
                    if (history != null) {
                        history.add(burst).burst(burst);
                        LOG.log(Level.INFO, () -> String.format("burst at %.0f m after %.0f s",
                                burst.altitudeM(), burst.timeSeconds()));
                    }
                    phase = new DescentPhase(atmosphere, windField, config, parameters);
                    y = next;
                    air = nextAir;
                    // Burst is stamped on the state, so a leg ending here hands the next leg a
                    // descending vehicle rather than one that would re-inflate.
                    if (stopTime - t <= TIME_EPSILON_S) {
                        return burst;
                    }
                    continue;
                }
            }

            y = next;
            air = nextAir;
            if (history != null && step % settings.stateSampleStride() == 0) {
                history.add(state(t, y, phase.diameterM(nextAir), phase.phase(), extrapolated));
            }
        }

        return state(t, y, phase.diameterM(air), phase.phase(), false);
    }

    /**
     * Refuses a step the integrator cannot take stably.
     *
     * <p>Quadratic drag linearises to a real negative eigenvalue whose magnitude is the phase's
     * damping rate, and that rate rises as the vehicle descends into denser air. Past the
     * integrator's stability boundary an explicit method oscillates instead of converging, and the
     * landing point it produces is wrong without looking wrong — measured at 39 s of extra flight
     * time, and around 470 m of landing error in a 12 m/s wind, for RK4 at a 1 s step (ADR-13).
     *
     * <p>So the condition is checked rather than assumed, and the message names the largest step
     * that would have worked.
     */
    private static void requireStableStep(FlightPhase phase, AtmosphericState air,
                                          double verticalRateMs, double h, double stabilityLimit,
                                          String integratorName, double altitudeM)
            throws SkyfixException {
        double dampingRate = phase.dampingRatePerSecond(air, verticalRateMs);
        if (dampingRate * h <= stabilityLimit) {
            return;
        }
        double maxStableStep = stabilityLimit / dampingRate;
        throw new ConvergenceException(String.format(
                "step_s (%.4g s) exceeds the stability limit of %s during %s at %.0f m; "
                        + "drag damping is %.3g /s there, so the step must be at most %.3g s",
                h, integratorName, phase.phase(), altitudeM, dampingRate, maxStableStep))
                .with("step_s", h)
                .with("max_stable_step_s", maxStableStep)
                .with("integrator", integratorName)
                .with("altitude_m", altitudeM);
    }

    private static BalloonState interpolateToGround(double[] before, double[] after,
                                                    double timeBefore, double h,
                                                    double groundElevationM, double diameterM,
                                                    boolean windExtrapolated) {
        double altBefore = before[FlightPhase.ALT];
        double altAfter = after[FlightPhase.ALT];
        double span = altBefore - altAfter;
        double fraction = span > GROUND_EPSILON_M ? (altBefore - groundElevationM) / span : 1.0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        double lat = before[FlightPhase.LAT] + fraction * (after[FlightPhase.LAT] - before[FlightPhase.LAT]);
        double lon = before[FlightPhase.LON] + fraction * (after[FlightPhase.LON] - before[FlightPhase.LON]);
        double vz = before[FlightPhase.VZ] + fraction * (after[FlightPhase.VZ] - before[FlightPhase.VZ]);

        return new BalloonState(timeBefore + fraction * h,
                new GeoPoint(lat, com.skyfix.domain.Geodesy.normaliseLongitude(lon),
                        groundElevationM),
                vz, diameterM, Phase.LANDED, windExtrapolated);
    }

    private static BalloonState state(double t, double[] y, double diameterM, Phase phase,
                                      boolean windExtrapolated) {
        return new BalloonState(t,
                new GeoPoint(y[FlightPhase.LAT],
                        com.skyfix.domain.Geodesy.normaliseLongitude(y[FlightPhase.LON]),
                        y[FlightPhase.ALT]),
                y[FlightPhase.VZ], diameterM, phase, windExtrapolated);
    }
}
