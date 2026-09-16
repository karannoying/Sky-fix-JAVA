package com.skyfix.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The provenance of one execution, gathered once at start-up (NFR-5).
 *
 * <p>Seed, git SHA, config hash, host cores and start time are captured here and written into the
 * {@code run} row, because a result that cannot be traced back to the code and inputs that made it
 * is not reproducible.
 */
public final class RunContext {

    private final long seed;
    private final String gitSha;
    private final int hostCores;
    private final String startedUtc;
    private final long startNanos;

    private RunContext(long seed, String gitSha, int hostCores, String startedUtc) {
        this.seed = seed;
        this.gitSha = gitSha;
        this.hostCores = hostCores;
        this.startedUtc = startedUtc;
        this.startNanos = System.nanoTime();
    }

    /**
     * Starts a run context, resolving the git SHA and host core count.
     *
     * @param seed the run seed, from which per-member seeds are derived (ADR-6)
     * @return the context, with its clock already running
     */
    public static RunContext start(long seed) {
        return new RunContext(seed, resolveGitSha(), Runtime.getRuntime().availableProcessors(),
                Instant.now().toString());
    }

    /** @return the run seed */
    public long seed() {
        return seed;
    }

    /** @return the commit the working tree was at, or {@code "unknown"} if it cannot be read */
    public String gitSha() {
        return gitSha;
    }

    /** @return cores available to this JVM */
    public int hostCores() {
        return hostCores;
    }

    /** @return the start time, ISO-8601 UTC */
    public String startedUtc() {
        return startedUtc;
    }

    /** @return milliseconds elapsed since the context was started */
    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Resolves the current commit by reading {@code .git} directly.
     *
     * <p>Reading the files rather than shelling out to {@code git} keeps the process
     * dependency-free and works the same on Windows and Linux (NFR-6). An unavailable SHA is
     * recorded as {@code "unknown"} rather than failing the run — provenance that is missing
     * should be visible, not fatal.
     */
    private static String resolveGitSha() {
        try {
            java.nio.file.Path gitDir = java.nio.file.Path.of(".git");
            if (!java.nio.file.Files.isDirectory(gitDir)) {
                return "unknown";
            }
            String head = java.nio.file.Files.readString(gitDir.resolve("HEAD")).strip();
            if (head.startsWith("ref:")) {
                java.nio.file.Path ref = gitDir.resolve(head.substring(4).strip());
                if (java.nio.file.Files.exists(ref)) {
                    return java.nio.file.Files.readString(ref).strip();
                }
                // A packed ref: scan packed-refs for the branch this HEAD points at.
                java.nio.file.Path packed = gitDir.resolve("packed-refs");
                if (java.nio.file.Files.exists(packed)) {
                    String target = head.substring(4).strip();
                    for (String line : java.nio.file.Files.readAllLines(packed)) {
                        if (line.endsWith(" " + target)) {
                            return line.substring(0, line.indexOf(' ')).strip();
                        }
                    }
                }
                return "unknown";
            }
            return head; // detached HEAD holds the SHA directly
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * Reads a resource as a string, used for banner and help text.
     *
     * @param resource classpath resource name
     * @return the contents, or an empty string if the resource is absent
     */
    static String readResource(String resource) {
        try (InputStream in = RunContext.class.getResourceAsStream(resource)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
