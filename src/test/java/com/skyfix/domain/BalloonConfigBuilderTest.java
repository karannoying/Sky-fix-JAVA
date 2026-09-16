package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-U-BUILDER and T-E1 — one negative test per cross-field rule in FR-1.3.
 *
 * <p>Each rule here has a matching CHECK constraint in {@code V1__init.sql}, so a value rejected
 * by the builder cannot reach the database through some other path either.
 */
class BalloonConfigBuilderTest {

    /** A configuration that satisfies every rule; each negative test breaks exactly one field. */
    static BalloonConfig.Builder valid() {
        return BalloonConfig.builder()
                .name("HabSat-1200g")
                .payloadMassKg(1.2)
                .envelopeMassKg(1.2)
                .launchDiameterM(1.8)
                .burstDiameterM(7.0)
                .freeLiftKg(1.0)
                .ascentCd(0.45)
                .chuteAreaM2(1.0)
                .chuteCd(1.4)
                .gas(LiftGas.HELIUM);
    }

    @Test
    @DisplayName("T-U-BUILDER: a valid configuration builds and is immutable")
    void validConfigurationBuilds() throws Exception {
        BalloonConfig c = valid().build();
        assertThat(c.name()).isEqualTo("HabSat-1200g");
        assertThat(c.dryMassKg()).isEqualTo(2.4, within(1e-12));
        assertThat(c.configHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("T-E1 rule 1: payload mass must be positive")
    void payloadMassMustBePositive() {
        assertThatThrownBy(() -> valid().payloadMassKg(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("payload_mass_kg");
        assertThatThrownBy(() -> valid().payloadMassKg(-1.2).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("payload_mass_kg");
    }

    @Test
    @DisplayName("T-E1 rule 2: envelope mass must be positive")
    void envelopeMassMustBePositive() {
        assertThatThrownBy(() -> valid().envelopeMassKg(-0.1).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("envelope_mass_kg");
    }

    @Test
    @DisplayName("T-E1 rule 3: launch diameter must be positive")
    void launchDiameterMustBePositive() {
        assertThatThrownBy(() -> valid().launchDiameterM(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("launch_diameter_m");
    }

    @Test
    @DisplayName("T-E1 rule 4: burst diameter must exceed launch diameter")
    void burstDiameterMustExceedLaunchDiameter() {
        assertThatThrownBy(() -> valid().launchDiameterM(1.6).burstDiameterM(1.2).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("burst_diameter_m")
                .hasMessageContaining("launch_diameter_m");

        // Equal is not enough either: the envelope has to have somewhere to expand to.
        assertThatThrownBy(() -> valid().launchDiameterM(1.6).burstDiameterM(1.6).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("burst_diameter_m");
    }

    @Test
    @DisplayName("T-E1 rule 5: free lift must be positive")
    void freeLiftMustBePositive() {
        assertThatThrownBy(() -> valid().freeLiftKg(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("free_lift_kg");
    }

    @Test
    @DisplayName("T-E1 rule 6: ascent Cd must lie in [0.1, 2.0]")
    void ascentCdMustBeInRange() {
        assertThatThrownBy(() -> valid().ascentCd(0.05).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ascent_cd");
        assertThatThrownBy(() -> valid().ascentCd(2.5).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ascent_cd");
    }

    @Test
    @DisplayName("T-E1 rule 7: parachute area must be positive")
    void chuteAreaMustBePositive() {
        assertThatThrownBy(() -> valid().chuteAreaM2(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("chute_area_m2");
    }

    @Test
    @DisplayName("T-E1 rule 8: parachute Cd must lie in [0.1, 2.0]")
    void chuteCdMustBeInRange() {
        assertThatThrownBy(() -> valid().chuteCd(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("chute_cd");
        assertThatThrownBy(() -> valid().chuteCd(2.0001).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("chute_cd");
    }

    @Test
    @DisplayName("T-E1 rule 9: gas is required and must be a known gas")
    void gasIsRequired() {
        assertThatThrownBy(() -> valid().gas(null).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("gas");
    }

    @Test
    @DisplayName("T-E1: a missing required field is reported as absent, not as zero")
    void missingFieldIsReportedAsAbsent() {
        assertThatThrownBy(() -> BalloonConfig.builder().name("bare").build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("payload_mass_kg")
                .hasMessageContaining("absent");
        assertThatThrownBy(() -> valid().name("  ").build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("T-E1: the exception names the offending field in its context, not just its text")
    void exceptionCarriesFieldContext() {
        assertThatThrownBy(() -> valid().ascentCd(9.0).build())
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.context()).containsEntry("field", "ascent_cd");
                    assertThat(e.context()).containsEntry("value", "9.0");
                    assertThat(e.exitCode()).isEqualTo(2);
                    assertThat(e.userMessage()).contains("ascent_cd").contains("field=ascent_cd");
                });
    }

    @Test
    @DisplayName("ADR-10: the config hash changes with any field and is stable across rebuilds")
    void configHashPinsEveryField() throws Exception {
        BalloonConfig base = valid().build();
        assertThat(valid().build().configHash())
                .as("same inputs must rehash identically")
                .isEqualTo(base.configHash());
        assertThat(base.toBuilder().build().configHash()).isEqualTo(base.configHash());

        assertThat(valid().ascentCd(0.451).build().configHash()).isNotEqualTo(base.configHash());
        assertThat(valid().name("other").build().configHash()).isNotEqualTo(base.configHash());
        assertThat(valid().gas(LiftGas.HYDROGEN).build().configHash())
                .isNotEqualTo(base.configHash());
        // A change in the ninth significant figure still yields a different identity (ADR-10).
        assertThat(valid().payloadMassKg(1.20000001).build().configHash())
                .isNotEqualTo(base.configHash());
    }

    @Test
    @DisplayName("Inflation follows from free lift, and the gas law conserves gas mass")
    void inflationFollowsFromFreeLift() throws Exception {
        BalloonConfig c = valid().build();
        double rho = 1.225, p = 101325.0, t = 288.15;

        double volume = c.inflatedVolumeM3(rho, p, t);
        double gasMass = c.gasMassKg(rho, p, t);

        // Buoyancy at launch must equal dry mass + gas mass + free lift, by construction.
        double buoyantMass = rho * volume;
        assertThat(buoyantMass).isEqualTo(c.dryMassKg() + gasMass + c.freeLiftKg(), within(1e-9));

        // Volume and diameter must be consistent through the cube root, both ways.
        double d = c.inflatedDiameterM(rho, p, t);
        assertThat(BalloonConfig.volumeOfDiameter(d)).isEqualTo(volume, within(1e-9));
        assertThat(BalloonConfig.diameterOfVolume(volume)).isEqualTo(d, within(1e-12));

        // The same gas mass at a lower pressure occupies a proportionally larger volume.
        assertThat(c.volumeAt(gasMass, p / 2.0, t)).isEqualTo(2.0 * volume, within(1e-9));
    }

    @Test
    @DisplayName("A gas heavier than the surrounding air is rejected, naming the field")
    void nonBuoyantGasIsRejected() {
        assertThatThrownBy(() -> valid().build().inflatedVolumeM3(0.01, 101325.0, 288.15))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("gas");
    }

    @Test
    @DisplayName("Hydrogen lifts more than helium for the same envelope")
    void hydrogenLiftsMoreThanHelium() throws Exception {
        double p = 101325.0, t = 288.15;
        assertThat(LiftGas.HYDROGEN.densityAt(p, t)).isLessThan(LiftGas.HELIUM.densityAt(p, t));
        // Same free lift, lighter gas, so less envelope volume is needed.
        assertThat(valid().gas(LiftGas.HYDROGEN).build().inflatedVolumeM3(1.225, p, t))
                .isLessThan(valid().gas(LiftGas.HELIUM).build().inflatedVolumeM3(1.225, p, t));
    }
}
