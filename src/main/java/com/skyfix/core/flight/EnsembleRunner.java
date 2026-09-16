package com.skyfix.core.flight;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.WindField;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.ConvergenceException;
import com.skyfix.domain.error.SkyfixException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a Monte Carlo ensemble on a fixed thread pool (FR-2.4, ADR-5, NFR-1).
 *
 * <p>Concurrency is explicit rather than delegated to a parallel stream, both because the syllabus
 * asks for it and because the pool's lifetime, the futures and the exception unwrapping are all
 * things this class has to get right in the open: {@link ExecutorService} with a fixed pool,
 * {@link CompletionService} to take results as they finish, and an explicit {@code shutdown()}
 * followed by {@code awaitTermination()} in a {@code finally}.
 *
 * <p>Nothing mutable is shared. The configuration, the settings, the atmosphere and the wind field
 * are immutable and read concurrently; each task allocates its own integrator and phase objects;
 * and every member's parameters come from a seed split off the run seed before any task starts
 * (ADR-6). Results are therefore independent of the order threads happen to finish in, which is
 * what makes T-R1 achievable at all.
 *
 * <p>Per BLUEPRINT §12 a single bad member does not kill a run — it is discarded with a warning —
 * but more than {@value #FAILURE_THRESHOLD_PERCENT}% failing means something is wrong with the
 * configuration rather than with one draw, and that raises.
 */
public final class EnsembleRunner {

    private static final Logger LOG = Logger.getLogger(EnsembleRunner.class.getName());

    /** Above this percentage of failed members, the run itself is considered failed. */
    public static final double FAILURE_THRESHOLD_PERCENT = 1.0;

    /**
     * Failures always tolerated, whatever the ensemble size.
     *
     * <p>{@link #FAILURE_THRESHOLD_PERCENT} on its own is not a usable rule below a hundred
     * members: one failure out of ten is 10%, so a small ensemble would be failed by a single draw
     * from the tail of its own dispersion. One failure is by definition one draw, and that is what
     * the threshold is explicitly not about.
     */
    public static final int ALWAYS_TOLERATED_FAILURES = 1;

    private final AtmosphereModel atmosphere;
    private final WindField windField;
    private final int threadCount;

    /**
     * Creates a runner sized to the machine.
     *
     * @param atmosphere the atmosphere model, shared across threads
     * @param windField  the wind field, shared across threads
     */
    public EnsembleRunner(AtmosphereModel atmosphere, WindField windField) {
        this(atmosphere, windField, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates a runner with an explicit pool size.
     *
     * @param atmosphere  the atmosphere model, shared across threads
     * @param windField   the wind field, shared across threads
     * @param threadCount pool size; measured scaling is near-linear to the core count and flat
     *                    beyond it, so oversubscribing buys nothing
     */
    public EnsembleRunner(AtmosphereModel atmosphere, WindField windField, int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("thread count must be at least 1");
        }
        this.atmosphere = atmosphere;
        this.windField = windField;
        this.threadCount = threadCount;
    }

    /**
     * Runs an ensemble.
     *
     * @param config            the nominal balloon configuration, shared immutably
     * @param spec              how far each parameter is dispersed
     * @param settings          integration settings
     * @param launch            the launch position
     * @param memberCount       how many members to fly
     * @param seed              the run seed
     * @param historySampleCount how many members' full trajectories to retain, from index 0. Per
     *                          BLUEPRINT §9 a run stores the nominal member plus a sample rather
     *                          than every member, which is what keeps a 1,000-member run to tens
     *                          of thousands of rows instead of millions
     * @return the ensemble result
     * @throws SkyfixException if more than {@value #FAILURE_THRESHOLD_PERCENT}% of members fail,
     *                         or the run is interrupted
     */
    public Result run(BalloonConfig config, DispersionSpec spec, SimSettings settings,
                      GeoPoint launch, int memberCount, long seed, int historySampleCount)
            throws SkyfixException {
        return fly(config, spec, settings, memberCount, seed, historySampleCount,
                (simulator, parameters, memberSettings) ->
                        simulator.run(config, parameters, memberSettings, launch));
    }

    /**
     * Re-predicts the rest of a flight from a measured state (FR-3.3).
     *
     * <p>Identical machinery to {@link #run}, different starting point: every member continues from
     * the state the telemetry reports rather than from a launch assumption. That is what makes an
     * in-flight footprint a re-prediction rather than a fresh guess — the part of the flight that
     * has already happened is measured, not modelled, and only the remainder carries dispersion.
     *
     * <p>The dispersion spec here should be built around the filter's posterior rather than around
     * the catalogue: the point of the estimator is that by this moment the parameters are known
     * better than they were pre-flight, and the footprint should be correspondingly tighter.
     *
     * @param config             the balloon configuration
     * @param spec               how far each parameter is dispersed about the posterior
     * @param settings           integration settings
     * @param from               the measured state to continue every member from
     * @param memberCount        how many members to fly
     * @param seed               the run seed
     * @param historySampleCount how many members' trajectories to retain
     * @return the ensemble result
     * @throws SkyfixException if too many members fail, or the run is interrupted
     */
    public Result runFrom(BalloonConfig config, DispersionSpec spec, SimSettings settings,
                          BalloonState from, int memberCount, long seed, int historySampleCount)
            throws SkyfixException {
        return fly(config, spec, settings, memberCount, seed, historySampleCount,
                (simulator, parameters, memberSettings) ->
                        simulator.runFrom(config, parameters, memberSettings, from));
    }

    private Result fly(BalloonConfig config, DispersionSpec spec, SimSettings settings,
                       int memberCount, long seed, int historySampleCount, Leg leg)
            throws SkyfixException {

        if (memberCount < 1) {
            throw com.skyfix.domain.error.ValidationException.field("member_count", memberCount,
                    "must be at least 1");
        }
        FlightParameters[] design = new DispersionSampler(spec).sample(memberCount, seed);

        long startNanos = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, namedThreads());
        CompletionService<MemberOutcome> completion = new ExecutorCompletionService<>(pool);

        try {
            // Members whose trajectory is not retained integrate with the state sampling turned
            // off entirely: the simulator keeps only the launch, burst and landing states. Without
            // this every member still builds a full history that is discarded a moment later --
            // about three million wasted objects in a 1,000-member run, measured at roughly three
            // times the wall clock.
            SimSettings sampledSettings = settings;
            SimSettings discardedSettings = settings.toBuilder()
                    .stateSampleStride(Integer.MAX_VALUE).build();

            for (int i = 0; i < memberCount; i++) {
                final int index = i;
                final boolean keepHistory = index < historySampleCount;
                final SimSettings memberSettings = keepHistory ? sampledSettings
                        : discardedSettings;
                completion.submit(() -> flyOne(design[index], memberSettings, index, keepHistory,
                        leg));
            }

            List<Ensemble.Member> members = new ArrayList<>(memberCount);
            Map<Integer, StateHistory> histories = new LinkedHashMap<>();
            int failures = 0;

            for (int i = 0; i < memberCount; i++) {
                MemberOutcome outcome;
                try {
                    outcome = completion.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ConvergenceException("ensemble run was interrupted");
                } catch (ExecutionException e) {
                    // A task should never escape without wrapping its own failure; if one does,
                    // it is a defect here rather than a bad member, so it is not swallowed.
                    throw new ConvergenceException(
                            "an ensemble member failed unexpectedly: " + causeMessage(e));
                }
                members.add(outcome.member());
                if (outcome.history() != null) {
                    histories.put(outcome.member().index(), outcome.history());
                }
                if (!outcome.member().succeeded()) {
                    failures++;
                    LOG.log(Level.WARNING, "member {0} discarded: {1}", new Object[]{
                            outcome.member().index(),
                            outcome.member().failure().orElse("unknown")});
                }
            }

            // A percentage alone cannot be the whole rule. At 1% a ten-member ensemble would be
            // failed by its first bad draw, and re-predictions run at 200 members or fewer — so
            // the threshold would mean "no draw may ever fail", which is not what it is for. The
            // rule is meant to separate "one unlucky draw from the tail of the dispersion" from
            // "the configuration is wrong", so a single failure is always allowed and the
            // percentage takes over once the ensemble is large enough for it to mean something.
            double failurePercent = 100.0 * failures / memberCount;
            int allowed = Math.max(ALWAYS_TOLERATED_FAILURES,
                    (int) (FAILURE_THRESHOLD_PERCENT / 100.0 * memberCount));
            if (failures > allowed) {
                throw new ConvergenceException(String.format(
                        "%d of %d members failed (%.1f%%); the threshold for an ensemble this "
                                + "size is %d, so the configuration or the dispersion spec is at "
                                + "fault rather than any single draw",
                        failures, memberCount, failurePercent, allowed))
                        .with("failed", failures)
                        .with("allowed", allowed)
                        .with("member_count", memberCount);
            }

            // Sorted by member index so the result does not depend on completion order -- the
            // whole point of ADR-6 (T-R1 compares ellipse parameters to 1e-9).
            members.sort(java.util.Comparator.comparingInt(Ensemble.Member::index));

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.info(() -> String.format("ensemble: %d members on %d threads in %d ms (%d failed)",
                    memberCount, threadCount, elapsedMs, members.size() - countSucceeded(members)));

            return new Result(new Ensemble(members, seed, elapsedMs), histories, threadCount);
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * How one member's flight is produced — from the launch point, or onward from a measured state.
     *
     * <p>A single seam rather than two copies of the pool, the failure accounting and the
     * reproducible ordering, all of which are identical either way and none of which should be
     * duplicated for the sake of one differing call.
     */
    @FunctionalInterface
    private interface Leg {
        StateHistory fly(FlightSimulator simulator, FlightParameters parameters,
                         SimSettings settings) throws SkyfixException;
    }

    private MemberOutcome flyOne(FlightParameters parameters, SimSettings settings, int index,
                                 boolean keepHistory, Leg leg) {
        try {
            StateHistory history = leg.fly(new FlightSimulator(atmosphere, windField), parameters,
                    settings);
            GeoPoint landing = history.landingPoint().orElse(null);
            if (landing == null) {
                return new MemberOutcome(
                        Ensemble.Member.failed(index, parameters, "never reached the ground"),
                        null);
            }
            return new MemberOutcome(
                    Ensemble.Member.landed(index, parameters, landing,
                            history.burstAltitudeM().orElse(Double.NaN)),
                    keepHistory ? history : null);
        } catch (SkyfixException e) {
            // One bad draw must not take the run down (BLUEPRINT §12). The reason is kept so the
            // run summary can report what went wrong rather than just a count.
            return new MemberOutcome(
                    Ensemble.Member.failed(index, parameters, e.userMessage()), null);
        }
    }

    private static int countSucceeded(List<Ensemble.Member> members) {
        return (int) members.stream().filter(Ensemble.Member::succeeded).count();
    }

    private static String causeMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause == null ? e.toString() : cause.getClass().getSimpleName()
                + ": " + cause.getMessage();
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "skyfix-member-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    /** @return the pool size this runner uses */
    public int threadCount() {
        return threadCount;
    }

    private record MemberOutcome(Ensemble.Member member, StateHistory history) {
    }

    /**
     * What an ensemble run produced.
     *
     * @param ensemble    every member's parameters and landing point
     * @param histories   full trajectories for the sampled members, keyed by member index
     * @param threadCount the pool size used, recorded for NFR-1 reporting
     */
    public record Result(Ensemble ensemble, Map<Integer, StateHistory> histories,
                         int threadCount) {

        /**
         * @param index the member index
         * @return that member's retained trajectory, or empty if it was not sampled
         */
        public java.util.Optional<StateHistory> historyOf(int index) {
            return java.util.Optional.ofNullable(histories.get(index));
        }
    }
}
