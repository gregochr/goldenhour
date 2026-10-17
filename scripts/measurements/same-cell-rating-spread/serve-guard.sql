-- The neighbour guard's rule (docs/engineering/neighbour-guard-plan.md §2) applied to the ratings the
-- app SERVES, approximating EvaluationViewService's merge for each enabled sky place and window:
--   1. the cached result (cached_evaluation.results_json; its evaluatedAt, else the row's evaluated_at)
--      and the latest non-PENDING forecast_evaluation row (by forecast_run_at, read as UTC);
--   2. each source dropped when a SKIPPED_STABILITY disposition for the slot is later than it;
--   3. the cache wins when it is at least as fresh as the row (or the row says nothing), else the row,
--      whose sky rating is its sky_rating (as served once G1a passes it through).
-- Not modelled: the honesty filter, which cannot change a count. It empties only a region whose
-- scoredLocationCount is 0 (BriefingHonestyFilter: no slot in it carries a Claude rating), and a
-- skyRating never exists without that rating, so a blanked region holds no candidate, supporter or
-- witness; a lightly evaluated region is only flagged, not emptied. Also not modelled: the region
-- roster join (places are matched by name, enabled, sky type). Postgres-only. Read-only.
-- :since is the first window date; :km the radius; margin 2, floor 3, two witnesses, 150 m are the
-- plan's defaults.
-- A served sky place: enabled, in a region (unregioned slots are neither candidates nor neighbours),
-- and either untyped (the app briefs an untyped place as an ordinary sky place) or with an open-sky
-- type. A place whose types are only WOODLAND, BLUEBELL or WILDLIFE stands in for a canopy slot.
CREATE TEMP VIEW sky_place AS
SELECT l.id, l.name, l.lat, l.lon, l.elevation_m AS elev, l.region_id
FROM locations l
WHERE l.enabled AND l.region_id IS NOT NULL
  AND (NOT EXISTS (SELECT 1 FROM location_location_type t WHERE t.location_id = l.id)
       OR EXISTS (SELECT 1 FROM location_location_type t WHERE t.location_id = l.id
                  AND t.location_type IN ('LANDSCAPE', 'SEASCAPE', 'WATERFALL')));

-- One cached result per place and window, read under the place's CURRENT region only, as
-- EvaluationViewService builds its cache key: an entry left under an old or renamed region is never
-- read by the app, so it is never read here.
CREATE TEMP VIEW cache_src AS
SELECT p.id, c.evaluation_date AS d, c.target_type AS t,
       CAST(r ->> 'rating' AS INTEGER) AS rating, CAST(r ->> 'skyRating' AS INTEGER) AS sky,
       -- evaluatedAt is written by the Jackson 2 mapper as epoch seconds; an ISO string is read too.
       -- With none, the region row's updated_at, as BriefingEvaluationService rehydrates it (its
       -- evaluated_at is set only when the row is created and later writes do not advance it).
       COALESCE(CASE WHEN r ->> 'evaluatedAt' ~ '^[0-9.]+$'
                     THEN TO_TIMESTAMP(CAST(r ->> 'evaluatedAt' AS DOUBLE PRECISION))
                     ELSE CAST(r ->> 'evaluatedAt' AS TIMESTAMPTZ) END,
                c.updated_at) AS written
FROM cached_evaluation c
CROSS JOIN LATERAL json_array_elements(CAST(c.results_json AS json)) AS r
JOIN sky_place p ON p.name = r ->> 'locationName'
JOIN regions g ON g.id = p.region_id AND g.name = c.region_name
WHERE c.evaluation_date >= CAST(:'since' AS DATE) AND c.target_type IN ('SUNRISE', 'SUNSET');

-- The latest non-PENDING row per slot, exactly ONE. On a tie (two rows with the same forecast_run_at)
-- the app keeps whichever the database returns first, which its query does not fix, so no script can
-- mirror it exactly; this takes the lowest id. Ties do not occur in practice: over 17 Aug - 17 Oct 2026
-- no SUNRISE/SUNSET slot had two latest rows (checked 2026-10-17), so the choice changes no count.
CREATE TEMP VIEW row_src AS
SELECT id, d, t, rating, sky, triage_reason, written FROM (
  SELECT e.location_id AS id, e.target_date AS d, e.target_type AS t, e.rating, e.sky_rating AS sky,
         e.triage_reason, e.forecast_run_at AT TIME ZONE 'UTC' AS written,
         ROW_NUMBER() OVER (PARTITION BY e.location_id, e.target_date, e.target_type
                            ORDER BY e.forecast_run_at DESC, e.id ASC) AS rn
  FROM forecast_evaluation e
  JOIN sky_place p ON p.id = e.location_id
  WHERE e.target_date >= CAST(:'since' AS DATE) AND e.target_type IN ('SUNRISE', 'SUNSET')
    AND (e.batch_state IS NULL OR e.batch_state <> 'PENDING')) x
WHERE rn = 1;

CREATE TEMP VIEW skip_src AS
SELECT p.id, d.evaluation_date AS d, d.event_type AS t, MAX(d.created_at) AS skip_at
FROM forecast_run_disposition d JOIN sky_place p ON p.name = d.location_name
WHERE d.disposition = 'SKIPPED_STABILITY' AND d.evaluation_date >= CAST(:'since' AS DATE)
GROUP BY p.id, d.evaluation_date, d.event_type;

CREATE TEMP VIEW slot_keys AS
SELECT id, d, t FROM cache_src UNION SELECT id, d, t FROM row_src;

-- Live sources after retraction, then the precedence rule.
CREATE TEMP VIEW served AS
SELECT k.id, k.d, k.t, p.name, p.lat, p.lon, p.elev,
       CASE WHEN c_live THEN c.sky ELSE r.sky END AS sky,
       CASE WHEN c_live THEN c.rating ELSE r.rating END AS rating
FROM slot_keys k
JOIN sky_place p ON p.id = k.id
LEFT JOIN cache_src c ON c.id = k.id AND c.d = k.d AND c.t = k.t
LEFT JOIN row_src r ON r.id = k.id AND r.d = k.d AND r.t = k.t
LEFT JOIN skip_src s ON s.id = k.id AND s.d = k.d AND s.t = k.t
CROSS JOIN LATERAL (SELECT
    (c.id IS NOT NULL AND NOT (s.skip_at IS NOT NULL AND c.written IS NOT NULL AND s.skip_at > c.written)) AS cache_ok,
    (r.id IS NOT NULL AND (r.rating IS NOT NULL OR r.triage_reason IS NOT NULL)
       AND NOT (s.skip_at IS NOT NULL AND s.skip_at > r.written)) AS row_ok) AS ok
CROSS JOIN LATERAL (SELECT ok.cache_ok AND (NOT ok.row_ok OR c.written IS NULL OR c.written >= r.written) AS c_live) AS w
WHERE (ok.cache_ok OR ok.row_ok)
  AND (CASE WHEN c_live THEN c.rating ELSE r.rating END) IS NOT NULL;

-- One statement with MATERIALIZED stages, so the merge runs once (as views, the correlated counts
-- re-ran it per window and the query took minutes on production).
-- nb_hi: the highest neighbour at ANY height (a supporter can stand anywhere); nb: neighbours at a
-- similar height (both elevations unknown, or both known and within 150 m), the possible witnesses.
WITH sv AS MATERIALIZED (SELECT * FROM served),
hi AS MATERIALIZED (
  SELECT a.d, a.t, a.id, a.name, a.sky, MAX(b.sky) AS nb_hi,
         SUM(CASE WHEN (a.elev IS NULL AND b.elev IS NULL)
                    OR (a.elev IS NOT NULL AND b.elev IS NOT NULL AND ABS(a.elev - b.elev) <= 150)
                  THEN 1 ELSE 0 END) AS nb
  FROM sv a
  JOIN sv b
    ON b.d = a.d AND b.t = a.t AND b.id <> a.id AND b.sky IS NOT NULL
   AND ABS(a.lat - b.lat) < 0.1 AND ABS(a.lon - b.lon) < 0.2
   AND 111.32 * SQRT((a.lat - b.lat) ^ 2 + ((a.lon - b.lon) * COS(RADIANS((a.lat + b.lat) / 2))) ^ 2)
       <= CAST(:'km' AS DOUBLE PRECISION)
  WHERE a.sky IS NOT NULL
  GROUP BY a.d, a.t, a.id, a.name, a.sky),
flags AS (
  SELECT d, t,
         SUM(CASE WHEN sky >= 3 AND nb >= 1 AND sky - nb_hi >= 2 THEN 1 ELSE 0 END) AS flagged_1w,
         SUM(CASE WHEN sky >= 3 AND nb >= 2 AND sky - nb_hi >= 2 THEN 1 ELSE 0 END) AS flagged,
         STRING_AGG(CASE WHEN sky >= 3 AND nb >= 1 AND sky - nb_hi >= 2
                         THEN name || ' ' || sky || ' vs ' || nb_hi || ' (' || nb || ')' END, '; ') AS which
  FROM hi GROUP BY d, t)
SELECT s.d, s.t, COUNT(*) AS rated, COUNT(s.sky) AS with_sky,
       COALESCE(MAX(f.flagged_1w), 0) AS flagged_1w, COALESCE(MAX(f.flagged), 0) AS flagged,
       MAX(f.which) AS which
FROM sv s LEFT JOIN flags f ON f.d = s.d AND f.t = s.t
GROUP BY s.d, s.t
ORDER BY s.d, s.t;
