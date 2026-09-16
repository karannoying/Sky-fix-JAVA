-- SKYFIX schema V1
-- Destination in the repo: src/main/resources/schema/V1__init.sql
-- Applied idempotently by com.skyfix.persistence.SchemaInitializer.
-- Physics is enforced here as well as in Java: a bad row cannot reach the database.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER PRIMARY KEY,
    applied_utc TEXT NOT NULL
);

CREATE TABLE mission (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT    NOT NULL UNIQUE,
    launch_lat       REAL    NOT NULL CHECK (launch_lat BETWEEN -90 AND 90),
    launch_lon       REAL    NOT NULL CHECK (launch_lon BETWEEN -180 AND 180),
    launch_alt_m     REAL    NOT NULL CHECK (launch_alt_m BETWEEN -500 AND 6000),
    ground_elev_m    REAL    NOT NULL,
    launch_epoch_utc TEXT    NOT NULL,
    created_utc      TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE balloon_config (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    mission_id        INTEGER NOT NULL REFERENCES mission(id) ON DELETE CASCADE,
    name              TEXT    NOT NULL,
    payload_mass_kg   REAL    NOT NULL CHECK (payload_mass_kg > 0),
    envelope_mass_kg  REAL    NOT NULL CHECK (envelope_mass_kg > 0),
    launch_diameter_m REAL    NOT NULL CHECK (launch_diameter_m > 0),
    burst_diameter_m  REAL    NOT NULL CHECK (burst_diameter_m > launch_diameter_m),
    free_lift_kg      REAL    NOT NULL CHECK (free_lift_kg > 0),
    ascent_cd         REAL    NOT NULL CHECK (ascent_cd BETWEEN 0.1 AND 2.0),
    chute_area_m2     REAL    NOT NULL CHECK (chute_area_m2 > 0),
    chute_cd          REAL    NOT NULL CHECK (chute_cd BETWEEN 0.1 AND 2.0),
    gas               TEXT    NOT NULL CHECK (gas IN ('HELIUM','HYDROGEN')),
    config_hash       TEXT    NOT NULL UNIQUE
);

CREATE TABLE sounding (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id  TEXT    NOT NULL,
    epoch_utc   TEXT    NOT NULL,
    source      TEXT    NOT NULL CHECK (source IN ('WYOMING','IGRA','SYNTHETIC')),
    file_sha256 TEXT    NOT NULL,
    level_count INTEGER NOT NULL CHECK (level_count > 1),
    UNIQUE (station_id, epoch_utc, source)
);

CREATE TABLE sounding_level (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    sounding_id   INTEGER NOT NULL REFERENCES sounding(id) ON DELETE CASCADE,
    height_gpm    REAL    NOT NULL CHECK (height_gpm >= -500),
    pressure_pa   REAL    NOT NULL CHECK (pressure_pa > 0),
    temperature_k REAL    NOT NULL CHECK (temperature_k BETWEEN 150 AND 340),
    wind_u_ms     REAL    NOT NULL,
    wind_v_ms     REAL    NOT NULL,
    UNIQUE (sounding_id, height_gpm)
);
CREATE INDEX idx_level_sounding_height ON sounding_level(sounding_id, height_gpm);

CREATE TABLE flight_log (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    mission_id   INTEGER NOT NULL REFERENCES mission(id) ON DELETE CASCADE,
    name         TEXT    NOT NULL,
    source_kind  TEXT    NOT NULL CHECK (source_kind IN ('RECORDED','SYNTHETIC')),
    file_sha256  TEXT    NOT NULL,
    sample_count INTEGER NOT NULL CHECK (sample_count > 0),
    truth_json   TEXT,
    UNIQUE (mission_id, name)
);

CREATE TABLE telemetry_sample (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    flight_log_id INTEGER NOT NULL REFERENCES flight_log(id) ON DELETE CASCADE,
    epoch_utc     TEXT    NOT NULL,
    lat           REAL    NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon           REAL    NOT NULL CHECK (lon BETWEEN -180 AND 180),
    alt_gps_m     REAL,
    pressure_pa   REAL    CHECK (pressure_pa IS NULL OR pressure_pa > 0),
    temperature_k REAL,
    packet_id     INTEGER NOT NULL,
    quality_flags INTEGER NOT NULL DEFAULT 0,
    UNIQUE (flight_log_id, packet_id)
);
CREATE INDEX idx_tlm_log_epoch ON telemetry_sample(flight_log_id, epoch_utc);

CREATE TABLE run (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    mission_id        INTEGER NOT NULL REFERENCES mission(id),
    balloon_config_id INTEGER NOT NULL REFERENCES balloon_config(id),
    sounding_id       INTEGER REFERENCES sounding(id),
    flight_log_id     INTEGER REFERENCES flight_log(id),
    run_kind          TEXT    NOT NULL CHECK (run_kind IN ('PREFLIGHT','REPLAY','SYNTH','VALIDATION')),
    integrator        TEXT    NOT NULL CHECK (integrator IN ('RK4','RKF45')),
    step_s            REAL    NOT NULL CHECK (step_s > 0),
    member_count      INTEGER NOT NULL CHECK (member_count > 0),
    rng_seed          INTEGER NOT NULL,
    git_sha           TEXT    NOT NULL,
    config_hash       TEXT    NOT NULL,
    host_cores        INTEGER NOT NULL,
    started_utc       TEXT    NOT NULL,
    wall_clock_ms     INTEGER,
    status            TEXT    NOT NULL CHECK (status IN ('RUNNING','OK','FAILED'))
);
CREATE INDEX idx_run_mission_kind ON run(mission_id, run_kind, started_utc);

CREATE TABLE ensemble_member (
    run_id            INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    member_index      INTEGER NOT NULL,
    free_lift_kg      REAL    NOT NULL,
    ascent_cd         REAL    NOT NULL,
    burst_diameter_m  REAL    NOT NULL,
    chute_cd          REAL    NOT NULL,
    wind_scale        REAL    NOT NULL,
    burst_alt_m       REAL,
    landing_lat       REAL,
    landing_lon       REAL,
    landing_epoch_utc TEXT,
    PRIMARY KEY (run_id, member_index)
);
CREATE INDEX idx_member_landing ON ensemble_member(run_id, landing_lat, landing_lon);

CREATE TABLE run_state (
    run_id            INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    member_index      INTEGER NOT NULL,
    t_s               REAL    NOT NULL,
    lat               REAL    NOT NULL,
    lon               REAL    NOT NULL,
    alt_m             REAL    NOT NULL,
    vz_ms             REAL    NOT NULL,
    diameter_m        REAL    NOT NULL,
    phase             TEXT    NOT NULL CHECK (phase IN ('ASCENT','BURST','DESCENT','LANDED')),
    wind_extrapolated INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id, member_index, t_s)
);

CREATE TABLE estimate (
    run_id           INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    update_epoch_utc TEXT    NOT NULL,
    param_name       TEXT    NOT NULL CHECK (param_name IN ('FREE_LIFT','ASCENT_CD','BURST_SCALE','CHUTE_CD')),
    median           REAL    NOT NULL,
    p05              REAL    NOT NULL,
    p95              REAL    NOT NULL,
    ess              REAL    NOT NULL,
    resample_count   INTEGER NOT NULL,
    PRIMARY KEY (run_id, update_epoch_utc, param_name),
    CHECK (p05 <= median AND median <= p95)
);

CREATE TABLE landing_ellipse (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id           INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    update_epoch_utc TEXT,
    confidence       REAL    NOT NULL CHECK (confidence > 0 AND confidence < 1),
    center_lat       REAL    NOT NULL,
    center_lon       REAL    NOT NULL,
    semi_major_m     REAL    NOT NULL CHECK (semi_major_m > 0),
    semi_minor_m     REAL    NOT NULL CHECK (semi_minor_m > 0),
    azimuth_deg      REAL    NOT NULL CHECK (azimuth_deg BETWEEN 0 AND 360),
    area_km2         REAL    NOT NULL,
    CHECK (semi_major_m >= semi_minor_m),
    UNIQUE (run_id, update_epoch_utc, confidence)
);

CREATE TABLE validation_result (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id          INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    case_id         TEXT    NOT NULL,
    quantity        TEXT    NOT NULL,
    model_value     REAL    NOT NULL,
    reference_value REAL    NOT NULL,
    unit            TEXT    NOT NULL,
    tolerance       REAL    NOT NULL,
    tolerance_kind  TEXT    NOT NULL CHECK (tolerance_kind IN ('ABS','REL')),
    passed          INTEGER NOT NULL CHECK (passed IN (0,1)),
    UNIQUE (run_id, case_id, quantity)
);
