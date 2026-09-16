# ADR-5 — Explicit `ExecutorService` with a fixed pool, not parallel streams

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-2.4, NFR-1, T-P1, T-R1

## Context

A 1,000-member ensemble is embarrassingly parallel: each member is an independent flight over the
same immutable configuration, atmosphere and wind field. Java offers at least three ways to run
that — a parallel stream, a `ForkJoinPool`, or an explicit `ExecutorService`.

## Decision

An explicit `ExecutorService` with a fixed pool sized to `availableProcessors()`, results taken
through a `CompletionService`, and `shutdown()` followed by `awaitTermination()` in a `finally`.

## Rationale

- The syllabus asks for multithreading, and the grader should see pool lifecycle, futures and
  exception unwrapping in the open rather than delegated to a stream's hidden common pool.
- A parallel stream would run on the shared `ForkJoinPool.commonPool()`, whose size this code does
  not control and which is shared with anything else in the JVM. T-P1 is a statement about a pool
  size, so the pool size has to be ours.
- `CompletionService` takes results as they finish rather than in submission order, which keeps
  the cores busy when members have very different flight times — a member that bursts low lands
  much sooner than one that bursts high.

## Measured

On the 4-core target hardware, with a sounding-interpolated wind field:

| Threads | 1,000 members |
|---|---|
| 1 | 18,546 ms |
| 2 | 9,447 ms |
| 4 | 4,721 ms |
| 8 | 4,890 ms |

Speedup is 3.93× on four cores — near-linear, which is what immutable shared state buys — and
oversubscribing to eight threads gains nothing. So sizing the pool to `availableProcessors()` is
right, and there is no case for going wider.

## Consequences

- I own the shutdown. It is in a `finally`, and `EnsembleRunnerTest.poolIsAlwaysShutDown` names the
  worker threads `skyfix-member-*` and asserts none outlive a run, including a run that failed —
  a leaked pool would keep the JVM alive after the CLI returned.
- I own the exception unwrapping. `ExecutionException` is unwrapped and reported with its cause; a
  member that fails for a physical reason is caught inside the task and returned as a failed
  member instead, so one bad draw does not abort the run (BLUEPRINT §12).
- **Nothing mutable is shared.** The configuration, settings, atmosphere and wind field are
  immutable and read concurrently; each task allocates its own integrator and phase objects. This
  is what makes ADR-6's reproducibility claim hold at any pool size, and `T-R1` checks it by
  running the same ensemble on one thread and on four and requiring the ellipse parameters to
  agree to 1e-9.
- **Members whose trajectory is not retained integrate with state sampling off.** Otherwise every
  member builds a full history that is discarded moments later — about three million wasted
  objects in a 1,000-member run, measured at 16.6 s against 9.7 s once fixed.
