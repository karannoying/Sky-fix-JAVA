package com.skyfix.app;

import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.flight.DescentPhase;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.core.flight.Rk4Integrator;
import com.skyfix.core.flight.Rkf45Integrator;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.Phase;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.Units;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.io.PlotExporter;
import com.skyfix.persistence.ValidationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the reference-case suite behind {@code run.sh validate} (FR-4.4, UC-4).
 *
 * <p>Every case compares the model against something computed <em>outside</em> it — a published
 * reference table, a closed-form solution, or a second integration scheme — and reports the error
 * against a stated numeric tolerance. The command exits non-zero if any tolerance is breached, so
 * it is usable as a CI gate, and its output is the table the report quotes.
 *
 * <p>The cases implemented at the MVP cut-line are T-V1 to T-V4. T-V5 to T-V7 need the ensemble
 * and the estimator and report as not-yet-implemented rather than silently passing — a validation
 * suite that quietly omits its hardest cases is worse than one that admits the gap.
 */
public final class ValidationService {

    private static final Path REFERENCE = Path.of("data", "reference", "ussa1976.csv");

    private final Ussa1976Atmosphere atmosphere = new Ussa1976Atmosphere();

    /**
     * Runs every implemented reference case.
     *
     * @return the results, in case order
     * @throws SkyfixException if a case cannot be evaluated at all
     */
    public List<ValidationResult> runAll() throws SkyfixException {
        List<ValidationResult> results = new ArrayList<>();
        results.addAll(caseV1());
        results.addAll(caseV2());
        results.addAll(caseV3());
        results.addAll(caseV4());
        return results;
    }

    /** @return the validation cases that are specified but not yet implemented */
    public List<String> notYetImplemented() {
        return List.of(
                "T-V5 parameter recovery over 20 synthetic flights - needs the particle filter "
                        + "(FR-3.2), due W8",
                "T-V6 live-vs-frozen landing error reduction - needs the estimator (FR-3.3), "
                        + "due W9",
                "T-V7 95% ellipse empirical containment - needs the ensemble (FR-2.4), due W5");
    }

    /**
     * T-V1 — atmosphere against the DS-3 reference table at 25 altitudes, 0.1% relative.
     *
     * @return one result per quantity per altitude
     * @throws SkyfixException if the reference file is missing or the model refuses an altitude
     */
    public List<ValidationResult> caseV1() throws SkyfixException {
        List<ValidationResult> results = new ArrayList<>();
        for (String line : readReference()) {
            String[] f = line.split(",");
            double altitude = Double.parseDouble(f[0]);
            AtmosphericState s = atmosphere.stateAt(altitude);
            String at = String.format("%.0f m", altitude);
            results.add(ValidationResult.relative("T-V1", "temperature @ " + at,
                    s.temperatureK(), Double.parseDouble(f[1]), "K", 0.001));
            results.add(ValidationResult.relative("T-V1", "pressure @ " + at,
                    s.pressurePa(), Double.parseDouble(f[2]), "Pa", 0.001));
            results.add(ValidationResult.relative("T-V1", "density @ " + at,
                    s.densityKgM3(), Double.parseDouble(f[3]), "kg/m3", 0.001));
        }
        return results;
    }

    /**
     * T-V2 — simulated ascent rate against the analytic terminal velocity at 5 altitudes, 2%.
     *
     * @return one result per altitude
     * @throws SkyfixException if the reference flight cannot be integrated
     */
    public List<ValidationResult> caseV2() throws SkyfixException {
        BalloonConfig config = referenceConfig();
        FlightParameters parameters = FlightParameters.nominal(config);
        StateHistory history = new FlightSimulator(atmosphere, ConstantWindField.calm())
                .run(config, parameters, referenceSettings("RK4", 0.25, 1), referenceLaunch());
        double gasMass = FlightSimulator.gasMassFor(config, parameters,
                atmosphere.stateAt(referenceLaunch().altitudeM()));

        List<ValidationResult> results = new ArrayList<>();
        for (double target : new double[]{2_000, 8_000, 14_000, 20_000, 26_000}) {
            BalloonState state = firstAscentAbove(history, target);
            AtmosphericState air = atmosphere.stateAt(state.altitudeM());

            // Terminal velocity where buoyancy, weight and drag balance:
            //   v = sqrt( 2 g (rho V - m) / (rho Cd A) )
            double volume = gasMass * config.gas().specificGasConstant() * air.temperatureK()
                    / air.pressurePa();
            double area = BalloonConfig.frontalAreaOfDiameter(
                    BalloonConfig.diameterOfVolume(volume));
            double analytic = Math.sqrt(2.0 * Units.STANDARD_GRAVITY
                    * (air.densityKgM3() * volume - (config.dryMassKg() + gasMass))
                    / (air.densityKgM3() * parameters.ascentCd() * area));

            results.add(ValidationResult.relative("T-V2",
                    String.format("ascent rate @ %.0f m", state.altitudeM()),
                    state.verticalRateMs(), analytic, "m/s", 0.02));
        }
        return results;
    }

    /**
     * T-V3 — RK4 against RKF45 on the same flight, 50 m of landing separation.
     *
     * @return one result per step size tested
     * @throws SkyfixException if either integration fails
     */
    public List<ValidationResult> caseV3() throws SkyfixException {
        BalloonConfig config = referenceConfig();
        ConstantWindField wind = new ConstantWindField(12.0, -4.0);

        List<ValidationResult> results = new ArrayList<>();
        for (double step : new double[]{0.5, 0.25, 0.125}) {
            GeoPoint rk4 = new FlightSimulator(atmosphere, wind)
                    .run(config, referenceSettings("RK4", step, 1), referenceLaunch())
                    .landingPoint().orElseThrow();
            GeoPoint rkf45 = new FlightSimulator(atmosphere, wind)
                    .run(config, referenceSettings("RKF45", step, 1), referenceLaunch())
                    .landingPoint().orElseThrow();
            results.add(ValidationResult.absolute("T-V3",
                    String.format("RK4 vs RKF45 landing @ dt=%.3f s", step),
                    Geodesy.haversineMetres(rk4, rkf45), 0.0, "m", 50.0));
        }
        return results;
    }

    /**
     * T-V4 — lifting-gas mass recovered from each stored diameter, 1e-6 relative drift.
     *
     * @return a single result carrying the worst drift over the flight
     * @throws SkyfixException if the reference flight cannot be integrated
     */
    public List<ValidationResult> caseV4() throws SkyfixException {
        BalloonConfig config = referenceConfig();
        FlightParameters parameters = FlightParameters.nominal(config);
        StateHistory history = new FlightSimulator(atmosphere, new ConstantWindField(8.0, 3.0))
                .run(config, parameters, referenceSettings("RK4", 0.25, 1), referenceLaunch());
        double sealedIn = FlightSimulator.gasMassFor(config, parameters,
                atmosphere.stateAt(referenceLaunch().altitudeM()));

        double worst = 0.0;
        for (BalloonState s : history) {
            if (s.phase() != Phase.ASCENT) {
                continue;
            }
            AtmosphericState air = atmosphere.stateAt(s.altitudeM());
            double recovered = BalloonConfig.volumeOfDiameter(s.diameterM())
                    * config.gas().densityAt(air.pressurePa(), air.temperatureK());
            worst = Math.max(worst, Math.abs(recovered - sealedIn) / sealedIn);
        }
        return List.of(ValidationResult.absolute("T-V4",
                "gas-law mass invariant, worst relative drift", worst, 0.0, "1", 1e-6));
    }

    /**
     * Writes PL-6, the atmosphere residual chart, from the same rows T-V1 checks.
     *
     * <p>T-V1 reports one worst-case figure; the chart shows how the error behaves with altitude,
     * which is what says whether the model is uniformly good or only good on average.
     *
     * @param outputDir where to write the chart
     * @return the file written
     * @throws SkyfixException if the reference table cannot be read or the chart cannot be written
     */
    public Path exportResidualPlot(Path outputDir) throws SkyfixException {
        List<double[]> rows = new ArrayList<>();
        for (String line : readReference()) {
            String[] f = line.split(",");
            rows.add(new double[]{Double.parseDouble(f[0]), Double.parseDouble(f[1]),
                    Double.parseDouble(f[2]), Double.parseDouble(f[3])});
        }
        return PlotExporter.exportAtmosphereResidual(outputDir, atmosphere, rows, 0.001);
    }

    /**
     * The descent rate a recovery team would see at touchdown, for the console summary.
     *
     * @param config the balloon configuration
     * @return terminal descent rate at sea-level density, m/s, negative downwards
     * @throws SkyfixException if the atmosphere cannot be queried
     */
    public double touchdownRateMs(BalloonConfig config) throws SkyfixException {
        return new DescentPhase(atmosphere, ConstantWindField.calm(), config,
                FlightParameters.nominal(config))
                .terminalRateMs(atmosphere.densityKgM3(0.0));
    }

    /**
     * The reference balloon every validation case flies — a HabSat-class 1200 g latex sounding.
     *
     * @return the reference configuration
     * @throws SkyfixException if the reference configuration is somehow invalid
     */
    public static BalloonConfig referenceConfig() throws SkyfixException {
        return BalloonConfig.builder()
                .name("validation-reference-1200g")
                .payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4)
                .gas(LiftGas.HELIUM)
                .build();
    }

    /**
     * The launch site every validation case flies from.
     *
     * @return the reference launch position
     */
    public static GeoPoint referenceLaunch() {
        return new GeoPoint(23.2599, 77.4126, 500.0);
    }

    /**
     * Settings for a validation flight.
     *
     * @param integrator  integration scheme
     * @param stepSeconds integration step, seconds
     * @param stride      how many steps between retained states; 1 keeps every state, which the
     *                    cases that inspect the trajectory need
     */
    private static SimSettings referenceSettings(String integrator, double stepSeconds, int stride)
            throws SkyfixException {
        return SimSettings.builder()
                .integrator(integrator)
                .stepSeconds(stepSeconds)
                .groundElevationM(referenceLaunch().altitudeM())
                .stateSampleStride(stride)
                .build();
    }

    private static BalloonState firstAscentAbove(StateHistory history, double altitudeM)
            throws SkyfixException {
        for (BalloonState s : history) {
            if (s.phase() == Phase.ASCENT && s.altitudeM() >= altitudeM) {
                return s;
            }
        }
        throw new com.skyfix.domain.error.ConvergenceException(
                "the reference flight never reached " + altitudeM + " m");
    }

    private static List<String> readReference() throws SkyfixException {
        try {
            List<String> rows = new ArrayList<>();
            for (String line : Files.readAllLines(REFERENCE, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("altitude_m")) {
                    rows.add(t);
                }
            }
            if (rows.isEmpty()) {
                throw new com.skyfix.domain.error.DataFormatException(REFERENCE.toString(), 0,
                        "reference table is empty; T-V1 cannot be evaluated");
            }
            return rows;
        } catch (IOException e) {
            throw new com.skyfix.domain.error.DataFormatException(REFERENCE.toString(), 0,
                    "reference table cannot be read: " + e.getMessage(), e);
        }
    }
}
