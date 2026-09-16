package com.skyfix.cli;

import com.skyfix.app.IngestService;
import com.skyfix.app.PredictionService;
import com.skyfix.app.ReplayOptions;
import com.skyfix.app.ReplayService;
import com.skyfix.app.RunContext;
import com.skyfix.app.SynthService;
import com.skyfix.app.ValidationService;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.NoiseSpec;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;
import com.skyfix.io.ConfigLoader;
import com.skyfix.io.PlotExporter;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.FlightLog;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.MissionDao;
import com.skyfix.persistence.RunRecord;
import com.skyfix.persistence.SchemaInitializer;
import com.skyfix.persistence.StoredBalloonConfig;
import com.skyfix.persistence.ValidationDao;
import com.skyfix.persistence.ValidationResult;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses arguments, dispatches to a command and maps failures to exit codes (BLUEPRINT §12).
 *
 * <p>Separated from {@link Main} so the whole surface is testable without spawning a process:
 * {@link #execute} returns an exit code rather than calling {@code System.exit}.
 *
 * <p>Exit codes: 0 success | 1 usage | 2 validation | 3 data format | 4 model domain |
 * 5 convergence | 6 persistence | 70 unexpected.
 */
public final class SkyfixCli {

    /** Exit code for a usage error - an unknown command or a missing required option. */
    public static final int EXIT_USAGE = 1;
    /** Exit code for an unexpected runtime failure. */
    public static final int EXIT_UNEXPECTED = 70;

    private final ConsoleReporter reporter;
    private final PrintStream err;

    /**
     * @param out where normal output goes
     * @param err where errors go
     */
    public SkyfixCli(PrintStream out, PrintStream err) {
        this.reporter = new ConsoleReporter(out, err);
        this.err = err;
    }

    /**
     * Runs one command.
     *
     * @param args the command line
     * @return the process exit code
     */
    public int execute(String... args) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            return args.length == 0 ? EXIT_USAGE : 0;
        }
        String command = args[0];
        Map<String, String> options;
        try {
            options = parseOptions(args, 1);
        } catch (ValidationException e) {
            reporter.error(e);
            return e.exitCode();
        }

        try {
            return switch (command) {
                case "ingest" -> ingest(options);
                case "predict" -> predict(options);
                case "replay" -> replay(options);
                case "synth" -> synth(options);
                case "validate" -> validate(options);
                case "version" -> version();
                default -> {
                    err.println("error: unknown command \"" + command + "\"");
                    printUsage();
                    yield EXIT_USAGE;
                }
            };
        } catch (SkyfixException e) {
            reporter.error(e);
            return e.exitCode();
        } catch (RuntimeException e) {
            reporter.unexpectedError(e, "out/error.log");
            writeErrorLog(e);
            return EXIT_UNEXPECTED;
        }
    }

    private int ingest(Map<String, String> options) throws SkyfixException {
        Path dbPath = Path.of(options.getOrDefault("db", "skyfix.db"));
        try (Database database = open(dbPath)) {
            IngestService ingest = new IngestService(database);

            if (options.containsKey("sounding")) {
                String epoch = options.getOrDefault("epoch", Instant.now().toString());
                IngestService.SoundingIngestResult result = ingest.ingestSounding(
                        Path.of(options.get("sounding")), epoch);
                reporter.info(String.format(
                        "sounding %s @ %s: %d levels, %d lines rejected%s",
                        result.sounding().stationId(), epoch, result.sounding().levelCount(),
                        result.rejections().size(),
                        result.isNew() ? "" : " (already imported, unchanged)"));
                for (String rejection : result.rejections()) {
                    reporter.info("  rejected: " + rejection);
                }
                reporter.info("sounding id: " + result.sounding().id());
            }

            if (options.containsKey("mission") || options.containsKey("balloon")) {
                Path missionPath = requiredPath(options, "mission");
                Path balloonPath = requiredPath(options, "balloon");
                IngestService.IngestResult result =
                        ingest.ingestMission(missionPath, balloonPath);
                reporter.info(String.format(
                        "mission \"%s\" id=%d%s, config %s id=%d%s",
                        result.mission().name(), result.mission().id(),
                        result.missionIsNew() ? " (new)" : " (existing)",
                        result.config().config().configHash().substring(0, 12),
                        result.config().id(),
                        result.configIsNew() ? " (new)" : " (existing)"));
            }

            if (!options.containsKey("sounding") && !options.containsKey("mission")) {
                throw ValidationException.field("--mission or --sounding", "absent",
                        "is required; there is nothing to ingest");
            }
            return 0;
        }
    }

    private int predict(Map<String, String> options) throws SkyfixException {
        Path dbPath = Path.of(options.getOrDefault("db", "skyfix.db"));
        Path outputDir = Path.of(options.getOrDefault("out", "out"));
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));

        try (Database database = open(dbPath)) {
            IngestService ingest = new IngestService(database);
            IngestService.IngestResult ingested = ingest.ingestMission(
                    requiredPath(options, "mission"), requiredPath(options, "balloon"));

            Mission mission = ingested.mission();
            StoredBalloonConfig config = ingested.config();
            Long soundingId = options.containsKey("sounding-id")
                    ? Long.parseLong(options.get("sounding-id"))
                    : null;

            ConfigLoader loader = new ConfigLoader();
            SimSettings settings = loader.loadSimSettings(
                    loader.read(requiredPath(options, "mission")), mission.groundElevationM());
            if (options.containsKey("step")) {
                settings = settings.withStepSeconds(Double.parseDouble(options.get("step")));
            }
            if (options.containsKey("integrator")) {
                settings = settings.withIntegrator(options.get("integrator"));
            }

            int memberCount = Integer.parseInt(options.getOrDefault("members", "1"));
            RunContext context = RunContext.start(seed);
            PredictionService service = new PredictionService(database);

            if (memberCount <= 1) {
                PredictionService.PredictionResult result =
                        service.predict(mission, config, soundingId, settings, context, outputDir);
                reporter.printPrediction(result.run().id(), result.history(),
                        result.windFieldName(), result.outputDir());
            } else {
                DispersionSpec spec = DispersionSpec.preflightDefault(config.config());
                PredictionService.EnsembleResult result = service.predictFootprint(
                        mission, config, soundingId, settings, spec, memberCount, context,
                        outputDir);
                reporter.printFootprint(result);
            }
            reporter.info(String.format("  seed %d | git %s | %s dt=%s s | %d members | %d ms",
                    context.seed(), context.gitSha(), settings.integrator(),
                    settings.stepSeconds(), memberCount, context.elapsedMs()));
            return 0;
        }
    }

    private int replay(Map<String, String> options) throws SkyfixException {
        Path dbPath = Path.of(options.getOrDefault("db", "skyfix.db"));
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));

        try (Database database = open(dbPath)) {
            IngestService ingest = new IngestService(database);
            IngestService.IngestResult ingested = ingest.ingestMission(
                    requiredPath(options, "mission"), requiredPath(options, "balloon"));
            Mission mission = ingested.mission();

            IngestService.TelemetryIngestResult log = ingest.ingestTelemetry(mission,
                    requiredPath(options, "log"),
                    options.containsKey("recorded") ? FlightLog.RECORDED : FlightLog.SYNTHETIC);
            reporter.info(String.format("flight log \"%s\" id=%d, %d samples%s",
                    log.log().name(), log.log().id(), log.series().size(),
                    log.isNew() ? " (new)" : " (already ingested)"));
            if (log.series().hasAnomalies()) {
                reporter.info("  " + log.series().anomalySummary());
            }

            ConfigLoader loader = new ConfigLoader();
            SimSettings settings = loader.loadSimSettings(
                    loader.read(requiredPath(options, "mission")), mission.groundElevationM());
            if (options.containsKey("step")) {
                settings = settings.withStepSeconds(Double.parseDouble(options.get("step")));
            }
            if (options.containsKey("integrator")) {
                settings = settings.withIntegrator(options.get("integrator"));
            }

            Long soundingId = options.containsKey("sounding-id")
                    ? Long.parseLong(options.get("sounding-id"))
                    : null;
            ReplayOptions replayOptions = ReplayOptions.standard()
                    .withAssimilateEvery(Integer.parseInt(options.getOrDefault("every", "1")))
                    .withParticleCount(Integer.parseInt(options.getOrDefault("particles",
                            String.valueOf(ReplayOptions.standard().particleCount()))))
                    .withFilterCount(Integer.parseInt(options.getOrDefault("filters",
                            String.valueOf(ReplayOptions.standard().filterCount()))))
                    .withRepredictMembers(Integer.parseInt(options.getOrDefault("members",
                            String.valueOf(ReplayOptions.DEFAULT_REPREDICT_MEMBERS))))
                    .validated();

            RunContext context = RunContext.start(seed);
            ReplayService.ReplayResult result = new ReplayService(database).replay(
                    mission, ingested.config(), soundingId, log.log().id(), log.series(),
                    settings, replayOptions, context);

            Path runDir = Path.of(options.getOrDefault("out", "out"))
                    .resolve("run-" + result.run().id());
            List<Path> plots = exportReplayPlots(runDir, result,
                    ingested.config().config().burstDiameterM());

            reporter.printReplay(result);
            reporter.info("  wrote " + plots.size() + " plots to " + runDir);
            reporter.info(String.format(
                    "  seed %d | git %s | %s dt=%s s | every %d samples | %d ms",
                    context.seed(), context.gitSha(), settings.integrator(),
                    settings.stepSeconds(), replayOptions.assimilateEvery(),
                    context.elapsedMs()));
            return 0;
        }
    }

    /**
     * Writes PL-3 for a replay: the posterior for each parameter against update epoch.
     *
     * <p>Built from the re-predictions rather than from every assimilated sample. A full-rate
     * replay produces thousands of posteriors and a chart with a point per second says nothing a
     * chart with a point per re-prediction does not.
     */
    private static List<Path> exportReplayPlots(Path runDir, ReplayService.ReplayResult result,
                                                double nominalBurstDiameterM)
            throws SkyfixException {
        double[] flightSeconds = new double[result.updates().size()];
        List<Posterior> history = new ArrayList<>(result.updates().size());
        for (int i = 0; i < result.updates().size(); i++) {
            flightSeconds[i] = result.updates().get(i).flightSeconds();
            history.add(result.updates().get(i).posterior());
        }
        if (history.isEmpty()) {
            return List.of();
        }
        // No truth line: a real replay has no truth to draw. The evaluation tests supply one.
        return PlotExporter.exportPosteriorHistory(runDir, flightSeconds, history, null,
                nominalBurstDiameterM);
    }

    private int synth(Map<String, String> options) throws SkyfixException {
        Path outputDir = Path.of(options.getOrDefault("out", "data/truth"));
        ConfigLoader loader = new ConfigLoader();
        BalloonConfig balloon = loader.loadBalloon(requiredPath(options, "balloon"));
        ConfigLoader.MissionSpec mission = loader.loadMission(requiredPath(options, "mission"));
        SimSettings settings = loader.loadSimSettings(
                loader.read(requiredPath(options, "mission")), mission.groundElevationM());

        SynthService service = new SynthService();
        List<SynthService.GeneratedFlight> flights = service.generateAll(outputDir, balloon,
                settings, mission.launch(), Optional.empty(), NoiseSpec.standard());

        Path manifest = outputDir.resolve("truth-manifest.csv");
        service.writeManifest(manifest, flights);

        reporter.printSynthSummary(flights, outputDir, manifest);
        return 0;
    }

    private int validate(Map<String, String> options) throws SkyfixException {
        ValidationService service = new ValidationService();
        List<ValidationResult> results = service.runAll();
        long failures = reporter.printValidationTable(results, service.notYetImplemented());

        if (options.containsKey("plots")) {
            Path plot = service.exportResidualPlot(Path.of(options.getOrDefault("out", "out")));
            reporter.info("  residual plot  " + plot);
        }

        // Persisting the table means report §11 is a query against a run rather than a
        // screenshot that has to be retaken whenever the model changes (FR-4.4).
        if (options.containsKey("db")) {
            persistValidationRun(Path.of(options.get("db")), results);
        }
        return failures == 0 ? 0 : 1;
    }

    private void persistValidationRun(Path dbPath, List<ValidationResult> results)
            throws SkyfixException {
        try (Database database = open(dbPath)) {
            MissionDao missions = new MissionDao(database);
            Mission mission = missions.findByName("validation-reference")
                    .orElseGet(() -> {
                        try {
                            return missions.save(new Mission(null, "validation-reference",
                                    ValidationService.referenceLaunch(),
                                    ValidationService.referenceLaunch().altitudeM(),
                                    Instant.now().toString()));
                        } catch (SkyfixException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            var config = new com.skyfix.persistence.BalloonConfigDao(database)
                    .findOrSave(mission.id(), ValidationService.referenceConfig());
            RunContext context = RunContext.start(0L);
            var run = new com.skyfix.persistence.RunDao(database).save(new RunRecord(null,
                    mission.id(), config.id(), null, null, "VALIDATION", "RK4", 0.25, 1,
                    context.seed(), context.gitSha(), config.config().configHash(),
                    context.hostCores(), context.startedUtc(), null, RunRecord.RUNNING));
            new ValidationDao(database).saveAll(run.id(), results);
            new com.skyfix.persistence.RunDao(database)
                    .finish(run.id(), context.elapsedMs(), RunRecord.OK);
            reporter.info("validation run " + run.id() + " stored in " + dbPath);
        }
    }

    private int version() {
        reporter.info("SKYFIX " + version(getClass()) + " - balloon landing-footprint prediction");
        reporter.info("schema version " + SchemaInitializer.SCHEMA_VERSION
                + " | git " + RunContext.start(0).gitSha());
        return 0;
    }

    private static String version(Class<?> type) {
        String implementation = type.getPackage().getImplementationVersion();
        return implementation == null ? "1.0-SNAPSHOT" : implementation;
    }

    private Database open(Path dbPath) throws SkyfixException {
        Database database = Database.openFile(dbPath);
        new SchemaInitializer(database).initialise();
        return database;
    }

    private static Path requiredPath(Map<String, String> options, String name)
            throws ValidationException {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw ValidationException.field("--" + name, "absent", "is required");
        }
        return Path.of(value);
    }

    /**
     * Parses {@code --key value} and {@code --key=value} pairs.
     *
     * @param args  the command line
     * @param from  the index to start at
     * @return the options, in the order given
     * @throws ValidationException if an argument is not a recognisable option
     */
    static Map<String, String> parseOptions(String[] args, int from) throws ValidationException {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            String body = arg.substring(2);
            int equals = body.indexOf('=');
            if (equals >= 0) {
                options.put(body.substring(0, equals), body.substring(equals + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(body, args[++i]);
            } else {
                options.put(body, "true"); // a flag with no value
            }
        }
        if (!positional.isEmpty()) {
            throw ValidationException.field("arguments", String.join(" ", positional),
                    "are not recognised; every option takes the form --name value");
        }
        return options;
    }

    private static boolean isHelp(String arg) {
        return arg.equals("help") || arg.equals("--help") || arg.equals("-h");
    }

    private void printUsage() {
        reporter.info("""
                SKYFIX - high-altitude balloon landing-footprint prediction

                usage: run.sh <command> [options]

                commands:
                  ingest     import a mission, a balloon configuration and/or a sounding
                    --mission  <mission.json>    mission definition
                    --balloon  <balloon.json>    balloon configuration
                    --sounding <file>            radiosonde sounding to import
                    --epoch    <iso-8601>        observation time for the sounding
                    --db       <file>            database file (default: skyfix.db)

                  predict    run a pre-flight prediction and export the trajectory
                    --mission  <mission.json>    mission definition        (required)
                    --balloon  <balloon.json>    balloon configuration     (required)
                    --sounding-id <id>           imported sounding to use as the wind field
                    --step     <seconds>         integration step (default: 0.25)
                    --integrator <RK4|RKF45>     integration scheme
                    --members  <n>               ensemble members; 1 is a single deterministic
                                                 flight, above that a dispersed footprint with
                                                 50% and 95% confidence ellipses (default: 1)
                    --seed     <n>               run seed (default: 42)
                    --out      <dir>             export directory (default: out)
                    --db       <file>            database file (default: skyfix.db)

                  replay     replay a telemetry log through the estimator, re-predicting
                             the landing footprint as the flight unfolds
                    --mission  <mission.json>    mission definition        (required)
                    --balloon  <balloon.json>    balloon configuration     (required)
                    --log      <flight.csv>      telemetry log to replay   (required)
                    --sounding-id <n>            wind field to advect with
                    --every    <n>               assimilate every n-th sample (default: 1)
                    --out      <dir>             export directory (default: out)
                    --particles <n>              total particle budget (default: 2000, ADR-18)
                    --filters  <n>               independent filters to pool (default: 16);
                                                 1 gives a single filter, whose band is not
                                                 a credible interval -- see ADR-18
                    --members  <n>               members per re-prediction (default: 200)
                    --recorded                   mark the log as real rather than synthetic
                    --seed     <n>               run seed (default: 42)
                    --db       <file>            database file (default: skyfix.db)

                  synth      regenerate the DS-6 synthetic evaluation set from
                             data/truth/seeds.csv; the same seeds reproduce the same files
                    --mission  <mission.json>    mission definition        (required)
                    --balloon  <balloon.json>    balloon configuration     (required)
                    --out      <dir>             output directory (default: data/truth)

                  validate   run the reference-case suite; exits non-zero on any breach
                    --db       <file>            also persist the results to this database
                    --plots                      also export PL-6, the atmosphere residual chart
                    --out      <dir>             where to write it (default: out)

                  version    print the version, schema version and git SHA

                exit codes: 0 ok | 1 usage | 2 validation | 3 data format | 4 model domain
                            5 convergence | 6 persistence | 70 unexpected
                """);
    }

    private void writeErrorLog(Throwable e) {
        try {
            Path log = Path.of("out", "error.log");
            java.nio.file.Files.createDirectories(log.getParent());
            try (java.io.PrintWriter w = new java.io.PrintWriter(
                    java.nio.file.Files.newBufferedWriter(log))) {
                w.println(Instant.now());
                e.printStackTrace(w);
            }
        } catch (java.io.IOException ignored) {
            // Failing to write the log must not mask the original failure.
        }
    }
}
