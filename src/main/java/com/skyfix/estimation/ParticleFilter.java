package com.skyfix.estimation;

import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Distribution;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.Gaussian;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Phase;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.ConvergenceException;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A bootstrap particle filter over the four flight parameters (FR-3.2, ADR-3).
 *
 * <p>Each particle is one hypothesis about the flight: a parameter set and the state that
 * parameter set has reached. An update propagates every particle to the observation epoch, scores
 * it against the telemetry with a {@link MeasurementModel}, and — when the effective sample size
 * has fallen below half the particle count — resamples and jitters. What comes out is a
 * {@link Posterior}: a median and a 5–95% band per parameter, not a point estimate.
 *
 * <h2>Why a particle filter</h2>
 *
 * <p>Burst is a hard discontinuity in the dynamics, not a smooth nonlinearity, and before it
 * happens the burst-diameter posterior is genuinely uninformative — no Kalman variant represents
 * either honestly. A bootstrap filter needs no Jacobians and carries whatever shape the posterior
 * actually has, at the cost of being O(N x forward-step) per update. ADR-3 caps N at 500 to hold
 * that cost inside FR-3.3's budget.
 *
 * <h2>What it does and does not estimate</h2>
 *
 * <p>Four parameters, exactly the ones FR-3.2 names and the {@code estimate} table stores: free
 * lift, ascent drag coefficient, burst diameter (as a multiple of the catalogue figure) and
 * parachute drag coefficient. The wind scale is deliberately <em>not</em> estimated. It is a
 * property of the sounding rather than of the balloon, a single flight's horizontal track is a
 * weak and heavily aliased observation of it, and letting the filter absorb wind error into a
 * balloon parameter is exactly the failure this project is meant to avoid. Wind uncertainty stays
 * where ADR-7 put it — dispersed across the forward ensemble — so the re-predicted footprint still
 * carries it.
 *
 * <h2>Identifiability, stated rather than hidden</h2>
 *
 * <p>Two of the four parameters are unobservable for most of the flight, and the posterior says so
 * instead of pretending otherwise. Burst diameter has no effect on the trajectory until the
 * envelope reaches it, so its band stays at the prior all the way up and collapses at burst.
 * Parachute drag has no effect before burst at all. Ascent drag is the mirror image: informative
 * during ascent, frozen afterwards. A band that has not moved is the correct answer to a question
 * the data has not yet asked.
 *
 * <p>Nothing forces a particle to burst when the real balloon does. A hypothesis that says the
 * envelope was still growing while the telemetry shows it falling is refuted by its own vertical
 * rate within a sample or two, and that refutation <em>is</em> the measurement of burst diameter.
 * Overriding it with a detected burst event would destroy the only signal there is.
 *
 * <h2>Threading</h2>
 *
 * <p>Not thread-safe, and deliberately single-threaded: one instance belongs to one replay. The
 * parallelism in this project sits in {@link com.skyfix.core.flight.EnsembleRunner}, where the
 * re-prediction runs; splitting a filter update across threads as well would contend with it for
 * the same cores and make neither reproducible without a great deal of care for no measured gain.
 */
public final class ParticleFilter {

    private static final Logger LOG = Logger.getLogger(ParticleFilter.class.getName());

    /** The particle count ADR-3 caps the filter at. */
    public static final int DEFAULT_PARTICLE_COUNT = 500;

    /** Resample when the effective sample size falls below this fraction of N (FR-3.2). */
    public static final double DEFAULT_RESAMPLE_THRESHOLD = 0.5;

    /**
     * The Liu–West discount factor that sets how hard the jitter kernel shrinks towards the mean.
     *
     * <p>0.98 is the value Liu and West recommend for parameter learning, and it is a deliberate
     * near-1: the shrinkage exists to stop resampling from inflating the posterior variance, not
     * to move the posterior.
     */
    public static final double DEFAULT_SHRINKAGE = 0.98;

    /**
     * A small floor on the jitter kernel, as a multiple of a dimension's prior spread, guarding
     * against sample impoverishment once the data has narrowed it.
     *
     * <p>This is insurance, not the main mechanism. The dimensions that would otherwise collapse
     * outright are handled exactly rather than by roughening — see
     * {@link #redrawsFromPrior(int)}. What is left is ordinary particle impoverishment in the
     * dimensions the data <em>is</em> informing, where a floor at a few per cent of the prior
     * keeps a live spread without stopping the posterior from narrowing.
     *
     * <p>The number follows from Liu–West's own arithmetic. Shrinking towards the weighted mean by
     * {@code a} and adding noise of width {@code h s}, with {@code h = sqrt(1 - a^2)}, reaches a
     * variance satisfying {@code v = a^2 v + h^2 s^2}; flooring {@code s} at
     * {@code f sigma_prior} therefore floors the spread at {@code f sigma_prior}. So {@code f} is
     * read directly as "the narrowest band this filter will ever claim, as a fraction of the
     * prior" — 2%, which is well inside T-V5's thresholds and well outside anything 500 particles
     * could honestly resolve.
     */
    public static final double DEFAULT_ROUGHENING = 0.02;

    private static final int FREE_LIFT = 0;
    private static final int ASCENT_CD = 1;
    private static final int BURST_SCALE = 2;
    private static final int CHUTE_CD = 3;
    private static final int DIMENSIONS = 4;

    /**
     * Smallest conditional mass the censored burst-diameter prior is allowed to keep.
     *
     * <p>An envelope that has grown past the prior's upper truncation has refuted the whole prior:
     * the conditional support is empty and the draw would be undefined. Reserving a sliver of mass
     * at the top keeps the filter running and pins those particles at the largest burst diameter
     * the prior admits, which is the closest thing to the truth the prior can express — and the
     * posterior piling up against its own upper bound is a visible, honest signal that the prior
     * was too narrow, rather than a crash.
     */
    private static final double MIN_CONDITIONAL_MASS = 1e-6;

    private final BalloonConfig config;
    private final FlightSimulator simulator;
    private final SimSettings settings;
    private final MeasurementModel model;
    private final Resampler resampler;
    private final DispersionSpec prior;
    private final int particleCount;
    private final double resampleThresholdFraction;
    private final double shrinkage;
    private final double roughening;
    private final double windScale;
    private final long seed;

    private final double[] lowerBound = new double[DIMENSIONS];
    private final double[] upperBound = new double[DIMENSIONS];
    private final double[] spreadFloor = new double[DIMENSIONS];

    private SplittableRandom random;
    private Particle[] particles;
    private double[] weights;
    private GeoPoint launchPoint;
    private Instant launchEpoch;
    private AtmosphericState launchAir;
    private int resampleCount;
    private int impossibleCount;
    private boolean burstObserved;
    private Posterior posterior;

    private ParticleFilter(Builder b) {
        this.config = b.config;
        this.simulator = b.simulator;
        this.settings = b.settings;
        this.model = b.model;
        this.resampler = b.resampler;
        this.prior = b.prior;
        this.particleCount = b.particleCount;
        this.resampleThresholdFraction = b.resampleThresholdFraction;
        this.shrinkage = b.shrinkage;
        this.roughening = b.roughening;
        this.windScale = b.windScale;
        this.seed = b.seed;

        double nominalBurst = config.burstDiameterM();
        lowerBound[FREE_LIFT] = prior.freeLiftKg().minimum();
        upperBound[FREE_LIFT] = prior.freeLiftKg().maximum();
        lowerBound[ASCENT_CD] = prior.ascentCd().minimum();
        upperBound[ASCENT_CD] = prior.ascentCd().maximum();
        lowerBound[BURST_SCALE] = prior.burstDiameterM().minimum() / nominalBurst;
        upperBound[BURST_SCALE] = prior.burstDiameterM().maximum() / nominalBurst;
        lowerBound[CHUTE_CD] = prior.chuteCd().minimum();
        upperBound[CHUTE_CD] = prior.chuteCd().maximum();

        spreadFloor[FREE_LIFT] = roughening * prior.freeLiftKg().spread();
        spreadFloor[ASCENT_CD] = roughening * prior.ascentCd().spread();
        spreadFloor[BURST_SCALE] = roughening * prior.burstDiameterM().spread() / nominalBurst;
        spreadFloor[CHUTE_CD] = roughening * prior.chuteCd().spread();
    }

    /** @return a builder for a filter */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Draws the prior particle set at the launch point.
     *
     * <p>The draw is a Latin hypercube rather than independent samples, for the same reason the
     * pre-flight ensemble uses one: at five hundred particles over four dimensions, stratifying
     * each margin costs nothing and removes the clumping that would otherwise leave part of the
     * prior unrepresented before a single observation has been seen.
     *
     * @param launch      where the flight starts; its altitude is the release altitude
     * @param launchEpoch the UTC time of release, which fixes t = 0 for every later observation
     * @throws SkyfixException if the configuration is unflyable at the launch site
     */
    public void start(GeoPoint launch, Instant launchEpoch) throws SkyfixException {
        this.launchPoint = launch;
        this.launchEpoch = launchEpoch;
        this.launchAir = simulator.atmosphere().stateAt(launch.altitudeM());
        this.random = new SplittableRandom(seed);
        this.resampleCount = 0;
        this.impossibleCount = 0;
        this.burstObserved = false;

        FlightParameters[] drawn =
                new com.skyfix.core.flight.DispersionSampler(prior).sample(particleCount, seed);
        particles = new Particle[particleCount];
        for (int i = 0; i < particleCount; i++) {
            // The wind scale is not estimated, so every particle carries the sounding as given
            // and the LHS draw for that dimension is discarded.
            FlightParameters p = drawn[i].withWindScale(windScale);
            particles[i] = Particle.of(p, simulator.launchState(config, p, launch));
        }
        weights = new double[particleCount];
        Arrays.fill(weights, 1.0 / particleCount);
        posterior = summarise(launchEpoch);
    }

    /**
     * Tells the filter that the telemetry has shown the balloon burst (FR-3.4).
     *
     * <p>This is the moment burst diameter and parachute drag stop being unobservable, and the
     * filter needs it from outside because it cannot reliably infer it from its own particles. The
     * <em>first</em> particle to burst is by construction the one holding the smallest burst
     * diameter in the set — typically three sigma below the prior mean — so "some particle has
     * burst" fires far too early, freezing the dimension on a value the real flight has said
     * nothing about yet. Measured on {@code ds6-flight-01}, arming on the first particle left the
     * recovered burst altitude 1,320 m low.
     *
     * <p>What does know is the telemetry, through {@link BurstDetector}: a sustained sign change in
     * the smoothed vertical rate is the observation that makes burst diameter measurable. This is
     * the {@code switchPhase} arrow in the BLUEPRINT §8 replay sequence, with the semantics the
     * physics actually wants — it does not force a single particle into descent, because a particle
     * that disagrees about when to burst is a hypothesis the data is entitled to refute. It only
     * says that the question has now been asked.
     *
     * <p>Idempotent, and harmless to call before {@link #start}. A filter never told about a burst
     * keeps reporting the prior for both dimensions, which is the correct answer when nothing has
     * detected a burst.
     */
    public void burstObserved() {
        burstObserved = true;
    }

    /** @return whether the telemetry has reported a burst to this filter */
    public boolean hasObservedBurst() {
        return burstObserved;
    }

    /**
     * Propagates, weights and — if the set has degenerated — resamples (FR-3.2).
     *
     * @param observed the telemetry sample to assimilate
     * @return the posterior after this update
     * @throws SkyfixException if {@link #start} has not been called, the observation predates
     *                         launch, or every particle was refuted at once
     */
    public Posterior update(Observation observed) throws SkyfixException {
        if (particles == null) {
            throw new IllegalStateException("start(...) must be called before update(...)");
        }
        double targetTime = flightTimeOf(observed.epochUtc());
        if (targetTime < 0.0) {
            throw new ValidationException("telemetry epoch " + observed.epochUtc()
                    + " precedes the launch epoch " + launchEpoch)
                    .with("epoch_utc", observed.epochUtc().toString())
                    .with("launch_epoch_utc", launchEpoch.toString());
        }

        impossibleCount = 0;
        for (int i = 0; i < particleCount; i++) {
            Particle p = particles[i];
            if (p.isImpossible()) {
                impossibleCount++;
                continue;
            }
            try {
                BalloonState advanced = simulator.advanceTo(config, p.parameters(), settings,
                        p.state(), targetTime);
                particles[i] = p.withState(advanced)
                        .weighted(model.logLikelihood(advanced, observed));
            } catch (SkyfixException e) {
                // A hypothesis that cannot be integrated is a hypothesis the flight has refuted —
                // a burst diameter so large the envelope leaves the atmosphere model's range, or a
                // drag so low the descent outruns the integrator's stability. Killing the whole
                // replay for it would throw away the 499 particles that are fine.
                LOG.log(Level.FINE, e, () -> "particle refuted while propagating: "
                        + e.getMessage());
                particles[i] = p.impossible();
                impossibleCount++;
            }
        }

        if (impossibleCount == particleCount) {
            throw new ConvergenceException(
                    "every particle was refuted by " + observed.epochUtc()
                            + "; the prior cannot explain this flight at all")
                    .with("epoch_utc", observed.epochUtc().toString())
                    .with("particle_count", particleCount);
        }

        double[] logWeights = new double[particleCount];
        for (int i = 0; i < particleCount; i++) {
            logWeights[i] = particles[i].logWeight();
        }
        weights = SystematicResampler.normaliseLogWeights(logWeights);

        double ess = SystematicResampler.effectiveSampleSize(weights);
        // Summarise before resampling: the weighted set is the posterior estimate, and resampling
        // only turns it into an equally weighted sample of the same distribution — at the cost of
        // some variance. Summarising first keeps that cost out of the reported bands.
        posterior = summarise(observed.epochUtc());

        if (ess < resampleThresholdFraction * particleCount) {
            resample(ess);
        }
        return posterior;
    }

    /**
     * Systematic resampling followed by Liu–West shrinkage jitter.
     *
     * <p>Resampling alone would make a parameter filter useless: a parameter never changes between
     * updates, so the set can only ever lose distinct values, and after a few dozen resamples every
     * particle carries the same number. Jitter puts diversity back. Adding plain noise, though,
     * inflates the posterior variance at every resample, so a filter that resamples a hundred times
     * ends up wider than its prior.
     *
     * <p>Liu and West's kernel fixes both at once. Shrinking each sample towards the weighted mean
     * by {@code a = (3 delta - 1) / (2 delta)} before adding noise of variance
     * {@code (1 - a^2) * s^2} leaves the mean and variance of the set exactly unchanged, so the
     * jitter buys diversity without buying width.
     */
    private void resample(double essBefore) throws SkyfixException {
        double[] mean = new double[DIMENSIONS];
        for (int d = 0; d < DIMENSIONS; d++) {
            for (int i = 0; i < particleCount; i++) {
                mean[d] += weights[i] * dimensionOf(particles[i].parameters(), d);
            }
        }

        double a = (3.0 * shrinkage - 1.0) / (2.0 * shrinkage);
        double kernel = Math.sqrt(Math.max(0.0, 1.0 - a * a));
        double[][] factor = jitterFactor(mean, kernel);

        int[] chosen = resampler.resample(weights, random);
        Particle[] next = new Particle[particleCount];
        double[] noise = new double[DIMENSIONS];
        for (int i = 0; i < particleCount; i++) {
            Particle source = particles[chosen[i]];
            for (int d = 0; d < DIMENSIONS; d++) {
                noise[d] = standardNormal();
            }
            double[] values = new double[DIMENSIONS];
            for (int d = 0; d < DIMENSIONS; d++) {
                double shrunk = a * dimensionOf(source.parameters(), d) + (1.0 - a) * mean[d];
                double correlated = 0.0;
                for (int k = 0; k <= d; k++) {
                    correlated += factor[d][k] * noise[k];
                }
                values[d] = clamp(shrunk + correlated, lowerBound[d], upperBound[d]);
            }
            if (redrawsFromPrior(CHUTE_CD)) {
                values[CHUTE_CD] = prior.chuteCd().quantile(uniform());
            }

            // The free lift is settled before the burst scale is drawn, because jittering it
            // resizes the envelope — and it is that resized diameter the censored burst-diameter
            // prior conditions on.
            FlightParameters withLift = parametersOf(values);
            BalloonState state = rescaleForFreeLift(source.state(), source.parameters(), withLift);
            if (redrawsFromPrior(BURST_SCALE)) {
                values[BURST_SCALE] = redrawBurstScale(state);
            }
            next[i] = source.resampledWith(parametersOf(values)).withState(state);
        }
        particles = next;
        Arrays.fill(weights, 1.0 / particleCount);
        resampleCount++;
        LOG.log(Level.FINE, () -> String.format(
                "resampled at ESS %.1f of %d (resample %d)", essBefore, particleCount,
                resampleCount));
    }

    /**
     * The lower-triangular factor of the jitter kernel's covariance.
     *
     * <p><strong>The kernel has to be the shape of the posterior, not a sphere.</strong> Free lift
     * and ascent drag are very nearly degenerate: more lift climbs faster, more drag climbs slower,
     * and over the ascent the two cancel almost exactly. Measured on {@code ds6-flight-01}, a 5%
     * error in ascent Cd absorbed by a 6% change in free lift reproduces the whole flight's
     * altitude profile to 10.5 m RMS — which is the GPS noise itself. The posterior is therefore
     * not a blob but a long thin ridge lying at an angle to both axes.
     *
     * <p>Jittering each dimension independently — with a diagonal kernel — is spherical, and a
     * spherical kernel on a ridge is destructive: almost every perturbation it proposes lands
     * <em>across</em> the ridge, where the likelihood kills it, so resampling grinds the set down
     * onto a point and the surviving point is wherever the random walk happened to be. That is not
     * a tuning problem, and no amount of extra particles or roughening fixes it; measured with a
     * diagonal kernel, the recovered ascent Cd scattered between 1% and 21% error across six
     * flights with no dependence on particle count.
     *
     * <p>So the kernel uses the full weighted covariance, which is what Liu and West specify:
     * {@code Cov = h^2 V}, realised by {@code h L eps} where {@code L L^T = V} is a Cholesky
     * factor and {@code eps} is standard normal. The proposal is then elongated along the ridge and
     * narrow across it, so particles move where the data is indifferent and stay put where it is
     * not.
     *
     * <p>The floor from {@link #DEFAULT_ROUGHENING} is added to the diagonal before factorising.
     * That does double duty: it guards against impoverishment, and it keeps the matrix positive
     * definite so the factorisation cannot fail on a set that has collapsed into a plane.
     */
    private double[][] jitterFactor(double[] mean, double kernel) {
        double[][] covariance = new double[DIMENSIONS][DIMENSIONS];
        for (int i = 0; i < particleCount; i++) {
            FlightParameters p = particles[i].parameters();
            for (int d = 0; d < DIMENSIONS; d++) {
                double dd = dimensionOf(p, d) - mean[d];
                for (int e = 0; e <= d; e++) {
                    covariance[d][e] += weights[i] * dd * (dimensionOf(p, e) - mean[e]);
                }
            }
        }
        for (int d = 0; d < DIMENSIONS; d++) {
            covariance[d][d] += spreadFloor[d] * spreadFloor[d];
            for (int e = 0; e < d; e++) {
                covariance[e][d] = covariance[d][e];
            }
        }
        return choleskyScaled(covariance, kernel);
    }

    /**
     * Cholesky decomposition of a symmetric positive-definite matrix, scaled by {@code kernel}.
     *
     * <p>{@code L} is lower triangular with {@code L L^T = kernel^2 * A}, so multiplying it by a
     * vector of independent standard normals gives a draw with covariance {@code kernel^2 A}. The
     * standard right-looking algorithm, written out rather than pulled from a library (CLAUDE.md
     * rule 1); at four dimensions it is a handful of operations per resample.
     *
     * <p>A diagonal entry that is not positive would mean the matrix is singular to rounding, which
     * the roughening floor should already have prevented. If it happens anyway the row is zeroed,
     * which degrades that direction to no jitter rather than producing NaNs that would silently
     * poison every particle.
     */
    private static double[][] choleskyScaled(double[][] a, double kernel) {
        int n = a.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    l[i][j] = sum > 0.0 ? Math.sqrt(sum) : 0.0;
                } else {
                    l[i][j] = l[j][j] > 0.0 ? sum / l[j][j] : 0.0;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                l[i][j] *= kernel;
            }
        }
        return l;
    }

    /**
     * Whether a dimension should be drawn afresh from the prior instead of inherited from a
     * surviving particle.
     *
     * <p>This is the filter's answer to the hardest problem in it. Burst diameter has <em>no
     * effect whatever</em> on the trajectory until the envelope reaches it, and parachute drag has
     * none until the canopy opens. While the telemetry has not yet shown a burst, the likelihood
     * is mathematically independent of both, so no particle is ever selected <em>for</em> them:
     * their values are passengers on particles chosen for their free lift and their drag, culled
     * for reasons that have nothing to do with them. Left to ordinary resampling, the filter
     * arrives at burst — the one instant burst diameter is measurable — holding a single arbitrary
     * value, and the measurement lands on an empty set. Measured on {@code ds6-flight-01} before
     * this rule existed: 8% error in burst scale, 1,721 m in burst altitude, against T-V5's 500 m.
     *
     * <p>So instead of inheriting them, the filter re-samples them from what is actually known
     * about them, which for parachute drag is the prior and for burst diameter is the prior
     * <em>conditioned on not having burst yet</em> — see {@link #redrawBurstScale}. Nothing here
     * is tuned. Once {@link #burstObserved()} fires, the likelihood depends on both and they revert
     * to ordinary Liu–West jitter around the values the data is now selecting.
     *
     * @param dimension the dimension index
     * @return {@code true} while the telemetry cannot yet distinguish values of that dimension
     */
    private boolean redrawsFromPrior(int dimension) {
        return !burstObserved && (dimension == BURST_SCALE || dimension == CHUTE_CD);
    }

    /**
     * Draws a burst scale from the prior conditioned on the envelope not having burst yet.
     *
     * <p>A plain redraw from the prior is wrong here, and wrong in a way that destroys the flight.
     * Burst is a threshold crossing: a particle bursts the moment its envelope diameter reaches its
     * burst diameter. Redraw that threshold afresh at every resample — a hundred and thirty times
     * over an ascent — and the particle bursts as soon as <em>any one</em> of those draws falls
     * below the diameter it has already reached. The burst time is then governed by the minimum of
     * many draws rather than by one, and the whole set bursts near the bottom of the prior.
     * Measured: every particle in flight 01 burst by 26 km against a true 30.8 km, and the
     * predicted altitude was fourteen kilometres below the telemetry by the real burst.
     *
     * <p>The error was in calling the pre-burst state uninformative. It is not — it is
     * <em>censored</em>. An envelope that has grown to diameter D without bursting is direct
     * evidence that its burst diameter exceeds D, and that evidence accumulates all the way up. So
     * the correct conditional prior is the prior truncated below at D, and sampling it needs
     * nothing more than the prior's own CDF: map D to its probability, draw uniformly above it,
     * map back. As the balloon climbs, D rises and the band narrows from below on its own — which
     * is not a trick to make the filter behave, but the actual information a rising balloon carries
     * about the envelope that has not yet failed.
     */
    private double redrawBurstScale(BalloonState state) {
        Distribution d = prior.burstDiameterM();
        double reachedM = state.phase() == Phase.ASCENT ? state.diameterM() : d.minimum();
        double pLow = Math.min(d.cdf(reachedM), 1.0 - MIN_CONDITIONAL_MASS);
        double u = pLow + uniform() * (1.0 - pLow);
        return d.quantile(Math.min(Math.max(u, Double.MIN_NORMAL), Math.nextDown(1.0)))
                / config.burstDiameterM();
    }

    /**
     * Keeps a particle's envelope consistent with its jittered free lift.
     *
     * <p>Free lift enters the dynamics only through the gas mass sealed in at launch, and the only
     * record of that gas mass in a {@link BalloonState} is the envelope diameter. Jitter the free
     * lift without touching the diameter and the new value would be inert — the particle would keep
     * flying on the old gas mass, and the free-lift "posterior" would be the prior smeared by
     * noise. Since {@code V = m R T / p} at fixed altitude, the diameter scales with the cube root
     * of the gas-mass ratio, and that is the whole correction.
     *
     * <p>After burst the diameter is the canopy's, not the envelope's, so there is nothing to
     * rescale.
     */
    private BalloonState rescaleForFreeLift(BalloonState state, FlightParameters before,
                                            FlightParameters after) throws ValidationException {
        if (state.phase() != Phase.ASCENT || before.freeLiftKg() == after.freeLiftKg()) {
            return state;
        }
        double gasBefore = FlightSimulator.gasMassFor(config, before, launchAir);
        double gasAfter = FlightSimulator.gasMassFor(config, after, launchAir);
        if (gasBefore <= 0.0 || gasAfter <= 0.0) {
            return state;
        }
        double scaled = state.diameterM() * Math.cbrt(gasAfter / gasBefore);
        return new BalloonState(state.timeSeconds(), state.position(), state.verticalRateMs(),
                scaled, state.phase(), state.windExtrapolated());
    }

    /** Summarises the current weighted set into a posterior. */
    private Posterior summarise(Instant epoch) {
        double[][] values = new double[DIMENSIONS][particleCount];
        for (int i = 0; i < particleCount; i++) {
            for (int d = 0; d < DIMENSIONS; d++) {
                values[d][i] = dimensionOf(particles[i].parameters(), d);
            }
        }
        return Posterior.of(epoch,
                bandOf(values[FREE_LIFT]),
                bandOf(values[ASCENT_CD]),
                bandOf(values[BURST_SCALE]),
                bandOf(values[CHUTE_CD]),
                SystematicResampler.effectiveSampleSize(weights),
                resampleCount,
                particleCount);
    }

    private Posterior.Band bandOf(double[] values) {
        int[] order = WeightedQuantile.order(values);
        return new Posterior.Band(
                WeightedQuantile.of(values, weights, order, 0.50),
                WeightedQuantile.of(values, weights, order, 0.05),
                WeightedQuantile.of(values, weights, order, 0.95));
    }

    /**
     * A standard normal deviate from this filter's own generator.
     *
     * <p>By inverse transform through {@link Gaussian#inverseCdf}, so there is one definition of
     * the normal distribution in the project and a given seed produces a given jitter regardless of
     * the JDK's own Gaussian algorithm.
     */
    private double standardNormal() {
        return Gaussian.inverseCdf(uniform());
    }

    /**
     * A uniform draw strictly inside (0, 1) — quantile functions are undefined at the endpoints
     * and {@link SplittableRandom#nextDouble()} can return exactly zero.
     */
    private double uniform() {
        return Math.min(Math.max(random.nextDouble(), Double.MIN_NORMAL), Math.nextDown(1.0));
    }

    private double dimensionOf(FlightParameters p, int dimension) {
        return switch (dimension) {
            case FREE_LIFT -> p.freeLiftKg();
            case ASCENT_CD -> p.ascentCd();
            case BURST_SCALE -> p.burstDiameterM() / config.burstDiameterM();
            case CHUTE_CD -> p.chuteCd();
            default -> throw new IllegalArgumentException("no dimension " + dimension);
        };
    }

    private FlightParameters parametersOf(double[] values) {
        return new FlightParameters(values[FREE_LIFT], values[ASCENT_CD],
                values[BURST_SCALE] * config.burstDiameterM(), values[CHUTE_CD], windScale);
    }

    private static double clamp(double v, double low, double high) {
        return Math.max(low, Math.min(high, v));
    }

    private double flightTimeOf(Instant epoch) {
        return Duration.between(launchEpoch, epoch).toNanos() / 1e9;
    }

    /**
     * The vertical rate the particle set believes, in m/s.
     *
     * <p>Not the telemetry's rate. A flight computer reports position, so an observed rate is a
     * difference of two noisy altitudes and at 1 Hz with a 10 m GPS is uncertain by about 7 m/s —
     * comparable to the balloon's whole ascent rate. The particles' rate is the output of the
     * dynamics integrated against the entire flight so far, so it is smooth and physically
     * consistent, which is what anything continuing the flight from here actually needs.
     *
     * @return the weighted mean vertical rate across the particle set
     */
    public double weightedVerticalRateMs() {
        double total = 0.0;
        for (int i = 0; i < particleCount; i++) {
            total += weights[i] * particles[i].state().verticalRateMs();
        }
        return total;
    }

    /** @return the posterior after the most recent update */
    public Posterior posterior() {
        return posterior;
    }

    /** @return how many times the set has been resampled this flight */
    public int resampleCount() {
        return resampleCount;
    }

    /** @return how many particles were refuted at the most recent update */
    public int impossibleCount() {
        return impossibleCount;
    }

    /** @return the particle count, fixed for the life of the filter */
    public int particleCount() {
        return particleCount;
    }

    /** @return the current particle set; the array is a copy, the particles are immutable */
    public Particle[] particles() {
        return particles.clone();
    }

    /** @return the current normalised weights; a copy */
    public double[] weights() {
        return weights.clone();
    }

    /** @return the launch epoch the filter was started at; used by diagnostics */
    public java.time.Instant launchEpochProbe() {
        return launchEpoch;
    }

    /** @return the launch point the filter was started at */
    public GeoPoint launchPoint() {
        return launchPoint;
    }

    /** Collects and validates the filter's collaborators and knobs. */
    public static final class Builder {

        private BalloonConfig config;
        private FlightSimulator simulator;
        private SimSettings settings;
        private MeasurementModel model = GaussianMeasurementModel.standard();
        private Resampler resampler = new SystematicResampler();
        private DispersionSpec prior;
        private int particleCount = DEFAULT_PARTICLE_COUNT;
        private double resampleThresholdFraction = DEFAULT_RESAMPLE_THRESHOLD;
        private double shrinkage = DEFAULT_SHRINKAGE;
        private double roughening = DEFAULT_ROUGHENING;
        private double windScale = 1.0;
        private long seed;

        /** @param v the balloon configuration @return this builder */
        public Builder config(BalloonConfig v) {
            this.config = v;
            return this;
        }

        /** @param v the simulator particles are propagated with @return this builder */
        public Builder simulator(FlightSimulator v) {
            this.simulator = v;
            return this;
        }

        /** @param v integration settings @return this builder */
        public Builder settings(SimSettings v) {
            this.settings = v;
            return this;
        }

        /** @param v the likelihood @return this builder */
        public Builder measurementModel(MeasurementModel v) {
            this.model = v;
            return this;
        }

        /** @param v the resampling scheme @return this builder */
        public Builder resampler(Resampler v) {
            this.resampler = v;
            return this;
        }

        /** @param v the parameter prior @return this builder */
        public Builder prior(DispersionSpec v) {
            this.prior = v;
            return this;
        }

        /** @param v the particle count @return this builder */
        public Builder particleCount(int v) {
            this.particleCount = v;
            return this;
        }

        /** @param v resample below this fraction of N @return this builder */
        public Builder resampleThresholdFraction(double v) {
            this.resampleThresholdFraction = v;
            return this;
        }

        /** @param v the Liu–West discount factor @return this builder */
        public Builder shrinkage(double v) {
            this.shrinkage = v;
            return this;
        }

        /**
         * @param v the narrowest spread the jitter kernel will hold, as a fraction of the prior
         *          spread; 0 disables the floor
         * @return this builder
         */
        public Builder roughening(double v) {
            this.roughening = v;
            return this;
        }

        /** @param v the wind scale every particle carries; not estimated @return this builder */
        public Builder windScale(double v) {
            this.windScale = v;
            return this;
        }

        /** @param v the run seed; the same seed reproduces the same filter @return this builder */
        public Builder seed(long v) {
            this.seed = v;
            return this;
        }

        /**
         * @return the filter
         * @throws ValidationException naming the first field that is missing or out of range
         */
        public ParticleFilter build() throws ValidationException {
            require(config != null, "config", "a balloon configuration is required");
            require(simulator != null, "simulator", "a flight simulator is required");
            require(settings != null, "settings", "integration settings are required");
            require(prior != null, "prior", "a parameter prior is required");
            require(model != null, "measurement_model", "a measurement model is required");
            require(resampler != null, "resampler", "a resampler is required");
            require(particleCount >= 2, "particle_count",
                    "at least two particles are needed, was " + particleCount);
            require(resampleThresholdFraction > 0.0 && resampleThresholdFraction <= 1.0,
                    "resample_threshold_fraction",
                    "must lie in (0, 1], was " + resampleThresholdFraction);
            require(shrinkage > 0.0 && shrinkage <= 1.0, "shrinkage",
                    "must lie in (0, 1], was " + shrinkage);
            require(roughening >= 0.0, "roughening",
                    "cannot be negative, was " + roughening);
            require(windScale > 0.0, "wind_scale", "must be positive, was " + windScale);
            require(config.burstDiameterM() > 0.0, "burst_diameter_m",
                    "the catalogue burst diameter scales the estimated one and must be positive");
            requireDispersed(prior.burstDiameterM(), "burst_diameter_m");
            requireDispersed(prior.ascentCd(), "ascent_cd");
            requireDispersed(prior.chuteCd(), "chute_cd");
            requireDispersed(prior.freeLiftKg(), "free_lift_kg");
            return new ParticleFilter(this);
        }

        /**
         * A prior with no width in a dimension cannot be learned from: every particle would hold
         * the same value, the jitter kernel's variance would be zero, and the band would report a
         * certainty the data never supplied.
         */
        private static void requireDispersed(Distribution d, String field)
                throws ValidationException {
            if (d.maximum() <= d.minimum()) {
                throw new ValidationException("the prior for " + field
                        + " has no width, so the filter cannot estimate it")
                        .with(field, d.describe());
            }
        }

        private static void require(boolean condition, String field, String message)
                throws ValidationException {
            if (!condition) {
                throw new ValidationException(message).with(field, "invalid");
            }
        }
    }
}
