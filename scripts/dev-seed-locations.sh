#!/usr/bin/env bash
#
# Seeds a LOCAL H2 dev database with regions and locations so the Plan tab's heat field has a
# catalogue to draw. Plan §7.3 of `docs/engineering/heat-field-plan.md`.
#
# ⚠️ Regions FIRST, and that is not a style choice: `POST /api/locations` takes a `regionId`, and
# `heatSpots.buildHeatSpots` DROPS a location with no region name — the region is the field's focus
# key and P3's rail unit, so an unregioned spot could be painted but never selected or named. Seed
# locations without regions and the field is simply empty, with nothing on screen saying why.
#
# ⚠️ This seeds the CATALOGUE only. Ratings need `cached_evaluation` rows and a backend RESTART —
# see the SQL block at the bottom of this file and §7.3, which explains why (the briefing enrichment
# reads an in-memory map populated from the DB only by `rehydrateCacheOnStartup`).
#
# Usage:
#   scripts/dev-seed-locations.sh                 # ~20 locations across 4 regions
#   scripts/dev-seed-locations.sh --dense         # + ~180 scattered around the anchors (P4's pan test)
#   BASE=http://localhost:8083 scripts/dev-seed-locations.sh
#
set -euo pipefail

BASE="${BASE:-http://localhost:8083}"
USER_NAME="${USER_NAME:-admin}"
PASSWORD="${PASSWORD:-golden2026}"
DENSE=0
[[ "${1:-}" == "--dense" ]] && DENSE=1

say() { printf '%s\n' "$*" >&2; }

TOKEN=$(curl -sS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[[ -n "$TOKEN" ]] || { say "login failed — is the backend up on $BASE?"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# ── Regions ──────────────────────────────────────────────────────────────────
# Four, so the strip's framing, the region ordering and the beyond line all have something to work
# with. Real names, because they are rendered.
declare -a REGIONS=(
  "Northumberland & Tyneside"
  "The North Yorkshire Coast"
  "The Lake District"
  "The Scottish Borders"
)

# A `name<TAB>id` table rather than an associative array: macOS ships bash 3.2, where `declare -A`
# is a syntax error, and this script has to run on the machine it is written for.
REGION_TABLE=""
# Separator is `|`, not a tab: a tab written as "\t" inside double quotes is a literal backslash-t
# in bash, so the table and the lookup silently disagreed and every location arrived with an empty
# regionId — which the API rejects as malformed JSON rather than as a missing region.
region_id_for() {
  printf '%s' "$REGION_TABLE" | awk -F'|' -v n="$1" '$1 == n { print $2; exit }'
}

for name in "${REGIONS[@]}"; do
  body=$(curl -sS -X POST "$BASE/api/regions" "${AUTH[@]}" -d "{\"name\":\"$name\",\"enabled\":true}")
  id=$(printf '%s' "$body" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  if [[ -z "$id" ]]; then
    # Already seeded — find it rather than failing, so the script is re-runnable.
    id=$(curl -sS "$BASE/api/regions" "${AUTH[@]}" \
      | tr '}' '\n' | grep -F "\"name\":\"$name\"" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
  fi
  [[ -n "$id" ]] || { say "could not create or find region: $name"; exit 1; }
  REGION_TABLE="${REGION_TABLE}${name}|${id}
"
  say "region $id  $name"
done

# ── Locations ────────────────────────────────────────────────────────────────
# name | lat | lon | region | locationType | bortleClass
# Coastal and inland, four dark-sky sites (bortleClass <= 4, the Map filter's own threshold), and
# one WOODLAND — which is deliberate: a canopy site is scored on inverted polarity and must NOT
# reach the field (P1's `isSkyPromptCandidate` gate). If a wood ever blooms gold on a misty dawn,
# this row is the fixture that shows it.
read -r -d '' ROWS <<'EOF' || true
Bamburgh Beach|55.6089|-1.7092|Northumberland & Tyneside|SEASCAPE|4
Dunstanburgh Castle|55.4894|-1.5942|Northumberland & Tyneside|SEASCAPE|4
Sycamore Gap|54.9917|-2.3778|Northumberland & Tyneside|LANDSCAPE|3
Tynemouth Priory|55.0175|-1.4186|Northumberland & Tyneside|SEASCAPE|6
Kielder Observatory|55.2331|-2.5975|Northumberland & Tyneside|LANDSCAPE|2
Hareshaw Linn|55.1450|-2.2300|Northumberland & Tyneside|WATERFALL|4
Wallington Woods|55.1350|-1.9450|Northumberland & Tyneside|WILDLIFE|5
Whitby Abbey|54.4883|-0.6067|The North Yorkshire Coast|SEASCAPE|5
Saltburn Pier|54.5847|-0.9739|The North Yorkshire Coast|SEASCAPE|6
Robin Hood's Bay|54.4331|-0.5297|The North Yorkshire Coast|SEASCAPE|4
Roseberry Topping|54.5122|-1.1042|The North Yorkshire Coast|LANDSCAPE|5
Sutton Bank|54.2381|-1.2119|The North Yorkshire Coast|LANDSCAPE|4
Mallyan Spout|54.4008|-0.7297|The North Yorkshire Coast|WATERFALL|4
Derwentwater|54.5772|-3.1450|The Lake District|LANDSCAPE|3
Wastwater|54.4425|-3.3011|The Lake District|LANDSCAPE|2
Buttermere|54.5386|-3.2778|The Lake District|LANDSCAPE|3
Ashness Bridge|54.5686|-3.1281|The Lake District|LANDSCAPE|3
Aira Force|54.5747|-2.9331|The Lake District|WATERFALL|3
St Abb's Head|55.9086|-2.1364|The Scottish Borders|SEASCAPE|3
Scott's View|55.5883|-2.6644|The Scottish Borders|LANDSCAPE|3
Kelso Abbey|55.5981|-2.4331|The Scottish Borders|LANDSCAPE|4
EOF

post_location() {
  local name=$1 lat=$2 lon=$3 region=$4 type=$5 bortle=$6
  local region_id
  region_id=$(region_id_for "$region")
  # The field names are `lat`/`lon`, not latitude/longitude — `AddLocationRequest` is a record and
  # Jackson silently defaults an unmatched primitive to 0.0, so the wrong names put every location
  # in the Gulf of Guinea rather than failing. The status is checked rather than discarded for the
  # same reason: a 400 here is invisible otherwise, and an empty field on the Plan tab looks exactly
  # like a broken join.
  local response status
  response=$(curl -sS -w '\n%{http_code}' -X POST "$BASE/api/locations" "${AUTH[@]}" -d @- <<JSON
{
  "name": "$name",
  "lat": $lat,
  "lon": $lon,
  "regionId": $region_id,
  "locationTypes": ["$type"],
  "solarEventTypes": ["SUNRISE", "SUNSET"],
  "tideTypes": [],
  "bortleClass": $bortle
}
JSON
)
  status=$(printf '%s' "$response" | tail -1)
  if [[ "$status" != "200" && "$status" != "201" ]]; then
    say "  ! $name -> HTTP $status: $(printf '%s' "$response" | head -1 | cut -c1-160)"
    return 1
  fi
}

count=0
while IFS='|' read -r name lat lon region type bortle; do
  [[ -z "${name:-}" ]] && continue
  post_location "$name" "$lat" "$lon" "$region" "$type" "$bortle" && count=$((count + 1))
done <<< "$ROWS"
say "seeded $count locations"

# ── Dense mode ───────────────────────────────────────────────────────────────
# P4's pan test needs a ~200-spot fixture: 20 will not exercise the kernel's 3×3 spatial bucketing,
# which is the optimisation that stopped 204 locations stalling a pan (§5.1). The scatter is around
# the anchors above rather than uniform, because clustered density is what the bucket walk is for.
#
# ⚠️ This seeds 189 more LOCATIONS and no ratings, and an unrated location paints NOTHING —
# `heatSpots.heatPointsFor` withdraws it from the window's point set. Rate the "Scatter N" names too
# or the kernel still only ever sees the 21 anchors, which is the measurement `--dense` exists to
# make. See the NOTE at the foot of this file for the names and their regions.
if [[ $DENSE -eq 1 ]]; then
  say "dense mode: scattering ~180 more around the anchors"
  i=0
  while IFS='|' read -r name lat lon region type bortle; do
    [[ -z "${name:-}" ]] && continue
    for n in $(seq 1 9); do
      i=$((i + 1))
      # Deterministic offsets, so two runs of the script produce the same map.
      dlat=$(awk -v n="$n" 'BEGIN { printf "%.4f", (n % 3 - 1) * 0.075 }')
      dlon=$(awk -v n="$n" 'BEGIN { printf "%.4f", (int(n / 3) - 1) * 0.130 }')
      post_location "Scatter $i" \
        "$(awk -v a="$lat" -v b="$dlat" 'BEGIN { printf "%.4f", a + b }')" \
        "$(awk -v a="$lon" -v b="$dlon" 'BEGIN { printf "%.4f", a + b }')" \
        "$region" "LANDSCAPE" "$bortle" || true
    done
  done <<< "$ROWS"
  say "seeded $i scattered locations"
fi

cat >&2 <<'NOTE'

Ratings, without spending on Claude — H2 console at http://localhost:8083/h2-console
(JDBC jdbc:h2:file:./data/goldenhour, user sa, empty password).

One row per region|date|event (V91), NOT per location: results_json is a JSON list of
BriefingEvaluationResult covering every location of that region you want lit — a location
missing from the list just renders unscored, and locationName must match the seeded name
exactly (the enrichment joins on it). The three columns must mirror the cache_key's own
components — the key is what the in-memory map is looked up by, the columns are what
rehydration filters on — and the date must be TODAY or FUTURE (rehydrateCacheOnStartup
ignores evaluation_date < today, silently). CURRENT_DATE in both places keeps the example
evergreen and the key and column incapable of disagreeing:

  INSERT INTO cached_evaluation
    (cache_key, region_name, evaluation_date, target_type, results_json, source,
     evaluated_at, updated_at)
  VALUES (
    'Northumberland & Tyneside|' || CURRENT_DATE || '|SUNSET',
    'Northumberland & Tyneside',
    CURRENT_DATE,
    'SUNSET',
    '[{"locationName":"Bamburgh Beach","rating":5,"fierySkyPotential":78,"goldenHourPotential":71,"summary":"Broken cloud clearing west."},
      {"locationName":"Dunstanburgh Castle","rating":3,"fierySkyPotential":48,"goldenHourPotential":62,"summary":"Mid deck thinning late."}]',
    'BATCH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

(evaluated_at / updated_at are explicit because the local H2 schema is Hibernate-generated —
`ddl-auto: update`, Flyway disabled in application-local.yml — so V91's DB defaults do not
exist here and both columns are bare NOT NULL. Scripting the insert instead of using the
console? The running backend holds the H2 file lock — stop, org.h2.tools.RunScript, start:

  pkill -f 'spring-boot:run'
  java -cp ~/.m2/repository/com/h2database/h2/*/h2-*.jar org.h2.tools.RunScript \
    -url "jdbc:h2:file:$PWD/backend/data/goldenhour" -user sa -password "" -script ratings.sql
  (cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local)

Forty-odd rows for a full week across four regions is not a good use of an afternoon — emit the
statement above in a loop over the REGIONS and ROWS tables at the top of this script.)

⚠️ RESTART THE BACKEND after inserting, then POST /api/briefing/run. The briefing enrichment reads
an in-memory ConcurrentHashMap populated from the DB only by `rehydrateCacheOnStartup`
(@EventListener(ApplicationReadyEvent)), so rows inserted while it is running are invisible to it.
Confusingly, GET /api/briefing/evaluate/scores DOES see them via its own DB fallback — so a
half-lit screen means you skipped the restart, not that the join is broken.

Startup logs `[EVAL HYDRATE] Loaded N entries from cached_evaluation (… dates >= YYYY-MM-DD)`.
That line is the fastest way to tell the two failure modes apart: N of 0 means your dates are in
the past (or the insert never committed), where a healthy N above a still-grey screen means the
restart happened but the briefing has not been rebuilt yet.

⚠️ --dense NEEDS ITS OWN RATINGS, and the failure is silent. It adds 189 locations (21 anchors x 9,
210 in total) named `Scatter 1` … `Scatter 189` and no ratings; `heatSpots.heatPointsFor` skips any
spot whose score is not a finite number, so every one of them is withdrawn from the field. The map
still paints, plausibly, off the 21 anchors — and the pan measurement the flag exists for is
vacuous, because the bucket walk never sees more than 21 points. Add the names to the SAME region's
results_json (a scatter name in the wrong region's row joins to nothing, exactly like any other).
They are deterministic, so generate them alongside the anchors rather than typing 189 entries:

  Northumberland & Tyneside   Scatter 1-63
  The North Yorkshire Coast   Scatter 64-117
  The Lake District           Scatter 118-162
  The Scottish Borders        Scatter 163-189

(The ranges follow the ROWS order above, nine per anchor. Two quirks of the offsets, so they are not
mistaken for bugs: `(n % 3 - 1)` by `(int(n / 3) - 1)` over n=1..9 is not a centred 3x3 — it drifts
east — and n=4 puts both offsets at zero, so one scatter sits exactly on its anchor.)

⚠️ Drive times, and therefore every leave-by line, need OpenRouteService — which is NOT configured
locally. `POST /api/user/settings/drive-times/refresh` returns 200 with distances and no minutes,
and the spot cards, the peek and the P8 location sheet then correctly render no departure at all
(plan §2.5: absence means "unknown", never "out of reach"). That is a real state worth seeing, but
if you need to exercise the departure lines — the day marker on a wrapped drive especially — insert
the minutes by hand during the same stopped window as the ratings:

  -- the home arm; user_id 1 is `admin`
  INSERT INTO user_drive_time (user_id, location_id, drive_duration_seconds) VALUES (1, 15, 350*60);
  -- an away origin's base → the whole roster, keyed on region_id (V145)
  INSERT INTO region_drive_time (region_id, location_id, drive_duration_seconds) VALUES (3, 1, 190*60);

Unlike the ratings, neither is read through a startup-populated map — no `@Cacheable`, no in-memory
copy on either path — so through the web console they take effect on the next request. Via
RunScript the point is moot: you have had to stop the backend for the lock anyway.

An away origin additionally needs its region to have a base town, or it cannot be an origin at all:
PUT /api/regions/{id}/base with baseName, baseLat and baseLon (all three, or a 400).
NOTE
