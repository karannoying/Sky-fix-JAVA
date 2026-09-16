# SKYFIX — problem statement

**Course:** CSE2006 Programming in Java · VIT Bhopal University
**Author:** Kumar Karan Bohidar, Reg. No. 25BAS10049

## The problem

A high-altitude balloon recovery team plans its chase from a single pre-flight prediction. That
prediction is computed with catalogue values for free lift, drag coefficient and burst diameter —
each known only to within tens of percent — and a wind forecast that is already hours old at launch.

Three things follow, and all three cost real time in the field:

1. **The prediction is a point, with no stated uncertainty.** A team commits to a road on the basis
   of a single dot, with no way to reason about how wrong it might be.
2. **Nothing corrects it when the real ascent diverges.** If the balloon is ascending at 4.2 m/s
   rather than the assumed 5.0 m/s, every downstream number is wrong, and the only workaround is to
   re-run an online predictor by hand mid-chase — still with nominal parameters.
3. **Payload duty cycles are scheduled against a profile the balloon does not fly.** An instrument
   keyed to altitude samples in the wrong place when the ascent rate is off by a metre per second.

## The distinguishing idea

Existing predictors **assume** the flight parameters. SKYFIX **estimates** them from the flight
itself, and quantifies what it does not know.

Telemetry is replayed through a bootstrap particle filter over the four parameters that actually
matter — free lift, ascent drag coefficient, burst-diameter scale and parachute drag coefficient —
and the landing footprint is re-predicted after every update, as a confidence ellipse rather than a
point.

## Scope

**In:** a single latex sounding balloon, launch → burst → parachute descent; 1-D vertical dynamics
with horizontal advection by an interpolated wind field; US Standard Atmosphere 1976 to 86 km;
offline sounding files; recorded and synthetic telemetry replay; Monte Carlo dispersion;
particle-filter estimation; SQLite persistence; a CLI with PNG, CSV and GeoJSON export.

**Out:** live radio or serial ingest (replay only); mandatory forecast downloads; zero-pressure and
superpressure float profiles; envelope elasticity and aerodynamic lift; 3-D turbulence; terrain
elevation lookup; chase-vehicle routing; a GUI; multi-user deployment.

## Target users

| | Goal | Pain today |
|---|---|---|
| **Recovery lead** (45 km mission, ~400 km drift) | Commit to a road 40+ minutes before touchdown, and know the odds | One dot from a web predictor; no spread, and no update as the flight diverges |
| **Payload engineer** (HabSat, ~30 km) | Schedule instrument duty cycles against the profile actually flown | Timing set against a nominal ascent rate; a 1 m/s error puts sampling windows at the wrong altitudes |
| **Mission planner** | Pick a launch site whose 95% footprint avoids water, cities and restricted airspace | The footprint is argued qualitatively, with no number to put in a notification (`verify:` DGCA/AAI requirements for unmanned free balloons in India) |

## High-level features

- Offline atmosphere and flight model, every aerospace algorithm hand-written in Java.
- Sounding and telemetry ingest that reports every rejected line as `file:line:reason`.
- Monte Carlo dispersion over five parameters, aggregated into a 50% and 95% landing ellipse.
- In-flight parameter estimation from replayed telemetry, with re-prediction after every update.
- SQLite persistence: every run stores its seed, git SHA, config hash, integrator, step size and
  wall-clock, so any result can be re-materialised and compared without re-simulating it.
- A reference-case validation command that exits non-zero on any breached tolerance.

## Success criteria

The project is done when all of the following hold:

| | Criterion | Status |
|---|---|---|
| O1 | Atmosphere within 0.1% of USSA-1976 at 25 altitudes; ascent rate within 2% of analytic at 5 altitudes | **met** — 0.0107% and 0.017% |
| O2 | 1,000-member ensemble and 50/95% ellipse in ≤30 s on 4 cores | not yet — week 5 |
| O3 | Posterior median recovers ascent Cd within 5% and burst altitude within 500 m on ≥18 of 20 synthetic flights | not yet — week 8 |
| O4 | Median landing-error reduction ≥30% against the frozen pre-flight prediction | not yet — week 9 |
| O5 | Fresh clone → `mvnw test` green offline; ≥80% coverage on core packages; identical seed → identical ellipse | **partly met** — offline suite green, `core.atmos` 100% and `core.flight` 96.4%; the ellipse half waits on O2 |
| O6 | Seven diagrams, dataset description, model rationale and evaluation methodology committed | in progress |

Two measurable claims in the original design have already been checked and corrected against
measurement rather than left as estimates: the spherical-earth approximation costs 0.327% over
400 km (ADR-9), and the integration step is bounded by descent stability rather than by accuracy,
which moved the default step from 1 s to 0.25 s (ADR-13).
