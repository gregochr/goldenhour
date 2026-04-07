-- Fix: Seaton Sluice latitude typo (id=27)
-- Was 54.083916 (south of Newcastle — incorrect), should be ~55.079 (Northumberland coast)
UPDATE locations
SET lat      = 55.079,
    grid_lat = NULL,
    grid_lng = NULL
WHERE id = 27
  AND name = 'Seaton Sluice';

-- Fix: Ravenscar View coordinates (id=79)
-- Was 53.8246473, ~-1.525 (Leeds area — incorrect)
-- Should be ~54.399, -0.494 (clifftop viewpoint, North Yorkshire coast)
UPDATE locations
SET lat      = 54.399,
    lon      = -0.494,
    grid_lat = NULL,
    grid_lng = NULL
WHERE id = 79
  AND name = 'Ravenscar View';

-- Verification query
SELECT id, name, lat, lon, grid_lat, grid_lng
FROM locations
WHERE id IN (27, 79)
ORDER BY id;
