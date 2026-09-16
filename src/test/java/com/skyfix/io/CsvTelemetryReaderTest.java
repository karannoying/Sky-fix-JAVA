package com.skyfix.io;

import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.DataFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-E5 and T-P4 — telemetry ingest, and specifically what happens to a messy log (FR-1.2).
 *
 * <p>FR-1.2's acceptance criterion is not that a clean log parses. It is that duplicate packet ids
 * and out-of-order timestamps are "counted and surfaced, never silently dropped" — so most of these
 * tests feed the reader exactly the mess a radio link produces and check the accounting.
 */
class CsvTelemetryReaderTest {

    @TempDir
    Path dir;

    private final CsvTelemetryReader reader = new CsvTelemetryReader();

    private Path log(String body) throws Exception {
        Path file = dir.resolve("flight.csv");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("FR-1.2: a clean log parses into an ordered series")
    void cleanLogParses() throws Exception {
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500,95000,288.0
                2026-09-14T04:30:01Z,2,23.2600,77.4127,505,94940,287.9
                2026-09-14T04:30:02Z,3,23.2601,77.4128,510,94880,287.8
                """));

        assertThat(series.size()).isEqualTo(3);
        assertThat(series.hasAnomalies()).isFalse();
        assertThat(series.anomalySummary()).isEqualTo("clean");
        assertThat(series.duration()).hasSeconds(2);
        assertThat(series.first().packetId()).isEqualTo(1);
        assertThat(series.last().altitudeGpsM()).isEqualTo(510.0);
        assertThat(series.first().hasPressure()).isTrue();
        assertThat(series.first().hasTemperature()).isTrue();
        assertThat(series).hasSize(3);
    }

    @Test
    @DisplayName("T-E5: duplicate packet ids are counted, not silently dropped")
    void duplicatePacketIdsAreCounted() throws Exception {
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500,95000,288.0
                2026-09-14T04:30:01Z,2,23.2600,77.4127,505,94940,287.9
                2026-09-14T04:30:01Z,2,23.2600,77.4127,505,94940,287.9
                2026-09-14T04:30:02Z,3,23.2601,77.4128,510,94880,287.8
                2026-09-14T04:30:03Z,1,23.2602,77.4129,515,94820,287.7
                """));

        assertThat(series.size()).as("each packet id appears once").isEqualTo(3);
        assertThat(series.duplicatePacketCount())
                .as("both repeats must be counted").isEqualTo(2);
        assertThat(series.hasAnomalies()).isTrue();
        assertThat(series.anomalySummary()).contains("2 duplicate packet ids");
    }

    @Test
    @DisplayName("T-E5: out-of-order timestamps are counted and flagged, and the series is sorted")
    void outOfOrderTimestampsAreCountedAndSorted() throws Exception {
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500,95000,288.0
                2026-09-14T04:30:03Z,4,23.2603,77.4130,520,94760,287.6
                2026-09-14T04:30:01Z,2,23.2600,77.4127,505,94940,287.9
                2026-09-14T04:30:02Z,3,23.2601,77.4128,510,94880,287.8
                """));

        // Arrival order is t = 0, 3, 1, 2. "Out of order" counts INVERSIONS in the stream -- a
        // packet whose timestamp precedes the one before it -- which is what a replay actually
        // notices as a late packet. Only packet 2 qualifies: it arrives after packet 4 (t=3) with
        // t=1. Packet 3 then arrives at t=2, which is later than packet 2's t=1, so it is in
        // order. Counting displaced-from-sorted-position instead would report three, which would
        // say more about the sort than about the radio link.
        assertThat(series.size()).isEqualTo(4);
        assertThat(series.outOfOrderCount())
                .as("one packet arrived before its predecessor").isEqualTo(1);

        // Sorted for replay...
        assertThat(series.samples()).extracting(TelemetrySample::packetId)
                .containsExactly(1, 2, 3, 4);
        // ...but sorting must not launder the count, which was taken from the file's own order.
        assertThat(series.anomalySummary()).contains("1 out of order");
        assertThat(series.samples().get(1).hasFlag(TelemetrySample.FLAG_OUT_OF_ORDER))
                .as("the offending sample stays flagged individually").isTrue();
        assertThat(series.samples().get(2).hasFlag(TelemetrySample.FLAG_OUT_OF_ORDER))
                .as("a packet that was in order is not flagged").isFalse();
    }

    @Test
    @DisplayName("T-E5: every inversion is counted, not just the first")
    void everyInversionIsCounted() throws Exception {
        // Two independent late arrivals: t = 0, 5, 1, 6, 2. Packets at t=1 and t=2 each follow a
        // later timestamp, so both are inversions.
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500
                2026-09-14T04:30:05Z,2,23.2600,77.4127,505
                2026-09-14T04:30:01Z,3,23.2601,77.4128,510
                2026-09-14T04:30:06Z,4,23.2602,77.4129,515
                2026-09-14T04:30:02Z,5,23.2603,77.4130,520
                """));
        assertThat(series.outOfOrderCount()).isEqualTo(2);
        assertThat(series.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("FR-1.2: a GPS dropout is counted and its altitude derived from pressure")
    void gpsDropoutDerivesPressureAltitude() throws Exception {
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500,95461,288.0
                2026-09-14T04:30:01Z,2,23.2600,77.4127,,54020,255.7
                2026-09-14T04:30:02Z,3,23.2601,77.4128,5010,54000,255.6
                """));

        assertThat(series.gpsDropoutCount()).isEqualTo(1);
        TelemetrySample derived = series.samples().get(1);
        assertThat(derived.hasFlag(TelemetrySample.FLAG_GPS_DROPOUT)).isTrue();
        assertThat(derived.hasFlag(TelemetrySample.FLAG_PRESSURE_ALTITUDE))
                .as("a computed altitude must be distinguishable from a measured one").isTrue();
        // 54,020 Pa is about 5 km in the standard atmosphere.
        assertThat(derived.altitudeGpsM()).isCloseTo(5_000.0, within(60.0));

        assertThat(series.samples().get(0).hasFlag(TelemetrySample.FLAG_PRESSURE_ALTITUDE))
                .as("a sample with a real fix is never marked as derived").isFalse();
    }

    @Test
    @DisplayName("FR-1.2: a dropout with no pressure keeps no altitude rather than inventing one")
    void dropoutWithoutPressureHasNoAltitude() throws Exception {
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500,95461,288.0
                2026-09-14T04:30:01Z,2,23.2600,77.4127,,,
                """));

        TelemetrySample blind = series.samples().get(1);
        assertThat(blind.hasGpsAltitude())
                .as("an absent altitude must stay absent, never default to zero").isFalse();
        assertThat(Double.isNaN(blind.altitudeGpsM())).isTrue();
        assertThat(blind.hasPressure()).isFalse();
        assertThat(series.gpsDropoutCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-1.2: a malformed row is rejected by file:line:reason, and the rest survive")
    void malformedRowsAreReportedNotFatal() throws Exception {
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500,95000,288.0
                not-a-time,2,23.2600,77.4127,505,94940,287.9
                2026-09-14T04:30:02Z,xx,23.2601,77.4128,510,94880,287.8
                2026-09-14T04:30:03Z,4,99.9,77.4129,515,94820,287.7
                2026-09-14T04:30:04Z,5,23.2603,77.4130,520,-5,287.6
                2026-09-14T04:30:05Z,6,23.2604,77.4131,525,94700,287.5
                """));

        assertThat(series.size()).isEqualTo(2);
        assertThat(series.rejections()).hasSize(4);
        String all = String.join("\n", series.rejections());
        assertThat(all).contains("flight.csv:3:").contains("not an ISO-8601 instant");
        assertThat(all).contains("packet_id").contains("not an integer");
        assertThat(all).contains("lat 99.9");
        assertThat(all).contains("pressure_pa").contains("must be positive");
    }

    @Test
    @DisplayName("FR-1.2: columns are found by header name, so extra channels are harmless")
    void headerIsMappedByName() throws Exception {
        TelemetrySeries series = reader.read(log("""
                packet_id,battery_v,lon,epoch_utc,lat,rssi,alt_gps_m
                7,4.1,77.4126,2026-09-14T04:30:00Z,23.2599,-91,500
                8,4.1,77.4127,2026-09-14T04:30:01Z,23.2600,-93,505
                """));

        assertThat(series.size()).isEqualTo(2);
        assertThat(series.first().packetId()).isEqualTo(7);
        assertThat(series.first().latitudeDeg()).isEqualTo(23.2599);
        assertThat(series.first().altitudeGpsM()).isEqualTo(500.0);
        assertThat(series.first().hasPressure())
                .as("a channel the log does not carry is absent, not zero").isFalse();
    }

    @Test
    @DisplayName("T-E3: an unusable log fails cleanly, naming what it found")
    void unusableLogsFailCleanly() throws Exception {
        assertThatThrownBy(() -> reader.read(log("""
                epoch_utc,packet_id,lat,lon
                nonsense,1,2,3
                """)))
                .isInstanceOfSatisfying(DataFormatException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(3);
                    assertThat(e.getMessage()).contains("no usable samples");
                });

        assertThatThrownBy(() -> reader.read(log("battery_v,rssi\n4.1,-91\n")))
                .isInstanceOf(DataFormatException.class)
                .hasMessageContaining("header must name");

        assertThatThrownBy(() -> reader.read(log("")))
                .isInstanceOf(DataFormatException.class)
                .hasMessageContaining("no header row");

        assertThatThrownBy(() -> reader.read(dir.resolve("absent.csv")))
                .isInstanceOf(DataFormatException.class)
                .hasMessageContaining("cannot be read");
    }

    @Test
    @DisplayName("FR-1.2: comments and blank lines are skipped without comment")
    void commentsAndBlanksAreSkipped() throws Exception {
        TelemetrySeries series = reader.read(log("""
                # HabSat-1 bench log
                epoch_utc,packet_id,lat,lon,alt_gps_m

                2026-09-14T04:30:00Z,1,23.2599,77.4126,500

                # radio reset here
                2026-09-14T04:30:01Z,2,23.2600,77.4127,505
                """));
        assertThat(series.size()).isEqualTo(2);
        assertThat(series.rejections()).isEmpty();
    }

    @Test
    @DisplayName("A timestamp without a trailing Z is accepted, since real logs write both")
    void bareTimestampsAreAccepted() throws Exception {
        TelemetrySeries series = reader.read(log("""
                epoch_utc,packet_id,lat,lon,alt_gps_m
                2026-09-14T04:30:00,1,23.2599,77.4126,500
                2026-09-14T04:30:01Z,2,23.2600,77.4127,505
                """));
        assertThat(series.size()).isEqualTo(2);
        assertThat(series.first().epochUtc()).isEqualTo(Instant.parse("2026-09-14T04:30:00Z"));
    }

    @Test
    @Tag("perf")
    @DisplayName("T-P4: 10,000 samples parse in under 3 s")
    void tenThousandSamplesInsideThreeSeconds() throws Exception {
        StringBuilder sb = new StringBuilder(
                "epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k\n");
        Instant start = Instant.parse("2026-09-14T04:30:00Z");
        for (int i = 0; i < 10_000; i++) {
            sb.append(start.plusSeconds(i)).append(',').append(i).append(',')
              .append(23.2599 + i * 1e-5).append(',')
              .append(77.4126 + i * 2e-5).append(',')
              .append(500 + i * 3.0).append(',')
              .append(95_000 * Math.exp(-i * 3.0 / 7_640.0)).append(',')
              .append(288.0 - i * 0.002).append('\n');
        }
        Path file = dir.resolve("big.csv");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        long begin = System.nanoTime();
        TelemetrySeries series = reader.read(file);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;

        System.out.printf("T-P4: %,d samples parsed in %d ms (budget 3,000 ms)%n",
                series.size(), elapsedMs);
        assertThat(series.size()).isEqualTo(10_000);
        assertThat(elapsedMs).isLessThan(3_000L);
    }
}
