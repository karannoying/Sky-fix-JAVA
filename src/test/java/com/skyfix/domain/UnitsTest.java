package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit conversion at the system edges (CLAUDE.md rule 3).
 *
 * <p>The oracles are the exact legal definitions: the international foot is exactly 0.3048 m, the
 * nautical mile exactly 1852 m, the avoirdupois pound exactly 0.45359237 kg. Each is asserted
 * against arithmetic on those definitions rather than a second lookup table.
 */
class UnitsTest {

    @Test
    @DisplayName("toMetres is overloaded across bare feet, an explicit unit and a written quantity")
    void toMetresOverloads() throws Exception {
        assertThat(Units.toMetres(1.0)).isEqualTo(0.3048, within(1e-12));
        assertThat(Units.toMetres(1.0, Units.LengthUnit.FOOT)).isEqualTo(0.3048, within(1e-12));
        assertThat(Units.toMetres("1 ft")).isEqualTo(0.3048, within(1e-12));

        assertThat(Units.toMetres(30.0, Units.LengthUnit.KILOMETRE)).isEqualTo(30_000.0);
        assertThat(Units.toMetres(1.0, Units.LengthUnit.NAUTICAL_MILE)).isEqualTo(1852.0);
        assertThat(Units.toMetres(1.0, Units.LengthUnit.MILE)).isEqualTo(5280 * 0.3048, within(1e-9));
        assertThat(Units.toMetres(7.0, Units.LengthUnit.METRE)).isEqualTo(7.0);
    }

    @Test
    @DisplayName("A written quantity parses with or without a space, and a bare number is SI")
    void writtenQuantities() throws Exception {
        assertThat(Units.toMetres("30km")).isEqualTo(30_000.0, within(1e-9));
        assertThat(Units.toMetres("  95000 ft ")).isEqualTo(95_000 * 0.3048, within(1e-6));
        assertThat(Units.toMetres("1200")).isEqualTo(1200.0, within(1e-12));
        assertThat(Units.toMetres("1.5e3 m")).isEqualTo(1500.0, within(1e-9));
        assertThat(Units.toKilograms("1200 g")).isEqualTo(1.2, within(1e-12));
        assertThat(Units.toKilograms("2.5")).isEqualTo(2.5, within(1e-12));
    }

    @Test
    @DisplayName("An unknown unit or a non-number is a ValidationException naming the input")
    void badQuantitiesAreRejected() {
        assertThatThrownBy(() -> Units.toMetres("30 furlongs"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("furlongs");
        assertThatThrownBy(() -> Units.toMetres("high"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Units.toMetres(""))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Units.toKilograms("5 stone"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("stone");
    }

    @Test
    @DisplayName("Mass, temperature and pressure conversions match their legal definitions")
    void otherConversions() {
        assertThat(Units.toKilograms(1.0)).isEqualTo(0.45359237, within(1e-12));
        assertThat(Units.toKilograms(1000.0, Units.MassUnit.GRAM)).isEqualTo(1.0, within(1e-12));

        assertThat(Units.toKelvin(0.0)).isEqualTo(273.15, within(1e-12));
        assertThat(Units.toKelvin(-273.15)).isEqualTo(0.0, within(1e-12));
        assertThat(Units.toKelvin(15.0, Units.TemperatureUnit.CELSIUS))
                .isEqualTo(288.15, within(1e-12));
        assertThat(Units.toKelvin(288.15, Units.TemperatureUnit.KELVIN)).isEqualTo(288.15);

        assertThat(Units.toPascals(1013.25)).isEqualTo(101325.0, within(1e-9));
        assertThat(Units.toPascals(1013.25, Units.PressureUnit.MILLIBAR))
                .isEqualTo(101325.0, within(1e-9));
        assertThat(Units.toPascals(500.0, Units.PressureUnit.PASCAL)).isEqualTo(500.0);
    }

    @Test
    @DisplayName("Display converters invert the ingest converters exactly")
    void displayConvertersRoundTrip() {
        assertThat(Units.metresToFeet(Units.toMetres(12345.0))).isEqualTo(12345.0, within(1e-9));
        assertThat(Units.kelvinToCelsius(Units.toKelvin(-56.5))).isEqualTo(-56.5, within(1e-12));
    }
}
