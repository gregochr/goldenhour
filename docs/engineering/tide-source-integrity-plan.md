# Tide source integrity — plan

Status: **reviewed, building** (2026-10-30). Owner question that started it: why is the 8–10 Nov
spring tide run only three days long?

## 1. What we found

**The three-day run is real.** `TideSizeIndex` counts a day as spring when any coastal location's
biggest high water clears 125% of that location's own mean high. Production, 7–11 Nov:

| Day | Best ratio | Locations over 1.25 |
|---|---|---|
| 7 Nov | 1.245 | 0 |
| 8 Nov | 1.303 | 19 |
| 9 Nov | 1.309 | 47 |
| 10 Nov | 1.289 | 50 |
| 11 Nov | 1.243 | 0 |

It is an apogean (weak) spring tide; the shoulders miss by under 1%. The December new-moon spring
tide (best 1.245 on 11 Dec) misses on every day and is absent from the feed.

**Investigating that exposed a data fault.** From the WorldTides fetch of **2026-09-07** onward
(tides from **6 Dec 2026**), ten of the 61 coastal locations are served different values for their
coordinates. Lags are high water relative to Whitby Piers' stored high water, before → after the
7 Sep fetch:

| Group | Lag before → after | Height step vs Whitby |
|---|---|---|
| Staithes, Saltburn-by-the-Sea | −15 / −18 → **+40 min** | +0.19 / +0.17 m |
| Black Hole, Cullernose Point, Dunstanburgh Castle, Howick Scar, Rumbling Kern, Sugar Sands, The Bathing House, Craster | −55 → **−21 min** | +0.11 m |
| Cresswell Beach | −40 → **−21 min** | +0.05 m |

Every other location shows a 0–2 min lag change and a uniform ≈ +0.05 m height step against Whitby
over the same seam. That common step is seasonal, not a fault: the seam falls at the same calendar
point as last year, where Whitby, Scarborough and Embleton's own monthly means match last year's
within 1 cm. The ten's height steps above **include** that common +0.05 m, so Cresswell's height
evidence is nil and its case rests on timing.

- WorldTides serves these points from **FES2022** (`station: null`, `atlas: FES2022`,
  `responseDatum: null`). It now returns the new value for **previously fetched** dates too:
  Staithes 20 Nov, stored (fetched 2026-08-17) HW 12:17 UTC / 1.209 m; fetched 2026-10-30,
  13:03 / 1.356 m. So a plain re-fetch reproduces the fault.
- Points 600 m and 1.2 km offshore of Staithes return byte-identical extremes: same model cell.
  Nudging the pin does not help.
- **Validated against measured water.** Environment Agency gauge readings, 2–30 Oct, high water
  relative to the Whitby gauge: Blyth −39, North Shields −34, Tees Dock −17, Bridlington +32
  (wide spread). WorldTides' pre-change lags at the nearest locations: −39, −32, −20, +42. Staithes
  and Saltburn lie between Tees/Redcar and Whitby (about −20…0); the new +40 is later than
  Bridlington, 60 km south. Craster and Cresswell lie north of Blyth (≤ −39); the new −21 is later
  than North Shields. The new values are wrong by roughly 35–55 minutes; the old ones agree.
- **Why nothing noticed.** The weekly refresh extends only the tail, so old and new answers sit
  side by side with a step at the seam. `WorldTidesResponse` maps only `status`, `extremes` and
  `callCount`; `atlas`, `station` and the response point are discarded, and the pre-change atlas
  is therefore unknown.

**Impact.** Coming up already shows it (Staithes alone extends the 30 Dec, 13 Jan and 28 Jan run
edges and inflates near-misses). For these ten, from 6 Dec, high-water times are 20–58 minutes off,
and so is every tide-at-the-light judgement (alignment, `tideOnTheLight`, the stand-down,
"HW 06:18 · 58m after sunrise" copy), plus heights, spring/king badges and, as those months become
history, their thresholds.

**⚠️ Deadline: Monday 9 Nov 2026, 02:00 UTC.** `WorldTidesIngestionService` re-seeds a location's
whole forward window when its oldest forward `fetched_at` is more than `RESEED_INTERVAL_DAYS` (90)
old. Staithes' oldest forward rows were fetched 2026-08-10, so the `tide_refresh` of Mon 9 Nov
re-seeds it, and FES2022 values replace everything from 9 Nov onward for all ten, putting the
fault on the Plan tab immediately. P1a must be **released and deployed before then**. (Owner check:
`SELECT l.name, min(e.fetched_at) FROM tide_extreme e JOIN locations l ON l.id = e.location_id
WHERE e.event_time >= now() GROUP BY 1 ORDER BY 2;`.)

## 2. Decisions

- **D1 — Borrow a trusted neighbour's water, don't re-fetch or backfill.** A FES2022 backfill
  (≈5,000 credits, ≈$2.50) makes the ten consistent with themselves but keeps their timing wrong
  and hides the fault. Instead each location gets an optional **tide point**: coordinates sent to
  WorldTides in place of the pin. The ten borrow a neighbour whose cell agrees with the gauges.
  The borrowed coordinates are a **snapshot** of the neighbour's pin; they do not follow a later
  move of it.

  | Location(s) | Tide point copied from | Why |
  |---|---|---|
  | Staithes | Runswick Bay (lag 0) | adjacent cove; gauges put Staithes between Redcar and Whitby |
  | Saltburn-by-the-Sea | Redcar (lag −20) | adjacent; old own lag −18 |
  | Craster group (7) | Alnmouth (lag −47) | old own lag −55; Embleton's −82 is 35 min away over a few km, so Embleton's cell is itself doubtful and is not used |
  | Cresswell Beach | Newbiggin-by-the-Sea (lag −39) | adjacent; old own lag −40 |

  All four sources showed no step at the seam (lag change 0 or ±1 min).
- **D2 — Keep history.** Their pre-December history is the old, gauge-consistent answer at their
  own pin, so thresholds stay. Accepted cost: for up to a year their spring/king ratios compare
  borrowed-cell heights against an own-pin mean. The gap is a few centimetres (Staithes vs
  Runswick ≈ 0.02 m, Craster vs Alnmouth ≈ 0.00 m, Saltburn vs Redcar ≈ 0.05 m, Cresswell vs
  Newbiggin ≈ 0.00 m).
- **D3 — Detection is "diff on write".** An unchanged model returns identical answers for dates
  already stored, so any fetch that overlaps stored rows shows a cell change directly — no
  reference location, no seasonal noise. This replaces the neighbour-step check of the first
  draft, which would have missed most of this incident (neighbours in the same changed area moved
  together).
- **D4 — On a detected change, store the new data and alert; do not freeze.** Freezing would stop
  coverage advancing for every location after any global WorldTides improvement; the failure here
  was silence, not the write. The alert names the location and the size of the change, and the
  fix (a tide point) is an admin action.
- **D5 — Stay on WorldTides.** Where gauges exist it is within a few minutes. Admiralty Premium
  (£300 + VAT/yr, chart-datum heights needing threshold recalibration, display licence unclear) is
  parked, not rejected.
- **D6 — Dimmed bars are independent of P1/P2 code.** Their ratios from 6 Dec onward are only
  right after the P1 repair has run in production.
- **D7 — The tide point applies to WorldTides only.** Open-Meteo Marine (`MarineWaveRefreshService`),
  solar times, the observer window, surge and weather keep the pin.
- **Owner actions (not code):** report the fault to WorldTides with the 20 Nov before/after and the
  gauge table; release and deploy P1a before 9 Nov; the post-deploy steps under P1a.

## 3. Phases

Order: **P1a → P2 → P3 → P1b → P4**, one PR each, each from updated `main`. P1a alone repairs the
data; it is the deadline phase. Every phase adds a `changelog.d/` entry and updates the reference
docs it touches in the same PR. New code reads time from an injected `Clock`; civil dates go through
`ForecastHorizon`.

### P1a — Tide point and fetch provenance (deadline: deployed before Mon 9 Nov 02:00 UTC)

**Schema (V205):**
- `locations.tide_lat`, `locations.tide_lng` — nullable `DOUBLE PRECISION`, both or neither
  (`CHECK ((tide_lat IS NULL) = (tide_lng IS NULL))`). `lng`, matching `valley_floor_lng`.
- Table `tide_fetch` — one row per WorldTides call: `id` BIGSERIAL, `location_id` BIGINT NOT NULL
  REFERENCES locations ON DELETE CASCADE, `fetched_at` TIMESTAMP NOT NULL, `window_from`,
  `window_to` TIMESTAMP, `reason` VARCHAR(16) NOT NULL (`SEED`, `RESEED`, `TAIL`,
  `POINT_CHANGE`, `BACKFILL` — a plain string column, not `@Enumerated`, so no
  `LocalH2EnumWidener` entry), `success` BOOLEAN NOT NULL, `request_lat`, `request_lng`,
  `response_lat`, `response_lng` DOUBLE PRECISION, `atlas`, `station`, `response_datum`
  VARCHAR(64), `call_count` INTEGER, `extremes_stored` INTEGER NOT NULL DEFAULT 0. Index
  `(location_id, fetched_at DESC)`. About 3,200 rows a year: no pruning needed.
- Data: a `DO` block sets the ten tide points by copying each source location's own `lat`/`lon`
  by exact name (names as in production; `locations.name` is unique), counts the rows updated and
  `RAISE NOTICE`s the count (expected 10 in production; 0 on an empty roster). It never raises an
  error — a typo must not block the deploy; the owner check below catches it.

**Code:**
- `LocationEntity`: `tideLat`, `tideLng` (ordinary updatable columns; `save()` callers are only in
  `LocationService`) and `getEffectiveTidePoint()` (tide point if set, else pin). Entity column
  types must match the migration exactly (prod is `ddl-auto: validate`).
- `WorldTidesResponse` maps `atlas`, `station`, `requestLat`, `requestLon`, `responseLat`,
  `responseLon`, `responseDatum` (all nullable; unknown properties are already ignored).
- `WorldTidesIngestionService` sends `getEffectiveTidePoint()` — the **only** coordinates it sends
  — on the forward and backfill paths.
- `resolveFetchWindow` takes the effective point as a parameter and gains a first rule, ahead of
  the `frontier == null` and at-horizon branches: **if the effective point differs (tolerance
  1e-6°) from the request point of the location's latest successful *forward* `tide_fetch` row
  (`SEED`/`RESEED`/`TAIL`/`POINT_CHANGE` — never `BACKFILL`), re-seed the whole forward window with
  reason `POINT_CHANGE`.** A location with no forward row is treated as last fetched at its **pin**:
  on first deploy the 51 unchanged locations do not re-seed (no credits), and the ten do. A pin
  edited before provenance existed is not detected (accepted).
- `FetchWindow.reason` becomes a code (`SEED` / `RESEED` / `TAIL` / `POINT_CHANGE`) plus the
  existing human text for the log line.
- Provenance writes: `TideService` builds the ingestion service with `new`, so its
  `@Transactional` does not apply and each repository call commits on its own. The success row is
  written **after** the delete and save have returned; a failed provenance write is logged and does
  not fail the fetch (the worst case is one extra `POINT_CHANGE` re-seed next week). A failure row
  (`success = false`) is written best-effort in the catch. Adding `TideFetchRepository` changes
  `TideService`'s constructor; update its callers and tests.
- The "WorldTides ← …" log line adds `atlas`, `station` and the response point. (A log field, not a
  detector — the detector is P2.)
- `LocationDto` and `LocationDtoMapper` carry `tideLat`/`tideLng` (admin metadata, harmless on
  other reads), so the ten are visible through the API before P1b's form exists.

**Tests:** `resolveFetchWindow` (no row → pin; same point → tail; changed point → re-seed; a later
`BACKFILL` row at the new point does not suppress the re-seed; tolerance; rule precedes the
at-horizon `null`); forward and backfill send the tide point, not the pin; provenance row on
success (fields from the response), on failure, and no success row when the merge throws;
response mapping with and without the new fields; `LocationDto` fields; a Testcontainers migration
test (columns, CHECK, table, FK cascade, data block on a seeded roster sets the expected points).

**Docs:** `hot-topics-tides-eclipse.md` (tide data), `database-migrations.md`,
`api-endpoints.md` (`GET /api/locations` fields), `configuration.md` if anything is configurable.

**Owner steps after deploy:**
1. Check the migration: `SELECT name, tide_lat, tide_lng FROM locations WHERE tide_lat IS NOT NULL;`
   — expect the ten.
2. Press tide **Run Now** (or `POST /api/forecast/run/tide`; it answers 409 while a refresh is
   running). The ten re-seed with `POINT_CHANGE` — about 14 credits each, ≈140 in all. The others
   take their normal tail. If skipped, the Monday 02:00 UTC refresh does the same.
3. Let one briefing cycle run, so `daily_briefing_cache` and `marine_high_water` (a 7-day copy of
   high-water times) rebuild from the repaired rows. Nothing else holds December tide data yet:
   evaluations reach T+3 at most, `topic_daily_log` logs yesterday only, Coming up is recomputed
   per serve, and `TideSizeIndex` is evicted on every tide write.
4. Re-run the roster query from this investigation: the ten should show the lag of the location
   they borrow from.

### P2 — Diff-on-write detection and source-change alerts

- **Tail overlap.** The tail fetch starts **7 days** before the frontier instead of 1 minute
  (`TAIL_OVERLAP`), so every weekly fetch re-buys one stored week: billing is `ceil(days/7)`, so
  +1 credit per location per week (≈61/week, ≈$0.03).
- **Diff before delete.** Before `deleteByLocationIdAndEventTimeBetween`, load the stored extremes
  in the overlap, pair them with the new ones by type within ±3 h, and compute the maximum timing
  and height deltas. **A change is > 5 min or > 0.05 m on any pair.** Skipped for `POINT_CHANGE`
  and `SEED` fetches (a change is expected / nothing to compare). `RESEED` fetches compare across
  their whole window for free.
- **Store and alert (D4).** The new values are written as today. The `tide_fetch` row records
  `overlap_pairs`, `max_lag_delta_min`, `max_height_delta_m` (V206 `ALTER`). When a location's
  fetch shows a change, or its `atlas`/`station`/response point differs from its previous
  successful forward row at the same request point, `AdminAlertService` sends one email per
  refresh listing every such location with before/after examples, and saying that the fix is a
  tide point. Sent once per change, not every week: a location is alerted again only after a fetch
  that showed no change.
- Runs inside the refresh, so the schedule, Run Now and the admin endpoint are all covered and
  there is no ordering problem.

**Tests:** overlap window and credit maths; pairing and thresholds; no diff on `POINT_CHANGE` /
`SEED`; re-seed diffs its whole window; alert on change, no alert on a clean fetch, no repeat alert
for an unchanged persisting difference; atlas/station change alert; V206 migration test.

**Docs:** `hot-topics-tides-eclipse.md`, `database-migrations.md`, `platform.md` (admin alerts).

### P3 — Dimmed shoulder bars on Coming up tide runs

**Goal.** A run whose edge misses the spring bar narrowly shows that day beside the run as an
**outlined** bar. The run's stated dates do not change: 8–10 Nov stays "8–10 Nov", with 7 and
11 Nov drawn outlined. ("Dimmed" already means "no forecast yet" in this row, so shoulders get
their own treatment.)

**Definition (server-owned).** A *shoulder day* is the day immediately before the run's first day,
or immediately after its last, that did not qualify, whose **roster-best ratio** is **≥ 1.20**
(`ComingUpScoringProperties`, with the per-side cap). **One per side** (cap configurable, default
1): it keeps the row on one line on a phone and is identical on every width, and it is the case
that prompted this. Roster-best ratio is `max over coastal locations of 1.25 × dayMaxHigh /
springThreshold` — computed from the same rounded threshold `measureUncached` tests, so a ratio
> 1.25 holds exactly when the day is spring; locations with a null threshold are skipped.
Roster-wide, because that is the test that set the run's span; the bar still shows the
representative's range, like every other bar. A shoulder whose range the representative cannot
derive is dropped. A run with no qualifying day (11 Dec) is still not shown — the feed states
measured events, not near misses. Two runs separated by one non-qualifying day may both claim it as
a shoulder; that is correct (it is beside both).

The calibration figures (7 and 11 Nov at 1.245/1.243; 8 Jan at 1.245) are from pre-repair data.
P3's code does not depend on P1, but its December–January shoulders are only meaningful after the
P1a repair has run in production; 1.20 is a configurable starting point.

**Backend.**
- `TideSizeIndex.Sizes` gains `Map<LocalDate, Double> bestRatio` (compact constructor normalises
  null to `Map.of()`), filled in `measureUncached` from the day maxima it already reads. The
  unmeasured fallback has an empty map → no shoulders. The measured window is padded by
  `MAX_RUN_WALK_DAYS + 1` so a probe after a full walk is still measured. All `new Sizes(...)`
  call sites updated.
- `TideAlmanacSource.completeRun` records `shoulderBefore`/`shoulderAfter` (nullable dates) on
  `Run`; `toEvent` carries them in meta as `shoulderDates` (comma-separated ISO). The event's
  `startDate`/`endDate` are unchanged.
- `ComingUpAssembler`: `shoulderDates` joins `droppedKeys` in `enrichTide` (build-time only,
  never served). `dayRangesOf` derives ranges for run dates plus shoulder dates at the same
  representative; the "fewer than 2 derivable days" rule filters on run dates explicitly.
  `ComingUpDayRange` gains `@JsonInclude(NON_DEFAULT) boolean shoulder`; `rankDays` rebuilds each
  range and **must carry `shoulder`**. Shoulders are ranked like the rest (the percentile is a fact
  about that water) but never `peak` (automatic: `peakDate` lies inside the run).
- `mergeEntries` copies the flagged ranges. On a merged supermoon entry a shoulder date may lie
  inside the union span; it stays flagged.
- A shoulder bar may be taller than the run's peak bar: the run is decided on roster high-water
  height, the bar shows the representative's range. Accepted and pinned by a test — the "biggest"
  label stays the run's peak.
- No Lite shaping (a range fact, not tide against the light); no cache-key change.

**Frontend.**
- `buildDayChips` walks the **extended** span (first shoulder to last), so month-crossing labels
  stay right; a chip is a shoulder when its served range carries `shoulder`. Shoulders **before
  today** are dropped (client side, via `appNow()`, beside the existing `gone` logic). This is a
  filter over served values: added to the licensed list in `architecture-decisions.md` beside
  `buildRangeScale`. The chip-count caps count run days only.
- `buildRangeScale` includes shoulder values.
- Render: column `data-shoulder="true"`, `data-tier="shoulder"` (overrides peak/big), bar drawn as
  an **outline** (no fill) in the column's normal ink; date in normal ink; no "top N%" pill, no
  PEAK; AVG segment still drawn. `data-live="false"` still applies its own muting to the date as
  today. On a merged supermoon chip the full-moon mark keeps its colour; only the bar is outlined.
  The row's note gains "outlined · just short of spring" when any shoulder is shown.
- Accessible name: `nameOf`'s existing format plus ", just short of spring size", without the rank;
  the tooltip likewise ("x.x m range · just short of spring size").

**Wire doc.** `coming-up-plan.md` §13 gains `dayRanges[].shoulder`; `shoulderDates` is documented
as build-time meta that is never served. Update `api-endpoints.md` and the Coming up section of
`hot-topics-tides-eclipse.md`.

**Tests.** Backend: ratio boundary agrees with `springOn`; shoulder ≥ 1.20 accepted, < 1.20 not;
one per side; unmeasured → none; a no-run near-miss emits nothing; underivable shoulder dropped;
`shoulderDates` not served; `rankDays` keeps the flag; `peakRankLabel` ignores shoulders; merge
keeps flags (outside and inside the union span); a shoulder taller than the peak; JSON pins
(`shoulder` absent on run days, `true` on shoulders). Frontend: extended walk incl. month crossing;
shoulder before today dropped while a run is in progress; caps unaffected; scale includes
shoulders; tier/pill/PEAK/aria/tooltip; note text; merged supermoon chip. Browser check at desktop
and 375 px.

### P1b — Admin tide point (API and UI)

- `UpdateLocationRequest` gains `tideLat`, `tideLng`, `clearTidePoint`, mirroring the valley-floor
  triple (both or neither; clear rejected alongside values).
- `LocationService.update`: when the effective tide point changes, call
  `fetchAndStoreTideExtremes` straight away (a `POINT_CHANGE` re-seed, ≈14 credits), so an edit
  takes effect without waiting for Monday.
- `LocationManagementView`: a "Tide point" pair with a one-line explanation ("Where tide times are
  read from, when the pin's own sea area is unreliable") and a clear button, filled from the DTO.
  Admin-only; no role-gating change. UI review cadence applies.

### P4 — Gauge check (model-drift monitoring)

A weekly check of WorldTides' predictions against measured water at the five Environment Agency
gauges. It cannot catch a cell change at a location without a gauge (P2 does that) and it sees an
error only after the event has passed; its job is to show the model's accuracy where there is
ground truth, and to catch drift there.

- Runs at the end of the tide refresh (no separate schedule).
- Measures (configuration, `photocast.tide.gauges`): North Shields
  `E70939-level-tidal_level-Mean-15_min-mAOD` → Tynemouth; Blyth
  `022999-level-tidal_level-i-15_min-mAOD` → Blyth Beach; Tees Dock
  `L3901-level-tidal_level-i-15_min-mAOD` → South Gare & Paddy's Hole; Whitby
  `E71039-level-tidal_level-Mean-15_min-mAOD` → Whitby Piers; Bridlington
  `L3001-level-tidal_level-i-15_min-m` → Bridlington North Beach.
- Reads the last 7 days (`startdate`/`enddate`, `_sorted`, explicit `_limit`), extracts high
  waters (local maximum over ±2 h with five equally spaced readings, five-point quadratic peak),
  pairs each with the stored prediction at the mapped location (±2 h), and records the median
  timing and height error per pair on a `tide_gauge_check` row (V207). Skips a pair with fewer than
  8 paired high waters.
- Flags a pair whose median timing error moves more than 10 min from its own trailing median over
  the previous 4 runs (the absolute error is not zero: gauges sit in harbours; "Mean" readings may
  be stamped at either end of their period). No flag until 4 runs exist.
- Alerts through `AdminAlertService` on a new flag only. An EA failure is logged and skips the
  check; it never fails the refresh.
- RestClient with timeouts, like the other clients. Environment Agency data is Open Government
  Licence v3: readings are not stored; the summary carries the attribution string.

**Docs:** `configuration.md`, `hot-topics-tides-eclipse.md`, `database-migrations.md`.

## 4. Open questions

None blocking. Thresholds (P2 5 min / 0.05 m, P3 1.20, P4 10 min) ship as constants with their
reasoning in Javadoc and can be tuned after a few weeks of `tide_fetch` data.
