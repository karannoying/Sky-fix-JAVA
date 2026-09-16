package com.skyfix.estimation;

import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A telemetry sample prepared for weighting: position, altitude and a derived vertical rate
 * (FR-3.1).
 *
 * <p>A raw {@link TelemetrySample} carries no vertical rate — a flight computer reports where it
 * is, not how fast it is climbing — so the rate is differenced from neighbouring samples before
 * the measurement model sees it. Doing that once, here, rather than inside the likelihood means
 * five hundred particles do not each recompute the same number.
 *
 * @param epochUtc           when the sample was taken
 * @param latitudeDeg        observed latitude
 * @param longitudeDeg       observed longitude
 * @param altitudeM          observed altitude above MSL, metres
 * @param verticalRateMs     vertical rate derived from neighbouring samples, m/s, or NaN if it
 *                           could not be derived
 * @param rateIntervalSeconds the time the rate was differenced over, seconds, or NaN if there is
 *                           no rate. The measurement model needs it: a rate differenced from two
 *                           noisy altitudes is only as precise as that baseline allows, and a
 *                           model that assumes otherwise weights noise as though it were signal
 * @param altitudeFromPressure whether the altitude came from the barometer rather than GPS, which
 *                           the model weights differently
 */
public record Observation(Instant epochUtc, double latitudeDeg, double longitudeDeg,
                          double altitudeM, double verticalRateMs, double rateIntervalSeconds,
                          boolean altitudeFromPressure) {

    /**
     * An observation with no derived vertical rate.
     *
     * @param epochUtc             when the sample was taken
     * @param latitudeDeg          observed latitude
     * @param longitudeDeg         observed longitude
     * @param altitudeM            observed altitude above MSL, metres
     * @param verticalRateMs       vertical rate, m/s, or NaN
     * @param altitudeFromPressure whether the altitude is barometric
     */
    public Observation(Instant epochUtc, double latitudeDeg, double longitudeDeg, double altitudeM,
                       double verticalRateMs, boolean altitudeFromPressure) {
        this(epochUtc, latitudeDeg, longitudeDeg, altitudeM, verticalRateMs, Double.NaN,
                altitudeFromPressure);
    }

    /**
     * Prepares a whole telemetry series for the filter (FR-3.1).
     *
     * <p>The vertical rate is a central difference over the neighbouring samples wherever both
     * exist, and a one-sided difference at the two ends. Central differencing is second-order
     * accurate against the one-sided alternative's first order, which matters here because the
     * rate is what separates an ascending hypothesis from a descending one, and it is also
     * symmetric — a one-sided rate would lag the truth by half a sample and bias every
     * likelihood in the same direction.
     *
     * <p>A sample whose neighbours have no altitude gets no rate, and the measurement model simply
     * drops that channel for it. That is the honest treatment of a GPS dropout: fewer channels,
     * not a fabricated rate.
     *
     * @param series the ingested telemetry
     * @return one observation per sample, in time order
     */
    public static List<Observation> streamOf(TelemetrySeries series) {
        List<TelemetrySample> samples = series.samples();
        List<Observation> out = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            TelemetrySample s = samples.get(i);
            out.add(new Observation(s.epochUtc(), s.latitudeDeg(), s.longitudeDeg(),
                    s.hasGpsAltitude() ? s.altitudeGpsM() : Double.NaN,
                    verticalRateAt(samples, i), rateIntervalAt(samples, i),
                    s.hasFlag(TelemetrySample.FLAG_PRESSURE_ALTITUDE)));
        }
        return List.copyOf(out);
    }

    /** Central difference where both neighbours carry an altitude, one-sided at the ends. */
    private static double verticalRateAt(List<TelemetrySample> samples, int i) {
        double seconds = rateIntervalAt(samples, i);
        if (Double.isNaN(seconds)) {
            return Double.NaN;
        }
        return (samples.get(afterIndex(samples, i)).altitudeGpsM()
                - samples.get(beforeIndex(i)).altitudeGpsM()) / seconds;
    }

    /**
     * The baseline the rate at {@code i} is differenced over, or NaN if no rate can be formed.
     *
     * <p>Kept alongside the rate because the two are only meaningful together: differencing two
     * altitudes each uncertain by {@code sigma} over a baseline {@code T} gives a rate uncertain by
     * {@code sigma sqrt(2) / T}, so at 1 Hz with a 10 m GPS the rate is good to about 7 m/s — which
     * is comparable to the balloon's entire ascent rate. A likelihood that treats such a number as
     * precise to 2 m/s is not weighting a measurement, it is weighting noise, and it hands the
     * whole particle set to whichever hypothesis the last GPS error happened to flatter.
     */
    private static double rateIntervalAt(List<TelemetrySample> samples, int i) {
        int before = beforeIndex(i);
        int after = afterIndex(samples, i);
        if (before == after) {
            return Double.NaN;
        }
        TelemetrySample a = samples.get(before);
        TelemetrySample b = samples.get(after);
        if (!a.hasGpsAltitude() || !b.hasGpsAltitude()) {
            return Double.NaN;
        }
        double seconds = Duration.between(a.epochUtc(), b.epochUtc()).toNanos() / 1e9;
        return seconds > 0.0 ? seconds : Double.NaN;
    }

    private static int beforeIndex(int i) {
        return i > 0 ? i - 1 : i;
    }

    private static int afterIndex(List<TelemetrySample> samples, int i) {
        return i < samples.size() - 1 ? i + 1 : i;
    }

    /** @return whether this observation carries a usable vertical rate */
    public boolean hasVerticalRate() {
        return !Double.isNaN(verticalRateMs);
    }

    /** @return whether this observation carries a usable altitude */
    public boolean hasAltitude() {
        return !Double.isNaN(altitudeM);
    }
}
