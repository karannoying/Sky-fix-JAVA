package com.skyfix.app;

import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.SkyfixException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DS-6 — the evaluation set's definition and its reproducibility (FR-1.4, BLUEPRINT §10).
 *
 * <p>The committed {@code seeds.csv} <em>is</em> the dataset: the telemetry regenerates from it and
 * is not stored in the repository. So the properties worth asserting are that the seed file is
 * well-formed and complete, and that regeneration really is byte-identical — if either failed,
 * every T-V5 and T-V6 number would rest on data nobody could reproduce.
 */
class SynthServiceTest {

    @TempDir
    Path dir;

    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);

    private final SynthService service = new SynthService();

    /** The first few flights of DS-6, so the suite stays quick. */
    private List<SynthService.SeedRow> firstFew(int count) throws SkyfixException {
        return service.readSeeds().subList(0, count);
    }

    private static BalloonConfig config() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g").payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4).gas(LiftGas.HELIUM)
                .build();
    }

    private static SimSettings settings() throws Exception {
        return SimSettings.builder().groundElevationM(LAUNCH.altitudeM()).build();
    }

    @Test
    @DisplayName("DS-6: the committed seed file defines exactly the 20 flights O3 requires")
    void seedFileDefinesTheSet() throws Exception {
        List<SynthService.SeedRow> seeds = service.readSeeds();

        assertThat(seeds).hasSize(SynthService.FLIGHT_COUNT);
        assertThat(seeds).extracting(SynthService.SeedRow::name).doesNotHaveDuplicates();
        assertThat(seeds).extracting(SynthService.SeedRow::seed).doesNotHaveDuplicates();
        for (SynthService.SeedRow row : seeds) {
            assertThat(row.name()).startsWith("ds6-flight-");
            assertThat(Instant.parse(row.launchEpochUtc())).isNotNull();
        }
    }

    @Test
    @DisplayName("DS-6: the seed file says what it is and why it exists")
    void seedFileIsSelfDescribing() throws Exception {
        // A dataset definition nobody can interpret is not a definition. This is the file a
        // reader of the report will open first.
        String text = Files.readString(SynthService.SEEDS);
        assertThat(text)
                .contains("DS-6")
                .contains("FR-1.4")
                .contains("regenerates")
                .contains("no real 30 km or 45 km flight log exists");
    }

    @Test
    @DisplayName("FR-1.4: regenerating the set reproduces byte-identical telemetry")
    void regenerationIsByteIdentical() throws Exception {
        // A subset, because generating twenty flights twice is a minute of CPU for a property
        // that fails identically at three.
        var seeds = firstFew(3);
        var a = service.generate(seeds, dir.resolve("first"), config(), settings(), LAUNCH,
                Optional.empty(), NoiseSpec.standard());
        var b = service.generate(seeds, dir.resolve("second"), config(), settings(), LAUNCH,
                Optional.empty(), NoiseSpec.standard());

        assertThat(a).hasSize(3);
        for (int i = 0; i < a.size(); i++) {
            assertThat(Files.readAllBytes(a.get(i).file()))
                    .as("%s must regenerate byte for byte", a.get(i).name())
                    .isEqualTo(Files.readAllBytes(b.get(i).file()));
            assertThat(b.get(i).truth()).isEqualTo(a.get(i).truth());
        }
    }

    @Test
    @DisplayName("DS-6: the flights differ from each other, spanning the parameter space")
    void flightsAreDistinctAndSpread() throws Exception {
        var flights = service.generate(firstFew(6), dir.resolve("set"), config(), settings(),
                LAUNCH, Optional.empty(), NoiseSpec.standard());

        // An evaluation set whose flights are near-identical would say nothing about how the
        // estimator handles the range of real flights.
        assertThat(flights).extracting(f -> f.truth().parameters().ascentCd())
                .doesNotHaveDuplicates();
        double minBurst = flights.stream().mapToDouble(f -> f.truth().burstAltitudeM()).min()
                .orElseThrow();
        double maxBurst = flights.stream().mapToDouble(f -> f.truth().burstAltitudeM()).max()
                .orElseThrow();
        assertThat(maxBurst - minBurst)
                .as("burst altitudes must span a meaningful range").isGreaterThan(1_000.0);
        assertThat(flights).allSatisfy(f -> {
            assertThat(f.truth().sampleCount()).isGreaterThan(1_000);
            assertThat(f.file()).exists();
        });
    }

    @Test
    @DisplayName("DS-6: the truth manifest records every flight's parameters")
    void manifestRecordsTruth() throws Exception {
        var flights = service.generate(firstFew(3), dir.resolve("set"), config(), settings(),
                LAUNCH, Optional.empty(), NoiseSpec.standard());
        Path manifest = dir.resolve("truth-manifest.csv");
        service.writeManifest(manifest, flights);

        List<String> lines = Files.readAllLines(manifest);
        assertThat(lines).hasSize(4); // header plus three flights
        assertThat(lines.get(0)).contains("ascent_cd").contains("burst_alt_m")
                .contains("landing_lat");
        assertThat(lines.get(1)).startsWith(flights.get(0).name() + ",");
    }
}
