# ADR-17 — Re-prediction runs on a flight-time interval, not on every sample

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-3.3, T-P2, NFR-1
**Amends:** BLUEPRINT §8 (replay sequence diagram), BLUEPRINT §6 (FR-3.3 acceptance criterion)

## Context

FR-3.3 reads:

> 200-member re-prediction in ≤5 s, so a 1 Hz log replayed at 10× drops no updates (T-P2).

The BLUEPRINT §8 replay sequence draws the re-prediction inside the per-sample loop, immediately
after each filter update.

Those two cannot both be right. A 1 Hz log replayed at 10× delivers a sample every **100 ms** of
wall clock. A re-prediction allowed **5 s** is fifty times that budget, so a per-sample
re-prediction would fall behind by a factor of fifty whatever the implementation — the criterion
would be unsatisfiable rather than merely demanding, and no amount of optimisation inside the
5 s allowance would change it.

The stated rationale is nonetheless arithmetically exact under one reading, and it is worth
spelling out because it is the reading that makes the number meaningful:

```
  replay speed                   10x
  wall-clock budget              100 ms per second of flight time
  re-prediction cost             5 s   (FR-3.3)
  affordable interval            5 s / 0.1 = 50 s of flight time
```

A re-prediction costing 5 s is affordable once per **50 s of flight time**, and at that cadence a
10× replay drops nothing. The "5 s" in FR-3.3 is therefore a statement about a *cadence*, not
about a per-sample cost.

## Decision

Separate the two loops, because they have different costs and different jobs.

**The filter update runs on every assimilated sample.** It is cheap: measured at N = 500, one
update costs about 17 ms while advancing 10 s of flight time, or roughly 2 ms per second of flight
time — comfortably inside the 100 ms budget. Assimilating every sample is what keeps the posterior
current, and it is what makes burst detection sharp.

**The re-prediction runs at most once per `REPREDICT_INTERVAL_SECONDS` of flight time**, default
**50 s**, derived above rather than chosen. It is the expensive half: 200 forward flights from the
current measured state, parallelised across the `EnsembleRunner` pool.

A re-prediction is also forced, off-cadence, at two moments where waiting would be indefensible:
the first update after burst is detected, because the footprint changes completely there and a
recovery team watching the console needs it immediately; and the final update of the replay, so
the last stored ellipse is the best one the run produced.

## Consequences

- **BLUEPRINT §8's sequence diagram is wrong as drawn** and is corrected in the same commit as this
  record (CLAUDE.md rule 6): the `repredict` and `saveEllipse` arrows move out of the per-sample
  loop into a guarded block.
- FR-3.3's acceptance criterion is restated as: *one 200-member re-prediction completes in ≤5 s,
  and re-predictions are issued no more often than once per 50 s of flight time, so a 1 Hz log
  replayed at 10× drops no updates.* T-P2 measures the first clause; the second is a property of
  the scheduler and is asserted directly.
- A replay of a three-hour flight issues roughly 220 re-predictions rather than about 8,000. The
  stored ellipse history is correspondingly a readable series rather than a per-second dump, which
  is what FR-4.1's error-against-time table wants anyway.
- The interval is configurable. A user who wants a re-prediction on every sample can ask for it and
  will simply not achieve 10× replay — which is the honest trade, stated rather than hidden.
