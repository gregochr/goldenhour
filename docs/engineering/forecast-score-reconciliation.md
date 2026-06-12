# forecast_score reconciliation queries (Pass 2)

These queries prove the Pass 2 dual-write (`forecast_score`) matches the live
serving payload (`cached_evaluation.results_json`) before Pass 4 migrates the
read side. They are **read-only** and safe to run against prod Postgres. Run
them as the empirical acceptance for the dual-write and, later, as the proof
that Pass 4's read migration is faithful.

Scope note — what `forecast_score` records this pass:

| forecast_type | id | what is written |
|---|---|---|
| SKY | 1 | pre-combine sky visitor score (1–5), summary = response prose |
| FIERY_SKY | 2 | `fiery_sky` (0–100), summary NULL |
| GOLDEN_HOUR | 3 | `golden_hour` (0–100), summary NULL |
| TIDAL | 4 | tide visitor score (1–5) when the tide applies and does not abstain, summary = deterministic clause |

**Excluded from `forecast_score` (so excluded from reconciliation):**
- **Triaged candidates** — never evaluated, never reach the write seam → no rows.
- **Sky-not-forecast** — a parseable response where Claude omitted the rating
  (`results_json.summary` = `'Claude did not forecast the fiery sky and golden hour for this location'`).
  The combiner never runs, so there is no genuine component score; the served
  1★ substitution is not recorded. **These elements are excluded below the same
  way triaged ones are.** (Deviation from a literal reading of the runbook's
  reconciliation #2, which says "every non-triaged element" — flagged here
  deliberately; see the Pass 2 report.)
- BLUEBELL / basic_* / inversion / headline — not written this pass.

`forecast_score` is keyed by `location_id`; `results_json` carries `locationName`.
The join is `locations.name = elem->>'locationName'` for the same
`(evaluation_date, event_type/target_type)`.

---

## Query 1 — per-night component counts (acceptance #1)

Run for the first night with the flag on. Substitute the date.

```sql
SELECT ft.code, COUNT(*) AS rows
FROM forecast_score fs
JOIN forecast_type ft ON ft.id = fs.forecast_type_id
WHERE fs.evaluation_date = DATE '2026-06-13'
GROUP BY ft.code
ORDER BY ft.code;
```

**Expectation:** `SKY` = `FIERY_SKY` = `GOLDEN_HOUR`, each equal to the night's
scored-location count (non-triaged, non-sky-not-forecast). `TIDAL` equals the
tide-applicable coastal count (coastal locations whose tide did not abstain).

---

## Query 2 — served elements expanded (shared CTE for queries 3–6)

This CTE flattens `results_json` into one row per served element, excluding
triaged and sky-not-forecast elements. Prepend it to each query below.

```sql
WITH served AS (
    SELECT
        ce.evaluation_date,
        ce.target_type,
        elem->>'locationName'               AS location_name,
        (elem->>'rating')::int              AS rating,
        (elem->>'fierySkyPotential')::int   AS fiery_sky,
        (elem->>'goldenHourPotential')::int AS golden_hour,
        elem->>'summary'                    AS summary
    FROM cached_evaluation ce,
         LATERAL jsonb_array_elements(ce.results_json::jsonb) AS elem
    WHERE ce.evaluation_date BETWEEN DATE '2026-06-13' AND DATE '2026-06-15'  -- N>=3 nights
      AND elem->>'triageReason' IS NULL                                       -- not triaged
      AND COALESCE(elem->>'summary', '') <>
          'Claude did not forecast the fiery sky and golden hour for this location'  -- not sky-not-forecast
)
```

---

## Query 3 — every served element has matching SKY/FIERY/GOLDEN; potentials equal (acceptance #2)

```sql
-- <prepend Query 2 CTE>
SELECT s.evaluation_date, s.target_type, s.location_name,
       (sky.id   IS NULL) AS missing_sky,
       (fiery.id IS NULL) AS missing_fiery,
       (golden.id IS NULL) AS missing_golden,
       fiery.score  AS fiery_row,  s.fiery_sky  AS fiery_json,
       golden.score AS golden_row, s.golden_hour AS golden_json
FROM served s
JOIN locations l ON l.name = s.location_name
LEFT JOIN forecast_score sky    ON sky.forecast_type_id = 1
       AND sky.location_id = l.id    AND sky.evaluation_date = s.evaluation_date
       AND sky.event_type = s.target_type
LEFT JOIN forecast_score fiery  ON fiery.forecast_type_id = 2
       AND fiery.location_id = l.id  AND fiery.evaluation_date = s.evaluation_date
       AND fiery.event_type = s.target_type
LEFT JOIN forecast_score golden ON golden.forecast_type_id = 3
       AND golden.location_id = l.id AND golden.evaluation_date = s.evaluation_date
       AND golden.event_type = s.target_type
WHERE sky.id IS NULL OR fiery.id IS NULL OR golden.id IS NULL
   OR fiery.score  <> s.fiery_sky
   OR golden.score <> s.golden_hour;
```

**Expectation:** **zero rows.** Any row is a missing component or a potential
that diverges from the served JSON.

---

## Query 4 — inland: SKY score equals the served rating exactly (acceptance #2)

Inland = no `location_tide_type` rows.

```sql
-- <prepend Query 2 CTE>
SELECT s.location_name, s.evaluation_date, s.target_type, sky.score AS sky_row, s.rating AS json_rating
FROM served s
JOIN locations l ON l.name = s.location_name
JOIN forecast_score sky ON sky.forecast_type_id = 1
       AND sky.location_id = l.id AND sky.evaluation_date = s.evaluation_date
       AND sky.event_type = s.target_type
WHERE NOT EXISTS (SELECT 1 FROM location_tide_type lt WHERE lt.location_id = l.id)
  AND sky.score <> s.rating;
```

**Expectation:** **zero rows** (inland served rating is sky alone).

---

## Query 5 — coastal: served rating equals ROUND(avg of the 1–5 peer rows) half-up (acceptance #2)

Coastal = has `location_tide_type` rows. The 1–5 combiner peers are SKY (1) and
TIDAL (4). Postgres `ROUND(numeric)` rounds half away from zero — matching the
combiner's `Math.round` half-up for these non-negative scores.

```sql
-- <prepend Query 2 CTE>
SELECT s.location_name, s.evaluation_date, s.target_type, s.rating AS json_rating,
       ROUND(AVG(peer.score))::int AS combined_from_rows
FROM served s
JOIN locations l ON l.name = s.location_name
JOIN forecast_score peer ON peer.location_id = l.id
       AND peer.evaluation_date = s.evaluation_date AND peer.event_type = s.target_type
       AND peer.forecast_type_id IN (1, 4)            -- SKY + TIDAL (the 1-5 peers)
WHERE EXISTS (SELECT 1 FROM location_tide_type lt WHERE lt.location_id = l.id)
GROUP BY s.location_name, s.evaluation_date, s.target_type, s.rating
HAVING s.rating <> ROUND(AVG(peer.score))::int;
```

**Expectation:** **zero rows.** Note a coastal location whose tide abstained has
only a SKY peer, so the average is the sky score — still correct.

---

## Query 6 — zero forecast_score rows for triaged candidates (acceptance #2)

```sql
SELECT ce.evaluation_date, ce.target_type, elem->>'locationName' AS location_name
FROM cached_evaluation ce,
     LATERAL jsonb_array_elements(ce.results_json::jsonb) AS elem
JOIN locations l ON l.name = elem->>'locationName'
JOIN forecast_score fs ON fs.location_id = l.id
       AND fs.evaluation_date = ce.evaluation_date AND fs.event_type = ce.target_type
WHERE ce.evaluation_date BETWEEN DATE '2026-06-13' AND DATE '2026-06-15'
  AND elem->>'triageReason' IS NOT NULL;
```

**Expectation:** **zero rows** (triaged candidates write nothing).

---

## Query 7 — provenance: pipeline_run_id populated on nightly rows, NULL on sync writes (acceptance #4)

```sql
SELECT (pipeline_run_id IS NOT NULL) AS has_run, COUNT(*)
FROM forecast_score
WHERE evaluation_date = DATE '2026-06-13'
GROUP BY (pipeline_run_id IS NOT NULL);
```

**Expectation:** nightly-written rows carry a non-NULL `pipeline_run_id`; a
sync/admin re-evaluation write carries NULL.

---

## Query 8 — dual-write health: rows on a clean night, no orphaned provenance

```sql
-- any forecast_score row whose pipeline_run_id does not resolve (should be impossible — FK):
SELECT fs.id FROM forecast_score fs
LEFT JOIN pipeline_run pr ON pr.id = fs.pipeline_run_id
WHERE fs.pipeline_run_id IS NOT NULL AND pr.id IS NULL;
```

**Expectation:** **zero rows** (FK guarantees it; the query documents intent).

Combine with a check of the application log for
`forecast_score dual-write FAILED` ERROR lines on a clean night — there should
be none.
