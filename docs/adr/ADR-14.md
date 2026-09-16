# ADR-14 — Latin hypercube over plain Monte Carlo, and a closed-form ellipse

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-2.4, O2, T-U-LHS, T-U-ELLIPSE, T-V7

## Context

FR-2.4 turns a nominal configuration into a footprint by dispersing five parameters and
aggregating where the members land. Two choices sit inside that: how to draw the design, and how
to turn a scatter of landing points into an ellipse.

## Decision 1 — Latin hypercube sampling

Each dimension is cut into *N* equal-probability strata, exactly one sample is taken from each,
and the strata are permuted independently per dimension.

**Why not plain Monte Carlo.** With *N* independent uniform draws, roughly a third of the strata
go unvisited while others are hit repeatedly — `DispersionSamplerTest` measures more than a fifth
empty at *N* = 1,000 — so the footprint wobbles from seed to seed at a given member count. LHS
guarantees marginal coverage by construction, so a smaller ensemble gives a stable ellipse.

**The property is checkable exactly, not statistically.** T-U-LHS asserts that every stratum in
every dimension holds exactly one sample. A companion test shows plain Monte Carlo fails the same
assertion, so the test genuinely distinguishes the two rather than being one any sampler passes.

**The blueprint's convergence claim is measured, and retired.** BLUEPRINT §10 claimed LHS "reaches
a stable 95% ellipse in ~400 members where plain MC needs ~1,500" — a ratio of 3.75 — and carried an
unverified-claim marker because nobody had measured it. `SamplerConvergenceTest` (`-Pperf`) is the
measurement. The advantage is real and it is **about 2x**, not 3.75x.

**The first attempt used the wrong statistic, and got the sign wrong.** It ran one seed per point
and compared each ensemble's fitted semi-major axis with its own 3,200-member value:

| members | LHS deviation | plain MC deviation |
|---|---|---|
| 100 | +6.64% | +1.85% |
| 200 | +1.19% | +5.98% |
| 400 | +3.33% | +0.42% |
| 800 | −0.39% | −0.53% |
| 1,600 | +0.57% | +1.02% |

Read on its own that table says LHS is no better and is worse at 100 members, and an earlier
revision of this ADR said exactly that. It is not evidence either way. "Stable at *N* members"
means *another ensemble of N members gives you the same axis* — the quantity is the spread of the
axis across independent draws, and one draw per point is a single sample from that spread. Each
number above is one sample of a quantity whose width is the entire question, so their ordering is
noise.

**Measured properly — seven independent seeds per point, and the statistic is the scatter:**

| members | LHS axis (mean) | LHS sd | LHS cv | MC axis (mean) | MC sd | MC cv | MC/LHS |
|---|---|---|---|---|---|---|---|
| 100 | 56,389 m | 3,198 m | 5.67% | 56,418 m | 3,633 m | 6.44% | 1.14 |
| 200 | 56,520 m | 1,733 m | 3.07% | 55,035 m | 2,411 m | 4.38% | 1.43 |
| 400 | 55,156 m | 1,564 m | 2.83% | 55,767 m | 2,659 m | 4.77% | 1.68 |
| 800 | 54,727 m | 853 m | 1.56% | 55,497 m | 1,385 m | 2.50% | 1.60 |
| 1,600 | 55,531 m | 699 m | 1.26% | 55,850 m | 977 m | 1.75% | 1.39 |
| 3,200 | 55,386 m | 607 m | 1.10% | 55,713 m | 847 m | 1.52% | 1.38 |

LHS's scatter is lower at every count, by about 1.4x on average. Both fall as a power of the member
count — **N^-0.53 for LHS and N^-0.46 for plain Monte Carlo**, either side of the N^-1/2 a Monte
Carlo estimator is supposed to follow, which is the check that the measurement is measuring
convergence at all.

Because the scatter falls as roughly the square root, a 1.4x advantage in scatter is about a **2x**
advantage in members: at 400 LHS members the scatter is 2.49%, and plain Monte Carlo reaches that
at about 880. **So the direction of the blueprint's claim was right and its size was not** — 2.2x
against the 3.75x asserted. The claim is retired rather than simply deleted: a reader who believed
"400 against 1,500" would over-trust a 400-member footprint by about 70%, which is the kind of
error the marker existed to prevent.

The 3,200-member row costs as much as all the others together and is not in the committed test,
which sweeps 100 to 1,600. Its numbers came from the same run and are reported here because the
ratio's slow drift with *N* — 1.96x at 100 rising to about 2.4x at 1,600 — is worth seeing.

Seven seeds puts roughly a 29% relative standard error on each individual `sd`, so no single row
carries much weight; the result is the consistency of the ordering across six counts and two
independently fitted power laws, not any one cell. The seeds are fixed and the simulator is
reproducible to 1e-9 (T-R1), so the test is deterministic and its assertions are exact rather than
statistical.

None of this changes the decision. LHS was chosen for a property that is proved exactly rather than
statistically — T-U-LHS asserts one sample per stratum per dimension, and a companion test shows
plain Monte Carlo leaves more than a fifth of them empty — and the measurement now says what that
property is worth: about half the members for the same stability, on this problem.

**Truncation by clamping, not rejection.** Physical parameters have hard bounds — the schema
confines drag coefficients to [0.1, 2.0] — but rejecting an out-of-range draw and redrawing would
break the one-sample-per-stratum property. The quantile is clamped to the truncation bounds
instead.

## Decision 2 — A closed-form 2×2 eigen-decomposition

Landing points are projected onto a local east-north tangent plane about their mean, and the 2×2
covariance is decomposed in closed form: eigenvalues from the trace and determinant, the major
axis from the eigenvector of the larger one.

**Why not an iterative solver.** A symmetric 2×2 eigenproblem has an exact algebraic solution, so
there is no convergence criterion to tune and nothing to iterate. That also settles the "is this a
job for a library?" question raised by CLAUDE.md rule 1: the whole decomposition is a dozen lines
of arithmetic.

**Why a tangent plane.** A covariance computed in degrees is anisotropic anywhere but the equator —
a degree of longitude at 23°N is 92% of a degree of latitude — so the ellipse would be skewed by
the coordinate system rather than by the physics.

**The confidence scaling is derived, not pasted.** For a bivariate normal the squared Mahalanobis
distance is chi-square with two degrees of freedom, whose CDF is `1 − exp(−s²/2)`; inverting gives
`s = sqrt(−2 ln(1 − p))`. So the 50% and 95% factors come out of the closed form rather than from
a table, and `GaussianTest` checks the inversion round-trips exactly.

## Measured

`T-U-ELLIPSE` builds a cloud with known axes and orientation and requires the fitter to recover
them: from a cloud built at 19,582 × 6,119 m on a 35° bearing, the fit returned
**19,468 × 6,088 m at 35.27°** — 0.58%, 0.51% and 0.27° of error, against a 2% tolerance.

Axes being right does not prove the scaling is, so containment is measured separately: the 50%,
90% and 95% ellipses contained **49.9%, 90.0% and 95.1%** of the cloud. That is the check T-V7
will make against real ensembles.

## Consequences

- A degenerate scatter — every member landing on one point, or exactly on a line — would give a
  zero axis, which the schema's `semi_major_m > 0` CHECK forbids. The axes are floored at one
  centimetre, far below any real dispersion, so the record stays storable.
- The fitted ellipse assumes the landing scatter is approximately Gaussian. Under a strongly
  sheared wind it may not be, and a very high aspect ratio is the signal: a constant-wind ensemble
  produces an aspect ratio in the thousands, because with unidirectional wind every bit of the
  dispersion lands along one axis. T-V7's containment check is what will show whether the Gaussian
  assumption holds on real profiles; if it does not, the honest fix is a convex hull or a kernel
  density contour, reported as such.
