# ADR-1 — US Standard Atmosphere 1976, hand-written, 0–86 km

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-2.1, FR-2.3, NFR-2, O1

## Context

The vertical dynamics are buoyancy minus weight minus drag. Buoyancy and drag are both linear in
air density, so the ascent rate the model predicts is only as good as its density profile. A
balloon flight spends most of its time between 10 and 35 km, which is exactly the band where the
crude models are worst.

Three candidates: a single-scale-height exponential, the US Standard Atmosphere 1976, and
NRLMSISE-00.

## Decision

Implement USSA-1976 by hand, as seven piecewise layers in geopotential height, valid from −5 km to
86 km geometric. Only the layer base temperatures and lapse rates are transcribed; every base
pressure is computed by recursion from sea level, so the table cannot become internally
inconsistent. Keep `ExponentialAtmosphere` behind the same interface as the comparison case.

## Alternatives considered

**Single-scale-height exponential.** Rejected, and the cost is measured rather than asserted:
`AtmosphereComparisonTest` fits the scale height to USSA density at sea level and at 11 km — the
exponential model at its most flattering — and still finds **20.4% density error at the tropopause
and up to 48.3% by 35 km**. Since ascent rate follows density almost linearly, that is an order of
magnitude outside the 2% tolerance T-V2 sets.

**NRLMSISE-00.** Rejected. It needs F10.7 and Ap space-weather indices, which means a download,
which breaks the offline rule (CLAUDE.md rule 2). Its advantages are above ~90 km, which is above
this project's ceiling anyway.

**A library implementation.** Rejected by CLAUDE.md rule 1: the course rewards the implementation,
and this model is roughly 120 lines of arithmetic.

## Consequences

- **A hard ceiling at 86 km.** Above it the standard switches to a diffusive, composition-varying
  regime this model does not implement, so `stateAt` raises `ModelDomainException` (exit code 4)
  rather than extrapolating. The 45 km mission has 41 km of headroom.
- **A published table becomes a real oracle.** T-V1 compares against DS-3 at 25 altitudes and
  requires 0.1% on T, p and ρ. Measured: **T 0.0008%, p 0.0096%, ρ 0.0107%** — about ten times
  inside tolerance, and at the level of the disagreement between the two independent
  implementations that generated the reference file.
- The geopotential/geometric distinction has to be handled explicitly. A query at 11 km geometric
  returns 216.77 K, not the 216.65 K printed against the 11 km layer base, because that base is a
  geopotential height. `Ussa1976AtmosphereTest.geopotentialConversionRoundTrips` pins this.
