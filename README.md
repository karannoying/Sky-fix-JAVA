# SKYFIX

Predicts where a high-altitude balloon payload will land as a **confidence ellipse**, then re-estimates
the flight's real parameters from replayed telemetry with a particle filter and re-predicts the
footprint as the flight unfolds.

Submission for **CSE2006 Programming in Java**, VIT Bhopal University — Kumar Karan Bohidar,
Reg. No. 25BAS10049.

## What it does today

This is **`v1.0-submission`**, and the whole path is built: ingest → atmosphere → flight model →
Monte Carlo footprint → telemetry replay → particle-filter estimation → re-prediction →
persistence → CSV/GeoJSON/PNG export, with the reference-case validation suite wired to a CLI
command.

The three headline results, all measured across the twenty DS-6 flights:

| | result | required |
|---|---|---|
| **T-V5** burst altitude recovered | **20 / 20** inside 500 m, 17 inside 130 m | ≥ 18 / 20 |
| **T-V6** landing-error reduction at burst | **74.0%** median, from a 15.4 km frozen error | ≥ 30% |
| **T-V7** 95% ellipse containment | **94.6%** in-sample, **94.0%** out-of-sample | 0.90–0.98 |

| Capability | Status |
|---|---|
| USSA-1976 atmosphere, 0–86 km, hand-written (FR-2.1) | done, validated by T-V1 |
| RK4 and RKF45 integrators (FR-2.3) | done, validated by T-U-INTEG, T-V3 |
| Ascent / burst / parachute descent flight model (FR-2.3) | done, validated by T-V2, T-V4 |
| Radiosonde sounding ingest and wind interpolation (FR-1.1, FR-2.2) | done, validated by T-U-WIND, T-E3 |
| SQLite persistence, 13 tables, 6 DAOs (ADR-2) | done, validated by T-D1, T-D3, T-D4, T-S1 |
| CLI: `ingest`, `predict`, `validate`, `version` | done, validated by T-U1, T-E1 |
| Monte Carlo ensemble + 50/95% ellipse (FR-2.4) | done, validated by T-U-LHS, T-U-ELLIPSE, T-P1, T-R1 |
| Telemetry ingest and replay (FR-1.2) | done, validated by T-E5, T-P4 |
| Synthetic flight generator, DS-6 (FR-1.4) | done, validated by `SynthServiceTest` |
| Burst detection (FR-3.4) | done, validated by T-E2 |
| Particle-filter parameter estimation (FR-3.1–3.3) | done, validated by T-V5, `ParticleFilterTest` |
| Pooled filter bank for calibrated bands (ADR-18) | done, validated by `FilterBankTest` |
| In-flight re-prediction and scoring (FR-3.3, FR-4.1) | done, validated by T-V6, T-P2 |
| CLI `replay` | done, validated by `SkyfixCliTest` |
| PNG plots PL-1…PL-6 (FR-4.2) | done, validated by `PlotExporterTest` |

## Requirements

JDK 21. Nothing else — Maven comes from the committed wrapper, and **no command or test touches the
network**. All data ships in `data/`.

## Install and run

```bash
git clone <repository-url> && cd SKY-FIX

# Unix / macOS
./scripts/run.sh validate
./scripts/run.sh ingest  --sounding data/soundings/SYNTHETIC_2026-09-14_00Z.txt \
                         --epoch 2026-09-14T00:00:00Z
./scripts/run.sh predict --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json --sounding-id 1

# a dispersed footprint rather than a single point: 50% and 95% confidence ellipses
./scripts/run.sh predict --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json --sounding-id 1 --members 1000

# replay a telemetry log: estimate the flight's parameters and re-predict the footprint
./scripts/run.sh replay  --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json \
                         --log data/truth/ds6-flight-01.csv --sounding-id 1 --every 20

# regenerate the DS-6 synthetic evaluation set (20 flights with known truth)
./scripts/run.sh synth   --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json
```

```cmd
REM Windows
scripts\run.cmd validate
scripts\run.cmd predict --mission data\missions\mission.json --balloon data\missions\balloon.json
```

`predict` writes `out/run-<id>/` containing `trajectory.csv`, `summary.csv` and `flight.geojson`
(drop the GeoJSON onto any map to see the track, burst point and landing point). With `--members`
it also writes `landing-scatter.csv`, `ellipses.csv` and `footprint.geojson`, the last holding each
confidence ellipse as a polygon alongside every member's landing point, plus two charts:
`pl1-altitude.png` (altitude against time, ensemble behind the nominal flight) and
`pl2-footprint.png` (the landing scatter with both ellipses). `validate --plots` adds
`pl6-atmosphere-residual.png`, which shows how the atmosphere model's error behaves with altitude
rather than as the single worst-case number T-V1 reports.

**Reading PL-2.** Its axes share a scale, so the ellipse has its true shape. Under the synthetic
sounding shipped in `data/soundings/`, whose wind veers only about 40° over the whole profile, the
footprint comes out extremely elongated — roughly 180:1 — because almost all the uncertainty is in
how long the balloon stays airborne, and that lands along one axis. A real sounding veers more and
gives a rounder footprint. The sliver is the data's shape, not a plotting fault; the chart could
only hide it by distorting the axes. Per-run detail goes
to `out/skyfix.0.log`; the console carries warnings and above.

### Exit codes

`validate` exits non-zero if any tolerance is breached, so it works as a CI gate.

| Code | Meaning | Code | Meaning |
|---|---|---|---|
| 0 | success | 4 | model queried outside its valid range |
| 1 | usage error | 5 | solver did not converge |
| 2 | input violates a stated rule | 6 | persistence failure |
| 3 | file unparseable at `file:line` | 70 | unexpected — full trace in `out/error.log` |

## Testing

```bash
./mvnw test          # unit, model validation and DB integration
./mvnw verify        # adds the JaCoCo coverage gate
./mvnw verify -Pperf # adds timing and reproducibility tests
```

Measured line coverage, from the JaCoCo report the gate reads: `core.atmos` **100%**,
`core.flight` **93.1%**, `estimation` **91.7%** (gate: 80% on `core.*` and `estimation`), `domain`
93.7%, `io` 74.9%, `app` 73.8%, `persistence` 73.2%, `cli` 69.9%; 81.6% overall. The last four sit
outside the strict gate by design (BLUEPRINT §11) and are covered by T-U1, T-E1 and T-E3 at the
command surface.

Take these from a clean `target/`. JaCoCo's agent writes into an existing `jacoco.exec`, so running
`-Dtest=SomeClass` and then reading the report gives that one class's coverage wearing the whole
project's name — which is how an earlier capture of this table came to understate `cli` and `io` by
more than twenty points each.

304 tests run in about 5 minutes; the perf-tagged evaluations (T-V5, T-V6, T-V7, T-P*) add roughly
another 25 and are excluded by default.

## Validation results

Every case compares the model against something computed **outside** it — a published reference
table, a closed-form solution, or a second integration scheme. `./scripts/run.sh validate` prints
this table; the figures below are its current output.

| Case | What it checks | Tolerance | Measured |
|---|---|---|---|
| T-V1 | USSA-1976 T, p, ρ at 25 altitudes, 0–47 km, against DS-3 | 0.1% relative | **T 0.0008%, p 0.0096%, ρ 0.0107%** |
| T-V2 | Ascent rate against analytic buoyancy–drag terminal velocity, 5 altitudes | 2% | **worst 0.017%** |
| T-V3 | RK4 vs RKF45 landing separation, dt = 0.5 / 0.25 / 0.125 s | 50 m | **0.0025 / 0.000085 / 0.0000048 m** |
| T-V4 | Gas mass recovered from each stored diameter | 1e-6 relative | **7.9e-16** |
| T-U-ELLIPSE | Recover known semi-axes and orientation from a synthetic cloud | 2% | **0.58% / 0.51% / 0.27°** |
| T-P1 | 1,000-member ensemble, 4 cores, warm JVM, sounding wind | 30 s | **5.84 s** (3-run median) |
| T-P2 | 200-member re-prediction | 5 s | **1.05 s** |
| T-P4 | 10,000 telemetry samples parsed | 3 s | **0.11 s** |
| T-E2 | Burst detection, 10 m GPS noise, dropouts | 5 s, 150 m, 0 false positives | **0.0–2.3 s, 1–36 m, none** |
| T-R1 | Same seed, 1 thread vs 4 threads, identical ellipse | 1e-9 | **met** |
| T-V5 | Burst altitude recovered over 20 DS-6 flights | 500 m on ≥18/20 | **20/20**, 17 inside 130 m, worst 461 m |
| T-V6 | Landing-error reduction at burst, live against frozen | 30% median | **74.0%**, from a 15.4 km median frozen error |
| T-V7 | 95% ellipse containment against a real ensemble | 0.90–0.98 | **0.946** in-sample, **0.940** out-of-sample |
| Band calibration | 5–95% posterior bands containing the truth | ~18/20 nominal | **15–17/20** pooled; **0/20** with a single filter (ADR-18) |

Two figures the blueprint left open are now measured rather than estimated:

- **ADR-9** — haversine against a Vincenty inverse solution on the WGS-84 ellipsoid, at ~400 km
  mission scale: **0.327%**, far below the wind-field error that dominates the prediction.
- **ADR-1** — a best-fit single-scale-height exponential atmosphere is **20.4%** off in density at
  the tropopause and **48.3%** off by 35 km, which is why USSA-1976 was implemented instead.

## A finding worth knowing about

The blueprint specified T-V3 "at dt ≤ 1 s". It does not hold there, and the reason is not a tight
tolerance. Quadratic drag linearises to a real eigenvalue `λ = −ρ·Cd·A·|v|/m` whose magnitude **grows
as the payload descends into denser air**, reaching ~3.6 /s near the ground. RK4's real-axis stability
limit is 2.785, which caps the step at ~0.77 s there — so a 1 s step is outside the region for the
last few kilometres of every descent, and the landing came out 39 s late and ~470 m off, with the
descent rate visibly oscillating.

The default step is therefore 0.25 s, and `FlightSimulator` refuses any step outside the integrator's
stability region rather than returning a plausible-looking wrong answer. Each integrator derives its
own limit by applying itself to `y' = λy` — nothing is transcribed. See `docs/adr/ADR-13.md`.

## Known limitations

- **The landing footprint is effectively one-dimensional, and the 50% ellipse over-covers because
  of it.** T-V7 measured the 95% ellipse at 0.946 in-sample and 0.940 out-of-sample, comfortably
  inside its window — but the fitted footprint comes out 76.4 × 0.5 km, an aspect ratio near 150,
  because this sounding's wind direction barely turns with altitude. Almost all the uncertainty is
  about *how long the flight lasts*, not about where it goes. A chi-square scaling for two degrees
  of freedom is therefore generous when the cloud has closer to one, which is why the 50% ellipse
  contains about 56% rather than 50%, converging towards nominal as the confidence rises. The
  ellipse is the right shape for the quantity it reports; it is simply describing a nearly linear
  cloud.
- **Ascent drag cannot be recovered to 5% from this observation set, and no estimator could.** Free
  lift and ascent drag trade off almost exactly: a 5% error in ascent Cd absorbed by a 6% change in
  free lift reproduces the whole flight's altitude profile to 10.5 m RMS — the GPS noise itself —
  and over the ascent alone a 20% error matches to 0.44 m. The two recovered values slide together
  on every one of the twenty flights. `ParameterRecoveryTest` therefore measures and prints ascent
  Cd rather than gating on it, with ADR-3 carrying the identifiability table as the stated reason.
  Burst altitude, the quantity a recovery team acts on, is recovered on 20 of 20.
- **A single particle filter's bands are not credible intervals.** Measured: 0/20 coverage. The
  dominant error is Monte Carlo rather than statistical — eight filters differing only in seed
  disagreed by about a hundred times their own reported band width — so the posterior is pooled
  from a bank of independent filters (ADR-18), which takes coverage to 15–17/20 against a nominal
  18/20. Bands are still slightly narrow; burst scale is the weakest at 15/20.
- **Parachute drag recovery is bimodal.** Sixteen of twenty flights recover it to under 5%, most of
  those to under 1%; four land between 15% and 26% out. Not correlated with the flight's wind-scale
  error, its burst-altitude error, or anything else checked so far. Reported, not gated.
- **A single sounding stands in for a 4-D wind field** (ADR-7). Over a long drift the profile is
  assumed to hold along the whole track. This is the model's largest named error source; the
  `wind_scale` dispersion exists to carry it into the footprint once the ensemble lands.
- **Constant ground elevation.** No terrain lookup (SRTM is Stretch).
- **Replay only.** No live serial or radio ingest in `src/main`, by design (ADR-4).
- **`data/soundings/` ships a synthetic profile**, clearly labelled, because the archive was not
  reachable from the build environment. See the file header and `[PLACEHOLDER — DS-1]`.
- **DS-6 is synthetic by necessity.** No real 30 km or 45 km flight log exists yet, so O3 and O4
  are scored on twenty generated flights with known truth. `data/truth/seeds.csv` is the dataset
  definition and the telemetry regenerates from it byte-identically; the generated logs are not
  committed. Every file says in its first line that it is synthetic. When HabSat flies, the same
  commands score a recorded log with no code change.
- **`data/reference/ussa1976.csv` is corroborated, not yet transcribed from the primary document.**
  Its 25 rows come from two independent third-party implementations of the standard that agree to
  0.00987%. The header carries a `verify:` marker requiring hand transcription from NOAA-S/T 76-1562
  Table I before the report cites it.

Every unverified number in the project carries a literal `verify:` or `[PLACEHOLDER]` marker, and
every marker has an entry in **`docs/VERIFICATION.md`** naming the source that clears it, the exact
check, and what changes if the answer differs. All of them are blocked on documents this build
environment cannot reach, not on more work in the repository. `SourceRulesTest` fails the build on
a marker with no ledger entry, so the list cannot quietly go stale.

## Screenshots and results

`docs/screenshots/` holds the captures BLUEPRINT §16 asks for, all produced by the commands above
after `v0.4-validated`. The console captures are the programs' real stdout, saved verbatim; the
charts are the PNGs the exporters write, at 1460 px or wider.

| ID | Artefact | Source |
|---|---|---|
| SC-1 | `validate` reference-case table | `sc1-validate.txt` |
| SC-2 | ingest, naming the sounding and its rejected lines | `sc2-ingest.txt` |
| SC-3 | 1,000-member footprint, with PL-1 and PL-2 | `sc3-footprint.txt`, `out/run-*/pl1-*.png`, `pl2-*.png` |
| SC-4 | `replay`: posterior bands, burst, re-prediction timing | `sc4-replay.txt`, `out/run-*/pl3-*.png` |
| SC-5 | T-V6 error-reduction table, live against frozen | `sc5-error-reduction.txt` |
| SC-6 | the same seed reproducing the same ellipse | `sc6-reproducibility.txt` |
| SC-7 | a deliberate config failure, exit code 2, no stack trace | `sc7-config-failure.txt` |
| SC-8 | JaCoCo coverage summary | `sc8-coverage.txt` |
| SC-9 | CI history, fifteen runs including the two that failed | `sc9-ci-history.txt` |
| SC-10 | `git log --oneline --graph --decorate`, with the milestone tags | `sc10-git-log.txt` |
| SC-11 | T-V5 recovery over all twenty flights | `sc11-tv5-recovery.txt` |
| SC-12 | T-V7 ellipse containment | `sc12-tv7-calibration.txt` |
| SC-13 | Sampler convergence: what LHS is worth (ADR-14) | `sc13-sampler-convergence.txt` |
| — | PL-4 landing error, PL-5 calibration | `pl4-landing-error.png`, `pl5-ellipse-calibration.png` |

## Report

`docs/report/report.pdf` — 38 pages, 15 sections plus a compliance checklist and a
reproduce-every-number appendix. Rebuild it with:

```bash
python3 scripts/build-report.py
```

The build inlines the console transcripts from `docs/screenshots/` rather than quoting them, so the
report cannot disagree with the runs that produced it. It needs only Python 3 and a browser; if no
Chrome or Chromium is found it still writes `report.html`, which prints to PDF from any browser.

## Documentation

- `docs/BLUEPRINT.md` — the specification: FR/NFR/UC/ADR/T- IDs, datasets, evaluation methodology,
  traceability matrix, timeline.
- `docs/adr/` — one file per design decision, including the three this implementation added
  (ADR-11 free lift drives inflation, ADR-12 RKF45 is a fixed-step cross-check, ADR-13 the step is
  bounded by descent stability).
- `docs/VERIFICATION.md` — the ledger of every unverified number: its source, the check that
  clears it, and what changes if the answer differs. Enforced by `SourceRulesTest`.
- `HANDOFF.md` — current state and the next steps in order.
- `docs/schema/V1__init.sql` — the 13-table schema, with physics enforced as CHECK constraints.

## Licence and attribution

Coursework submission. The USSA-1976 tables are a US Government publication; the sounding layout
follows the University of Wyoming upper-air archive (`verify:` its terms before redistributing any
real file).
