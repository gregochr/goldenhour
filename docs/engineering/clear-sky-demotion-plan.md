# Triage's clear-sky demotion reads the horizon — plan

Status: planned 2026-11-15, reviewed (data-query lens; design and blast-radius lens), revised. Follows
`gloss-solar-cloud-plan.md` (#1835, #1836), whose "Out of scope" named this. **Phase A** is a read-only data
check the owner runs on production; **Phase B** is code and goes ahead only if Phase A says so.

## The problem

`BriefingVerdictEvaluator.applyClearSkyDemotion` demotes a triage **GO to MARGINAL** when the observer
point's low, mid and high cloud are all below `CLEAR_ALL_LAYERS_MAX` (15%); `buildFlags` adds
`"Clear all layers"` on the same test. Both read **overhead** cloud only.

Since #1835/#1836 every sky slot also carries cloud 113 km toward the real sun and anti-sun
(`WeatherConditions.sunSide*`, `antiSunSide*`), and the full evaluation (`PromptBuilder`) says "a clear
observer point with directional cloud canvas is NOT 'clear sky'". On 16 Nov (clear overhead, a high-cloud
canvas on the horizons, 43 of 55 rated highly) triage still demoted those spots.

### What GO vs MARGINAL actually drives (traced by review)

On screen, nothing directly: a rated spot shows its rating (`DisplayVerdict.resolve`), and unrated GO and
MARGINAL both show `UNRATED`. The slot's flags are never rendered by the frontend. Readers that do see the
difference:

| Reader | Effect of more GO |
|---|---|
| `ForceEvalHeadlineSelector` (L151, D+2..3, capped by `forceEvalCap`) | Re-orders who gets a paid forced evaluation, **and adds spend up to the cap** on days that today have fewer GO slots than the cap (e.g. an all-clear-overhead day). |
| `BriefingVerdictEvaluator.rollUpVerdict` | Region verdict can move MARGINAL → GO. |
| `BriefingHeadlineGenerator`, `BriefingRollupBuilder` (L453, best-bet input), `BriefingHierarchyBuilder` (L143, representative comfort row) | Counts and choices shift. |
| `BriefingVerdictEvaluator.buildRegionSummary` (L446–453) | Says "Clear at N of M locations" for GO; more GO will now come from horizon-canvas skies. Check Ask and emails for readers of `region.summary` before calling it invisible; reword to neutral ("Go at N of M") if any reader exists. |
| Region gloss (`goCount`, `marginalCount`, GO-slot medians, `verdict` — all in the reuse key) | Every region whose counts change gets a fresh Haiku gloss on the first build after deploy, and again whenever a slot crosses the line. Small (gloss is about $4/month in total) but real. |

Not affected (checked): Ask's snapshot (reads `DisplayVerdict`), `BriefingDigestService`,
`ReachVerdictService`, `AskReadyFreshness`, `VerdictSampleGate`, batch candidate selection, model tier,
stability skips, triage retraction. Frontend `tierUtils.js` maps raw GO only when `displayVerdict` is
absent (dormant).

---

## Phase A — does an overhead-clear, horizon-canvas evening deserve GO? (owner runs, read-only)

Sessions never read production. The owner runs this on production Postgres and pastes the output into the
Phase B PR (or this doc).

Notes on the data:
- Use `sky_rating` (Claude's pure sky star, batch path, V164+), not `rating` (sky combined with tide or
  bluebell). Exclude triage rows (`triage_reason IS NOT NULL` carry a canned `rating = 1`).
- `forecast_evaluation` has one row per scheduled run (T+7 … T+0, Haiku far-term and Sonnet near-term):
  keep the **latest** scored row per (location, date, event).
- `solar_*_cloud` is the evaluation's 3-point cone mean (±15°); the briefing reads one point. A mean under
  15 can hide one cloudy bearing, so cohort B here is slightly less clear than the briefing's. Not blocking.
- `outcome_rating` is one row per user per slot (1–5; NULL = not seen): average per slot before joining.
  These are **reader** outcomes, not only the owner's.
- `cloud_verification` (ERA5) samples the sunward cone and the observer only — no anti-sun — so its check
  is "sunward horizon low < 15 and sunward mid/high ≥ 15".

```sql
WITH latest AS (
  SELECT DISTINCT ON (f.location_id, f.target_date, f.target_type) f.*
  FROM forecast_evaluation f
  WHERE f.sky_rating IS NOT NULL AND f.triage_reason IS NULL
    AND f.target_type IN ('SUNRISE','SUNSET')
    AND f.low_cloud IS NOT NULL AND f.mid_cloud IS NOT NULL AND f.high_cloud IS NOT NULL
    AND f.target_date >= CURRENT_DATE - 180 AND f.target_date < CURRENT_DATE
  ORDER BY f.location_id, f.target_date, f.target_type, f.scored_at DESC NULLS LAST, f.id DESC
), c AS (
  SELECT l.*,
    CASE WHEN NOT (l.low_cloud < 15 AND l.mid_cloud < 15 AND l.high_cloud < 15) THEN 'C_ordinary'
         WHEN l.solar_low_cloud IS NULL THEN NULL
         WHEN l.solar_low_cloud >= 15 THEN 'D_overhead_clear_sunward_low'
         WHEN GREATEST(l.solar_mid_cloud, l.solar_high_cloud,
                       COALESCE(l.antisolar_mid_cloud, 0), COALESCE(l.antisolar_high_cloud, 0)) >= 40
           THEN 'A2_canvas40'
         WHEN GREATEST(l.solar_mid_cloud, l.solar_high_cloud,
                       COALESCE(l.antisolar_mid_cloud, 0), COALESCE(l.antisolar_high_cloud, 0)) >= 15
           THEN 'A1_canvas15'
         ELSE 'B_clear_everywhere' END AS cohort
  FROM latest l
), o AS (
  SELECT location_id, event_date, event_type, AVG(rating) AS outcome
  FROM outcome_rating WHERE rating IS NOT NULL GROUP BY 1, 2, 3
), v AS (
  SELECT f.location_id, f.target_date, f.target_type,
    BOOL_OR(cv.horizon_low_cloud < 15
            AND GREATEST(cv.horizon_mid_cloud, cv.horizon_high_cloud) >= 15) AS era5_gap_canvas
  FROM cloud_verification cv JOIN forecast_evaluation f ON f.id = cv.forecast_evaluation_id
  WHERE cv.horizon_low_cloud IS NOT NULL GROUP BY 1, 2, 3
)
SELECT c.cohort, COUNT(*) AS slots,
  ROUND(AVG(c.sky_rating), 2)               AS mean_sky_rating,
  ROUND(AVG((c.sky_rating >= 4)::int), 2)   AS share_4plus,
  COUNT(o.outcome)                          AS rated_outcomes,
  ROUND(AVG(o.outcome), 2)                  AS mean_outcome,
  COUNT(v.era5_gap_canvas)                  AS era5_verified,
  ROUND(AVG(v.era5_gap_canvas::int), 2)     AS era5_gap_canvas_share
FROM c
LEFT JOIN o ON o.location_id = c.location_id AND o.event_date = c.target_date AND o.event_type = c.target_type
LEFT JOIN v ON v.location_id = c.location_id AND v.target_date = c.target_date AND v.target_type = c.target_type
WHERE c.cohort IS NOT NULL
GROUP BY c.cohort ORDER BY c.cohort;
```

Optional second run: add `c.evaluation_model` to the SELECT and GROUP BY to compare the Haiku and Sonnet
scales.

**Decision rule.** Let A = A1 ∪ A2 (or A2 alone if A1 fails the rule; then Phase B's canvas threshold is 40).
B is the comparison that matters: it is exactly what stays demoted.

1. A has ≥ 30 slots.
2. *Triage/evaluator alignment:* A's `mean_sky_rating` ≥ B's + 0.5. (Partly circular — the prompt scores
   this pattern up — so it shows triage disagrees with the evaluator, not that the evaluator is right.)
3. *Outcomes decide when there are enough:* if A has ≥ 8 `rated_outcomes`, A's `mean_outcome` ≥ B's and no
   more than 0.5 below C's.
4. *Otherwise ERA5:* A has ≥ 10 `era5_verified` and `era5_gap_canvas_share` ≥ 0.6. If not, **stop and wait
   for data**; re-run in a month.

Cohort D (overhead clear, sunward low ≥ 15) is shown for context only; Phase B never un-demotes it. The
owner may overrule the rule either way.

### Phase A result (production, run 2026-11-15, last 180 days, latest scored row per slot)

| Cohort | Slots | Mean sky ★ | Share 4★+ | Reader outcomes | ERA5 verified | ERA5 gap + canvas |
|---|---|---|---|---|---|---|
| A1 canvas 15–39 | 41 | 3.05 | 0.07 | 0 | 25 | 0.48 |
| A2 canvas ≥ 40 | 154 | 3.47 | 0.51 | 1 (2.0) | 119 | 0.49 |
| B clear everywhere | 320 | 2.92 | 0.03 | 0 | 248 | 0.45 |
| C ordinary | 9,796 | 2.41 | 0.12 | 4 (4.0) | 9,264 | 0.23 |
| D overhead clear, sunward low | 1,330 | 2.51 | 0.03 | 0 | 1,183 | 0.13 |

(A2 is 148 Sonnet 5.5 + 6 Haiku rows; everything else is almost all Sonnet 5.5.)

Against the rule:
1. Size: A = 195, A2 = 154 — pass.
2. Alignment: A combined 3.38 vs B 2.92 (+0.46) — **fail**; A1 (3.05) is rated like B. A2 alone 3.47 (+0.55,
   51% at 4★+ vs 3%) — **pass**, so if anything the canvas threshold would be 40.
3. Outcomes: 1 reader outcome in A2 (5 in all cohorts over 180 days) — not enough; fall to ERA5.
4. ERA5: A2 has 119 verified slots but only **0.49** confirm the sunward gap plus canvas — **fail** (< 0.6).
   More telling, B (forecast: no canvas anywhere) confirms at **0.45**: ERA5 sees sunward canvas almost as
   often when the forecast said none as when it said ≥ 40%.

**Decision: stop. Phase B is not built.** The evaluator does rate A2 higher, but nothing independent shows
those evenings were better: there are no outcomes, and the forecast's horizon mid/high cloud barely
separates from "no canvas" in ERA5. Caveats before reading too much into (4): the ERA5 check is sunward
only (A2 includes anti-sun-only canvas, which it cannot see), and ERA5's own mid/high cloud is a model,
not an observation (see `far-cloud-p1-findings-2026-10-22.md`). Re-run when there are more reader outcomes.

### Phase A follow-up — sunward canvas only (same day, same row set)

Restricted to overhead clear **and** sunward low < 15, grouped by where the forecast put the canvas, so the
ERA5 check (sunward only) is fair:

| Group | Slots | Mean sky ★ | 4★+ | Fcst sun canvas | ERA5 verified | ERA5 canvas ≥ 15 | ERA5 canvas ≥ 40 | ERA5 mean canvas |
|---|---|---|---|---|---|---|---|---|
| 1 sunward ≥ 40 | 67 | 3.91 | 0.91 | 84 | 65 | **0.63** | **0.57** | 78 |
| 2 sunward 15–39 | 34 | 3.09 | 0.09 | 26 | 30 | 0.40 | 0.30 | 36 |
| 3 anti-sun only | 94 | 3.11 | 0.18 | 1 | 49 | 0.35 | 0.16 | 43 |
| 4 no canvas (= B) | 320 | 2.92 | 0.03 | 0 | 248 | 0.45 | 0.20 | 24 |

Reading:
- **Sunward ≥ 40 has real signal.** ERA5 confirms a ≥ 40% canvas 57% of the time against 20% for the
  no-canvas baseline (≈ 3×), and its mean (78) sits close to the forecast's (84). Against the rule: 67 ≥ 30;
  +0.99★ over B; outcomes still too few; ERA5 65 verified at 0.63 ≥ 0.6. **Passes.**
- **Weak sunward canvas (15–39) and anti-sun-only canvas do not.** The evaluator rates both like B (3.09,
  3.11), and ERA5 does not separate them from the baseline. The earlier A1/A2 failure came from mixing these in.
- The 0.45 baseline at the ≥ 15 threshold is mostly thin cirrus the forecast missed (ERA5 mean 24); the ≥ 40
  threshold is the meaningful one.
- Still no ground truth from readers (5 outcomes in 180 days). The case rests on the evaluator and ERA5
  agreeing.

**Revised decision: Phase B goes ahead, narrowed** to sunward canvas ≥ 40 (rule below). Anti-sun canvas does
not count for triage.

Side note for the region gloss (#1835): it counts the larger of the two sides as canvas. Group 3 says the
evaluator does not reward anti-sun-only canvas, so the gloss can describe a canvas the ratings do not
back; its ratings gate limits the damage. Worth revisiting separately, not in Phase B.

---

## Phase B — un-demote overhead-clear spots with a horizon canvas (one PR, Sonnet builds)

### 1. The rule (strictly loosening; matches cohort A exactly)

A GO slot is demoted to MARGINAL, and flagged `"Clear all layers"`, when its **observer** layers are all
< 15% (today's test) **unless** the slot is directional and has a sunward canvas:

- directional = sun-side low, mid and high all non-null;
- sun-side low < 15; and
- `max(sunSideMid, sunSideHigh) >= SUNWARD_CANVAS_MIN` (= 40, from the Phase A follow-up). Anti-sun cloud
  is **not** counted.

Consequences, all intended: overhead cloud with clear horizons is **not** a new demotion (option (b) from
review); a weak (15–39) or anti-sun-only canvas stays demoted; sunward low 15–39% stays demoted; no horizon
data ⇒ today's behaviour. Stage 5 (`applyHorizonCloudDemotion`) is unchanged and still demotes sunward low
≥ 40%. Expected volume: about 67 slots in 180 days met this in the evaluation's own data, so the effect on
force-eval spend and roll-ups is small.

### 2. Code

- **Shared helper, static, in `service`** (e.g. `SlotCloud`): `isDirectional(WeatherConditions)`,
  `observer(w)`, `horizon(w)` returning a small record with `low`, `mid`, `high`, `isClear()`, and
  `canvas()` (max mid/high). **Do not** add methods to `WeatherConditions` (Jackson would serialise them;
  `ReviewedTypes`/`LitePickSentinelTest` would fail). `BriefingGlossService` switches to the helper but
  **keeps its region-wide all-or-nothing choice** (horizon only if every median-source slot is directional);
  its output must not change. Add a gloss test with a mixed region (one non-directional slot) if none exists.
- `BriefingSlotBuilder.buildSlot`: move all five `extractLayer` calls above the clear-sky demotion
  (~L246) and reuse the values in `WeatherConditions` (~L291–296) instead of extracting twice.
- `WeatherMetrics` gains the horizon values. Add an explicit 7-argument constructor delegating with nulls
  (22 calls in `BriefingVerdictEvaluatorTest`, 3 in `BriefingVerdictEvaluatorBoundaryTest`) and keep the
  4-argument one; Javadoc both.
- One private predicate `isClearSky(WeatherMetrics)` used by **both** `applyClearSkyDemotion` and
  `buildFlags`, so they cannot drift.
- Delete `logClearAllLayersDiagnostic` (~L680–711; "Temporary — remove once root cause is identified").
- `buildRegionSummary`: if Ask, emails or any other reader shows `region.summary`, reword "Clear at N of M"
  to something true for a canvas sky ("Go at N of M"); otherwise leave it and say so in the PR.

### 3. Tests (pin every stub argument; `test-improvement-standards.md`)

- `SlotCloudTest`: directional / non-directional / one null sun-side layer / null anti-sun; `isClear`,
  `canvas`.
- `BriefingVerdictEvaluatorTest`:
  - observer clear + sun-side low 5 + sun-side high 70 ⇒ GO kept, no flag;
  - observer clear + sun-side low 5 + sun-side high 0 + anti-sun high 90 ⇒ MARGINAL + flag (anti-sun ignored);
  - observer clear + horizons clear ⇒ MARGINAL + flag;
  - observer clear + no horizon data ⇒ MARGINAL + flag (today);
  - observer clear + sun-side low 25 + sun-side high 70 ⇒ MARGINAL + flag;
  - boundary: sun-side canvas 39 ⇒ MARGINAL + flag, 40 ⇒ GO; sun-side low 14 vs 15;
  - observer high 60 + horizons clear ⇒ not demoted (no new demotion).
- `BriefingSlotBuilderTest`: end to end, including observer clear + sun-side low 45 + canvas ⇒ MARGINAL via
  stage 5 and **no** `"Clear all layers"` flag.
- Gloss: all existing tests pass unmodified, plus the mixed-region test.

### 4. Docs and changelog

- `docs/engineering/reference/pipeline.md`: a sentence on the triage stage beside the gloss paragraph (no
  reference doc lists the verdict stages today).
- `changelog.d/YYYYMMDD-clear-sky-demotion-horizon.md`: `### Fixed — A clear sky overhead no longer demotes
  a spot when there is a strong cloud canvas toward the sun`. (The helper's `canvas()` may stay two-sided for
  the gloss; triage reads the sun-side layers directly.)
- PR body: Phase A's table, the decision, the chosen `CANVAS_MIN`, and the two cost effects above.

## Out of scope

- Other triage stages (mid-cloud, building trend) still read overhead cloud; same question, separate evidence.
- `forceEvalCap` and the selector's ordering.
- The roll-up may mix sampling points when horizon fetches failed for only some locations; per-slot is
  correct for triage.
