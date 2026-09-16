package com.skyfix.core.atmos;

import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-U-WIND — node-exact interpolation, hold-above-top, and the extrapolation flag (FR-2.2).
 */
class WindFieldTest {

    /** Heights here are geopotential, as a radiosonde reports them. */
    private static List<SoundingLevel> profile() {
        return List.of(
                new SoundingLevel(0, 101_325, 300.0, 2.0, 1.0),
                new SoundingLevel(5_000, 54_000, 255.0, 12.0, -3.0),
                new SoundingLevel(10_000, 26_500, 223.0, 30.0, -8.0),
                new SoundingLevel(20_000, 5_500, 217.0, -5.0, 2.0));
    }

    @Test
    @DisplayName("T-U-WIND: a query at a node height returns that node's values exactly")
    void nodeAltitudesReturnNodeValuesExactly() throws Exception {
        SoundingWindField field = SoundingWindField.of("VABB", profile());
        for (SoundingLevel level : profile()) {
            // The field is indexed by geopotential height, so query at the geometric altitude
            // that maps to it — otherwise "node-exact" would be testing the wrong point.
            double geometric = Ussa1976Atmosphere.toGeometricM(level.heightGeopotentialM());
            WindSample w = field.at(0.0, geometric);
            assertThat(w.eastMs()).as("u at %.0f gpm", level.heightGeopotentialM())
                    .isEqualTo(level.windEastMs(), within(1e-9));
            assertThat(w.northMs()).as("v at %.0f gpm", level.heightGeopotentialM())
                    .isEqualTo(level.windNorthMs(), within(1e-9));
            assertThat(w.extrapolated()).isFalse();
        }
    }

    @Test
    @DisplayName("T-U-WIND: between nodes the wind is linear in geopotential height")
    void interpolationIsLinearBetweenNodes() throws Exception {
        SoundingWindField field = SoundingWindField.of("VABB", profile());
        // Halfway between the 5 km and 10 km nodes, in geopotential height.
        double geometric = Ussa1976Atmosphere.toGeometricM(7_500.0);
        WindSample w = field.at(0.0, geometric);
        assertThat(w.eastMs()).isEqualTo(21.0, within(1e-9));   // midway between 12 and 30
        assertThat(w.northMs()).isEqualTo(-5.5, within(1e-9));  // midway between -3 and -8
        assertThat(w.extrapolated()).isFalse();
    }

    @Test
    @DisplayName("T-U-WIND: above the top level the wind is held and flagged as extrapolated")
    void aboveTopLevelHoldsAndFlags() throws Exception {
        SoundingWindField field = SoundingWindField.of("VABB", profile());
        WindSample w = field.at(0.0, Ussa1976Atmosphere.toGeometricM(30_000.0));
        assertThat(w.eastMs()).isEqualTo(-5.0);
        assertThat(w.northMs()).isEqualTo(2.0);
        assertThat(w.extrapolated())
                .as("a held value above the profile must say so — FR-2.2 counts these")
                .isTrue();
    }

    @Test
    @DisplayName("T-U-WIND: below the bottom level the wind is held and flagged too")
    void belowBottomLevelHoldsAndFlags() throws Exception {
        SoundingWindField field = SoundingWindField.of("VABB", profile());
        WindSample w = field.at(0.0, -200.0);
        assertThat(w.eastMs()).isEqualTo(2.0);
        assertThat(w.extrapolated()).isTrue();

        // Exactly at the bottom node is inside the profile, not extrapolated.
        assertThat(field.at(0.0, 0.0).extrapolated()).isFalse();
    }

    @Test
    @DisplayName("T-U-WIND: a profile needs at least two levels and strictly increasing heights")
    void degenerateProfilesAreRejected() {
        assertThatThrownBy(() -> SoundingWindField.of("VABB",
                List.of(new SoundingLevel(0, 101_325, 300, 1, 1))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("level_count");
        assertThatThrownBy(() -> SoundingWindField.of("VABB", null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SoundingWindField.of("VABB", List.of(
                new SoundingLevel(1_000, 90_000, 290, 1, 1),
                new SoundingLevel(1_000, 90_000, 290, 5, 5))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("height_gpm");
    }

    @Test
    @DisplayName("T-U-WIND: levels supplied out of order are sorted, not rejected")
    void unsortedLevelsAreSorted() throws Exception {
        SoundingWindField field = SoundingWindField.of("VABB", List.of(
                new SoundingLevel(10_000, 26_500, 223, 30, -8),
                new SoundingLevel(0, 101_325, 300, 2, 1),
                new SoundingLevel(5_000, 54_000, 255, 12, -3)));
        assertThat(field.levelCount()).isEqualTo(3);
        assertThat(field.bottomHeightM()).isEqualTo(0.0);
        assertThat(field.topHeightM()).isEqualTo(10_000.0);
        assertThat(field.at(0, Ussa1976Atmosphere.toGeometricM(5_000)).eastMs())
                .isEqualTo(12.0, within(1e-9));
        assertThat(field.stationId()).isEqualTo("VABB");
        assertThat(field.name()).isEqualTo("SOUNDING");
    }

    @Test
    @DisplayName("Interpolation is correct at every level of a large profile")
    void binarySearchIsCorrectAcrossAWholeProfile() throws Exception {
        // 120 levels, the size of a real radiosonde ascent, so an off-by-one in the binary
        // search shows up rather than hiding in a four-level toy.
        java.util.List<SoundingLevel> many = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            many.add(new SoundingLevel(i * 250.0, 101_325 - i * 800.0, 290 - i * 0.5,
                    i * 0.4, -i * 0.2));
        }
        SoundingWindField field = SoundingWindField.of("BIG", many);
        for (SoundingLevel level : many) {
            WindSample w = field.at(0, Ussa1976Atmosphere.toGeometricM(level.heightGeopotentialM()));
            assertThat(w.eastMs()).isEqualTo(level.windEastMs(), within(1e-9));
            assertThat(w.northMs()).isEqualTo(level.windNorthMs(), within(1e-9));
        }
        // And midway between two arbitrary interior nodes.
        WindSample mid = field.at(0, Ussa1976Atmosphere.toGeometricM(250.0 * 61.5));
        assertThat(mid.eastMs()).isEqualTo(0.4 * 61.5, within(1e-9));
    }

    @Test
    @DisplayName("Meteorological wind reports convert to east-north with the right sign")
    void meteorologicalConversionSigns() {
        // A "westerly" (270 degrees) blows FROM the west, so it pushes eastward: u > 0, v = 0.
        SoundingLevel westerly = SoundingLevel.fromMeteorological(0, 101_325, 288, 270.0, 10.0);
        assertThat(westerly.windEastMs()).isEqualTo(10.0, within(1e-9));
        assertThat(westerly.windNorthMs()).isEqualTo(0.0, within(1e-9));

        // A "northerly" (0 degrees) blows FROM the north, so it pushes southward: v < 0.
        SoundingLevel northerly = SoundingLevel.fromMeteorological(0, 101_325, 288, 0.0, 10.0);
        assertThat(northerly.windNorthMs()).isEqualTo(-10.0, within(1e-9));
        assertThat(northerly.windEastMs()).isEqualTo(0.0, within(1e-9));

        assertThat(westerly.windSpeedMs()).isEqualTo(10.0, within(1e-9));
    }

    @Test
    @DisplayName("WindSample reports speed and meteorological direction consistently")
    void windSampleAccessors() {
        WindSample w = new WindSample(10.0, 0.0, false);
        assertThat(w.speedMs()).isEqualTo(10.0, within(1e-9));
        assertThat(w.fromDirectionDeg()).isEqualTo(270.0, within(1e-9)); // blowing east, from west
        assertThat(new WindSample(0.0, -10.0, false).fromDirectionDeg())
                .isEqualTo(0.0, within(1e-9)); // blowing south, from north
        assertThat(w.scaled(2.0).eastMs()).isEqualTo(20.0);
        assertThat(w.scaled(2.0).extrapolated()).isFalse();
        assertThat(new WindSample(1, 1, true).scaled(0.5).extrapolated())
                .as("scaling must not launder the extrapolation flag").isTrue();
        assertThat(WindSample.CALM.speedMs()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("A constant field is uniform in space and time — the analytic oracle")
    void constantFieldIsUniform() throws Exception {
        ConstantWindField field = new ConstantWindField(7.0, -2.0);
        for (double h : new double[]{0, 1_000, 30_000}) {
            for (double t : new double[]{0, 500, 9_999}) {
                assertThat(field.at(t, h).eastMs()).isEqualTo(7.0);
                assertThat(field.at(t, h).northMs()).isEqualTo(-2.0);
                assertThat(field.at(t, h).extrapolated()).isFalse();
            }
        }
        assertThat(ConstantWindField.calm().at(0, 0).speedMs()).isEqualTo(0.0);
        assertThat(field.name()).isEqualTo("CONSTANT");
    }

    @Test
    @DisplayName("WindFieldFactory resolves known names and rejects unknown ones by name")
    void factoryResolvesAndRejects() throws Exception {
        assertThat(WindFieldFactory.create("SOUNDING", "VABB", profile(), 0, 0))
                .isInstanceOf(SoundingWindField.class);
        assertThat(WindFieldFactory.create("constant", null, null, 3, 4).at(0, 0).speedMs())
                .isEqualTo(5.0, within(1e-9));
        assertThat(WindFieldFactory.create("CALM", null, null, 0, 0).at(0, 0).speedMs())
                .isEqualTo(0.0);
        assertThat(WindFieldFactory.available()).contains("SOUNDING", "CONSTANT", "CALM");

        assertThatThrownBy(() -> WindFieldFactory.create("gfs", null, null, 0, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("wind_field");
        assertThatThrownBy(() -> WindFieldFactory.create(null, null, null, 0, 0))
                .isInstanceOf(ValidationException.class);
    }
}
