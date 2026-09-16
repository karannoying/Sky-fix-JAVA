package com.skyfix.domain;

/**
 * The normal distribution function and its inverse, hand-written (CLAUDE.md rule 1).
 *
 * <p>Needed in two places: Latin-hypercube sampling maps a stratified uniform draw through the
 * inverse CDF (FR-2.4), and the landing ellipse scales its axes by a quantile of the chi-square
 * distribution (FR-2.4, FR-3.3).
 *
 * <p>Both directions are computed without a table of fitted coefficients. {@link #cdf} sums the
 * Maclaurin series for the error function, which converges quickly over the range that matters,
 * and {@link #inverseCdf} inverts it by bisection — monotone, so bisection reaches machine
 * precision in about sixty iterations and there is nothing to mistype. {@code GaussianTest}
 * checks the CDF against Simpson quadrature of the density, which is a genuinely different
 * computation rather than a restatement.
 */
public final class Gaussian {

    /** Largest magnitude the inverse CDF will return, in standard deviations. */
    public static final double MAX_SIGMA = 8.0;

    private static final double INV_SQRT_2 = 1.0 / Math.sqrt(2.0);
    private static final double TWO_OVER_SQRT_PI = 2.0 / Math.sqrt(Math.PI);

    private Gaussian() {
    }

    /**
     * Probability density of the standard normal distribution.
     *
     * @param x the point
     * @return the density at {@code x}
     */
    public static double pdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    /**
     * Cumulative distribution function of the standard normal distribution.
     *
     * @param x the point
     * @return the probability that a standard normal variate is at most {@code x}, in [0, 1]
     */
    public static double cdf(double x) {
        // 0.5 * erfc(-x/sqrt2) rather than 0.5 * (1 + erf(...)): in the lower tail the latter is
        // 1 minus something very close to 1, which is pure cancellation.
        return 0.5 * erfc(-x * INV_SQRT_2);
    }

    /**
     * The upper tail probability, {@code 1 - cdf(x)}.
     *
     * <p>Kept separate because subtracting {@link #cdf} from one loses every digit past about
     * three sigma, and the tail is exactly where a footprint's outliers live.
     *
     * @param x the point
     * @return the probability that a standard normal variate exceeds {@code x}
     */
    public static double upperTail(double x) {
        return 0.5 * erfc(x * INV_SQRT_2);
    }

    /**
     * Inverse CDF (quantile function) of the standard normal distribution.
     *
     * <p>Found by bisection on the monotone CDF, so no fitted rational approximation and no
     * transcribed coefficients are involved.
     *
     * @param p a probability, strictly between 0 and 1
     * @return the value {@code x} with {@code cdf(x) == p}, clamped to +/- {@link #MAX_SIGMA}
     * @throws IllegalArgumentException if {@code p} is not strictly inside (0, 1); a probability
     *                                  outside the open interval is a programmer error, since
     *                                  user-supplied bounds are screened by the caller
     */
    public static double inverseCdf(double p) {
        if (!(p > 0.0 && p < 1.0)) {
            throw new IllegalArgumentException("probability must lie strictly in (0, 1), was " + p);
        }
        double low = -MAX_SIGMA;
        double high = MAX_SIGMA;
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (low + high);
            if (cdf(mid) < p) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    /**
     * Crossover between the two methods below. The series is accurate to roughly
     * {@code exp(x^2) * eps}, so it runs out at about |x| = 3; the continued fraction converges
     * quickly from about |x| = 2. Two is inside both, so either could be used there, which is what
     * {@code GaussianTest} exploits to check they agree.
     */
    private static final double SERIES_LIMIT = 2.0;

    /**
     * The error function.
     *
     * <p>Two methods, because no single one is accurate across the range:
     *
     * <ul>
     *   <li><b>|x| &lt; 2</b> — the Maclaurin series
     *       {@code erf(x) = (2/sqrt(pi)) * sum (-1)^n x^(2n+1) / (n!(2n+1))}. It alternates, and
     *       its largest term grows like {@code exp(x^2)} before cancelling back down to a result
     *       of order one, so past |x| = 3 the cancellation has eaten every significant digit.
     *       Using it out there is silently, badly wrong: it puts {@code cdf(8)} off by 7e-5.</li>
     *   <li><b>|x| &gt;= 2</b> — the complementary function from its continued fraction,
     *       {@code erfc(x) = exp(-x^2) / (sqrt(pi) * K)} with
     *       {@code K = x + (1/2)/(x + 1/(x + (3/2)/(x + ...)))}, evaluated by the modified Lentz
     *       algorithm. The partial numerators are just {@code n/2}, so there is nothing
     *       transcribed here either.</li>
     * </ul>
     *
     * @param x the argument
     * @return {@code erf(x)}, in [-1, 1]
     */
    public static double erf(double x) {
        if (x < 0) {
            return -erf(-x);
        }
        if (x < SERIES_LIMIT) {
            return erfSeries(x);
        }
        return 1.0 - erfc(x);
    }

    /**
     * The complementary error function, {@code 1 - erf(x)}.
     *
     * <p>Computed directly rather than by subtraction, so it keeps its relative precision deep
     * into the tail where {@code 1 - erf(x)} would be entirely cancellation.
     *
     * @param x the argument
     * @return {@code erfc(x)}, in [0, 2]
     */
    public static double erfc(double x) {
        if (x < 0) {
            return 2.0 - erfc(-x);
        }
        if (x < SERIES_LIMIT) {
            return 1.0 - erfSeries(x);
        }
        if (x > 27.0) {
            return 0.0; // exp(-27^2) underflows a double
        }
        // Modified Lentz evaluation of K = x + (1/2)/(x + 1/(x + (3/2)/(x + ...))).
        final double tiny = 1e-300;
        double f = x;
        double c = f;
        double d = 0.0;
        for (int j = 1; j < 300; j++) {
            double a = j / 2.0;
            d = x + a * d;
            if (d == 0.0) {
                d = tiny;
            }
            d = 1.0 / d;
            c = x + a / c;
            if (c == 0.0) {
                c = tiny;
            }
            double delta = c * d;
            f *= delta;
            if (Math.abs(delta - 1.0) < 1e-16) {
                break;
            }
        }
        return Math.exp(-x * x) / (Math.sqrt(Math.PI) * f);
    }

    private static double erfSeries(double x) {
        double term = x;          // n = 0 term, x^1 / (0! * 1)
        double sum = x;
        double xSquared = x * x;
        for (int n = 1; n < 200; n++) {
            // term_n / term_{n-1} = -x^2 / n
            term *= -xSquared / n;
            double contribution = term / (2 * n + 1);
            sum += contribution;
            if (Math.abs(contribution) < 1e-18 * Math.abs(sum)) {
                break;
            }
        }
        return TWO_OVER_SQRT_PI * sum;
    }

    /**
     * The scale factor that turns one standard deviation into the radius of a confidence ellipse.
     *
     * <p>For a bivariate normal, the squared Mahalanobis distance follows a chi-square
     * distribution with two degrees of freedom, whose CDF is {@code 1 - exp(-s^2/2)}. Setting
     * that equal to the confidence level and solving gives {@code s = sqrt(-2 ln(1 - p))} — a
     * closed form, so the 50% and 95% factors are derived here rather than pasted in.
     *
     * @param confidence the confidence level, strictly between 0 and 1
     * @return the number of standard deviations the ellipse axes are scaled by
     * @throws IllegalArgumentException if {@code confidence} is not strictly inside (0, 1)
     */
    public static double ellipseScaleFactor(double confidence) {
        if (!(confidence > 0.0 && confidence < 1.0)) {
            throw new IllegalArgumentException(
                    "confidence must lie strictly in (0, 1), was " + confidence);
        }
        return Math.sqrt(-2.0 * Math.log(1.0 - confidence));
    }
}
