package com.skyfix.estimation;

import java.time.Duration;
import java.time.Instant;

/**
 * A detected burst: where and when the envelope let go (FR-3.4).
 *
 * <p>Two epochs, because they answer different questions. {@link #epochUtc} is when the burst is
 * judged to have <em>happened</em>, which is what the estimator needs in order to switch to the
 * descent model at the right point. {@link #detectedAtUtc} is when the detector became
 * <em>confident</em>, which is necessarily later — a sign change cannot be confirmed from the
 * sample it occurs on — and is what a recovery lead sees on screen.
 *
 * @param epochUtc       when the burst is judged to have occurred
 * @param altitudeM      the altitude it occurred at, metres
 * @param packetId       the packet the burst is attributed to
 * @param detectedAtUtc  when the detector confirmed it
 */
public record BurstEvent(Instant epochUtc, double altitudeM, int packetId,
                         Instant detectedAtUtc) {

    /**
     * How long confirmation took after the event itself.
     *
     * @return the lag; T-E2 requires it to stay within five seconds
     */
    public Duration detectionLag() {
        return Duration.between(epochUtc, detectedAtUtc);
    }

    @Override
    public String toString() {
        return String.format("burst at %.0f m, %s (confirmed %.1f s later)",
                altitudeM, epochUtc, detectionLag().toMillis() / 1000.0);
    }
}
