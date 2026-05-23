# Gate 2 redesign investigation — verdict-as-filter to verdict-as-attribute

Status: investigation only. Written 2026-05-23 against `main` at `054ce17`. No production code modified.

**Update (as built, 2026-05-23):** Option B (verdict-gating removed at all horizons, TIDE_MISMATCH retained as hard constraint) shipped via commits `3b70066` (gating policy + 3 sites) and `a7b1d0b` (Claude headline + STANDDOWN gloss + UI). The UI shape that landed is **α-shaped** (Claude-authored headline rendered alongside a separate triage element), **not the γ synthesised header** the implementation prompt referenced. See [Section 9.6](#96-update-as-built--shape-is-α-not-γ) for the correction note. The options analysis in Section 5 is otherwise accurate.

This is a sibling document to [gating-architecture-investigation.md](gating-architecture-investigation.md). Section 2 of the parent document mapped Gate 2 in its current shape and proposed "soften to attribute" as a recommendation. This document is the deep-dive needed before drafting an implementation prompt: it traces every site that produces or consumes the verdict, examines the STANDDOWN reason distribution, sketches three UI shapes for the user to choose between, and presents three concrete end-state options.

The work that motivated this investigation is the April 22 2026 product principle ("Claude evaluates every photographable opportunity at T and T+1"). Gate 4 (just shipped via PR #100) achieved this for the stability axis. Gate 2 — the briefing verdict — remains the dominant skip reason in production logs. This document is the basis for the work that closes the gap.

---

## TL;DR

- **Gate 2 has *three* enforcement sites, not one.** The headline batch filter in [`ForecastTaskCollector.collectForecastCandidates:542`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) is the dominant one, but the same `verdict != GO && verdict != MARGINAL` predicate also gates the SSE drill-down path ([`BriefingEvaluationService.getEvaluableLocationNames:627`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)) and the Claude-authored gloss generation ([`BriefingGlossService:170`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingGlossService.java)). Any redesign that touches one and not the others leaves the system in an inconsistent state.
- **The UI is half-migrated to `displayVerdict` already.** Region cells and drill-down headers read `region.displayVerdict` first, falling back to `region.verdict`. The slot-level Plan grid card still reads `slot.verdict` directly to filter STANDDOWN slots out of the list. The redesign needs to push displayVerdict into the slot-level paths too.
- **The honesty filter is a compensating control with a short half-life.** It exists because the briefing layer told the API "this region is GO" while zero locations had Claude evaluations — a divergence that arose precisely *because* Gate 2 was filtering them out before the batch. Once Gate 2 stops filtering at T+0/T+1, the zero-coverage case becomes rare (limited to batch failures), and the filter can retire or shrink to a sparse-coverage defence.
- **The skip log line strips the standdown reason.** `[BATCH DIAG] SKIP {location} | ... reason=VERDICT_STANDDOWN` does *not* include `slot.standdownReason()`. To compute a reason distribution from logs, the user must either widen the log line (one-line change in `ForecastTaskCollector`) or join the briefing JSON in `daily_briefing_cache` against the skip lines by location+date+target.
- **Cost ceiling for full alignment is small but real.** Scenario B (verdict-gating removed at all horizons, stability still controls T+2/T+3) projects to ~$10-15/day at the user's proposed Sonnet-for-near-term pricing — roughly 4-5× today's Haiku-for-everything baseline. Scenario A (T+0/T+1 only, stability untouched at T+2/T+3) is ~2-3× baseline. Scenario C (richer UI composition layer) is the same cost but ~2-3× the engineering effort.

What surprised me most: the briefing layer never asked Claude whether STANDDOWN was right. The verdict pipeline composes six demotion stages and produces a string like "Heavy cloud" or "Clear sky — no canvas", and the architecture treats that string as authoritative. Claude — which has 70+ lines of prompt guidance about thin strips, blocked horizons, mist-as-positive, and clear-sky-no-canvas in [PromptBuilder:69-115](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) — has more context to reach the same conclusion or revise it. The redesign isn't really about "spend more on Claude". It's about not having a 200-line threshold pipeline silently overruling a 600-token prompt that was specifically built to handle these edge cases.

---

## Investigation constraints

Before the sections proper, three constraints on the data this document can cite:

1. **No production access from this dev machine.** Per CLAUDE.md: "Production runs in Docker on a separate host machine — NOT on the local dev Mac". The prompt instructs me to "pull production data for the last 7 days". I cannot — and the local H2 DB plus log file are dominated by test runs of a single location ("Durham UK") that are not representative. Where this document cites concrete numbers, they come from (a) the parent investigation's already-collected production sampling, (b) static code analysis of structural questions (e.g. "is verdict computed once and stored?"), or (c) reasoned projection from the parent doc's baseline. Where production data is needed, this document lists the exact queries to run.
2. **The `[BATCH DIAG] SKIP ... reason=VERDICT_STANDDOWN` log line does not include the standdown reason.** [`ForecastTaskCollector.java:543-545`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) logs `verdict={STANDDOWN/GO/MARGINAL}` only. To get a reason distribution: widen the log line to include `slot.standdownReason()`, or join the briefing JSON in `daily_briefing_cache.payload` against skip lines by location+date+target.
3. **The honesty filter is a week old.** [`BriefingHonestyFilter.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHonestyFilter.java) was added in the v2.11.18 series (commits `c34dea1`, `054ce17`) and is currently live. Sections that touch the filter (especially Section 6) are written assuming it stays live through the Gate 2 redesign — the question is when it retires, not whether the Gate 2 redesign happens with or without it in place.

---

## Section 1 — Verdict computation map

### 1.1 Where does `BriefingSlot.verdict()` come from?

`BriefingSlot.verdict()` is set exactly once, during briefing build, by [`BriefingSlotBuilder.buildSlot:135-168`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingSlotBuilder.java). The slot then carries the verdict as an immutable field through cache, API response, and UI. There is no "recompute on read" path — the verdict at API read time is whatever was set at briefing-build time (04:00 / 14:00 / 22:00 UTC).

The slot-level verdict is then rolled up to a region-level verdict by [`BriefingVerdictEvaluator.rollUpVerdict:404-436`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) inside [`BriefingHierarchyBuilder.buildRegion:115`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHierarchyBuilder.java). Region-level rollup is computed once at build, not lazily.

### 1.2 Slot-level verdict pipeline

The pipeline composes a base verdict and applies five demotion stages plus a tide override. Each stage can only demote (never promote). The order of stages and the file lines:

| # | Stage | Source | Effect |
|---|-------|--------|--------|
| 1 | `determineVerdict` | [BriefingVerdictEvaluator.java:167-177](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | Base verdict from observer-point low cloud, precip, visibility, humidity |
| 2 | `applyMidCloudDemotion` | [:190-198](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | Mid cloud ≥80 → STANDDOWN; ≥60 demotes GO → MARGINAL |
| 3 | `applyCloudTrendDemotion` | [:218-230](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | Peak ≥40% AND event-hour > earliest → GO → MARGINAL |
| 4 | `applyClearSkyDemotion` | [:244-256](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | All three layers < 15% → GO → MARGINAL |
| 5 | `applyHorizonCloudDemotion` | [:269-277](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | 113-km solar horizon low cloud ≥70 → STANDDOWN; ≥40 demotes GO → MARGINAL |
| 6 | Coastal tide override | [BriefingSlotBuilder.java:163-168](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingSlotBuilder.java) | Coastal + tide data + not aligned → STANDDOWN |

### 1.3 Threshold table

From the constants defined at the top of [`BriefingVerdictEvaluator.java:27-65`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java):

| Signal | STANDDOWN trigger | MARGINAL trigger |
|--------|-------------------|------------------|
| Low cloud (observer point) | > 80% | > 50% |
| Precipitation | > 2.0 mm | > 0.5 mm |
| Visibility | < 5 000 m | < 10 000 m |
| Humidity | — | > 90% |
| Mid cloud (observer point) | ≥ 80% | ≥ 60% (only demotes GO) |
| All three layers low | — | < 15% on all (only demotes GO) |
| Building cloud trend | — | peak ≥ 40% AND increasing (only demotes GO) |
| Horizon low cloud (113 km solar) | ≥ 70% | ≥ 40% (only demotes GO) |
| Coastal tide misaligned | override → STANDDOWN | — |

### 1.4 Region-level rollup

Region verdict is derived from slot verdicts by [`BriefingVerdictEvaluator.rollUpVerdict:404-436`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) with two percentage thresholds:

- **GO**: `goCount / totalViable ≥ 20%` (where viable = GO + MARGINAL)
- **MARGINAL**: `totalViable / totalLocations ≥ 20%`
- **STANDDOWN**: otherwise

Worked examples are in the Javadoc at lines 391-398. The 20% threshold is intentional: it prevents a single GO slot in a large region from elevating the whole region's pill colour to WORTH IT.

### 1.5 Reason labelling — `deriveStanddownReason`

[`BriefingVerdictEvaluator.deriveStanddownReason:345-375`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) walks an ordered enum (`StanddownReason`, defined at [:71-114](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java)) and returns the first match. Priority order (post v2.11.17 horizon-cloud fix):

1. `HEAVY_CLOUD` — low cloud > 80
2. `OVERCAST` — mid cloud ≥ 80
3. `RAIN` — precip > 2.0 mm
4. `POOR_VISIBILITY` — visibility < 5 000 m
5. `BUILDING_CLOUD` — buildingTrend flag set
6. `TIDE_MISMATCH` — coastal tide override applied
7. `SUN_BLOCKED_HORIZON` — horizon low cloud ≥ 70 (added in v2.11.17)
8. `CLEAR_SKY` — all three layers < 15%
9. `POOR_CONDITIONS` — fallback

The reason is stored as a String on the slot, serialised in the briefing API response, and consumed by the UI's drill-down for the "— {reason}" suffix on STANDDOWN slot rows (see [DailyBriefing.jsx:421-429](../../frontend/src/components/DailyBriefing.jsx) and the equivalent dimmed-rows block in [HeatmapGrid.jsx:395](../../frontend/src/components/HeatmapGrid.jsx)).

### 1.6 Storage vs computation

The verdict is computed once during briefing build and stored on the slot. The full briefing response is persisted as JSON in [`daily_briefing_cache.payload`](../../backend/src/main/resources/db/migration/V59__daily_briefing_cache.sql) (V59), keyed at row id=1 — there's one row at a time, replaced on every refresh.

There is no per-slot verdict storage in `forecast_evaluation` or `cached_evaluation`. Those tables hold Claude scores; the verdict lives in the briefing JSON.

The cache write semantics for the briefing are simple: refresh replaces the row. So once a region's verdict is set by the 04:00 build, it stands until 14:00 — neither the batch result writer nor the Claude evaluation flow modifies verdict on the slot.

### 1.7 Implications for the redesign

Five observations relevant to Section 7's surface-area analysis:

1. The verdict computation is **already factored into a stateless evaluator class** (`BriefingVerdictEvaluator`). The redesign does not require restructuring this — verdict still gets computed, still gets stored on the slot, still appears in API responses. Only the *use* of verdict as a filter changes.
2. The verdict is **stored on the BriefingSlot record**, so the API response shape doesn't need to change. The frontend can continue to read `slot.verdict` for display purposes (e.g. flag chips, the dimmed-rows display).
3. The honest-filter (`BriefingHonestyFilter`) already discriminates regions by `scoredLocationCount`, so the API has the data needed to render mixed states (triage says STAND_DOWN, Claude says 4★, both shown).
4. The reason string is **already populated on STANDDOWN slots** and is currently shown only in the drill-down's dimmed-rows display. UI options in Section 5 can use this same field — no new API field needed.
5. The current 20% rollup thresholds **interact with the redesign in one subtle way**: if Claude evaluates STANDDOWN slots and rates some of them 3★+, the region's `displayVerdict` will be elevated by [`BriefingRatingStats.resolveRegionDisplayVerdict`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingRatingStats.java) regardless of the triage verdict. This is intentional behaviour today — the redesign just makes it observable on more cells.

---

## Section 2 — How the UI consumes verdict

### 2.1 Surface map

Each UI surface and its verdict dependency:

| Surface | File:line | Reads | Renders |
|---------|-----------|-------|---------|
| **Plan grid card (heatmap cell)** — header label | [HeatmapGrid.jsx:647-677](../../frontend/src/components/HeatmapGrid.jsx) | `resolveRegionDisplay(region)` → `region.displayVerdict` first, fallback to mapped `region.verdict` | "Worth it sunset" / "Maybe sunset" / "Poor" / "Awaiting" |
| Plan grid card — coloured background tier | [HeatmapGrid.jsx:644-665](../../frontend/src/components/HeatmapGrid.jsx) + [tierUtils.js:48-73](../../frontend/src/utils/tierUtils.js) | `computeCellTier(region)` reads displayVerdict + tide signals | Tier 0-5 → 6 different bg colours |
| Plan grid card — visible-at-current-quality gate | [HeatmapGrid.jsx:645,690-694](../../frontend/src/components/HeatmapGrid.jsx) | tier vs `qualityTier` slider | Cell visible / faded / hidden |
| Plan grid card — clickable (drill-down) | [HeatmapGrid.jsx:692-694](../../frontend/src/components/HeatmapGrid.jsx) | `isStanddown && !showAllLocations` | Card disables click when STAND_DOWN (unless "Show all locations" toggle) |
| Plan grid card — verdict distribution bar | [HeatmapGrid.jsx:800-819](../../frontend/src/components/HeatmapGrid.jsx) | iterates `region.slots[].verdict` for GO/MARGINAL/STANDDOWN counts | 4 px gradient at bottom of cell |
| Plan grid card — mean Claude score badge | [HeatmapGrid.jsx:758-792](../../frontend/src/components/HeatmapGrid.jsx) | `slot.claudeRating` (only `!isStanddown`) | Mean rating pill |
| **Drill-down — event row header** | [HeatmapGrid.jsx:475,495-499](../../frontend/src/components/HeatmapGrid.jsx) and [DailyBriefing.jsx:444,468-472](../../frontend/src/components/DailyBriefing.jsx) | `region.displayVerdict`, `region.verdict`, `region.verdictLabel` | `<VerdictPill>` component |
| Drill-down — gloss prose | [HeatmapGrid.jsx:527-531](../../frontend/src/components/HeatmapGrid.jsx) | `region.glossDetail` | Italic prose under event row |
| Drill-down — per-location list (LocationSlotList) | [HeatmapGrid.jsx:253-409](../../frontend/src/components/HeatmapGrid.jsx) | `slot.verdict !== 'STANDDOWN'` for "visible"; rest are dimmed-rows under "Poor conditions" divider | Score badge OR `<VerdictPill>` per slot |
| Drill-down — "Run full forecast" cost estimate | [HeatmapGrid.jsx:444-447](../../frontend/src/components/HeatmapGrid.jsx) | `slot.verdict === 'GO' \|\| === 'MARGINAL'` to count cost | Confirmation dialog message |
| **Mobile region card** | [DailyBriefing.jsx:552-622](../../frontend/src/components/DailyBriefing.jsx) | `bestDisplay = resolveRegionDisplay(bestRegion)`; `bestRegion.verdictLabel` | Card colour + verdict pill |
| **Map tab** — markers | [MapView.jsx:71-72](../../frontend/src/components/MapView.jsx) + [markerUtils.js:20-89](../../frontend/src/components/markerUtils.js) | `STAND_DOWN_COLOUR` for rating-derived colour (1-2 stars), NOT briefing verdict | Marker SVG |
| Map tab — clusters / popups | [MarkerPopupContent.jsx:230-242](../../frontend/src/components/MarkerPopupContent.jsx) | rating-derived, NOT briefing verdict | Cluster icons, popup content |

### 2.2 Where the existing displayVerdict migration ends

Migration to `displayVerdict` (defined at [DisplayVerdict.java](../../backend/src/main/java/com/gregochr/goldenhour/model/DisplayVerdict.java)) is partial:

- **Region-level: complete.** Plan grid cells and drill-down event rows read `region.displayVerdict` first, falling back to mapped `region.verdict`. The honesty filter overrides `verdictLabel` for zero-coverage regions, also at the region level.
- **Slot-level: NOT migrated.** The per-location list inside the drill-down still reads `slot.verdict` directly to filter STANDDOWN slots into a dimmed-rows section ([HeatmapGrid.jsx:253-256](../../frontend/src/components/HeatmapGrid.jsx)). The `BriefingSlot.displayVerdict` field exists on the API response ([BriefingSlot.java:41,80-82](../../backend/src/main/java/com/gregochr/goldenhour/model/BriefingSlot.java)), populated from `claudeRating` when present, but no UI code reads it. **This is dead-on-arrival code from the previous migration that the Gate 2 redesign should pick up.**

### 2.3 Honesty filter interactions

The filter ([BriefingHonestyFilter.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHonestyFilter.java)) is applied only on the API read path via `BriefingService.getCachedBriefingForApi()` ([BriefingService.java:229-230](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java)) and rewrites regions where `scoredLocationCount == 0`. The rewrites:

- `verdict` — **preserved** (still has the true triage verdict, useful for downstream consumers; the API field doesn't lie about triage)
- `summary` — overridden to "No per-location forecast — conditions too unsettled to evaluate"
- `slots` — overridden to empty list
- `glossDetail` — overridden to the "may firm up closer to the date — or may remain unsettled" prose
- `displayVerdict` — overridden to `STAND_DOWN`
- `scoredLocationCount` — preserved as 0
- `verdictLabel` — set to "Too unsettled to forecast"

In the UI:
- Plan grid cell renders `region.displayVerdict` (STAND_DOWN) and `region.verdictLabel` (the override label) — both honest.
- Drill-down expands the cell, finds empty `region.slots`, renders the gloss detail as the only content.

**Once Gate 2 redesign ships (T+0/T+1 stops filtering)**, the zero-coverage case shrinks dramatically. Section 6 deep-dives this relationship.

### 2.4 Map tab is independent

The Map tab in the frontend renders markers coloured by **Claude rating only**, not by triage verdict. The dark-red `STAND_DOWN_COLOUR` only applies when a location has rating 1-2 ([markerUtils.js:33,89](../../frontend/src/components/markerUtils.js)). A location with no Claude rating either renders as a low-opacity marker or as a "scored-popup" with empty content — the briefing verdict doesn't affect it. Gate 2 changes **do not affect the Map tab** other than possibly increasing how many markers have ratings (more locations getting evaluated → more populated markers).

### 2.5 Verdict-as-filter sites in the frontend

Confirmed list:
- **[HeatmapGrid.jsx:253](../../frontend/src/components/HeatmapGrid.jsx)** — `LocationSlotList` filters STANDDOWN slots into a dimmed-rows section unless `showAllLocations`
- **[HeatmapGrid.jsx:475](../../frontend/src/components/HeatmapGrid.jsx)** — drill-down event row `tappable = !past && (region.verdict !== 'STANDDOWN' || showAllLocations)`
- **[DailyBriefing.jsx:444](../../frontend/src/components/DailyBriefing.jsx)** — mobile drill-down `tappable = !past && region.verdict !== 'STANDDOWN'`
- **[DailyBriefing.jsx:377-378](../../frontend/src/components/DailyBriefing.jsx)** — mobile `LocationSlotList` partitions visible vs standdown by `slot.verdict !== 'STANDDOWN'`
- **[HeatmapGrid.jsx:446](../../frontend/src/components/HeatmapGrid.jsx)** — "Run full forecast" cost estimate counts only GO/MARGINAL slots

Each of these needs to change to read `displayVerdict` instead, OR change predicate semantics (e.g. "show as dimmed only if STAND_DOWN AND no claudeRating"). Section 7 enumerates these per file.

---

## Section 3 — STANDDOWN cases in practice

### 3.1 Production data availability

The user's prompt asks for a 7-day distribution of STANDDOWN reasons. As noted in the Investigation Constraints section, two practical blockers:

1. **No prod access from this dev machine.** Per CLAUDE.md.
2. **The skip log line does not include `slot.standdownReason()`.** Confirmed at [`ForecastTaskCollector.java:543-545`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java):
   ```java
   LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | reason=VERDICT_{}",
           slot.locationName(), date, targetType, slot.verdict());
   ```
   This logs `VERDICT_STANDDOWN` as a verdict-class label but not which standdown reason. The fix is one line: append `slot.standdownReason()` to the log format string and provide it as the next arg.

Two queries to run on prod once the log line is widened, or against the briefing JSON column directly:

```sql
-- Option A: requires log-line widening to include standdownReason
-- (one-line change in ForecastTaskCollector.collectForecastCandidates around line 544)
SELECT
  reason_label,
  COUNT(*) as skip_count
FROM (
  SELECT regexp_extract(message, 'reason=VERDICT_STANDDOWN \\((.+)\\)$', 1) as reason_label
  FROM application_log
  WHERE message LIKE '%[BATCH DIAG] SKIP%VERDICT_STANDDOWN%'
    AND timestamp > NOW() - INTERVAL '7 days'
) sub
GROUP BY reason_label
ORDER BY skip_count DESC;

-- Option B: parse the daily_briefing_cache JSON for slot-level reasons
-- (works against current schema, no code change required, but limited to the
-- most recent briefing — the cache is single-row).
SELECT
  jsonb_path_query_array(
    payload::jsonb,
    '$.days[*].eventSummaries[*].regions[*].slots[?(@.verdict == "STANDDOWN")].standdownReason'
  ) AS reasons
FROM daily_briefing_cache
WHERE id = 1;
```

### 3.2 Reason-by-reason: would Claude produce useful output if not filtered?

For each `StanddownReason` enum value, this section asks: *if Gate 2 stopped filtering this case, would Claude reach the same conclusion (and produce noise), or could it surface useful nuance?* This is the central product question.

The signals Claude already has in its prompt are listed in [PromptBuilder.java:69-115](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) — every signal the verdict pipeline considers is also visible to the model.

#### 3.2.1 `HEAVY_CLOUD` — low cloud > 80%

Verdict trigger: observer-point low cloud > 80%.

Claude sees: low cloud observer value in the data block ([PromptBuilder:360-368](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)) PLUS, when directional data is available, the 113 km solar horizon and antisolar samples PLUS the 226 km far-field sample. The prompt is explicit about the dominance of directional over observer-point: ["When directional data is provided, ALWAYS use it instead of the observer-point Cloud line for scoring."](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) (lines 105-108).

Conclusion: **likely repetitive low rating** when overhead AND solar horizon are both blocked. Claude will rate 1-2★ with a "blanket overcast" summary. Some signal value: when overhead is 80%+ but directional shows a thin strip and far-field shows < 30%, Claude's "thin strip" guidance ([PromptBuilder:94-102](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)) revises to 3-4★ with canvas. Gate 2's observer-only check misses this completely.

**Recommendation**: do not gate. The strip-vs-blanket distinction is exactly the nuance Claude is built to surface.

#### 3.2.2 `OVERCAST` — mid cloud ≥ 80%

Verdict trigger: mid cloud ≥ 80% at the event hour.

Claude sees: observer-point mid cloud AND directional mid cloud at the solar and antisolar horizon. The prompt explicitly says ["Solar horizon low cloud <20% with thick mid cloud (>80%) = light still penetrates below the mid layer, and the mid/high cloud acts as a large canvas. RATE 4 (not 3, not 5)"](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) (lines 82-85). The mid-cloud-blanket case has been explicitly distinguished from the truly-overcast case in Claude's guidance.

**Recommendation**: do not gate. The "thick mid cloud but clear low cloud at horizon" case is the *standout* "moody dramatic light" scenario, and the verdict pipeline gates it out entirely.

#### 3.2.3 `RAIN` — precip > 2.0 mm

Verdict trigger: observer-point precip > 2.0 mm at the event hour.

Claude sees: precipitation in the observer point data, plus weather code. The prompt doesn't have a specific rule for "treat heavy rain as 1★", but Claude's training on photography concepts is enough to handle it.

Conclusion: **likely repetitive low rating**. Claude will rate 1-2★ with a "rain washing out the event" summary. The only useful information is when rain is forecast for T-1h but tapering at the event hour — Claude can pick this up from the weather data trend, but the briefing pipeline computes a single event-hour value and gates on it.

**Recommendation**: do not gate on this *alone* — defer to Claude. But consider that the user's prompt asks "would Claude produce useful output?" — for the 100%-precip Lake District case, the answer is no, Claude produces a flat 1★. This is the cheapest reason to defend gating on if the user wants to preserve any filter at all.

#### 3.2.4 `POOR_VISIBILITY` — visibility < 5 km

Verdict trigger: observer-point visibility < 5 000 m.

Claude sees: visibility metres in the data block, plus the MIST/VISIBILITY TREND guidance in the prompt ([PromptBuilder:116-138](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)) which explicitly treats mist as *positive* when conditions match (thin ground mist + clear sky above = light shafts, atmospheric glow, layering).

The prompt says: ["POSITIVE (score UP): thin ground mist (visibility 2-8 km) with clear sky above (low cloud_cover_low < 30%) at sunrise — light shafts, atmospheric glow, layering."](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)

So a visibility of 4 km at sunrise with clear sky above is — per Claude's prompt guidance — a high-rating opportunity for atmospheric photography. The briefing layer's STANDDOWN-on-<5km gates this out entirely.

**Recommendation**: do not gate. This is a positive false-negative the verdict layer creates.

#### 3.2.5 `BUILDING_CLOUD` — peak ≥ 40% AND increasing

Verdict trigger: the cloud trend evaluator detected a BUILDING pattern.

Claude sees: the same trend block in its prompt via `CloudApproachData`/`SolarCloudTrend` ([PromptBuilder:411-426](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)), labelled `[BUILDING]`. The prompt explicitly handles the BUILDING+THIN_STRIP combination: ["[BUILDING — but THIN STRIP CONFIRMED at event time: strip is well-established, not a developing blanket; THIN STRIP rules take priority — rate 3-4 with canvas present]"](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java).

But here's the thing: **`BUILDING_CLOUD` only fires as a STANDDOWN reason when no other reason matched first** (priority position 5 in the enum). So in practice this is the *least common* of the major STANDDOWN reasons — the slot reaches BUILDING only after low, mid, precip, visibility all passed.

Conclusion: **uncommon but Claude has all the data**. Claude is strictly more informed than the verdict pipeline on building-cloud cases.

**Recommendation**: do not gate.

#### 3.2.6 `TIDE_MISMATCH` — coastal location, tide not aligned

Verdict trigger: coastal location + tide data exists + tide doesn't match the location's preferred `TideType`.

This is qualitatively distinct from the others. Tide alignment is a *hard constraint* — it's a property of the location (a low-tide-only beach is not photographable at high tide). It's not a probabilistic weather signal; it's geometric. Claude doesn't have a model of "this specific location requires low tide for the shot to work" — the tide preference is purely a piece of location metadata used by the verdict layer, not surfaced to Claude.

If Gate 2 stops gating on this, Claude would receive a location with mismatched tide and score it on the weather alone. The user would see "Worth it sunset 4★" for a coastal location that's photographically unusable because the tide is wrong.

Two options here:
- **Keep TIDE_MISMATCH as a hard filter** (a special-case retention of Gate 2 for this single reason).
- **Surface tide alignment as a stronger prompt signal** so Claude can score it down. Currently the prompt mentions tide via the `tide` block but doesn't explicitly say "if mismatched, cap rating at 2★". Adding such guidance is a one-line prompt change.

**Recommendation**: this is the case worth keeping as a hard filter even if the others soften. Cost saved is small but the consequence of getting it wrong is "the user drove out to a location with no shot". Section 9's options keep TIDE_MISMATCH gated.

#### 3.2.7 `SUN_BLOCKED_HORIZON` — horizon low cloud ≥ 70%

Verdict trigger: horizon low cloud at 113 km on the solar azimuth ≥ 70%. Added to `deriveStanddownReason` in v2.11.17 (commit `d39b520`).

Claude sees: the same 113 km solar horizon sample in `DirectionalCloudData` ([PromptBuilder:380-403](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)), PLUS the 226 km far-field strip-vs-blanket sample, PLUS the temporal trend, PLUS the upwind sample. The prompt's blocked-horizon rule is at lines 73-76: ["Solar horizon low cloud >60% = light is BLOCKED; treat as overcast for scoring (fiery_sky 5-20, golden_hour 15-30, rating 1-2)"](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java).

**This is the case where Claude is most clearly better-informed than the verdict pipeline.** The Tyne and Wear screenshots from the earlier investigation had horizon cloud blocking the sun while observer was 0% — Claude would correctly report that, and could even report nuance like "the antisolar mid cloud at 50% might catch some scattered light".

**Recommendation**: do not gate. This is the case the April 22 design was originally motivated by.

#### 3.2.8 `CLEAR_SKY` — all three layers < 15%

Verdict trigger: every layer is below 15%.

Claude sees: the same data, AND a specific CLEAR SKY CAP rule: ["when no directional data is provided and ALL observer-point cloud layers (low, mid, high) are ≤5%, the sky has no canvas whatsoever. Cap rating ≤3."](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) (lines 112-115).

So Claude already caps rating at 3 for genuinely cloudless conditions. The difference: the verdict pipeline treats clear sky as STANDDOWN (rating effectively 0★, position-wise in the UI); Claude treats it as ≤3★, which surfaces as MAYBE. The user gets shown the slot, with a 3★ rating and the gloss explaining "no canvas to catch colour, but light quality may be pleasant".

**Recommendation**: do not gate. The current STANDDOWN promotes the user's confusion ("clear sky on the forecast — why is it STAND DOWN?"). A 3★ MAYBE pill with "no canvas, but light quality may be pleasant" answers the question instead of dodging it.

#### 3.2.9 `POOR_CONDITIONS` — fallback

Verdict trigger: no specific reason matched. Should be rare.

**Recommendation**: do not gate. If it's rare enough to be fallback, the cost saved is rounding error.

### 3.3 Summary table

| Reason | Gate keeps? | Rationale |
|--------|------------|-----------|
| `HEAVY_CLOUD` | **No** | Strip-vs-blanket nuance Claude has and the verdict layer doesn't |
| `OVERCAST` | **No** | Thick-mid-cloud is the most surprising "actually 4★" case |
| `RAIN` | **No** (defer) | Cheapest case to keep gating on if user wants any filter, but Claude handles it cleanly with a 1★ rating |
| `POOR_VISIBILITY` | **No** | Mist-as-positive is a deliberate positive false-negative in the verdict layer |
| `BUILDING_CLOUD` | **No** | Strictly less informed than Claude (Claude sees the full trend) |
| `TIDE_MISMATCH` | **Yes** | Hard geometric constraint, not a probabilistic weather signal |
| `SUN_BLOCKED_HORIZON` | **No** | The April-22-motivating case; Claude reasons over horizon cloud explicitly |
| `CLEAR_SKY` | **No** | Claude caps at 3★ already; STANDDOWN turns "explainable 3★" into "unexplained skip" |
| `POOR_CONDITIONS` | **No** | Fallback — small in volume, no defensible reason to gate |

### 3.4 Reference cases threaded through the document

Two concrete prior incidents to remember:

- **Lake District / 2026-05-18 / SUNRISE**. User reported widespread STANDDOWN with 100% precip. Per Section 1's pipeline, this hits the `RAIN` rule. Under any redesign that ungates `RAIN`, Claude would score this 1★ with a "rain washing out the event" prose — *not useful information*, but also not harmful, and consistent with the principle "evaluate every photographable opportunity". The Lake District case is the strongest argument for keeping a *light* Gate 2 on precip-only — and the weakest argument against removing Gate 2 entirely (Claude's response is a benign 1★).

- **Tyne and Wear / 2026-05-17 / SUNRISE**. Pre-v2.11.17, this surfaced as "Clear sky — no canvas" with "0% at every level" gloss. Post-fix, the label was corrected to `SUN_BLOCKED_HORIZON`. Under any redesign that ungates `SUN_BLOCKED_HORIZON`, Claude would score this 1-2★ with a "solar horizon blocked, antisolar canvas N%" summary — which is *more useful than the current label-only experience*: the user sees Claude's read, can see if antisolar canvas is present (worth driving for the secondary side?), can see far-field strip-vs-blanket structure. This is the *strongest case* for ungating.

---

## Section 4 — Cost projection

### 4.1 Baseline assumptions

From the parent investigation document and the user's prompt context:

- **Per-cycle baseline today**: ~140 evaluations per batch cycle, 2 cycles/day = ~280 evaluations/day. (Section 6 of parent doc; metric corresponds to current `[BATCH DIAG] Triage complete` aggregations.)
- **Region count**: 7 regions enabled in production.
- **Locations per region**: ~5 GO/MARGINAL slots/region (after Gate 2 filter). With STANDDOWN slots back in, more like ~10-15 per region depending on weather.
- **Events per cycle per location**: 2 (sunrise + sunset). Per the briefing build dates: T+0, T+1, T+2, T+3 (4 dates) × 2 events = 8 (location, date, event) tuples per location per cycle.
- **Token usage per evaluation**: ~3K input + ~500 output, per the parent investigation's pricing notes.
- **Pricing**:
  - Haiku 4.5: ~$0.80/Mtok input, ~$4/Mtok output → ~$0.0044 per evaluation (~£0.0035, ~0.35p)
  - Sonnet 4.6: ~$3/Mtok input, ~$15/Mtok output → ~$0.0165 per evaluation (~£0.013, ~1.3p)

### 4.2 Realistic upper bound — Scenario A: T+0/T+1 only

Verdict gating removed at T+0/T+1; T+2/T+3 keeps current verdict + stability gating (i.e. stays where it is today, post-Gate-4).

Per cycle:
- 7 regions × ~15 slots/region (with STANDDOWN back) × 2 events × 2 dates (T+0/T+1) = **~420 evaluations**
- Subtract a small "still gated by TIDE_MISMATCH" fraction: ~5% of coastal slots, ~20% of total locations are coastal → 2-5 slots/cycle → negligible
- Subtract cached evaluations (Gate 1 still in force): the parent investigation shows TRANSITIONAL=12h aligns with the 12h cycle period, so cache effectiveness is ~50% on average → **~210 fresh evaluations per cycle**, 420 per day

Cost (using BATCH_NEAR_TERM model):
- Haiku: 420 × $0.0044 = ~$1.85/day, ~$55/month
- Sonnet: 420 × $0.0165 = ~$6.95/day, ~$210/month

Subtract today's baseline of ~280/day baseline → **net add of ~140 evaluations/day** at near-term model rates.

### 4.3 Realistic upper bound — Scenario B: All horizons

Verdict gating removed at all horizons; stability gating still controls T+2/T+3.

T+0/T+1: as Scenario A (~420 evaluations per cycle, before cache).

T+2: 7 regions × 15 slots × 2 events × (SETTLED + TRANSITIONAL inclusion rate ≈ 78% per parent doc's 124/159 cell snapshot) = ~165 evaluations.

T+3: 7 regions × 15 slots × 2 events × (SETTLED-only rate ≈ 0% in UK May per parent doc) = ~0 evaluations realistically; up to ~30 in genuinely-SETTLED weeks.

Per-cycle total: ~585 evaluations before cache, ~290 after cache (50% effective).

Per day: 580 evaluations.

Cost split (BATCH_NEAR_TERM for T+0/T+1, BATCH_FAR_TERM for T+2/T+3):
- T+0/T+1 (420/cycle * 2 cycles * 50% cached = 420/day) at Sonnet: $6.95/day
- T+2/T+3 (165/cycle * 2 cycles * 50% cached = 165/day) at Haiku: $0.73/day
- **Total: ~$7.68/day = ~$230/month** under the user's proposed pricing
- **All-Haiku equivalent: ~$2.57/day = ~$77/month**

### 4.4 Realistic upper bound — Scenario C: Maximal

Same as Scenario B for the batch pipeline. The "C" in Section 9 differs by UI complexity, not by evaluation volume. Same cost as B.

### 4.5 Cost projection table

| Scenario | Daily evaluations (gross) | Daily evaluations (cached) | Daily cost — Haiku-everywhere | Daily cost — user's mixed (Sonnet near + Haiku far) | Monthly cost (mixed) |
|----------|--------------------------|-----------------------------|-------------------------------|----------------------------------------------------|----------------------|
| Today (baseline) | ~560 | ~280 | ~$1.23 | ~$1.23 (all Haiku today) | ~$37 |
| A — T+0/T+1 only | ~840 | ~420 | ~$1.85 | ~$6.95 | ~$210 |
| B — All horizons | ~1170 | ~580 | ~$2.57 | ~$7.68 | ~$230 |
| C — Same as B + UI work | ~1170 | ~580 | ~$2.57 | ~$7.68 | ~$230 |

### 4.6 Cost-related caveats

1. **Cache effectiveness is the dominant unknown**. If the per-location cache change from the parent investigation's Section 7 lands together with Gate 2 redesign, cache effectiveness might rise above 50% (per-location granularity means STANDDOWN-then-Claude-evaluated slots persist on their own freshness window). If the redesign ships first, cache effectiveness might *drop* below 50% (more evaluations means more cache thrash).
2. **The Sonnet pricing assumption** rests on the user's stated intent to move near-term to Sonnet. Today the system uses Haiku for both; if that stays, monthly cost is ~$80 even under Scenario B.
3. **Costs concentrate on weather-bad weeks**, not weather-good weeks. STANDDOWN volume scales with bad weather. The projections above are upper bounds for an active synoptic week (like the parent investigation's May spring sample); a settled high-pressure week would be ~half.
4. **No new infrastructure cost.** Open-Meteo and prefetch costs don't change; the additional load is purely Claude calls.

---

## Section 5 — UI shape options for STANDDOWN-as-attribute

Once Gate 2 stops filtering, the UI sees both triage's verdict AND Claude's evaluation for slots that previously had only the former. Three options for the composition, **not recommended — for the user to choose**.

### 5.1 Option α — Claude-dominant

Card header shows **Claude's verdict and rating**. Triage's view appears as a secondary chip.

**Plan grid card mock:**
```
┌─────────────────────────────────┐
│ Worth it sunset · 3.2★         │  ← Claude-derived header
│ Pre-frontal light, mid cloud   │  ← Claude gloss
│ ☁️ 12°C 8mph                    │
│ ┌─────────────────────────────┐ │
│ │ triage: clear sky no canvas │ │  ← Triage as secondary chip
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

**Drill-down list row (slot):**
```
4★ Sycamore Gap · 05:32  🚗 45min   [Sun blocked] [Clear all layers]
  Pre-frontal light may catch the mid cloud; the eastern horizon
  is mostly blocked but a strip overhead could light up.
```

**Implementation complexity:**
- New `TriageChip` component (small).
- Card header copy logic switches on `slot.displayVerdict` rather than `slot.verdict`. Existing `BriefingSlot.displayVerdict` field already serves this.
- The dimmed "Poor conditions" divider in `LocationSlotList` goes away.

**Risk:**
- User confusion when triage and Claude disagree strongly. "Clear sky no canvas" + "Worth it 4★" looks contradictory. Mitigation: Claude's gloss should explicitly call out the disagreement (e.g. "clear overhead but the eastern strip might fire — a long lens shot rather than wide vista").
- Triage chip becomes a backseat — users may stop trusting it. If the chip is right (e.g. "tide mismatch" on a coastal slot), it's load-bearing even with Claude saying 4★. UX needs to make the chip clickable to a tooltip explaining the disagreement.

**Pull-through to honesty filter (see Section 6)**: Option α makes the filter cleaner — zero-coverage cells say "Awaiting evaluation"; non-zero-coverage cells render whatever Claude said, and triage falls back to a chip.

### 5.2 Option β — Triage headline, Claude in body

Card header keeps the **triage's verdict** as the visible pill. Body gloss is Claude's prose explaining what Claude actually thinks (which may contradict the header).

**Plan grid card mock:**
```
┌─────────────────────────────────┐
│ Stand down · clear sky          │  ← Triage-derived header (unchanged)
│ ── but Claude says ──            │  ← Body opens with disagreement
│ 4★ — pre-frontal light, etc.    │  ← Claude rating + gloss
│ ☁️ 12°C 8mph                    │
└─────────────────────────────────┘
```

**Drill-down list row:**
```
[STAND DOWN] Sycamore Gap · 05:32 — clear sky      [3★ on a closer look]
  Triage flagged this as clear sky no canvas, but the eastern strip
  might fire pre-frontally — worth a look if you're already close.
```

**Implementation complexity:**
- Smallest. Existing pill stays; Claude data renders in body.
- The current dimmed-rows section in `LocationSlotList` stays, with an inline Claude indicator added.
- Card click behaviour might change (STAND DOWN cells become clickable when they have a Claude rating).

**Risk:**
- Encourages user to read the disagreement as informational rather than something to act on. Could be a feature (the user sees both layers' reasoning) or a confusion (which one is right?).
- "Stand down" pill colour (dark red) clashes with "3★+ Claude rating" — the cell ends up mixed-signal visually. Mitigation: render the cell with a striped background or split-colour pill.

### 5.3 Option γ — Synthesised header

A composer combines Claude's output with triage's verdict into a single description.

**Plan grid card mock:**
```
┌─────────────────────────────────┐
│ Worth a look — clear-sky catch  │  ← Synthesised header
│ Triage said stand down on clear │
│ sky, but pre-frontal light may  │
│ catch the eastern strip. 4★.    │
│ ☁️ 12°C 8mph                    │
└─────────────────────────────────┘
```

**Drill-down list row:**
```
Sycamore Gap · 05:32 — clear sky-with-strip; 4★ if pre-frontal light catches
  [Long gloss combining triage reason and Claude reasoning]
```

**Implementation complexity:**
- Highest. Requires a composition layer — either:
  - **Static template synthesis** (e.g. "Worth a look — {triage reason} catch" when Claude > triage; "Stand down — {triage reason}" when Claude ≤ triage). Modest effort, brittle if Claude's prose contradicts the template.
  - **Claude-generated synthesis** (a third Claude call per region to produce a combined header). High effort, additional cost (~$30/month at Sonnet rates), but smoothest UX.

**Risk:**
- Highest implementation complexity. Highest test burden. Brittle on edge cases (Claude says 5★ for a tide-mismatched location → synthesis says what?).
- Possibly the best UX when it works — single coherent message rather than two layers in tension.

### 5.4 Comparison summary

| Aspect | Option α — Claude-dominant | Option β — Triage headline | Option γ — Synthesised |
|--------|---------------------------|---------------------------|------------------------|
| User reading effort | Low (Claude says X) | Medium (read both) | Low (single message) |
| Implementation effort | ~2 days | ~1 day | ~5+ days |
| Robustness on edge cases | Good (chip is fallback) | Good (both visible) | Brittle |
| Honesty filter compatibility | Clean (zero-coverage = AWAITING) | Clean (zero-coverage = STANDDOWN unchanged) | Requires special-case templates |
| Risk of mixed-signal confusion | Low if chip is clear | Medium | Lowest if synthesis is good |

The user decides between these — they are UX choices the doc cannot pre-empt.

---

## Section 6 — Honesty filter relationship

### 6.1 Current state

[`BriefingHonestyFilter`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHonestyFilter.java) is a read-time transform applied only by [`BriefingService.getCachedBriefingForApi():229-230`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java). It rewrites regions where `scoredLocationCount == 0`, overriding `displayVerdict` to `STAND_DOWN`, `verdictLabel` to "Too unsettled to forecast", and the gloss/summary/slots fields. The triage `verdict` field is preserved on the rewritten record (for downstream consumers that may consult the API directly).

The filter exists because, before this week's hotfix, a region with a GO triage verdict but zero Claude evaluations would surface as "Worth it · 4★" in the Plan grid with the briefing's "Clear at N of M locations" summary — actively misleading the user about coverage that was never produced.

### 6.2 Why does zero-coverage happen today?

Today's zero-coverage cases come from one of:

1. **Gate 2 filtered every slot in the region.** All slots were STANDDOWN → none entered the batch → no evaluations. *This is the dominant case in active-weather regions.*
2. **Gate 3 triaged every slot.** Slots reached the batch but failed weather triage (cloud > 80, precip > 2, visibility < 5km). Smaller volume per the parent doc's 559 catches in 7 days.
3. **Gate 4 excluded every slot.** Stability classification ruled them ineligible at the requested horizon. Note: with the Gate 4 remap (PR #100), T+0/T+1 are now always eligible, so this is realistically only T+2/T+3 — but on T+2/T+3 with all-UNSETTLED regions, all slots get excluded.
4. **Batch API failure.** The submission succeeded but the result didn't write back, or wrote partial results. Per the parent doc's cache-replace-not-merge semantics, a partial batch result REPLACES the region's cache — but the partial replacement can have 0 entries if the batch failed entirely.
5. **No Claude evaluation has been attempted yet.** The briefing was just refreshed but no batch cycle has fired since.

### 6.3 What changes after Gate 2 redesign at T+0/T+1?

If Gate 2 stops filtering at T+0/T+1:

- **Case 1 (Gate 2 filtered everything)** collapses to zero at T+0/T+1 horizons. T+2/T+3 still has it if Gate 2 stays there (Scenario A).
- **Case 2 (Gate 3 triaged everything)** doesn't change.
- **Case 3 (Gate 4 excluded everything)** doesn't change.
- **Case 4 (batch API failure)** doesn't change.
- **Case 5 (not yet attempted)** doesn't change.

So at T+0/T+1, post-redesign, zero-coverage shrinks dramatically — limited to Cases 2-5. Case 2 (Gate 3) is the dominant residual: ~80 catches/day spread across 7 regions × 2 events × 2 dates → ~3-4 slots per region-event-date on average, often clustered in active-weather days.

### 6.4 Should the honesty filter retire as part of Gate 2 work?

Three options:

**Option 1 — Retire honesty filter at the same time as Gate 2.**
- Aligns the work in a single PR; reviewer sees the complete picture.
- Risk: a batch failure in the first week post-deploy puts the UI back in the misleading state. Without the filter, the v2.11.18 problem returns. Recommend AGAINST.

**Option 2 — Keep honesty filter, retitle it.**
- Rename internally to `BriefingSparseCoverageFilter`. Threshold changes from "zero" to "below N% of region slots scored".
- Now defends against partial-batch-failure cases too.
- More work but smoother degradation. Recommend.

**Option 3 — Keep honesty filter as-is, do nothing more in this PR.**
- Filter still fires only when `scoredLocationCount == 0`. After Gate 2 redesign, that's rare but real (Gate 3 batch failures).
- Smallest change set. Acceptable.

### 6.5 Residual scope

If we keep the filter (Options 2 or 3), the residual cases it defends against are:

- Whole region triaged out by Gate 3 (~3-4 slots per region-event-date in bad weather)
- Whole region excluded by Gate 4 (T+2/T+3 all-UNSETTLED region)
- Batch API failure

For the partial-coverage case (Option 2), thresholds to consider:
- "Below 50%": balanced default, catches half-failed batches
- "Below 30%": more permissive, only catches major outages
- "Below 20%": matches the region rollup threshold for symmetry

### 6.6 Recommendation

**Keep the filter through Gate 2 redesign. Decide on Option 2 vs 3 in a follow-up PR.**

The filter is a small piece of code (~120 lines) with a single point of application (one method call in `BriefingController`). It's not creating debt; it's a load-bearing compensating control. Retiring it during Gate 2 work would compound the risk of the redesign with the risk of the filter removal — both can be examined post-deploy separately.

---

## Section 7 — Implementation surface area

Files that change, with rough line counts and risk per file. **No code in this section; it's a sketch for the implementation prompt to expand.**

### 7.1 Backend changes

| File | Lines changed | Change | Risk |
|------|---------------|--------|------|
| [`ForecastTaskCollector.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) | ~10 | Remove the `verdict != GO && verdict != MARGINAL` filter at line 542 (Scenario B) OR add a `daysAhead <= 1` exemption (Scenario A). Keep TIDE_MISMATCH if user retains tide-as-filter. Widen the BATCH DIAG log line to include `slot.standdownReason()` so reason distribution becomes queryable. | Medium — this is the headline change; needs careful test coverage to confirm filter behaviour by `daysAhead`. |
| [`BriefingEvaluationService.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) | ~5 | `getEvaluableLocationNames` line 627 needs the same predicate as `ForecastTaskCollector` so the SSE "Run full forecast" button doesn't behave differently from the batch. The simplest approach is to extract the predicate into a shared static (e.g. `BriefingGatingPolicy.shouldEvaluate(slot, daysAhead, locationType)`) and use it in both sites. | Low — straightforward refactor. |
| [`BriefingGlossService.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingGlossService.java) | ~30 | Currently only generates Claude-authored gloss for GO/MARGINAL regions ([:170](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingGlossService.java)). Decision needed: should STANDDOWN regions get gloss too, to support Option α/β/γ? If yes, the prompt may need to change ("describe even when conditions are poor"). | Medium — directly affects user-facing copy; needs careful prompt review. |
| [`BriefingVerdictEvaluator.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | 0 | **No changes.** Verdict computation stays exactly as-is. Verdict still gets computed, still gets stored on the slot, still appears in API responses. | None. |
| [`BriefingHonestyFilter.java`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHonestyFilter.java) | 0 to ~30 | Per Section 6 decision. If keeping as-is: no change. If extending to sparse-coverage: add a threshold parameter and read the slot count. | Low if no change; medium if extending. |
| New: `BriefingGatingPolicy.java` (suggested) | ~50 | Single source of truth for "should this slot enter Claude evaluation?". Used by `ForecastTaskCollector` and `BriefingEvaluationService`. Encodes the `(daysAhead, locationType, verdict, tideAligned)` policy. | Low — pure refactor of existing two-line predicates. |
| Test files | ~200 | New unit tests for `BriefingGatingPolicy`. Updated tests for `ForecastTaskCollector` to verify per-daysAhead behaviour. SSE drill-down tests for the same. | Low — straightforward additions. |

### 7.2 Frontend changes

| File | Lines changed | Change | Risk |
|------|---------------|--------|------|
| [`HeatmapGrid.jsx`](../../frontend/src/components/HeatmapGrid.jsx) | ~30 | Lines 253-256: `LocationSlotList` filters slots by `verdict !== 'STANDDOWN'`. Change to `displayVerdict !== 'STAND_DOWN'`. The dimmed-rows section semantics depend on Option α/β/γ chosen. Lines 444-447: "Run full forecast" cost calc — update predicate to match backend's `BriefingGatingPolicy`. Lines 475: `tappable` predicate update for Option β. | Medium — most visible behaviour change. |
| [`DailyBriefing.jsx`](../../frontend/src/components/DailyBriefing.jsx) | ~20 | Lines 377-378: mobile slot list filter. Line 444: mobile drill-down tappable. Mirror HeatmapGrid changes. | Low — mirrors the desktop change. |
| New: `TriageChip.jsx` (if Option α) | ~50 | Small chip component showing triage's view, with tooltip for reason. | Low — small standalone component. |
| [`tierUtils.js`](../../frontend/src/utils/tierUtils.js) | 0 | Already reads `displayVerdict` — no change needed. | None. |
| Test files (`.test.jsx`) | ~100 | Update component tests for the new filter/dim semantics. Add tests for `TriageChip` if added. | Low — additive. |

### 7.3 API contract changes

**None required for the headline redesign.** The `BriefingRegion.displayVerdict`, `BriefingRegion.verdict`, `BriefingRegion.verdictLabel`, `BriefingSlot.displayVerdict`, `BriefingSlot.verdict`, and `BriefingSlot.standdownReason` fields all exist today. The frontend just reads them differently.

If Option α (TriageChip) is chosen, the frontend can render `slot.standdownReason` directly as the chip text — no API change needed.

If the user wants a *new* field (e.g. `slot.triageBadge: { label, severity }`) for cleaner UX, that's an opt-in API addition; not required.

### 7.4 Migration / schema changes

**None.** No DB schema changes are required for the Gate 2 redesign. The data model is sufficient as-is.

### 7.5 Total surface area summary

- ~5 backend files changed, ~315 lines net
- ~3 frontend files changed (+ 1 new), ~200 lines net
- 0 DB migrations
- 0 API contract additions (with the option of one slot-level field if Option α wants it)

This is moderate scope — comparable to the Gate 4 work — but is concentrated on the high-traffic batch pipeline and the Plan grid card. Both are areas where regression cost is high; test coverage and the verification plan (Section 8) are load-bearing.

---

## Section 8 — Test strategy

### 8.1 Unit tests

- **`BriefingGatingPolicyTest`** (new). Per-`daysAhead` and per-`StanddownReason` tables. Each combo asserts "evaluate vs skip" against the chosen scenario (A/B/C).
- **`ForecastTaskCollectorTest`** — extend the existing test to verify:
  - T+0 STANDDOWN slot enters the batch (Scenario A, B, C)
  - T+0 TIDE_MISMATCH slot does not enter (regardless of scenario, if user retains tide-as-filter)
  - T+2 STANDDOWN slot enters only in Scenario B/C
  - The `[BATCH DIAG] SKIP` log line includes `standdownReason` when `verdict=STANDDOWN`
- **`BriefingEvaluationServiceTest`** — the SSE drill-down's `getEvaluableLocationNames` produces the same set as the batch collector for the same (region, date, target).
- **`BriefingGlossServiceTest`** — when STANDDOWN regions are included (decision-dependent), the gloss prompt and parsing work correctly.

### 8.2 Integration tests

- **End-to-end briefing → batch → Claude → cache → UI flow** with a STANDDOWN-verdict slot at T+0. Verify:
  - Slot enters the batch
  - Result writes to `cached_evaluation`
  - Slot's `claudeRating` populates on the next briefing refresh
  - Plan grid card renders with Claude's rating (Option α) or with mixed signal (Option β)
- **Honesty filter parity** — confirm regions with zero coverage still render as STAND_DOWN per the filter, and regions with partial coverage render normally.

### 8.3 Production smoke

Post-deploy queries to confirm the change works:

```sql
-- 1. Count of evaluations per daysAhead pre/post deploy
SELECT
  DATE_TRUNC('day', evaluated_at) as day,
  EXTRACT(DAY FROM (date - DATE_TRUNC('day', evaluated_at)::date)) as days_ahead,
  COUNT(*) as evaluations
FROM cached_evaluation
WHERE evaluated_at > NOW() - INTERVAL '14 days'
GROUP BY day, days_ahead
ORDER BY day, days_ahead;

-- 2. Confirm STANDDOWN slots are now evaluated (find slot ratings with verdict=STANDDOWN)
-- Requires inspecting the briefing JSON alongside cached_evaluation.results_json.
SELECT
  jsonb_path_query(
    payload::jsonb,
    '$.days[*].eventSummaries[*].regions[*].slots[?(@.verdict == "STANDDOWN" && @.claudeRating != null)].locationName'
  ) AS evaluated_standdown_locations
FROM daily_briefing_cache
WHERE id = 1
LIMIT 50;

-- 3. Verify the BATCH DIAG SKIP log line now includes reasons
-- (Look for the widened log line in app logs; sample with grep)
docker logs <container> 2>&1 | grep "BATCH DIAG.*VERDICT_STANDDOWN" | tail -20
```

### 8.4 Rollback plan

- Backend: revert the predicate change in `ForecastTaskCollector` and `BriefingEvaluationService` (one PR).
- Frontend: revert the filter change in `HeatmapGrid` and `DailyBriefing` (separate PR — but the backend revert alone restores Gate 2 behaviour because no STANDDOWN slots will have evaluations).
- The honesty filter remains in place either way.

### 8.5 Cost monitoring

The metrics page already surfaces per-cycle batch counts and per-run costs. Watch:
- Daily total Claude spend in £
- Per-(model × daysAhead) call counts
- Cache hit rate (should change with the per-location granularity if Gate 1 work also ships)

If daily cost exceeds 1.5× the Section 4 projection, investigate cache thrash before rolling back.

---

## Section 9 — Three end-state options

Per the prompt, three concrete options for the user to choose between. **Not recommended — for the user to pick.**

### 9.1 Option A — Minimal

**Scope**: Verdict-gating removed at T+0/T+1 only. T+2/T+3 keeps current verdict + stability gating. TIDE_MISMATCH kept as a hard filter at all horizons. UI option β (triage headline, Claude in body) — smallest UI change.

**Code changes**:
- `ForecastTaskCollector.collectForecastCandidates` predicate: `if (daysAhead > 1 && (slot.verdict != GO && slot.verdict != MARGINAL)) skip` — keep gating only at T+2+.
- Frontend slot-list filter swaps from `verdict !== 'STANDDOWN'` to `displayVerdict !== 'STAND_DOWN'` so Claude-scored STANDDOWN slots become visible.
- No other changes.

**Cost impact**: Section 4 Scenario A. ~$7/day at user's proposed Sonnet pricing (~$210/month), up from ~$1/day baseline. ~$2/day at all-Haiku pricing.

**Effort**: ~3-4 engineering days. Backend ~1 day, frontend ~1 day, tests ~1 day, end-to-end + smoke ~1 day.

**Risk**:
- Smallest UI change → users don't notice the new evaluations buried at the bottom of drill-downs.
- T+2/T+3 still gated → April 22 principle only partially met.
- Honesty filter behaviour preserved.

**April 22 alignment**: **Partial.** T+0/T+1 evaluate everything; T+2/T+3 still skip STANDDOWNs.

### 9.2 Option B — Aligned

**Scope**: Verdict-gating removed at ALL horizons. Stability gating from Gate 4 still controls T+2/T+3 cost. TIDE_MISMATCH kept as a hard filter. UI option α (Claude-dominant header with triage chip) — moderate UI change.

**Code changes**:
- `ForecastTaskCollector.collectForecastCandidates` predicate: drop the verdict check entirely (keep TIDE_MISMATCH separately by checking it on the slot).
- New `BriefingGatingPolicy` shared class encoding the residual TIDE_MISMATCH-only filter.
- Frontend: Option α implementation, including `TriageChip` component.
- `BriefingGlossService` extended to gloss STANDDOWN regions too (Claude prose for the "stand down but here's why" case).

**Cost impact**: Section 4 Scenario B. ~$8/day at user's proposed Sonnet pricing (~$230/month), ~$2.50/day at all-Haiku.

**Effort**: ~6-8 engineering days. Backend ~2 days (including gloss service work), frontend ~2-3 days (TriageChip + filter changes + tests), tests ~2 days, end-to-end + smoke ~1 day.

**Risk**:
- Largest cost jump (4-5× baseline at user's proposed Sonnet pricing).
- Largest user-visible change → support burden temporary spike.
- Gloss service extending to STANDDOWN means re-tuning the prompt; risk of "rain at 100% prob is bad" repetitive prose unless tuning is good.

**April 22 alignment**: **Full at horizon level; partial at evaluation depth.** Every horizon evaluates everything; what enters the batch is now controlled only by stability (Gate 4) and one structural filter (TIDE_MISMATCH).

### 9.3 Option C — Maximal

**Scope**: Same as Option B (all horizons, stability-only at T+2/T+3, TIDE_MISMATCH kept), plus:
- UI option γ (synthesised header) implementation
- Honesty filter extended to sparse-coverage (per Section 6 Option 2)
- New `BriefingSlot.triageBadge` API field surfacing the standdown reason with severity
- VerdictPill extracted into a shared component (per the parent investigation's note that this was already on the radar)
- Slot-level `displayVerdict` consumed in the slot list

**Code changes**: All of B, plus:
- ~50 lines of new composer logic for synthesised headers
- ~30 lines of new API field on `BriefingRegion`/`BriefingSlot`
- ~80 lines of frontend component extraction and refactor
- Sparse-coverage threshold work in honesty filter

**Cost impact**: Same as B (~$230/month at proposed Sonnet pricing). The composer optionally adds a small Claude call (+~$10-30/month) if implemented as Claude-generated rather than templated.

**Effort**: ~10-14 engineering days. Backend ~3-4 days, frontend ~5-6 days, tests ~3 days, end-to-end + smoke ~2 days.

**Risk**:
- Highest scope → more places to regress.
- Synthesised header is the most user-research-sensitive change. UX gain is real if done well; brittle if done poorly.

**April 22 alignment**: **Full**, plus auxiliary improvements.

### 9.4 Option comparison

| Aspect | A — Minimal | B — Aligned | C — Maximal |
|--------|-------------|-------------|-------------|
| Days of engineering | 3-4 | 6-8 | 10-14 |
| Cost impact (monthly, mixed model) | ~$210 | ~$230 | ~$240 |
| Cost impact (monthly, all Haiku) | ~$55 | ~$77 | ~$77 |
| April 22 alignment | Partial | Full | Full+ |
| User-visible change | Minimal | Moderate | Major |
| Risk | Low | Medium | Medium-high |
| Test surface | Small | Medium | Large |
| Reversibility | Easy | Easy | Medium |
| Honesty filter retire timing | Not in this work | Not in this work | Extended in this work |

### 9.5 Sequencing relative to other gating work

If the user is also planning the Gate 1 (cache freshness, per-location) work from the parent investigation:

- **Gate 2 (this work) before Gate 1**: Gate 2 redesign increases batch volume → existing per-region cache thrash becomes worse → motivation for Gate 1 work increases. Order: B → then parent investigation's Gate 1.
- **Gate 1 before Gate 2**: per-location freshness in place → Gate 2's volume increase is absorbed cleanly. Order: parent investigation's Gate 1 → then B.

Either order works. The parent investigation suggested Gate 1 should come after Gate 2/4 because Gate 2/4 changes the cache write shape. That argument still holds.

### 9.6 Update (as built): UI shape is α, not γ

**UI shape as built: α-shaped (Claude-authored headline + separate triage element).**

The original plan referenced option γ (a synthesised header merging Claude's evaluation and the triage verdict into one sentence). The implementation took a cheaper, cleaner path: Claude authors the headline from the same atmospheric/scoring data it rates from, and the triage-vs-Claude two-layer story surfaces as separate UI elements (Claude headline + rating as the primary signal; triage verdict as a secondary chip/sub-line) rather than as synthesised prose.

This is functionally closer to option α (Claude-dominant header, triage as a separate element) than to γ. The synthesis logic γ described — and the brittleness concern that came with Java-composing two signals into one sentence — does not exist in the as-built system, because there is no synthesis: the two signals are displayed side by side, not merged.

**Deferred: triage-aware headline synthesis (true γ).** If the separate-element presentation proves confusing in practice, threading the triage verdict + reason into the headline prompt so Claude can explicitly acknowledge it ("Triage flagged stand down, but...") is a clean additive follow-up. It was deferred to avoid a ~10-file prompt-context cascade for a UX refinement whose value is unproven until the separate-element UI has been seen in production.

---

## Open questions for the user before drafting the implementation prompt

1. **Scenario choice** — A, B, or C. Section 4's cost projection and Section 9's effort estimates should inform but not pre-empt this.
2. **UI option** — α, β, or γ. Section 5's mocks present them; the choice depends on how much UX consistency vs. trust-in-Claude the user wants.
3. **TIDE_MISMATCH retention** — keep it as a hard filter (recommended in Section 3.2.6) or include it in the softening?
4. **Honesty filter strategy** — Section 6 Option 1, 2, or 3. The recommendation was Option 3 (defer) but the user may want to bundle Section 6 Option 2 (sparse-coverage extension) into the same PR.
5. **`BriefingGlossService` STANDDOWN extension** — does Claude write gloss for STANDDOWN regions too? This is a small but visible call.
6. **Pricing assumption** — does "user's proposed Sonnet pricing for near-term" hold? If the actual production state keeps Haiku, the cost projection drops by ~3-4× across all scenarios.
7. **Log-line widening** — independent prep work: include `slot.standdownReason()` in the `[BATCH DIAG] SKIP` log line so reason distribution becomes queryable from logs alone, before the redesign ships. One-line change, zero behaviour impact. The user may want this as a separate PR before the main work.

---

## Areas of uncertainty

Things this investigation could not pin down confidently:

1. **Production STANDDOWN reason distribution.** Section 3.2 reasons about each reason qualitatively but the actual frequency mix is unknown without prod access + a log-line widening or briefing-JSON parse.
2. **Cache effectiveness post-redesign.** Section 4's 50% cache hit rate is a guess based on the parent investigation's TRANSITIONAL=12h vs 12h cycle observation. The real rate could be 30% or 70%; that swings the cost estimate by 40% either way.
3. **Whether Gate 3 (triage) will be touched in parallel.** The parent investigation's Section 7 left Gate 3 as a product decision A/B/C. If Gate 3 is also softened to attribute-only, the Section 4 cost projection should add ~80/day × Sonnet rate = ~$1.30/day extra. The cost estimates in this document assume Gate 3 stays as-is.
4. **Whether the `BriefingGlossService` prompt produces useful prose on STANDDOWN regions.** Without testing the prompt against actual STANDDOWN cases, the assumption that it gracefully says "rain washes out the event" rather than repeating itself across regions is unverified. May need a small prompt-engineering pass before shipping.
5. **Frontend test coverage gap risk.** The frontend tests for `HeatmapGrid` and `DailyBriefing` mostly exercise the GO/MARGINAL paths. STANDDOWN-with-Claude-rating is a new state that may need ~5-10 new test cases per component to cover.

---

## Appendix: files referenced

| File | Purpose |
|------|---------|
| [BriefingSlotBuilder.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingSlotBuilder.java) | Slot verdict pipeline (Section 1.2) |
| [BriefingVerdictEvaluator.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | Thresholds, demotions, rollup, reason labelling |
| [BriefingHierarchyBuilder.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHierarchyBuilder.java) | Region rollup invocation site |
| [BriefingService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java) | Cache read/write, API-facing variant, enrichment |
| [BriefingHonestyFilter.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingHonestyFilter.java) | Read-time zero-coverage rewrite |
| [BriefingEvaluationService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) | SSE drill-down "Run full forecast" — second verdict filter site |
| [BriefingGlossService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingGlossService.java) | Region gloss — third verdict filter site (GO/MARGINAL only) |
| [BriefingRatingStats.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingRatingStats.java) | Claude-rating to displayVerdict resolution |
| [ForecastTaskCollector.java](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) | Headline verdict-as-filter site (line 542) |
| [PromptBuilder.java](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java) | What Claude sees about cloud, mist, horizon |
| [BriefingSlot.java](../../backend/src/main/java/com/gregochr/goldenhour/model/BriefingSlot.java) | Slot record — verdict + displayVerdict + standdownReason fields |
| [BriefingRegion.java](../../backend/src/main/java/com/gregochr/goldenhour/model/BriefingRegion.java) | Region record — verdict + displayVerdict + verdictLabel + scoredLocationCount |
| [DisplayVerdict.java](../../backend/src/main/java/com/gregochr/goldenhour/model/DisplayVerdict.java) | Enum + resolve() logic |
| [HeatmapGrid.jsx](../../frontend/src/components/HeatmapGrid.jsx) | Plan grid card and drill-down |
| [DailyBriefing.jsx](../../frontend/src/components/DailyBriefing.jsx) | Mobile region cards and drill-down |
| [tierUtils.js](../../frontend/src/utils/tierUtils.js) | resolveRegionDisplay + computeCellTier |
