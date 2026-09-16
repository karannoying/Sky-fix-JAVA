package com.skyfix.core.flight;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.SimSettings;
import com.skyfix.io.ConfigLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-14: does Latin hypercube sampling actually buy a stabler footprint than plain Monte Carlo,
 * and by how much?
 *
 * <p>BLUEPRINT §10 claimed LHS "reaches a stable 95% ellipse in ~400 members where plain MC needs
 * ~1,500" and carried a {@code verify:} marker because nobody had measured it. This is the
 * measurement, and it retires the claim: the advantage is real but it is about <strong>2x</strong>
 * in members, not 3.75x.
 *
 * <p><strong>The statistic matters more than the result.</strong> A first attempt ran one seed per
 * point and compared each ensemble's fitted semi-major axis with its own 3,200-member value. That
 * is not a convergence measurement — it is one draw from a distribution whose width is the very
 * thing in question, so it reported LHS as +6.64% at 100 members against plain MC's +1.85% and
 * made LHS look worse. What "stable" means is that another ensemble of the same size gives you the
 * same axis, so the statistic is the <em>spread across independent seeds</em>, which needs several
 * seeds per point. Measured that way the ordering is consistent and the other way round.
 *
 * <p>Deterministic: the seeds are fixed and the simulator is reproducible to 1e-9 (T-R1), so the
 * numbers below are the same on every run and the assertions are exact rather than statistical.
 *
 * <p>Tagged {@code perf}: about 43,000 flights, a few minutes on four cores.
 */
@Tag("perf")
class SamplerConvergenceTest {

    /** Member counts swept. ADR-14 also reports 3,200, which costs as much as all of these. */
    private static final int[] COUNTS = {100, 200, 400, 800, 1600};

    /** Independent seeds per point. The spread across these is the statistic. */
    private static final long[] SEEDS = {20260914L, 5150L, 991L, 777003L, 42424242L, 31337L, 8675309L};

    /** The blueprint's claim, as a member ratio: 1,500 plain-MC members against 400 LHS ones. */
    private static final double CLAIMED_FACTOR = 1500.0 / 400.0;

    private static final ThreadLocal<FlightSimulator> SIM = ThreadLocal.withInitial(
            () -> new FlightSimulator(new Ussa1976Atmosphere(), new ConstantWindField(12.0, -4.0)));

    @Test
    @DisplayName("ADR-14: LHS is stabler than plain Monte Carlo, by about 2x in members")
    void latinHypercubeIsStablerThanPlainMonteCarlo() throws Exception {
        ConfigLoader loader = new ConfigLoader();
        BalloonConfig balloon = loader.loadBalloon(Path.of("data/missions/balloon.json"));
        ConfigLoader.MissionSpec mission = loader.loadMission(Path.of("data/missions/mission.json"));
        SimSettings settings = loader.loadSimSettings(
                loader.read(Path.of("data/missions/mission.json")), mission.groundElevationM());
        // Nothing here reads a trajectory, and retaining one allocates a few thousand states per
        // flight across forty thousand flights.
        SimSettings quiet = settings.toBuilder().stateSampleStride(Integer.MAX_VALUE).build();
        DispersionSpec spec = DispersionSpec.preflightDefault(balloon);

        double[] lhsCv = new double[COUNTS.length];
        double[] mcCv = new double[COUNTS.length];

        System.out.printf(Locale.ROOT, "%n%-9s | %-24s | %-24s | %s%n",
                "members", "LHS mean / sd / cv", "plain MC mean / sd / cv", "cv ratio");
        System.out.println("-".repeat(86));

        for (int c = 0; c < COUNTS.length; c++) {
            double[] lhs = axesOverSeeds(balloon, spec, quiet, mission, COUNTS[c], false);
            double[] mc = axesOverSeeds(balloon, spec, quiet, mission, COUNTS[c], true);
            lhsCv[c] = sd(lhs) / mean(lhs);
            mcCv[c] = sd(mc) / mean(mc);
            System.out.printf(Locale.ROOT,
                    "%9d | %8.0f %7.0f %5.2f%% | %8.0f %7.0f %5.2f%% | %6.2f%n",
                    COUNTS[c], mean(lhs), sd(lhs), lhsCv[c] * 100,
                    mean(mc), sd(mc), mcCv[c] * 100, mcCv[c] / lhsCv[c]);
        }

        double lhsRate = rate(lhsCv);
        double mcRate = rate(mcCv);
        double factor = equivalentMemberFactor(lhsCv, mcCv);
        System.out.printf(Locale.ROOT,
                "%nscatter falls as N^%.2f (LHS) and N^%.2f (plain MC)%n", lhsRate, mcRate);
        System.out.printf(Locale.ROOT,
                "plain MC needs about %.1fx the members for the same scatter "
                        + "(BLUEPRINT section 10 claimed %.2fx)%n", factor, CLAIMED_FACTOR);

        for (int c = 0; c < COUNTS.length; c++) {
            assertThat(lhsCv[c])
                    .as("at %d members LHS scatter must be below plain MC's", COUNTS[c])
                    .isLessThan(mcCv[c]);
        }

        // Both should fall at roughly the Monte Carlo rate. A sampler whose scatter did not fall
        // with N would mean the ensemble is not converging on anything and the whole footprint is
        // a fiction; this is the assertion that would catch that.
        assertThat(lhsRate).as("LHS scatter must fall at roughly N^-1/2").isBetween(-0.75, -0.30);
        assertThat(mcRate).as("MC scatter must fall at roughly N^-1/2").isBetween(-0.75, -0.30);

        // The claim retirement, made mechanical: the advantage is real, and it is not 3.75x.
        assertThat(factor)
                .as("LHS's member-count advantage over plain Monte Carlo")
                .isGreaterThan(1.5)
                .isLessThan(CLAIMED_FACTOR);
    }

    /** The fitted 95% semi-major axis at one member count, once per seed. */
    private static double[] axesOverSeeds(BalloonConfig balloon, DispersionSpec spec,
                                          SimSettings quiet, ConfigLoader.MissionSpec mission,
                                          int members, boolean plainMonteCarlo) throws Exception {
        double[] axes = new double[SEEDS.length];
        for (int s = 0; s < SEEDS.length; s++) {
            axes[s] = axis(balloon, spec, quiet, mission, members, SEEDS[s], plainMonteCarlo);
        }
        return axes;
    }

    /** One ensemble, drawn by LHS or by plain Monte Carlo, flown and fitted. */
    private static double axis(BalloonConfig balloon, DispersionSpec spec, SimSettings quiet,
                               ConfigLoader.MissionSpec mission, int members, long seed,
                               boolean plainMonteCarlo) throws Exception {
        FlightParameters[] design;
        if (plainMonteCarlo) {
            // The comparison arm: independent uniforms per dimension, no stratification. Drawn
            // here rather than in src/main because plain MC is not a mode SKYFIX offers.
            SplittableRandom random = new SplittableRandom(seed);
            design = new FlightParameters[members];
            for (int i = 0; i < members; i++) {
                double[] u = new double[spec.dimensionCount()];
                for (int d = 0; d < u.length; d++) {
                    u[d] = Math.min(Math.max(random.nextDouble(), 1e-9), 1.0 - 1e-9);
                }
                design[i] = spec.at(u);
            }
        } else {
            design = new DispersionSampler(spec).sample(members, seed);
        }

        List<GeoPoint> landings = Arrays.stream(design).parallel().map(p -> {
            try {
                return SIM.get().run(balloon, p, quiet, mission.launch()).landingPoint().orElse(null);
            } catch (Exception refused) {
                return null; // a draw the stability guard refuses is a draw neither sampler lands
            }
        }).filter(Objects::nonNull).collect(Collectors.toCollection(ArrayList::new));

        return EllipseFitter.fit(landings, 0.95, mission.groundElevationM()).semiMajorM();
    }

    /**
     * How many times more members plain Monte Carlo needs for the same scatter.
     *
     * <p>Both scatters fall as a power of N, so fitting {@code log cv = a + b log N} to each and
     * asking where the two curves meet a common scatter gives the ratio directly. Taken at the
     * middle of the swept range, since the two exponents differ slightly and the ratio therefore
     * drifts with N rather than being one number.
     */
    private static double equivalentMemberFactor(double[] lhsCv, double[] mcCv) {
        double[] lhsFit = logLogFit(lhsCv);
        double[] mcFit = logLogFit(mcCv);
        int middle = COUNTS.length / 2;
        double targetCv = Math.exp(lhsFit[0] + lhsFit[1] * Math.log(COUNTS[middle]));
        double mcMembers = Math.exp((Math.log(targetCv) - mcFit[0]) / mcFit[1]);
        return mcMembers / COUNTS[middle];
    }

    /** The exponent of the power law the scatter follows. */
    private static double rate(double[] cv) {
        return logLogFit(cv)[1];
    }

    /** Least-squares fit of {@code log cv = a + b log N}, returned as {a, b}. */
    private static double[] logLogFit(double[] cv) {
        double[] x = new double[COUNTS.length];
        double[] y = new double[COUNTS.length];
        for (int i = 0; i < COUNTS.length; i++) {
            x[i] = Math.log(COUNTS[i]);
            y[i] = Math.log(cv[i]);
        }
        double mx = mean(x);
        double my = mean(y);
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < x.length; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
        }
        double b = sxy / sxx;
        return new double[] {my - b * mx, b};
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    /** Sample standard deviation — the seeds are a sample of the sampler's behaviour, not all of it. */
    private static double sd(double[] v) {
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }
}
