# Big seas and the stand-down hatch: build plan

Owner ask, 2026-10-22: a storm on **Saturday 24 Oct** never showed on the Map tab. The handoff in
`docs/design/big-seas/` (README, prototype `Map Storm Stand-down.html` + `map-storm.js`, five
screenshots) adds two things to the Map tab:

1. **Big seas.** "Should I go to the coast to see waves breaking over the piers and lighthouses?" A
   line under the window control and a teal `⚡` chip on each qualifying pier or lighthouse. High water
   on the sunrise or sunset is the window to drive for.
2. **Stood down, drawn.** A cool-grey hatch over stood-down ground, with the region and cause named
   on it, so a region the weather ruled out no longer looks like an empty one.

The two are independent. Each phase below is **one PR**, built by a **Sonnet subagent** in its own
worktree from this plan, reviewed by the planning session, then taken through CI and Codex
(CLAUDE.md, *How every code change is made*). UI phases follow the UI review cadence.

---

## §1 Corrections to the handoff (read before building anything)

The handoff was written against the prototype. Against the code, these hold:

1. **Do not edit V123.** The handoff asks to fix V123's stale comment ("sampled at the aligned
   high-water time"). Production happens to run `validate-on-migrate: false`
   (`application-prod.yml`), so the edit would not fail there. But CI's Flyway migration tests
   validate, and applied migrations are history. Fix the wording in the `MarineWaveEntity`/
   `MarineClient` Javadoc and say so in V188's header instead.
2. **Whitby already has a piers location.** `Whitby Piers` exists (V60, id 75). Use it; the "Whitby
   Abbey vs a new location" question goes away.
3. **Eyemouth is not in the database.** It's only in a design mock. B1 seeds **seven** structures
   that exist (the handoff's five, plus Seaham North Pier & Lighthouse and Hartlepool Headland; Q4). Souter Lighthouse
   stands a little inland, so it is not a structure.
   Eyemouth waits until the owner adds it in the admin UI.
4. **Real names differ from the mock, and four of them are unproven.** `Roker Pier & Lighthouse`
   is inserted by V138. `Amble` (id 21), `St. Mary's Lighthouse` (28), `Saltburn-by-the-Sea` (55)
   and `Whitby Piers` (75) appear only as trailing `-- name` comments on V60's `WHERE id = N`
   updates; they may have been renamed in the admin UI since. **Step 0 (§3 B1) confirms them on
   production before V188 is written.** The chip's short label is a new column, not a string the
   client invents.
4a. **Roker is SUNRISE-only** (V138's `location_solar_event_type`). Big seas ignores a location's
   solar event types: waves are waves at any time of day, so a Roker big-seas day can be aligned
   with *sunset*. The chip still appears; the location's ordinary sunset rating is absent as today.
5. **A bearing column already exists.** `locations.shore_normal_bearing_degrees` is populated for
   all five, but it's the *shore's* normal (Roker 90°, Saltburn 45°), not the direction the
   *structure* faces (handoff: Roker 45°, Saltburn 0°). Add a separate `structure_faces_deg` (Q3).
6. **The horizon is the briefing's five days** (today + 4, `BriefingService.BRIEFING_WINDOW_DAYS`),
   not the marine fetch's seven. The Map tab can only jump to windows it has.
7. **Stand-down on the Map is per place, not per region.** The prototype hard-codes stood-down
   *regions* (`STORM.sd`). In production a place is stood down when its score row has a
   `triageReason` and no rating (`utils/standDown.js` `resolveStandDown`, `utils/heatSpots.js`
   `windowState` → `stood`). The hatch is drawn over **stood places**, under the field, so a rated
   neighbour's colour wins. A region label is drawn only when the region has stood places, no rated
   place, and no served mean (§3 F3).
8. **There is no "storm" cause.** `triageReason` is `HIGH_CLOUD | PRECIPITATION | LOW_VISIBILITY |
   TIDE_MISALIGNED | GENERIC`. The slot `standdownReason` string the handoff names is never read by
   the frontend. The Map reads `triageReason`, so the cause word maps from that (Q2). Saturday's
   storm will read `rain` or `cloud`, not `storm`.
9. **Most of the prototype's `pool` / `regionsFor` / `evVerd` change is already production
   behaviour.** Stood places are already out of the heat pool (`heatPointsFor` keeps rated points
   only). Region means and the pill verdict are **served** (`utils/mapVerdict.js`; the module bans
   client thresholds). Do not re-derive them. When the whole scope is stood down, no in-scope region
   has a finite `meanRating`. `windowVerdict` then returns null (`pickTopEligibleRegion`), and the
   pill shows **no verdict word at all**. The handoff wants "Stood down" (Q5).
10. **The footer has a different shape now.** It reads "**N** of K shown · M rated" (thin-sample F4).
    Append ` · N stood down` to it; don't rebuild the prototype's footer.
11. **This reverses an owner decision made today.** Thin-sample plan Q1 (2026-10-22): "Stand-downs:
    plain ground, not hatched and not a third look." The stand-down hatch *is* a third look, so
    The owner overruled it for this series (Q1, 2026-10-22).
12. **The "day-level summary" can mislead.** Taking the earliest *from* and the latest *to* across a
    day's qualifying high waters can produce `07:10–18:15` from two separate high waters. The served
    day summary is built from **one lead group** of overlapping high-water windows instead (§3 B2,
   Q6).
13. **Handoff behaviour deliberately not built in this series** (ask the owner to add any back):
    - The stand-down hatch on the storm day's **night** rows. Night windows have no triage
      stand-down.
    - The footer's cause word (`· storm`). The cause is named on the ground; the footer says
      ` · N stood down`.
    - The **phone** week-menu ⚡. The phone's window list (peek sheet) has no topics column.
    - The stand-down hatch draws **under** the field, not after it as the handoff says (§3 F3,
      rated colour must win).
    - The big-seas line is **504px**, not 580 (the label-obstacle budget).

---

## §2 What must not change

- `marine_wave` and its sunrise/sunset samples. The king, spring and storm-surge pills,
  `StormSurgeFactsBuilder`, `TideRunBuilder`, `CoastalTideFactsBuilder` and
  `WindowTideRollupBuilder` keep reading them unchanged.
- Served verdicts, `meanRating`, picks and the `mapVerdict.js` channel. No client mean or threshold.
- The thin-sample "not rated yet" hatch, its legend keys and its gates. Stood places stay out of
  `unratedPointsFor`.
- Hot topics, Coming up, and the Plan tab apart from F4's stand-down hatch. Big seas itself is
  **Map-only** in this series (Q7).
- Desktop and tablet behaviour of anything this series doesn't name; phone-only rules stay phone-only.
- Open-Meteo call count. The marine call gains two variables (5 in total, still under the
  10-variable weight step), so it stays one weighted call per coastal location per cycle.

---

## §3 Phases

```
B1 (data) → B2 (rule + served block) → F1 (line + week-menu ⚡) → F2 (chips, tooltip, legend key)
F3 (stand-down hatch, label, legend key, footer) → F4 (Plan-tab thumbnails hatch stood ground)
```

F3 can be built in parallel with B1 as soon as Q1 is answered. Every phase branches from the updated
`main` after its predecessor merges.

### B1 · Sample the sea at every daylight high water (backend, data only)

**Step 0, before the brief: the owner runs one read-only query on production** (the
`prod-db-query-recipe`: `docker exec -i goldenhour-db psql -U goldenhour -d goldenhour`):

```sql
SELECT l.id, l.name, l.enabled, string_agg(DISTINCT t.tide_type, ',') AS tides,
       string_agg(DISTINCT s.solar_event_type, ',') AS solar
FROM locations l
LEFT JOIN location_tide_type t ON t.location_id = l.id
LEFT JOIN location_solar_event_type s ON s.location_id = l.id
WHERE l.id IN (21, 28, 55, 75) OR l.name ILIKE ANY (ARRAY['%roker%','%mary%','%amble%',
      '%saltburn%','%whitby%','%eyemouth%','%seaham%pier%','%hartlepool%'])
GROUP BY l.id, l.name, l.enabled ORDER BY l.id;
```

Paste the result into §6. V188 seeds the **exact** names returned. Each must have a non-empty tide
type: `refresh` filters on it before any structure code runs, and tide extremes are fetched only for
coastal locations. A structure without one silently gets nothing, so fix it in V188 or drop it.
**Migration `V188__big_seas_high_water.sql`** (confirm the number with
`ls backend/src/main/resources/db/migration/ | sort -V | tail -1` on `main` first):

- `locations` gains three nullable columns: `coastal_structure VARCHAR(20)` (PIER | BREAKWATER |
  LIGHTHOUSE), `structure_faces_deg INTEGER` (0–359, with a CHECK) and `structure_label VARCHAR(40)`
  (the chip's short name, such as "Roker Pier").
- Seed by **name** (never by id, the V60 mistake), using the Step 0 names and the handoff's faces:
  `Roker Pier & Lighthouse` PIER 45 "Roker Pier" · `St. Mary's Lighthouse` LIGHTHOUSE 90 "St Mary's"
  · `Amble` PIER 45 "Amble piers" · `Saltburn-by-the-Sea` PIER 0 "Saltburn Pier" · `Whitby Piers`
  PIER 0 "Whitby piers" · `Seaham North Pier & Lighthouse` PIER 90 "Seaham Pier" · `Hartlepool
  Headland` BREAKWATER 90 "Hartlepool" (owner, 2026-10-22). Use the exact names Step 0 returns.
- End with a post-flight `DO $$ … $$` count of seeded rows, in the V138:161-175 shape, using
  `RAISE WARNING` (not `EXCEPTION`, which would block a production restart) when the count isn't 7.
- New table `marine_high_water`. Exact SQL ↔ Java types are binding, because production runs
  `ddl-auto: validate` and a mismatch fails startup only in CI or production:

  | column | SQL | Java |
  |---|---|---|
  | `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | `Long` |
  | `location_id` | `BIGINT NOT NULL`, FK → `locations(id)`, no cascade (as `marine_wave`) | `@ManyToOne(LAZY) LocationEntity` |
  | `hw_time` | `TIMESTAMP NOT NULL` (UTC, no zone, as `tide_extreme.event_time`) | `LocalDateTime` |
  | `hw_height_m` | `DECIMAL(6,3)` | `BigDecimal` (as `TideHighWater`) |
  | `window_start`, `window_end`, `peak_time` | `TIMESTAMP NOT NULL` (UTC) | `LocalDateTime` |
  | `wave_height_m`, `wave_period_s`, `swell_wave_height_m`, `swell_wave_period_s` | `DOUBLE PRECISION` | `Double` |
  | `wave_direction_deg` | `INTEGER` | `Integer` |
  | `evaluated_at` | `TIMESTAMP WITH TIME ZONE NOT NULL` | `Instant` |

  Add `UNIQUE (location_id, hw_time)` and an index on `hw_time`. The header says the table stores
  **every** daylight high water, qualifying or not, so the thresholds can be tuned against it.
- The header also corrects V123's wording in prose (§1.1).

**Entity / enum:** `CoastalStructure` enum; three fields on `LocationEntity` (ordinary updatable
columns, migration-written only; no admin UI in this series, the same precedent as
`shore_normal_bearing_degrees`); expose them on `LocationDto`. `LocalH2EnumWidener` is **not**
needed: `ddl-auto: update` creates a new column with the whole enum, and the widener is for a value
added later. New `MarineHighWaterEntity` + `MarineHighWaterRepository`. Its delete is a **bulk
`@Modifying @Query`**, mirroring `TideExtremeRepository.deleteByLocationIdAndEventTimeBetween`. A
derived `deleteBy…` won't do: it loads and removes rows one by one, and Hibernate flushes inserts
before deletes, so the re-insert of an unchanged `hw_time` would hit the UNIQUE key.

Local profiles don't run Flyway, so local H2 never gets the seeds. Add a `src/local` seeder
(`@Profile("local & !prod")`, the `AskLocalFixtureSeeder` precedent) that sets the structure columns
on whichever of the seven names exist locally, and inserts a handful of `marine_high_water` rows for
today+1 (one aligned with sunset, Hs 4 m from 30°, 11 s) so F1/F2 can be seen in a browser.

**Fetch:** `MarineClient.HOURLY_PARAMS` += `wave_period,swell_wave_period`, and
`OpenMeteoMarineResponse.Hourly` gets the two lists (`List<Double>`). New
`MarineClient.peakInWindow(response, fromUtc, toUtc)` returns a new record `MarineWavePeak(peakTime,
waveHeight, direction, period, swellHeight, swellPeriod)`: the hour with the highest `wave_height`
among hourly stamps inside `[from, to]`, falling back to the hour nearest the midpoint when none
falls inside (a window clipped to under an hour). Ties go to the earlier hour. Return empty on a land
cell, as `sampleAt` does. `MarineWaveSample`/`sampleAt` are unchanged.

**Refresh:** `MarineWaveRefreshService.refresh` keeps the sunrise/sunset loop untouched. For each
coastal location with `coastal_structure != null`, it reuses the same fetched response (no extra
call). It then:

1. Reads high waters through `TideExtremeRepository.findHighWatersInWindow` over
   `[ForecastHorizon.ukDayStartUtc(first), ForecastHorizon.ukDayStartUtc(last.plusDays(1)))`. No
   padding is needed: a high water near UK midnight can't reach daylight at these latitudes.
2. Buckets each HW by `ForecastHorizon.civilDate(hw.toInstant(ZoneOffset.UTC))`. **Never**
   `ZoneId.systemDefault()` or a fixed offset: Sunday 25 Oct 2026 is the BST→GMT change, a 25-hour
   UK day, on the storm weekend itself.
3. **Daylight:** window = `[hw − 90 min, hw + 90 min] ∩ [civilDawnUtc, civilDuskUtc]` of that civil
   date (`SolarService.civilDawnUtc/civilDuskUtc`). Skip the HW when the intersection is empty. This
   covers the handoff's "night HW whose window reaches daylight": it's kept and clipped. The peak is
   taken inside the clipped window. If the clipped window holds no hourly stamp, `peakInWindow`'s
   nearest-hour fallback may pick a stamp just outside it. That's accepted.
4. **Delete then insert, one transaction per location.** The bulk delete covers that location's
   `hw_time` range, then fresh rows are inserted. High-water times move when tides are re-fetched,
   and a stale `hw_time` row must not survive. Put both in a `@Transactional` method on a
   **separate bean** (`MarineHighWaterWriter`), because self-invocation doesn't proxy. The HTTP
   fetch stays outside it. A concurrent `/api/briefing` serve then sees either the old rows or the
   new ones, never none. Refresh runs only under `BriefingService`'s refresh lock, so there's no
   writer race.
5. One INFO log per location: `Big-seas samples: {name} {n} daylight HWs`.

Never fatal: it shares the existing try/catch around the refresh in `BriefingService`.

**Tests:** `MarineClientTest` (peak inside the window, tie → earlier, fallback to nearest, land cell,
missing period list); `MarineWaveRefreshServiceTest` (daylight clip at both ends, a night HW
excluded, a night HW reaching dawn included and clipped, a non-structure coastal location gets no
HW rows, stale rows deleted, sunrise/sunset rows unchanged, fetch failure isolated).
**Clock-change fixtures are required:** a morning HW on Sat 24 Oct 2026 (BST) and on Sun 25 Oct
(GMT), each clipped against its own date's dawn, plus `ukDayStartUtc(25 Oct) = 24 Oct 23:00Z`.

A Flyway migration test in the `NorthernBluebellWoodsMigrationTest` shape:

- Migrate to V187. V138 has already inserted Roker, Seaham North Pier & Lighthouse and Hartlepool Headland, and
  `locations.name` is UNIQUE, so insert only the **other four** names, plus a decoy with a similar name (e.g. `Whitby Abbey`). Copy V138's
  INSERT column list for the NOT NULL columns.
- Migrate to V188.
- Assert exactly those seven are seeded with the right faces and labels, and that the decoy isn't.
- Assert the `marine_high_water` UNIQUE key and FK.

**Coverage (JaCoCo 80% per class):** the new entity, properties and response accessors count. Cover
them through the service and repository-shaped unit tests, not by excluding them.

**Docs:** `reference/database-migrations.md` (V188). Also add a short marine paragraph to
`reference/hot-topics-tides-eclipse.md`, which has none yet: the two carriers `marine_wave` (event
grain) and `marine_high_water` (HW grain).

### B2 · The rule and the served `bigSeas` block (backend)

**Config:** `BigSeasProperties`, a `@Component @ConfigurationProperties("photocast.big-seas")`
with **Java field defaults** and clamping, copying `NeighbourGuardProperties`. Host
`application.yml` is gitignored, so defaults can't live only in YAML. The fields are
`minWaveHeightM = 3.0`, `maxOnshoreAngleDeg = 60`, `minWavePeriodS = 8` and
`halfWindowMinutes = 90`. Document them in `application-example.yml` and
`reference/configuration.md`. B1's ±90 constant moves onto this property. A change to it affects
only later refreshes.

**`BigSeasRule`** (pure, no Spring I/O): qualifies when:

- `wave_height ≥ min`;
- the circular difference `|((dir − faces + 540) mod 360) − 180| ≤ max-angle`;
- `period ≥ min`;
- the window is non-empty.

Any null input means it doesn't qualify. **Evaluated at serve time from the stored numbers, never
persisted.** This is the same principle as V123's sea-state band, so retuning needs no refetch.

**`BigSeasBuilder`**, modelled on **`WindowTideRollupBuilder`** (the DB-per-serve precedent,
ServedBriefingAssembler.java:166-167). `WindowTideFactProjector` is pure and is not the model.

- Make it fail-soft: `build()` wraps `buildOrThrow()`, logs, and returns null. A DB error must never
  500 `/api/briefing`.
- **One query.** A JPQL constructor projection joins `marine_high_water` to the location's current
  `coastal_structure`, `structure_faces_deg`, `structure_label`, `lat`/`lon` and `enabled`. Filter
  on structure and faces non-null and `enabled = true`, so rows for a structure that has since been
  un-set are ignored. No N+1 lazy loads. It runs on every serve, including revalidations, which is
  fine at ~50 rows.
- **Days served:** the intersection of the served briefing's `days` dates and
  `ForecastHorizon.today(clock)`…+4. Drop any (structure, HW) whose `window_end` is before
  `LocalDateTime.now(clock.withZone(ZoneOffset.UTC))` before grouping, so a morning window already
  over doesn't lead today. The server decides; the client never filters by time.
- **Aligned** (per structure): the structure's clipped window contains its own UK date's
  `sunriseUtc` or `sunsetUtc` (at the structure's lat/lon). The location's solar event types are
  ignored (§1.4a).
- **Grouping:** sort a day's qualifying (structure, HW) pairs by `window_start`. Sweep transitively,
  starting a new group when a window starts after the group's running max end. Pick the **lead
  group** by: any aligned member, then most structures, then highest wave, then earliest start.
- **Lead group fields:**
  - `windowStart` / `windowEnd`: the union of its members' windows (earliest start, latest end;
    the handoff's day summary, now bounded to one group).
  - The **anchor member**: the aligned member with the smallest |gap| if any, otherwise the
    highest wave. It supplies `hwTime`, `alignedEvent`, `gapMinutes` and `gapSide`.
  - `waveHeightM`: the group's maximum. `waveDirectionDeg` and `wavePeriodS` come from the
    max-wave member.
- **Gap:** `|hw − event|`, rounded to the **nearest** minute (not `Duration.toMinutes` truncation),
  served as a positive number plus `gapSide` `BEFORE | AFTER` (HW before or after the event). Don't
  also serve a signed value.

**Wire shape.** Times are **`LocalDateTime` UTC with no zone**, matching `solarEventTime` and
`generatedAt` elsewhere in the briefing, so the client reuses its UTC-naive parser.

```
BigSeasDay {
  date                         // UK civil date
  windowStart, windowEnd       // LocalDateTime UTC, the lead group's union
  hwTime                       // anchor member's HW, LocalDateTime UTC
  alignedEvent                 // SUNRISE | SUNSET | null
  gapMinutes, gapSide          // null unless aligned
  waveHeightM, waveDirectionDeg, wavePeriodS
  structures: [ { locationId, label, structure, waveHeightM, waveDirectionDeg, wavePeriodS,
                  facesDeg, hwTime, aligned } ]   // lead group only
  otherWindows                 // count of further qualifying groups that day (Q6; unused by F1)
}
```

**`DailyBriefingResponse`** is a 16-component record with convenience constructors and five
withers, and 26 files construct it. Make these changes:

- Add a 17th component `@JsonInclude(NON_NULL) List<BigSeasDay> bigSeas`.
- Keep a 16-argument constructor that defaults it to null, so the 26 call sites don't change.
- Add `withBigSeas`, and make **every** existing wither carry the field.
- Attach it as the **last** step of `ServedBriefingAssembler.assembleForPlan`, after
  `stripSlotTide`.

It is null on every stored and internal path, so it never reaches `daily_briefing_cache`. A test
must prove the cached payload has no `bigSeas` and the served one does.

**Logging:** one DEBUG line per (structure, HW) with every input and the verdict, and one INFO per
qualifying day. The table holds every input, so tuning is a SQL query; §6 gets it once B1 is live.

**Tests:**

- `BigSeasRuleTest`: each threshold at the boundary on both sides; the angle wrap (350° vs 10°, and
  `dir = 360`); nulls.
- `BigSeasBuilderTest`:
  - grouping: overlapping, chained and disjoint windows;
  - lead order;
  - anchor choice;
  - the gap rounding to nearest, with the side;
  - a past window dropped;
  - a structure qualifying only in a non-lead group isn't listed;
  - `otherWindows`;
  - a disabled or un-set structure ignored;
  - fail-soft returns null.
- `ServedBriefingAssemblerTest`: attached on serve, absent from the cached payload, survives every
  wither.
- `JsonDateFormatContractTest`, through `/api/briefing` (it is `@SpringBootTest`): pin the format of
  the new times.

**Docs:** `reference/api-endpoints.md` (the field under `/api/briefing`), `reference/configuration.md`.

### Common frontend plumbing (part of F1)

- **Data path:** `WindowFirstMapPane` reads `briefing?.bigSeas` and passes it to `MapView` as a new
  `bigSeas` prop (PropTypes; a module-level stable `EMPTY` default). `MapView` hands it to
  `BigSeasLine`, `WindowControl` and (in F2) the chip builder. No other context changes.
- **Copy helpers** (`utils/bigSeas.js`):
  - Times use the existing `formatEventTimeUk`.
  - Compass words come from a **new 16-point** sibling of `utils/conversions.js`'s 8-point
    `degreesToCompass` ("NNE"). Leave the 8-point one unchanged.
  - The gap is `gapMinutes` as served (already positive) + ` min before|after sunrise|sunset`. It
    is always minutes; it can't exceed 90.
- **Tokens:** `--color-tide` (`#6FA8B0`) already exists in `@theme` (index.css:45). Add only the
  missing ones in the same `--color-*` convention: `--color-tide-light` `#8FC0C7`,
  `--color-tide-pale` `#A9D6DC`, and (F3) `--color-standdown-ink` `#D3DAE8`,
  `--color-standdown-ink-2` `#B8C5DC`, `--color-standdown-count` `#C9D2E4`. Never `#f59e0b` on the
  map.

### F1 · The big-seas line and the week-menu ⚡ (frontend)

- `bigSeasFor(bigSeas, evRow, events, now)` (pure, filter/select only; `now` = `appNow()` so Rewind
  works) returns `{ form: 'full' | 'compact' | null, day, aligned, jumpRow }`:
  - **full**: the current EV row's UK date has a `BigSeasDay` whose `windowEnd` is after `now`
    (solar or night row of that day).
  - **compact**: otherwise, the first later day whose `windowEnd` is after `now`.
  - Otherwise **null**. A past row (`row.past`) gives null.
  - `jumpRow`: `idx = findEvIndex(events, day.alignedEvent, day.date)` and `jumpRow = events[idx]`.
    It's null when there's no aligned event, the index is −1 (today+3/+4 may have no EV row; the
    line then shows with **no action**, as intended), or the reader is already on that row.
- `components/map/BigSeasLine.jsx`, on its own row inside `.wf-map-chrome-tl`, under the window
  control. Copy and visual tokens are verbatim from handoff *Big-seas line*:
  - **full**: `15:30–18:15 · high water 16:58[, 49 min before sunset]` /
    `Waves 4.1 m from NNE onto N piers and lighthouses · watch from high ground, not the pier` and
    action `On the sunset ›`.
  - **compact**: `Saturday 15:30–18:15 · high water near sunset` (`· high water 16:58` when not
    aligned) / `Waves … onto N piers and lighthouses` and action `See Saturday ›`.
  - With N = 1 the line uses the label: `onto Roker Pier`.
  - The badge's `⚡` is `aria-hidden`, and the root is a labelled group ("Big seas"), not
    `role="status"`.
  - **Action:** call the existing `focusAfterJump`-before-`onSelectEv` pattern from
    `MapTideStrip.jsx` (~:438), then `selectEvRow(jumpRow)`. Focus goes to the line's root
    (`tabIndex={-1}`), which stays mounted while the action itself disappears. `selectEvRow` resets
    the star floor and `showStandDown` on a sunrise↔sunset change. That's existing user-choice
    behaviour and is accepted.
  - **Width:** desktop is bounded at the control's **504px**, not the handoff's 580, so the
    `.wf-map-chrome-tl` obstacle doesn't widen (its CSS note at index.css:3808-3822). On phone it's
    full width, wrapping, and the badge doesn't wrap.
  - **Height:** the obstacle does grow taller, so **re-run the label-obstacle harness**
    (`scripts/measurements/label-obstacle/`) with the line shown and record the result in §6.
- **Offsets that assume a 36px control:** `.wf-win-panel` (`top: 50px`, index.css:5813) and
  `.wf-land` (`top: 60px`, phone 56px). Make them read a CSS variable (`--wf-chrome-tl-h`) that
  `BigSeasLine` keeps current through a `ResizeObserver` on the chrome group, with the old constants
  as fallbacks. Test that the panel and the landing card sit below the line when it shows and are
  unchanged when it doesn't.
- **Week menu:** `buildMapEvents` stamps `bigSeasAligned: true` on the EV row matching a day's
  `alignedEvent` (filter/select). `WindowRow` renders `⚡` in the `aria-hidden`
  `.wf-win-row-topics` cell **and** an sr-only ", big seas" clause in the row's main cell (the
  verdict-count pattern at WindowControl.jsx:807). The phone peek sheet's window list has no topics
  column, so there's no ⚡ on phone (§1.13).
- **Tests** (`docs/engineering/frontend-test-standards.md`):
  - `bigSeas.test.js`: full on the day; full on that day's night row; compact before; null after
    the last day; null on past rows; an elapsed today switching to the next day's compact under a
    Rewound `appNow`; jump null when aligned, absent or at index −1; the 16-point compass.
  - `BigSeasLine.test.jsx`: both forms' copy; the gap clause only when aligned; N = 1; the action
    selects `events[idx]` and focus lands on the root.
  - `WindowControl.test.jsx`: ⚡ and the sr-only clause only on the aligned row.
  - A phone cascade test (full width) and the panel/landing offset test.

### F2 · Chips, tooltip and legend key (frontend)

- **Coordinates:** `bigSeasChipItems(day, locationsById, zoom, evRow)` joins the served
  `structures[].locationId` to `locations` by id, **not** `labelSpots`. `labelSpots` drops stood-down
  places (unless `showStandDown`) and anything under the star floor, which is exactly the storm-day
  case. Chips show when `bigSeasFor(...).form === 'full'`, the EV row is **solar**, and zoom < 13.
  They're ringed when `evRow.eventType === day.alignedEvent`. Selection works for a filtered place
  already (`selectedLoc` reads `locations`).
- **Tier** in `MapLabels`' measure-then-place pass: home → rings → Ask picks → regions (and F3's
  stand-down labels) → **big-seas chips** → ordinary chips. This matches the prototype.
- **Suppression:** on a full day, drop the ordinary chip for a structure with a big-seas chip,
  **except** an `askPick` chip or the selected chip. Those are never suppressed; in that case the
  big-seas chip is dropped instead.
- **Contracts:** big-seas chips carry `data-ask="fade"` while Ask is active. They carry
  `data-testid="map-label-chip"` with a `data-kind="big-seas"` attribute, and `data-selected`, so
  `closeCallout`'s focus target (MapView.jsx:5087) finds them. A click selects the place as an
  ordinary chip does.
- **Accessible name:** an `aria-label` replaces the content, so it carries everything: "Roker Pier,
  big seas, waves 4.3 metres, 15:30 to 18:15[, high water on the sunset]". The `⚡` is `aria-hidden`.
- **Tooltip:** the existing `.wf-maplab-tip`, lines per the handoff: name / `Big seas · 15:30–18:15`
  / `High water 16:58 · 49 min before sunset` (aligned only) / `Waves 4.3 m from NNE, 11 s · faces
  NE, onshore` / `region · drive` (existing helpers).
- **CSS** per handoff *Big-seas chip*. In forced colours the chip has a 1px border and the aligned
  ring is a 2px `outline` (box-shadows vanish there).
- **Legend:** a `⚡` key, "waves onto a pier or lighthouse at high water · ringed when high water is
  on the light". It's shown only while chips are drawn: one condition shared with the chip mount,
  the `viewlineShown` pattern.
- **Pins view too** (owner, Q11). `PinsLayer` runs its own label pass with its own copy of the
  obstacle list. Feed it the same `bigSeasChipItems` at the same tier (before its ordinary labels)
  with the same suppression rules. The legend key needs a mount in Pins: `MapLegendPanel` mounts only
  when `heatView === 'heat'` (MapView.jsx:6531). Give it a `variant='pins'` that renders only the
  big-seas and stand-down keys (F3), mounted in Pins on desktop while either is shown. On phone, the
  Layers section's key row shows the same keys in Pins.
- **Tests:**
  - `mapLabels.test.js`: tier order; suppression and its askPick/selected exceptions; a stood-down
    structure still gets a chip; the zoom cut-off; night rows produce none; the ring only on the
    aligned event.
  - `MapLabels.test.jsx`: render, accessible name, tooltip lines, click selects, Ask fade, the
    `closeCallout` focus return.
  - `MapLegendPanel.test.jsx`: the key iff chips are shown.

### F3 · Stand-down hatch, label, legend key, footer (frontend)

- **Keep the reason.** `buildHeatSpots` keeps only `{rating, stood}` (heatSpots.js:162). Add a
  per-window `reasons` array, read through the same normaliser `hasTriageReason` uses: a string, or
  an object's name/`reason` field (heatSpots.js:69-74). `buildHeatWindowStates` gains a `stood` list
  `{lat, lng, reason, regionId, key}` beside `unrated`, built inside the same `scoresLoaded` gate.
  Past windows get it from their forecast rows.
- **Point set** `stoodPoints` (MapView, beside `unratedPoints`) is that list minus
  `TIDE_MISALIGNED` (not "ruled out by the weather"; Q2).
  - Gates: solar only, past windows included, never astro/aurora. It shows in **Heat and Pins**
    (owner, Q11). In Pins, `MapHeatLayer` clears the field (MapView.jsx Pins branch, MapHeatLayer.jsx
    561-567). Paint the stand-down layer after that clear, with the same rated mask, so it reads
    identically in both views. Its keys use the `variant='pins'` legend mount (built by whichever of F2/F3 lands first; the other reuses it) and the phone Layers
    row.
  - It is **not** gated on `heatWindow.bestRating`. A window where every place stood down has no
    rated place, and that's the window this exists for. Gate on **evidence** instead: the window's
    states hold at least one `rated` or `stood` place (the `unratedPointsFor` test,
    heatSpots.js:262).
  - It is narrowed only by dark sky and the door lens.
- **The unscored line.** When `windowUnscored` is true but `stoodPoints` is non-empty, the "This
  event is not scored yet" line (MapView.jsx:4448-4458) reads `Stood down by the weather — no place
  rated for this event` instead. The ground key's gate (MapView.jsx:6543) and the phone Ground row's
  gate (:6448) also open when `stoodPoints` is non-empty.
- **Canvas.** `utils/heatField.js` `paintStandDown(ctx, stoodPts, ratedPts, map, clip, opacity)`
  paints **under the field, like `paintUnknown`**, not after it. A dark 0.66 fill drawn after the
  field would darken rated places within ~6.5 km of a stood one, because stand-down is per place.
  - Build its mask with `unknownMask`, with stood points in place of unrated, so rated colour wins.
  - Clip it through the **soft path** (`applySoftMask` + `clipGrow`, as `paintUnknown`). A raw hard
    clip erased 7 of 51 coastal places (MapHeatLayer.jsx:114).
  - Fill `rgba(16,15,19,.66)`, then an 8×8 pattern of 1.2px 45° lines in `rgba(184,197,220,.5)`,
    disc radius `radiusFor(map, 6500, 22, 150)`.
  - Order: after the unrated hatch, before the field and the coast stroke.
  - `opacity` carries `fade.heat` and Ask's dim.
  - It must stay distinguishable from the thin hatch (7px, 0.6px, bone .18, no fill). Review both on
    one screen, and review a **mixed** region (rated beside stood).
- **Region label** `standDownRegionItems`. A region qualifies when, over the window's states map
  narrowed only by dark sky and the door lens (not `labelSpots`, not the star floor, not scope),
  **both** hold:
  - it has ≥ 1 stood place and 0 rated places;
  - for a served window, the served region (`regionVerdictIndex`) has **no finite `meanRating`**.
    This guard means the label can never contradict a served rating.
  - Its position, zoom and tier: the centroid of its stood places, zoom < 11.2, the regions tier.
    It **replaces** that region's ordinary label.
  - Text: the region short/tiny name / `stood down · <cause>`. Labels are `aria-hidden` like region
    labels, so the footer suffix is the spoken trace.
- **Cause word:** `STAND_DOWN_CAUSE_WORD` in `utils/standDown.js` maps `HIGH_CLOUD → cloud`,
  `PRECIPITATION → rain`, `LOW_VISIBILITY → murk`, `GENERIC → weather` (Q2). The region's word is the
  most common reason among its stood places; a tie gives `weather`.
- **Licence edit** (`architecture-decisions.md`, *Backend-heavy*, the Map scope class). Add the
  members **(g)** the footer stood count, **(h)** the region stand-down qualification and **(i)** the
  cause vote. Change "Members: those six, nothing else" to "those nine", and change "None of the six"
  to match. Do **not** renumber the classes: the next class is still headed "A SEVENTH class".
- **Legend:** a 20×9 hatch swatch, "ruled out by the weather, cause named on the ground", shown
  while `stoodPoints` is non-empty, in the desktop panel and the phone Ground row.
- **Footer:** append ` · N stood down` in `--color-standdown-count`. N = `scopeBasePool` ∩ the window's
  stood places (minus tide), joined by `heatSpotKey`. That's the footer's own population; the
  footer has no Pins/Heat split. It's omitted at 0. The cause word isn't repeated (§1.13).
- **Pill:** unchanged (Q5).
- **Docs:** amend `map-tab.md:10` ("a stood-down place is never hatched") and the thin-sample plan's
  §1 #2 and Q1 row with a pointer to this plan's Q1 answer.
- **Tests:**
  - Pins: the layer paints after the Pins clear, with the `variant='pins'` legend key shown.
  - Point set: a whole-map stand-down window still hatches; tide-misaligned excluded; nights and
    `scoresLoaded=false` excluded; dark sky and the door lens narrow it; past windows from forecast
    rows.
  - `heatFieldStandDown.test.js`: rated suppression in a mixed region; soft clip; opacity
    passthrough; call order (unknown → stood → field → coast).
  - Label: zero-rated qualifies; one rated 1★ place disqualifies; a served finite mean
    disqualifies; it replaces the region label; majority and tie cause.
  - The unscored line's new wording; the legend and Ground keys; the footer suffix and its absence
    at 0.
  - The forced-colours cascade.

### F4 · Plan-tab thumbnails hatch stood ground (frontend; after F3)

Owner, Q9: the Map and Plan hosts must agree on stood ground, as thin-sample promised for the unrated
hatch (heatSpots.js:249-253).

- The Plan hosts are the card thumbnails and the row map that thin-sample F2 (#1284) hatched. They
  call `paintStandDown` with the **same** `buildHeatWindowStates().stood` list as F3.
- The gates are F3's: solar only, the evidence gate (not `bestRating`), `TIDE_MISALIGNED` excluded,
  the `scoresLoaded` gate, past windows from their forecast rows.
- Same paint: under the field, rated mask, soft clip.
- No region labels or footer on the Plan tab; the thumbnails are too small.
- The Plan legend's two swatches (`rated` / `not rated yet`) gain a third: the 20×9 stand-down
  swatch, "ruled out by the weather", shown only when a visible thumbnail has stood ground.
- Spoken text: thumbnails already carry a hidden sentence. Add "N places stood down by the weather"
  when N > 0.
- **Tests:** a host test per surface (thumbnail, row map) that a stood place is hatched and a rated
  neighbour keeps its colour; the evidence gate on a whole-window stand-down; the legend's third
  swatch on and off; the spoken clause.
- **Docs:** `plan-tab.md` (hatch states), plus the thin-sample plan's "hosts agree" note.

---

## §4 Brief for every Sonnet build

Each brief carries:

- this plan's phase section and §1–§2, plus the design folder `docs/design/big-seas/`;
- "read `docs/engineering/test-improvement-standards.md` (backend) or
  `docs/engineering/frontend-test-standards.md` (frontend) first";
- the gates, run until green, gating on exit codes:
  - backend: `./mvnw checkstyle:check` and the `clean verify` line with
    `-Dtest='!**/integration/**'`. The migration test runs only in CI (no Docker here).
  - frontend: `npm run lint && npm test && npm audit --audit-level=high && npm run build`;
- a `changelog.d/YYYYMMDD-big-seas-<phase>.md` entry;
- the matching `docs/engineering/reference/` update (map-tab.md for F1–F3);
- "never `git checkout --` to undo a mutation; commit nothing; don't touch files outside the phase".

The review is sized to the risk. B1/B2 get two lenses: correctness (time zones, the daylight clip,
the migration's silent no-op) and the test standards. F1–F3 get the UI cadence's six lenses plus
refuters, a browser check on port 8083 where possible, and a statement of what was seen versus only
tested.

---

## §5 Owner answers (2026-10-22)

| # | Question | Answer |
|---|---|---|
| Q1 | Thin-sample Q1 said stand-downs stay plain ground. Does this handoff overrule it? | **Yes, hatch them.** F3 is unblocked; F3 amends the thin-sample docs |
| Q2 | Cause words | `cloud / rain / murk / weather`; tide stand-downs not hatched; a mixed region reads `weather` |
| Q3 | Faces column or the shore normal? | New `structure_faces_deg` |
| Q4 | Which structures? | The five that exist **plus Seaham North Pier & Lighthouse (PIER, 90°, "Seaham Pier") and Hartlepool Headland (BREAKWATER, 90°, "Hartlepool")**. Souter Lighthouse dropped: it stands a little inland (owner, later the same day). Eyemouth later |
| Q5 | "Stood down" on the pill when the whole scope is stood? | Leave it, no word |
| Q6 | Two qualifying high waters in a day | Lead window only; `otherWindows` is served but unused |
| Q7 | Big seas on Plan and Coming up as a hot topic? | Map only |
| Q8 | Role gating | All roles |
| Q9 | Plan-tab thumbnails hatch stood ground? | **Yes, as phase F4** in this series |
| Q10 | Region label and cause vote: client or served? | Client, licensed as members (h)/(i), guarded by the served mean |
| Q11 | Handoff items to add back | **Hatch and chips in Pins view.** Night-row hatch, footer cause and the phone ⚡ stay out |

The thresholds (3.0 m, 60°, 8 s, ±90 min) are config, to be tuned over the winter from
`marine_high_water`. The surge contribution stays out of the rule, per the handoff.

**Timing:** Saturday 24 Oct is two days out. Nothing here can be released in time to show that
storm. If B1 is released soon, though, its table starts recording real high-water sea states for
tuning; the owner cuts releases.

---

## §6 Status

| Phase | PR | State |
|---|---|---|
| Plan | — | drafted 2026-10-22; adversarially reviewed (backend + frontend lenses), all findings folded in |
| B1 | — | Step 0 done; ready to brief once this plan merges |
| B2 | — | after B1 |
| F1 | — | after B2 |
| F2 | — | after F1 |
| F3 | — | ready to brief (Q1 answered); independent of B1–F2 |
| F4 | — | after F3 |

Step 0 result (production, 2026-10-22). All seven structure names match exactly, are enabled and
have a non-empty tide type:

| id | name | tides | solar |
|---|---|---|---|
| 21 | Amble | HIGH | SUNRISE,SUNSET |
| 28 | St. Mary's Lighthouse | HIGH | SUNRISE,SUNSET |
| 55 | Saltburn-by-the-Sea | HIGH,LOW,MID | SUNRISE,SUNSET |
| 75 | Whitby Piers | HIGH,LOW,MID | SUNRISE,SUNSET |
| 243 | Roker Pier & Lighthouse | HIGH | SUNRISE |
| 244 | Seaham North Pier & Lighthouse | LOW,MID | SUNRISE |
| 252 | Hartlepool Headland | HIGH | SUNRISE,SUNSET |

Not structures: Whitby Town (62) and Whitby Abbey (76), which have no tide type. Eyemouth is absent.
The decoy in B1's migration test should be `Whitby Abbey`, a real near-miss name.

Label-obstacle re-measure (F1): _(paste here)_
