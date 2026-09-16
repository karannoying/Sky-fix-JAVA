package com.skyfix.core.atmos;

import com.skyfix.domain.Units;
import com.skyfix.domain.error.ModelDomainException;

/**
 * The U.S. Standard Atmosphere 1976, hand-written, valid from -5 km to 86 km geometric (ADR-1).
 *
 * <p>The model is seven piecewise layers in <em>geopotential</em> height. Within a layer the
 * temperature is linear in geopotential height and the pressure follows from hydrostatic balance
 * with a constant-composition ideal gas:
 *
 * <pre>
 *   T(H)  = T_b + L_b (H - H_b)
 *   p(H)  = p_b (T_b / T(H))^(g0 M / (R* L_b))      for L_b != 0
 *   p(H)  = p_b exp(-g0 M (H - H_b) / (R* T_b))     for L_b == 0
 *   rho   = p M / (R* T)
 * </pre>
 *
 * <p>Geometric altitude {@code h} converts to geopotential height {@code H = r h / (r + h)} with
 * the standard's effective earth radius {@code r = 6356766 m}. That conversion is the reason a
 * query at 11 km geometric returns 216.77 K rather than the 216.65 K quoted at the 11 km layer
 * base, which is a geopotential height.
 *
 * <p>Only the layer <em>base temperatures</em> and <em>lapse rates</em> are transcribed; every base
 * pressure is computed by recursion from sea level, so the table cannot be internally inconsistent.
 * Validated by T-V1 against DS-3 at 25 altitudes to 0.1% relative on T, p and rho.
 *
 * <p>Above 86 km the standard switches to a diffusive, composition-varying regime this model does
 * not implement, so queries above the ceiling raise {@link ModelDomainException} rather than
 * extrapolate (ADR-1 consequence).
 */
public final class Ussa1976Atmosphere implements AtmosphereModel {

    /** Effective earth radius used by the standard for the geopotential conversion, metres. */
    public static final double EFFECTIVE_EARTH_RADIUS_M = 6_356_766.0;

    /** Mean molar mass of air below the turbopause, kg/mol. */
    public static final double MOLAR_MASS_AIR = 28.9644e-3;

    /** Sea-level pressure, Pa. */
    public static final double SEA_LEVEL_PRESSURE_PA = 101_325.0;

    /** Ratio of specific heats for air, dimensionless. */
    public static final double GAMMA = 1.4;

    /** Specific gas constant for air, R* / M, J/(kg K). */
    public static final double SPECIFIC_GAS_CONSTANT =
            Units.UNIVERSAL_GAS_CONSTANT / MOLAR_MASS_AIR;

    /** Highest geometric altitude the model is valid at, metres. */
    public static final double CEILING_M = 86_000.0;

    /** Lowest geometric altitude the model is valid at, metres. */
    public static final double FLOOR_M = -5_000.0;

    /**
     * Layer bases in geopotential height, metres: {base height, base temperature K,
     * lapse rate K/m}. The final entry is the top of the seventh layer and carries no lapse rate
     * of its own.
     */
    private static final double[][] LAYERS = {
            {     0.0, 288.15, -0.0065},
            { 11_000.0, 216.65,  0.0},
            { 20_000.0, 216.65,  0.001},
            { 32_000.0, 228.65,  0.0028},
            { 47_000.0, 270.65,  0.0},
            { 51_000.0, 270.65, -0.0028},
            { 71_000.0, 214.65, -0.002},
            { 84_852.0, 186.946, 0.0},
    };

    /** Base pressure of each layer, Pa — computed once by recursion, never transcribed. */
    private static final double[] BASE_PRESSURE = computeBasePressures();

    /** Geopotential height of the model floor, metres. */
    private static final double FLOOR_H = toGeopotentialM(FLOOR_M);

    @Override
    public AtmosphericState stateAt(double altitudeM) throws ModelDomainException {
        if (Double.isNaN(altitudeM) || altitudeM < FLOOR_M || altitudeM > CEILING_M) {
            throw ModelDomainException.outOfRange(
                    "geometric altitude", altitudeM, FLOOR_M, CEILING_M, "m");
        }
        double h = toGeopotentialM(altitudeM);
        int layer = layerIndexFor(h);

        double baseH = LAYERS[layer][0];
        double baseT = LAYERS[layer][1];
        double lapse = LAYERS[layer][2];
        double baseP = BASE_PRESSURE[layer];

        double temperature = baseT + lapse * (h - baseH);
        double pressure = pressureIn(baseP, baseT, lapse, h - baseH);
        double density = pressure / (SPECIFIC_GAS_CONSTANT * temperature);
        double speedOfSound = Math.sqrt(GAMMA * SPECIFIC_GAS_CONSTANT * temperature);

        return new AtmosphericState(temperature, pressure, density, speedOfSound);
    }

    @Override
    public double minimumAltitudeM() {
        return FLOOR_M;
    }

    @Override
    public double maximumAltitudeM() {
        return CEILING_M;
    }

    @Override
    public String name() {
        return "USSA-1976";
    }

    /**
     * Converts geometric altitude to the geopotential height the layer table is indexed by.
     *
     * @param altitudeM geometric altitude above MSL, metres
     * @return geopotential height, metres
     */
    public static double toGeopotentialM(double altitudeM) {
        return EFFECTIVE_EARTH_RADIUS_M * altitudeM / (EFFECTIVE_EARTH_RADIUS_M + altitudeM);
    }

    /**
     * Converts geopotential height back to geometric altitude — the inverse of
     * {@link #toGeopotentialM}. Radiosonde files report geopotential height, so ingest needs
     * this direction (FR-1.1).
     *
     * @param geopotentialM geopotential height, metres
     * @return geometric altitude above MSL, metres
     */
    public static double toGeometricM(double geopotentialM) {
        return EFFECTIVE_EARTH_RADIUS_M * geopotentialM
                / (EFFECTIVE_EARTH_RADIUS_M - geopotentialM);
    }

    /**
     * The geometric altitude at which the model predicts a given pressure — pressure altitude.
     *
     * <p>Telemetry often carries a barometer reading with no GPS fix (FR-1.2), and a barometer
     * measures altitude far more smoothly than GPS does. Inverting the model turns that reading
     * into an altitude.
     *
     * <p>Found by bisection, because pressure falls strictly monotonically with altitude through
     * every layer, so bisection converges to machine precision with nothing to tune and no
     * per-layer inverse to get wrong. It is the same argument as {@code Gaussian.inverseCdf}.
     *
     * <p><strong>This is pressure altitude, not true altitude.</strong> It is what the standard
     * atmosphere would put at that pressure, and on a day whose profile differs from the standard
     * the two are not the same. The estimator is what reconciles them.
     *
     * @param pressurePa the measured pressure, Pa
     * @return the geometric altitude above MSL in metres
     * @throws ModelDomainException if the pressure lies outside the range the model spans
     */
    public double altitudeForPressure(double pressurePa) throws ModelDomainException {
        double atFloor = pressurePa(FLOOR_M);
        double atCeiling = pressurePa(CEILING_M);
        if (Double.isNaN(pressurePa) || pressurePa > atFloor || pressurePa < atCeiling) {
            throw ModelDomainException.outOfRange("pressure", pressurePa, atCeiling, atFloor, "Pa");
        }
        double low = FLOOR_M;
        double high = CEILING_M;
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (low + high);
            if (pressurePa(mid) > pressurePa) {
                low = mid;   // still too low down: pressure there is higher than measured
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    /** @return the number of layers in the model */
    public static int layerCount() {
        return LAYERS.length - 1;
    }

    /**
     * Base pressure of a layer, in Pa — exposed so tests can check the recursion directly.
     *
     * @param layerIndex zero-based layer index, below {@link #layerCount()}
     * @return the base pressure in Pa
     */
    public static double basePressurePa(int layerIndex) {
        return BASE_PRESSURE[layerIndex];
    }

    /**
     * Geopotential height of a layer base, in metres.
     *
     * @param layerIndex zero-based layer index, at most {@link #layerCount()}
     * @return the base geopotential height in metres
     */
    public static double baseGeopotentialM(int layerIndex) {
        return LAYERS[layerIndex][0];
    }

    /** Binary search over the layer bases — the table is small but sorted, so this stays O(log n). */
    private static int layerIndexFor(double geopotentialM) {
        int low = 0;
        int high = LAYERS.length - 2; // the last entry is the ceiling, not a layer of its own
        if (geopotentialM <= LAYERS[0][0]) {
            return 0; // below sea level, extend the troposphere's lapse rate downward
        }
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (LAYERS[mid][0] <= geopotentialM) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private static double pressureIn(double baseP, double baseT, double lapse, double deltaH) {
        double exponent = Units.STANDARD_GRAVITY * MOLAR_MASS_AIR / Units.UNIVERSAL_GAS_CONSTANT;
        if (lapse == 0.0) {
            return baseP * Math.exp(-exponent * deltaH / baseT);
        }
        return baseP * Math.pow(baseT / (baseT + lapse * deltaH), exponent / lapse);
    }

    private static double[] computeBasePressures() {
        double[] p = new double[LAYERS.length];
        p[0] = SEA_LEVEL_PRESSURE_PA;
        for (int i = 1; i < LAYERS.length; i++) {
            double baseH = LAYERS[i - 1][0];
            double baseT = LAYERS[i - 1][1];
            double lapse = LAYERS[i - 1][2];
            p[i] = pressureIn(p[i - 1], baseT, lapse, LAYERS[i][0] - baseH);
        }
        return p;
    }
}
