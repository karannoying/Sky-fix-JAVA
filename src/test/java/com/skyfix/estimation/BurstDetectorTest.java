package com.skyfix.estimation;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.FlightTruth;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.io.CsvTelemetryReader;
import com.skyfix.io.SyntheticFlightWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-E2 — burst detection under realistic noise and dropouts (FR-3.4).
 *
 * <p>The acceptance criteria are specific and this test checks each: detected within 5 s and 150 m
 * with a 10 m GPS error, and <em>zero</em> false positives across a 3 s dropout. The detector is
 * driven from the same synthetic flights the estimator will use, through the same reader, so it is
 * exercised on the data it will actually see rather than on a hand-built ramp.
 */
class BurstDetectorTest {

    @TempDir
    Path dir;

    private static final Ussa1976Atmosphere ATMOSPHERE = new Ussa1976Atmosphere();
    private static final ConstantWindField WIND = new ConstantWindField(12.0, -4.0);
    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);
    private static final Instant EPOCH = Instant.parse("2026-09-14T04:30:00Z");

    private static BalloonConfig config() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g").payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4).gas(LiftGas.HELIUM)
                .build();
    }

    private record Flight(TelemetrySeries series, FlightTruth truth) {
    }

    private Flight flight(NoiseSpec noise, long seed) throws Exception {
        BalloonConfig config = config();
        Path file = dir.resolve("flight-" + seed + ".csv");
        FlightTruth truth = new SyntheticFlightWriter(ATMOSPHERE, WIND).write(file, config,
                FlightParameters.nominal(config),
                SimSettings.builder().groundElevationM(LAUNCH.altitudeM()).build(),
                LAUNCH, EPOCH, noise, seed);
        return new Flight(new CsvTelemetryReader().read(file), truth);
    }

    private static Optional<BurstEvent> detect(TelemetrySeries series) {
        BurstDetector detector = new BurstDetector();
        for (TelemetrySample sample : series) {
            Optional<BurstEvent> event = detector.observe(sample);
            if (event.isPresent()) {
                return event;
            }
        }
        return detector.burst();
    }

    @Test
    @DisplayName("T-E2: burst is detected within 5 s and 150 m at 10 m GPS noise")
    void burstDetectedInsideTolerance() throws Exception {
        // Several seeds, because a detector that happens to work on one noise realisation has not
        // been shown to work.
        for (long seed : new long[]{1L, 2L, 3L, 5L, 8L, 13L}) {
            Flight flight = flight(NoiseSpec.standard(), seed);
            BurstEvent event = detect(flight.series()).orElseThrow(() ->
                    new AssertionError("no burst detected for seed " + seed));

            Instant trueBurst = Instant.parse(flight.truth().burstEpochUtc());
            double timeErrorS = Math.abs(
                    Duration.between(trueBurst, event.epochUtc()).toMillis() / 1000.0);
            double altitudeErrorM =
                    Math.abs(event.altitudeM() - flight.truth().burstAltitudeM());

            System.out.printf("T-E2 seed %2d: burst at %.0f m, time error %.1f s, "
                            + "altitude error %.0f m, confirmed %.1f s later%n",
                    seed, event.altitudeM(), timeErrorS, altitudeErrorM,
                    event.detectionLag().toMillis() / 1000.0);

            assertThat(timeErrorS).as("seed %d time error", seed).isLessThanOrEqualTo(5.0);
            assertThat(altitudeErrorM).as("seed %d altitude error", seed)
                    .isLessThanOrEqualTo(150.0);
        }
    }

    @Test
    @DisplayName("T-E2: zero false positives across dropouts during the ascent")
    void noFalsePositiveAcrossDropouts() throws Exception {
        // The failure mode this guards: when a fix returns, or altitude switches to a
        // pressure-derived value, the step between the two sources looks like a sudden descent.
        // A detector that fired there would report burst thousands of metres too low.
        for (long seed : new long[]{101L, 103L, 107L, 109L}) {
            Flight flight = flight(new NoiseSpec(8, 10, 0.002, 1.0, 0.03, 3), seed);
            assertThat(flight.series().gpsDropoutCount())
                    .as("this test is only meaningful if dropouts actually occurred")
                    .isPositive();

            BurstDetector detector = new BurstDetector();
            Instant trueBurst = Instant.parse(flight.truth().burstEpochUtc());
            for (TelemetrySample sample : flight.series()) {
                Optional<BurstEvent> event = detector.observe(sample);
                if (event.isPresent()) {
                    // Any detection before the real burst is a false positive.
                    assertThat(event.get().epochUtc())
                            .as("seed %d fired early, during the ascent", seed)
                            .isAfterOrEqualTo(trueBurst.minusSeconds(5));
                    break;
                }
            }
            assertThat(detector.hasBurst()).as("seed %d must still find the real burst", seed)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("T-E2: a long dropout straddling the ascent does not trigger a detection")
    void longDropoutDoesNotTrigger() throws Exception {
        // A deliberately punishing case: a five-sample outage at 1 Hz, well beyond the three
        // seconds T-E2 names.
        Flight flight = flight(new NoiseSpec(8, 10, 0.002, 1.0, 0.05, 5), 211L);
        Instant trueBurst = Instant.parse(flight.truth().burstEpochUtc());

        BurstDetector detector = new BurstDetector();
        Instant firstDetection = null;
        for (TelemetrySample sample : flight.series()) {
            Optional<BurstEvent> event = detector.observe(sample);
            if (event.isPresent()) {
                firstDetection = event.get().epochUtc();
                break;
            }
        }
        assertThat(firstDetection).isNotNull();
        assertThat(firstDetection).isAfterOrEqualTo(trueBurst.minusSeconds(5));
    }

    @Test
    @DisplayName("FR-3.4: the burst is reported at apogee, not where it was confirmed")
    void burstIsAttributedToApogeeNotToConfirmation() throws Exception {
        Flight flight = flight(NoiseSpec.none(), 41L);
        BurstEvent event = detect(flight.series()).orElseThrow();

        // Confirmation is necessarily later than the event, and at thirty metres per second of
        // early descent that gap is hundreds of metres. Reporting the confirming sample would
        // blow the 150 m tolerance on its own, so the two epochs must differ.
        assertThat(event.detectedAtUtc()).isAfter(event.epochUtc());
        assertThat(event.detectionLag()).isPositive();

        double altitudeError = Math.abs(event.altitudeM() - flight.truth().burstAltitudeM());
        assertThat(altitudeError).as("apogee attribution keeps the altitude error small")
                .isLessThan(150.0);
        assertThat(event.toString()).contains("burst at").contains("confirmed");
    }

    @Test
    @DisplayName("FR-3.4: nothing fires before the balloon has convincingly climbed")
    void neverFiresBeforeAscent() {
        // Sitting on the pad with a noisy fix: no sustained climb, so nothing can have burst.
        BurstDetector detector = new BurstDetector();
        Instant t = EPOCH;
        java.util.SplittableRandom random = new java.util.SplittableRandom(5L);
        for (int i = 0; i < 200; i++) {
            double jitter = (random.nextDouble() - 0.5) * 30.0;
            detector.observe(new TelemetrySample(t.plusSeconds(i), i, 23.2599, 77.4126,
                    500.0 + jitter, 95_461.0, 288.0, 0));
        }
        assertThat(detector.hasBurst()).isFalse();
    }

    @Test
    @DisplayName("FR-3.4: a burst is reported once, and only once")
    void burstIsReportedOnce() throws Exception {
        Flight flight = flight(NoiseSpec.standard(), 43L);
        BurstDetector detector = new BurstDetector();
        int detections = 0;
        for (TelemetrySample sample : flight.series()) {
            if (detector.observe(sample).isPresent()) {
                detections++;
            }
        }
        assertThat(detections).isEqualTo(1);
        assertThat(detector.burst()).isPresent();

        // And a reset makes the instance reusable for a second flight.
        detector.reset();
        assertThat(detector.hasBurst()).isFalse();
        assertThat(detector.burst()).isEmpty();
        assertThat(detector.currentVerticalRateMs()).isNaN();
    }

    @Test
    @DisplayName("FR-3.4: the smoothed rate tracks the flight, positive up and negative down")
    void smoothedRateTracksTheFlight() throws Exception {
        Flight flight = flight(NoiseSpec.standard(), 47L);
        Instant trueBurst = Instant.parse(flight.truth().burstEpochUtc());

        // Burst happens mid-step, so its epoch is not a whole second and no sample matches
        // trueBurst plus an exact offset. Sampling the nearest packet is the right comparison.
        Instant ascentProbe = trueBurst.minusSeconds(120);
        BurstDetector ascending = new BurstDetector();
        double rateWellBeforeBurst = Double.NaN;
        for (TelemetrySample sample : flight.series()) {
            ascending.observe(sample);
            if (!sample.epochUtc().isBefore(ascentProbe)) {
                rateWellBeforeBurst = ascending.currentVerticalRateMs();
                break;
            }
        }

        // A second detector for the descent: the first stops updating once it has fired, since a
        // detector's job ends at the event it was looking for.
        Instant descentStart = trueBurst.plusSeconds(60);
        Instant descentProbe = trueBurst.plusSeconds(120);
        BurstDetector descending = new BurstDetector();
        double rateWellAfterBurst = Double.NaN;
        for (TelemetrySample sample : flight.series()) {
            if (sample.epochUtc().isBefore(descentStart)) {
                continue;
            }
            descending.observe(sample);
            if (!sample.epochUtc().isBefore(descentProbe)) {
                rateWellAfterBurst = descending.currentVerticalRateMs();
                break;
            }
        }

        System.out.printf("FR-3.4: smoothed rate %.1f m/s ascending, %.1f m/s descending%n",
                rateWellBeforeBurst, rateWellAfterBurst);
        // Ascent is a few m/s up; early descent under a parachute in thin air is far faster down.
        assertThat(rateWellBeforeBurst).isBetween(2.0, 12.0);
        assertThat(rateWellAfterBurst).isLessThan(-8.0);
    }

    @Test
    @DisplayName("Samples with no altitude at all are skipped, not fed in as a step")
    void samplesWithoutAltitudeAreSkipped() {
        BurstDetector detector = new BurstDetector();
        Instant t = EPOCH;
        for (int i = 0; i < 20; i++) {
            detector.observe(new TelemetrySample(t.plusSeconds(i), i, 23.26, 77.41,
                    500.0 + i * 5.0, 95_000, 288, 0));
        }
        double before = detector.currentVerticalRateMs();
        // A sample with neither a fix nor a barometer carries no vertical information.
        detector.observe(new TelemetrySample(t.plusSeconds(20), 20, 23.26, 77.41,
                Double.NaN, Double.NaN, Double.NaN, TelemetrySample.FLAG_GPS_DROPOUT));
        assertThat(detector.currentVerticalRateMs())
                .as("the window is unchanged by a sample it cannot use")
                .isEqualTo(before);
        assertThat(detector.hasBurst()).isFalse();
    }

    @Test
    @DisplayName("Nonsensical tuning is rejected at construction")
    void invalidTuningRejected() {
        assertThatThrownBy(() -> new BurstDetector(2, 3, -2.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("window");
        assertThatThrownBy(() -> new BurstDetector(9, 0, -2.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirmations");
        assertThatThrownBy(() -> new BurstDetector(9, 3, 2.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BurstDetector(9, 3, -2.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
