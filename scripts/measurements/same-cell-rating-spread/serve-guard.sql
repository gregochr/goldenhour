-- The neighbour guard's rule (docs/engineering/neighbour-guard-plan.md §2) applied to what the app
-- actually SERVES: the cached_evaluation store, one result per location per window, whatever run or
-- model wrote it, across regions. Postgres-only (reads the results_json array). Read-only.
-- :since is the first window date to include; :km the radius; margin 2 and floor 3 are the defaults.
-- A canopy slot is approximated as a location whose only types are WOODLAND/BLUEBELL/WILDLIFE, since
-- the stored result does not say which evaluator wrote it. nb_hi is the highest neighbour at ANY height
-- (any nearby place can support a high rating); nb counts only neighbours at a similar height (both
-- elevations unknown, or both known and within 150 m), the ones that may count against it.
CREATE TEMP VIEW served AS
SELECT c.evaluation_date AS d, c.target_type AS t, l.id, l.name, l.lat, l.lon, l.elevation_m AS elev,
       CAST(r ->> 'rating' AS INTEGER) AS rating, CAST(r ->> 'skyRating' AS INTEGER) AS sky
FROM cached_evaluation c
CROSS JOIN LATERAL json_array_elements(CAST(c.results_json AS json)) AS r
JOIN locations l ON l.name = r ->> 'locationName' AND l.enabled
WHERE c.evaluation_date >= CAST(:'since' AS DATE)
  AND c.target_type IN ('SUNRISE', 'SUNSET')
  AND r ->> 'rating' IS NOT NULL
  AND EXISTS (SELECT 1 FROM location_location_type t WHERE t.location_id = l.id
              AND t.location_type IN ('LANDSCAPE', 'SEASCAPE', 'WATERFALL'));

CREATE TEMP VIEW served_hi AS
SELECT a.d, a.t, a.id, a.name, a.sky,
       MAX(b.sky) AS nb_hi,
       SUM(CASE WHEN (a.elev IS NULL AND b.elev IS NULL)
                  OR (a.elev IS NOT NULL AND b.elev IS NOT NULL AND ABS(a.elev - b.elev) <= 150)
                THEN 1 ELSE 0 END) AS nb
FROM served a
LEFT JOIN served b
  ON b.d = a.d AND b.t = a.t AND b.id <> a.id AND b.sky IS NOT NULL
 AND 111.32 * SQRT((a.lat - b.lat) ^ 2 + ((a.lon - b.lon) * COS(RADIANS((a.lat + b.lat) / 2))) ^ 2)
     <= CAST(:'km' AS DOUBLE PRECISION)
WHERE a.sky IS NOT NULL
GROUP BY a.d, a.t, a.id, a.name, a.sky;

-- Per window: rated slots, how many carry skyRating, how many have a neighbour, how many flagged.
SELECT s.d, s.t, COUNT(*) AS rated, COUNT(s.sky) AS with_sky,
       (SELECT COUNT(*) FROM served_hi h WHERE h.d = s.d AND h.t = s.t AND h.nb > 0) AS with_neighbour,
       (SELECT COUNT(*) FROM served_hi h WHERE h.d = s.d AND h.t = s.t
          AND h.sky >= 3 AND h.nb > 0 AND h.sky - h.nb_hi >= 2) AS flagged,
       (SELECT COUNT(*) FROM served_hi h WHERE h.d = s.d AND h.t = s.t
          AND h.sky >= 3 AND h.nb >= 2 AND h.sky - h.nb_hi >= 2) AS flagged_2nb,
       (SELECT STRING_AGG(h.name || ' ' || h.sky || ' vs ' || h.nb_hi, '; ' ORDER BY h.name)
          FROM served_hi h WHERE h.d = s.d AND h.t = s.t
          AND h.sky >= 3 AND h.nb > 0 AND h.sky - h.nb_hi >= 2) AS which
FROM served s
GROUP BY s.d, s.t
ORDER BY s.d, s.t;
