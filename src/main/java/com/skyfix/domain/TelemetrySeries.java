package com.skyfix.domain;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;

/**
 * An ordered, de-duplicated run of telemetry, with a record of what was wrong with the input
 * (FR-1.2).
 *
 * <p>The anomaly counts are part of the value, not a side channel. FR-1.2's acceptance criterion is
 * that duplicate packet ids and out-of-order timestamps are "counted and surfaced, never silently
 * dropped", so a caller that ignores them has to do so deliberately.
 *
 * <p>Iterable in time order.
 */
public record TelemetrySeries(
        List<TelemetrySample> samples,
        int duplicatePacketCount,
        int outOfOrderCount,
        int gpsDropoutCount,
        List<String> rejections) implements Iterable<TelemetrySample> {

    /**
     * @param samples              the accepted samples, in time order
     * @param duplicatePacketCount how many packets repeated an id already seen
     * @param outOfOrderCount      how many packets arrived with a timestamp before their predecessor
     * @param gpsDropoutCount      how many packets had no GPS fix
     * @param rejections           one message per unusable line, each naming {@code file:line:reason}
     */
    public TelemetrySeries {
        samples = List.copyOf(samples);
        rejections = List.copyOf(rejections);
    }

    /** @return how many samples were accepted */
    public int size() {
        return samples.size();
    }

    /** @return whether the series holds no samples */
    public boolean isEmpty() {
        return samples.isEmpty();
    }

    /** @return the first sample */
    public TelemetrySample first() {
        return samples.get(0);
    }

    /** @return the last sample */
    public TelemetrySample last() {
        return samples.get(samples.size() - 1);
    }

    /** @return wall-clock span from the first sample to the last */
    public Duration duration() {
        return isEmpty() ? Duration.ZERO : Duration.between(first().epochUtc(), last().epochUtc());
    }

    /**
     * Whether anything about the input was irregular.
     *
     * @return {@code true} if any duplicate, out-of-order packet, dropout or rejected line was seen
     */
    public boolean hasAnomalies() {
        return duplicatePacketCount > 0 || outOfOrderCount > 0 || gpsDropoutCount > 0
                || !rejections.isEmpty();
    }

    /**
     * A one-line description of the anomalies, for the run summary.
     *
     * @return the summary, or {@code "clean"} if there was nothing to report
     */
    public String anomalySummary() {
        if (!hasAnomalies()) {
            return "clean";
        }
        return String.format("%d duplicate packet ids, %d out of order, %d GPS dropouts, "
                        + "%d lines rejected",
                duplicatePacketCount, outOfOrderCount, gpsDropoutCount, rejections.size());
    }

    @Override
    public Iterator<TelemetrySample> iterator() {
        return samples.iterator();
    }
}
