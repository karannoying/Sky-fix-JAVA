package com.skyfix.app;

import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.PredictionError;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.io.ConfigLoader;
import com.skyfix.io.CsvTelemetryReader;
import com.skyfix.persistence.BalloonConfigDao;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.MissionDao;
import com.skyfix.persistence.SchemaInitializer;
import com.skyfix.persistence.StoredBalloonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-V6: does watching the flight actually improve the landing prediction? (O4, FR-4.1)
 *
 * <p>For each DS-6 flight: freeze a pre-flight footprint from the catalogue configuration alone,
 * replay the telemetry, and score both against where the payload actually landed. The blueprint
 * asks for a median reduction of at least 30%.
 *
 * <p>The reduction is scored <strong>at burst</strong>, not at the final update. Burst is the
 * operationally decisive moment — it is when a recovery team commits to a drive, and the entire
 * descent, where most of the landing uncertainty lives, is still ahead. Scoring at the last update
 * would ask how well the model predicts a payload that has nearly landed, which flatters the
 * result and answers a question nobody has. The whole curve is printed either way.
 *
 * <p><strong>Both predictions fly in the same wind field the DS-6 flights were generated in.</strong>
 * That matters more than it looks. Run with no wind at all — which is what a null sounding gives —
 * the frozen prediction lands near the launch site while the truth has drifted a hundred
 * kilometres, and the "reduction" measured is mostly the live prediction knowing where the balloon
 * currently is. Measured that way the median reduction at burst was 73%, against 30% required, and
 * the number would have been an artefact of the setup rather than a property of the estimator.
 * Giving both predictions the truth's wind field leaves exactly one thing the frozen prediction
 * does not know — what this particular balloon is doing — which is the question O4 asks.
 *
 * <p>The predictions still do not know the flight's wind <em>scale</em>, which DS-6 disperses per
 * flight and the filter deliberately does not estimate (ADR-3, ADR-7). That residual is the honest
 * remaining error, and it is the same for both sides of the comparison.
 *
 * <p>Tagged {@code perf}: twenty pre-flight ensembles plus twenty replays is minutes.
 */
@Tag("perf")
class LandingErrorTest {

    /** T-V6's threshold: the median flight's error must fall by at least this fraction. */
    private static final double REQUIRED_REDUCTION = 0.30;

    /** Members in the frozen pre-flight ensemble. */
    private static final int PREFLIGHT_MEMBERS = 120;

    /** Assimilate every n-th sample; the replay is scored, not timed. */
    private static final int ASSIMILATE_EVERY = 40;

    /**
     * Total particles here, against the 2,000 the default uses.
     *
     * <p>Still a pooled bank, because a single filter's re-predictions inherit a median that has
     * wandered (ADR-18) — but at the eight-filter, 500-particle configuration ADR-18 measures,
     * whose medians are as good as the larger one's. T-V6 scores where the footprint lands, and
     * the extra particles buy band width rather than a better centre.
     */
    private static final int PARTICLE_BUDGET = 500;

    /** Filters in the bank (ADR-18). */
    private static final int FILTER_COUNT = 8;

    /**
     * The wind field DS-6 was generated in ({@code SynthService}), given to both predictions.
     *
     * <p>Not the flights' individual wind <em>scales</em>, which are dispersed per flight and which
     * neither prediction knows — that is the error ADR-7 says stays in the footprint.
     */
    private static final com.skyfix.core.atmos.WindField TRUTH_WIND =
            new com.skyfix.core.atmos.ConstantWindField(12.0, -4.0);

    /**
     * Members per in-flight re-prediction here, against the 200 the default uses.
     *
     * <p>What is scored is the <em>centre</em> of each footprint, and a centre is the mean landing
     * point of the members — an estimate whose standard error falls as the square root of the
     * count, so eighty places it to within a few per cent of where two hundred would. The axes,
     * which do need the full count, are not what T-V6 measures.
     */
    private static final int REPREDICT_MEMBERS = 80;

    /**
     * Flight-time seconds between scored re-predictions, against the 50 the default uses.
     *
     * <p>T-V6 needs a curve, not a stream: about thirty points across a two-hour flight is more
     * than enough to locate the burst and show the error falling, and it is the difference between
     * a ten-minute evaluation and a two-hour one. ADR-17's 50 s interval is a live-replay budget,
     * not a scoring resolution.
     */
    private static final double SCORING_INTERVAL_SECONDS = 300.0;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("T-V6: median landing-error reduction at burst, live against frozen, >= 30%")
    void inFlightUpdateBeatsTheFrozenPrediction() throws Exception {
        ConfigLoader loader = new ConfigLoader();
        BalloonConfig balloon = loader.loadBalloon(Path.of("data/missions/balloon.json"));
        ConfigLoader.MissionSpec spec = loader.loadMission(Path.of("data/missions/mission.json"));
        SimSettings settings = loader.loadSimSettings(
                loader.read(Path.of("data/missions/mission.json")), spec.groundElevationM());

        List<Double> atBurst = new ArrayList<>();
        List<Double> atEnd = new ArrayList<>();
        List<Double> frozenErrors = new ArrayList<>();

        System.out.printf(Locale.ROOT, "%n%-16s %12s %12s %12s %10s %10s%n",
                "flight", "frozen km", "burst km", "final km", "cut@burst", "cut@end");
        System.out.println("-".repeat(78));

        for (String[] row : truthRows()) {
            PredictionError scored = scoreOne(row, balloon, spec, settings);
            double frozenKm = scored.frozenErrorM() / 1000.0;
            frozenErrors.add(scored.frozenErrorM());

            double burstKm = scored.atBurst().map(u -> u.errorM() / 1000.0).orElse(Double.NaN);
            double endKm = scored.last().map(u -> u.errorM() / 1000.0).orElse(Double.NaN);
            double cutBurst = scored.atBurst()
                    .map(u -> scored.reductionAgainstFrozen(u.errorM())).orElse(Double.NaN);
            double cutEnd = scored.last()
                    .map(u -> scored.reductionAgainstFrozen(u.errorM())).orElse(Double.NaN);

            if (!Double.isNaN(cutBurst)) {
                atBurst.add(cutBurst);
            }
            if (!Double.isNaN(cutEnd)) {
                atEnd.add(cutEnd);
            }
            System.out.printf(Locale.ROOT, "%-16s %12.2f %12.2f %12.2f %9.0f%% %9.0f%%%n",
                    row[0], frozenKm, burstKm, endKm, cutBurst * 100, cutEnd * 100);
        }

        double medianBurst = ReportService.median(toArray(atBurst));
        double medianEnd = ReportService.median(toArray(atEnd));
        System.out.printf(Locale.ROOT,
                "%nT-V6  median error reduction at burst : %.1f%%  (required %.0f%%)  GATED%n",
                medianBurst * 100, REQUIRED_REDUCTION * 100);
        System.out.printf(Locale.ROOT,
                "      median error reduction at landing: %.1f%%  reported%n", medianEnd * 100);
        System.out.printf(Locale.ROOT,
                "      median frozen error              : %.2f km%n",
                ReportService.median(toArray(frozenErrors)) / 1000.0);

        assertThat(atBurst).as("every flight produced a post-burst re-prediction to score")
                .hasSize(20);
        assertThat(medianBurst)
                .as("median landing-error reduction at burst, live against frozen")
                .isGreaterThanOrEqualTo(REQUIRED_REDUCTION);
    }

    /** Freezes a pre-flight footprint, replays the flight, and scores both against the truth. */
    private PredictionError scoreOne(String[] truth, BalloonConfig balloon,
                                     ConfigLoader.MissionSpec spec, SimSettings settings)
            throws SkyfixException, IOException {
        String name = truth[0];
        long seed = Long.parseLong(truth[1]);
        GeoPoint actual = new GeoPoint(Double.parseDouble(truth[8]), Double.parseDouble(truth[9]),
                spec.groundElevationM());

        Path db = tempDir.resolve(name + ".db");
        try (Database database = Database.openFile(db)) {
            new SchemaInitializer(database).initialise();
            Mission mission = new MissionDao(database).save(new Mission(null, name, spec.launch(),
                    spec.groundElevationM(), spec.launchEpochUtc()));
            StoredBalloonConfig stored = new BalloonConfigDao(database)
                    .findOrSave(mission.id(), balloon);

            // The frozen prediction knows only what a team knows before launch: the catalogue.
            PredictionService predictions = new PredictionService(database);
            var frozen = predictions.predictFootprint(mission, stored, null, TRUTH_WIND, settings,
                    DispersionSpec.preflightDefault(balloon), PREFLIGHT_MEMBERS,
                    RunContext.start(seed), tempDir.resolve("out"));

            TelemetrySeries series = new CsvTelemetryReader().read(ds6(name));
            var replay = new ReplayService(database).replay(mission, stored, null, TRUTH_WIND,
                    null, series, settings, ReplayOptions.standard()
                            .withAssimilateEvery(ASSIMILATE_EVERY)
                            .withFilterCount(FILTER_COUNT)
                            .withParticleCount(PARTICLE_BUDGET)
                            .withRepredictMembers(REPREDICT_MEMBERS)
                            .withRepredictIntervalSeconds(SCORING_INTERVAL_SECONDS),
                    RunContext.start(seed));

            Instant launchEpoch = series.first().epochUtc();
            Instant burstEpoch = replay.burst().map(b -> b.epochUtc()).orElse(null);
            return new ReportService(database).score(replay.run().id(), frozen.run().id(), actual,
                    launchEpoch, burstEpoch, spec.groundElevationM());
        }
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static List<String[]> truthRows() throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("data/truth/truth-manifest.csv"))) {
            if (!line.isBlank() && !line.startsWith("name,")) {
                rows.add(line.split(","));
            }
        }
        return rows;
    }

    /**
     * The path to a DS-6 flight, checked to exist first.
     *
     * <p>DS-6's telemetry is generated rather than committed (FR-1.4), so on a fresh clone these
     * files are absent until `run.sh synth` has been run. A raw {@code NoSuchFileException} names
     * the file but not the remedy, and the remedy is one command.
     */
    private static Path ds6(String name) {
        Path path = Path.of("data/truth", name + ".csv");
        if (!java.nio.file.Files.exists(path)) {
            throw new IllegalStateException(
                    "DS-6 telemetry is missing: " + path + ". It is generated rather than "
                            + "committed, so run it first:\n\n"
                            + "  ./scripts/run.sh synth --mission data/missions/mission.json "
                            + "--balloon data/missions/balloon.json\n");
        }
        return path;
    }
}
