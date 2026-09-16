package com.skyfix.app;

import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.PredictionError;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.EllipseDao;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Scores predictions against what actually happened (FR-4.1, O4).
 *
 * <p>Answers one question: was watching the flight worth it? A frozen pre-flight footprint and the
 * series of in-flight re-predictions are scored against the same landing point with the same
 * haversine, so the comparison carries no advantage either way. The reduction is reported as a
 * fraction, because flights differ by an order of magnitude in how far they drift.
 *
 * <p>Everything here reads from the database rather than from a live object graph, which is what
 * FR-4.3 asks for: a run scores from its stored rows alone, so a result can be re-examined months
 * later without re-flying anything.
 */
public final class ReportService {

    /** The confidence level whose ellipse is scored; the 95% one is what a reader acts on. */
    public static final double SCORED_CONFIDENCE = 0.95;

    private final EllipseDao ellipses;

    /**
     * @param database the database to read from
     */
    public ReportService(Database database) {
        this.ellipses = new EllipseDao(database);
    }

    /**
     * Scores a replay's re-predictions against the actual landing point.
     *
     * <p>No burst epoch, so every update is treated as pre-burst and
     * {@link PredictionError#atBurst()} comes back empty rather than wrong.
     *
     * @param replayRunId      the replay run
     * @param preflightRunId   the frozen pre-flight run to compare against
     * @param actual           where the payload actually landed
     * @param launchEpoch      the first telemetry epoch, which fixes t = 0
     * @param groundElevationM ground elevation, metres
     * @return the scored series
     * @throws SkyfixException if either run has no scoreable footprint
     */
    public PredictionError score(long replayRunId, long preflightRunId, GeoPoint actual,
                                 Instant launchEpoch, double groundElevationM)
            throws SkyfixException {
        return score(replayRunId, preflightRunId, actual, launchEpoch, null, groundElevationM);
    }

    /**
     * Scores a replay whose burst epoch is known, so post-burst updates can be marked.
     *
     * <p>The burst epoch cannot be read back from the ellipse table — it is a property of the
     * telemetry, not of any footprint — so a caller that detected it passes it in.
     *
     * @param replayRunId      the replay run
     * @param preflightRunId   the frozen pre-flight run
     * @param actual           where the payload actually landed
     * @param launchEpoch      the first telemetry epoch
     * @param burstEpoch       when the telemetry showed burst, or {@code null} if it never did
     * @param groundElevationM ground elevation, metres
     * @return the scored series
     * @throws SkyfixException if either run has no scoreable footprint
     */
    public PredictionError score(long replayRunId, long preflightRunId, GeoPoint actual,
                                 Instant launchEpoch, Instant burstEpoch, double groundElevationM)
            throws SkyfixException {

        double frozenErrorM = errorM(
                preflightEllipse(preflightRunId, groundElevationM).centre(), actual);

        List<PredictionError.Update> updates = new ArrayList<>();
        for (EllipseDao.TimedEllipse timed : ellipses.findTimeline(replayRunId, groundElevationM)) {
            if (timed.isPreflight() || timed.ellipse().confidence() != SCORED_CONFIDENCE) {
                continue;
            }
            double flightSeconds = Duration.between(launchEpoch, timed.epochUtc()).toNanos() / 1e9;
            boolean afterBurst = burstEpoch != null && !timed.epochUtc().isBefore(burstEpoch);
            updates.add(new PredictionError.Update(timed.epochUtc(), flightSeconds,
                    errorM(timed.ellipse().centre(), actual), timed.ellipse().semiMajorM(),
                    afterBurst));
        }
        if (updates.isEmpty()) {
            throw new ValidationException("replay run " + replayRunId
                    + " stored no in-flight footprint to score")
                    .with("run_id", replayRunId);
        }
        return new PredictionError(replayRunId, actual, frozenErrorM, updates);
    }

    /**
     * The median of a set of values.
     *
     * <p>The median rather than the mean, throughout this scoring, because landing errors are
     * heavy-tailed: one flight whose wind field was badly wrong would drag a mean far enough to
     * hide what happened on the other nineteen. O4 asks for a median reduction for that reason.
     *
     * @param values the values; not modified
     * @return the median, or NaN if there are none
     */
    public static double median(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : 0.5 * (sorted[mid - 1] + sorted[mid]);
    }

    /**
     * The frozen pre-flight footprint at the scored confidence.
     *
     * @throws ValidationException if the run stored no such ellipse
     */
    private LandingEllipse preflightEllipse(long runId, double groundElevationM)
            throws SkyfixException {
        Optional<LandingEllipse> found = ellipses.findTimeline(runId, groundElevationM).stream()
                .filter(EllipseDao.TimedEllipse::isPreflight)
                .map(EllipseDao.TimedEllipse::ellipse)
                .filter(e -> e.confidence() == SCORED_CONFIDENCE)
                .findFirst();
        if (found.isEmpty()) {
            throw new ValidationException("run " + runId + " has no pre-flight "
                    + (int) (SCORED_CONFIDENCE * 100) + "% ellipse to score against")
                    .with("run_id", runId);
        }
        return found.get();
    }

    private static double errorM(GeoPoint predicted, GeoPoint actual) {
        return Geodesy.haversineMetres(predicted.latitudeDeg(), predicted.longitudeDeg(),
                actual.latitudeDeg(), actual.longitudeDeg());
    }
}
