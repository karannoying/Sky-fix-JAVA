package com.skyfix.estimation;

import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Several independent particle filters over one flight, pooled into one posterior (FR-3.2, T-V6).
 *
 * <h2>Why this exists</h2>
 *
 * <p>A single particle filter reports a band that is not an honest interval, and the measurement
 * is stark. Eight independently seeded filters run over the same DS-6 flight disagreed about the
 * ascent drag coefficient across the range 0.425 to 0.594 — a spread of 0.169 — while each one
 * reported a 5-95% band of width 0.0017. <strong>Each filter understated its own uncertainty by a
 * factor of about a hundred.</strong> Across the twenty DS-6 flights, a single filter's 5-95% band
 * contained the generating truth 0 times out of 20 for free lift, ascent Cd and burst scale.
 *
 * <p>The reason is that the dominant error is not statistical but algorithmic. Free lift and ascent
 * drag are near-degenerate (ADR-3), so the posterior is a long thin ridge; a finite particle set
 * resampled a hundred times performs a random walk along that ridge and stops somewhere arbitrary,
 * then reports the width of whatever it collapsed onto. The band measures the collapse, not the
 * uncertainty. No amount of particles in <em>one</em> filter fixes this, because one filter cannot
 * observe its own Monte Carlo scatter — but several can.
 *
 * <p>So the bank runs {@code k} filters that share nothing but the telemetry, and pools their
 * particles. The pooled set is a sample from the mixture of the individual posteriors, which means
 * the between-run scatter — the error that actually dominates — is inside the band by construction
 * rather than by hope.
 *
 * <h2>What it costs</h2>
 *
 * <p>Nothing, if the particle budget is split rather than multiplied. Eight filters of 63 particles
 * cost the same sequential work as one filter of 500, and they run in parallel on the pool, so the
 * wall clock is better. The trade is that each member filter is individually worse — fewer
 * particles means a noisier median — against a band that means something. For the quantity T-V5
 * gates on, burst altitude, the medians were already good enough that this is a clear win.
 *
 * <p>Threading is an explicit fixed pool with a {@link CompletionService} and a deliberate
 * shutdown (ADR-5). Each member filter is single-threaded and touches nothing the others touch;
 * only the pooling reads across them, and that happens after every task has completed.
 */
public final class FilterBank implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FilterBank.class.getName());

    /**
     * How many independent filters a bank runs by default.
     *
     * <p>Measured across the twenty DS-6 flights, counting how often the 5-95% band contained the
     * value each flight was generated from (nominally 18 of 20):
     *
     * <pre>
     *   configuration            free lift  ascent Cd  burst scale  chute Cd   burst &lt;= 500 m
     *   1 filter  x 500              0/20       0/20        0/20       5/20        20/20
     *   8 filters x  62 (500)       13/20      16/20       14/20      16/20        19/20
     *   16 filters x 125 (2000)     17/20      17/20       15/20      20/20        20/20
     * </pre>
     *
     * <p>Sixteen is where both halves come good: the band percentiles stop being estimated from a
     * handful of draws, and each member still carries enough particles that the medians do not
     * suffer — burst altitude returns to 20 of 20. The eight-filter row is worth keeping in view
     * because it shows the calibration is bought by <em>splitting</em> the budget, not by spending
     * more: at an unchanged 500 particles, coverage went from 0/20 to 13-16/20.
     */
    public static final int DEFAULT_FILTER_COUNT = 16;

    /**
     * Spacing between member seeds.
     *
     * <p>A prime stride so that consecutive banks, whose base seeds differ by a small amount, do
     * not end up sharing member seeds — which would make two "independent" runs correlated and
     * quietly narrow the very spread this class exists to measure.
     */
    private static final long SEED_STRIDE = 7919L;

    private final List<ParticleFilter> filters;
    private final BalloonConfig config;
    private final ExecutorService pool;
    private final int threadCount;

    private Posterior posterior;
    private int lastImpossibleCount;

    private FilterBank(List<ParticleFilter> filters, BalloonConfig config, int threadCount) {
        this.filters = List.copyOf(filters);
        this.config = config;
        this.threadCount = threadCount;
        this.pool = Executors.newFixedThreadPool(threadCount, namedThreads());
    }

    /**
     * Builds a bank by splitting a particle budget across independent filters.
     *
     * <p>The budget is the total across the bank, not per filter, so swapping a single filter for a
     * bank does not change what the replay costs — only what the band means.
     *
     * @param template      a builder carrying the shared configuration; its particle count and seed
     *                      are overridden per member
     * @param config        the balloon configuration, needed to read burst scale off a parameter set
     * @param filterCount   how many independent filters to run
     * @param totalParticles the particle budget to divide between them
     * @param seed          the run seed; member {@code k} derives its own from it
     * @param threadCount   pool size; at most one thread per filter is useful
     * @return the bank
     * @throws ValidationException if the counts do not leave each filter a usable particle set
     */
    public static FilterBank of(ParticleFilter.Builder template, BalloonConfig config,
                                int filterCount, int totalParticles, long seed, int threadCount)
            throws ValidationException {
        if (filterCount < 1) {
            throw ValidationException.field("filter_count", filterCount, "must be at least 1");
        }
        int perFilter = totalParticles / filterCount;
        if (perFilter < 2) {
            throw ValidationException.field("particles", totalParticles,
                    "split across " + filterCount + " filters leaves " + perFilter
                            + " particles each; at least two are needed for a distribution");
        }

        List<ParticleFilter> members = new ArrayList<>(filterCount);
        for (int k = 0; k < filterCount; k++) {
            members.add(template.particleCount(perFilter)
                    .seed(seed + k * SEED_STRIDE)
                    .build());
        }
        LOG.info(() -> String.format("filter bank: %d filters x %d particles", filterCount,
                perFilter));
        return new FilterBank(members, config, Math.max(1, Math.min(threadCount, filterCount)));
    }

    /**
     * Draws every member's prior set at the launch point.
     *
     * @param launch      where the flight starts
     * @param launchEpoch the UTC time of release
     * @throws SkyfixException if the configuration is unflyable at the launch site
     */
    public void start(GeoPoint launch, Instant launchEpoch) throws SkyfixException {
        for (ParticleFilter filter : filters) {
            filter.start(launch, launchEpoch);
        }
        posterior = pool(launchEpoch);
    }

    /** Tells every member that the telemetry has shown a burst (FR-3.4). */
    public void burstObserved() {
        filters.forEach(ParticleFilter::burstObserved);
    }

    /**
     * Advances every member and pools the result.
     *
     * @param observed the telemetry sample to assimilate
     * @return the pooled posterior
     * @throws SkyfixException if a member fails; one filter losing the flight is not something to
     *                         paper over by averaging it with the others
     */
    public Posterior update(Observation observed) throws SkyfixException {
        CompletionService<Integer> completion = new ExecutorCompletionService<>(pool);
        for (ParticleFilter filter : filters) {
            completion.submit(() -> {
                filter.update(observed);
                return filter.impossibleCount();
            });
        }

        int impossible = 0;
        SkyfixException failure = null;
        for (int i = 0; i < filters.size(); i++) {
            try {
                impossible += completion.take().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new com.skyfix.domain.error.ConvergenceException(
                        "filter bank was interrupted at " + observed.epochUtc());
            } catch (ExecutionException e) {
                // Drain the rest before rethrowing, so no task is still writing into a filter the
                // caller is about to read.
                failure = asSkyfix(e);
            }
        }
        if (failure != null) {
            throw failure;
        }

        lastImpossibleCount = impossible;
        posterior = pool(observed.epochUtc());
        return posterior;
    }

    /**
     * Pools every member's particles into one weighted sample.
     *
     * <p>Each filter contributes its own normalised weights scaled by {@code 1/k}, so the members
     * count equally however many particles each carries. The pooled set is then a sample from the
     * even mixture of the member posteriors, and its spread includes the disagreement between them
     * — which is the whole point.
     */
    private Posterior pool(Instant epoch) {
        int total = 0;
        for (ParticleFilter filter : filters) {
            total += filter.particleCount();
        }

        double[][] values = new double[4][total];
        double[] weights = new double[total];
        double share = 1.0 / filters.size();
        int resamples = 0;
        int at = 0;

        for (ParticleFilter filter : filters) {
            Particle[] particles = filter.particles();
            double[] memberWeights = filter.weights();
            resamples += filter.resampleCount();
            for (int i = 0; i < particles.length; i++) {
                FlightParameters p = particles[i].parameters();
                values[0][at] = p.freeLiftKg();
                values[1][at] = p.ascentCd();
                values[2][at] = p.burstDiameterM() / config.burstDiameterM();
                values[3][at] = p.chuteCd();
                weights[at] = memberWeights[i] * share;
                at++;
            }
        }

        return Posterior.of(epoch,
                bandOf(values[0], weights),
                bandOf(values[1], weights),
                bandOf(values[2], weights),
                bandOf(values[3], weights),
                SystematicResampler.effectiveSampleSize(weights),
                resamples, total);
    }

    private static Posterior.Band bandOf(double[] values, double[] weights) {
        int[] order = WeightedQuantile.order(values);
        return new Posterior.Band(
                WeightedQuantile.of(values, weights, order, 0.50),
                WeightedQuantile.of(values, weights, order, 0.05),
                WeightedQuantile.of(values, weights, order, 0.95));
    }

    private static SkyfixException asSkyfix(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SkyfixException skyfix) {
            return skyfix;
        }
        return new com.skyfix.domain.error.ConvergenceException(
                "a filter in the bank failed unexpectedly: " + cause);
    }

    /**
     * The vertical rate the pooled set believes, in m/s.
     *
     * <p>See {@link ParticleFilter#weightedVerticalRateMs()} for why this exists rather than using
     * the telemetry's own differenced rate. Members are weighted equally, as everywhere else in
     * the pooling.
     *
     * @return the mean of the members' weighted mean vertical rates
     */
    public double weightedVerticalRateMs() {
        double total = 0.0;
        for (ParticleFilter filter : filters) {
            total += filter.weightedVerticalRateMs();
        }
        return total / filters.size();
    }

    /** @return the pooled posterior after the most recent update */
    public Posterior posterior() {
        return posterior;
    }

    /** @return how many filters the bank runs */
    public int filterCount() {
        return filters.size();
    }

    /** @return the pool size */
    public int threadCount() {
        return threadCount;
    }

    /** @return particles refuted across the whole bank at the most recent update */
    public int impossibleCount() {
        return lastImpossibleCount;
    }

    /** @return whether the bank has been told the telemetry showed a burst */
    public boolean hasObservedBurst() {
        return filters.get(0).hasObservedBurst();
    }

    /**
     * The member filters, for diagnostics that need to see the disagreement rather than the pool.
     *
     * @return the filters, in seed order
     */
    public List<ParticleFilter> filters() {
        return filters;
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(1, TimeUnit.MINUTES)) {
                pool.shutdownNow();
                LOG.warning("filter bank pool did not shut down cleanly");
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "skyfix-filter-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
