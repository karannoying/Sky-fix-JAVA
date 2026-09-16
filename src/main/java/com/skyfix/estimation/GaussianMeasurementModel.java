package com.skyfix.estimation;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;

/**
 * Independent Gaussian errors on altitude, vertical rate and horizontal position (FR-3.1).
 *
 * <p>The three channels are treated as independent, which is an approximation — a GPS receiver's
 * horizontal and vertical errors are correlated through the same satellite geometry — but a
 * defensible one at this level of modelling, and one the report should state rather than leave
 * implicit.
 *
 * <p><strong>A barometric altitude is weighted differently from a GPS one.</strong> When the fix
 * drops, {@code CsvTelemetryReader} substitutes a pressure altitude and flags it. That number is
 * smooth but biased: it is what the <em>standard</em> atmosphere puts at that pressure, and the
 * day's profile is not the standard one. Trusting it as heavily as a GPS fix would let the filter
 * chase an offset that is a property of the atmosphere rather than of the balloon, so its sigma is
 * inflated by {@link #PRESSURE_ALTITUDE_SIGMA_FACTOR}.
 *
 * <p><strong>The horizontal channel is dominated by model error, not measurement error.</strong>
 * A GPS fix is good to metres, but the <em>predicted</em> horizontal position rests on a single
 * sounding, and ADR-7 is explicit that the sounding is wrong by some unknown scale factor. After
 * a few hundred kilometres of drift that error is kilometres, not metres. Scoring the residual
 * against an 8 m sigma would therefore hand the horizontal channel a likelihood hundreds of
 * thousands of times sharper than the vertical ones, and the filter would obediently distort the
 * balloon's drag and lift to absorb an error that belongs to the wind. {@link #withWindDrift}
 * inflates the sigma in proportion to how far the balloon has drifted, using the same wind-scale
 * uncertainty the pre-flight ensemble disperses over, which puts the two channels back on
 * comparable footing.
 */
public final class GaussianMeasurementModel implements MeasurementModel {

    /**
     * How much wider the altitude sigma is for a barometric altitude than for a GPS one.
     *
     * <p><strong>Measured on DS-6: 1.71.</strong> Across 158,753 samples carrying both a GPS
     * altitude and a pressure reading, the pressure altitude differs from the GPS altitude with a
     * mean of −0.01 m and a standard deviation of 17.12 m, against the 10 m GPS sigma.
     *
     * <p>That measurement settles only half the question, and the half it settles is the smaller
     * one. DS-6's pressures are generated from the <em>same</em> USSA-1976 model this reader
     * inverts, so the round trip contains the barometer's own noise and nothing else — the mean of
     * −0.01 m is the giveaway. What it cannot contain is the error this factor mainly exists for:
     * a pressure altitude is where the <em>standard</em> atmosphere puts that pressure, and the
     * day's profile is not the standard one. On a real flight that bias is systematic and can run
     * to hundreds of metres in the troposphere.
     *
     * <p>So 1.71 is a floor, not an answer, and the value stays at 4.0. The consequence on DS-6 is
     * stated rather than hidden: the barometric channel is weighted more loosely there than the
     * data strictly requires, which is the conservative direction — it costs a little information
     * on dropout samples and cannot cause the filter to chase an atmospheric offset.
     *
     * <p>verify: the model-bias term needs a real flight log carrying both GPS and barometric
     * altitude through a day whose profile departs from the standard atmosphere. No synthetic
     * dataset generated from the same atmosphere model can supply it.
     */
    public static final double PRESSURE_ALTITUDE_SIGMA_FACTOR = 4.0;

    private static final double LOG_SQRT_TWO_PI = 0.5 * Math.log(2.0 * Math.PI);

    private final double altitudeSigmaM;
    private final double verticalRateSigmaMs;
    private final double horizontalSigmaM;
    private final GeoPoint launchPoint;
    private final double windScaleSigma;

    /**
     * Creates a model with per-channel standard deviations.
     *
     * @param altitudeSigmaM      GPS altitude error, metres
     * @param verticalRateSigmaMs vertical-rate error, m/s — larger than the altitude error implies,
     *                            because the rate is differenced from noisy altitudes
     * @param horizontalSigmaM    horizontal position error, metres
     */
    public GaussianMeasurementModel(double altitudeSigmaM, double verticalRateSigmaMs,
                                    double horizontalSigmaM) {
        this(altitudeSigmaM, verticalRateSigmaMs, horizontalSigmaM, null, 0.0);
    }

    private GaussianMeasurementModel(double altitudeSigmaM, double verticalRateSigmaMs,
                                     double horizontalSigmaM, GeoPoint launchPoint,
                                     double windScaleSigma) {
        if (altitudeSigmaM <= 0 || verticalRateSigmaMs <= 0 || horizontalSigmaM <= 0) {
            throw new IllegalArgumentException("every sigma must be positive");
        }
        if (windScaleSigma < 0) {
            throw new IllegalArgumentException("the wind-scale sigma cannot be negative");
        }
        this.altitudeSigmaM = altitudeSigmaM;
        this.verticalRateSigmaMs = verticalRateSigmaMs;
        this.horizontalSigmaM = horizontalSigmaM;
        this.launchPoint = launchPoint;
        this.windScaleSigma = windScaleSigma;
    }

    /**
     * Returns a copy whose horizontal sigma grows with drift, to account for wind-field error.
     *
     * <p>A wind field wrong by a fractional scale {@code s} puts the balloon wrong by {@code s}
     * times however far it has drifted, so the two error sources combine in quadrature:
     *
     * <pre>
     *   sigma_h(state) = sqrt( sigma_gps^2 + ( sigma_windscale * drift(launch, state) )^2 )
     * </pre>
     *
     * <p>At launch the inflation is nothing and the GPS sigma stands; four hundred kilometres
     * downrange with a 20% wind-scale uncertainty it is eighty kilometres, which is the honest
     * statement of how well a single sounding can place the balloon. The alternative — estimating
     * the wind scale as a fifth parameter — would let one number absorb every unmodelled effect in
     * the horizontal plane and is explicitly not what ADR-7 chose.
     *
     * @param launch         the launch point drift is measured from
     * @param windScaleSigma the fractional uncertainty in the sounding's wind speed, the same
     *                       figure the pre-flight ensemble disperses over (ADR-7)
     * @return a copy with drift-inflated horizontal weighting
     */
    public GaussianMeasurementModel withWindDrift(GeoPoint launch, double windScaleSigma) {
        return new GaussianMeasurementModel(altitudeSigmaM, verticalRateSigmaMs, horizontalSigmaM,
                launch, windScaleSigma);
    }

    /**
     * The model matched to the noise {@code NoiseSpec.standard()} applies, which is what the DS-6
     * flights carry.
     *
     * @return the standard model
     */
    public static GaussianMeasurementModel standard() {
        return new GaussianMeasurementModel(10.0, 2.0, 8.0);
    }

    @Override
    public double logLikelihood(BalloonState predicted, Observation observed) {
        double total = 0.0;

        if (observed.hasAltitude()) {
            double sigma = observed.altitudeFromPressure()
                    ? altitudeSigmaM * PRESSURE_ALTITUDE_SIGMA_FACTOR
                    : altitudeSigmaM;
            total += logGaussian(predicted.altitudeM() - observed.altitudeM(), sigma);
        }
        if (observed.hasVerticalRate()) {
            total += logGaussian(predicted.verticalRateMs() - observed.verticalRateMs(),
                    verticalRateSigmaFor(observed));
        }

        // Horizontal error as one great-circle distance rather than two angular residuals: a
        // degree of longitude is not a degree of latitude, and treating them as interchangeable
        // would make the model latitude-dependent for no reason.
        double horizontalErrorM = Geodesy.haversineMetres(
                predicted.latitudeDeg(), predicted.longitudeDeg(),
                observed.latitudeDeg(), observed.longitudeDeg());
        total += logGaussian(horizontalErrorM, horizontalSigmaFor(predicted));

        return total;
    }

    /**
     * The vertical-rate sigma for an observation, accounting for how the rate was derived.
     *
     * <p>A flight computer reports where it is, not how fast it is climbing, so the rate in an
     * {@link Observation} is a difference of two noisy altitudes over a baseline {@code T}. Its
     * uncertainty is therefore {@code sigma_alt sqrt(2) / T} — at 1 Hz over a two-second central
     * difference with a 10 m GPS, about 7 m/s, which is comparable to the balloon's entire ascent
     * rate. That differencing noise is combined in quadrature with the configured sigma, which is
     * then what it should have been all along: the rate error the <em>model</em> has, not the
     * sensor.
     *
     * <p>Getting this wrong is not a small mis-weighting. Measured on {@code ds6-flight-01} with a
     * flat 2 m/s: at 26.8 km a single GPS error produced a rate that flattered one just-burst
     * particle by enough nats to take the entire weight, all 500 particles were resampled onto it,
     * and the filter spent the rest of the flight in free fall while the telemetry climbed another
     * four kilometres. A likelihood that claims more precision than the measurement has does not
     * merely add noise — it lets a single sample overrule the whole flight.
     */
    private double verticalRateSigmaFor(Observation observed) {
        double baseline = observed.rateIntervalSeconds();
        if (Double.isNaN(baseline) || baseline <= 0.0) {
            return verticalRateSigmaMs;
        }
        double sigma = observed.altitudeFromPressure()
                ? altitudeSigmaM * PRESSURE_ALTITUDE_SIGMA_FACTOR
                : altitudeSigmaM;
        return Math.hypot(verticalRateSigmaMs, sigma * Math.sqrt(2.0) / baseline);
    }

    /** The horizontal sigma at a predicted state, inflated by drift if wind drift is configured. */
    private double horizontalSigmaFor(BalloonState predicted) {
        if (launchPoint == null) {
            return horizontalSigmaM;
        }
        double driftM = Geodesy.haversineMetres(
                launchPoint.latitudeDeg(), launchPoint.longitudeDeg(),
                predicted.latitudeDeg(), predicted.longitudeDeg());
        return Math.hypot(horizontalSigmaM, windScaleSigma * driftM);
    }

    /** Log of a zero-mean Gaussian density at {@code error}. */
    private static double logGaussian(double error, double sigma) {
        double z = error / sigma;
        return -0.5 * z * z - Math.log(sigma) - LOG_SQRT_TWO_PI;
    }

    @Override
    public String name() {
        return "GAUSSIAN";
    }

    /** @return the GPS altitude standard deviation, metres */
    public double altitudeSigmaM() {
        return altitudeSigmaM;
    }

    /** @return the vertical-rate standard deviation, m/s */
    public double verticalRateSigmaMs() {
        return verticalRateSigmaMs;
    }

    /** @return the horizontal standard deviation, metres */
    public double horizontalSigmaM() {
        return horizontalSigmaM;
    }
}
