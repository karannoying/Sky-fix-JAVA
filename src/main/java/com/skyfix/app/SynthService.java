package com.skyfix.app;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.SoundingWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.atmos.WindField;
import com.skyfix.core.flight.DispersionSampler;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.FlightTruth;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.DataFormatException;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.io.SyntheticFlightWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Generates the DS-6 evaluation set: a fixed number of synthetic flights with known truth
 * (FR-1.4, DS-6).
 *
 * <p>This is the set O3 and O4 are scored on, because no real 30 km or 45 km flight log exists yet.
 * Everything about it is reproducible from {@code data/truth/seeds.csv}: each row is one flight's
 * seed, and the seed determines both the truth parameters drawn for that flight and the noise
 * applied to its telemetry. Deleting the generated files and re-running reproduces them byte for
 * byte.
 *
 * <p>Truth parameters come from the same {@link DispersionSampler} the ensemble uses, so the
 * flights are spread over the parameter space the predictor disperses across — rather than over
 * some separate distribution that would make the evaluation easier or harder than the real task.
 */
public final class SynthService {

    private static final Logger LOG = Logger.getLogger(SynthService.class.getName());

    /** Where the seed list lives; the file is the definition of DS-6. */
    public static final Path SEEDS = Path.of("data", "truth", "seeds.csv");

    /** How many flights DS-6 holds, per O3 and T-V5. */
    public static final int FLIGHT_COUNT = 20;

    private final AtmosphereModel atmosphere = new Ussa1976Atmosphere();

    /**
     * Generates every flight named in the seed file.
     *
     * @param outputDir where the telemetry logs are written
     * @param config    the balloon configuration the flights fly
     * @param settings  integration settings
     * @param launch    the launch position
     * @param wind      the wind field, or empty for a constant test wind
     * @param noise     how the telemetry is corrupted
     * @return one result per flight, in seed-file order
     * @throws SkyfixException if the seed file cannot be read or a flight cannot be generated
     */
    public List<GeneratedFlight> generateAll(Path outputDir, BalloonConfig config,
                                             SimSettings settings, GeoPoint launch,
                                             Optional<SoundingWindField> wind, NoiseSpec noise)
            throws SkyfixException {
        return generate(readSeeds(), outputDir, config, settings, launch, wind, noise);
    }

    /**
     * Generates a given list of flights.
     *
     * <p>Takes the seed rows explicitly so a caller can regenerate a subset — one flight that
     * needs re-examining, or a handful in a test — without the whole set. Passing
     * {@link #readSeeds()} reproduces DS-6 exactly.
     *
     * @param seeds     the flights to generate
     * @param outputDir where the telemetry logs are written
     * @param config    the balloon configuration the flights fly
     * @param settings  integration settings
     * @param launch    the launch position
     * @param wind      the wind field, or empty for a constant test wind
     * @param noise     how the telemetry is corrupted
     * @return one result per flight, in the given order
     * @throws SkyfixException if a flight cannot be generated
     */
    public List<GeneratedFlight> generate(List<SeedRow> seeds, Path outputDir,
                                          BalloonConfig config, SimSettings settings,
                                          GeoPoint launch, Optional<SoundingWindField> wind,
                                          NoiseSpec noise) throws SkyfixException {
        if (seeds.isEmpty()) {
            throw new DataFormatException(SEEDS.toString(), 0, "no flights to generate");
        }
        WindField windField = wind.map(w -> (WindField) w)
                .orElseGet(() -> new ConstantWindField(12.0, -4.0));

        // One dispersion draw covering the whole set, so the flights span the parameter space
        // rather than each being an independent draw that might cluster.
        DispersionSpec spec = DispersionSpec.preflightDefault(config);
        FlightParameters[] truthSet = new DispersionSampler(spec)
                .sample(seeds.size(), seeds.get(0).seed());

        SyntheticFlightWriter writer = new SyntheticFlightWriter(atmosphere, windField);
        List<GeneratedFlight> generated = new ArrayList<>();

        for (int i = 0; i < seeds.size(); i++) {
            SeedRow row = seeds.get(i);
            Path file = outputDir.resolve(row.name() + ".csv");
            FlightTruth truth = writer.write(file, config, truthSet[i], settings, launch,
                    Instant.parse(row.launchEpochUtc()), noise, row.seed());
            generated.add(new GeneratedFlight(row.name(), file, truth));
            LOG.info(() -> "generated " + row.name() + ": burst "
                    + Math.round(truth.burstAltitudeM()) + " m, " + truth.sampleCount()
                    + " samples");
        }
        return generated;
    }

    /**
     * Reads the seed file that defines DS-6.
     *
     * @return one row per flight
     * @throws SkyfixException if the file is missing or malformed
     */
    public List<SeedRow> readSeeds() throws SkyfixException {
        List<String> lines;
        try {
            lines = Files.readAllLines(SEEDS, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataFormatException(SEEDS.toString(), 0,
                    "seed file cannot be read: " + e.getMessage()
                            + " — DS-6 is defined by this file, so it must be committed", e);
        }
        List<SeedRow> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("name,")) {
                continue;
            }
            String[] f = line.split(",");
            if (f.length < 3) {
                throw new DataFormatException(SEEDS.toString(), i + 1,
                        "expected name,seed,launch_epoch_utc; got \"" + line + "\"");
            }
            try {
                rows.add(new SeedRow(f[0].strip(), Long.parseLong(f[1].strip()), f[2].strip()));
            } catch (NumberFormatException e) {
                throw new DataFormatException(SEEDS.toString(), i + 1,
                        "seed \"" + f[1] + "\" is not a number");
            }
        }
        if (rows.isEmpty()) {
            throw new DataFormatException(SEEDS.toString(), 0, "seed file holds no flights");
        }
        return rows;
    }

    /**
     * Writes a manifest of the generated set, so the truth is inspectable without a database.
     *
     * @param path   the file to write
     * @param flights the generated flights
     * @throws SkyfixException if the file cannot be written
     */
    public void writeManifest(Path path, List<GeneratedFlight> flights) throws SkyfixException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("name,seed,free_lift_kg,ascent_cd,burst_diameter_m,chute_cd,wind_scale,"
                        + "burst_alt_m,landing_lat,landing_lon,sample_count");
                out.newLine();
                for (GeneratedFlight flight : flights) {
                    FlightTruth t = flight.truth();
                    FlightParameters p = t.parameters();
                    out.write(String.format(Locale.ROOT,
                            "%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.1f,%.7f,%.7f,%d",
                            flight.name(), t.seed(), p.freeLiftKg(), p.ascentCd(),
                            p.burstDiameterM(), p.chuteCd(), p.windScale(),
                            t.burstAltitudeM(), t.landingLatDeg(), t.landingLonDeg(),
                            t.sampleCount()));
                    out.newLine();
                }
            }
        } catch (IOException e) {
            throw new com.skyfix.domain.error.PersistenceException(
                    "cannot write truth manifest to " + path, e);
        }
    }

    /**
     * One row of the seed file.
     *
     * @param name           the flight's name, used as its file name
     * @param seed           the seed that reproduces it
     * @param launchEpochUtc when the flight launches, ISO-8601 UTC
     */
    public record SeedRow(String name, long seed, String launchEpochUtc) {
    }

    /**
     * One generated flight.
     *
     * @param name  the flight's name
     * @param file  where its telemetry was written
     * @param truth what it really did
     */
    public record GeneratedFlight(String name, Path file, FlightTruth truth) {
    }
}
