#!/usr/bin/env bash
# Executes repeat.sql against SQLite over fixtures whose expected answer was taken from the REAL
# `PlanWindowProjector` (see README §Validation). Proves the reconstruction's LOGIC, not Postgres
# syntax — which is why repeat.sql avoids `DISTINCT ON` and every other Postgres-only construct.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
db=$(mktemp -t q5db); rm -f "$db"

sqlite3 "$db" <<'SQL'
CREATE TABLE briefing_region_snapshot (
  region_name TEXT, evaluation_date TEXT, target_type TEXT,
  mean_rating REAL, voting_count INTEGER, display_verdict TEXT,
  generated_at TEXT, briefing_generated_at TEXT);
INSERT INTO briefing_region_snapshot
  (region_name, evaluation_date, target_type, mean_rating, voting_count, briefing_generated_at)
VALUES
  -- A: same region on both picks; the only differing window is 1.0 back, so forcing = SILENCE
  ('Lakes','2026-09-07','SUNSET',5.0,2,'A'), ('Dales','2026-09-07','SUNSET',4.0,2,'A'),
  ('Lakes','2026-09-08','SUNSET',4.5,2,'A'), ('Moors','2026-09-08','SUNSET',3.0,2,'A'),
  ('Dales','2026-09-09','SUNSET',4.0,2,'A'),
  -- B: same region on both picks; a differing window ties at 4.5, so forcing FINDS one, cost 0
  ('Lakes','2026-09-07','SUNSET',5.0,2,'B'),
  ('Lakes','2026-09-08','SUNSET',4.5,2,'B'),
  ('Dales','2026-09-09','SUNSET',4.5,2,'B'),
  -- C: control — the picks already differ
  ('Lakes','2026-09-07','SUNSET',5.0,2,'C'),
  ('Dales','2026-09-08','SUNSET',4.5,2,'C'),
  -- D: control — AlsoGoodFloor refuses the runner-up (1.0 behind), so there is no second pick
  ('Lakes','2026-09-07','SUNSET',5.0,2,'D'),
  ('Dales','2026-09-08','SUNSET',4.0,2,'D');
SQL

grep -v '^\\echo' "$here/repeat.sql" | sqlite3 -header -column "$db"
rm -f "$db"
