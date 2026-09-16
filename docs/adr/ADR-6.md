# ADR-6 — One derived seed per member, via `SplittableRandom.split()`

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-2.4, NFR-5, T-R1

## Context

T-R1 requires that the same seed reproduces identical ellipse parameters to 1e-9, across separate
JVM runs. An ensemble runs on a thread pool, so anything that draws from a shared generator makes
the result depend on the order threads happen to reach it.

## Decision

The run seed is split once per member with `SplittableRandom.split()`, **before any task is
submitted**. Member *k* gets its own generator, and the Latin-hypercube permutations come from a
separate stream split off the same seed. No shared mutable `Random` exists anywhere in the
ensemble path.

## Alternatives considered

**A shared synchronised `Random`.** Rejected: results would depend on thread interleaving, which
breaks T-R1 outright, and the lock would serialise the one part of the run that should scale.

**`ThreadLocalRandom`.** Rejected for the same reason in a subtler form — which thread a member
lands on is not reproducible, so neither are its parameters.

**Seeding each member with `seed + k`.** Rejected: consecutive seeds can produce correlated
streams in some generators, and correlation across members is exactly what dispersion must not
have. `split()` is designed for this.

## Consequences

- The design is a pure function of `(seed, memberCount, spec)`. `EnsembleRunnerTest` asserts that
  member *k* receives identical parameters whether the pool has one thread or four.
- **Member *k* is reproducible in isolation.** Debugging an outlier — the member that landed
  40 km from the rest — means re-running that member alone with the same seed and member count,
  rather than reproducing the whole ensemble and hoping the outlier recurs.
- Results are sorted by member index before the ellipse is fitted, so completion order cannot
  leak into the answer through the order of the landing-point list.
- The seed is persisted on the `run` row (NFR-5), so any stored run can be reproduced from the
  database alone.

## Measured

`T-R1` runs the same 150-member ensemble on one thread and on four and compares the fitted
ellipse: semi-major, semi-minor, azimuth and centre all agree to within 1e-9.
