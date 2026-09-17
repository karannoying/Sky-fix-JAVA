# SKYFIX

**High-Altitude Balloon Trajectory Predictor & Real-Time Parameter Estimator**

*CSE2006 Programming in Java — Submission by Kumar Karan Bohidar (Reg. No. 25BAS10049), VIT Bhopal University*

SKYFIX models high-altitude balloon trajectories to compute landing footprints as 2D confidence ellipses. As telemetry streams during a flight, it re-estimates physical flight parameters using a particle filter and dynamically updates the landing footprint as the ascent and descent unfold.

---

## Key Performance Results

Evaluated against the reference benchmark suite across 20 synthetic DS-6 flights with known ground truth:

| Target Metric | Benchmark Requirement | Measured Performance | Status |
| --- | --- | --- | --- |
| **T-V5** Burst Altitude Recovery | $\ge 18 / 20$ within $500\text{ m}$ | **20 / 20** within $500\text{ m}$ ($17/20$ within $130\text{ m}$) | Passed |
| **T-V6** Landing-Error Reduction at Burst | $\ge 30\%$ median reduction | **74.0%** median reduction (from $15.4\text{ km}$ frozen baseline) | Passed |
| **T-V7** 95% Ellipse Containment | $0.90 \text{--} 0.98$ | **94.6%** (In-sample) / **94.0%** (Out-of-sample) | Passed |

---

## Capabilities & Implementation Matrix

| Subsystem / Feature | Implementation Status | Validation Suite |
| --- | --- | --- |
| USSA-1976 Atmospheric Model ($0\text{--}86\text{ km}$) | Completed (Hand-written) | `T-V1` |
| RK4 & Adaptive RKF45 Integrators | Completed | `T-U-INTEG`, `T-V3` |
| Flight Dynamics (Ascent / Burst / Parachute Descent) | Completed | `T-V2`, `T-V4` |
| Radiosonde Sounding Ingest & Wind Interpolation | Completed | `T-U-WIND`, `T-E3` |
| SQLite Persistence Layer (13 Tables, 6 DAOs) | Completed | `T-D1`, `T-D3`, `T-D4`, `T-S1` |
| Command-Line Interface (`ingest`, `predict`, `validate`, `version`, `replay`, `synth`) | Completed | `T-U1`, `T-E1`, `SkyfixCliTest` |
| Monte Carlo Ensemble & 50%/95% Confidence Ellipses | Completed | `T-U-LHS`, `T-U-ELLIPSE`, `T-P1`, `T-R1` |
| Telemetry Ingest & Replay Framework | Completed | `T-E5`, `T-P4` |
| Synthetic Flight Generator (DS-6 Benchmark) | Completed | `SynthServiceTest` |
| Burst Event Detection | Completed | `T-E2` |
| Particle Filter Parameter Estimator | Completed | `T-V5`, `ParticleFilterTest` |
| Pooled Filter Bank for Calibrated Posterior Bands | Completed | `FilterBankTest` |
| In-Flight Re-prediction & Scoring Engine | Completed | `T-V6`, `T-P2` |
| Analytical Plot Exporters (PNG Output PL-1 through PL-6) | Completed | `PlotExporterTest` |

---

## Requirements & Execution

**Prerequisites:** JDK 21 or higher. The project relies on the included Maven Wrapper (`./mvnw`). Execution is fully offline; all soundings and mission parameters are packaged locally under `data/`.

### Command Line Usage

**Linux / macOS:**

```bash
git clone <repository-url> && cd SKYFIX

# Run standard validation suite
./scripts/run.sh validate

# Ingest weather sounding data
./scripts/run.sh ingest --sounding data/soundings/SYNTHETIC_2026-09-14_00Z.txt \
                         --epoch 2026-09-14T00:00:00Z

# Predict point flight trajectory
./scripts/run.sh predict --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json \
                         --sounding-id 1

# Compute Monte Carlo dispersion footprint (50% and 95% confidence ellipses)
./scripts/run.sh predict --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json \
                         --sounding-id 1 \
                         --members 1000

# Replay telemetry log to re-estimate parameters and re-predict footprint
./scripts/run.sh replay  --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json \
                         --log data/truth/ds6-flight-01.csv \
                         --sounding-id 1 \
                         --every 20

# Regenerate synthetic DS-6 evaluation benchmark set (20 flights with known ground truth)
./scripts/run.sh synth   --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json

```

**Windows:**

```cmd
scripts\run.cmd validate
scripts\run.cmd predict --mission data\missions\mission.json --balloon data\missions\balloon.json --sounding-id 1

```

### Output Artifacts

Execution outputs are saved to `out/run-<id>/`:

* `trajectory.csv` & `summary.csv`: Step-by-step flight telemetry and summary statistics.
* `flight.geojson`: Primary flight track, burst point, and predicted impact point.
* `landing-scatter.csv` & `ellipses.csv`: Output when running ensemble operations (`--members`).
* `footprint.geojson`: Polygon geometry of the 50% and 95% confidence ellipses alongside individual member landing coordinates.
* Visual Plot Exporters: `pl1-altitude.png` (altitude profile vs time) and `pl2-footprint.png` (2D spatial scatter plot and confidence ellipses). Running `validate --plots` adds `pl6-atmosphere-residual.png` (atmospheric model error distribution over altitude).

**Interpreting Plot PL-2:** Axis scales are kept equal to preserve physical aspect ratios. Under single-sounding wind conditions where wind direction varies minimally across altitude, the predicted landing cloud becomes elongated (ratios up to 180:1) because uncertainty is primarily constrained to total flight duration along the wind vector.

---

## Exit Codes

The `validate` entry point returns non-zero status codes upon failure, making it suitable for CI/CD pipeline enforcement.

| Exit Code | Meaning | Exit Code | Meaning |
| --- | --- | --- | --- |
| `0` | Success | `4` | Model queried outside valid operational range |
| `1` | Usage / Argument error | `5` | Numerical solver convergence failure |
| `2` | Domain rule / Constraint violation | `6` | Persistence layer failure |
| `3` | Malformed / Unparseable input file | `70` | Uncaught exception (Trace logged in `out/error.log`) |

---

## Testing & Code Coverage

```bash
./mvnw test          # Runs unit, physics validation, and database integration tests
./mvnw verify        # Runs test suite and enforces JaCoCo code coverage gates
./mvnw verify -Pperf # Includes extended performance and reproducibility benchmarks

```

### Coverage Distribution

Overall project line coverage is **81.6%**, measured by JaCoCo:

* `core.atmos`: **100%**
* `core.flight`: **93.1%**
* `estimation`: **91.7%**
* `domain`: **93.7%**
* `io`: **74.9%**
* `app`: **73.8%**
* `persistence`: **73.2%**
* `cli`: **69.9%**

*Note:* CLI, Application, IO, and Persistence layers sit outside the strict 80% automated coverage gate by architectural design (BLUEPRINT §11) and are validated via functional command-surface tests (`T-U1`, `T-E1`, `T-E3`).

The standard unit suite executes 304 tests in under 5 minutes. The performance suite (`-Pperf`) adds long-running validation tests (`T-V5`, `T-V6`, `T-V7`, `T-P*`), taking approximately 25 additional minutes.

---

## Complete Validation Suite Results

Validation cases compare the simulated outputs against external benchmarks—such as published US Standard Atmosphere tables, closed-form analytic solutions, or secondary integration algorithms.

| Case ID | Verification Subject | Target Tolerance | Measured Result |
| --- | --- | --- | --- |
| **T-V1** | USSA-1976 $T, p, \rho$ at 25 altitudes ($0\text{--}47\text{ km}$) | 0.1% Relative | **$T$: 0.0008%, $p$: 0.0096%, $\rho$: 0.0107%** |
| **T-V2** | Ascent rate vs analytic buoyancy-drag terminal velocity | 2.0% | **Worst-case: 0.017%** |
| **T-V3** | RK4 vs RKF45 landing position ($dt = 0.5, 0.25, 0.125\text{ s}$) | $50\text{ m}$ | **$0.0025\text{ m} / 0.000085\text{ m} / 0.0000048\text{ m}$** |
| **T-V4** | Gas mass consistency across expanded balloon diameters | 1e-6 Relative | **7.9e-16** |
| **T-U-ELLIPSE** | Reconstruct semi-axes and rotation from synthetic cloud | 2.0% | **0.58% / 0.51% / 0.27°** |
| **T-P1** | 1,000-member ensemble run time (4 cores, warm JVM) | $< 30\text{ s}$ | **5.84 s** (3-run median) |
| **T-P2** | 200-member state re-prediction execution time | $< 5\text{ s}$ | **1.05 s** |
| **T-P4** | Parse throughput (10,000 telemetry samples) | $< 3\text{ s}$ | **0.11 s** |
| **T-E2** | Burst detection under $10\text{ m}$ noise & signal dropouts | $< 5\text{ s}$, $< 150\text{ m}$ err, 0 FP | **0.0–2.3 s latency, 1–36 m error, 0 false positives** |
| **T-R1** | Ellipse reproduction across threads given identical seeds | $1\text{e-}9$ diff | **Met (Exact match)** |
| **T-V5** | Burst altitude recovery across 20 DS-6 flights | $\ge 18/20 \le 500\text{ m}$ | **20/20** ($\le 500\text{ m}$), $17/20$ ($\le 130\text{ m}$), worst $461\text{ m}$ |
| **T-V6** | Burst landing error reduction (live vs frozen baseline) | $\ge 30\%$ median | **74.0%** (from $15.4\text{ km}$ median frozen error) |
| **T-V7** | 95% Confidence Ellipse containment rate | $0.90 \text{--} 0.98$ | **0.946** (In-sample), **0.940** (Out-of-sample) |
| **Band Calibration** | 5–95% posterior credible interval coverage | $\sim 18/20$ Nominal | **15–17 / 20** (Pooled); **0 / 20** (Single filter) |

### Supplementary Verification Findings

* **ADR-9 (Distance Metrics):** Haversine calculation error against a Vincenty inverse solution on the WGS-84 ellipsoid at $400\text{ km}$ distance scales to **0.327%**, negligible relative to atmospheric wind uncertainty.
* **ADR-1 (Atmospheric Approximation Limits):** Single-scale-height exponential atmospheric models introduce errors of **20.4%** in air density at the tropopause and **48.3%** at $35\text{ km}$, validating the decision to implement the full USSA-1976 model.

---

## Technical Insights & Stability Limits

### Numerical Stability of Quadratic Parachute Drag

Initial specifications dictated evaluating integrator accuracy at steps up to $dt \le 1.0\text{ s}$. However, quadratic drag linearizes to a negative real eigenvalue:

$$\lambda = -\frac{\rho \cdot C_d \cdot A \cdot \vert{}v\vert{}}{m}$$

As the payload enters dense lower atmospheric layers ($\rho \to 1.225\text{ kg/m}^3$), $\vert{}\lambda\vert{}$ reaches approximately $3.6\text{ s}^{-1}$. Classical RK4 integration possesses a real-axis stability limit of $2.785$. To prevent numerical divergence, the step size must satisfy:

$$dt < \frac{2.785}{\vert{}\lambda\vert{}} \approx 0.77\text{ s}$$

Executing fixed-step RK4 with $dt = 1.0\text{ s}$ causes numerical instability in lower atmospheric bounds, producing landing timing errors of $39\text{ s}$ and spatial shifts of $\sim 470\text{ m}$. To preserve physical accuracy:

1. The default integration timestep is constrained to **$dt = 0.25\text{ s}$**.
2. `FlightSimulator` dynamically assesses step bounds against stability limits, refusing integration steps outside the valid stability region (ADR-13).

---

## System Limitations & Domain Edge Cases

* **Footprint Geometry Scaling:** Under uniform wind profiles, landing dispersion becomes nearly 1D. Chi-square ellipse scaling (designed for 2 degrees of freedom) over-covers tight confidence bounds (~56% coverage for a 50% nominal ellipse), though higher intervals (95%) converge properly to nominal values.
* **Parameter Identifiability Trade-offs:** Ascent drag ($C_d$) and free lift are mathematically correlated during ascent. A 5% error in ascent drag combined with a 6% variance in free lift reproduces overall altitude profiles to within $10.5\text{ m}$ RMS (below standard GPS noise). Consequently, parameter estimation prioritizes recovery of burst altitude and descent drag rather than isolating ascent drag (ADR-3).
* **Particle Filter Variance:** Single particle filter instances undergo Monte Carlo sampling variance, causing reported credible intervals to collapse (0/20 coverage). SKYFIX uses a pooled bank of independent particle filters (ADR-18), raising coverage to 15–17/20 across test cases.
* **Parachute Drag Recovery:** Parachute $C_d$ recovery exhibits bimodal behavior across test flights—16 of 20 test runs recover drag coefficients within 5% error, while 4 runs diverge between 15% and 26% error due to local noise minima.
* **Atmospheric Wind Representation:** Wind vectors are sampled from a 1D sounding profile assumed invariant over geographic distance (ADR-7). Variance in real-world 4D wind fields is represented by scaling ensemble dispersion (`wind_scale`).
* **Terrain Considerations:** Terrain height is treated as constant ground level without SRTM raster lookups.
* **Execution Scope:** Operating modes are intentionally constrained to offline replay and batch simulation; live hardware serial communication streams are excluded from core application modules (ADR-4).

---

## Verification Artifacts & Screenshots

Diagnostic outputs required by BLUEPRINT §16 are generated in `docs/screenshots/`:

| Artifact ID | Description | Source File |
| --- | --- | --- |
| **SC-1** | `validate` command summary table | `sc1-validate.txt` |
| **SC-2** | Sounding ingest and rejection report | `sc2-ingest.txt` |
| **SC-3** | 1,000-member footprint generation | `sc3-footprint.txt`, `out/run-*/pl1-*.png`, `pl2-*.png` |
| **SC-4** | Replay telemetry state estimation | `sc4-replay.txt`, `out/run-*/pl3-*.png` |
| **SC-5** | T-V6 error reduction metrics | `sc5-error-reduction.txt` |
| **SC-6** | Reproducibility verification | `sc6-reproducibility.txt` |
| **SC-7** | Error handling and non-zero exit code validation | `sc7-config-failure.txt` |
| **SC-8** | JaCoCo coverage report summary | `sc8-coverage.txt` |
| **SC-9** | Automated CI execution history | `sc9-ci-history.txt` |
| **SC-10** | Repository commit graph and release tags | `sc10-git-log.txt` |
| **SC-11** | T-V5 recovery profile over 20 test flights | `sc11-tv5-recovery.txt` |
| **SC-12** | T-V7 ellipse containment rates | `sc12-tv7-calibration.txt` |
| **SC-13** | Latin Hypercube Sampling (LHS) convergence comparison | `sc13-sampler-convergence.txt` |

---

## Technical Documentation & Reports

* **Project Report:** A 38-page technical report is available at `docs/report/report.pdf`. Rebuild the PDF locally using:
```bash
python3 scripts/build-report.py

```


*(Requires Python 3 and a Chromium-based browser).*
* **Specification Blueprint:** `docs/BLUEPRINT.md` details system requirements, testing criteria, user stories, and architectural decision matrices.
* **Architectural Decisions:** Detailed rationale for design choices are documented in `docs/adr/` (ADR-1 through ADR-18).
* **Verification Ledger:** `docs/VERIFICATION.md` tracks physical constants and third-party data source assertions. Enforced during compilation by `SourceRulesTest`.
* **Database Schema:** `docs/schema/V1__init.sql` outlines the 13-table SQLite schema with embedded physics validation rules enforced via `CHECK` constraints.
