package com.skyfix.cli;

import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.Posterior;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.persistence.ValidationResult;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * Renders tables, summaries and errors for the terminal (NFR-3, NFR-6).
 *
 * <p>Errors are rendered as one actionable line naming the offending field or {@code file:line}.
 * A user never sees a stack trace; an unexpected failure points at a log file instead.
 */
public final class ConsoleReporter {

    private final PrintStream out;
    private final PrintStream err;

    /**
     * @param out where normal output goes
     * @param err where errors go
     */
    public ConsoleReporter(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Prints the model-versus-reference table that report §10 and §11 quote (FR-4.4).
     *
     * @param results  the validation results
     * @param skipped  cases that are specified but not yet implemented
     * @return how many results breached their tolerance
     */
    public long printValidationTable(List<ValidationResult> results, List<String> skipped) {
        out.println();
        out.println("SKYFIX reference-case validation");
        out.println("=".repeat(96));
        out.printf(Locale.ROOT, "%-6s %-34s %14s %14s %11s %7s%n",
                "CASE", "QUANTITY", "MODEL", "REFERENCE", "ERROR", "RESULT");
        out.println("-".repeat(96));

        String currentCase = null;
        long failures = 0;
        for (ValidationResult r : results) {
            if (!r.caseId().equals(currentCase)) {
                if (currentCase != null) {
                    out.println();
                }
                currentCase = r.caseId();
            }
            boolean relative = ValidationResult.RELATIVE.equals(r.toleranceKind());
            out.printf(Locale.ROOT, "%-6s %-34s %14.6g %14.6g %10s %7s%n",
                    r.caseId(),
                    truncate(r.quantity(), 34),
                    r.modelValue(),
                    r.referenceValue(),
                    relative ? String.format(Locale.ROOT, "%.4f%%", r.error() * 100)
                             : String.format(Locale.ROOT, "%.4g", r.error()),
                    r.passed() ? "PASS" : "FAIL");
            if (!r.passed()) {
                failures++;
            }
        }

        out.println("-".repeat(96));
        summariseByCase(results);

        if (!skipped.isEmpty()) {
            out.println();
            out.println("Not yet implemented (reported, not silently passed):");
            for (String s : skipped) {
                out.println("  - " + s);
            }
        }

        out.println();
        out.printf(Locale.ROOT, "%d checks, %d passed, %d failed%n",
                results.size(), results.size() - failures, failures);
        return failures;
    }

    private void summariseByCase(List<ValidationResult> results) {
        for (String caseId : results.stream().map(ValidationResult::caseId).distinct().toList()) {
            List<ValidationResult> inCase = results.stream()
                    .filter(r -> r.caseId().equals(caseId)).toList();
            double worst = inCase.stream().mapToDouble(ValidationResult::error).max().orElse(0);
            long failed = inCase.stream().filter(r -> !r.passed()).count();
            boolean relative = ValidationResult.RELATIVE.equals(inCase.get(0).toleranceKind());
            out.printf(Locale.ROOT, "  %-6s %2d checks, tolerance %-10s worst %-12s %s%n",
                    caseId, inCase.size(),
                    relative ? String.format(Locale.ROOT, "%.3f%%",
                            inCase.get(0).tolerance() * 100)
                            : String.format(Locale.ROOT, "%.4g %s", inCase.get(0).tolerance(),
                                    inCase.get(0).unit()),
                    relative ? String.format(Locale.ROOT, "%.5f%%", worst * 100)
                            : String.format(Locale.ROOT, "%.4g", worst),
                    failed == 0 ? "PASS" : failed + " FAILED");
        }
    }

    /**
     * Prints the outcome of a prediction.
     *
     * @param runId     the run this belongs to
     * @param history   the trajectory
     * @param windField which wind field was used
     * @param outputDir where the exports went
     */
    public void printPrediction(long runId, StateHistory history, String windField,
                                java.nio.file.Path outputDir) {
        out.println();
        out.printf(Locale.ROOT, "Run %d - pre-flight prediction (wind: %s)%n", runId, windField);
        out.println("-".repeat(64));
        history.burst().ifPresent(b -> out.printf(Locale.ROOT,
                "  burst        %,10.0f m   at T+%.0f s%n", b.altitudeM(), b.timeSeconds()));
        history.landing().ifPresent(l -> {
            out.printf(Locale.ROOT, "  landing      %10.6f, %.6f%n",
                    l.latitudeDeg(), l.longitudeDeg());
            out.printf(Locale.ROOT, "  flight time  %,10.0f s   (%.0f min)%n",
                    l.timeSeconds(), l.timeSeconds() / 60.0);
            out.printf(Locale.ROOT, "  touchdown    %10.2f m/s%n", l.verticalRateMs());
        });
        out.printf(Locale.ROOT, "  apogee       %,10.0f m%n", history.apogeeM());
        out.printf(Locale.ROOT, "  steps        %,10d%n", history.stepCount());
        if (history.windExtrapolatedCount() > 0) {
            out.printf(Locale.ROOT,
                    "  WARNING      %,10d steps used wind held above the sounding's top level%n",
                    history.windExtrapolatedCount());
        }
        out.println();
        out.println("  exports      " + outputDir);
    }

    /**
     * Prints the outcome of an ensemble prediction — the footprint, not just a point.
     *
     * @param result the ensemble result
     */
    public void printFootprint(com.skyfix.app.PredictionService.EnsembleResult result) {
        var ensemble = result.ensemble();
        out.println();
        out.printf(Locale.ROOT, "Run %d - landing footprint, %d members (wind: %s)%n",
                result.run().id(), ensemble.members().size(), result.windFieldName());
        out.println("-".repeat(72));

        result.nominalHistory().burst().ifPresent(b -> out.printf(Locale.ROOT,
                "  nominal burst   %,10.0f m   at T+%.0f s%n", b.altitudeM(), b.timeSeconds()));
        out.printf(Locale.ROOT, "  mean burst      %,10.0f m%n", ensemble.meanBurstAltitudeM());
        result.nominalHistory().landing().ifPresent(l -> out.printf(Locale.ROOT,
                "  flight time     %,10.0f s   (%.0f min)%n",
                l.timeSeconds(), l.timeSeconds() / 60.0));
        out.println();

        for (var ellipse : result.ellipses()) {
            out.printf(Locale.ROOT, "  %2.0f%% ellipse   centre %10.6f, %.6f%n",
                    ellipse.confidence() * 100,
                    ellipse.centre().latitudeDeg(), ellipse.centre().longitudeDeg());
            out.printf(Locale.ROOT,
                    "                 axes %s at %.0f deg | area %s%n",
                    axes(ellipse), ellipse.azimuthDeg(), area(ellipse));
        }

        out.println();
        if (ensemble.failureCount() > 0) {
            out.printf(Locale.ROOT, "  WARNING      %d of %d members failed and were discarded%n",
                    ensemble.failureCount(), ensemble.members().size());
        }
        out.printf(Locale.ROOT, "  ensemble     %,d ms on %d threads%n",
                ensemble.wallClockMs(), result.threadCount());
        out.println("  exports      " + result.outputDir());
    }

    /**
     * Prints a summary of a generated DS-6 set.
     *
     * @param flights  the generated flights
     * @param outputDir where the telemetry logs went
     * @param manifest  where the truth manifest went
     */
    public void printSynthSummary(java.util.List<com.skyfix.app.SynthService.GeneratedFlight> flights,
                                  java.nio.file.Path outputDir, java.nio.file.Path manifest) {
        out.println();
        out.printf(Locale.ROOT, "DS-6 evaluation set: %d synthetic flights%n", flights.size());
        out.println("-".repeat(78));
        out.printf(Locale.ROOT, "%-16s %10s %12s %12s %9s%n",
                "FLIGHT", "BURST (m)", "ASCENT Cd", "FREE LIFT", "SAMPLES");
        out.println("-".repeat(78));

        double minBurst = Double.MAX_VALUE;
        double maxBurst = -Double.MAX_VALUE;
        for (var flight : flights) {
            var truth = flight.truth();
            minBurst = Math.min(minBurst, truth.burstAltitudeM());
            maxBurst = Math.max(maxBurst, truth.burstAltitudeM());
            out.printf(Locale.ROOT, "%-16s %10.0f %12.4f %12.4f %9d%n",
                    flight.name(), truth.burstAltitudeM(), truth.parameters().ascentCd(),
                    truth.parameters().freeLiftKg(), truth.sampleCount());
        }
        out.println("-".repeat(78));
        out.printf(Locale.ROOT, "  burst altitude spans %,.0f to %,.0f m%n", minBurst, maxBurst);
        out.println("  telemetry    " + outputDir);
        out.println("  truth        " + manifest);
        out.println();
        out.println("  These are SYNTHETIC flights with known truth (DS-6). They exist because no");
        out.println("  real 30 km or 45 km log has flown yet, and they are what T-V5 and T-V6 are");
        out.println("  scored against. Nothing here is an observation.");
    }

    /**
     * Prints a line of normal output.
     *
     * @param message the message
     */
    public void info(String message) {
        out.println(message);
    }

    /**
     * Renders a SKYFIX failure as one actionable line (NFR-3).
     *
     * @param e the failure
     */
    public void error(SkyfixException e) {
        err.println("error: " + e.userMessage());
    }

    /**
     * Renders an unexpected failure, pointing at the log rather than printing a trace.
     *
     * @param e       the failure
     * @param logPath where the full trace was written
     */
    public void unexpectedError(Throwable e, String logPath) {
        err.println("error: unexpected failure (" + e.getClass().getSimpleName() + "): "
                + e.getMessage());
        err.println("       full details in " + logPath);
    }

    private static String truncate(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "...";
    }

    /**
     * Prints what a replay recovered: the posterior, the burst, and the final footprint (FR-3.2,
     * FR-3.3).
     *
     * <p>Bands rather than point estimates, because a median with no interval is exactly the
     * over-confident answer this project exists to replace. A collapsed effective sample size is
     * called out in words — a reader should not have to know what "ESS" means to see that the
     * estimate is not worth much.
     *
     * @param result what the replay produced
     */
    public void printReplay(com.skyfix.app.ReplayService.ReplayResult result) {
        var posterior = result.finalPosterior();
        out.println();
        out.printf(Locale.ROOT, "Run %d - replay estimate, %d particles (wind: %s)%n",
                result.run().id(), posterior.particleCount(), result.windFieldName());
        out.println("-".repeat(72));

        result.burst().ifPresentOrElse(
                b -> out.printf(Locale.ROOT,
                        "  burst detected  %,10.0f m   at %s (%.0f s after the sign change)%n",
                        b.altitudeM(), b.epochUtc(), b.detectionLag().toMillis() / 1000.0),
                () -> out.println("  burst           not detected in this log"));
        out.printf(Locale.ROOT, "  assimilated     %,10d samples, %d re-predictions%n",
                result.assimilatedCount(), result.repredictionCount());
        out.println();

        out.println("  parameter          median        5%         95%");
        printBand("free lift (kg)", posterior, Posterior.FREE_LIFT);
        printBand("ascent Cd", posterior, Posterior.ASCENT_CD);
        printBand("burst scale", posterior, Posterior.BURST_SCALE);
        printBand("parachute Cd", posterior, Posterior.CHUTE_CD);
        out.println();

        result.lastUpdate().ifPresent(update -> {
            out.printf(Locale.ROOT, "  final footprint at T+%.0f s%n", update.flightSeconds());
            for (var ellipse : update.ellipses()) {
                out.printf(Locale.ROOT, "  %2.0f%% ellipse   centre %10.6f, %.6f%n",
                        ellipse.confidence() * 100,
                        ellipse.centre().latitudeDeg(), ellipse.centre().longitudeDeg());
                out.printf(Locale.ROOT,
                        "                 axes %s at %.0f deg | area %s%n",
                        axes(ellipse), ellipse.azimuthDeg(), area(ellipse));
            }
            out.println();
        });

        if (posterior.isDegenerate()) {
            out.printf(Locale.ROOT,
                    "  WARNING      effective sample size fell to %.0f of %d particles; the bands "
                            + "above rest%n               on a handful of hypotheses and should "
                            + "not be read as a calibrated interval%n",
                    posterior.effectiveSampleSize(), posterior.particleCount());
        }
        out.printf(Locale.ROOT, "  filter       ESS %.0f of %d | %d resamples%n",
                posterior.effectiveSampleSize(), posterior.particleCount(),
                posterior.resampleCount());
        out.printf(Locale.ROOT, "  re-predict   %,d ms mean over %d runs on %d threads%n",
                result.meanRepredictMs(), result.repredictionCount(), result.threadCount());
    }

    private void printBand(String label, Posterior posterior, String name) {
        var band = posterior.band(name);
        out.printf(Locale.ROOT, "  %-16s %9.4f %9.4f %9.4f%n",
                label, band.median(), band.p05(), band.p95());
    }

    /**
     * The ellipse's semi-axes, with enough precision to stay meaningful when one of them is small.
     *
     * <p>A footprint minutes from touchdown genuinely has a sub-metre minor axis, and rounding that
     * to "0 m" reads as a defect rather than as the certainty it actually represents.
     *
     * @param ellipse the ellipse
     * @return the axes as a printable pair
     */
    private static String axes(LandingEllipse ellipse) {
        return String.format(Locale.ROOT, "%s x %s",
                distance(ellipse.semiMajorM()), distance(ellipse.semiMinorM()));
    }

    /** A distance printed at a precision that suits its size. */
    private static String distance(double metres) {
        if (metres >= 10_000.0) {
            return String.format(Locale.ROOT, "%,.1f km", metres / 1000.0);
        }
        if (metres >= 10.0) {
            return String.format(Locale.ROOT, "%,.0f m", metres);
        }
        return String.format(Locale.ROOT, "%.2f m", metres);
    }

    /** An ellipse area in the unit that keeps it readable. */
    private static String area(LandingEllipse ellipse) {
        double km2 = ellipse.areaKm2();
        if (km2 >= 0.01) {
            return String.format(Locale.ROOT, "%,.2f km2", km2);
        }
        return String.format(Locale.ROOT, "%,.0f m2", km2 * 1_000_000.0);
    }
}
