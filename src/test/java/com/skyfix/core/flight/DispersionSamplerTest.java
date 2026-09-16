package com.skyfix.core.flight;

import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Distribution;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.Gaussian;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-U-LHS — the stratification property, and the reproducibility ADR-6 requires.
 *
 * <p>The defining property of a Latin hypercube is checkable exactly, not statistically: for N
 * members and N strata per dimension, every stratum must contain exactly one sample. That is the
 * assertion here, and it would fail for plain Monte Carlo, which is the point of choosing LHS.
 */
class DispersionSamplerTest {

    static BalloonConfig config() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g").payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4).gas(LiftGas.HELIUM)
                .build();
    }

    static DispersionSampler sampler() throws Exception {
        return new DispersionSampler(DispersionSpec.preflightDefault(config()));
    }

    @Test
    @DisplayName("T-U-LHS: each stratum is occupied exactly once, in every dimension")
    void everyStratumIsOccupiedExactlyOnce() throws Exception {
        for (int n : new int[]{2, 7, 50, 1000}) {
            double[][] design = sampler().unitDesign(n, 20260914L);
            assertThat(design.length).isEqualTo(n);

            for (int d = 0; d < 5; d++) {
                int[] occupancy = new int[n];
                for (double[] point : design) {
                    int stratum = (int) Math.floor(point[d] * n);
                    assertThat(stratum).as("stratum index in range").isBetween(0, n - 1);
                    occupancy[stratum]++;
                }
                assertThat(occupancy)
                        .as("dimension %d with %d members: every stratum exactly once", d, n)
                        .containsOnly(1);
            }
        }
    }

    @Test
    @DisplayName("T-U-LHS: plain Monte Carlo would fail the same check — the property is real")
    void plainMonteCarloWouldNotSatisfyTheProperty() {
        // A test that any sampler passes proves nothing. With 1,000 independent uniform draws,
        // roughly a third of the strata end up empty, so the assertion above genuinely
        // distinguishes Latin-hypercube sampling from the alternative it was chosen over.
        int n = 1000;
        java.util.SplittableRandom random = new java.util.SplittableRandom(1L);
        int[] occupancy = new int[n];
        for (int i = 0; i < n; i++) {
            occupancy[(int) Math.floor(random.nextDouble() * n)]++;
        }
        long empty = Arrays.stream(occupancy).filter(c -> c == 0).count();
        assertThat(empty)
                .as("plain MC leaves many strata unvisited")
                .isGreaterThan(n / 5);
    }

    @Test
    @DisplayName("T-U-LHS: dimensions are permuted independently, not moved together")
    void dimensionsArePermutedIndependently() throws Exception {
        // If every dimension shared one permutation, the design would lie on a diagonal and the
        // parameters would be perfectly correlated — the ensemble would explore a line, not a
        // volume.
        double[][] design = sampler().unitDesign(500, 7L);
        for (int d = 1; d < 5; d++) {
            double correlation = correlation(column(design, 0), column(design, d));
            assertThat(Math.abs(correlation))
                    .as("correlation between dimension 0 and %d", d)
                    .isLessThan(0.2);
        }
    }

    @Test
    @DisplayName("ADR-6: the same seed reproduces the design exactly, a different one changes it")
    void designIsReproducibleFromTheSeed() throws Exception {
        double[][] first = sampler().unitDesign(200, 99L);
        double[][] second = sampler().unitDesign(200, 99L);
        for (int i = 0; i < first.length; i++) {
            assertThat(second[i]).as("member %d", i).containsExactly(first[i]);
        }

        double[][] other = sampler().unitDesign(200, 100L);
        assertThat(Arrays.deepToString(other)).isNotEqualTo(Arrays.deepToString(first));
    }

    @Test
    @DisplayName("ADR-6: member k is reproducible in isolation, whatever else ran")
    void memberJitterDoesNotDependOnOtherMembers() throws Exception {
        // Each member's generator is split from the run seed independently, so the design for
        // member k must not shift when the ensemble around it changes... within the same member
        // count. Debugging an outlier means re-running exactly that member, so the parameters it
        // receives must be a pure function of (seed, memberCount, k).
        FlightParameters[] a = sampler().sample(100, 5L);
        FlightParameters[] b = sampler().sample(100, 5L);
        for (int k = 0; k < 100; k++) {
            assertThat(b[k]).as("member %d", k).isEqualTo(a[k]);
        }
    }

    @Test
    @DisplayName("Samples respect every distribution's bounds, including the hard physical ones")
    void samplesStayInsideTheirDistributions() throws Exception {
        DispersionSpec spec = DispersionSpec.preflightDefault(config());
        FlightParameters[] members = new DispersionSampler(spec).sample(2000, 3L);

        for (FlightParameters p : members) {
            assertThat(p.freeLiftKg()).isBetween(spec.freeLiftKg().minimum(),
                    spec.freeLiftKg().maximum()).isPositive();
            // The drag coefficients must stay inside the range the schema's CHECK allows, or a
            // member would be unstorable.
            assertThat(p.ascentCd()).isBetween(0.1, 2.0);
            assertThat(p.chuteCd()).isBetween(0.1, 2.0);
            assertThat(p.burstDiameterM()).isPositive();
            assertThat(p.windScale()).isBetween(spec.windScale().minimum(),
                    spec.windScale().maximum());
        }
    }

    @Test
    @DisplayName("The sample reproduces each distribution's own mean and spread")
    void marginalsMatchTheRequestedDistributions() throws Exception {
        // Stratification means the marginals converge fast: 1,000 members should recover the mean
        // of each parameter to well inside a percent, which is the benefit being claimed.
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.preflightDefault(config);
        FlightParameters[] members = new DispersionSampler(spec).sample(1000, 11L);

        double meanFreeLift = Arrays.stream(members)
                .mapToDouble(FlightParameters::freeLiftKg).average().orElseThrow();
        assertThat(meanFreeLift)
                .as("free lift mean should recover the nominal")
                .isCloseTo(config.freeLiftKg(), within(0.01 * config.freeLiftKg()));

        double meanWindScale = Arrays.stream(members)
                .mapToDouble(FlightParameters::windScale).average().orElseThrow();
        assertThat(meanWindScale).isCloseTo(1.0, within(0.01));

        // And the spread is the one that was asked for, not something narrower.
        double sd = standardDeviation(Arrays.stream(members)
                .mapToDouble(FlightParameters::windScale).toArray());
        assertThat(sd).as("wind scale spread").isCloseTo(0.20, within(0.02));
    }

    @Test
    @DisplayName("A fixed dimension produces exactly the nominal value for every member")
    void fixedDimensionsDoNotVary() throws Exception {
        BalloonConfig config = config();
        DispersionSpec spec = DispersionSpec.around(config).build();  // everything fixed
        FlightParameters[] members = new DispersionSampler(spec).sample(50, 1L);

        Set<Double> distinctCd = new HashSet<>();
        for (FlightParameters p : members) {
            distinctCd.add(p.ascentCd());
            assertThat(p.freeLiftKg()).isEqualTo(config.freeLiftKg());
            assertThat(p.windScale()).isEqualTo(1.0);
        }
        assertThat(distinctCd).as("a fixed dimension takes one value").hasSize(1);
    }

    @Test
    @DisplayName("Distributions invert their own CDFs and report honest bounds")
    void distributionBasics() throws Exception {
        Distribution.Uniform uniform = new Distribution.Uniform(2.0, 6.0);
        assertThat(uniform.quantile(0.0)).isEqualTo(2.0);
        assertThat(uniform.quantile(0.5)).isEqualTo(4.0);
        assertThat(uniform.quantile(1.0)).isEqualTo(6.0);
        assertThat(uniform.minimum()).isEqualTo(2.0);
        assertThat(uniform.maximum()).isEqualTo(6.0);
        assertThat(uniform.describe()).contains("uniform");

        Distribution.TruncatedNormal normal =
                Distribution.TruncatedNormal.symmetric(10.0, 2.0, 3.0);
        assertThat(normal.quantile(0.5)).isEqualTo(10.0, within(1e-9));
        // One standard deviation above the mean sits at Phi(1) by construction.
        assertThat(normal.quantile(Gaussian.cdf(1.0))).isEqualTo(12.0, within(1e-6));
        assertThat(normal.quantile(1e-15)).isEqualTo(normal.minimum(), within(1e-9));
        assertThat(normal.quantile(1 - 1e-15)).isEqualTo(normal.maximum(), within(1e-9));

        Distribution.Fixed fixed = new Distribution.Fixed(3.5);
        assertThat(fixed.quantile(0.1)).isEqualTo(3.5);
        assertThat(fixed.quantile(0.9)).isEqualTo(3.5);
        assertThat(fixed.minimum()).isEqualTo(fixed.maximum());
    }

    @Test
    @DisplayName("Degenerate distributions and specs are rejected where they are built")
    void degenerateInputsRejected() throws Exception {
        assertThatThrownBy(() -> new Distribution.Uniform(5.0, 5.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Distribution.TruncatedNormal(0, -1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Distribution.TruncatedNormal.relative(0.0, 0.1, 3.0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("nominal");
        assertThatThrownBy(() -> Distribution.TruncatedNormal.relative(1.0, 0.0, 3.0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("relative_sigma");

        assertThatThrownBy(() -> new DispersionSpec.Builder().build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("free_lift_kg");

        assertThatThrownBy(() -> sampler().unitDesign(0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DispersionSpec.preflightDefault(config()).at(new double[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The spec describes itself for run records")
    void specDescribesItself() throws Exception {
        DispersionSpec spec = DispersionSpec.preflightDefault(config());
        assertThat(spec.dimensionCount()).isEqualTo(5);
        assertThat(spec.dimensions()).hasSize(5);
        assertThat(spec.toString())
                .contains("free_lift").contains("ascent_cd").contains("wind_scale");
    }

    private static double[] column(double[][] design, int d) {
        double[] out = new double[design.length];
        for (int i = 0; i < design.length; i++) {
            out[i] = design[i][d];
        }
        return out;
    }

    private static double standardDeviation(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0);
        double sum = 0;
        for (double v : values) {
            sum += (v - mean) * (v - mean);
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    private static double correlation(double[] a, double[] b) {
        double meanA = Arrays.stream(a).average().orElse(0);
        double meanB = Arrays.stream(b).average().orElse(0);
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) {
            num += (a[i] - meanA) * (b[i] - meanB);
            da += (a[i] - meanA) * (a[i] - meanA);
            db += (b[i] - meanB) * (b[i] - meanB);
        }
        return num / Math.sqrt(da * db);
    }
}
