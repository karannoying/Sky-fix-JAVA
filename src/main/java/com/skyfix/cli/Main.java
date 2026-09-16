package com.skyfix.cli;

/**
 * Process entry point.
 *
 * <p>Does nothing but construct {@link SkyfixCli} and turn its return value into an exit code, so
 * every behaviour worth testing is reachable without spawning a JVM.
 */
public final class Main {

    private Main() {
    }

    /**
     * Runs SKYFIX.
     *
     * @param args the command line
     */
    public static void main(String[] args) {
        // Console streams are wrapped in UTF-8 explicitly: the default encoding on a Windows
        // terminal is a legacy code page, which turns degree signs and dashes into "?" (NFR-6
        // requires the same output on Windows and Linux).
        java.io.PrintStream out = new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8);
        java.io.PrintStream err = new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.err), true,
                java.nio.charset.StandardCharsets.UTF_8);
        configureLogging();
        System.exit(new SkyfixCli(out, err).execute(args));
    }

    /**
     * Installs the shipped logging configuration unless the user named their own.
     *
     * <p>Per NFR-5 and architecture I4, per-phase detail goes to a file and only WARNING and above
     * reaches the console, so a run summary stays readable. The log directory is created first
     * because {@code FileHandler} will not create it.
     */
    private static void configureLogging() {
        if (System.getProperty("java.util.logging.config.file") != null) {
            return; // the user's choice wins
        }
        try (java.io.InputStream in =
                     Main.class.getResourceAsStream("/logging.properties")) {
            if (in == null) {
                return;
            }
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("out"));
            java.util.logging.LogManager.getLogManager().readConfiguration(in);
        } catch (java.io.IOException e) {
            // Logging that cannot be configured is not a reason to refuse to run; the default
            // console handler stays in place.
            System.err.println("warning: could not configure logging (" + e.getMessage() + ")");
        }
    }
}
