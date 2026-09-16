package com.skyfix.estimation;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;
import com.skyfix.io.CsvTelemetryReader;
import com.skyfix.io.SyntheticFlightWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pooling independent filters so the band means something (FR-3.2).
 *
 * <p>The claim under test is narrow and specific: a bank's band is wider than any one member's,
 * because it contains the members' disagreement. That disagreement is the dominant error, and a
 * single filter cannot see it.
 */
class FilterBankTest {

    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);
    private static final Instant LAUNCH_EPOCH = Instant.parse("2026-09-14T04:30:00Z");
    private static final FlightParameters TRUTH =
            new FlightParameters(1.0119, 0.4409, 7.7528, 1.3050, 1.0);

    private static BalloonConfig config;
    private static SimSettings settings;
    private static FlightSimulator simulator;

    @BeforeAll
    static void setUp() throws SkyfixException {
        config = BalloonConfig.builder()
                .name("test-1200g")
                .payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0)
                .freeLiftKg(1.1).ascentCd(0.45)
                .chuteAreaM2(1.0).chuteCd(1.4)
                .gas(LiftGas.HELIUM)
                .build();
        settings = SimSettings.builder().groundElevationM(500.0).build();
        simulator = new FlightSimulator(new Ussa1976Atmosphere(),
                new ConstantWindField(12.0, -4.0));
    }

    private static ParticleFilter.Builder template() throws SkyfixException {
        return ParticleFilter.builder()
                .config(config)
                .simulator(simulator)
                .settings(settings)
                .prior(DispersionSpec.preflightDefault(config))
                .measurementModel(GaussianMeasurementModel.standard()
                        .withWindDrift(LAUNCH, 0.20));
    }

    private static Path writeFlight(Path dir) throws SkyfixException {
        Path log = dir.resolve("flight.csv");
        new SyntheticFlightWriter(new Ussa1976Atmosphere(), new ConstantWindField(12.0, -4.0))
                .write(log, config, TRUTH, settings, LAUNCH, LAUNCH_EPOCH, NoiseSpec.standard(),
                        20260914L);
        return log;
    }

    @Test
    @DisplayName("the pooled band is wider than any single member's, because it holds their disagreement")
    void poolIsWiderThanItsMembers(@TempDir Path dir) throws Exception {
        Path log = writeFlight(dir);
        TelemetrySeries series = new CsvTelemetryReader().read(log);
        List<Observation> stream = Observation.streamOf(series);

        try (FilterBank bank = FilterBank.of(template(), config, 6, 180, 20260914L, 4)) {
            BurstDetector detector = new BurstDetector();
            bank.start(LAUNCH, stream.get(0).epochUtc());
            for (int i = 0; i < stream.size(); i += 60) {
                for (int j = i; j < Math.min(i + 60, series.size()); j++) {
                    if (detector.observe(series.samples().get(j)).isPresent()) {
                        bank.burstObserved();
                    }
                }
                bank.update(stream.get(i));
            }

            Posterior pooled = bank.posterior();
            for (String name : pooled.parameters().keySet()) {
                double widest = bank.filters().stream()
                        .mapToDouble(f -> f.posterior().band(name).width())
                        .max().orElseThrow();
                assertThat(pooled.band(name).width())
                        .as("pooled band for %s against the widest member's", name)
                        .isGreaterThanOrEqualTo(widest);
            }
            assertThat(pooled.particleCount()).isEqualTo(180);
        }
    }

    @Test
    @DisplayName("members are independently seeded, so they do not all land on the same answer")
    void membersDisagree(@TempDir Path dir) throws Exception {
        Path log = writeFlight(dir);
        TelemetrySeries series = new CsvTelemetryReader().read(log);
        List<Observation> stream = Observation.streamOf(series);

        try (FilterBank bank = FilterBank.of(template(), config, 6, 180, 20260914L, 4)) {
            bank.start(LAUNCH, stream.get(0).epochUtc());
            for (int i = 0; i < stream.size(); i += 60) {
                bank.update(stream.get(i));
            }

            // If the members agreed exactly, the seeds would not be independent and the whole
            // construction would be measuring nothing.
            double low = Double.MAX_VALUE;
            double high = -Double.MAX_VALUE;
            for (ParticleFilter filter : bank.filters()) {
                double median = filter.posterior().band(Posterior.ASCENT_CD).median();
                low = Math.min(low, median);
                high = Math.max(high, median);
            }
            assertThat(high - low)
                    .as("spread of the members' ascent-Cd medians")
                    .isGreaterThan(0.0);
        }
    }

    @Test
    @DisplayName("the same seed reproduces the same pooled posterior")
    void isReproducible(@TempDir Path dir) throws Exception {
        Path log = writeFlight(dir);
        List<Observation> stream = Observation.streamOf(new CsvTelemetryReader().read(log));

        Posterior first = runBank(stream, 20260914L);
        Posterior second = runBank(stream, 20260914L);
        for (String name : first.parameters().keySet()) {
            assertThat(second.band(name).median())
                    .as("median of %s is identical across runs despite the thread pool", name)
                    .isEqualTo(first.band(name).median());
            assertThat(second.band(name).p05()).isEqualTo(first.band(name).p05());
            assertThat(second.band(name).p95()).isEqualTo(first.band(name).p95());
        }
    }

    @Test
    @DisplayName("a different seed gives a different bank, so the scatter is real")
    void differentSeedsDiffer(@TempDir Path dir) throws Exception {
        Path log = writeFlight(dir);
        List<Observation> stream = Observation.streamOf(new CsvTelemetryReader().read(log));

        Posterior first = runBank(stream, 20260914L);
        Posterior second = runBank(stream, 77777L);
        assertThat(second.band(Posterior.ASCENT_CD).median())
                .isNotEqualTo(first.band(Posterior.ASCENT_CD).median());
    }

    private static Posterior runBank(List<Observation> stream, long seed) throws SkyfixException {
        try (FilterBank bank = FilterBank.of(template(), config, 4, 120, seed, 4)) {
            bank.start(LAUNCH, stream.get(0).epochUtc());
            for (int i = 0; i < stream.size(); i += 80) {
                bank.update(stream.get(i));
            }
            return bank.posterior();
        }
    }

    @Test
    @DisplayName("a particle budget that cannot be split is refused, naming the field")
    void refusesAnUnsplittableBudget() throws Exception {
        assertThatThrownBy(() -> FilterBank.of(template(), config, 8, 8, 1L, 4))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("particles each");
        assertThatThrownBy(() -> FilterBank.of(template(), config, 0, 500, 1L, 4))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("filter_count");
    }

    @Test
    @DisplayName("the pool is sized to the work and shut down on close")
    void poolIsBoundedAndClosed() throws Exception {
        try (FilterBank bank = FilterBank.of(template(), config, 3, 90, 1L, 16)) {
            // More threads than filters is waste, not speed.
            assertThat(bank.threadCount()).isEqualTo(3);
            assertThat(bank.filterCount()).isEqualTo(3);
        }
        int before = Thread.activeCount();
        try (FilterBank bank = FilterBank.of(template(), config, 4, 120, 1L, 4)) {
            assertThat(bank.filterCount()).isEqualTo(4);
        }
        // Closing must reclaim the threads rather than leaving a pool alive per replay.
        assertThat(Thread.activeCount()).isLessThanOrEqualTo(before + 1);
    }
}
