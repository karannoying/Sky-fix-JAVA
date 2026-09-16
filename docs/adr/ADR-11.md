# ADR-11 — Free lift, not launch diameter, is the primary inflation parameter

**Status:** accepted · **Date:** 2026-09-14 · **Supersedes:** nothing · **Affects:** FR-1.3, FR-2.3, FR-3.2

## Context

`balloon_config` carries both `free_lift_kg` and `launch_diameter_m`, and for a given launch site
the two are not independent: fixing either one fixes the envelope volume at launch, and therefore
fixes the other. The flight dynamics need exactly one of them, and the choice decides what the rest
of the system can do.

- Free lift is what a launch crew actually sets, by filling against a spring balance until the
  neck lift reads the target figure.
- Launch diameter is what a manufacturer prints, and what a crew estimates afterwards from a
  photograph, if at all.
- Free lift is one of the four parameters the particle filter estimates from telemetry (FR-3.2),
  and one of the five the ensemble disperses over (FR-2.4).

## Decision

Envelope volume at launch is **derived from free lift** and the launch-site air density:

```
rho_air V = m_dry + rho_gas V + freeLift      =>      V = (m_dry + freeLift) / (rho_air - rho_gas)
```

`launch_diameter_m` is retained as the manufacturer's nominal inflated diameter. It is used for the
`burst_diameter_m > launch_diameter_m` rule (FR-1.3) and is reported alongside the derived
`inflatedDiameterM` so a user can see whether the free lift they asked for matches the inflation the
manufacturer assumed. It never enters the equations of motion.

## Alternatives considered

**Derive free lift from launch diameter.** Rejected. It inverts the dependency the estimator needs:
the filter would be estimating a quantity that the dynamics read only indirectly, through a
diameter the model would then have to hold fixed while the free lift moved. It also makes the
nominal catalogue figure authoritative over a quantity the crew measures directly.

**Validate that the two agree and reject configurations where they do not.** Rejected. The
manufacturer's nominal diameter is quoted at one assumed free lift and one assumed air density;
a launch from 500 m on a warm day legitimately produces a different diameter for the same free
lift. Rejecting that would reject correct configurations.

**Carry both and let the caller choose.** Rejected. Two ways to specify the same physical state is
how a model acquires a class of bugs where the two disagree and the answer depends on which code
path ran.

## Consequences

- A flight is reproducible from `free_lift_kg` plus the launch-site atmosphere alone, so the
  config hash (ADR-10) pins everything the dynamics read.
- The filter estimates a parameter the dynamics use directly, with no inversion in between.
- `launch_diameter_m` is documentation and a validation bound, not physics. The CLI prints the
  derived diameter next to it so a large disagreement is visible rather than silent.
- `BalloonConfigBuilderTest.inflationFollowsFromFreeLift` asserts the launch buoyancy identity
  `rho_air V == m_dry + m_gas + freeLift`, so the derivation cannot drift from its definition.
