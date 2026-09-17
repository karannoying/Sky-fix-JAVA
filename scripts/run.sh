#!/usr/bin/env sh
# SKYFIX launcher (Unix). Builds on first use, then runs the CLI.
#   ./scripts/run.sh validate
#   ./scripts/run.sh predict --mission data/missions/mission.json --balloon data/missions/balloon.json
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

JAR="target/skyfix-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "[skyfix] building $JAR ..." >&2
  ./mvnw -q -B -DskipTests package
fi

exec java -jar "$JAR" "$@"
