package com.skyfix.core.atmos;

import com.skyfix.domain.error.ModelDomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-V1 and T-U-ATMOS — the first validation gate (FR-2.1, NFR-2).
 *
 * <p>T-V1 compares this implementation against DS-3, {@code data/reference/ussa1976.csv}, at 25
 * altitudes from 0 to 47 km and requires 0.1% relative agreement on temperature, pressure and
 * density. The reference file is an external oracle, not a recording of this model's own output;
 * its header documents where the values came from and what still has to be verified.
 */
class Ussa1976AtmosphereTest {

    private static final Path REFERENCE = Path.of("data", "reference", "ussa1976.csv");
    private static final double TOLERANCE = 0.001; // 0.1 % relative, per T-V1

    private final Ussa1976Atmosphere atmosphere = new Ussa1976Atmosphere();

    private record Row(double altitudeM, double temperatureK, double pressurePa,
                       double densityKgM3) {
    }

    private static List<Row> reference() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(REFERENCE, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("altitude_m")) {
                continue;
            }
            String[] f = t.split(",");
            rows.add(new Row(Double.parseDouble(f[0]), Double.parseDouble(f[1]),
                    Double.parseDouble(f[2]), Double.parseDouble(f[3])));
        }
        return rows;
    }

    @Test
    @DisplayName("T-V1: the reference file really does carry 25 rows spanning 0-47 km")
    void referenceFileIsTheOneTheBlueprintSpecifies() throws Exception {
        List<Row> rows = reference();
        assertThat(rows).as("DS-3 must hold 25 altitudes").hasSize(25);
        assertThat(rows.get(0).altitudeM()).isEqualTo(0.0);
        assertThat(rows.get(rows.size() - 1).altitudeM()).isEqualTo(47_000.0);
        // A test that silently passed on an empty or truncated oracle would be worthless.
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.temperatureK()).isBetween(150.0, 340.0);
            assertThat(r.pressurePa()).isPositive();
            assertThat(r.densityKgM3()).isPositive();
        });
    }

    @Test
    @DisplayName("T-V1: T, p and rho are within 0.1 % of DS-3 at 25 altitudes")
    void matchesPublishedReferenceWithinOnePerMille() throws Exception {
        double worstT = 0, worstP = 0, worstRho = 0;
        double worstAlt = 0;

        for (Row r : reference()) {
            AtmosphericState s = atmosphere.stateAt(r.altitudeM());

            double dt = relative(s.temperatureK(), r.temperatureK());
            double dp = relative(s.pressurePa(), r.pressurePa());
            double drho = relative(s.densityKgM3(), r.densityKgM3());

            assertThat(dt).as("temperature at %.0f m", r.altitudeM()).isLessThan(TOLERANCE);
            assertThat(dp).as("pressure at %.0f m", r.altitudeM()).isLessThan(TOLERANCE);
            assertThat(drho).as("density at %.0f m", r.altitudeM()).isLessThan(TOLERANCE);

            if (Math.max(dt, Math.max(dp, drho)) > Math.max(worstT, Math.max(worstP, worstRho))) {
                worstAlt = r.altitudeM();
            }
            worstT = Math.max(worstT, dt);
            worstP = Math.max(worstP, dp);
            worstRho = Math.max(worstRho, drho);
        }

        System.out.printf("T-V1 worst relative error: T %.5f %%, p %.5f %%, rho %.5f %% "
                        + "(worst altitude %.0f m; tolerance 0.1 %%)%n",
                worstT * 100, worstP * 100, worstRho * 100, worstAlt);
    }

    @Test
    @DisplayName("T-U-ATMOS: T and p are continuous across every layer boundary")
    void temperatureIsContinuousAcrossLayerBoundaries() throws Exception {
        // Crossing a boundary, the only change permitted is the one physics demands: temperature
        // moves by the lapse rate over the step, pressure by the hydrostatic gradient rho*g*dh.
        // Anything beyond that is a discontinuity in the piecewise table. Comparing against those
        // gradients, rather than against a fixed tolerance, is what makes this a continuity test:
        // a fixed epsilon would either pass a real jump low down or fail on the true gradient at
        // 11 km, which is 1.6e-4 per metre.
        final double dh = 0.5;
        for (int i = 1; i < Ussa1976Atmosphere.layerCount(); i++) {
            double boundary = Ussa1976Atmosphere.toGeometricM(
                    Ussa1976Atmosphere.baseGeopotentialM(i));

            AtmosphericState below = atmosphere.stateAt(boundary - dh);
            AtmosphericState above = atmosphere.stateAt(boundary + dh);

            // Temperature: at most the steeper of the two adjoining lapse rates over 2*dh.
            double maxLapse = 0.0065; // K/m, the largest magnitude in the table
            assertThat(Math.abs(above.temperatureK() - below.temperatureK()))
                    .as("temperature jump across layer %d base", i)
                    .isLessThanOrEqualTo(maxLapse * 2 * dh + 1e-9);

            // Pressure: exactly the hydrostatic drop and nothing more. The standard's hydrostatic
            // relation is dp = -rho g0 dH in GEOPOTENTIAL height, so the step is measured there
            // (a 1 % correction by 32 km), and the mean of the two densities is used because the
            // density itself changes across the step.
            double dH = Ussa1976Atmosphere.toGeopotentialM(boundary + dh)
                    - Ussa1976Atmosphere.toGeopotentialM(boundary - dh);
            double meanDensity = 0.5 * (below.densityKgM3() + above.densityKgM3());
            double hydrostatic = meanDensity * com.skyfix.domain.Units.STANDARD_GRAVITY * dH;
            assertThat(below.pressurePa() - above.pressurePa())
                    .as("pressure jump across layer %d base", i)
                    .isCloseTo(hydrostatic, within(1e-4 * hydrostatic));
        }
    }

    @Test
    @DisplayName("T-U-ATMOS: pressure and density fall monotonically all the way to the ceiling")
    void pressureAndDensityDecreaseMonotonically() throws Exception {
        double previousP = Double.MAX_VALUE;
        double previousRho = Double.MAX_VALUE;
        for (double h = 0; h <= Ussa1976Atmosphere.CEILING_M; h += 250.0) {
            AtmosphericState s = atmosphere.stateAt(h);
            assertThat(s.pressurePa()).as("pressure at %.0f m", h).isLessThan(previousP);
            assertThat(s.densityKgM3()).as("density at %.0f m", h).isLessThan(previousRho);
            assertThat(s.temperatureK()).isBetween(180.0, 300.0);
            assertThat(s.speedOfSoundMs()).isBetween(250.0, 350.0);
            previousP = s.pressurePa();
            previousRho = s.densityKgM3();
        }
    }

    @Test
    @DisplayName("FR-2.1: above the 86 km ceiling the model refuses rather than extrapolating")
    void aboveCeilingRaisesModelDomainException() {
        assertThatThrownBy(() -> atmosphere.stateAt(86_000.01))
                .isInstanceOf(ModelDomainException.class)
                .hasMessageContaining("86000");
        assertThatThrownBy(() -> atmosphere.stateAt(120_000.0))
                .isInstanceOfSatisfying(ModelDomainException.class,
                        e -> assertThat(e.exitCode()).isEqualTo(4));
        assertThatThrownBy(() -> atmosphere.stateAt(-5_000.01))
                .isInstanceOf(ModelDomainException.class);
        assertThatThrownBy(() -> atmosphere.stateAt(Double.NaN))
                .isInstanceOf(ModelDomainException.class);
    }

    @Test
    @DisplayName("The geopotential conversion round-trips and matches the standard's definition")
    void geopotentialConversionRoundTrips() {
        for (double h : new double[]{0, 1_000, 11_000, 30_000, 47_000, 86_000}) {
            double geopotential = Ussa1976Atmosphere.toGeopotentialM(h);
            assertThat(Ussa1976Atmosphere.toGeometricM(geopotential)).isEqualTo(h, within(1e-6));
            // Geopotential height is always at or below geometric altitude, and equal only at MSL.
            assertThat(geopotential).isLessThanOrEqualTo(h);
        }
        assertThat(Ussa1976Atmosphere.toGeopotentialM(0.0)).isEqualTo(0.0);
        // At 11 km geometric the geopotential height is ~19 m lower, which is exactly why the
        // reference table reads 216.77 K there and not the 216.65 K of the layer base.
        assertThat(Ussa1976Atmosphere.toGeopotentialM(11_000.0)).isCloseTo(10_981.0, within(1.0));
    }

    @Test
    @DisplayName("Base pressures are recursed from sea level, not transcribed")
    void basePressuresFollowFromTheRecursion() throws Exception {
        // Each layer base pressure must equal what a query just below that base returns.
        assertThat(Ussa1976Atmosphere.basePressurePa(0))
                .isEqualTo(Ussa1976Atmosphere.SEA_LEVEL_PRESSURE_PA);
        for (int i = 1; i < Ussa1976Atmosphere.layerCount(); i++) {
            double geometric = Ussa1976Atmosphere.toGeometricM(
                    Ussa1976Atmosphere.baseGeopotentialM(i));
            assertThat(relative(atmosphere.pressurePa(geometric),
                    Ussa1976Atmosphere.basePressurePa(i)))
                    .as("layer %d base pressure", i).isLessThan(1e-9);
        }
    }

    @Test
    @DisplayName("The convenience accessors agree with the full state query")
    void convenienceAccessorsAgree() throws Exception {
        AtmosphericState s = atmosphere.stateAt(18_000.0);
        assertThat(atmosphere.temperatureK(18_000.0)).isEqualTo(s.temperatureK());
        assertThat(atmosphere.pressurePa(18_000.0)).isEqualTo(s.pressurePa());
        assertThat(atmosphere.densityKgM3(18_000.0)).isEqualTo(s.densityKgM3());
        assertThat(atmosphere.name()).isEqualTo("USSA-1976");
        assertThat(atmosphere.minimumAltitudeM()).isEqualTo(-5_000.0);
        assertThat(atmosphere.maximumAltitudeM()).isEqualTo(86_000.0);
    }

    @Test
    @DisplayName("The ideal gas law closes on the returned triple at every altitude")
    void idealGasLawClosesOnTheReturnedTriple() throws Exception {
        for (double h = 0; h <= 86_000; h += 1_000) {
            AtmosphericState s = atmosphere.stateAt(h);
            double impliedPressure = s.densityKgM3()
                    * Ussa1976Atmosphere.SPECIFIC_GAS_CONSTANT * s.temperatureK();
            assertThat(relative(impliedPressure, s.pressurePa()))
                    .as("p = rho R T at %.0f m", h).isLessThan(1e-12);
        }
    }

    @Test
    @DisplayName("FR-1.2: pressure altitude inverts the model exactly, across the whole range")
    void pressureAltitudeInvertsTheModel() throws Exception {
        for (double h = -4_000; h <= 85_000; h += 250) {
            double pressure = atmosphere.pressurePa(h);
            assertThat(atmosphere.altitudeForPressure(pressure))
                    .as("round trip at %.0f m", h)
                    .isEqualTo(h, within(1e-6));
        }
    }

    @Test
    @DisplayName("FR-1.2: pressure altitude agrees with the reference table independently")
    void pressureAltitudeAgreesWithTheReferenceTable() throws Exception {
        // Feeding the DS-3 pressures back in must recover the DS-3 altitudes. That checks the
        // inversion against the published table rather than only against the forward model.
        for (Row r : reference()) {
            assertThat(atmosphere.altitudeForPressure(r.pressurePa()))
                    .as("pressure altitude at %.0f m", r.altitudeM())
                    .isCloseTo(r.altitudeM(), within(15.0));
        }
    }

    @Test
    @DisplayName("FR-1.2: a pressure outside the model's range is refused, not extrapolated")
    void pressureOutsideTheRangeIsRefused() {
        assertThatThrownBy(() -> atmosphere.altitudeForPressure(200_000.0))
                .isInstanceOf(ModelDomainException.class)
                .hasMessageContaining("pressure");
        assertThatThrownBy(() -> atmosphere.altitudeForPressure(0.0))
                .isInstanceOf(ModelDomainException.class);
        assertThatThrownBy(() -> atmosphere.altitudeForPressure(Double.NaN))
                .isInstanceOf(ModelDomainException.class);
    }

    private static double relative(double actual, double expected) {
        return Math.abs(actual - expected) / Math.abs(expected);
    }
}
