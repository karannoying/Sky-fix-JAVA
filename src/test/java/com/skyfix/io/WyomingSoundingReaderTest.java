package com.skyfix.io;

import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.core.atmos.SoundingWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.atmos.WindSample;
import com.skyfix.domain.error.DataFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * FR-1.1 and T-E3 — sounding ingest, including what happens to bad lines.
 *
 * <p>The acceptance criterion is not only that good levels parse: every rejected line must be
 * reported as {@code file:line:reason} and counted, never dropped in silence. Most of these tests
 * are therefore about malformed input.
 */
class WyomingSoundingReaderTest {

    private static final Path SAMPLE =
            Path.of("data", "soundings", "SYNTHETIC_2026-09-14_00Z.txt");

    private final WyomingSoundingReader reader = new WyomingSoundingReader();

    @Test
    @DisplayName("FR-1.1: the committed sample parses to a usable, sorted profile")
    void sampleFileParses() throws Exception {
        SoundingReader.SoundingParseResult result = reader.read(SAMPLE);

        assertThat(result.levelCount()).isGreaterThanOrEqualTo(30);
        assertThat(result.rejections()).isEmpty();
        assertThat(result.stationId()).isEqualTo("SYNTH");
        assertThat(result.fileSha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(reader.sourceName()).isEqualTo("WYOMING");

        // Sorted, strictly increasing, and converted into SI.
        double previous = Double.NEGATIVE_INFINITY;
        for (SoundingLevel level : result.levels()) {
            assertThat(level.heightGeopotentialM()).isGreaterThan(previous);
            previous = level.heightGeopotentialM();
            assertThat(level.pressurePa()).isBetween(500.0, 110_000.0);
            assertThat(level.temperatureK()).isBetween(150.0, 340.0);
        }
        assertThat(result.levels().get(result.levels().size() - 1).heightGeopotentialM())
                .as("the profile must reach the stratosphere to be useful")
                .isGreaterThan(25_000.0);
    }

    @Test
    @DisplayName("FR-1.1: units are converted at ingest — hPa to Pa, C to K, knots to m/s")
    void unitsAreConvertedAtTheBoundary() throws Exception {
        Path file = writeSounding("""
                   PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV
                    hPa     m      C      C      %    g/kg    deg   knot     K      K      K
                 1000.0    111   15.0   11.0   80.0  11.00    270     10  288.0  293.0  289.0
                  500.0   5574  -21.0  -30.0   40.0   1.00    270     20  300.0  305.0  301.0
                """);
        SoundingLevel level = reader.read(file).levels().get(0);

        assertThat(level.pressurePa()).isEqualTo(100_000.0, within(1e-6));
        assertThat(level.temperatureK()).isEqualTo(288.15, within(1e-9));
        // 10 knots is exactly 10 * 1852/3600 m/s, blowing from 270 so pushing due east.
        assertThat(level.windEastMs()).isEqualTo(10 * 1852.0 / 3600.0, within(1e-9));
        assertThat(level.windNorthMs()).isEqualTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("FR-1.1: a non-numeric field is reported as file:line:reason, not dropped")
    void nonNumericFieldIsReportedWithFileAndLine() throws Exception {
        Path file = writeSounding("""
                   PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV
                 1000.0    111   15.0   11.0   80.0  11.00    270     10  288.0  293.0  289.0
                  925.0    762   10.0    8.0   80.0   9.00    ///    ///  290.0  295.0  291.0
                  850.0   1457    5.0    2.0   70.0   7.00    260     15  292.0  297.0  293.0
                """);
        SoundingReader.SoundingParseResult result = reader.read(file);

        assertThat(result.levelCount()).isEqualTo(2);
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.rejections().get(0))
                .contains(file.getFileName().toString())
                .contains(":3:")            // the offending line number
                .contains("wind_dir")
                .contains("///");
    }

    @Test
    @DisplayName("FR-1.1: out-of-range values are rejected with the range named")
    void outOfRangeValuesAreRejected() throws Exception {
        Path file = writeSounding("""
                   PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV
                 1000.0    111   15.0   11.0   80.0  11.00    270     10  288.0  293.0  289.0
                  925.0    762 -160.0    8.0   80.0   9.00    260     12  290.0  295.0  291.0
                  900.0    988   10.0    8.0   80.0   9.00    999     12  290.0  295.0  291.0
                  875.0   1200   10.0    8.0   80.0   9.00    260    -12  290.0  295.0  291.0
                 -850.0   1457    5.0    2.0   70.0   7.00    260     15  292.0  297.0  293.0
                  800.0   1949    2.0    0.0   70.0   6.00    255     18  294.0  299.0  295.0
                """);
        SoundingReader.SoundingParseResult result = reader.read(file);

        assertThat(result.levelCount()).isEqualTo(2);
        assertThat(result.rejectedCount()).isEqualTo(4);
        assertThat(String.join("\n", result.rejections()))
                .contains("150-340 K")       // temperature below the plausible floor
                .contains("0-360 degrees")   // wind direction out of range
                .contains("must not be negative")
                .contains("must be positive");
    }

    @Test
    @DisplayName("FR-1.1: a repeated height is dropped and reported, so interpolation stays defined")
    void repeatedHeightsAreDroppedAndReported() throws Exception {
        Path file = writeSounding("""
                   PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV
                 1000.0    111   15.0   11.0   80.0  11.00    270     10  288.0  293.0  289.0
                  925.0    762   10.0    8.0   80.0   9.00    260     12  290.0  295.0  291.0
                  924.0    762   10.1    8.0   80.0   9.00    261     12  290.0  295.0  291.0
                  850.0   1457    5.0    2.0   70.0   7.00    260     15  292.0  297.0  293.0
                """);
        SoundingReader.SoundingParseResult result = reader.read(file);

        assertThat(result.levelCount()).isEqualTo(3);
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.rejections().get(0)).contains("762").contains("repeats");
    }

    @Test
    @DisplayName("FR-1.1: a line with too few columns names how many it had")
    void shortLinesAreRejected() throws Exception {
        Path file = writeSounding("""
                   PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV
                 1000.0    111   15.0   11.0   80.0  11.00    270     10  288.0  293.0  289.0
                  925.0    762   10.0
                  850.0   1457    5.0    2.0   70.0   7.00    260     15  292.0  297.0  293.0
                """);
        SoundingReader.SoundingParseResult result = reader.read(file);
        assertThat(result.levelCount()).isEqualTo(2);
        assertThat(result.rejections().get(0)).contains("3 columns").contains("at least 8");
    }

    @Test
    @DisplayName("T-E3: a file with too few usable levels fails cleanly, naming the count")
    void tooFewUsableLevelsFailsCleanly() throws Exception {
        Path file = writeSounding("""
                   PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV
                 1000.0    111   15.0   11.0   80.0  11.00    ///    ///  288.0  293.0  289.0
                  925.0    762   10.0    8.0   80.0   9.00    260     12  290.0  295.0  291.0
                """);
        assertThatThrownBy(() -> reader.read(file))
                .isInstanceOfSatisfying(DataFormatException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(3);
                    assertThat(e.getMessage()).contains("1 usable levels");
                    assertThat(e.getMessage()).contains("1 lines rejected");
                });
    }

    @Test
    @DisplayName("T-E3: a corrupted 10,000-line file exits cleanly rather than throwing raw")
    void corruptedFileExitsCleanly(@TempDir Path dir) throws Exception {
        StringBuilder sb = new StringBuilder(
                "   PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV\n");
        for (int i = 0; i < 10_000; i++) {
            sb.append("  9").append(i % 10).append("9.0   ????   ??.?   ??.?   ??.?  ??.??")
                    .append("    ???    ???  ???.?  ???.?  ???.?\n");
        }
        Path file = dir.resolve("corrupt.txt");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file))
                .isInstanceOfSatisfying(DataFormatException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(3);
                    // A clean, actionable message — not a stack trace (NFR-3).
                    assertThat(e.userMessage()).contains("usable levels");
                });
    }

    @Test
    @DisplayName("T-E3: a missing file is a DataFormatException, not an IOException")
    void missingFileIsReportedCleanly() {
        assertThatThrownBy(() -> reader.read(Path.of("data", "soundings", "no-such-file.txt")))
                .isInstanceOf(DataFormatException.class)
                .hasMessageContaining("cannot be read");
    }

    @Test
    @DisplayName("FR-1.1: re-reading the same file yields the same SHA-256, so imports are idempotent")
    void hashIsStableAcrossReads() throws Exception {
        assertThat(reader.read(SAMPLE).fileSha256()).isEqualTo(reader.read(SAMPLE).fileSha256());
    }

    @Test
    @DisplayName("The parsed sample drives a wind field whose jet appears near 12 km")
    void parsedSampleDrivesAWindField() throws Exception {
        SoundingWindField field = SoundingWindField.of("SYNTH", reader.read(SAMPLE).levels());

        WindSample low = field.at(0, 500.0);
        WindSample jet = field.at(0, Ussa1976Atmosphere.toGeometricM(12_000.0));
        assertThat(jet.speedMs()).as("the jet must be the fastest part of the profile")
                .isGreaterThan(low.speedMs() * 3);
        assertThat(jet.extrapolated()).isFalse();
        assertThat(jet.eastMs()).as("a westerly jet pushes eastward").isPositive();

        // Above the top of the profile the wind is held, and says so.
        assertThat(field.at(0, 60_000.0).extrapolated()).isTrue();
    }

    private static Path writeSounding(String body) throws Exception {
        Path file = Files.createTempFile("sounding", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }
}
