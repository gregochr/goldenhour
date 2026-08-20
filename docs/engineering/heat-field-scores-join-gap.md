# The heat field's empty windows — investigating the scores/briefing join gap

**Status:** open. Diagnosis queries written, not yet run against production.
**Raised by:** a v2.18.13 screenshot, 2026-08-19 evening (today = Thu 20 Aug).

---

## 1. The observation

On the Plan tab, six windows. Three painted a heat field, three did not:

| Window | horizon | `best spot` on its own card | heat field |
|---|---|---|---|
| Thu sunset | T+0 | 4★ | painted |
| Fri sunrise | T+1 | 4★ | painted |
| Fri sunset | T+1 | 4★ | painted |
| **Sat sunrise** | **T+2** | **5★** | **empty** |
| **Sat sunset** | **T+2** | **4★** | **empty** |
| Sun sunrise | T+3 | *(none)* | empty |

The regional planner on the same screen showed Saturday means — Lake District **3.7★**,
North York Moors **3★**, Yorkshire Dales **2.9★** — and Saturday sunrise carried the
**BEST BET** flag. Sunday showed no star anywhere.

So for Saturday, two surfaces on one screen disagreed about whether anything was rated. The
split lands exactly on Gate 4's boundary (T+0/T+1 all stabilities, T+2 SETTLED+TRANSITIONAL,
T+3 SETTLED only), which is suggestive but is **not** on its own an explanation: both surfaces
are supposed to read the same tables.

## 2. The two paths, and where they can diverge

Both nominally resolve through `EvaluationViewService`, which is the whole point of that class
("They must not have different *rules*, and for three days in production they did").

- **Heat field** — `GET /api/briefing/evaluate/scores` → `EvaluationViewService.forDateRange`
  → per enabled location × date × type, merge `cached_evaluation` (region-grained, per-location
  ratings inside `results_json`) with the latest `forecast_evaluation` row; drop `Source.NONE`.
  The frontend then joins those rows onto the location roster in `buildHeatSpots`.
- **Card / rail / planner** — `BriefingService.reEnrichVerdicts` → `enrichWithCachedScores` over
  the **persisted** `daily_briefing_cache` payload, resolving live scores through
  `EvaluationViewService.getScoresForEnrichmentBulk`.

### The prime suspect

`BriefingService.enrichSlot`:

```java
BriefingEvaluationResult eval = cached.get(slot.locationName());
if (eval == null) {
    return slot;          // ← the persisted slot keeps whatever rating it was built with
}
if (eval.rating() != null) { ...set... }
if (eval.triageReason() != null && slot.claudeRating() != null) { ...clear... }
```

The serve path re-enriches an **already-enriched** payload. A rating baked into
`daily_briefing_cache` at build time (04:00 / 14:00 / 22:00) therefore **survives** serve-time
re-enrichment whenever the live lookup returns no entry for that location. Only a live *triage*
row clears it; a plain absence does not.

That is a code-visible mechanism for exactly the divergence observed, and it points the blame the
opposite way from first appearances: **the card's 5★ may be the stale value and the empty field
the truthful one.**

### Ruled out

- **The endpoint's date range.** `today-2 … today+FORECAST_HORIZON_DAYS` with the horizon at 5,
  so Saturday (T+2) and Sunday (T+3) are inside it.
- **The DTO's shape.** `LocationEvaluationView` carries both `locationId` and `rating`, so the
  frontend's id-first / name-second join has the keys it needs.
- **A frontend indexing slip.** `buildHeatSpots` and `buildHeatPointSets` are driven by the same
  `upcomingEvents` array, so the positional `scores[index]` cannot be off by a window.

### Still possible, and worth the same query pass

- **H1 — nothing rated at T+2/T+3.** `cached_evaluation` genuinely has no rated entries for those
  windows; `/scores` is right, and the card is stale per the mechanism above.
- **H2 — rated, but not sky.** The frontend withholds scores from non-sky-prompt locations, while
  `PlanWindowProjector.bestRating` excludes only **canopy** slots. The gap between the two
  populations is narrower than it first looks: `SKY_SUBJECT_TYPES` is
  `LANDSCAPE / SEASCAPE / WATERFALL` and an **untyped** location counts as sky, so the only
  locations that feed `bestRating` and not the field are **WILDLIFE-only** ones (woodland is
  canopy and is excluded from both). It would take a window whose entire rated set was
  wildlife-only, which is a stretch — but the query costs nothing extra.
- **H3 — name drift.** `results_json` is keyed by location *name*; a renamed location leaves the
  cached entry unreachable from the current roster.
- **H4 — region drift.** `buildViews` looks the cache row up as `location's current region | date
  | type`, while `enrichWithCachedScores` uses the *briefing payload's* region name. A location
  moved between regions makes those two keys differ.

## 3. The queries

Read-only, and verified against a real Postgres with a schema-accurate fixture before being
handed over — including one that reproduces the hypothesised production state end to end, so the
queries are known to discriminate it rather than merely to parse.

⚠️ **`results_json` is a JSON ARRAY, not an object.** `EvaluationViewService` reads it as
`List<BriefingEvaluationResult>` and keys the map by `locationName` in Java; the location name is
a *field inside each element*, not the JSON key. The first cut of these queries used
`jsonb_each` and died with `cannot call jsonb_each on a non-object`. Use `jsonb_array_elements`
and `e.value->>'locationName'`.

Run each block as-is:

### Q1 — the discriminator: rated coverage per window

```bash
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour <<'SQL'
SELECT c.evaluation_date,
       c.target_type,
       COUNT(DISTINCT c.region_name)                            AS regions,
       COUNT(*)                                                 AS location_entries,
       COUNT(*) FILTER (WHERE e.value->>'rating' IS NOT NULL)   AS rated_entries,
       MAX(c.evaluated_at)                                      AS newest_write
FROM   cached_evaluation c
CROSS JOIN LATERAL jsonb_array_elements(c.results_json::jsonb) AS e(value)
WHERE  c.evaluation_date BETWEEN CURRENT_DATE - 2 AND CURRENT_DATE + 5
GROUP  BY 1, 2
ORDER  BY 1, 2;
SQL
```

**Read it like this.** `rated_entries = 0` (or no rows at all) for the T+2/T+3 windows while
T+0/T+1 are populated ⟹ **H1**, and the card is the stale surface. Non-zero `rated_entries` for a
window whose field was empty ⟹ the rows exist and something downstream drops them: go to Q2.

### Q2 — if rows exist: does the roster reach them?

Self-targeting rather than parameterised by date: it walks every rated entry in the plan window
and returns **only the ones that fail a join test**, so an empty result is itself the answer.

```bash
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour <<'SQL'
SELECT c.evaluation_date,
       c.target_type,
       c.region_name                       AS cached_region,
       e.value->>'locationName'            AS cached_location_name,
       (e.value->>'rating')::int           AS rating,
       l.id                                AS matched_location_id,
       l.enabled,
       r.name                              AS locations_current_region,
       string_agg(lt.location_type, ',')   AS location_types
FROM   cached_evaluation c
CROSS JOIN LATERAL jsonb_array_elements(c.results_json::jsonb) AS e(value)
LEFT   JOIN locations l               ON l.name = e.value->>'locationName'
LEFT   JOIN regions r                 ON r.id = l.region_id
LEFT   JOIN location_location_type lt ON lt.location_id = l.id
WHERE  c.evaluation_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 5
  AND  e.value->>'rating' IS NOT NULL
GROUP  BY 1, 2, 3, 4, 5, 6, 7, 8
HAVING l.id IS NULL
    OR l.enabled = false
    OR r.name IS DISTINCT FROM c.region_name
    OR COALESCE(bool_or(lt.location_type
         IN ('LANDSCAPE', 'SEASCAPE', 'WATERFALL')), true) = false
ORDER  BY 1, 2, 4;
SQL
```

The sky test mirrors `isSkyPromptCandidate` exactly, **including its rule that an untyped location
counts** — hence the `COALESCE(..., true)`, which is what stops a location with no rows in
`location_location_type` being reported as a false positive.

- `matched_location_id IS NULL` ⟹ **H3** (name drift).
- `cached_region <> locations_current_region` ⟹ **H4** (region drift); the `/scores` path misses
  the row while the briefing path finds it, which is precisely the observed asymmetry.
- every rated row **WILDLIFE-only** ⟹ **H2**. Waterfall and untyped both count as sky, so they
  are not evidence for it.
- `enabled = false` ⟹ the roster excludes it and so does the field, correctly.

### Q3 — confirming the stale-card mechanism

Only worth running if Q1 says **H1**. It asks whether the payload being served still carries
ratings the live tables no longer back.

```bash
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour <<'SQL'
SELECT b.generated_at,
       d.value->>'date'                    AS day,
       es.value->>'targetType'             AS target,
       reg.value->>'regionName'            AS region,
       COUNT(*) FILTER (WHERE s.value->>'claudeRating' IS NOT NULL) AS rated_slots,
       COUNT(*)                            AS slots
FROM   daily_briefing_cache b
CROSS JOIN LATERAL jsonb_array_elements(b.payload::jsonb -> 'days')        AS d(value)
CROSS JOIN LATERAL jsonb_array_elements(d.value -> 'eventSummaries')       AS es(value)
CROSS JOIN LATERAL jsonb_array_elements(es.value -> 'regions')             AS reg(value)
CROSS JOIN LATERAL jsonb_array_elements(reg.value -> 'slots')              AS s(value)
WHERE  (d.value->>'date')::date BETWEEN CURRENT_DATE AND CURRENT_DATE + 5
GROUP  BY 1, 2, 3, 4
ORDER  BY 2, 3, 4;
SQL
```

(`daily_briefing_cache` is a single-row table — V59: "id = 1 always; upserted on every briefing
refresh" — so there is no newest-row selection to make.)

`rated_slots > 0` for a window Q1 reported as having **zero** rated entries is the confirmation:
the payload is carrying a build-time rating the live cache no longer supplies, and
`enrichSlot`'s `eval == null → return slot` branch is why.

> ⚠️ The two blobs differ here and it does not matter. `BriefingEvaluationResult.rating` is
> serialised as an explicit JSON `null`, while `BriefingSlot.claudeRating` is
> `@JsonInclude(NON_NULL)` and is **omitted entirely**. `->>` yields SQL `NULL` for both a JSON
> null and a missing key, so the filters above are correct for either shape — do not "fix" them
> into `jsonb_exists` checks.

> ⚠️ `generated_at` matters as much as the counts. If the payload predates the last batch cycle,
> every number in it is a build-time value and the question is why the rebuild has not happened,
> not why the slots are stale.

## 4. What follows from each outcome

- **H1 confirmed.** The fix is in `enrichSlot`: a slot whose live lookup returns nothing should
  have its rating **cleared**, not preserved — absence is an answer. That is a one-branch change
  with a wide blast radius (it would empty every stale rating currently on the Plan tab), so it
  wants its own change and its own before/after count from Q1. Note it also means the heat
  field's empty Saturday was *correct*, and that the honest mark for those tiles is the unscored
  hatch after all — but keyed on a `bestRating` that has stopped lying, not on the point set.
- **H2.** Either the field should include the non-sky ratings (it should not — a waterfall rating
  is not a sky claim) or `bestRating` should exclude them, matching `isSkyPromptCandidate` rather
  than the narrower canopy test. The latter is the consistent choice and is a small change in
  `PlanWindowProjector`.
- **H3 / H4.** Both are join-key bugs in `EvaluationViewService.buildViews`, and both argue for
  keying `cached_evaluation` lookups on `location_id` rather than on names carried in JSON.

## 5. Meanwhile, on the UI

PR #573 makes all three unscored marks read the window's served `bestRating`, so the tile, the
rail and the card can no longer contradict each other on screen. That is right regardless of
which side turns out to be stale — but it is **self-consistency, not accuracy**. If H1 is
confirmed, the Plan tab is consistently optimistic until `enrichSlot` is fixed, and this document
is the reason why.
