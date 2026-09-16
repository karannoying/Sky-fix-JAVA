package com.skyfix.domain;

import com.skyfix.domain.error.ConvergenceException;
import com.skyfix.domain.error.DataFormatException;
import com.skyfix.domain.error.ModelDomainException;
import com.skyfix.domain.error.PersistenceException;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** GeoPoint ranges, StateHistory summaries, and the exit codes of the exception hierarchy. */
class DomainTypesTest {

    @Test
    @DisplayName("GeoPoint.of reports out-of-range user input as a ValidationException")
    void geoPointValidatesUserInput() {
        assertThatThrownBy(() -> GeoPoint.of(91.0, 0.0, 0.0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("latitude");
        assertThatThrownBy(() -> GeoPoint.of(0.0, 181.0, 0.0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    @DisplayName("GeoPoint rejects a programmer error with IllegalArgumentException, not a checked one")
    void geoPointRejectsProgrammerErrors() {
        assertThatThrownBy(() -> new GeoPoint(91.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(0.0, 0.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GeoPoint helpers keep latitude and longitude while changing altitude")
    void geoPointHelpers() throws Exception {
        GeoPoint p = GeoPoint.of(23.2599, 77.4126, 500.0);
        assertThat(p.atAltitude(30_000.0).altitudeM()).isEqualTo(30_000.0);
        assertThat(p.atAltitude(30_000.0).latitudeDeg()).isEqualTo(p.latitudeDeg());
        assertThat(p.distanceTo(p)).isEqualTo(0.0, within(1e-9));
        assertThat(p.toString()).contains("23.2599").contains("500.0");
    }

    @Test
    @DisplayName("StateHistory summarises apogee, duration, burst, landing and wind extrapolation")
    void stateHistorySummaries() throws Exception {
        GeoPoint at = GeoPoint.of(23.0, 77.0, 0.0);
        BalloonState ascending = new BalloonState(0, at.atAltitude(500), 5.0, 2.0,
                Phase.ASCENT, false);
        BalloonState burst = new BalloonState(1800, at.atAltitude(30_000), 5.0, 7.0,
                Phase.BURST, true);
        BalloonState landed = new BalloonState(3600, at.atAltitude(210), 0.0, 7.0,
                Phase.LANDED, false);

        StateHistory h = StateHistory.builder()
                .add(ascending).countStep(false)
                .add(burst).countStep(true)
                .add(landed).countStep(false)
                .burst(burst)
                .landing(landed)
                .build();

        assertThat(h.states()).hasSize(3);
        assertThat(h.apogeeM()).isEqualTo(30_000.0);
        assertThat(h.durationSeconds()).isEqualTo(3600.0);
        assertThat(h.stepCount()).isEqualTo(3);
        assertThat(h.windExtrapolatedCount()).isEqualTo(1);
        assertThat(h.burstAltitudeM()).contains(30_000.0);
        assertThat(h.landingPoint()).isPresent();
        assertThat(h.landing()).contains(landed);
        assertThat(h).hasSize(3); // Iterable
        assertThat(h.states()).isUnmodifiable();
    }

    @Test
    @DisplayName("A history with no burst and no landing reports both as empty")
    void emptyHistorySummaries() {
        StateHistory h = StateHistory.builder().build();
        assertThat(h.burst()).isEmpty();
        assertThat(h.landing()).isEmpty();
        assertThat(h.landingPoint()).isEmpty();
        assertThat(h.burstAltitudeM()).isEmpty();
        assertThat(h.durationSeconds()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("BLUEPRINT §12: each exception type maps to its documented exit code")
    void exitCodesMatchTheBlueprint() {
        assertThat(new ValidationException("x").exitCode()).isEqualTo(2);
        assertThat(new DataFormatException("f.txt", 7, "x").exitCode()).isEqualTo(3);
        assertThat(new ModelDomainException("x").exitCode()).isEqualTo(4);
        assertThat(new ConvergenceException("x").exitCode()).isEqualTo(5);
        assertThat(new PersistenceException("x").exitCode()).isEqualTo(6);
    }

    @Test
    @DisplayName("NFR-3: a parse failure renders as file:line: message")
    void parseFailuresCarryFileAndLine() {
        SkyfixException e = new DataFormatException("VABB_2024-01-15_00Z.txt", 143,
                "wind_dir \"///\" is not numeric");
        assertThat(e.sourceFile()).isEqualTo("VABB_2024-01-15_00Z.txt");
        assertThat(e.sourceLine()).isEqualTo(143);
        assertThat(e.userMessage())
                .startsWith("VABB_2024-01-15_00Z.txt:143: ")
                .contains("is not numeric");
    }

    @Test
    @DisplayName("Context entries render in insertion order and the map is a copy")
    void contextIsOrderedAndCopied() {
        SkyfixException e = new ModelDomainException("above ceiling")
                .with("quantity", "altitude").with("value", 90_000.0);
        assertThat(e.userMessage())
                .isEqualTo("above ceiling [quantity=altitude, value=90000.0]");
        assertThat(e.context()).isUnmodifiable();
    }

    @Test
    @DisplayName("ModelDomainException.outOfRange names the quantity, the value and the bounds")
    void outOfRangeMessage() {
        ModelDomainException e = ModelDomainException.outOfRange(
                "geometric altitude", 90_000.0, 0.0, 86_000.0, "m");
        assertThat(e.getMessage()).contains("geometric altitude").contains("86000.0");
        assertThat(e.context()).containsEntry("max", "86000.0");
    }

    @Test
    @DisplayName("Phase covers exactly the states the schema allows")
    void phasesMatchTheSchema() {
        assertThat(Phase.values())
                .containsExactly(Phase.ASCENT, Phase.BURST, Phase.DESCENT, Phase.LANDED);
        assertThat(new BalloonState(0, new GeoPoint(0, 0, 0), 0, 1, Phase.ASCENT, false)
                .withPhase(Phase.LANDED).phase()).isEqualTo(Phase.LANDED);
    }

    @Test
    @DisplayName("FlightParameters derive from a config and support single-field replacement")
    void flightParameterDerivation() throws Exception {
        BalloonConfig c = BalloonConfigBuilderTest.valid().build();
        FlightParameters p = FlightParameters.nominal(c);
        assertThat(p.freeLiftKg()).isEqualTo(c.freeLiftKg());
        assertThat(p.ascentCd()).isEqualTo(c.ascentCd());
        assertThat(p.burstDiameterM()).isEqualTo(c.burstDiameterM());
        assertThat(p.chuteCd()).isEqualTo(c.chuteCd());
        assertThat(p.windScale()).isEqualTo(1.0);

        assertThat(p.withFreeLiftKg(2.0).freeLiftKg()).isEqualTo(2.0);
        assertThat(p.withAscentCd(0.3).ascentCd()).isEqualTo(0.3);
        assertThat(p.withBurstDiameterM(9.0).burstDiameterM()).isEqualTo(9.0);
        assertThat(p.withChuteCd(1.0).chuteCd()).isEqualTo(1.0);
        assertThat(p.withWindScale(1.2).windScale()).isEqualTo(1.2);
        // Replacing one field leaves the rest alone.
        assertThat(p.withWindScale(1.2).freeLiftKg()).isEqualTo(p.freeLiftKg());
    }

    @Test
    @DisplayName("cdf inverts quantile across every distribution")
    void cdfInvertsQuantile() throws Exception {
        Distribution uniform = new Distribution.Uniform(2.0, 8.0);
        Distribution normal = Distribution.TruncatedNormal.symmetric(7.0, 0.7, 3.0);

        for (int i = 1; i < 100; i++) {
            double p = i / 100.0;
            assertThat(uniform.cdf(uniform.quantile(p))).isCloseTo(p, within(1e-9));
            assertThat(normal.cdf(normal.quantile(p))).isCloseTo(p, within(1e-6));
        }
        // Outside the support the answer is saturated, not extrapolated.
        assertThat(uniform.cdf(1.0)).isZero();
        assertThat(uniform.cdf(9.0)).isEqualTo(1.0);
        assertThat(normal.cdf(normal.minimum() - 1.0)).isZero();
        assertThat(normal.cdf(normal.maximum() + 1.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("conditioning a prior on a lower bound never returns a value below it")
    void cdfSupportsTruncatedSampling() throws Exception {
        // How the particle filter samples burst diameter given that the envelope has reached D
        // without bursting (ADR-3 §4).
        Distribution prior = Distribution.TruncatedNormal.symmetric(7.0, 0.7, 3.0);
        double reached = 6.2;
        double pLow = prior.cdf(reached);

        for (int i = 1; i < 200; i++) {
            double u = pLow + (i / 200.0) * (1.0 - pLow);
            assertThat(prior.quantile(u)).isGreaterThanOrEqualTo(reached - 1e-9);
        }
        assertThat(pLow).isGreaterThan(0.0).isLessThan(1.0);
    }

    @Test
    @DisplayName("spread reports a usable width for each distribution shape")
    void spreadDescribesTheWidth() throws Exception {
        assertThat(new Distribution.Uniform(0.0, 12.0).spread())
                .isCloseTo(12.0 / Math.sqrt(12.0), within(1e-12));
        assertThat(Distribution.TruncatedNormal.symmetric(7.0, 0.7, 3.0).spread())
                .isEqualTo(0.7);
        assertThat(new Distribution.Fixed(4.0).spread()).isZero();
    }
}
