# Diagrams

| File | What it shows | Kept current with |
|---|---|---|
| `architecture.svg` | Package dependency direction, one way only | The package structure in `src/main/java/com/skyfix` |
| `replay-sequence.mmd` | The replay loop: detector, filter bank, throttled re-prediction | ADR-17, ADR-18, `ReplayService` |

`architecture.svg` renders in any browser and is embedded directly in the report.
`replay-sequence.mmd` is Mermaid source; GitHub renders it inline, and any Mermaid tool will
export it.

CLAUDE.md rule 6 applies: a stale diagram is worse than no diagram. `replay-sequence.mmd` was
corrected when ADR-17 moved the re-prediction out of the per-sample loop — the version in
BLUEPRINT §8 had it inside, which the requirement's own timing budget makes impossible.
