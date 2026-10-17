-- The neighbour guard's rule (docs/engineering/neighbour-guard-plan.md §2) applied to the ratings the
-- app SERVES, approximating EvaluationViewService's merge for each enabled sky place and window:
--   1. the cached result (cached_evaluation.results_json; its evaluatedAt, else the row's evaluated_at)
--      and the latest non-PENDING forecast_evaluation row (by forecast_run_at, read as UTC);
--   2. each source dropped when a SKIPPED_STABILITY disposition for the slot is later than it;
--   3. the cache wins when it is at least as fresh as the row (or the row says nothing), else the row,
--      whose sky rating is its sky_rating (as served once G1a passes it through).
-- Not modelled: the honesty filter (it empties zero-coverage regions, which only removes places) and
-- the region roster join (places are matched by name, enabled, sky type). Postgres-only. Read-only.
-- :since is the first window date; :km the radius; margin 2, floor 3, two witnesses, 150 m are the
-- plan's defaults.
-- A served sky place: enabled, in a region (unregioned slots are neither candidates nor neighbours),
-- and either untyped (the app briefs an untyped place as an ordinary sky place) or with an open-sky
-- type. A place whose types are only WOODLAND, BLUEBELL or WILDLIFE stands in for a canopy slot.
CREATE TEMP VIEW sky_place AS
SELECT l.id, l.name, l.lat, l.lon, l.elevation_m AS elev
FROM locations l
WHERE l.enabled AND l.region_id IS NOT NULL
  AND (NOT EXISTS (SELECT 1 FROM location_location_type t WHERE t.location_id = l.id)
       OR EXISTS (SELECT 1 FROM location_location_type t WHERE t.location_id = l.id
                  AND t.location_type IN ('LANDSCAPE', 'SEASCAPE', 'WATERFALL')));

-- One cached result per place and window. The app reads a place under its CURRENT region only; a
-- place that changed region can leave an entry under the old one, so this keeps the newest entry.
CREATE TEMP VIEW cache_all AS
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
WHERE c.evaluation_date >= CAST(:'since' AS DATE) AND c.target_type IN ('SUNRISE', 'SUNSET');

CREATE TEMP VIEW cache_src AS
SELECT id, d, t, rating, sky, written FROM (
  SELECT a.*, ROW_NUMBER() OVER (PARTITION BY id, d, t ORDER BY written DESC) AS rn FROM cache_all a) x
WHERE rn = 1;

-- The latest non-PENDING row per slot, exactly ONE: the app keeps the first of tied rows in the
-- query's order, which the query does not fix within a tie, so this takes the lowest id.
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

-- nb_hi: the highest neighbour at ANY height (a supporter can stand anywhere); nb: neighbours at a
-- similar height (both elevations unknown, or both known and within 150 m), the possible witnesses.
CREATE TEMP VIEW served_hi AS
SELECT a.d, a.t, a.id, a.name, a.sky, MAX(b.sky) AS nb_hi,
       SUM(CASE WHEN (a.elev IS NULL AND b.elev IS NULL)
                  OR (a.elev IS NOT NULL AND b.elev IS NOT NULL AND ABS(a.elev - b.elev) <= 150)
                THEN 1 ELSE 0 END) AS nb
FROM served a
JOIN served b
  ON b.d = a.d AND b.t = a.t AND b.id <> a.id AND b.sky IS NOT NULL
 AND 111.32 * SQRT((a.lat - b.lat) ^ 2 + ((a.lon - b.lon) * COS(RADIANS((a.lat + b.lat) / 2))) ^ 2)
     <= CAST(:'km' AS DOUBLE PRECISION)
WHERE a.sky IS NOT NULL
GROUP BY a.d, a.t, a.id, a.name, a.sky;

SELECT s.d, s.t, COUNT(*) AS rated, COUNT(s.sky) AS with_sky,
       (SELECT COUNT(*) FROM served_hi h WHERE h.d = s.d AND h.t = s.t
          AND h.sky >= 3 AND h.nb >= 1 AND h.sky - h.nb_hi >= 2) AS flagged_1w,
       (SELECT COUNT(*) FROM served_hi h WHERE h.d = s.d AND h.t = s.t
          AND h.sky >= 3 AND h.nb >= 2 AND h.sky - h.nb_hi >= 2) AS flagged,
       (SELECT STRING_AGG(h.name || ' ' || h.sky || ' vs ' || h.nb_hi || ' (' || h.nb || ')', '; ' ORDER BY h.name)
          FROM served_hi h WHERE h.d = s.d AND h.t = s.t
          AND h.sky >= 3 AND h.nb >= 1 AND h.sky - h.nb_hi >= 2) AS which
FROM served s
GROUP BY s.d, s.t
ORDER BY s.d, s.t;
