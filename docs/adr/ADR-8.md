# ADR-8 — A CLI with exported files, not a GUI

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** NFR-3, NFR-6, FR-4.2, T-U1

## Context

The deliverable has to be demonstrable by a grader with no hardware and no prior setup, and the
marks come from the computational core rather than from widgets. No GUI toolkit appears in the lab
record for this course.

## Decision

A command-line interface — `ingest`, `predict`, `validate`, `version` — with results exported as
CSV, GeoJSON and (from week 6) PNG charts. A Swing view stays in the Stretch tier and would attach
as a `RunListener` without touching the service layer.

## Consequences and what implementing it settled

- **`SkyfixCli.execute` returns an exit code; only `Main` calls `System.exit`.** So every path,
  including every failure path, is exercised in-process by `SkyfixCliTest` without spawning a JVM.
  The exit codes in BLUEPRINT §12 are a scripting contract, and a contract nobody tests is a
  contract that drifts.
- **Everything that can be rejected is rejected before a row is written.** An unknown integrator
  name was initially caught by the `CHECK` constraint on `run.integrator`, which surfaced as a
  database error (exit 6) instead of a `ValidationException` naming the field (exit 2), and left a
  `RUNNING` row behind for a run that never started. `PredictionService` now resolves the
  integrator and the wind field before saving the run record.
- **Console output is ASCII, on UTF-8 streams.** A Windows terminal defaults to a legacy code page,
  which turned dashes and ellipses into `?`. NFR-6 requires the same output on Windows and Linux,
  so `Main` wraps `stdout` and `stderr` in UTF-8 explicitly and the table itself avoids characters
  that would need it.
- **Logs go to a file, warnings to the console.** Per-phase `INFO` mixed into the validation table
  makes it unreadable. The shipped `logging.properties` sends detail to `out/skyfix.0.log` — NFR-5's
  audit trail — and lets only `WARNING` and above reach the terminal, so a wind-extrapolation
  warning is still visible where it matters.
- **`validate` reports what it has not implemented.** T-V5 to T-V7 need the ensemble and the
  estimator. The command names them, with the requirement and the week they are due, rather than
  printing a clean pass over a suite that silently omits its hardest cases.
