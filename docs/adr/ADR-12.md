# ADR-12 — RKF45 ships as a fixed-step cross-check, not an adaptive integrator

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-2.3, NFR-2, T-V3, T-R1

## Context

The blueprint selects RK4 as the default integrator and lists RKF45 as "available", for two stated
reasons: it "gives the error estimate" and it provides "the T-V3 cross-check". Runge–Kutta–Fehlberg
is normally used adaptively — its embedded 4th- and 5th-order pair exists so a solver can size its
own steps. Shipping it adaptively would conflict with two other requirements.

## Decision

`Rkf45Integrator` evaluates the full six-stage embedded pair, **propagates the fifth-order
solution, and keeps the 4th/5th difference as a local truncation-error estimate** readable through
`Integrator.lastErrorEstimate()`. It does **not** adapt the step size.

## Rationale

- **FR-2.3 specifies a fixed step**, and every stored state record carries the step size that
  produced it (CLAUDE.md rule 3). An adaptive solver has no single step size to record.
- **T-R1 requires that the same seed reproduces identical ellipse parameters to 1e-9.** Adaptive
  stepping makes the step sequence depend on the trajectory, so two members that differ only in a
  dispersed parameter take different steps, and the reproducibility argument gets much harder to
  make.
- **T-V3's value is independence, not adaptivity.** What makes "RK4 and RKF45 agree within 50 m"
  a real cross-check is that the two schemes have different coefficients, different stage counts
  and different orders. A bug in the flight derivative shows up in both; a bug in an integrator
  shows up in one. Adaptivity adds nothing to that argument.

## Consequences

- The error estimate is available for logging and for the report, but nothing acts on it
  automatically. If a future step-size study wants adaptivity, the estimate is already computed.
- RKF45 costs six derivative evaluations per step against RK4's four, so it is the cross-check
  integrator rather than the default.
- `Rkf45Integrator` holds per-step mutable state (the error estimate), so `IntegratorFactory`
  returns a fresh instance per call and never shares one across ensemble threads.
- The class Javadoc states plainly that the step is not adapted, so nobody reads "RKF45" and
  assumes otherwise.
