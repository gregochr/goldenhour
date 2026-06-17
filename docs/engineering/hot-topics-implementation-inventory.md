# Hot Topics — Implementation Inventory (recon)

**Date:** 2026-06-17
**Scope:** Read-only reconnaissance. Establishes which hot-topic types are
backed by a real detector running on a live cycle vs. which exist only as
admin-simulation templates. No code changed.

**Method:** Read every `*HotTopic*` source file, the `HotTopicAggregator`
registration mechanism, the four real strategy beans, and traced the data
each could read (`forecast_score`, `forecast_evaluation`, briefing cache,
aurora cache, `LunarPhaseService`, `SeasonalWindow`, solar azimuth,
`StormSurgeService`). Where the brief's assumptions disagreed with the code,
the code wins and is flagged.

---

## TL;DR

- **4 of 15 simulatable types have real detectors:** `BLUEBELL`, `AURORA`,
  `KING_TIDE`, `SPRING_TIDE`. Everything else is **sim-only** — a
  `HotTopicSimulationService` template that renders in the admin preview but
  is **never produced by a real cycle**.
- The simulation catalogue at HEAD has **15 entries, not 13** (see
  [discrepancy note](#discrepancy-with-the-brief)).
- **The data trap is real but inverted from what you'd guess:** `DUST`,
  `INVERSION`, and `STORM_SURGE` data is *already persisted* on
  `forecast_evaluation` (dust µg/m³, AOD, inversion_score/potential, full
  surge column set) — these are forecasting features whose data a detector
  could read **today** with no new plumbing. The old "surge not available at
  strategy build time" note is **stale**.
- The genuinely new-data types are the **snow** trio — there is **no snow
  data fetched anywhere** in the backend today.
- The astronomical types (`SUPERMOON`, `EQUINOX`, `NLC`, `METEOR`) are all
  **cheap deterministic detectors** — the primitives they need
  (`LunarPhaseService`, `SeasonalWindow`, solar azimuth) already exist.

---

## The classification table

| Type | Implemented? | Data source (if impl) / would read (if stub) | On current pattern? | If stub: what it needs |
|------|-------------|----------------------------------------------|---------------------|------------------------|
| **BLUEBELL** | ✅ Real (`BluebellHotTopicStrategy`) | `forecast_score` BLUEBELL rows (1–5 Claude rating), Pass-3-repointed off the old `forecast_evaluation.bluebell_score` | ✅ **Modern** — reads normalised `forecast_score`, the reference shape | — |
| **AURORA** | ✅ Real (`AuroraHotTopicStrategy`) | Aurora space-weather cache (`AuroraStateCache` level + `NoaaSwpcClient.getCachedKpForecast()`) + `BriefingAuroraSummaryBuilder` clear-count | ⚠️ Own-source (aurora cache). Correct for aurora; not the `forecast_score` shape and shouldn't be | — |
| **KING_TIDE** | ✅ Real (`KingTideHotTopicStrategy`) | Briefing cache (`BriefingService.getCachedDays()`) for lunar tide classification + `forecast_evaluation` tide-alignment counts | ⚠️ Own-source (briefing cache + deterministic lunar). Fine as-is | — |
| **SPRING_TIDE** | ✅ Real (`SpringTideHotTopicStrategy`) | Same as king tide; suppressed entirely when a king tide is in-window | ⚠️ Same as king tide. Fine as-is | — |
| **STORM_SURGE** | ❌ Sim-only | Would read `forecast_evaluation` surge columns (`surge_total_metres`, `surge_risk_level`, `surge_wind_metres`) — **already persisted** by the forecast pipeline (`ForecastService` writes `data.surge()`) | n/a | **Data exists.** Needs only a strategy that queries surge rows ≥ a risk threshold for coastal locations. No new plumbing. |
| **DUST** | ❌ Sim-only (forecast feature exists, detector does not) | Would read `forecast_evaluation.dust` (µg/m³) + `aerosol_optical_depth` — **already persisted**; the AOD+PM2.5 aerosol proxy already feeds the prompt and the Sahara Dust badge | n/a | **Data exists.** Needs a detector applying the dust-vs-smoke proxy (high AOD / low PM2.5) per day/region. No new plumbing. |
| **INVERSION** | ❌ Sim-only (forecast feature exists, detector does not) | Would read `forecast_evaluation.inversion_score` (0–10) + `inversion_potential` (NONE/MODERATE/STRONG) — **already persisted** by `InversionScoreCalculator` via the pipeline | n/a | **Data exists.** Needs a detector firing on `inversion_potential >= MODERATE` for elevated/overlooks-water locations. No new plumbing. |
| **SUPERMOON** | ❌ Sim-only | Deterministic: `LunarPhaseService.isFullMoon(date) && isMoonAtPerigee(date)` | n/a | **Primitives exist.** Pure calendar/ephemeris detector. Cheap. |
| **EQUINOX** | ❌ Sim-only | Deterministic: fixed date window (~Mar 20 / Sep 22) + solar azimuth ≈ due-east/west (`solarService.sunriseAzimuthDeg`/`sunsetAzimuthDeg`, already used by `ForecastDtoMapper`) | n/a | **Primitives exist.** Date-window + azimuth detector. Cheap. |
| **NLC** (noctilucent cloud) | ❌ Sim-only | Deterministic: a `SeasonalWindow` (late May–early Aug) bean, same pattern as the bluebell window | n/a | **Primitive exists** (`SeasonalWindow` + `SeasonConfig`). Add a window bean. Optionally gate on solar-depression angle for richness, but pure-calendar is enough to ship. |
| **METEOR** | ❌ Sim-only | Deterministic: a static fixed-date table of shower peaks (Perseids, Geminids, etc.), optionally cross-referenced with Bortle-dark locations (already queried for aurora) | n/a | **No primitive**, but trivial static calendar. Cheap. |
| **SNOW_FRESH** | ❌ Sim-only | **No data today.** Would need Open-Meteo `snowfall` / `snow_depth` (overnight accumulation) — not currently fetched | n/a | **New data plumbing** in the Open-Meteo fetch + a field on the briefing/forecast cache. |
| **SNOW_MIST** | ❌ Sim-only | **No data today.** Snow data (above) **+** existing low-cloud/visibility/humidity (already in briefing cache) for the mist signal | n/a | **New snow plumbing**, then combine with existing mist signal. |
| **SNOW_TOPS** | ❌ Sim-only | **No data today.** Would need Open-Meteo `freezing_level_height` vs. location `elevation_m` (elevation already on locations, V65) | n/a | **New data plumbing** (freezing level); elevation already present. |
| **CLEARANCE** (`CLEARANCE`, sim label "Dramatic clearance") | ❌ Sim-only | Would read a cloud-cover *transition* across the hours into the solar event. Briefing cache holds point-in-time low/mid/high cloud %; the pipeline already computes `SolarCloudTrend` (T-3h→event low cloud) per `forecast_evaluation` but for a *building* (worsening) signal, not clearing | n/a | **Mostly concept.** Cleanest path: invert/extend the `SolarCloudTrend` idea into a "clearing" detector reading hourly cloud from the triage/briefing fetch. Data substrate exists (hourly cloud); the clearing-transition computation does not. |

---

## Discrepancy with the brief

The brief lists 13–14 types and `DRAMATIC_CLEARANCE`. The **actual**
`HotTopicSimulationService.ALL_SIMULATIONS` at HEAD has **15** entries:

```
BLUEBELL, KING_TIDE, SPRING_TIDE, STORM_SURGE, AURORA, DUST, INVERSION,
SUPERMOON, SNOW_FRESH, SNOW_MIST, SNOW_TOPS, NLC, METEOR, EQUINOX, CLEARANCE
```

Differences from the brief's list:
- **`SNOW_MIST`** ("Fresh snow with mist") exists as a 3rd snow variant — not
  in the brief.
- The clearance type id is **`CLEARANCE`**, not `DRAMATIC_CLEARANCE` (the
  *label* is "Dramatic clearance").

---

## Registration / aggregation mechanism (how a new strategy plugs in)

- **Interface:** `HotTopicStrategy` — single method
  `List<HotTopic> detect(LocalDate fromDate, LocalDate toDate)`. Contract:
  runs **after** the triage/forecast cycle, **read-only**, **no external API
  calls** (consumes already-persisted data only).
- **Registration:** Spring auto-collects **all** `HotTopicStrategy` beans via
  `List<HotTopicStrategy>` constructor injection into `HotTopicAggregator`.
  **To add a new detector: create one `@Component implements
  HotTopicStrategy`. That's the whole wiring** — it's picked up
  automatically. (This is the "add a visitor following the pattern"
  mechanism.)
- **Aggregation:** `HotTopicAggregator.getHotTopics()` flat-maps every
  strategy's `detect()`, `.sorted()` by `HotTopic`'s natural order (priority
  asc, then date asc), returns the list.
- **Simulation override:** when `HotTopicSimulationService.isEnabled()`, the
  aggregator **bypasses all real strategies** and returns simulated topics
  instead. So enabling sim mode in the admin tab hides whatever the real
  detectors would have produced — a real detector and its sim template never
  run together.

### Where the detected set flows (uniform path for all types)

1. `BriefingService` calls `hotTopicAggregator.getHotTopics(today, today+3)`
   (a **4-day window**), then `bluebellGlossService.enrichGlosses(...)`.
2. Topics are placed on `DailyBriefingResponse.hotTopics` (cached in the
   `AtomicReference` + `daily_briefing_cache` table alongside the rest of the
   briefing).
3. Frontend `DailyBriefing.jsx` renders `<HotTopicStrip>` with the
   `hotTopics` array between the Best Bet cards and the quality slider.

This path is **uniform for all types** — a new strategy inherits it for free.
Note the **window is `today .. today+3`**, so a detector that wants to fire
for events further out won't be queried; deterministic-calendar detectors
must emit within that 4-day window.

### Role gating (new topics inherit it)

- The whole briefing planner is **PRO/ADMIN**; `DailyBriefing.jsx` computes
  `isPro = role === 'ADMIN' || role === 'PRO_USER'` and greys the dummy card
  with `opacity-45 pointer-events-none` + "Upgrade to Pro" for LITE.
- `HotTopicStrip` takes `isLiteUser={role === 'LITE_USER'}`; LITE users get
  non-expandable pills (`canExpand = !isLiteUser && ...`).
- **A new topic type inherits gating automatically** — it flows through the
  same strip and the same `isLiteUser` flag. No per-type gating code needed.

---

## Recommended build order (grouped by effort)

### Tier 1 — cheap deterministic detectors (primitives already exist)
These need no new data and no new plumbing — just a `@Component` reading an
existing service. Highest value-per-effort.

1. **SUPERMOON** — `LunarPhaseService.isFullMoon() && isMoonAtPerigee()`.
   Possibly the single cheapest.
2. **EQUINOX** — fixed date window + `solarService.sunrise/sunsetAzimuthDeg`
   (azimuth ≈ 90°/270°). Reuse the azimuth call already in `ForecastDtoMapper`.
3. **NLC** — add a `SeasonalWindow` bean (late-May–early-Aug) via
   `SeasonConfig`, mirror `BluebellHotTopicStrategy`'s season gate.
4. **METEOR** — static fixed-date peak table; optionally join Bortle-dark
   locations (the `findByBortleClassIsNotNullAndEnabledTrue()` query aurora
   already uses).

### Tier 2 — data already persisted, detector missing
Data is on `forecast_evaluation` today; the detector is the only missing
piece. Slightly more work than Tier 1 because you query/threshold real rows.

5. **INVERSION** — read `inversion_potential`/`inversion_score`; fire on
   `>= MODERATE` for elevated/overlooks-water locations.
6. **DUST** — read `dust` + `aerosol_optical_depth`; apply the existing
   dust-vs-smoke aerosol proxy.
7. **STORM_SURGE** — read surge columns; fire on `surge_risk_level >=
   MODERATE` for coastal locations. **(Confirms the old "surge data not
   cached" blocker is resolved.)**

> Consideration for Tier 2: these columns live on `forecast_evaluation`
> (the enhanced/PRO Claude path), not on `forecast_score`. Decide whether to
> read `forecast_evaluation` directly (as `KingTide` already does for tide
> alignment) or repoint onto a normalised `forecast_score` component the way
> bluebell was — see [open questions](#open-questions-for-chris).

### Tier 3 — needs new data plumbing
8. **SNOW_TOPS** — fetch Open-Meteo `freezing_level_height`; compare to
   `elevation_m` (already on locations). Smallest of the snow three.
9. **SNOW_FRESH** — fetch Open-Meteo `snowfall` / `snow_depth`; detect
   overnight accumulation + a morning that holds it.
10. **SNOW_MIST** — SNOW_FRESH data **+** existing mist signal (low cloud /
    visibility / humidity already in the briefing cache).
11. **CLEARANCE** — needs a *clearing-transition* computation over hourly
    cloud (the substrate exists via the triage fetch / `SolarCloudTrend`
    pattern, but the clearing calc doesn't). Most design-heavy; lowest
    certainty about the exact signal.

---

## What surprised me most

The **data trap is the opposite of the obvious read.** The instinct is
"sim templates make everything look there, so probably nothing else has data
either." In fact three of the unimplemented types — **DUST, INVERSION,
STORM_SURGE** — already have their *data* fully persisted on
`forecast_evaluation` (the forecast pipeline writes dust, AOD, inversion
score/potential, and the entire surge column set every cycle). They are
"forecast feature exists, hot-topic detector does not" cases — exactly the
(a)/(b)/(c)/(d) distinction the brief asked to confirm: each is **(a)
forecast feature + (d) sim stub, but no (b) detector**. So three of the
"remaining hot topics" are detector-only work, not feature work — much
cheaper than they look. Conversely, the snow trio looks simple ("just snow")
but is the *only* group needing genuinely new data ingestion.

---

## Open questions for Chris

1. **Tier-2 read shape:** for DUST/INVERSION/SURGE, read
   `forecast_evaluation` columns directly (like `KingTide` does), or repoint
   onto normalised `forecast_score` components the way bluebell was in Pass 3?
   The latter is more consistent but needs new `ForecastType` rows + dual
   writes; the former ships faster.
2. **4-day window:** the aggregator is queried `today .. today+3`. Calendar
   detectors (supermoon/equinox/meteor/NLC) — do you want them to surface a
   few days ahead within that window, or also further out (which would need a
   wider query)?
3. **Snow data source:** is adding Open-Meteo `snowfall`/`snow_depth`/
   `freezing_level_height` to the existing fetch acceptable, or is snow a
   separate later epic? This is the gate on the entire snow trio.
4. **SNOW_MIST:** keep it as a distinct third snow type, or fold "with mist"
   into a flag on SNOW_FRESH? (It's not in the brief's list.)
5. **CLEARANCE signal definition:** what exactly counts as a "dramatic
   clearance" — e.g. low+mid cloud dropping ≥ X pp in the hour into the solar
   event? This one needs a product definition before a detector can be built.
6. **Sim-vs-real coexistence:** sim mode currently *replaces* all real
   detectors. As real detectors land, do you want a mode that overlays sims
   on top of live topics (for demos) rather than bypassing them?
