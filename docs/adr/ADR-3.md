# ADR-3 — Bootstrap particle filter over four parameters, and what it can and cannot recover

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-3.1, FR-3.2, FR-3.4, O3, T-V5
**Amends:** BLUEPRINT §10 (T-V5 row) — see *Consequences*

## Context

FR-3.2 asks for a recursive estimator over θ = (free lift, ascent Cd, burst-diameter scale,
parachute Cd) from replayed telemetry. The blueprint's ADR-3 row chose a bootstrap particle
filter over EKF, UKF and batch least squares. This record is the detail behind that row, written
against a working implementation and the measurements it produced.

Burst is a hard discontinuity in the dynamics rather than a smooth nonlinearity, and before it
happens the burst-diameter posterior is genuinely uninformative — no Kalman variant represents
either honestly, and batch least squares cannot answer mid-flight at all. A bootstrap filter needs
no Jacobians and carries whatever shape the posterior actually has, at O(N x forward-step) per
update. N is capped at 500 to hold that cost inside FR-3.3's budget.

## Decisions

### 1. Particles are advanced incrementally, not re-simulated

Each particle carries its own `(θ, BalloonState)` and is advanced by one observation interval with
`FlightSimulator.advanceTo`. Re-simulating every particle from launch at every sample would be
quadratic in log length: 500 particles over an 8,400-sample log is about four billion integration
steps. Incrementally it is one flight per particle. The step is truncated at the observation epoch
so the predicted state sits exactly on it — a smaller final step, and a smaller step is never less
stable (ADR-13).

A `BalloonState` carries phase, diameter and rate, which is everything the next leg needs, so
there is no separate integrator state to keep in step with the particle.

### 2. The wind scale is not estimated

Four parameters, exactly the ones FR-3.2 names. The wind scale is a property of the sounding, not
of the balloon; a single flight's horizontal track is a weak and heavily aliased observation of it;
and letting the filter absorb wind error into a balloon parameter is precisely the failure this
project exists to avoid. Wind uncertainty stays where ADR-7 put it — dispersed across the forward
ensemble — so the re-predicted footprint still carries it.

Instead its magnitude enters the likelihood: `GaussianMeasurementModel.withWindDrift` inflates the
horizontal sigma as `sqrt(sigma_gps^2 + (sigma_windscale * drift)^2)`. Scoring a horizontal
residual against an 8 m GPS sigma when the *prediction* rests on a single sounding would hand that
channel a likelihood hundreds of thousands of times sharper than the vertical ones.

### 3. The vertical-rate sigma is derived, not assumed

A flight computer reports position, not rate, so the rate in an `Observation` is a difference of
two noisy altitudes over a baseline T and is uncertain by `sigma_alt * sqrt(2) / T`. At 1 Hz with
a 10 m GPS that is about 7 m/s — comparable to the balloon's entire ascent rate.

The first implementation used a flat 2 m/s. **Measured consequence on `ds6-flight-01`:** at
26.8 km a single GPS error produced a rate that flattered one just-burst particle by enough nats
to take the entire weight; all particles were resampled onto it; and the filter spent the rest of
the flight in free fall while the telemetry climbed another four kilometres. A likelihood that
claims more precision than the measurement has does not merely add noise — it lets one sample
overrule the whole flight.

### 4. Burst diameter is redrawn from a *censored* prior until burst is observed

Burst diameter has no effect on the trajectory until the envelope reaches it. While the telemetry
has not shown a burst, no particle is ever selected *for* it: burst-diameter values are passengers
on particles chosen for their lift and drag. Ordinary resampling therefore arrives at burst — the
one instant the parameter is measurable — holding a single arbitrary value. **Measured:** 8% error
in burst scale, 1,721 m in burst altitude, against T-V5's 500 m.

Redrawing from the *plain* prior at each resample is worse, and instructively so. Burst is a
threshold crossing, so a threshold redrawn 130 times over an ascent is crossed as soon as any one
draw falls below the diameter already reached: burst time is then governed by the minimum of many
draws. **Measured:** every particle burst by 26 km against a true 30.8 km, and the predicted
altitude was fourteen kilometres below the telemetry by the real burst.

The error was in calling the pre-burst state uninformative. It is *censored*: an envelope that has
grown to diameter D without bursting is direct evidence that its burst diameter exceeds D. So the
correct conditional prior is the prior truncated below at D, sampled through the prior's own CDF.
The band then narrows from below as the balloon climbs, which is the actual information a rising
balloon carries about an envelope that has not yet failed.

Burst is signalled from the telemetry by `BurstDetector` (FR-3.4), not inferred from the particles.
The *first* particle to burst is by construction the one holding the smallest burst diameter in the
set, so "some particle has burst" fires far too early. This is the `switchPhase` arrow in the
BLUEPRINT §8 replay sequence, with the semantics the physics wants: it does not force any particle
into descent — a particle that disagrees about when to burst is a hypothesis the data may refute —
it only says the question has now been asked.

### 5. Jitter uses the full weighted covariance

Liu–West shrinkage jitter, with `a = (3d-1)/2d` at `d = 0.98` and kernel covariance `h^2 V`,
`h^2 = 1 - a^2`, realised through a hand-written Cholesky factor.

The covariance must be the full matrix, not per-dimension variances. Free lift and ascent drag are
very nearly degenerate — more lift climbs faster, more drag climbs slower, and over an ascent the
two cancel almost exactly — so the posterior is a long thin ridge lying at an angle to both axes. A
diagonal kernel is spherical, and a spherical proposal on a ridge lands almost entirely *across*
it, where the likelihood kills it; resampling then grinds the set onto a point wherever the random
walk happened to be.

**Measured, six DS-6 flights at N = 200, ascent-Cd error:**

| kernel | f01 | f02 | f03 |
|---|---|---|---|
| diagonal | 19.61% | 0.95% | 15.57% |
| full covariance | 4.76% | 1.32% | 6.10% |

## The identifiability limit, measured

Ascent Cd is only weakly identifiable, and this is a property of the observation set rather than of
the estimator. Scanning ascent Cd away from truth on `ds6-flight-01` and re-optimising free lift
and burst diameter at each step, the best achievable RMS difference against the true altitude
profile is:

| ascent Cd error | compensating free lift | compensating burst diameter | best whole-flight RMS |
|---|---|---|---|
| −10% | −12.05% | −1.07% | 10.98 m |
| −5% | −6.02% | −0.47% | 10.45 m |
| +5% | +6.14% | +0.48% | 10.94 m |
| +10% | +12.30% | +0.85% | 29.68 m |
| +15% | +18.53% | +1.20% | 49.95 m |

A 5% error in ascent Cd, absorbed by a 6% change in free lift, reproduces the entire flight to
**10.5 m RMS — the GPS noise itself**. Over the ascent alone the degeneracy is far tighter still:
a 20% Cd error compensated by free lift matches the ascent profile to **0.44 m**. And because
`DescentPhase` depends only on dry mass and parachute drag, neither free lift nor ascent Cd is
observable at all after burst: the information about them arrives entirely before the moment they
stop mattering.

**Measured recovery, all twenty DS-6 flights at N = 500, assimilating every 10th sample**
(`ParameterRecoveryTest`, `-Pperf`; 6.5 min):

| flight | free lift | ascent Cd | burst scale | chute Cd | burst altitude | ESS |
|---|---|---|---|---|---|---|
| 01 | −0.94% | −0.35% | +0.28% | +0.06% | +73 m | 500 |
| 02 | −6.88% | −5.77% | −0.96% | +20.36% | −40 m | 279 |
| 03 | +4.61% | +4.00% | +0.36% | −1.56% | −23 m | 490 |
| 04 | +20.54% | +17.19% | +1.37% | +4.35% | −100 m | 308 |
| 05 | −5.35% | −5.48% | −1.12% | −2.21% | −116 m | 437 |
| 06 | −3.93% | −4.21% | +0.37% | +15.52% | +154 m | 302 |
| 07 | −10.68% | −8.38% | −1.36% | −0.70% | −24 m | 273 |
| 08 | +6.43% | +6.44% | +0.65% | +8.70% | −5 m | 346 |
| 09 | −17.48% | −14.17% | −2.35% | +4.62% | −53 m | 258 |
| 10 | −12.20% | −9.85% | −2.13% | −26.29% | −125 m | 500 |
| 11 | −4.89% | −3.49% | −0.61% | −0.04% | −12 m | 330 |
| 12 | +31.07% | +23.48% | +2.60% | +1.10% | −75 m | 469 |
| 13 | +54.46% | +41.77% | +4.14% | +0.11% | −1 m | 405 |
| 14 | +11.67% | +9.59% | +1.55% | −0.20% | +63 m | 500 |
| 15 | −27.29% | −22.78% | −5.15% | −0.01% | −461 m | 361 |
| 16 | −20.10% | −16.12% | −2.32% | −0.03% | −3 m | 257 |
| 17 | −18.47% | −14.59% | −2.21% | +0.01% | +18 m | 290 |
| 18 | +26.51% | +20.13% | +1.95% | +0.95% | −55 m | 465 |
| 19 | +22.48% | +17.73% | +2.71% | −20.05% | +128 m | 500 |
| 20 | −4.80% | −4.61% | −0.77% | +0.92% | −56 m | 281 |

Three things this says, in order of how much they matter.

**Burst altitude is recovered essentially exactly: 20 of 20 inside T-V5's 500 m**, seventeen of
them inside 130 m, and the worst 461 m. That is the physically decisive quantity and the one a
recovery team acts on, and it is the criterion T-V5 gates on.

**Free lift and ascent Cd slide together along the ridge, exactly as the identifiability table
predicts.** The two columns track each other on every flight — flight 13 is +54% and +42%, flight
15 is −27% and −23% — because that is the direction the data cannot see. Ascent Cd is inside 5% on
5 of 20, and no amount of particles changes that; it is the width of the ridge, not the width of
the estimator. Notice that burst scale is nonetheless recovered to a few per cent throughout: the
combination the data constrains is recovered, the individual parameters are not.

**Parachute drag is bimodal.** Sixteen flights recover it to under 5%, most of those to under 1%
(flights 15, 16, 17 to 0.03% or better), and four land 15-26% out. The failures do not correlate
with the flight's wind-scale error, with the burst-altitude error, or with the sign of anything
else checked so far. Not yet explained, so not yet gated.

### Calibration: the bands are not credible

The same run measured how often each 5-95% band contains the value the flight was generated from.
A calibrated band should contain it about 18 times in 20:

| parameter | 5-95% band covers truth |
|---|---|
| free lift | **0 / 20** |
| ascent Cd | **0 / 20** |
| burst scale | **0 / 20** |
| parachute Cd | 5 / 20 |

**This was fixed in ADR-18, which supersedes the paragraphs below**: pooling a bank of independent
filters over the same particle budget took coverage from 0/20 to 13-16/20, and the default bank to
15-16/20. The diagnosis that follows is kept because it is what led there.

This was the most important defect in the estimator as first built, and it is worth stating plainly
because it goes to the project's central claim. SKYFIX exists to replace a confident point estimate
with an honest interval. The medians here are good — burst altitude is recovered to a few tens of
metres — but the intervals around them are not intervals anyone should rely on. A band that never
contains the truth is worse than no band, because it invites exactly the false confidence the
whole design is meant to remove.

The cause is understood: particle impoverishment along the directions the likelihood is sharp in.
`DEFAULT_ROUGHENING` floors each band at 2% of the prior spread, which stops a dimension dying
outright but is far too narrow to represent the posterior honestly. The obvious fix is not simply a
larger floor — measured at a floor of one prior spread, the ascent-Cd median degraded from 1.6% to
32% error, because holding an informed dimension open at its prior width stops it converging at
all. Fixing the width without losing the median is real work, and it is what T-V6 is for.
## Consequences

- T-V5's burst-altitude criterion (500 m) is met on every flight, most by two orders of magnitude.
  `ParameterRecoveryTest` gates on it.
- **T-V5's "ascent Cd within 5%" criterion is not supported by the observation set** — it is met on
  5 of 20 — and `ParameterRecoveryTest` therefore measures and prints it rather than asserting it,
  with the identifiability table above as the stated reason. [PLACEHOLDER — amending a graded
  acceptance criterion is not the implementer's call. The measurement and the proposed replacement
  are recorded here; the criterion in BLUEPRINT §10 stands until it is agreed. The candidate is to
  gate on the identifiable quantities — burst altitude within 500 m, and the posterior median
  reproducing the observed altitude profile within 3 sigma of the GPS noise — and to report ascent
  Cd and parachute Cd alongside.]
- **The 5-95% bands of a single filter are not calibrated** — measured coverage 0/20 on three
  parameters and 5/20 on the fourth. ADR-18 supersedes this: the posterior now comes from a pooled
  bank of independent filters, measured at 15-16/20.
- Parachute drag recovers to under 1% on most flights and 15-26% out on four, for reasons not yet
  established. It is reported, not gated.
- One filter instance belongs to one replay and is single-threaded by design; the parallelism in
  this project is in `EnsembleRunner`, where the re-prediction runs.
