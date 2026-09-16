package com.skyfix.persistence;

/**
 * The provenance of one execution — everything NFR-5 requires a run to persist so it can be
 * reproduced and compared. Row shape of {@code run}.
 *
 * @param id             database key, or {@code null} before insertion
 * @param missionId      owning mission
 * @param balloonConfigId the configuration used
 * @param soundingId     the sounding used, or {@code null} for a windless run
 * @param flightLogId    the telemetry log replayed, or {@code null} for a prediction
 * @param runKind        PREFLIGHT, REPLAY, SYNTH or VALIDATION
 * @param integrator     integrator name
 * @param stepSeconds    integration step, seconds
 * @param memberCount    ensemble members; 1 for a single deterministic flight
 * @param rngSeed        the run seed, from which member seeds are derived (ADR-6)
 * @param gitSha         the commit the binary was built from
 * @param configHash     the configuration identity (ADR-10)
 * @param hostCores      cores available to the JVM
 * @param startedUtc     start time, ISO-8601 UTC
 * @param wallClockMs    elapsed milliseconds, or {@code null} while the run is in progress
 * @param status         RUNNING, OK or FAILED
 */
public record RunRecord(
        Long id,
        long missionId,
        long balloonConfigId,
        Long soundingId,
        Long flightLogId,
        String runKind,
        String integrator,
        double stepSeconds,
        int memberCount,
        long rngSeed,
        String gitSha,
        String configHash,
        int hostCores,
        String startedUtc,
        Long wallClockMs,
        String status) {

    /** Status of a run that is still executing. */
    public static final String RUNNING = "RUNNING";
    /** Status of a run that completed successfully. */
    public static final String OK = "OK";
    /** Status of a run that failed. */
    public static final String FAILED = "FAILED";

    /**
     * @param newId the assigned key
     * @return the identified run
     */
    public RunRecord withId(long newId) {
        return new RunRecord(newId, missionId, balloonConfigId, soundingId, flightLogId, runKind,
                integrator, stepSeconds, memberCount, rngSeed, gitSha, configHash, hostCores,
                startedUtc, wallClockMs, status);
    }

    /**
     * Returns a copy marked finished.
     *
     * @param elapsedMs wall-clock milliseconds
     * @param newStatus {@link #OK} or {@link #FAILED}
     * @return the completed run
     */
    public RunRecord completed(long elapsedMs, String newStatus) {
        return new RunRecord(id, missionId, balloonConfigId, soundingId, flightLogId, runKind,
                integrator, stepSeconds, memberCount, rngSeed, gitSha, configHash, hostCores,
                startedUtc, elapsedMs, newStatus);
    }
}
