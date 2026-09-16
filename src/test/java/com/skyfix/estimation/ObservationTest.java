package com.skyfix.estimation;

import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Preparing telemetry for the filter (FR-3.1). */
class ObservationTest {

    private static final Instant T0 = Instant.parse("2026-09-14T04:30:00Z");

    @Test
    @DisplayName("the vertical rate is a central difference, one-sided at the ends")
    void derivesVerticalRate() {
        // A constant 5 m/s climb, sampled at 1 Hz.
        TelemetrySeries series = series(5.0, 6);
        List<Observation> stream = Observation.streamOf(series);

        assertThat(stream).hasSize(6);
        for (Observation o : stream.subList(1, 5)) {
            assertThat(o.verticalRateMs()).isCloseTo(5.0, within(1e-9));
            assertThat(o.rateIntervalSeconds()).isEqualTo(2.0);
        }
        // The ends have only one neighbour, so the baseline is half as long.
        assertThat(stream.get(0).verticalRateMs()).isCloseTo(5.0, within(1e-9));
        assertThat(stream.get(0).rateIntervalSeconds()).isEqualTo(1.0);
        assertThat(stream.get(5).rateIntervalSeconds()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a sample with no altitude yields no rate rather than a fabricated one")
    void dropsTheRateWhenAnAltitudeIsMissing() {
        List<TelemetrySample> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double altitude = i == 1 ? Double.NaN : 500.0 + 5.0 * i;
            samples.add(new TelemetrySample(T0.plusSeconds(i), i, 23.0, 77.0, altitude,
                    Double.NaN, Double.NaN, 0));
        }
        List<Observation> stream = Observation.streamOf(new TelemetrySeries(samples, 0, 0, 1, List.of()));

        // Index 0 and 2 both border the hole, so neither can be differenced.
        assertThat(stream.get(0).hasVerticalRate()).isFalse();
        assertThat(stream.get(2).hasVerticalRate()).isFalse();
        assertThat(stream.get(1).hasAltitude()).isFalse();
        assertThat(stream.get(3).hasVerticalRate()).isTrue();
    }

    @Test
    @DisplayName("a single sample carries no rate at all")
    void singleSampleHasNoRate() {
        List<Observation> stream = Observation.streamOf(series(5.0, 1));
        assertThat(stream.get(0).hasVerticalRate()).isFalse();
        assertThat(Double.isNaN(stream.get(0).rateIntervalSeconds())).isTrue();
    }

    private static TelemetrySeries series(double rateMs, int count) {
        List<TelemetrySample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            samples.add(new TelemetrySample(T0.plus(Duration.ofSeconds(i)), i, 23.0, 77.0,
                    500.0 + rateMs * i, Double.NaN, Double.NaN, 0));
        }
        return new TelemetrySeries(samples, 0, 0, 0, List.of());
    }
}
