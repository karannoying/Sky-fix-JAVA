package com.skyfix.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The normal distribution function and its inverse, against oracles that do not reuse the code
 * under test.
 *
 * <p>The primary check is Simpson quadrature of the Gaussian density, which reaches the CDF by a
 * completely different route from the series {@link Gaussian#cdf} sums. The identities — symmetry,
 * monotonicity, the exact value at zero — pin the rest without needing a table of quantiles
 * transcribed from anywhere.
 */
class GaussianTest {

    @Test
    @DisplayName("cdf matches Simpson quadrature of the density, an independent computation")
    void cdfMatchesNumericalQuadrature() {
        // Deliberately reaches +/- 8 sigma: an earlier version of this test stopped at 4, which
        // is inside the range the Maclaurin series handles, and so passed over a real defect.
        for (double x = -8.0; x <= 8.0; x += 0.25) {
            double quadrature = 0.5 + integrateDensity(0.0, x);
            assertThat(Gaussian.cdf(x))
                    .as("Phi(%.2f)", x)
                    .isEqualTo(quadrature, within(1e-10));
        }
    }

    /** Simpson's rule on the standard normal density — written here, used nowhere else. */
    private static double integrateDensity(double from, double to) {
        int panels = 20_000;
        double h = (to - from) / panels;
        double sum = Gaussian.pdf(from) + Gaussian.pdf(to);
        for (int i = 1; i < panels; i++) {
            sum += (i % 2 == 0 ? 2.0 : 4.0) * Gaussian.pdf(from + i * h);
        }
        return sum * h / 3.0;
    }

    @Test
    @DisplayName("The identities that pin the CDF: exact at zero, symmetric, monotone, bounded")
    void cdfIdentities() {
        assertThat(Gaussian.cdf(0.0)).isEqualTo(0.5, within(1e-15));
        assertThat(Gaussian.erf(0.0)).isEqualTo(0.0, within(1e-15));

        for (double x = 0.1; x <= 6.0; x += 0.1) {
            assertThat(Gaussian.cdf(x) + Gaussian.cdf(-x))
                    .as("Phi(x) + Phi(-x) = 1 at x=%.1f", x)
                    .isEqualTo(1.0, within(1e-12));
            assertThat(Gaussian.erf(x)).isEqualTo(-Gaussian.erf(-x), within(1e-12));
        }

        double previous = -1.0;
        for (double x = -6.0; x <= 6.0; x += 0.05) {
            double value = Gaussian.cdf(x);
            assertThat(value).as("Phi must increase at x=%.2f", x).isGreaterThan(previous);
            assertThat(value).isBetween(0.0, 1.0);
            previous = value;
        }
        assertThat(Gaussian.cdf(10.0)).isEqualTo(1.0, within(1e-15));
        assertThat(Gaussian.cdf(-10.0)).isEqualTo(0.0, within(1e-15));
    }

    @Test
    @DisplayName("inverseCdf inverts cdf to machine precision across the usable range")
    void inverseRoundTrips() {
        // Indexed rather than accumulated: p += step drifts, and the last step lands on a value
        // indistinguishable from 1 where the CDF has saturated, which would test rounding rather
        // than the inverse.
        int steps = 2_000;
        for (int i = 1; i < steps; i++) {
            double p = (double) i / steps;
            double x = Gaussian.inverseCdf(p);
            assertThat(Gaussian.cdf(x)).as("round trip at p=%.5f", p).isEqualTo(p, within(1e-12));
        }
        // The median is exactly zero, and the quantile function is antisymmetric about it.
        assertThat(Gaussian.inverseCdf(0.5)).isEqualTo(0.0, within(1e-12));
        for (double p : new double[]{0.01, 0.1, 0.25, 0.4}) {
            assertThat(Gaussian.inverseCdf(p))
                    .isEqualTo(-Gaussian.inverseCdf(1 - p), within(1e-10));
        }
    }

    @Test
    @DisplayName("Round-tripping in x is well conditioned everywhere the sampler uses it")
    void inverseRoundTripsInXSpace() {
        // The tail is better tested from the x side: p is crowded against 1 out there, so the
        // p-space round trip measures how many digits are left in p rather than the inverse.
        for (double x = -6.0; x <= 6.0; x += 0.01) {
            assertThat(Gaussian.inverseCdf(Gaussian.cdf(x)))
                    .as("x -> p -> x at x=%.2f", x)
                    .isEqualTo(x, within(1e-7));
        }
    }

    @Test
    @DisplayName("The far tail saturates in double precision, and does so predictably")
    void farTailSaturatesRatherThanMisleading() {
        // Beyond roughly 8 sigma the CDF is 1 to the last bit of a double, so the inverse cannot
        // resolve further and returns the clamp. Stating that here means nobody later mistakes the
        // clamp for a computed quantile. Latin-hypercube strata never reach this far: even a
        // million members put the extreme stratum near 4.9 sigma.
        assertThat(Gaussian.cdf(Gaussian.MAX_SIGMA)).isEqualTo(1.0, within(1e-15));
        // Math.nextDown(1.0) rather than 1 - 1e-17: the latter IS 1.0 in double precision, since
        // 1e-17 is below the spacing of doubles at one, and the guard rightly rejects it.
        assertThat(Gaussian.inverseCdf(Math.nextDown(1.0)))
                .isEqualTo(Gaussian.MAX_SIGMA, within(1e-9));
        assertThat(Gaussian.inverseCdf(Double.MIN_NORMAL))
                .isEqualTo(-Gaussian.MAX_SIGMA, within(1e-9));

        // The extreme stratum of a very large ensemble is still comfortably inside the range.
        double extremeStratumOfAMillion = Gaussian.inverseCdf(0.5 / 1_000_000);
        assertThat(Math.abs(extremeStratumOfAMillion)).isLessThan(5.0);
    }

    @Test
    @DisplayName("inverseCdf rejects probabilities outside the open interval")
    void inverseRejectsDegenerateProbabilities() {
        for (double p : new double[]{0.0, 1.0, -0.1, 1.1, Double.NaN}) {
            assertThatThrownBy(() -> Gaussian.inverseCdf(p))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("The two erf methods agree where their ranges overlap")
    void seriesAndContinuedFractionAgreeAtTheCrossover() {
        // The series and the continued fraction are independent algorithms; both are valid around
        // |x| = 2, so their agreement there is what justifies switching between them. A
        // discontinuity at the crossover would show up immediately as a kink in the CDF.
        for (double x = 1.90; x <= 2.10; x += 0.005) {
            assertThat(Gaussian.erf(x) + Gaussian.erfc(x))
                    .as("erf + erfc = 1 at x=%.3f", x)
                    .isEqualTo(1.0, within(1e-14));
        }
        double justBelow = Gaussian.cdf(1.9999999);
        double justAbove = Gaussian.cdf(2.0000001);
        assertThat(justAbove - justBelow)
                .as("no jump in the CDF at the crossover")
                .isLessThan(1e-7);
    }

    @Test
    @DisplayName("The tail keeps its relative precision where 1 - cdf would be all cancellation")
    void upperTailIsAccurateDeepIntoTheTail() {
        // Checked against quadrature of the density from x outwards -- again, a different
        // computation. This is the range where the Maclaurin series alone was wrong by 7e-5.
        for (double x : new double[]{3.0, 4.0, 5.0, 6.0, 7.0}) {
            double quadrature = integrateDensity(x, x + 40.0);
            assertThat(Gaussian.upperTail(x))
                    .as("upper tail at %.1f sigma", x)
                    .isCloseTo(quadrature, within(1e-6 * quadrature));
        }
        // And it stays strictly positive rather than collapsing to zero.
        assertThat(Gaussian.upperTail(7.0)).isPositive().isLessThan(1e-11);
        assertThat(Gaussian.upperTail(0.0)).isEqualTo(0.5, within(1e-15));
    }

    @Test
    @DisplayName("The density integrates to one and has unit variance")
    void densityIsNormalised() {
        // Both moments by quadrature, so this checks pdf independently of cdf.
        assertThat(2 * integrateDensity(0.0, 9.0)).as("total mass").isEqualTo(1.0, within(1e-9));

        int panels = 40_000;
        double from = -9.0, to = 9.0, h = (to - from) / panels;
        double sum = 0.0;
        for (int i = 0; i <= panels; i++) {
            double x = from + i * h;
            double w = (i == 0 || i == panels) ? 1.0 : (i % 2 == 0 ? 2.0 : 4.0);
            sum += w * x * x * Gaussian.pdf(x);
        }
        assertThat(sum * h / 3.0).as("variance").isEqualTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("The ellipse scale factor follows the closed form, and is derived not pasted")
    void ellipseScaleFactorIsClosedForm() {
        // For a bivariate normal the squared Mahalanobis distance is chi-square with 2 dof, whose
        // CDF is 1 - exp(-s^2/2). So P(inside s sigma) = 1 - exp(-s^2/2) must invert exactly.
        for (double p : new double[]{0.5, 0.9, 0.95, 0.99}) {
            double s = Gaussian.ellipseScaleFactor(p);
            assertThat(1.0 - Math.exp(-0.5 * s * s))
                    .as("containment implied by the scale factor at p=%.2f", p)
                    .isEqualTo(p, within(1e-12));
        }
        // Larger confidence, larger ellipse; and the two levels the blueprint names are ordered.
        assertThat(Gaussian.ellipseScaleFactor(0.95))
                .isGreaterThan(Gaussian.ellipseScaleFactor(0.5));

        assertThatThrownBy(() -> Gaussian.ellipseScaleFactor(1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gaussian.ellipseScaleFactor(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Sampling through inverseCdf reproduces the distribution's own moments")
    void samplingThroughInverseReproducesMoments() {
        // Stratified uniforms through the inverse CDF must have mean 0 and variance 1. This is an
        // end-to-end check of the path Latin-hypercube sampling actually uses.
        int n = 20_000;
        double sum = 0.0, sumSquares = 0.0;
        for (int i = 0; i < n; i++) {
            double x = Gaussian.inverseCdf((i + 0.5) / n);
            sum += x;
            sumSquares += x * x;
        }
        assertThat(sum / n).as("sample mean").isEqualTo(0.0, within(1e-9));
        assertThat(sumSquares / n).as("sample variance").isEqualTo(1.0, within(0.002));
    }
}
