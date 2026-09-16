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
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Distribution;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.Phase;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;
import com.skyfix.estimation.BurstDetector;
import com.skyfix.estimation.BurstEvent;
import com.skyfix.estimation.GaussianMeasurementModel;
import com.skyfix.estimation.Observation;
import com.skyfix.estimation.FilterBank;
import com.skyfix.estimation.ParticleFilter;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.EllipseDao;
import com.skyfix.persistence.EstimateDao;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.RunDao;
import com.skyfix.persistence.RunRecord;
import com.skyfix.persistence.SoundingDao;
import com.skyfix.persistence.StoredBalloonConfig;
import com.skyfix.persistence.StoredSounding;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replays a telemetry log through the estimator and re-predicts the landing (FR-3.2, FR-3.3,
 * FR-3.4).
 *
 * <p>The loop is the one BLUEPRINT §8 draws, with the correction ADR-17 makes: every sample goes to
 * the burst detector and the filter, but the re-prediction is throttled to a flight-time interval.
 * The two have very different costs — a filter update is milliseconds, a 200-member ensemble is
 * seconds — and FR-3.3's own budget only closes if they are separated.
 *
 * <p>Nothing here decides physics. The filter owns the estimate, the ensemble owns the footprint,
 * and this class owns the order they happen in and what is written down.
 */
public final class ReplayService {

    private static final Logger LOG = Logger.getLogger(ReplayService.class.getName());

    /**
     * Floor on the relative spread the re-prediction disperses a parameter over.
     *
     * <p>The spread itself is not a constant — it is the width of the filter's own posterior band
     * for that parameter, which is the whole point of having one. This is only a floor, so that a
     * band which has collapsed cannot produce an ensemble with no dispersion at all and a footprint
     * claiming certainty it has not earned.
     *
     * <p>Measured across the twenty DS-6 flights, the posterior's relative sigma per parameter is
     * 0.104 for free lift, 0.081 for ascent Cd, 0.022 for burst scale and 0.115 for parachute drag.
     * The floor sits below all of them.
     */
    private static final double MINIMUM_RELATIVE_SIGMA = 0.01;

    /** Standard deviations spanned by a 5–95% interval of a normal distribution. */
    private static final double BAND_SIGMAS = 3.29;

    /** Wind-scale dispersion, unchanged from pre-flight (ADR-7). */
    private static final double WIND_SCALE_SIGMA = 0.20;

    /** How many members' full trajectories a re-prediction stores. */
    private static final int STORED_HISTORY_SAMPLE = 5;

    /**
     * How close to the ground a reported altitude has to be before the flight counts as over,
     * metres. Generous, because the last samples of a real log are noisy and a payload in a tree
     * is not at the ground elevation the mission declared.
     */
    private static final double LANDED_MARGIN_M = 50.0;

    private final Database database;
    private final RunDao runs;
    private final SoundingDao soundings;
    private final EstimateDao estimates;
    private final EllipseDao ellipses;
    private final AtmosphereModel atmosphere = new Ussa1976Atmosphere();

    /**
     * @param database the database to write into
     */
    public ReplayService(Database database) {
        this.database = database;
        this.runs = new RunDao(database);
        this.soundings = new SoundingDao(database);
        this.estimates = new EstimateDao(database);
        this.ellipses = new EllipseDao(database);
    }

    /**
     * Replays a log, estimating parameters and re-predicting the landing as it goes.
     *
     * @param mission      the mission the log belongs to
     * @param config       the stored balloon configuration
     * @param soundingId   the sounding to advect with, or {@code null} for still air
     * @param flightLogId  the stored flight log this replay is of, or {@code null} if the
     *                     telemetry was not ingested
     * @param series       the telemetry to replay
     * @param settings     integration settings
     * @param options      particle count, ensemble size and re-prediction cadence
     * @param context      provenance for this execution
     * @return what the replay produced
     * @throws SkyfixException if the log is unusable, or the run cannot be stored
     */
    public ReplayResult replay(Mission mission, StoredBalloonConfig config, Long soundingId,
                               Long flightLogId, TelemetrySeries series, SimSettings settings,
                               ReplayOptions options, RunContext context) throws SkyfixException {
        return replay(mission, config, soundingId, null, flightLogId, series, settings, options,
                context);
    }

    /**
     * Replays against a wind field supplied directly rather than looked up.
     *
     * <p>Same reason as {@link PredictionService#predictFootprint(Mission, StoredBalloonConfig,
     * Long, com.skyfix.core.atmos.WindField, SimSettings, DispersionSpec, int, RunContext,
     * java.nio.file.Path)}: an evaluation must replay in the wind its synthetic flights were
     * generated in, or it measures the wind mismatch instead of the estimator.
     *
     * @param mission      the mission the log belongs to
     * @param config       the stored balloon configuration
     * @param soundingId   the sounding to record on the run, or {@code null}
     * @param windField    the wind field to use; when {@code null} it is resolved from
     *                     {@code soundingId} as usual
     * @param flightLogId  the stored flight log, or {@code null}
     * @param series       the telemetry to replay
     * @param settings     integration settings
     * @param options      particle count, ensemble size and re-prediction cadence
     * @param context      provenance for this execution
     * @return what the replay produced
     * @throws SkyfixException if the log is unusable, or the run cannot be stored
     */
    public ReplayResult replay(Mission mission, StoredBalloonConfig config, Long soundingId,
                               WindField windField, Long flightLogId, TelemetrySeries series,
                               SimSettings settings, ReplayOptions options, RunContext context)
            throws SkyfixException {

        // Rejected before a row is written, so a bad integrator name surfaces as a validation
        // failure naming the field rather than as a CHECK-constraint error on a half-built run.
        IntegratorFactory.create(settings.integrator());
        options.validated();
        List<Observation> stream = Observation.streamOf(series);
        if (stream.isEmpty()) {
            throw ValidationException.field("telemetry", series.size(),
                    "a replay needs at least one usable sample");
        }

        WindField wind = windField != null ? windField : resolveWindField(soundingId);
        BalloonConfig balloon = config.config();
        GeoPoint launch = mission.launch();

        RunRecord run = runs.save(new RunRecord(null, mission.id(), config.id(), soundingId,
                flightLogId, "REPLAY", settings.integrator(), settings.stepSeconds(),
                options.particleCount(), context.seed(), context.gitSha(), balloon.configHash(),
                context.hostCores(), context.startedUtc(), null, RunRecord.RUNNING));

        try {
            ReplayResult result = drive(run.id(), balloon, wind, launch, settings, series, stream,
                    options, mission.groundElevationM(), context);
            runs.finish(run.id(), context.elapsedMs(), RunRecord.OK);

            // Read the run back rather than returning the record as it was built: that one still
            // says RUNNING and carries no wall clock, and a caller printing it would report a
            // finished run as unfinished. The stored row is the truth about the run.
            RunRecord finished = runs.findById(run.id()).orElse(run.withId(run.id()));
            return result.withRun(finished);
        } catch (SkyfixException e) {
            runs.finish(run.id(), context.elapsedMs(), RunRecord.FAILED);
            LOG.log(Level.SEVERE, "replay run " + run.id() + " failed", e);
            throw e;
        }
    }

    private ReplayResult drive(long runId, BalloonConfig balloon, WindField wind, GeoPoint launch,
                               SimSettings settings, TelemetrySeries series,
                               List<Observation> stream, ReplayOptions options,
                               double groundElevationM, RunContext context)
            throws SkyfixException {

        FlightSimulator simulator = new FlightSimulator(atmosphere, wind);
        ParticleFilter.Builder template = ParticleFilter.builder()
                .config(balloon)
                .simulator(simulator)
                .settings(settings)
                .prior(DispersionSpec.preflightDefault(balloon))
                .measurementModel(GaussianMeasurementModel.standard()
                        .withWindDrift(launch, WIND_SCALE_SIGMA));

        try (FilterBank filter = FilterBank.of(template, balloon, options.filterCount(),
                options.particleCount(), context.seed(), context.hostCores())) {
            return assimilate(runId, balloon, launch, settings, series, stream, options,
                    groundElevationM, context, filter, simulator, wind);
        }
    }

    private ReplayResult assimilate(long runId, BalloonConfig balloon, GeoPoint launch,
                                    SimSettings settings, TelemetrySeries series,
                                    List<Observation> stream, ReplayOptions options,
                                    double groundElevationM, RunContext context,
                                    FilterBank filter, FlightSimulator simulator, WindField wind)
            throws SkyfixException {

        int assimilateEvery = options.assimilateEvery();
        BurstDetector detector = new BurstDetector();
        EnsembleRunner runner = new EnsembleRunner(atmosphere, wind);
        List<TelemetrySample> samples = series.samples();

        Instant launchEpoch = stream.get(0).epochUtc();
        filter.start(launch, launchEpoch);

        List<Posterior> posteriors = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        BurstEvent burst = null;
        boolean landed = false;
        double lastRepredictSeconds = Double.NEGATIVE_INFINITY;
        long repredictNanos = 0;
        int repredictions = 0;

        for (int i = 0; i < stream.size(); i += assimilateEvery) {
            // Every sample reaches the detector even when only every n-th is assimilated: burst
            // detection smooths over a window of consecutive samples (FR-3.4) and thinning its
            // input would blunt exactly the edge it is looking for.
            boolean justBurst = false;
            for (int j = i; j < Math.min(i + assimilateEvery, samples.size()); j++) {
                Optional<BurstEvent> detected = detector.observe(samples.get(j));
                if (detected.isPresent() && burst == null) {
                    burst = detected.get();
                    filter.burstObserved();
                    justBurst = true;
                    final BurstEvent found = burst;
                    LOG.info(() -> "burst detected " + found);
                }
            }

            Observation observed = stream.get(i);
            Posterior posterior = filter.update(observed);
            posteriors.add(posterior);

            double flightSeconds = Duration.between(launchEpoch, observed.epochUtc()).toNanos()
                    / 1e9;
            boolean last = i + assimilateEvery >= stream.size();
            boolean due = flightSeconds - lastRepredictSeconds
                    >= options.repredictIntervalSeconds();

            // A payload already on the ground has no landing left to predict. Every member would
            // start at the ground and finish there, and the ellipse would collapse to a point
            // whose "uncertainty" is rounding error — a confident-looking answer to a question
            // nobody asked. The last airborne footprint stays the run's final one.
            if (observed.hasAltitude()
                    && observed.altitudeM() <= groundElevationM + LANDED_MARGIN_M) {
                if (!landed) {
                    landed = true;
                    final double at = flightSeconds;
                    LOG.info(() -> String.format(
                            "telemetry is at ground level by T+%.0f s; re-prediction stops here",
                            at));
                }
                continue;
            }

            // Off-cadence re-predictions at the two moments where waiting would be indefensible:
            // burst, where the footprint changes completely, and the last airborne sample, so the
            // final stored ellipse is the best one the run produced (ADR-17).
            if (!due && !justBurst && !last) {
                continue;
            }

            long started = System.nanoTime();
            List<LandingEllipse> fitted = repredict(runner, balloon, settings, posterior,
                    measuredState(observed, balloon, launch, posterior, burst,
                            filter.weightedVerticalRateMs()), groundElevationM,
                    context.seed() + repredictions, options.repredictMembers());
            repredictNanos += System.nanoTime() - started;
            repredictions++;
            lastRepredictSeconds = flightSeconds;

            ellipses.saveAll(runId, fitted, observed.epochUtc().toString());
            updates.add(new Update(observed.epochUtc(), flightSeconds, posterior, fitted));
        }

        estimates.saveAll(runId, posteriors);

        final int repredictionCount = repredictions;
        final long meanMs = repredictions == 0 ? 0
                : repredictNanos / repredictions / 1_000_000L;
        final boolean sawBurst = filter.hasObservedBurst();
        final int updateCount = posteriors.size();
        LOG.info(() -> String.format(
                "replay: %d updates, %d re-predictions averaging %d ms, %s",
                updateCount, repredictionCount, meanMs,
                sawBurst ? "burst seen" : "no burst detected"));

        return new ReplayResult(null, posteriors.get(posteriors.size() - 1), updates,
                Optional.ofNullable(burst), posteriors.size(), repredictions,
                repredictNanos / 1_000_000L, runner.threadCount(), wind.name());
    }

    /**
     * The state a re-prediction continues from: measured position, filtered rate and phase.
     *
     * <p>Position and altitude come straight from the telemetry, because they are measured, and
     * the whole point of a re-prediction is that the flown part of the flight is not modelled
     * again. Everything else comes from the filter.
     *
     * <p><strong>The vertical rate must not come from the telemetry.</strong> A flight computer
     * reports position, so an observed rate is a difference of two noisy altitudes — at 1 Hz with
     * a 10 m GPS, uncertain by about 7 m/s against an ascent rate of 5. Seeding two hundred forward
     * flights with that number does not merely add noise: quadratic drag damping scales with the
     * rate, so a sample that happens to read 25 m/s puts the damping at 12 /s, past RK4's stability
     * limit at the default step, and the members are refused outright. Measured: 128 of 200 members
     * discarded at 1,012 m, from a flight that was ascending normally. The filter's own rate is the
     * dynamics integrated against the whole flight so far, so it is smooth and physically
     * consistent, which is exactly what continuing the flight needs.
     *
     * <p>The envelope diameter cannot be measured either, so it is reconstructed from the posterior
     * median's gas mass at the current ambient conditions — and the phase comes from the burst
     * detector rather than from the sign of a noisy rate, which at 1 Hz changes sign constantly.
     *
     * <p>The state's clock is reset to zero: the ensemble it seeds integrates the remaining flight,
     * and the wind field is indexed from the same origin the pre-flight prediction used.
     */
    private BalloonState measuredState(Observation observed, BalloonConfig balloon,
                                       GeoPoint launch, Posterior posterior, BurstEvent burst,
                                       double verticalRateMs) throws SkyfixException {
        FlightParameters median = posterior.medianParameters(balloon.burstDiameterM(), 1.0);
        Phase phase = burst == null ? Phase.ASCENT : Phase.DESCENT;

        double diameter;
        if (phase == Phase.ASCENT) {
            double gasMass = FlightSimulator.gasMassFor(balloon, median,
                    atmosphere.stateAt(launch.altitudeM()));
            var air = atmosphere.stateAt(observed.altitudeM());
            diameter = BalloonConfig.diameterOfVolume(
                    balloon.volumeAt(gasMass, air.pressurePa(), air.temperatureK()));
        } else {
            diameter = median.burstDiameterM();
        }

        return new BalloonState(0.0,
                new GeoPoint(observed.latitudeDeg(), observed.longitudeDeg(), observed.altitudeM()),
                verticalRateMs, diameter, phase, false);
    }

    private List<LandingEllipse> repredict(EnsembleRunner runner, BalloonConfig balloon,
                                           SimSettings settings, Posterior posterior,
                                           BalloonState from, double groundElevationM, long seed,
                                           int memberCount) throws SkyfixException {
        DispersionSpec spec = aroundPosterior(balloon, posterior);
        EnsembleRunner.Result result = runner.runFrom(balloon, spec, settings, from, memberCount,
                seed, STORED_HISTORY_SAMPLE);
        Ensemble ensemble = result.ensemble();

        List<LandingEllipse> fitted = new ArrayList<>();
        for (double confidence : PredictionService.CONFIDENCE_LEVELS) {
            fitted.add(EllipseFitter.fit(ensemble.landingPoints(), confidence, groundElevationM));
        }
        return fitted;
    }

    /**
     * A dispersion spec centred on the posterior, and as wide as the posterior says it should be.
     *
     * <p>The spread of each parameter is taken from the width of its own 5–95% band rather than
     * from a constant. That is the only defensible choice once the bands are calibrated (ADR-18):
     * the filter has just measured how well it knows each parameter, and a re-prediction that
     * disperses over some other figure is either throwing that away or contradicting it.
     *
     * <p>It also fixes a real over-confidence. The constant this replaced was 0.05 relative for
     * every parameter, against measured posterior sigmas of 0.104 for free lift, 0.081 for ascent
     * Cd, 0.022 for burst scale and 0.115 for parachute drag over the twenty DS-6 flights — so the
     * re-predicted footprint was dispersing free lift and parachute drag over roughly half the
     * uncertainty the filter itself reported, and burst scale over more than twice it.
     *
     * <p>The wind scale keeps its full pre-flight spread, because the filter never estimated it
     * (ADR-3) and no amount of watching the balloon shrinks it (ADR-7).
     */
    private static DispersionSpec aroundPosterior(BalloonConfig balloon, Posterior posterior)
            throws ValidationException {
        FlightParameters median = posterior.medianParameters(balloon.burstDiameterM(), 1.0);
        return DispersionSpec.around(balloon)
                .freeLiftKg(posteriorSpread(posterior, Posterior.FREE_LIFT, median.freeLiftKg()))
                .ascentCd(posteriorSpread(posterior, Posterior.ASCENT_CD, median.ascentCd()))
                .burstDiameterM(posteriorSpread(posterior, Posterior.BURST_SCALE,
                        median.burstDiameterM()))
                .chuteCd(posteriorSpread(posterior, Posterior.CHUTE_CD, median.chuteCd()))
                .windScale(Distribution.TruncatedNormal.symmetric(1.0, WIND_SCALE_SIGMA, 3.0))
                .build();
    }

    /**
     * One parameter's dispersion, taken from the width of its posterior band.
     *
     * <p>A 5–95% interval spans 3.29 standard deviations of a normal, so that is what converts the
     * reported band back into the sigma an ensemble disperses over. The burst scale is reported as
     * a multiple of the catalogue diameter while the ensemble needs metres, so its relative width
     * is applied to the median in metres — which is why this works from the <em>relative</em> width
     * rather than the absolute one.
     */
    private static Distribution posteriorSpread(Posterior posterior, String name, double medianValue)
            throws ValidationException {
        Posterior.Band band = posterior.band(name);
        double relative = Math.abs(band.median()) > 0.0
                ? (band.width() / BAND_SIGMAS) / Math.abs(band.median())
                : MINIMUM_RELATIVE_SIGMA;
        return Distribution.TruncatedNormal.relative(medianValue,
                Math.max(relative, MINIMUM_RELATIVE_SIGMA), 3.0);
    }

    private WindField resolveWindField(Long soundingId) throws SkyfixException {
        if (soundingId == null) {
            LOG.info("no sounding supplied; replaying in still air");
            return ConstantWindField.calm();
        }
        Optional<StoredSounding> stored = soundings.findById(soundingId);
        if (stored.isEmpty()) {
            throw ValidationException.field("sounding_id", soundingId,
                    "no sounding with that id has been ingested");
        }
        return SoundingWindField.of(stored.get().stationId(), stored.get().levels());
    }

    /**
     * One re-prediction, as the console and the report see it.
     *
     * @param epochUtc      the telemetry epoch it was made at
     * @param flightSeconds seconds since the first sample
     * @param posterior     the parameter estimate at that epoch
     * @param ellipses      the fitted footprint, one per confidence level
     */
    public record Update(Instant epochUtc, double flightSeconds, Posterior posterior,
                         List<LandingEllipse> ellipses) {

        /**
         * @param confidence the level to look for
         * @return that ellipse, if it was fitted
         */
        public Optional<LandingEllipse> ellipseAt(double confidence) {
            return ellipses.stream().filter(e -> e.confidence() == confidence).findFirst();
        }
    }

    /**
     * What a replay produced.
     *
     * @param run              the stored run record
     * @param finalPosterior   the estimate after the last assimilated sample
     * @param updates          one entry per re-prediction
     * @param burst            the detected burst, if there was one
     * @param assimilatedCount how many samples reached the filter
     * @param repredictionCount how many footprints were fitted
     * @param repredictMs      total wall clock spent re-predicting, milliseconds
     * @param threadCount      the ensemble pool size
     * @param windFieldName    which wind field was used
     */
    public record ReplayResult(RunRecord run, Posterior finalPosterior, List<Update> updates,
                               Optional<BurstEvent> burst, int assimilatedCount,
                               int repredictionCount, long repredictMs, int threadCount,
                               String windFieldName) {

        /** @param stored the saved run record @return a copy carrying it */
        public ReplayResult withRun(RunRecord stored) {
            return new ReplayResult(stored, finalPosterior, updates, burst, assimilatedCount,
                    repredictionCount, repredictMs, threadCount, windFieldName);
        }

        /** @return the mean wall clock per re-prediction, milliseconds */
        public long meanRepredictMs() {
            return repredictionCount == 0 ? 0 : repredictMs / repredictionCount;
        }

        /** @return the last re-prediction, if the replay made any */
        public Optional<Update> lastUpdate() {
            return updates.isEmpty() ? Optional.empty()
                    : Optional.of(updates.get(updates.size() - 1));
        }
    }
}
