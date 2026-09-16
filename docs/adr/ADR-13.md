# ADR-13 — The integration step is bounded by descent stability, not by accuracy

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-2.3, NFR-1, NFR-2, T-V3
**Amends:** BLUEPRINT §10 (T-V3 row), BLUEPRINT §6 (FR-2.3 acceptance criterion)

## Context

The blueprint set T-V3 as "RK4 and RKF45 agree within 50 m of landing position at dt ≤ 1 s", and
`SimSettings` defaulted to a 1 s step. Implementing the flight model and running that test showed
the criterion does not hold at 1 s — and the reason turned out to be more interesting than a
tolerance being too tight.

**Measured, on the HabSat-class reference flight in a 12 m/s wind:**

| Integrator | dt (s) | Landing time (s) |
|---|---|---|
| RK4 | 1.0 | 8060.08 |
| RK4 | 0.5 | 8021.16 |
| RK4 | 0.25 | 8020.87 |
| RK4 | 0.125 | 8020.72 |
| RKF45 | 1.0 | 8021.17 |
| RKF45 | 0.25 | 8020.87 |

Everything converges to ≈8020.7 s except **RK4 at 1 s, which is 39 s late** — about 470 m of
landing error at that wind speed, comfortably outside T-V3's 50 m. Printing the descent profile
showed why: below roughly 6 km the RK4 vertical rate oscillates (−6.11, −5.92, −6.17, −5.78 m/s on
consecutive steps) instead of tracking the analytic terminal velocity, which it matches to 0.01%
at a smaller step.

## Analysis

This is an absolute-stability failure, not an accuracy shortfall. Linearising the quadratic drag
term about the terminal velocity gives a real negative eigenvalue

```
lambda = -rho * Cd * A * |v| / m
```

whose magnitude **grows as the payload descends into denser air**: about 0.5 /s just after burst,
but 3.6 /s near the ground for this configuration. An explicit Runge–Kutta method is stable only
while `|lambda| h` stays inside its stability region, and RK4's real-axis boundary is ≈2.785. At
`|lambda| = 3.6 /s` that caps the step at ≈0.77 s — so a 1 s step is outside the region for the
last few kilometres of every descent.

RKF45 survives the same step because its six stages buy a wider region, ≈3.678. The asymmetry the
failing test showed was never about tolerance; it was about stability.

## Decision

1. **The default step becomes 0.25 s**, leaving roughly a threefold margin at the stiffest point
   of a typical descent.
2. **`FlightSimulator` checks the criterion every step and refuses to continue when it is
   violated**, raising `ConvergenceException` (exit 5) whose message names the damping rate, the
   altitude and the largest step that would have been stable. A wrong landing point that does not
   look wrong is the worst possible output for this project, so it is made impossible rather than
   documented.
3. **Each integrator derives its own stability limit** through `Integrator.realAxisStabilityLimit()`,
   by applying itself to `y' = λy` at unit step — which is exactly its stability function `R(λ)` —
   and bisecting for `|R| = 1`. Nothing is transcribed, and the method stays correct for any
   integrator added later. The derivation returns 2.785293563 for RK4, which
   `FlightSimulatorTest` cross-checks against the fourth-order truncation of `exp(z)` evaluated
   directly.
4. **T-V3 is amended** to "at every stable dt ≤ 1 s", and runs at 0.5, 0.25 and 0.125 s. Measured
   separations: **0.003 m, 0.000 m, 0.000 m** against the 50 m tolerance. A companion test asserts
   that a 1 s RK4 step is *refused*, so this finding cannot silently regress.

## Consequences

- **Cost.** A full flight takes ~50 ms at 0.25 s against ~20 ms at 1 s. A 1,000-member ensemble is
  therefore ~50 s single-threaded, which the fixed thread pool of ADR-5 must bring inside NFR-1's
  30 s budget on 4 cores. This is now the binding constraint on NFR-1 and must be re-measured by
  T-P1 when `EnsembleRunner` lands.
- Users who ask for a step that is too large get an actionable error naming the step that works,
  rather than a plausible-looking wrong answer.
- The guard costs one extra atmosphere-free arithmetic evaluation per step, reusing the
  atmospheric state the loop already holds.
- A smaller chute or a heavier payload raises the damping rate further; the guard adapts, because
  it is computed from the actual state rather than from a fixed table.
