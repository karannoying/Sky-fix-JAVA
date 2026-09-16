package com.skyfix.estimation;

import com.skyfix.domain.TelemetrySample;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Detects burst from a replayed telemetry stream (FR-3.4).
 *
 * <p>Burst is the one moment the dynamics change discontinuously — buoyancy vanishes and a
 * parachute takes over — so the estimator has to know when it happened, and it has to find out from
 * noisy telemetry rather than from the simulator.
 *
 * <p><strong>Detection.</strong> A window of recent samples is fitted with a least-squares line to
 * get a smoothed vertical rate; differencing consecutive altitudes would be hopeless with a
 * ten-metre GPS error, since the true signal between samples is about five metres. Burst is
 * declared once that slope has been convincingly negative for several consecutive samples, having
 * previously been positive.
 *
 * <p><strong>Timing.</strong> The event is <em>not</em> reported at the sample that confirmed it —
 * that would be several seconds and several hundred metres late, outside T-E2's tolerance. Once
 * descent is confirmed, the detector looks back over its buffer and attributes the burst to the
 * highest sample it holds, because burst <em>is</em> apogee. That makes the reported time accurate
 * to about one sample interval no matter how long confirmation took.
 *
 * <p><strong>Dropouts.</strong> A GPS outage is where a naive detector goes wrong. When the fix
 * returns, or when altitude switches to a pressure-derived value, the step between the two sources
 * looks like a sudden descent. Requiring the slope to stay negative across several samples, over a
 * window longer than a typical outage, is what makes a dropout a non-event — which is exactly what
 * T-E2 checks.
 *
 * <p>Stateful and single-threaded: one detector belongs to one replay.
 */
public final class BurstDetector {

    /** Samples in the regression window. At 1 Hz this is a nine-second view. */
    public static final int DEFAULT_WINDOW = 9;
    /** Consecutive descending samples needed before burst is declared. */
    public static final int DEFAULT_CONFIRMATIONS = 3;
    /** Slope, m/s, below which a sample counts as descending. */
    public static final double DEFAULT_DESCENT_THRESHOLD_MS = -2.0;
    /** Slope, m/s, above which the vehicle is considered to be genuinely climbing. */
    public static final double DEFAULT_ASCENT_THRESHOLD_MS = 1.0;

    private final int window;
    private final int confirmations;
    private final double descentThreshold;
    private final double ascentThreshold;

    private final Deque<TelemetrySample> recent = new ArrayDeque<>();
    private boolean hasAscended;
    private int consecutiveDescending;
    private BurstEvent burst;

    /** Creates a detector with the documented defaults. */
    public BurstDetector() {
        this(DEFAULT_WINDOW, DEFAULT_CONFIRMATIONS, DEFAULT_DESCENT_THRESHOLD_MS,
                DEFAULT_ASCENT_THRESHOLD_MS);
    }

    /**
     * Creates a detector with explicit tuning.
     *
     * @param window           samples in the regression window; at least three
     * @param confirmations    consecutive descending samples required
     * @param descentThreshold slope in m/s below which a sample counts as descending; negative
     * @param ascentThreshold  slope in m/s above which the vehicle counts as climbing; positive
     */
    public BurstDetector(int window, int confirmations, double descentThreshold,
                         double ascentThreshold) {
        if (window < 3) {
            throw new IllegalArgumentException("window must be at least 3, was " + window);
        }
        if (confirmations < 1) {
            throw new IllegalArgumentException(
                    "confirmations must be at least 1, was " + confirmations);
        }
        if (descentThreshold >= 0 || ascentThreshold <= 0) {
            throw new IllegalArgumentException(
                    "descent threshold must be negative and ascent threshold positive");
        }
        this.window = window;
        this.confirmations = confirmations;
        this.descentThreshold = descentThreshold;
        this.ascentThreshold = ascentThreshold;
    }

    /**
     * Feeds one sample to the detector.
     *
     * <p>Once burst has been detected the detector stops: further samples are ignored and
     * {@link #currentVerticalRateMs()} freezes at its last value. A detector's job ends at the
     * event it was looking for, and the descent rate is the estimator's business. Call
     * {@link #reset()} to reuse the instance on another flight.
     *
     * @param sample the next telemetry sample
     * @return the burst event on the sample that confirms it, empty otherwise. Only ever returns a
     *         value once per flight; afterwards use {@link #burst()}
     */
    public Optional<BurstEvent> observe(TelemetrySample sample) {
        if (burst != null) {
            return Optional.empty();
        }
        if (!sample.hasGpsAltitude()) {
            // No altitude at all -- neither a fix nor a usable barometer. The sample carries no
            // vertical information, so it is skipped rather than fed in as a gap that would look
            // like a step.
            return Optional.empty();
        }

        recent.addLast(sample);
        while (recent.size() > window) {
            recent.removeFirst();
        }
        if (recent.size() < window) {
            return Optional.empty();
        }

        double slope = smoothedVerticalRate();
        if (slope > ascentThreshold) {
            hasAscended = true;
            consecutiveDescending = 0;
            return Optional.empty();
        }
        if (!hasAscended) {
            // Never seen a convincing climb, so there is nothing that could have burst. This is
            // what stops a detector firing during the seconds on the pad before release.
            return Optional.empty();
        }

        if (slope < descentThreshold) {
            consecutiveDescending++;
            if (consecutiveDescending >= confirmations) {
                burst = attributeToApogee(sample.epochUtc());
                return Optional.of(burst);
            }
        } else {
            consecutiveDescending = 0;
        }
        return Optional.empty();
    }

    /**
     * Least-squares slope of altitude against time over the window, in m/s.
     *
     * <p>A regression rather than a difference of endpoints: with a ten-metre GPS error and a
     * five-metre-per-second true signal, differencing two samples gives a rate whose noise is
     * three times the signal. Fitting the whole window divides that error by roughly the square
     * root of the window length and, more importantly, is not thrown by any single bad fix.
     */
    private double smoothedVerticalRate() {
        double baseSeconds = recent.getFirst().epochUtc().toEpochMilli() / 1000.0;
        double sumT = 0, sumA = 0, sumTT = 0, sumTA = 0;
        int n = recent.size();
        for (TelemetrySample s : recent) {
            double t = s.epochUtc().toEpochMilli() / 1000.0 - baseSeconds;
            double a = s.altitudeGpsM();
            sumT += t;
            sumA += a;
            sumTT += t * t;
            sumTA += t * a;
        }
        double denominator = n * sumTT - sumT * sumT;
        if (Math.abs(denominator) < 1e-9) {
            return 0.0; // every sample at the same instant; no rate is defined
        }
        return (n * sumTA - sumT * sumA) / denominator;
    }

    /**
     * Attributes the burst to the highest sample in the buffer.
     *
     * <p>Burst is apogee, so the peak of the retained window is a far better estimate of when and
     * where it happened than the sample that happened to confirm it — which is
     * {@code confirmations} samples later and, at thirty metres per second of early descent,
     * hundreds of metres lower.
     */
    private BurstEvent attributeToApogee(Instant detectedAt) {
        TelemetrySample peak = recent.getFirst();
        for (TelemetrySample s : recent) {
            if (s.altitudeGpsM() > peak.altitudeGpsM()) {
                peak = s;
            }
        }
        return new BurstEvent(peak.epochUtc(), peak.altitudeGpsM(), peak.packetId(), detectedAt);
    }

    /** @return the detected burst, if one has been detected */
    public Optional<BurstEvent> burst() {
        return Optional.ofNullable(burst);
    }

    /** @return whether burst has been detected */
    public boolean hasBurst() {
        return burst != null;
    }

    /** @return the smoothed vertical rate over the current window, or NaN before it fills */
    public double currentVerticalRateMs() {
        return recent.size() < window ? Double.NaN : smoothedVerticalRate();
    }

    /** Resets the detector so one instance can replay a second flight. */
    public void reset() {
        recent.clear();
        hasAscended = false;
        consecutiveDescending = 0;
        burst = null;
    }
}
