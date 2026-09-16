# ADR-15 — Timing runs are measured without coverage instrumentation

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** NFR-1, NFR-4, T-P1, T-P2

## Context

Two of the build's jobs conflict. NFR-4 wants JaCoCo instrumenting every class to enforce the 80%
coverage gate; NFR-1 wants a wall-clock figure for a 1,000-member ensemble. Both were running in
the same `-Pperf` invocation, because the profile only widened the test selection and left
everything else in place.

JaCoCo works by attaching a bytecode agent that rewrites each class as it loads, adding a probe to
every branch. The ensemble's hot path — the integrator's inner loop, the atmosphere's layer lookup,
the wind field's binary search — is exactly the tight numerical code that instrumentation slows
most, and it runs tens of millions of times in a single T-P1.

## Decision

The `perf` profile sets `jacoco.skip=true`. Coverage and timing are separate runs:

- `./mvnw verify` — the coverage gate (NFR-4, T-M1)
- `./mvnw verify -Pperf` — the timing and reproducibility figures (NFR-1, T-P1, T-P2, T-R1)

## What prompted it

T-P1's first measurement came back at **91,670 ms** against a 30,000 ms budget and was reported as
a failure. It was not one. That run was invalid twice over: it executed under the JaCoCo agent, and
it shared four cores with a concurrent build and a separate 1,000-member prediction.

Re-measured with instrumentation off and nothing else running, on the same hardware and against a
sounding-interpolated wind field: **5,232 / 5,836 / 5,862 ms, median 5,836 ms** — about sixteen
times faster than the void reading, and five times inside the budget. T-P2 came back at
**1,054 ms** against 5,000 ms.

## Consequences

- **A performance number is only as good as the conditions it was taken under.** Both the profile
  change and this record exist so the figure quoted in the report cannot quietly be an instrumented
  one. If a future T-P1 fails, the first question is whether the agent was attached and what else
  held the CPU.
- The perf tests now run against `SoundingWindField` rather than `ConstantWindField`. A constant
  wind skips the profile's binary search on every derivative evaluation, which flattered the
  measurement; NFR-1's budget is for a real run, so the test does what a real run does.
- CI runs `./mvnw -B verify`, which keeps the coverage gate. Timing is not asserted in CI at all,
  and deliberately so: a shared runner's wall clock says nothing about a 4-core laptop, and a
  timing test that fails on a busy runner teaches people to ignore red builds.
- The report must state the measurement conditions alongside the number: 4 cores, JDK 21, warm JVM,
  ≤2 GB heap, no instrumentation, sounding wind field, 3-run median.
