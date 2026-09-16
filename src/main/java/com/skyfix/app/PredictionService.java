package com.skyfix.app;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.SoundingWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.atmos.WindField;
import com.skyfix.core.flight.EllipseFitter;
import com.skyfix.core.flight.EnsembleRunner;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.core.flight.IntegratorFactory;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.io.CsvWriter;
import com.skyfix.io.GeoJsonWriter;
import com.skyfix.io.PlotExporter;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.EllipseDao;
import com.skyfix.persistence.EnsembleDao;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.RunDao;
import com.skyfix.persistence.RunRecord;
import com.skyfix.persistence.RunStateDao;
import com.skyfix.persistence.StoredBalloonConfig;
import com.skyfix.persistence.StoredSounding;
import com.skyfix.persistence.SoundingDao;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a pre-flight prediction and persists everything needed to reproduce it (UC-1, FR-2.3).
 *
 * <p>At the MVP cut-line this is a single deterministic flight — the ensemble and its confidence
 * ellipse arrive with FR-2.4. The run record it writes already carries the seed, git SHA, config
 * hash, integrator, step size, member count and host cores, so adding members later changes the
 * numbers in the row rather than its shape.
 */
public final class PredictionService {

    private static final Logger LOG = Logger.getLogger(PredictionService.class.getName());

    /**
     * How many members' full trajectories are stored. BLUEPRINT §9 budgets the nominal member plus
     * a sample of about twenty, so a 1,000-member run holds tens of thousands of state rows rather
     * than millions.
     */
    private static final int STORED_HISTORY_SAMPLE = 20;

    private final Database database;
    private final RunDao runs;
    private final RunStateDao states;
    private final SoundingDao soundings;
    private final AtmosphereModel atmosphere = new Ussa1976Atmosphere();

    /**
     * @param database the database to write into
     */
    public PredictionService(Database database) {
        this.database = database;
        this.runs = new RunDao(database);
        this.states = new RunStateDao(database);
        this.soundings = new SoundingDao(database);
    }

    /** Confidence levels the blueprint reports a footprint at. */
    public static final List<Double> CONFIDENCE_LEVELS = List.of(0.50, 0.95);

    /**
     * Predicts a landing point with a single deterministic flight.
     *
     * @param mission    the mission
     * @param config     the stored balloon configuration
     * @param soundingId the sounding to use, or {@code null} for a windless prediction
     * @param settings   integration settings
     * @param context    provenance for this execution
     * @param outputDir  where CSV and GeoJSON exports are written
     * @return the run record and the trajectory
     * @throws SkyfixException if the flight cannot be integrated or the run cannot be stored
     */
    public PredictionResult predict(Mission mission, StoredBalloonConfig config, Long soundingId,
                                    SimSettings settings, RunContext context, Path outputDir)
            throws SkyfixException {

        // Everything that can be rejected is rejected before a row is written. An unknown
        // integrator would otherwise be caught by the CHECK constraint on run.integrator and
        // surface as a database error (exit 6) rather than as a ValidationException naming the
        // field (exit 2) -- and would leave a RUNNING row behind for a run that never started.
        IntegratorFactory.create(settings.integrator());
        WindField wind = resolveWindField(soundingId);
        BalloonConfig balloon = config.config();

        RunRecord run = runs.save(new RunRecord(null, mission.id(), config.id(), soundingId, null,
                "PREFLIGHT", settings.integrator(), settings.stepSeconds(), 1,
                context.seed(), context.gitSha(), balloon.configHash(), context.hostCores(),
                context.startedUtc(), null, RunRecord.RUNNING));

        try {
            StateHistory history = new FlightSimulator(atmosphere, wind)
                    .run(balloon, settings, mission.launch());

            states.saveHistory(run.id(), 0, history);

            Path runDir = outputDir.resolve("run-" + run.id());
            CsvWriter.writeTrajectory(runDir.resolve("trajectory.csv"), history);
            CsvWriter.writeSummary(runDir.resolve("summary.csv"), run.id(), history);
            GeoJsonWriter.writeFlight(runDir.resolve("flight.geojson"), history);

            runs.finish(run.id(), context.elapsedMs(), RunRecord.OK);

            if (history.windExtrapolatedCount() > 0) {
                LOG.warning(() -> history.windExtrapolatedCount() + " of " + history.stepCount()
                        + " steps queried the wind above the sounding's top level; the wind there "
                        + "is held, not known");
            }
            return new PredictionResult(run.withId(run.id()), history, runDir,
                    wind.name());
        } catch (SkyfixException e) {
            runs.finish(run.id(), context.elapsedMs(), RunRecord.FAILED);
            LOG.log(Level.SEVERE, "prediction run " + run.id() + " failed", e);
            throw e;
        }
    }

    private WindField resolveWindField(Long soundingId) throws SkyfixException {
        if (soundingId == null) {
            LOG.info("no sounding supplied; predicting in still air");
            return ConstantWindField.calm();
        }
        Optional<StoredSounding> stored = soundings.findById(soundingId);
        if (stored.isEmpty()) {
            throw com.skyfix.domain.error.ValidationException.field("sounding_id", soundingId,
                    "does not match any imported sounding");
        }
        return SoundingWindField.of(stored.get().stationId(), stored.get().levels());
    }

    /**
     * Predicts a landing <em>footprint</em> by dispersing the flight parameters over an ensemble
     * (FR-2.4, O2).
     *
     * <p>This is the answer the project exists to give: not a point, but an ellipse with a stated
     * probability. The nominal member's trajectory is still exported, so the altitude profile a
     * payload engineer needs is not lost in the aggregate.
     *
     * @param mission     the mission
     * @param config      the stored balloon configuration
     * @param soundingId  the sounding to use, or {@code null} for a windless prediction
     * @param settings    integration settings
     * @param spec        how far each parameter is dispersed
     * @param memberCount how many members to fly
     * @param context     provenance for this execution
     * @param outputDir   where exports are written
     * @return the run record, the ensemble, the fitted ellipses and the nominal trajectory
     * @throws SkyfixException if too many members fail, or the run cannot be stored
     */
    public EnsembleResult predictFootprint(Mission mission, StoredBalloonConfig config,
                                           Long soundingId, SimSettings settings,
                                           DispersionSpec spec, int memberCount,
                                           RunContext context, Path outputDir)
            throws SkyfixException {
        return predictFootprint(mission, config, soundingId, null, settings, spec, memberCount,
                context, outputDir);
    }

    /**
     * Predicts a footprint against a wind field supplied directly rather than looked up.
     *
     * <p>For callers that hold a wind field the database does not: an evaluation scoring against
     * synthetic flights has to predict in the <em>same</em> wind the flights were generated in, or
     * it is measuring the wind mismatch rather than whatever it meant to measure.
     *
     * @param mission     the mission
     * @param config      the stored balloon configuration
     * @param soundingId  the sounding to record on the run, or {@code null}
     * @param windField   the wind field to use; when {@code null} it is resolved from
     *                    {@code soundingId} as usual
     * @param settings    integration settings
     * @param spec        how far each parameter is dispersed
     * @param memberCount how many members to fly
     * @param context     provenance for this execution
     * @param outputDir   where exports are written
     * @return the run record, the ensemble, the fitted ellipses and the nominal trajectory
     * @throws SkyfixException if too many members fail, or the run cannot be stored
     */
    public EnsembleResult predictFootprint(Mission mission, StoredBalloonConfig config,
                                           Long soundingId, WindField windField,
                                           SimSettings settings, DispersionSpec spec,
                                           int memberCount, RunContext context, Path outputDir)
            throws SkyfixException {

        IntegratorFactory.create(settings.integrator());
        WindField wind = windField != null ? windField : resolveWindField(soundingId);
        BalloonConfig balloon = config.config();

        RunRecord run = runs.save(new RunRecord(null, mission.id(), config.id(), soundingId, null,
                "PREFLIGHT", settings.integrator(), settings.stepSeconds(), memberCount,
                context.seed(), context.gitSha(), balloon.configHash(), context.hostCores(),
                context.startedUtc(), null, RunRecord.RUNNING));

        try {
            EnsembleRunner runner = new EnsembleRunner(atmosphere, wind);
            EnsembleRunner.Result result = runner.run(balloon, spec, settings, mission.launch(),
                    memberCount, context.seed(), STORED_HISTORY_SAMPLE);
            Ensemble ensemble = result.ensemble();

            List<LandingEllipse> ellipses = new ArrayList<>();
            for (double confidence : CONFIDENCE_LEVELS) {
                ellipses.add(EllipseFitter.fit(ensemble.landingPoints(), confidence,
                        mission.groundElevationM()));
            }

            // Persist the full landing set and the ellipses, plus trajectories for the sampled
            // members only -- BLUEPRINT §9's retention rule, which is what keeps a 1,000-member
            // run to tens of thousands of rows rather than millions.
            new EnsembleDao(database).saveAll(run.id(), ensemble, null);
            new EllipseDao(database).saveAll(run.id(), ellipses, null);
            for (Map.Entry<Integer, StateHistory> entry : result.histories().entrySet()) {
                states.saveHistory(run.id(), entry.getKey(), entry.getValue());
            }

            // The trajectory exported alongside the footprint is the NOMINAL flight -- the
            // undispersed parameters -- not member 0, which is just the first draw of the design
            // and can sit anywhere in the distribution. Labelling a dispersed draw "nominal" would
            // misreport the altitude profile a payload engineer schedules against (persona P2).
            // One extra flight costs about twenty milliseconds.
            Path runDir = outputDir.resolve("run-" + run.id());
            StateHistory nominal = new FlightSimulator(atmosphere, wind)
                    .run(balloon, FlightParameters.nominal(balloon), settings, mission.launch());
            CsvWriter.writeTrajectory(runDir.resolve("trajectory.csv"), nominal);
            CsvWriter.writeSummary(runDir.resolve("summary.csv"), run.id(), nominal);
            CsvWriter.writeLandingScatter(runDir.resolve("landing-scatter.csv"), ensemble);
            CsvWriter.writeEllipses(runDir.resolve("ellipses.csv"), run.id(), ellipses);
            GeoJsonWriter.writeFlight(runDir.resolve("flight.geojson"), nominal);
            GeoJsonWriter.writeFootprint(runDir.resolve("footprint.geojson"), ellipses, ensemble);
            List<Path> plots = PlotExporter.exportEnsemblePlots(runDir, nominal,
                    result.histories(), ensemble, ellipses, mission.launch());
            LOG.info(() -> "wrote " + plots.size() + " plots to " + runDir);

            runs.finish(run.id(), context.elapsedMs(), RunRecord.OK);

            if (ensemble.failureCount() > 0) {
                LOG.warning(() -> ensemble.failureCount() + " of " + memberCount
                        + " members failed and were discarded");
            }
            return new EnsembleResult(run.withId(run.id()), ensemble, ellipses, nominal, runDir,
                    wind.name(), runner.threadCount());
        } catch (SkyfixException e) {
            runs.finish(run.id(), context.elapsedMs(), RunRecord.FAILED);
            LOG.log(Level.SEVERE, "ensemble run " + run.id() + " failed", e);
            throw e;
        }
    }

    /**
     * The outcome of a prediction.
     *
     * @param run          the stored run record
     * @param history      the trajectory
     * @param outputDir    the directory the exports were written to
     * @param windFieldName which wind field was used, for the console summary
     */
    public record PredictionResult(RunRecord run, StateHistory history, Path outputDir,
                                   String windFieldName) {
    }

    /**
     * The outcome of an ensemble prediction.
     *
     * @param run           the stored run record
     * @param ensemble      every member's parameters and landing point
     * @param ellipses      the fitted confidence ellipses, in the order of
     *                      {@link #CONFIDENCE_LEVELS}
     * @param nominalHistory the trajectory of member 0, exported for the altitude profile
     * @param outputDir     the directory the exports were written to
     * @param windFieldName which wind field was used
     * @param threadCount   the pool size used, for NFR-1 reporting
     */
    public record EnsembleResult(RunRecord run, Ensemble ensemble, List<LandingEllipse> ellipses,
                                 StateHistory nominalHistory, Path outputDir, String windFieldName,
                                 int threadCount) {

        /**
         * @param confidence the level to look for
         * @return the ellipse at that confidence, if it was fitted
         */
        public java.util.Optional<LandingEllipse> ellipseAt(double confidence) {
            return ellipses.stream()
                    .filter(e -> Math.abs(e.confidence() - confidence) < 1e-9).findFirst();
        }
    }
}
