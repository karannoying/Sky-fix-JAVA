# ADR-2 — SQLite through plain JDBC, with hand-written DAOs

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-4.3, NFR-4, NFR-5, T-D1–T-D4, T-S1

## Context

Every run has to be reproducible and comparable after the fact: a grader must be able to
re-materialise a stored result without re-simulating it, and the report's numbers must be
traceable to the run that produced them. That needs real persistence, and the course rewards
seeing the candidate's own JDBC and SQL.

## Decision

Embedded SQLite via `sqlite-jdbc`, accessed through plain JDBC and hand-written DAOs behind a
generic `Repository<T, K>`. No ORM. The 13-table schema ships as a single reviewable artefact at
`src/main/resources/schema/V1__init.sql`, applied by `SchemaInitializer`.

## Alternatives considered

**H2 or MySQL.** Rejected. MySQL needs an install and a running server, which breaks NFR-6's
"fresh clone → result in ≤3 commands" and T-U1's clean-clone run. SQLite is one file and no
daemon.

**JPA/Hibernate.** Rejected by CLAUDE.md: the grader must see the candidate's own JDBC. It would
also hide exactly the mechanics — batches, transactions, generated keys — that the syllabus asks
for.

**A singleton connection.** Rejected. It hides the connection's lifetime and makes per-test
isolation impossible; `PersistenceTest` gives every test its own temporary file precisely because
`Database` is constructed and injected rather than reached for globally.

## Consequences and what implementing it settled

- **Idempotent DDL without touching the script.** `V1__init.sql` is not written to be re-runnable
  — most of its `CREATE TABLE` statements have no `IF NOT EXISTS`. Rather than edit the shipped
  schema, `SchemaInitializer` consults `schema_version` first and applies the script only if its
  version has never been recorded, the way a migration runner does. The schema file stays byte-
  identical to the copy in `docs/schema/` and reviewable as one artefact.
- **Foreign keys must be switched on per connection.** SQLite ignores `REFERENCES` unless
  `PRAGMA foreign_keys = ON` is set on each connection. `Database` sets it at open and exposes
  `foreignKeysEnforced()` so a test can assert it — without which every cascade test would have
  passed for the wrong reason.
- **The schema's cascade rules are asymmetric, and deliberately so.** Every child of `mission` and
  `run` cascades on delete *except* `run.mission_id` and `run.balloon_config_id`, which are plain
  references. So a mission with runs cannot be deleted: a run record is the provenance of a result
  that may already have been reported (NFR-5), and deleting a mission must not silently destroy
  it. `MissionDao.deleteById` turns that constraint violation into a message that says so, rather
  than surfacing a raw constraint name. T-D3 asserts both halves.
- **Physics is enforced twice, independently.** The `CHECK` constraints repeat the rules
  `BalloonConfig.Builder` enforces, so a row the builder would reject cannot reach the table by
  another route either — `schemaEnforcesPhysicsIndependentlyOfJava` writes such a row directly,
  bypassing the builder, and the database still refuses it.
- **A tampered row is detected on read.** `BalloonConfigDao` rebuilds each configuration and
  compares it against the stored `config_hash` (ADR-10). If someone edits the `.db` file behind
  the application's back, the mismatch is reported instead of quietly invalidating every
  reproducibility claim that rests on that row.
- **`PreparedStatement` everywhere is checked mechanically, not by memory.** T-S1 scans
  `src/main` for SQL built by concatenation. Making it pass required inlining a shared `COLUMNS`
  constant into whole SQL literals in two DAOs: `"SELECT " + COLUMNS + " FROM run"` is safe, but
  it is still SQL assembled from parts, and a rule with a carve-out is a rule that erodes. The
  scan is case-sensitive, because SQL here is upper case and user-facing prose is lower case —
  a case-insensitive scan flags every `"cannot delete mission " + id` and gets switched off,
  which is worse than no scan.
- I own migrations. A V2 schema needs a second script and a version bump in `SchemaInitializer`.
