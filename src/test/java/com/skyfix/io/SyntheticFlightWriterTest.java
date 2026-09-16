package com.skyfix.io;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.FlightTruth;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * FR-1.4 — the synthetic flight generator that carries O3 and O4.
 *
 * <p>The criterion that matters most is byte-identical regeneration: DS-6 is the evaluation set,
 * and an evaluation set that drifts between runs would make every T-V5 and T-V6 number
 * unreproducible. So that is asserted on the bytes, not on parsed values, which would pass even if
 * the formatting had changed.
 */
class SyntheticFlightWriterTest {

    @TempDir
    Path dir;

    private static final Ussa1976Atmosphere ATMOSPHERE = new Ussa1976Atmosphere();
    private static final ConstantWindField WIND = new ConstantWindField(12.0, -4.0);
    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);
    private static final Instant EPOCH = Instant.parse("2026-09-14T04:30:00Z");

    static BalloonConfig config() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g").payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4).gas(LiftGas.HELIUM)
                .build();
    }

    static SimSettings settings() throws Exception {
        return SimSettings.builder().groundElevationM(LAUNCH.altitudeM()).build();
    }

    private FlightTruth generate(Path path, NoiseSpec noise, long seed) throws Exception {
        BalloonConfig config = config();
        return new SyntheticFlightWriter(ATMOSPHERE, WIND).write(path, config,
                FlightParameters.nominal(config), settings(), LAUNCH, EPOCH, noise, seed);
    }

    @Test
    @DisplayName("FR-1.4: the same seed reproduces a byte-identical file")
    void sameSeedReproducesByteIdenticalFile() throws Exception {
        Path a = dir.resolve("a.csv");
        Path b = dir.resolve("b.csv");
        FlightTruth truthA = generate(a, NoiseSpec.standard(), 20260914L);
        FlightTruth truthB = generate(b, NoiseSpec.standard(), 20260914L);

        // On the bytes, not on parsed values: a formatting change would slip past a parsed
        // comparison, and DS-6 has to be reproducible as a file.
        assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
        assertThat(truthB).isEqualTo(truthA);
    }

    @Test
    @DisplayName("FR-1.4: a different seed gives a different file but a comparable flight")
    void differentSeedGivesDifferentNoise() throws Exception {
        Path a = dir.resolve("a.csv");
        Path b = dir.resolve("b.csv");
        FlightTruth truthA = generate(a, NoiseSpec.standard(), 1L);
        FlightTruth truthB = generate(b, NoiseSpec.standard(), 2L);

        assertThat(Files.readAllBytes(a)).isNotEqualTo(Files.readAllBytes(b));
        // Only the noise differs: the underlying flight is the same truth, so it lands in the
        // same place to within the GPS error.
        assertThat(truthB.landingLatDeg()).isEqualTo(truthA.landingLatDeg(), within(1e-9));
        assertThat(truthB.burstAltitudeM()).isEqualTo(truthA.burstAltitudeM(), within(1e-9));
    }

    @Test
    @DisplayName("FR-1.4: the log reads back through the normal ingest path")
    void generatedLogParsesWithTheReader() throws Exception {
        Path file = dir.resolve("flight.csv");
        FlightTruth truth = generate(file, NoiseSpec.standard(), 7L);

        TelemetrySeries series = new CsvTelemetryReader().read(file);
        assertThat(series.rejections())
                .as("the generator must not emit rows its own reader rejects").isEmpty();
        assertThat(series.duplicatePacketCount()).isZero();
        assertThat(series.outOfOrderCount()).isZero();
        assertThat(series.size()).isEqualTo(truth.sampleCount());

        // Packet ids are dense and ordered, as a flight computer's would be.
        for (int i = 0; i < series.size(); i++) {
            assertThat(series.samples().get(i).packetId()).isEqualTo(i);
        }
        assertThat(series.first().epochUtc()).isEqualTo(EPOCH);
        assertThat(series.duration().toSeconds())
                .isCloseTo((long) series.size() - 1, within(2L));
    }

    @Test
    @DisplayName("FR-1.4: noise is actually applied, and its scale matches the spec")
    void noiseIsAppliedAtTheSpecifiedScale() throws Exception {
        Path noisy = dir.resolve("noisy.csv");
        Path clean = dir.resolve("clean.csv");
        generate(noisy, NoiseSpec.standard(), 11L);
        generate(clean, NoiseSpec.none(), 11L);

        TelemetrySeries noisySeries = new CsvTelemetryReader().read(noisy);
        TelemetrySeries cleanSeries = new CsvTelemetryReader().read(clean);
        assertThat(noisySeries.size()).isEqualTo(cleanSeries.size());

        // Altitude error should have roughly the standard deviation that was asked for. A
        // generator that silently applied no noise -- or ten times too much -- would make T-V5
        // either trivial or impossible, and neither would be visible without measuring it.
        double sumSquares = 0;
        int counted = 0;
        for (int i = 0; i < noisySeries.size(); i++) {
            TelemetrySample n = noisySeries.samples().get(i);
            TelemetrySample c = cleanSeries.samples().get(i);
            if (!n.hasFlag(TelemetrySample.FLAG_GPS_DROPOUT)) {
                double error = n.altitudeGpsM() - c.altitudeGpsM();
                sumSquares += error * error;
                counted++;
            }
        }
        double rms = Math.sqrt(sumSquares / counted);
        System.out.printf("FR-1.4: GPS altitude noise RMS %.2f m (spec sigma 10 m)%n", rms);
        assertThat(rms).isCloseTo(10.0, within(1.5));

        // And the clean spec really is clean, so a test that needs the true signal can have it.
        assertThat(cleanSeries.gpsDropoutCount()).isZero();
    }

    @Test
    @DisplayName("FR-1.4: dropouts occur in runs, not as isolated samples")
    void dropoutsComeInRuns() throws Exception {
        Path file = dir.resolve("dropouts.csv");
        generate(file, new NoiseSpec(8, 10, 0.002, 1.0, 0.02, 4), 13L);
        TelemetrySeries series = new CsvTelemetryReader().read(file);

        assertThat(series.gpsDropoutCount()).isPositive();

        // Measure the run lengths: a GPS receiver that loses lock stays lost for a few samples,
        // and an estimator tuned only on isolated single-sample gaps would be tuned for a fiction.
        int longestRun = 0;
        int current = 0;
        for (TelemetrySample sample : series) {
            if (sample.hasFlag(TelemetrySample.FLAG_GPS_DROPOUT)) {
                current++;
                longestRun = Math.max(longestRun, current);
            } else {
                current = 0;
            }
        }
        assertThat(longestRun).as("dropouts must last several packets").isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("FR-1.4: a dropped packet still carries pressure, so altitude is recoverable")
    void droppedPacketsKeepTheirBarometer() throws Exception {
        Path file = dir.resolve("dropouts.csv");
        generate(file, new NoiseSpec(8, 10, 0.002, 1.0, 0.05, 3), 17L);
        TelemetrySeries series = new CsvTelemetryReader().read(file);

        long dropped = series.samples().stream()
                .filter(s -> s.hasFlag(TelemetrySample.FLAG_GPS_DROPOUT)).count();
        assertThat(dropped).isPositive();
        assertThat(series.samples().stream()
                .filter(s -> s.hasFlag(TelemetrySample.FLAG_GPS_DROPOUT))
                .allMatch(TelemetrySample::hasPressure))
                .as("the barometer keeps working when the GPS fix does not").isTrue();
        assertThat(series.samples().stream()
                .filter(s -> s.hasFlag(TelemetrySample.FLAG_GPS_DROPOUT))
                .allMatch(s -> s.hasFlag(TelemetrySample.FLAG_PRESSURE_ALTITUDE)))
                .as("so their altitude is recovered from pressure").isTrue();
    }

    @Test
    @DisplayName("FR-1.4: the stored truth is what the flight actually did")
    void truthMatchesTheFlight() throws Exception {
        Path file = dir.resolve("flight.csv");
        FlightTruth truth = generate(file, NoiseSpec.none(), 23L);
        BalloonConfig config = config();

        assertThat(truth.seed()).isEqualTo(23L);
        assertThat(truth.parameters()).isEqualTo(FlightParameters.nominal(config));
        assertThat(truth.burstAltitudeM()).isBetween(20_000.0, 40_000.0);
        assertThat(truth.burstEpochUtc()).isNotNull();
        assertThat(Instant.parse(truth.landingEpochUtc())).isAfter(Instant.parse(truth.burstEpochUtc()));
        assertThat(truth.sampleCount()).isGreaterThan(1_000);

        // With no noise the last packet must sit on the true landing point.
        TelemetrySeries series = new CsvTelemetryReader().read(file);
        assertThat(Geodesy.haversineMetres(series.last().position(), truth.landingPoint()))
                .isLessThan(20.0);
        assertThat(truth.landingErrorM(truth.landingPoint())).isEqualTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("FR-1.4: the truth renders as JSON for the flight_log column")
    void truthRendersAsJson() throws Exception {
        FlightTruth truth = generate(dir.resolve("f.csv"), NoiseSpec.none(), 29L);
        String json = SyntheticFlightWriter.truthJson(truth);

        assertThat(json)
                .contains("\"seed\":29")
                .contains("\"ascent_cd\":")
                .contains("\"burst_alt_m\":")
                .contains("\"landing_lat\":")
                .contains("\"sample_count\":");
        // Round-trips through a parser, so the column is queryable rather than just a blob.
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.get("seed").asLong()).isEqualTo(29L);
        assertThat(node.get("ascent_cd").asDouble()).isEqualTo(truth.parameters().ascentCd());
    }

    @Test
    @DisplayName("The file says plainly that it is synthetic")
    void fileIsLabelledSynthetic() throws Exception {
        Path file = dir.resolve("f.csv");
        generate(file, NoiseSpec.standard(), 31L);
        String head = Files.readAllLines(file).get(0);
        assertThat(head).contains("SYNTHETIC").contains("Not an observation");
        assertThat(Files.readAllLines(file).get(1)).contains("seed=31");
    }

    @Test
    @DisplayName("A nonsensical noise spec is rejected where it is built")
    void invalidNoiseSpecRejected() {
        assertThatThrownBy(() -> new NoiseSpec(-1, 10, 0.002, 1, 0.01, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoiseSpec(8, 10, 0.002, 1, 1.5, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dropout probability");
        assertThatThrownBy(() -> new NoiseSpec(8, 10, 0.002, 1, 0.01, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run length");
    }
}
