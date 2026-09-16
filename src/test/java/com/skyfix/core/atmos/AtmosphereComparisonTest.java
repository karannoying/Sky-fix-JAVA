package com.skyfix.core.atmos;

import com.skyfix.domain.error.ModelDomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * ADR-1 — what the exponential atmosphere actually costs.
 *
 * <p>The blueprint rejects a single-scale-height exponential on the grounds that it is "~10 % off
 * near the tropopause, and that error maps almost linearly into ascent rate". This test measures
 * the figure so the report can quote a number it computed rather than repeat an estimate.
 */
class AtmosphereComparisonTest {

    private final Ussa1976Atmosphere ussa = new Ussa1976Atmosphere();
    // 7.64 km is the scale height that matches USSA density at sea level and at 11 km, so this
    // is the exponential model at its most flattering, not a straw man.
    private final ExponentialAtmosphere exponential = new ExponentialAtmosphere(7_640.0);

    @Test
    @DisplayName("ADR-1: the exponential model's density error is measured across the flight band")
    void exponentialDensityErrorIsMeasured() throws Exception {
        double worst = 0.0;
        double worstAltitude = 0.0;
        for (double h = 0; h <= 35_000; h += 500) {
            double relative = Math.abs(exponential.densityKgM3(h) - ussa.densityKgM3(h))
                    / ussa.densityKgM3(h);
            if (relative > worst) {
                worst = relative;
                worstAltitude = h;
            }
        }
        double atTropopause = Math.abs(exponential.densityKgM3(11_000) - ussa.densityKgM3(11_000))
                / ussa.densityKgM3(11_000);
        System.out.printf("ADR-1 measured: exponential (H = 7.64 km) density differs from "
                        + "USSA-1976 by %.1f %% at the tropopause (11 km) and by up to "
                        + "%.1f %% over 0-35 km (worst at %.0f m)%n",
                atTropopause * 100, worst * 100, worstAltitude);

        // Buoyancy and drag are both linear in density, so this error passes almost directly
        // into ascent rate. It is far too large for the 2 % T-V2 tolerance, which is the whole
        // reason ADR-1 selects USSA-1976.
        assertThat(worst)
                .as("exponential model error, the quantity ADR-1 rejects it for")
                .isGreaterThan(0.05);
    }

    @Test
    @DisplayName("ADR-1: the exponential model is isothermal, so it cannot see the tropopause")
    void exponentialIsIsothermal() throws Exception {
        double t0 = exponential.temperatureK(0);
        assertThat(exponential.temperatureK(20_000)).isEqualTo(t0, within(1e-9));
        // USSA-1976 falls by ~71 K to the tropopause and rises again above it; an isothermal
        // model has no way to represent either.
        assertThat(ussa.temperatureK(11_000)).isLessThan(ussa.temperatureK(0) - 60.0);
        assertThat(ussa.temperatureK(47_000)).isGreaterThan(ussa.temperatureK(20_000) + 40.0);
    }

    @Test
    @DisplayName("Both models agree at sea level, where the exponential is anchored")
    void modelsAgreeAtSeaLevel() throws Exception {
        assertThat(exponential.densityKgM3(0)).isEqualTo(ussa.densityKgM3(0), within(1e-3));
        assertThat(exponential.pressurePa(0)).isEqualTo(ussa.pressurePa(0), within(1.0));
    }

    @Test
    @DisplayName("The exponential model refuses queries outside its range, like any AtmosphereModel")
    void exponentialRespectsItsRange() {
        assertThatThrownBy(() -> exponential.stateAt(-1.0))
                .isInstanceOf(ModelDomainException.class);
        assertThatThrownBy(() -> exponential.stateAt(86_000.01))
                .isInstanceOf(ModelDomainException.class);
        assertThatThrownBy(() -> new ExponentialAtmosphere(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The exponential model describes itself for run records")
    void exponentialMetadata() {
        assertThat(exponential.name()).isEqualTo("EXPONENTIAL");
        assertThat(exponential.scaleHeightM()).isEqualTo(7_640.0);
        assertThat(exponential.minimumAltitudeM()).isEqualTo(0.0);
        assertThat(exponential.maximumAltitudeM()).isEqualTo(86_000.0);
    }

    @Test
    @DisplayName("Swapping the model is a one-line change: both satisfy the same interface")
    void bothSatisfyTheSameStrategyInterface() throws Exception {
        for (AtmosphereModel model : new AtmosphereModel[]{ussa, exponential}) {
            AtmosphericState s = model.stateAt(10_000.0);
            assertThat(s.densityKgM3()).isPositive();
            assertThat(s.pressurePa()).isPositive();
            assertThat(s.temperatureK()).isPositive();
            assertThat(s.speedOfSoundMs()).isPositive();
            assertThat(model.name()).isNotBlank();
        }
    }
}
