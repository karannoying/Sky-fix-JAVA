package com.skyfix.domain;

/**
 * How a synthetic flight's telemetry is corrupted before it is written (FR-1.4).
 *
 * <p>A generator that emits the simulator's own states verbatim would make the estimator's job
 * trivially easy and T-V5 meaningless: the filter would be recovering parameters from data with no
 * measurement error, which is not the problem the project exists to solve. These are the error
 * sources a real flight computer actually has.
 *
 * @param gpsHorizontalSigmaM  standard deviation of horizontal GPS error, metres
 * @param gpsAltitudeSigmaM    standard deviation of GPS altitude error, metres — larger than the
 *                             horizontal error, as it is on any GPS receiver
 * @param pressureRelativeSigma standard deviation of barometer error, as a fraction of the reading
 * @param temperatureSigmaK    standard deviation of temperature error, K
 * @param dropoutProbability   chance that any given packet loses its GPS fix, 0 to 1
 * @param dropoutRunLength     how many consecutive packets a dropout lasts once it starts —
 *                             GPS outages come in runs, not as independent single samples
 */
public record NoiseSpec(
        double gpsHorizontalSigmaM,
        double gpsAltitudeSigmaM,
        double pressureRelativeSigma,
        double temperatureSigmaK,
        double dropoutProbability,
        int dropoutRunLength) {

    /**
     * @throws IllegalArgumentException if a sigma is negative or the probability is out of range
     */
    public NoiseSpec {
        if (gpsHorizontalSigmaM < 0 || gpsAltitudeSigmaM < 0 || pressureRelativeSigma < 0
                || temperatureSigmaK < 0) {
            throw new IllegalArgumentException("noise standard deviations must not be negative");
        }
        if (dropoutProbability < 0.0 || dropoutProbability > 1.0) {
            throw new IllegalArgumentException(
                    "dropout probability must lie in [0, 1], was " + dropoutProbability);
        }
        if (dropoutRunLength < 1) {
            throw new IllegalArgumentException("dropout run length must be at least 1");
        }
    }

    /**
     * The noise level T-E2 specifies: 10 m GPS altitude error, and dropouts that last about three
     * seconds at 1 Hz.
     *
     * @return the standard noise specification
     */
    public static NoiseSpec standard() {
        return new NoiseSpec(8.0, 10.0, 0.002, 1.0, 0.004, 3);
    }

    /** @return a specification with no noise at all, for tests that need the clean signal */
    public static NoiseSpec none() {
        return new NoiseSpec(0.0, 0.0, 0.0, 0.0, 0.0, 1);
    }
}
