# The heat field's empty windows — investigating the scores/briefing join gap

**Status:** **RESOLVED.** The cause was a mount-only fetch in the frontend provider, not anything
in the backend. Fixed on this branch. Every hypothesis in this document — including the one it
originally headlined as "THE ANSWER" — was refuted by data; they are kept because the refutations
are the useful part.
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

---

## THE ANSWER: the session was holding yesterday's copy

`WindowFirstBriefingProvider` polled the briefing every ten minutes and again on window focus, and
fetched the batch ratings **once, in a mount-only `useEffect(..., [])`**. So on any tab left open,
verdicts / `bestRating` / the planner grid / hot topics all kept moving while the rows the heat
FIELD is drawn from stayed at whatever was true when the tab was opened. Open the Plan tab in the
evening, and the 01:16 batch writes the T+2/T+3 ratings that session never sees — `best spot 5★`
on the card with a blank thumbnail above it, indefinitely.

**The tell was a hard reload fixing it instantly**, which is worth remembering: it is the cheapest
discriminator between "the data is wrong" and "this session's copy of it is", and it should have
been step one rather than step five.

Fixed by giving both fetches one `refresh`, so the two payloads a single screen joins can never be
more than one cycle apart. Both are ETag-revalidated, so an unchanged cycle costs a 304.

### How it was pinned, after four wrong turns

Every layer was exonerated in order, and only then did the remaining suspect become the session
itself:

| check | result |
|---|---|
| Q1 — `cached_evaluation` coverage | Sat sunrise **163 of 163 rated**. Nothing missing. |
| Q2 — join keys | 12 hits, all `WOODLAND,BLUEBELL`, correctly withheld from a sky field. |
| Q4 — the freshness gate | `row_wins_freshness = 0` on **every** window. The gate never fires. |
| Endpoint, in the browser | `2026-08-22:SUNRISE` → 214 rows, **163 rated**, all `CACHED_EVALUATION`, window keys matching `/api/briefing`. |
| Frontend join, replayed live | **151 points** for Sat sunrise, **153** for Sat sunset. |
| DOM, same moment | Both Saturday tiles `data-unscored="true"`. |
| Hard reload | Five of six tiles paint. Only Sunday sunrise hatches — correctly, it has 0 rated rows. |

The last two rows are the whole diagnosis: correct data, correct join, wrong session.

---

## The theory this document originally headlined, which was ALSO wrong — and is now fixed anyway

Kept in full because it is a real asymmetry, it was not the cause of anything observed here, and
it has since been closed on its own merits. **Fixed:** precedence is now a single method,
`EvaluationViewService.cachedWins`, and neither path derives any part of it.

**`mergeToView` and `resolveForEnrichment` share the freshness gate but not its fallback** — four
lines apart in one file.

```java
// resolveForEnrichment — the BRIEFING path
if (cachedResult != null && cachedIsAtLeastAsFresh(...)) return cachedResult;
BriefingEvaluationResult fromRow = toEnrichmentResult(locationName, forecastRow);
return fromRow != null ? fromRow : cachedResult;      // ← falls back to the cached rating

// mergeToView — the /scores path
if (cachedResult != null && cachedIsAtLeastAsFresh(...)) return CACHED_EVALUATION;
if (forecastRow != null && forecastRow.getRating() != null) return FORECAST_EVALUATION_SCORED;
if (forecastRow != null && forecastRow.getTriage()...) return FORECAST_EVALUATION_TRIAGE;
return emptyView(...);                                 // ← Source.NONE, and the caller DROPS it
```

⚠️ **Refuted by Q4: `row_wins_freshness = 0` across every window, so this branch is never
reached in current production data.** The asymmetry is still real and still worth closing on its
own merits — the two methods genuinely disagree about what a won gate with an empty winner means —
but it caused none of what was observed here.

When the latest `forecast_evaluation` row is **newer than the cached row but carries neither a
rating nor a triage reason** — a bare base-forecast row, and `CLAUDE.md` records that roughly three
quarters of that table's rows have a null rating — the two paths part company:

| path | result |
|---|---|
| briefing card / rail / planner | cached rating survives → **5★** |
| `/scores` → heat field | `Source.NONE` → row dropped → **no point → empty field** |

Same tables, same gate, opposite outcome. The class comment asserts the two paths were unified
("Both paths now decide through `cachedIsAtLeastAsFresh`; a third reader must call it rather than
re-derive precedence") — they were unified on the **gate** and not on the **fallback**, and that
comment is why the gap survived review.

It is date-dependent for the obvious reason: T+0/T+1 get re-scored overnight so their cached rows
stay fresh, while the further-out days keep an older cached rating and then collect a newer
base-forecast row.

### What the production data said

- **Q1 — refutes H1 outright.** Saturday 22 Aug SUNRISE: **163 location entries, all 163 rated**.
  The live cache was full. The card's 5★ was *not* stale and the empty field was *not* truthful.
- **Q2 — refutes H2, H3 and H4.** The only rows failing a join test were the 12 bluebell/woodland
  locations, every one `WOODLAND,BLUEBELL` — correctly withheld from a sky field. No name drift,
  no region drift, nothing disabled.
- **Q3 — consistent with Q1**, e.g. Lake District 22 Aug SUNRISE 57/57 rated slots. It also turned
  up an unrelated curiosity worth knowing: the persisted payload serialises `LocalDate` as a JSON
  **array** (`[2026, 8, 20]`), so the cache's ObjectMapper is not the HTTP one. Harmless to the
  frontend, which reads the API's ISO strings, but it will surprise the next person to query this
  column.

### The fix, now applied

`mergeToView` falls back to the cached result when the forecast row wins the gate but says
nothing — exactly as `resolveForEnrichment` already did. A won gate means "this row is newer",
not "this row is an answer"; branch 4 was treating an empty winner as a denial.

It is expressed as a shared `cachedWins`, not as a fourth branch, and that shape is the point. The
first divergence was fixed by giving both paths the same *gate* and asserting in the class comment
that they were unified — which is exactly why the *fallback* divergence survived review. The
invariant that holds is not "both paths share the gate" but "neither path contains a precedence
decision of its own".

⚠️ **The objection to check first: this does not weaken the freshness gate.** The gate exists
because a stale 4★ was outliving a row triaged `HIGH_CLOUD` on 87–99% low cloud, one rating 47.9
hours out of date. In every case of that shape the newer row *is* triaged, so the fallback clause
is false and the cache still loses. It fires only where the newer row is empty, which carries no
contradicting information at all. `theGateStillBitesWhenTheRowSpeaks` pins that under the same
fixture as the fallback test, so the two cannot stop differing.

Scope: `/scores` now returns rows it previously omitted, so the heat field, the map's pin
visibility (`resolveStandDown` treats an unrated location with a triage row as stood down) and the
Close to home panel all gain data. That is the intent — those surfaces already show the cached
rating everywhere the briefing feeds them. Q4 below sizes it, and on the day it was measured
`dropped_to_none` was **0 on every window**, so the immediate production effect is nil; the change
is a trap removed rather than a visible fix.

### Q4 — how many slots the gap is currently eating

Run this to size the fix before and after. `dropped_to_none` is the bug's own count.

```bash
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour <<'SQL'
WITH cached AS (
  SELECT c.evaluation_date, c.target_type, c.updated_at,
         e.value->>'locationName' AS location_name,
         (e.value->>'rating')::int AS cached_rating
  FROM   cached_evaluation c
  CROSS JOIN LATERAL jsonb_array_elements(c.results_json::jsonb) AS e(value)
  WHERE  c.evaluation_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 5
    AND  e.value->>'rating' IS NOT NULL
), latest AS (
  SELECT DISTINCT ON (fe.location_id, fe.target_date, fe.target_type)
         fe.location_id, fe.target_date, fe.target_type,
         fe.forecast_run_at, fe.rating AS row_rating, fe.triage_reason
  FROM   forecast_evaluation fe
  WHERE  fe.target_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 5
  ORDER  BY fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at DESC
)
SELECT c.evaluation_date, c.target_type,
       COUNT(*) AS cached_rated,
       COUNT(*) FILTER (
         WHERE f.forecast_run_at IS NOT NULL
           AND c.updated_at < (f.forecast_run_at AT TIME ZONE 'Europe/London')
       ) AS row_wins_freshness,
       COUNT(*) FILTER (
         WHERE f.forecast_run_at IS NOT NULL
           AND c.updated_at < (f.forecast_run_at AT TIME ZONE 'Europe/London')
           AND f.row_rating IS NULL AND f.triage_reason IS NULL
       ) AS dropped_to_none,
       COUNT(*) FILTER (
         WHERE f.forecast_run_at IS NOT NULL
           AND c.updated_at < (f.forecast_run_at AT TIME ZONE 'Europe/London')
           AND f.row_rating IS NULL AND f.triage_reason IS NOT NULL
       ) AS shown_as_triage
FROM   cached c
JOIN   locations l ON l.name = c.location_name
LEFT   JOIN latest f ON f.location_id = l.id
                    AND f.target_date = c.evaluation_date
                    AND f.target_type = c.target_type
GROUP  BY 1, 2 ORDER BY 1, 2;
SQL
```

`forecast_run_at` is a naive `LocalDateTime` recorded in **Europe/London** while `updated_at` is a
timestamptz, hence the `AT TIME ZONE` — comparing them raw is silently an hour out through BST,
which `forecastRunInstant`'s own javadoc warns about.

⚠️ `updated_at`, **not** `evaluated_at`. The hydration path takes `getUpdatedAt()` and says why:
`evaluated_at` is only ever set for a *new* row, so a slot re-evaluated for three days running
still carries its day-one stamp. Q1 above reports `evaluated_at` because it is only asking "when
did anything last land here"; the freshness gate is a different question.

---

## What was originally suspected, and why it was wrong

Kept because the reasoning was sound and the refutation is the useful part.

### The prime suspect (REFUTED by Q1)

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

That is a code-visible mechanism for exactly the divergence observed, and it pointed the blame the
opposite way from first appearances: the card's 5★ *might* have been the stale value.

**Q1 refuted it.** The live cache held 163 rated entries for that window, so nothing was stale and
the card was right. The mechanism is real and still worth knowing — an absent lookup genuinely does
preserve a build-time rating — but it was not what happened here.

### Ruled out

- **The endpoint's date range.** `today-2 … today+FORECAST_HORIZON_DAYS` with the horizon at 5,
  so Saturday (T+2) and Sunday (T+3) are inside it.
- **The DTO's shape.** `LocationEvaluationView` carries both `locationId` and `rating`, so the
  frontend's id-first / name-second join has the keys it needs.
- **A frontend indexing slip.** `buildHeatSpots` and `buildHeatPointSets` are driven by the same
  `upcomingEvents` array, so the positional `scores[index]` cannot be off by a window.

### Still possible, and worth the same query pass

- ~~**H1 — nothing rated at T+2/T+3.**~~ **Refuted:** 163 of 163 entries rated for Sat sunrise.
- **H2 — rated, but not sky.** The frontend withholds scores from non-sky-prompt locations, while
  `PlanWindowProjector.bestRating` excludes only **canopy** slots. The gap between the two
  populations is narrower than it first looks: `SKY_SUBJECT_TYPES` is
  `LANDSCAPE / SEASCAPE / WATERFALL` and an **untyped** location counts as sky, so the only
  locations that feed `bestRating` and not the field are **WILDLIFE-only** ones (woodland is
  canopy and is excluded from both). **Refuted:** Q2's only hits were 12 `WOODLAND,BLUEBELL`
  locations, correctly withheld; no wildlife-only rated entries at all.
- ~~**H3 — name drift.**~~ **Refuted:** every rated entry matched a location by name.
- ~~**H4 — region drift.**~~ **Refuted:** `cached_region` matched `locations_current_region`
  everywhere.

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

PR #573 makes all three unscored marks read the window's served `bestRating`. With the cause now
known that choice is not merely self-consistent, it is **correct** — and it is also what would
have made the underlying defect harmless: `bestRating` rides the briefing, which was refreshing
all along, so the mark would have tracked the live forecast even while the field was frozen. The
two fixes ship together on the same branch.
