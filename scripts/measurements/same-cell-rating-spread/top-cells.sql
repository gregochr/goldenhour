-- The cells that disagree most often: groups with a 2+ star spread, per cell and model.
-- Ranked by inland groups first, because a tidal member's served rating averages in its own tide
-- score and can differ from a neighbour's for a reason that is not noise.
SELECT g.grid_lat, g.grid_lng, g.evaluation_model AS model,
       COUNT(*) AS groups,
       SUM(CASE WHEN g.hi - g.lo >= 2 THEN 1 ELSE 0 END) AS ge2,
       SUM(CASE WHEN g.hi - g.lo >= 3 THEN 1 ELSE 0 END) AS ge3,
       SUM(CASE WHEN g.n_tidal = 0 AND g.hi - g.lo >= 2 THEN 1 ELSE 0 END) AS inland_ge2,
       MAX(g.n_tidal) AS tidal_members,
       (SELECT MIN(l.name) FROM locations l WHERE l.grid_lat = g.grid_lat AND l.grid_lng = g.grid_lng) AS a_member
FROM cell_group g
GROUP BY g.grid_lat, g.grid_lng, g.evaluation_model
HAVING SUM(CASE WHEN g.hi - g.lo >= 2 THEN 1 ELSE 0 END) > 0
ORDER BY inland_ge2 DESC, ge2 DESC, ge3 DESC
LIMIT 25;
