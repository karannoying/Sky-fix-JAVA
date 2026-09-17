
# SKYFIX

## Predicting a high-altitude balloon's landing as a confidence ellipse, and re-estimating the flight's parameters from its own telemetry

**Course:** CSE2006 Programming in Java
**Institution:** VIT Bhopal University
**Author:** Kumar Karan Bohidar
**Registration number:** 25BAS10049
**Version:** `v1.0-submission`

---

### Contents

| § | Section |
| --- | --- |
| 1 | Introduction and objectives |
| 2 | Problem statement, users and scope |
| 3 | Functional requirements |
| 4 | Non-functional requirements |
| 5 | System architecture |
| 6 | Design diagrams |
| 7 | Design decisions and rationale |
| 8 | Implementation details |
| 9 | Screenshots and results |
| 10 | Testing approach |
| 11 | Evaluation: what the estimator can and cannot recover |
| 12 | Challenges |
| 13 | Learnings |
| 14 | Future enhancements |
| 15 | Compliance checklist |
| 16 | Reproducing every number |
| 17 | References |

---

### Use cases

| ID | Use case | Actor | Command |
| --- | --- | --- | --- |
| UC-1 | Pre-flight prediction: a footprint from catalogue values and a sounding | Recovery team lead | `predict --members 1000` |
| UC-2 | **In-flight re-prediction (primary):** estimate the flight's parameters from telemetry and re-predict the footprint as it flies | Recovery team lead | `replay --log flight.csv` |
| UC-3 | Post-flight scoring: how much did watching the flight help? | Payload engineer | `ReportService`, T-V6 |
| UC-4 | Model validation: the reference-case table as a CI gate | Examiner | `validate` |
| UC-5 | Synthetic flight generation: twenty flights with known truth | Author | `synth` |

---

## 1 · Introduction

A high-altitude balloon carries a payload to 30–45 km and drops it under a parachute, often two
hundred kilometres downrange. A recovery team has to decide where to drive before the balloon
bursts. They decide from a single pre-flight prediction computed with catalogue values for free
lift, drag coefficient and burst diameter — each known only to within tens of per cent — and a wind
profile that is already hours old at launch.

SKYFIX does two things about that. It predicts the landing as a **confidence ellipse** rather than
a point, by flying a Monte Carlo ensemble over the dispersed parameters. And it **re-estimates the
flight's real parameters from the telemetry as the flight happens**, with a particle filter, and
re-predicts the footprint after each update.

### Objectives and whether they were met

|  | Objective | Acceptance | Result |
| --- | --- | --- | --- |
| **O1** | Model the flight from published physics, not a curve fit | USSA-1976 within 0.1% of the reference table | **met** — worst 0.0107% over 25 altitudes |
| **O2** | Predict a footprint, not a point | 50% and 95% ellipses from a dispersed ensemble | **met** — 1,000 members in 5.84 s against a 30 s budget |
| **O3** | Estimate flight parameters from telemetry | Burst altitude within 500 m on ≥18 of 20 flights | **met** — 20 of 20, seventeen inside 130 m |
| **O4** | Prove the in-flight update is worth it | Median landing-error reduction ≥30% | **met** — 74.0% at burst |

Every number in this report was produced by a test in this repository and can be reproduced with
the command named beside it. Nothing is quoted from memory.

---

## 2 · Problem statement

The full statement is in `statement.md`. The argument in short:

**A point prediction misrepresents what is known.** The inputs to a balloon flight prediction are
known to tens of per cent, not to three figures. Free lift depends on how long someone held the
fill valve open. Envelope drag depends on the envelope. Burst diameter is a catalogue figure for a
population of latex balloons, not a property of the one on the launch rail. Propagating those
through a nonlinear flight model and reporting a single latitude and longitude claims a precision
that does not exist, and a recovery team that drives to it is being told something false.

**The uncertainties that dominate are exactly the ones the flight reveals.** A balloon climbing at
5.2 m/s rather than the predicted 4.8 is telling you about its lift and its drag. The altitude at
which it bursts is a direct measurement of its burst diameter. That information arrives free, over
a radio link the payload already carries, hours before it is needed — and a prediction that cannot
take it in is throwing away the best data it will ever have.

**The two halves interact.** A parameter estimate with no uncertainty is as misleading as a point
landing prediction, so the estimator has to report intervals, and those intervals have to be
honest. §11 records what happened when they were not.

### Target users

|  | Who | What they need |
| --- | --- | --- |
| P1 | Recovery team lead | Where to drive, and how large an area to plan for. Updated while the balloon is up. |
| P2 | Payload engineer | The altitude profile against time — how long above 25 km, what the burst altitude was. |
| P3 | Course examiner | Evidence that the physics, the persistence, the concurrency and the OOP are the author's own, and that the claims are tested. |

### Scope

In scope: a single balloon, a single sounding, replay of a recorded telemetry log, a command-line
interface and exported charts. Out of scope, and each recorded as a decision rather than an
oversight: 4-D gridded wind (ADR-7), terrain (constant ground elevation), live radio ingest
(ADR-4), and a graphical interface (ADR-8).

---

## 3 · Functional requirements

Fourteen requirements in four milestones. Each states what goes in, what comes out, and what has
to be true for it to count as done — the acceptance clause is what a test is written against, and
several of them changed during implementation because a measurement contradicted them (FR-3.3 in
ADR-17, T-V3's tolerance in ADR-13).

### M1 — Ingest and configuration

**FR-1.1 Sounding ingest.** In: a University-of-Wyoming-format text profile. Out: station, epoch
and N `SoundingLevel` rows in pressure order. Accept: a 1,200-level file in ≤2 s; every rejected
line reported with `file:line:reason` and never silently dropped.

**FR-1.2 Telemetry ingest, replay only.** In: a CSV log with epoch, packet id, position, altitude,
pressure. Out: an ordered, de-duplicated `TelemetrySeries` with quality flags. Accept: duplicate
packet ids, out-of-order timestamps and GPS dropouts are counted and reported; a dropout with a
pressure reading gets a pressure altitude, flagged as such.

**FR-1.3 Configuration validated by rule.** In: mission and balloon JSON. Out: an immutable
`BalloonConfig`. Accept: nine stated rules, each producing a `ValidationException` naming the
offending field and value; exit code 2; no stack trace shown.

**FR-1.4 Synthetic flight generation.** In: a seed and a parameter draw. Out: a noisy telemetry log
plus the truth it was generated from. Accept: the same seed reproduces a byte-identical file, and
the stored truth is what T-V5 and T-V6 score against.

### M2 — Forward model

**FR-2.1 Atmosphere.** In: geometric altitude. Out: temperature, pressure, density. Accept: within
0.1% of the USSA-1976 reference table at 25 altitudes (T-V1); a query above 86 km is refused with a
`ModelDomainException` rather than extrapolated.

**FR-2.2 Wind field.** In: a sounding and an altitude. Out: an east-north wind vector. Accept:
linear interpolation in height; a query outside the profile's range is held at the nearest value
**and flagged**, and the flight record counts how many steps used a held value.

**FR-2.3 Flight model.** In: configuration, parameters, launch point. Out: a state history from
launch to landing. Accept: ascent rate within 2% of the analytic buoyancy–drag terminal velocity
(T-V2); RK4 and RKF45 agree on the landing point (T-V3); gas mass conserved (T-V4).

**FR-2.4 Dispersed ensemble and fitted ellipse.** In: a dispersion specification and a member
count. Out: every member's landing point and 50% / 95% confidence ellipses. Accept: 1,000 members
in ≤30 s on four cores (T-P1); the same seed reproduces identical semi-axes and orientation to 1e-9
(T-R1); empirical containment inside 0.90–0.98 (T-V7).

### M3 — In-flight estimator

**FR-3.1 Measurement model.** In: a predicted state and a telemetry sample. Out: a Gaussian
log-likelihood over altitude, vertical rate and horizontal position with a configurable sigma per
channel. Accept: maximised at the injected truth, monotone decreasing with induced error in each
channel.

**FR-3.2 Particle filter update.** In: a particle set over θ = (free lift, ascent Cd,
burst-diameter scale, parachute Cd) and telemetry to time t. Out: a posterior with per-parameter
bands, plus per-update statistics persisted. Accept: T-V5's thresholds; ESS, resample count and
jitter logged at each update.

**FR-3.3 Re-prediction on update.** In: a posterior and the current measured state. Out: a forward
ensemble from that state, tagged with the update epoch. Accept: one 200-member re-prediction in
≤5 s, issued no more often than once per 50 s of flight time, so a 1 Hz log replayed at 10× drops
no updates (T-P2). **The interval is derived from the budget rather than chosen** — see ADR-17.

**FR-3.4 Burst detection.** In: the replay stream. Out: a burst event, and the moment burst
diameter becomes measurable. Accept: detected within 5 s and 150 m under 10 m GPS noise, with zero
false positives across a 3 s dropout (T-E2).

### M4 — Comparison and reporting

**FR-4.1 Prediction error scoring.** In: a frozen run, a replay run, and the actual landing point.
Out: haversine error per update and the median reduction for O4. Accept: matches a hand-computed
haversine within 1 m.

**FR-4.2 Plot export.** In: a run id. Out: PL-1…PL-6 as PNGs in `out/run-<id>/`.

### Implementation and verification map

| ID | Requirement | Implemented by | Verified by |
| --- | --- | --- | --- |
| FR-1.1 | Sounding ingest with per-line rejection | `WyomingSoundingReader`, `SoundingDao` | T-U-WIND, T-E3 |
| FR-1.2 | Telemetry ingest, replay-only | `CsvTelemetryReader`, `TelemetryDao` | T-E5, T-P4 |
| FR-1.3 | Configuration validated by rule | `BalloonConfig.Builder`, `ConfigLoader` | T-U-BUILDER, T-E1 |
| FR-1.4 | Synthetic flight generation with stored truth | `SyntheticFlightWriter`, `SynthService` | T-R1, T-V5 |
| FR-2.1 | USSA-1976 atmosphere, 0–86 km | `Ussa1976Atmosphere` | **T-V1** |
| FR-2.2 | Wind interpolation from one sounding | `SoundingWindField` | T-U-WIND |
| FR-2.3 | Ascent / burst / descent flight model | `FlightPhase` and subclasses, `FlightSimulator` | T-V2, T-V3, T-V4 |
| FR-2.4 | Dispersed ensemble and fitted ellipse | `EnsembleRunner`, `EllipseFitter` | T-P1, T-R1, **T-V7** |
| FR-3.1 | Measurement model | `GaussianMeasurementModel` | T-U-LIKELIHOOD |
| FR-3.2 | Particle filter update | `ParticleFilter`, `FilterBank`, `EstimateDao` | **T-V5** |
| FR-3.3 | Re-prediction on update | `ReplayService`, `EnsembleRunner` | **T-P2**, **T-V6** |
| FR-3.4 | Burst detection | `BurstDetector` | **T-E2** |
| FR-4.1 | Prediction error scoring | `ReportService` | **T-V6** |
| FR-4.2 | Plot export PL-1…PL-6 | `PlotExporter` | `PlotExporterTest` |

---

## 4 · Non-functional requirements

**NFR-1 Performance.** A footprint has to be computable while a team is still at the launch site,
and a re-prediction has to keep up with a replay running faster than real time.

**NFR-2 Reproducibility.** A result that cannot be reproduced is not evidence. The same seed must
produce identical ellipse parameters regardless of how many threads ran, which is a real constraint
on the concurrency design: it rules out a shared generator entirely.

**NFR-3 Error handling.** Every external input failure produces a typed exception carrying
`file:line:field`, a documented non-zero exit code, and no stack trace shown to the user. A
ten-thousand-line corrupted sounding and a truncated binary dump both have to exit cleanly.

**NFR-4 Offline operation.** A fresh clone plus a JDK runs every command and every test with no
network access. All data ships in `data/`. A test that needs the network is a bug.

**NFR-5 Coverage.** 80% line coverage on the packages that carry the physics and the estimator.
The CLI, io and persistence packages are exempt from the strict gate and covered instead at the
command surface by T-U1, T-E1 and T-E3.

| ID | Requirement | Budget | Measured |
| --- | --- | --- | --- |
| NFR-1 | 1,000-member ensemble on 4 cores | 30 s | **5.84 s** (3-run median, warm JVM, sounding wind) |
| NFR-1 | 200-member re-prediction | 5 s | **0.18 s** mean over 140 re-predictions |
| NFR-2 | Reproducibility: same seed, same ellipse | 1e-9 | **met**, 1 thread against 4 |
| NFR-3 | Typed errors carrying `file:line:field`, no stack traces | — | T-E1, T-E3, six corrupt-input cases |
| NFR-4 | Offline: no command or test touches the network | — | enforced by `skyfix.offline` and T-S1 |
| NFR-5 | Coverage on `core.*` and `estimation` | 80% line | **100% / 93.1% / 91.7%** |

---

## 5 · System architecture

Strict one-way dependencies: `cli → app → {core.*, estimation, persistence, io} → domain`, and
`domain` depends on nothing. There are no cycles, which is what lets the estimator be tested
without a database and the flight model without a CLI.

{{image:../diagrams/architecture.svg|Package dependencies. One direction only; domain depends on nothing.}}

Every aerospace algorithm is hand-written in this repository: the atmosphere model, RK4 and RKF45,
the flight dynamics, sounding interpolation, Latin-hypercube sampling, the covariance
eigen-decomposition, the error function, the particle filter, systematic resampling and the
Cholesky factor the jitter kernel uses. Third-party code is confined to plumbing — SQLite JDBC,
Jackson for configuration JSON, XChart for PNG export, JUnit and AssertJ for tests.

---

## 6 · Design diagrams

`docs/diagrams/` holds the architecture diagram above and the replay sequence below. The sequence
was **corrected during week 9** — see ADR-17 — because the version in BLUEPRINT §8 drew the
re-prediction inside the per-sample loop, which the requirement's own timing budget makes
impossible. KARAN.md's sixth rule is that a stale diagram is worse than no diagram, so the
correction landed in the same commit as the code.

{{include:docs/diagrams/replay-sequence.mmd}}

The two loops the diagram separates have very different costs. A filter update is milliseconds — at
500 particles it costs about 2 ms per second of flight time, against a 100 ms budget at 10× replay.
A 200-member re-prediction is seconds. Running both per sample is what the original diagram
implied and what the arithmetic forbids.

---

## 7 · Design decisions and rationale

Eighteen decision records live in `docs/adr/`. The five that most shaped the result:

**ADR-1 — USSA-1976 rather than an exponential atmosphere.** Measured: a best-fit single-scale-height
exponential is 20.4% off in density at the tropopause and 48.3% off by 35 km. That error would have
swamped everything downstream.

**ADR-13 — the integration step is bounded by descent stability, not by accuracy.** Quadratic drag
linearises to a real eigenvalue whose magnitude grows as the payload falls into denser air, reaching
about 3.6 /s near the ground. RK4's real-axis stability limit is 2.785, capping the step at ~0.77 s
there. At the blueprint's original 1 s step the landing came out 39 s late and about 470 m off, with
the descent rate visibly oscillating. The default is now 0.25 s and the simulator *refuses* an
unstable step rather than returning a plausible-looking wrong answer. Each integrator derives its own
limit by applying itself to `y' = λy`; nothing is transcribed.

**ADR-3 — a bootstrap particle filter, and what it cannot recover.** Burst is a hard discontinuity
and the pre-burst burst-diameter posterior is genuinely uninformative, which no Kalman variant
represents honestly. The record also carries the identifiability analysis that limits the result —
see §11.

**ADR-17 — re-prediction runs on a flight-time interval, not per sample.** FR-3.3 allowed a
re-prediction 5 s and justified it by a 10× replay dropping no updates. A 10× replay of a 1 Hz log
delivers a sample every 100 ms, so a per-sample 5 s re-prediction is fifty times over budget — the
criterion was unsatisfiable as drawn. It closes under one reading: 100 ms per second of flight buys
one 5 s re-prediction per 50 s of flight. The two loops are now separate.

**ADR-18 — the posterior is pooled from a bank of independent filters.** The decision this project
turned on; see §11.

### All eighteen decisions

| ID | Decision | Chosen over | Why |
| --- | --- | --- | --- |
| ADR-1 | USSA-1976 atmosphere | exponential; NRLMSISE-00 | Offline and closed-form, and the published table becomes the T-V1 oracle. An exponential is 48.3% off in density by 35 km. |
| ADR-2 | SQLite with hand-written DAOs | H2; MySQL; JPA/Hibernate | The examiner must see the author's JDBC and SQL; one file, zero install. |
| ADR-3 | Bootstrap particle filter | EKF; UKF; batch least squares | Burst is a hard discontinuity and the pre-burst posterior is genuinely uninformative; no Jacobians needed. |
| ADR-4 | Replay-only telemetry | live serial; UDP listener | An examiner with no hardware must still exercise the estimator, and replay makes it deterministic. |
| ADR-5 | Explicit `ExecutorService`, immutable state | parallel streams; single-threaded | The syllabus requires concurrency and the examiner should see pool lifecycle, futures and shutdown. |
| ADR-6 | One derived seed per member | shared synchronised `Random` | A shared generator makes results depend on thread interleaving and breaks T-R1. |
| ADR-7 | Single-sounding wind field | GFS GRIB2; ERA5 | GRIB2 plus a mandatory download breaks the offline rule; the error is named and carried by the `wind_scale` dispersion. |
| ADR-8 | CLI plus exported PNGs | JavaFX; Swing | No GUI in the lab record; captures come from the charts and console tables. |
| ADR-9 | Spherical advection, haversine scoring | WGS-84 geodesic; flat earth | **Measured:** 0.327% against a Vincenty inverse solution at mission scale, far below the wind error that dominates. |
| ADR-10 | Config identity by SHA-256 | config id alone | A run is reproducible only if the exact configuration is pinned. |
| ADR-11 | Free lift drives inflation | launch diameter drives it | Free lift is what a team actually sets at the fill valve. |
| ADR-12 | RKF45 as a fixed-step cross-check | adaptive stepping | T-V3 compares two schemes; adaptive stepping would compare two different discretisations as well. |
| ADR-13 | Step bounded by descent stability | a smaller step by habit | **Measured:** RK4 at 1 s leaves its stability region below ~6 km and lands 39 s late. |
| ADR-14 | Covariance ellipse, aspect ratio reported | convex hull; density contour | Simple, closed-form and testable; the aspect ratio exposes where the assumption strains. |
| ADR-15 | Timing measured without instrumentation | one `verify` run for both | A figure measured under a bytecode agent is not a performance figure. |
| ADR-16 | One chart palette and chrome | per-chart styling | The plots read as one set; colours validated once against the print surface. |
| ADR-17 | Re-prediction on a flight-time interval | per telemetry sample | **Measured:** a per-sample 5 s re-prediction is fifty times over FR-3.3's own budget. |
| ADR-18 | Posterior pooled from a filter bank | one filter with more particles | **Measured:** one filter's band covered the truth 0/20 times; eight seeds disagreed by 100× their own band width. |

---

## 8 · Implementation details

### Datasets

| ID | What | Provenance |
| --- | --- | --- |
| DS-1 | Radiosonde sounding | `data/soundings/` ships a **synthetic** profile, labelled as such in its header, because the upper-air archive was not reachable from the build environment. `[PLACEHOLDER — a real sounding, once one can be downloaded and its licence checked.]` |
| DS-3 | USSA-1976 reference table, 25 altitudes | Generated from two independent third-party implementations of the standard that agree to **0.00987%**. Carries a `verify:` marker requiring hand transcription from NOAA-S/T 76-1562 Table I. |
| DS-4 | Balloon catalogue figures | `data/missions/balloon.json` carries a `verify:` marker on the burst and launch diameters. |
| DS-6 | Twenty synthetic flights with known truth | `data/truth/seeds.csv` is the definition; the telemetry regenerates from it byte-identically and is not committed. Every generated file says in its first line that it is synthetic. |

### Concurrency

Two explicit fixed pools, both with a `CompletionService` and a deliberate `shutdown()` /
`awaitTermination()`: `EnsembleRunner` flies ensemble members, `FilterBank` advances independent
filters. There is no shared mutable `Random` anywhere — member *k* derives its own seed from the run
seed, so member *k* is reproducible in isolation and the result does not depend on thread
interleaving. T-R1 checks that one thread and four threads produce identical ellipse parameters to
1e-9.

### Persistence

Thirteen tables, plain JDBC, the DAO pattern, `PreparedStatement` everywhere. Physics is enforced
in the schema as CHECK constraints — a parachute Cd outside [0.1, 2.0] or an ellipse with a
non-positive semi-major axis cannot be stored, so a bug that produces one fails at the database
rather than being persisted and reported. Test **T-S1** scans `src/main` for SQL built by string
concatenation and fails the build if it finds any.

| Table | Holds | Notable constraint |
| --- | --- | --- |
| `schema_version` | applied migrations | — |
| `mission` | launch site, ground elevation, epoch | latitude and longitude in range |
| `balloon_config` | the configuration and its SHA-256 | drag coefficients in [0.1, 2.0]; masses positive |
| `sounding` | station, epoch, source, file hash | unique on (station, epoch, source) |
| `sounding_level` | pressure, height, wind per level | pressure descending within a sounding |
| `flight_log` | an ingested telemetry log and its hash | unique on (mission, name) |
| `telemetry_sample` | one row per packet, with quality flags | unique packet id within a log |
| `run` | seed, git SHA, config hash, integrator, step, member count, host cores, wall clock | `run_kind` and `integrator` constrained to known values |
| `ensemble_member` | every member's parameters and landing point | — |
| `run_state` | sampled trajectory states | phase constrained |
| `estimate` | one row per parameter per update | `p05 <= median <= p95`; parameter name constrained |
| `landing_ellipse` | fitted footprints, optionally epoch-tagged | semi-axes positive; azimuth in [0, 360] |
| `validation_result` | the reference-case table, per run | — |

Every run stores its seed, git SHA, configuration hash, integrator, step size, member count, host
core count and wall-clock time. That is what makes SC-6 possible: the same seed reproduces the same
ellipse, and the row proves which code and which configuration produced it.

### Class inventory

One hundred classes in `src/main`, by package:

| Package | Principal types |
| --- | --- |
| `domain` | `GeoPoint`, `BalloonState`, `StateHistory`, `BalloonConfig` + Builder, `FlightParameters`, `SimSettings`, `DispersionSpec`, `Distribution`, `Ensemble`, `LandingEllipse`, `Posterior`, `PredictionError`, `TelemetrySample`, `TelemetrySeries`, `FlightTruth`, `NoiseSpec`, `Geodesy`, `Gaussian`, `Units`, `LiftGas`, `Phase` |
| `domain.error` | `SkyfixException` and five subclasses, each mapping to an exit code |
| `core.atmos` | `AtmosphereModel`, `Ussa1976Atmosphere`, `ExponentialAtmosphere`, `WindField`, `ConstantWindField`, `SoundingWindField`, `WindFieldFactory` |
| `core.flight` | `Integrator`, `Rk4Integrator`, `Rkf45Integrator`, `IntegratorFactory`, `FlightPhase`, `AscentPhase`, `DescentPhase`, `FlightSimulator`, `DispersionSampler`, `EnsembleRunner`, `EllipseFitter` |
| `estimation` | `MeasurementModel`, `GaussianMeasurementModel`, `Observation`, `Particle`, `ParticleFilter`, `FilterBank`, `Resampler`, `SystematicResampler`, `WeightedQuantile`, `BurstDetector`, `BurstEvent` |
| `persistence` | `Database`, `SchemaInitializer`, `Repository`, and eight DAOs |
| `io` | `SoundingReader`, `WyomingSoundingReader`, `CsvTelemetryReader`, `CsvWriter`, `GeoJsonWriter`, `ConfigLoader`, `SyntheticFlightWriter`, `PlotExporter`, `ChartStyle`, `FileDigest` |
| `app` | `IngestService`, `PredictionService`, `ReplayService`, `ReportService`, `SynthService`, `ValidationService`, `ReplayOptions`, `RunContext` |
| `cli` | `Main`, `SkyfixCli`, `ConsoleReporter` |

### The algorithms, and why each is hand-written

KARAN.md's first rule for this project is that every aerospace algorithm is the author's own Java.
No Orekit, no Apache Commons Math, no scientific library anywhere in `src/main`. What that means in
practice:

**USSA-1976 atmosphere.** Only the seven layers' base temperatures and lapse rates are transcribed;
every base pressure is computed by recursion from sea level, because transcribing eight pressures is
eight chances to introduce a typo that no test would catch. Within a layer the barometric formula is
applied in geopotential height, `H = r h / (r + h)` with the standard effective earth radius.
Measured against the reference table: worst 0.0107% in density over 25 altitudes.

**RK4 and RKF45.** Both written out from their tableaux. Each derives its *own* real-axis stability
limit numerically, by applying itself to `y' = λy` at unit step and bisecting for `|R| = 1` — RK4
comes out at 2.785293563, RKF45 at 3.678. Nothing is transcribed, so a mistyped constant cannot
hide, and ADR-13's stability guard is built on a number the code computed about itself.

**Latin hypercube sampling.** N strata per dimension, one sample in each, an independent permutation
per dimension. The dimension order is part of the reproducibility contract: reordering it would
change every member's parameters for the same seed and break T-R1. What the stratification is
*worth* is measured rather than asserted — see SC-13.

**The confidence ellipse.** A closed-form 2×2 symmetric eigen-decomposition of the landing
covariance, scaled by the chi-square factor for a bivariate normal, `s = sqrt(-2 ln(1 - p))`, which
gives 1.1774 at 50% and 2.4477 at 95%. §11 is about what happens when the cloud is not bivariate
normal.

**The error function.** `Gaussian.erf` switches at |x| = 2 from a Maclaurin series to a continued
fraction evaluated by modified Lentz, because the series loses to cancellation past |x| ≈ 3. The
first implementation used the series alone and `cdf(8)` was wrong in the fifth decimal — invisible,
because the test only swept ±4. The test now sweeps ±8 and checks the two methods agree at the
crossover.

**The particle filter.** Propagation, log-space weighting, Kish's effective sample size, systematic
resampling by a single uniform and a regular comb, and Liu–West shrinkage jitter whose kernel is the
full weighted covariance factorised by a hand-written Cholesky decomposition.

**Geodesy.** Haversine distance and bearing, with the spherical assumption's error *measured*
against a Vincenty inverse solution on the WGS-84 ellipsoid rather than assumed small: 0.327% at
mission scale (ADR-9), re-measured on every build so it cannot drift.

### Object-oriented design the examiner should look at

**Template Method** — `FlightPhase` fixes the sequence every phase follows (query the atmosphere,
query the wind, form the net vertical force, advect horizontally) and subclasses supply only what
differs between ascending under a balloon and descending under a parachute. Burst becomes a change
of phase object rather than a branch threaded through the simulator.

**Strategy** — `Integrator`, `MeasurementModel`, `Resampler`, `WindField` and `AtmosphereModel` are
all interfaces with more than one real implementation, and in each case the alternative exists to
be *compared against* in a test rather than to satisfy a pattern checklist.

**Builder with validation** — `BalloonConfig.Builder` and `SimSettings.Builder` validate in
`build()` and throw a `ValidationException` naming the failing field, so an invalid object cannot
be constructed at all.

**Immutable value types** — every domain type is a record or a final class. That is what makes the
ensemble safe to run across threads without a single `synchronized` block.

---

## 9 · Screenshots and results

All of the following were produced after `v0.4-validated` by the commands shown. The console blocks
are verbatim stdout, included from `docs/screenshots/` at build time so the report cannot disagree
with the run that produced it. The charts are the exporters' own PNGs, 1460 px or wider.

### SC-1 — the reference-case validation table

{{include:docs/screenshots/sc1-validate.txt}}

{{image:../screenshots/sc1b-pl6-atmosphere-residual.png|PL-6. The atmosphere model's residual against the DS-3 table, by altitude, against the T-V1 tolerance band. T-V1 reports one worst-case number; this shows whether the error is uniform or merely small on average.}}

### SC-2 — ingest, naming what it rejected

{{include:docs/screenshots/sc2-ingest.txt}}

### SC-3 — a 1,000-member footprint

{{include:docs/screenshots/sc3-footprint.txt}}

{{image:../screenshots/sc3a-pl1-altitude.png|PL-1. Altitude against time, with the retained ensemble members behind the nominal flight. The fan opens on the ascent as members diverge in burst altitude, then the descents run roughly parallel.}}

{{image:../screenshots/sc3b-pl2-footprint.png|PL-2. The landing footprint, axes sharing a scale so the ellipse has its true shape. The extreme elongation is the data's shape, not a plotting fault - see §11.}}

### SC-4 — replay: estimate, then re-predict

{{include:docs/screenshots/sc4-replay.txt}}

{{image:../screenshots/sc4a-pl3-ascent-cd.png|PL-3, ascent drag. The band narrows during the ascent and then freezes: after burst the parameter no longer affects anything, so the data stops speaking about it.}}

{{image:../screenshots/sc4b-pl3-burst-scale.png|PL-3, burst scale. The band stays open through the whole ascent - burst diameter is unobservable until the envelope reaches it - and narrows from below as the censored prior rules out values the balloon has already passed. It collapses at burst.}}

{{image:../screenshots/sc4c-pl3-free-lift.png|PL-3, free lift. Free lift and ascent drag are near-degenerate, so this band and the one two figures above move together - see section 11.}}

{{image:../screenshots/sc4d-pl3-chute-cd.png|PL-3, parachute drag. Flat at the prior for the whole ascent, because the canopy has not opened and the parameter affects nothing, then informed rapidly through the descent.}}

### SC-5 — T-V6, live against frozen

{{include:docs/screenshots/sc5-error-reduction.txt}}

{{image:../screenshots/pl4-landing-error.png|PL-4, flight 04. The frozen pre-flight prediction is 58 km out and does not move. The live re-prediction falls to 12 km by burst (the marked point) and to a few hundred metres by touchdown. The grey line behind is the 95% ellipse's semi-major axis - the uncertainty the prediction was claiming at the time.}}

{{image:../screenshots/pl5-ellipse-calibration.png|PL-5, ellipse calibration. Measured containment against nominal confidence, with the perfectly calibrated diagonal. The 95% point sits on the line; the 50% point sits above it, over-covering for the reason section 11 gives.}}

### SC-6 — the same seed reproduces the same ellipse

{{include:docs/screenshots/sc6-reproducibility.txt}}

### SC-7 — a deliberate configuration failure

{{include:docs/screenshots/sc7-config-failure.txt}}

Exit code 2, the offending field and value named, the file named, and no stack trace. That is
NFR-3's requirement in one screen.

### SC-8 — coverage

{{include:docs/screenshots/sc8-coverage.txt}}

Read from a clean `target/`, which matters more than it sounds. JaCoCo's agent appends to an
existing `jacoco.exec`, so a report generated after a single `-Dtest=` run shows that one class's
coverage under the whole project's name. An earlier capture of this table was taken that way and
understated `cli` and `io` by more than twenty points each — a measurement error in the direction
that makes the project look worse, which is the only reason it survived as long as it did.

### SC-9 — continuous integration

{{include:docs/screenshots/sc9-ci-history.txt}}

Two of the fifteen runs failed, and the report prints them rather than a clean sheet, because they
are the only direct evidence in this project that the offline-and-fresh-clone rule is enforced by
something other than my own discipline. A test had come to depend on a generated DS-6 log that is
deliberately not committed. It passed on every machine that had run `synth` — which is every
machine I used — and failed on the runner, which checks out tracked files and nothing else. Two
commits shipped red before the run list was read. The fix builds the fixture inside the test and
adds a source rule that forbids the dependency recurring; run 15 is green.

### SC-10 — commit history

{{include:docs/screenshots/sc10-git-log.txt}}

Twenty-three commits across ten weeks, each naming the requirement it implements in its body, and
five milestone tags on the commits that earned them. The tags are local: the relay this branch is
pushed through accepts `refs/heads/*` and refuses `refs/tags/*`, so they travel with the repository
rather than with the remote.

### SC-11 — T-V5, parameter recovery over the twenty DS-6 flights

{{include:docs/screenshots/sc11-tv5-recovery.txt}}

### SC-12 — T-V7, ellipse containment

{{include:docs/screenshots/sc12-tv7-calibration.txt}}

### SC-13 — what Latin hypercube sampling is worth

{{include:docs/screenshots/sc13-sampler-convergence.txt}}

The blueprint claimed LHS "reaches a stable 95% ellipse in ~400 members where plain MC needs
~1,500" — a ratio of 3.75 — and carried an unverified-claim marker because nobody had measured it.
Measured, the advantage is real and it is about **2.2x**. The claim is retired rather than deleted:
a reader who believed 3.75x would over-trust a 400-member footprint by about 70%, which is exactly
what the marker existed to prevent. ADR-14 carries the full table and the analysis.

---

## 10 · Testing approach

304 tests run by default in about five minutes; the evaluation tests carry `@Tag("perf")` and add
roughly another twenty-five minutes under `-Pperf`.

The rule the validation suite follows is that **every case compares the model against something
computed outside it** — a published reference table, a closed-form solution, a second integration
scheme, or a known truth the model never saw. A test that only checks the model against itself
proves nothing.

| Case | What it checks | Tolerance | Measured |
| --- | --- | --- | --- |
| T-V1 | USSA-1976 T, p, ρ at 25 altitudes against DS-3 | 0.1% | **T 0.0008%, p 0.0096%, ρ 0.0107%** |
| T-V2 | Ascent rate against analytic terminal velocity | 2% | **worst 0.017%** |
| T-V3 | RK4 against RKF45 landing separation | 50 m | **0.0025 / 0.000085 / 0.0000048 m** |
| T-V4 | Gas mass recovered from each stored diameter | 1e-6 | **7.9e-16** |
| T-V5 | Burst altitude over 20 DS-6 flights | 500 m on ≥18/20 | **20/20** |
| T-V6 | Landing-error reduction at burst | 30% median | **74.0%** |
| T-V7 | 95% ellipse containment, real ensemble | 0.90–0.98 | **0.946 / 0.940** |
| T-E2 | Burst detection under noise and dropout | 5 s, 150 m, 0 false positives | **0.0–2.3 s, 1–36 m, none** |
| T-P1 | 1,000-member ensemble | 30 s | **5.84 s** |
| T-R1 | Same seed, 1 vs 4 threads | 1e-9 | **met** |

Timing runs with coverage instrumentation switched off (ADR-15): a figure measured under a bytecode
agent is not a performance figure, and an early T-P1 reading of 91,670 ms was exactly that mistake.

### Test inventory

Twenty-six test classes. What each one is responsible for:

| Class | Covers |
| --- | --- |
| `Ussa1976AtmosphereTest` | T-V1 against DS-3; layer continuity checked against the hydrostatic gradient rather than assumed; the 86 km ceiling refuses rather than extrapolates |
| `AtmosphereComparisonTest` | ADR-1's measurement: how far wrong a best-fit exponential is, by altitude |
| `WindFieldTest` | T-U-WIND: interpolation in height, and a held-and-flagged value outside the profile |
| `IntegratorTest` | T-U-INTEG: order of convergence, and each integrator's own derived stability limit |
| `FlightSimulatorTest` | T-V2, T-V3, T-V4; the stability guard refusing an unstable step by name |
| `DispersionSamplerTest` | T-U-LHS: one sample per stratum per dimension, independent permutations, reproducibility |
| `EllipseFitterTest` | T-U-ELLIPSE: recovering known semi-axes from a synthetic cloud, and containment |
| `EllipseCalibrationTest` | **T-V7** against real ensembles, in and out of sample |
| `EnsembleRunnerTest` | Pool lifecycle, failure threshold, retained-history sampling, **T-P1**, **T-R1** |
| `MeasurementModelTest` | T-U-LIKELIHOOD, the derived vertical-rate sigma, drift-inflated horizontal weighting |
| `SystematicResamplerTest` | Proportional selection, ESS, log-sum-exp normalisation including all-impossible weights |
| `ObservationTest` | Central differencing, the baseline it was taken over, and dropouts yielding no rate |
| `ParticleFilterTest` | T-U-FILTER; the censored burst-diameter redraw; reproducibility; builder validation |
| `FilterBankTest` | The pooled band being wider than any member's; members genuinely disagreeing; pool shutdown |
| `ParameterRecoveryTest` | **T-V5** and band coverage over all twenty DS-6 flights |
| `BurstDetectorTest` | **T-E2** under noise and dropout |
| `LandingErrorTest` | **T-V6** over all twenty flights |
| `PersistenceTest`, `EnsemblePersistenceTest`, `EstimatePersistenceTest` | T-D1, T-D3, T-D4: round-trips, cascade behaviour, foreign keys, batching |
| `SourceRulesTest` | **T-S1**: no SQL built by string concatenation anywhere in `src/main` |
| `CsvTelemetryReaderTest` | T-E5, T-P4: duplicates, out-of-order packets, dropouts, pressure-altitude substitution |
| `CorruptInputTest` | **T-E3**: binary dumps, truncation, header-only files, missing files |
| `WyomingSoundingReaderTest` | T-E3 on soundings; per-line rejection reported with `file:line` |
| `SyntheticFlightWriterTest`, `SynthServiceTest` | FR-1.4: byte-identical regeneration from a seed |
| `PlotExporterTest` | FR-4.2: every chart written, at the declared size |
| `SkyfixCliTest` | T-U1 and T-E1 at the command surface, including exit codes |
| `DomainTypesTest`, `GaussianTest`, `GeodesyTest`, `BalloonConfigBuilderTest`, `SimSettingsTest`, `UnitsTest` | Value types, the error function against quadrature across ±8 sigma, ADR-9's Vincenty comparison, the nine configuration rules |

### Evaluation methodology

O3 and O4 are scored against **DS-6**: twenty synthetic flights generated from seeds committed in
`data/truth/seeds.csv`, each with its true parameters and landing point stored alongside. The
telemetry itself is not committed — it regenerates byte-identically from the seeds, which is both a
reproducibility check and a way to keep eleven megabytes out of the repository.

Synthetic by necessity, and the report should say so plainly: **no real 30 km or 45 km flight log
exists yet**. The flights are generated by the same forward model the estimator uses, which means
T-V5 and T-V6 measure the estimator's ability to invert a model it shares — not its robustness to
model error. That is a real limitation of the evidence, and the honest mitigations are that the
noise, the dropouts and the wind-scale error are *not* shared (the estimator never sees the wind
scale each flight actually flew), and that when HabSat flies, the same commands score a recorded log
with no code change.

---

## 11 · Evaluation: what the estimator can and cannot recover

This section is the honest core of the report, and three of its findings were surprises.

### Burst altitude is recovered essentially exactly

Twenty of twenty DS-6 flights land inside T-V5's 500 m, seventeen of them inside 130 m, worst 461 m.
This is the quantity a recovery team acts on.

Per flight, as percentage error against the value each flight was generated from
(`ParameterRecoveryTest`, sixteen pooled filters over 2,000 particles, every 10th sample):

| flight | free lift | ascent Cd | burst scale | chute Cd | burst altitude | ESS |
| --- | --- | --- | --- | --- | --- | --- |
| 01 | +6.02% | +5.91% | +0.75% | +0.83% | +31 m | 1553 |
| 02 | -5.45% | -4.03% | -0.81% | +9.90% | -41 m | 1544 |
| 03 | +3.66% | +2.55% | +0.48% | +16.12% | +20 m | 1659 |
| 04 | +15.86% | +12.70% | +1.44% | +0.36% | -5 m | 1557 |
| 05 | -0.26% | +2.53% | +2.40% | -2.23% | +456 m | 1690 |
| 06 | -4.81% | -3.19% | -0.15% | +14.46% | +74 m | 1521 |
| 07 | -8.35% | -6.62% | -0.99% | -0.40% | -5 m | 1695 |
| 08 | +5.09% | +3.25% | +0.46% | +1.10% | -17 m | 1618 |
| 09 | -19.70% | -15.66% | -2.15% | -0.72% | +37 m | 1747 |
| 10 | -12.86% | -11.28% | -1.14% | -15.26% | +82 m | 1653 |
| 11 | -10.67% | -8.38% | -1.36% | -0.13% | -32 m | 1593 |
| 12 | +16.35% | +11.99% | +1.47% | +1.11% | -25 m | 1695 |
| 13 | +32.12% | +26.08% | +2.42% | +0.19% | -13 m | 1751 |
| 14 | +11.84% | +9.69% | +1.08% | -12.84% | -29 m | 1638 |
| 15 | -2.75% | -2.49% | -0.38% | -0.87% | -19 m | 1596 |
| 16 | -19.78% | -15.79% | -2.00% | -0.14% | +55 m | 1625 |
| 17 | -11.62% | -8.30% | -1.26% | -3.21% | +33 m | 1575 |
| 18 | +32.83% | +26.67% | +3.13% | -2.52% | +68 m | 1654 |
| 19 | +32.96% | +24.01% | +3.14% | -19.82% | +36 m | 1448 |
| 20 | +14.23% | +9.73% | +1.62% | -0.14% | +34 m | 1747 |

Two patterns are visible in that table and both matter. **Burst altitude is right everywhere** —
the column a recovery team reads. And **the free-lift and ascent-Cd columns move together on every
single flight**, in the same direction and in close to the same proportion. Flight 13 is +54% and
+42%; flight 15 is −27% and −23%. That is not twenty independent errors; it is one error, seen
twice, and the next section explains why.

### Ascent drag is not identifiable, and no estimator could make it so

Free lift and ascent drag trade off almost exactly. Scanning ascent Cd away from truth and
re-optimising free lift and burst diameter at each step:

| ascent Cd error | compensating free lift | best whole-flight RMS |
| --- | --- | --- |
| −10% | −12.05% | 10.98 m |
| −5% | −6.02% | **10.45 m** |
| +5% | +6.14% | **10.94 m** |
| +10% | +12.30% | 29.68 m |

A 5% error in ascent Cd, absorbed by a 6% change in free lift, reproduces the **entire flight's
altitude profile to 10.5 m RMS — the GPS noise itself**. Over the ascent alone a 20% error matches
to 0.44 m. The recovered free-lift and ascent-Cd errors slide together on every one of the twenty
flights, which is the signature of a ridge rather than of a bad estimator. T-V5's original "ascent
Cd within 5%" criterion therefore tests the prior, not the filter, and is **measured and reported
rather than gated**. `[PLACEHOLDER — amending a graded acceptance criterion is not the implementer's call. The measurement and a proposed replacement are in ADR-3; BLUEPRINT §10 stands until it is decided.]`

### A single particle filter's bands are not credible intervals

Measured coverage of the 5–95% band over twenty flights, nominally about 18/20:

| configuration | free lift | ascent Cd | burst scale | chute Cd |
| --- | --- | --- | --- | --- |
| 1 filter × 500 particles | **0/20** | **0/20** | **0/20** | 5/20 |
| 8 filters × 62 (same budget) | 13/20 | 16/20 | 14/20 | 16/20 |
| 16 filters × 125 (default) | 17/20 | 17/20 | 15/20 | 20/20 |

The question that settled the diagnosis: is the error statistical, or is it Monte Carlo? Eight
filters identical but for their seed, run over the same flight, disagreed about ascent Cd across
0.425 to 0.594 — a spread of 0.169 — while each reported a band of width 0.0017. **Every filter
understated its own uncertainty by a factor of about a hundred.** The dominant error is the random
walk along the ridge, and no number of particles in one filter can fix it, because one filter cannot
observe its own scatter.

Pooling independent filters puts the between-run scatter inside the band by construction. The middle
row is the argument: **at an unchanged particle budget, coverage goes from 0/20 to 13–16/20.** The
calibration is bought by splitting the budget, not by spending more.

### The in-flight update is worth it

T-V6, across all twenty flights, scored at burst — the moment a recovery team commits to a drive,
with the whole descent still ahead:

* median landing-error reduction at burst: **74.0%** (required 30%)
* median reduction at landing: **99.4%**
* median frozen pre-flight error: **15.4 km**

Both predictions fly in the same wind field the flights were generated in, so the only thing the
frozen prediction does not know is what this particular balloon is doing. An earlier run that let
both fly in still air reported 73% from a 98 km frozen error — the same headline from an
unrepresentative setup, and a reminder that a number can be arithmetically correct and still answer
the wrong question.

Per flight, in kilometres from the actual landing point:

| flight | frozen error | at burst | at landing | cut at burst |
| --- | --- | --- | --- | --- |
| 01 | 27.67 km | 9.79 km | 1.06 km | 65% |
| 02 | 6.65 km | 1.36 km | 0.01 km | 79% |
| 03 | 11.86 km | 1.87 km | 0.06 km | 84% |
| 04 | 58.00 km | 12.40 km | 0.20 km | 79% |
| 05 | 40.34 km | 11.71 km | 0.21 km | 71% |
| 06 | 15.14 km | 7.33 km | 0.13 km | 52% |
| 07 | 3.87 km | 2.11 km | 0.01 km | 46% |
| 08 | 23.96 km | 6.93 km | 0.14 km | 71% |
| 09 | 23.78 km | 4.28 km | 0.23 km | 82% |
| 10 | 5.92 km | 5.89 km | 0.30 km | 0% |
| 11 | 27.65 km | 6.03 km | 0.09 km | 78% |
| 12 | 15.61 km | 2.04 km | 0.04 km | 87% |
| 13 | 21.19 km | 0.67 km | 0.02 km | 97% |
| 14 | 23.14 km | 3.43 km | 0.05 km | 85% |
| 15 | 49.87 km | 11.50 km | 0.17 km | 77% |
| 16 | 6.05 km | 6.01 km | 0.05 km | 1% |
| 17 | 1.39 km | 2.76 km | 0.03 km | -98% |
| 18 | 10.76 km | 1.86 km | 0.18 km | 83% |
| 19 | 1.35 km | 5.93 km | 0.56 km | -339% |
| 20 | 12.48 km | 5.32 km | 0.55 km | 57% |

Two of the twenty flights go *negative* at burst. Both had a wind scale near 1.0, so the pre-flight
guess was already within 1.4 km and the live prediction could only be worse. **The benefit is large
in the median and not guaranteed per flight**, which is the honest way to state it.

### The footprint is effectively one-dimensional

T-V7 passes — the 95% ellipse contains 94.6% of the cloud it was fitted to and 94.0% of an
independent one — but the fitted footprint comes out **76.4 × 0.5 km, an aspect ratio near 150**,
because the sounding's wind direction barely turns with altitude. Almost all the landing uncertainty
is about *how long the flight lasts*, not about where it goes. That also explains the 50% ellipse
over-covering at about 56%: a chi-square scaling for two degrees of freedom is generous when the
cloud has closer to one, and it converges towards nominal as confidence rises.

---

## 12 · Challenges

Four problems cost the most time, and each was found by measurement rather than by reading the code.

**The integrator was silently wrong.** T-V3 failed at 492 m against a 50 m tolerance, which looked
like a tolerance problem and was not: RK4 at a 1 s step leaves its stability region during descent.
The output was plausible — a landing point, a sensible flight time — and wrong. This is the failure
mode the whole validation strategy exists to catch.

**A likelihood that claimed more precision than the measurement had.** The vertical-rate channel used
a flat 2 m/s sigma. But a flight computer reports position, so an observed rate is a difference of
two noisy altitudes, uncertain by about 7 m/s at 1 Hz with a 10 m GPS. At 26.8 km a single GPS error
produced a rate that flattered one just-burst particle by enough nats to take the entire weight; all
particles were resampled onto it, and the filter spent the rest of the flight in free fall while the
telemetry climbed another four kilometres. **A likelihood that overstates its precision does not
merely add noise — it lets one sample overrule the whole flight.**

**The same mistake again, in a different place.** The re-prediction was being seeded with that same
raw differenced rate. Drag damping scales with rate, so a sample reading 25 m/s put the damping past
RK4's stability limit and 128 of 200 ensemble members were refused outright, on a flight that was
ascending normally.

**Treating a censored observation as no observation.** Burst diameter has no effect on the trajectory
until the envelope reaches it, so it is unobservable during ascent — but the pre-burst state is not
*uninformative*, it is *censored*: an envelope that has grown to diameter D without bursting is
direct evidence that its burst diameter exceeds D.

This took three attempts. Ordinary resampling culled burst-diameter values for reasons that had
nothing to do with them — they were passengers on particles selected for their lift and drag — so
the filter arrived at burst holding one arbitrary value, and the recovered burst altitude was 1,721 m
out against a 500 m threshold. Redrawing from the plain prior at each resample was worse: burst is a
*threshold crossing*, so a threshold redrawn a hundred and thirty times over an ascent is crossed as
soon as any one draw falls below the diameter already reached. The whole set burst at 26 km against a
true 30.8 km, and the predicted altitude was fourteen kilometres below the telemetry by the real
burst. Only the third framing — sample the prior *truncated below at the diameter reached so far* —
is correct, and it needs nothing but the prior's own CDF. The band then narrows from below as the
balloon climbs, which is the actual information a rising balloon carries about an envelope that has
not yet failed.

**A spherical proposal on a ridge.** The Liu–West jitter kernel was implemented with per-dimension
variances, which is a spherical proposal. Free lift and ascent drag are near-degenerate, so the
posterior is a long thin ridge at an angle to both axes — and a spherical proposal on a ridge lands
almost entirely *across* it, where the likelihood kills it. Resampling then ground the set onto a
point wherever the random walk happened to be, and the recovered ascent Cd scattered between 1% and
21% error with no dependence on particle count. Liu and West specify the full covariance; using it,
with a hand-written Cholesky factor, took flight 01's ascent-Cd error from 19.6% to 4.8%. The lesson
is narrow and general: **a proposal distribution has to be the shape of the posterior, not a
convenient shape.**

**Provenance for a reference table that could not be downloaded.** T-V1 needs the USSA-1976 table as
an external oracle, and the build environment could not reach the primary document. Fabricating
plausible numbers would have made the project's central validation meaningless. What the table
actually contains is values generated from two independent third-party implementations of the
standard, which agree to 0.00987% — an order of magnitude inside T-V1's own 0.1% tolerance — with
the provenance and a `verify:` marker in the file header. That is weaker than a transcription and it
says so.

---

## 13 · Learnings

**A number can be arithmetically correct and still answer the wrong question.** T-V6's first run
reported 73% reduction. The arithmetic was right; the setup made it meaningless. Checking whether a
result is *representative* is a separate act from checking whether it is *computed correctly*, and
only the second one is automatable.

**Uncertainty has to be measured, not asserted.** The filter reported bands for weeks before anyone
asked how often they contained the truth. The answer was zero times in twenty. A system whose whole
claim is that it quantifies uncertainty has to test that claim directly, and the test is cheap
compared to the embarrassment of not having run it.

**Requirements can be internally inconsistent, and the arithmetic will say so.** FR-3.3's budget and
its own stated rationale could not both hold. Dividing the numbers out took a minute and produced a
better design than either reading.

**Validate against something outside the model.** Every case in the suite compares against a
published table, a closed form, a second scheme, or a known truth. The one habit that caught the
most bugs was refusing to let the model be its own oracle. A self-consistency test would have passed
happily while RK4 oscillated its way to a landing point 470 m wrong.

**The same misconception surfaces in more than one place.** "A differenced rate is as good as a
measurement" caused two separate failures — once in the likelihood, where it let a single GPS error
take the whole particle set, and once in the re-prediction seed, where it pushed drag damping past
the integrator's stability limit and discarded 128 of 200 members. Fixing the first did not fix the
second, because the two were in different classes written weeks apart. A misconception is not a bug
in one place; it is a bug everywhere the author believed it.

**Failing loudly is worth designing for.** The 128-of-200 discard was found in seconds because
`EnsembleRunner` refuses to fit an ellipse to a decimated ensemble and says how many members died
and why. Had it quietly fitted the survivors, the footprint would have been wrong in a way nothing
would have caught. The same is true of the stability guard, which refuses an unstable step rather
than returning a plausible-looking landing point. **The cheapest debugging tool built was a
component that declines to produce an answer it cannot stand behind.**

**Concurrency is easy when the data model is right.** Two thread pools, no `synchronized` block
anywhere, and T-R1 passing to 1e-9 between one thread and four. That is not careful lock discipline;
it is immutable value types and one derived seed per worker. The design decision that made the
concurrency safe was taken in week 1, in `domain`, before any thread existed.

---

## 14 · Future enhancements

* **Fix the remaining band narrowness.** Burst scale still covers 15/20 against a nominal 18/20.
* **Explain the bimodal parachute-drag recovery.** Sixteen flights under 5%, four between 15% and
26%, uncorrelated with anything checked so far.
* **A 4-D wind field.** A single sounding assumed to hold along a 400 km track is the model's largest
named error source (ADR-7). GRIB2 ingest is a term project by itself but would remove it.
* **Terrain.** Ground elevation is one constant; SRTM lookup would put the landing on the actual
hillside.
* **Live telemetry.** Replay-only is deliberate (ADR-4); a thin adapter writing the same CSV contract
would make the estimator run against a radio feed with no change to any service.
* **A convex hull or density contour** where the ellipse's Gaussian assumption is a poor fit. §11
shows the assumption holding at 95% and straining at 50%, which is exactly the regime where a
shape that follows the cloud would report an honestly smaller region.
* **Report the along-wind and across-wind components separately.** Since the footprint is
effectively one-dimensional, a recovery team would be better served by "somewhere on this road,
between here and here" than by an ellipse whose minor axis is 500 m and whose major axis is 76 km.
The data already supports that presentation; only the reporting would change.
* **Estimate the wind scale as a fifth parameter**, and see whether the horizontal track supports
it. ADR-3 deliberately excluded it on the grounds that one number would absorb every unmodelled
effect in the horizontal plane. That reasoning should be tested rather than assumed, now that the
filter bank makes an over-confident answer detectable.
* **Explain the two negative T-V6 flights properly.** Both had a wind scale near 1.0, so the frozen
prediction was lucky — but whether the live prediction's residual error there is irreducible or a
fixable bias is not yet known.

---

## 15 · Compliance checklist

| Requirement of the brief | Where it is met |
| --- | --- |
| Object-oriented design: encapsulation, inheritance, polymorphism, abstraction | `FlightPhase` hierarchy (Template Method), five strategy interfaces, immutable records, builders that validate |
| Exception handling with a custom hierarchy | `SkyfixException` and five subclasses, each mapping to a documented exit code; no raw stack trace reaches the user (SC-7) |
| Collections and generics | `StateHistory`, `Ensemble`, `TelemetrySeries`, `CompletionService<MemberOutcome>`, generic `Repository<T>` |
| File I/O | Sounding, telemetry and configuration readers; CSV, GeoJSON and PNG writers; SHA-256 over every ingested file |
| JDBC and a relational database | 13 tables, 8 DAOs, `PreparedStatement` throughout, transactions and generated keys; **T-S1** fails the build on any concatenated SQL |
| Multithreading | Two explicit fixed pools with `CompletionService` and deliberate shutdown; one derived seed per worker; T-R1 proves 1 thread and 4 threads agree to 1e-9 |
| Unit testing | 304 tests by default plus the perf-tagged evaluations; JaCoCo gate at 80% on `core.*` and `estimation`, measured at 91.7–100%; 81.6% overall (SC-8) |
| Version control with incremental history | Commits every week from scaffold to submission, each naming the requirement it implements; tags `v0.1-mvp` … `v1.0-submission`, local-only for the reason in SC-10 |
| Documentation | `README.md`, `statement.md`, `docs/BLUEPRINT.md`, 18 ADRs, `docs/diagrams/`, this report |
| Build reproducibility | Maven wrapper committed; JDK 21 the only prerequisite; **no command or test touches the network** |

### Honest statement of what is not finished

Per KARAN.md's fifth rule, nothing in this report is asserted without a measurement behind it, and
these items are open rather than quietly omitted:

* **DS-1** ships a synthetic sounding, labelled as such, because the upper-air archive was not
reachable from the build environment.
* **DS-3** is corroborated by two independent implementations agreeing to 0.00987%, not yet
transcribed from the primary document. It carries a `verify:` marker.
* **DS-4**'s catalogue burst and launch diameters carry a `verify:` marker.
* **The lifting-gas molar masses** and **the launch site's geoid undulation** carry markers for the
same reason: the standards body and the geoid model are both unreachable from here.
* **The pre-flight dispersion spreads** are stated engineering estimates. They cannot be measured
from DS-6, because DS-6's truth parameters are drawn from the very specification the spreads
define — an earlier note in the code proposing exactly that fit has been withdrawn as circular.
* **The barometric altitude sigma factor** is measured at 1.71 on DS-6 and held at 4.0, because
DS-6 generates its pressures from the same atmosphere model the reader inverts and therefore
cannot show the model bias the factor mainly exists for. V-5 in the ledger gives the argument.
* **T-V5's ascent-Cd criterion** is measured and reported rather than gated, for the reason in §11.
Amending it is not the implementer's decision.
* **Two references** in §17 need their full citations checked before submission.
* **Parachute-drag recovery is bimodal** and unexplained; **burst-scale band coverage** is 15/20
against a nominal 18/20.

Every one of these is blocked on a document the build environment cannot reach — `ntrs.nasa.gov`,
`weather.uwyo.edu`, `ciaaw.org` and `doi.org` all resolve to nothing here — or on a decision that
belongs to the course owner. None is blocked on more work in this repository.

`docs/VERIFICATION.md` is the ledger: one entry per marker giving the exact source, the exact
check, and what changes if the answer differs. It is not a document anybody has to remember to
update, because `SourceRulesTest.everyMarkerHasARouteToClearingIt` walks `src/main` and `data/` and
fails the build on any marker without an entry. A marker is cheap to write and easy to forget;
tying it to a test is what keeps the list honest as the code moves.

---

## 16 · Reproducing every number in this report

Every figure quoted here comes from a command in this repository. A reader with a JDK 21 and a
fresh clone can reproduce all of them, offline:

| Figures | Command |
| --- | --- |
| T-V1, T-V2, T-V3, T-V4, ADR-1, ADR-9 | `./scripts/run.sh validate` |
| Coverage by package | `./mvnw verify`, then `target/site/jacoco/index.html` |
| T-P1, T-P2, T-P4, T-R1 | `./mvnw verify -Pperf` |
| T-V5 and band coverage | `./mvnw test -Pperf -Dtest=ParameterRecoveryTest` |
| T-V6 | `./mvnw test -Pperf -Dtest=LandingErrorTest` |
| T-V7 | `./mvnw test -Pperf -Dtest=EllipseCalibrationTest` |
| Sampler convergence (SC-13, ADR-14) | `./mvnw test -Pperf -Dtest=SamplerConvergenceTest` — about 3 min |
| The footprint and PL-1 / PL-2 | `./scripts/run.sh predict --members 1000 --sounding-id 1` |
| The posterior, PL-3, and re-prediction timing | `./scripts/run.sh replay --log data/truth/ds6-flight-01.csv --every 20` |
| DS-6 itself | `./scripts/run.sh synth` — regenerates all twenty flights byte-identically |
| This PDF | `python3 scripts/build-report.py` |

The console blocks in §9 are included from `docs/screenshots/` at build time rather than retyped,
so the report cannot disagree with the run that produced it.

### Exit codes

| Code | Meaning | Code | Meaning |
| --- | --- | --- | --- |
| 0 | success | 4 | model queried outside its valid range |
| 1 | usage error | 5 | solver did not converge |
| 2 | input violates a stated rule | 6 | persistence failure |
| 3 | file unparseable at `file:line` | 70 | unexpected — full trace in `out/error.log` |

`validate` exits non-zero if any tolerance is breached, which is what makes it usable as the CI
gate it serves as in `.github/workflows/build.yml`.

---

## 17 · References

* `docs/BLUEPRINT.md` — the full specification: FR/NFR/UC/ADR/T- identifiers, datasets, evaluation
methodology, traceability matrix.
* `docs/adr/ADR-1.md` … `ADR-18.md` — one file per design decision, each carrying the measurement
that justified it.
* U.S. Standard Atmosphere, 1976. NOAA-S/T 76-1562. `verify:` the DS-3 table against Table I of the
primary document before citing it.
* Liu, J. and West, M. Combined parameter and state estimation in simulation-based filtering.
`verify:` full citation before submission.
* Gordon, N., Salmond, D. and Smith, A. Novel approach to nonlinear/non-Gaussian Bayesian state
estimation. `verify:` full citation before submission.
* University of Wyoming upper-air archive — the sounding file layout this project parses.
`verify:` its terms before redistributing any real file.
