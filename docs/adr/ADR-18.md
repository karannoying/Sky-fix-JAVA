# ADR-18 — The posterior comes from a bank of independent filters, not from one

**Status:** accepted · **Date:** 2026-09-15 · **Affects:** FR-3.2, T-V5, T-V6, ADR-3
**Amends:** ADR-3 (particle cap), BLUEPRINT §10 (T-V6 calibration line)

## Context

SKYFIX exists to replace a confident point estimate with an honest interval. ADR-3 delivered a
particle filter whose medians are good — burst altitude recovered on 20 of 20 DS-6 flights inside
T-V5's 500 m — and whose intervals were not intervals at all.

**Measured over the twenty DS-6 flights, how often a single filter's 5-95% band contained the value
the flight was generated from:**

| parameter | coverage |
|---|---|
| free lift | 0 / 20 |
| ascent Cd | 0 / 20 |
| burst scale | 0 / 20 |
| parachute Cd | 5 / 20 |

A calibrated 5-95% band should contain it about 18 times in 20. A band that essentially never does
is worse than no band, because it invites exactly the false confidence the project exists to
remove.

## The diagnosis

The question that settled it: is the error statistical, or is it Monte Carlo? Eight filters,
identical in every respect but their seed, were run over the same flight:

| seed | free lift | ascent Cd | burst scale | chute Cd |
|---|---|---|---|---|
| 0 | 1.1665 | 0.4924 | 1.1270 | 1.5674 |
| 1 | 1.0315 | 0.4463 | 1.1126 | 1.5869 |
| 2 | 1.1359 | 0.4840 | 1.1182 | 1.3743 |
| 3 | 0.9683 | 0.4254 | 1.1028 | 1.6210 |
| 4 | 1.1198 | 0.4760 | 1.1210 | 1.1833 |
| 5 | 1.4841 | 0.5939 | 1.1593 | 1.3345 |
| 6 | 1.3400 | 0.5518 | 1.1408 | 1.4096 |
| 7 | 1.1646 | 0.4933 | 1.1226 | 1.3087 |
| *truth* | *1.0119* | *0.4409* | *1.1075* | *1.3050* |

The eight disagree about ascent Cd across **0.425 to 0.594, a spread of 0.169**, while each one
reported a 5-95% band of width **0.0017**. Every filter understated its own uncertainty by a factor
of about a hundred.

So the dominant error is not statistical but algorithmic. Free lift and ascent drag are
near-degenerate (ADR-3), so the posterior is a long thin ridge; a finite particle set resampled a
hundred times performs a random walk along that ridge, stops somewhere arbitrary, and reports the
width of whatever it collapsed onto. The band measures the collapse, not the uncertainty.

The important consequence: **no number of particles in one filter fixes this**, because a single
filter cannot observe its own Monte Carlo scatter. The truth does, however, lie inside the spread
of the eight — for all four parameters.

## Decision

Run `k` independent filters that share nothing but the telemetry, and pool their particles into one
posterior. Each filter contributes its own normalised weights scaled by `1/k`, so the pooled set is
a sample from the even mixture of the member posteriors — and the between-run scatter, which is the
error that actually dominates, is inside the band by construction rather than by hope.

The budget is **split, not multiplied**. Eight filters of 63 particles cost the same sequential
work as one of 500, and run in parallel on an explicit fixed pool (ADR-5), so the wall clock
improves.

**Measured coverage, twenty DS-6 flights, nominally 18/20:**

| configuration | free lift | ascent Cd | burst scale | chute Cd | burst ≤ 500 m |
|---|---|---|---|---|---|
| 1 filter × 500 | 0/20 | 0/20 | 0/20 | 5/20 | 20/20 |
| 8 filters × 62 (500 total) | 13/20 | 16/20 | 14/20 | 16/20 | 19/20 |
| **16 filters × 125 (2,000 total)** | **17/20** | **17/20** | **15/20** | **20/20** | **20/20** |

(Those three rows assimilate every 20th sample. `ParameterRecoveryTest`, which assimilates every
10th, measures the default bank at 15/15/16/16 with burst altitude 20/20 — the same picture, and
the numbers the test gates on.)

The middle row is the one that makes the argument: **at an unchanged particle budget, coverage goes
from 0/20 to 13-16/20.** The calibration is bought by splitting the budget, not by spending more.
The bottom row is the default, because sixteen members stop the band's own percentiles being
estimated from a handful of draws while leaving each filter enough particles that the medians do
not suffer — burst altitude returns to 20 of 20.

## Consequences

- **ADR-3's 500-particle cap is superseded.** That cap was about holding FR-3.3's cost, and the
  filter was never the expensive half: an update costs about 2 ms per second of flight time at 500
  particles against a 100 ms budget (ADR-17), so 2,000 is still comfortably inside it. The
  re-prediction ensemble is unchanged at 200 members.
- Bands are now usable, but they are still slightly narrow: 15-20 of 20 against a nominal 18. Burst
  scale is the weakest at 15/20. They should be reported as measured coverage rather than claimed
  coverage until T-V6 has more to say.
- The medians are no better, and for ascent Cd they remain what ADR-3's identifiability analysis
  says they must be. Pooling fixes the interval, not the ridge.
- `ReplayOptions.particleCount` is now the total across the bank rather than per filter. A
  `filterCount` of 1 reproduces the old single-filter behaviour, and is retained for exactly one
  purpose: showing, in the report, what a single filter's band looks like.
- A bank owns a thread pool and must be closed. `ReplayService` does so with try-with-resources.
