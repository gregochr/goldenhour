-- ============================================================================
-- Phase 2: Storm Surge — Add coastal parameters to locations table
--
-- Shore-normal bearing: compass bearing pointing SEAWARD, perpendicular
--   to the local coastline. Wind blowing FROM this direction = max onshore.
-- ============================================================================

ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS shore_normal_bearing_degrees DOUBLE PRECISION DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS effective_fetch_metres       DOUBLE PRECISION DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS avg_shelf_depth_metres       DOUBLE PRECISION DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS is_coastal_tidal             BOOLEAN          DEFAULT FALSE NOT NULL;


-- ╔═══════════════════════════════════════════════════════════════════════╗
-- ║  NORTHUMBERLAND — Berwick to Holy Island                            ║
-- ║  Coast faces NE–E. Fetch 300–400km. Shelf 30–35m.                  ║
-- ╚═══════════════════════════════════════════════════════════════════════╝

UPDATE locations SET shore_normal_bearing_degrees =  65, effective_fetch_metres = 400000, avg_shelf_depth_metres = 35, is_coastal_tidal = TRUE WHERE id = 1;   -- Berwick-Upon-Tweed
UPDATE locations SET shore_normal_bearing_degrees =  70, effective_fetch_metres = 380000, avg_shelf_depth_metres = 35, is_coastal_tidal = TRUE WHERE id = 2;   -- Spittal Beach
UPDATE locations SET shore_normal_bearing_degrees =  75, effective_fetch_metres = 370000, avg_shelf_depth_metres = 33, is_coastal_tidal = TRUE WHERE id = 3;   -- Cocklawburn Beach
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 350000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 8;   -- Holy Island Causeway
UPDATE locations SET shore_normal_bearing_degrees = 170, effective_fetch_metres =  50000, avg_shelf_depth_metres = 10, is_coastal_tidal = TRUE WHERE id = 5;   -- Holy Island Harbour (S facing, short fetch)
UPDATE locations SET shore_normal_bearing_degrees = 110, effective_fetch_metres = 350000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 4;   -- Lindisfarne Castle (SE tip)
UPDATE locations SET shore_normal_bearing_degrees =  45, effective_fetch_metres = 380000, avg_shelf_depth_metres = 32, is_coastal_tidal = TRUE WHERE id = 7;   -- Emmanuel Head (NE tip)
UPDATE locations SET shore_normal_bearing_degrees = 150, effective_fetch_metres =  60000, avg_shelf_depth_metres = 10, is_coastal_tidal = TRUE WHERE id = 208; -- Guile Point (S side of island)
UPDATE locations SET shore_normal_bearing_degrees =  80, effective_fetch_metres = 330000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 207; -- Ross Sands

-- ╔═══════════════════════════════════════════════════════════════════════╗
-- ║  NORTHUMBERLAND — Bamburgh to Craster                               ║
-- ║  Coast faces ENE–E. Fetch 250–300km. Shelf 28–30m.                 ║
-- ╚═══════════════════════════════════════════════════════════════════════╝

UPDATE locations SET shore_normal_bearing_degrees =  10, effective_fetch_metres = 350000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 9;   -- Budle Bay (N facing)
UPDATE locations SET shore_normal_bearing_degrees =  75, effective_fetch_metres = 300000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 10;  -- Bamburgh Castle
UPDATE locations SET shore_normal_bearing_degrees =  80, effective_fetch_metres = 280000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 11;  -- Beadnell
UPDATE locations SET shore_normal_bearing_degrees =  75, effective_fetch_metres = 280000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 13;  -- Embleton Bay
UPDATE locations SET shore_normal_bearing_degrees =  80, effective_fetch_metres = 270000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 12;  -- Low Newton-by-the-Sea
UPDATE locations SET shore_normal_bearing_degrees =  75, effective_fetch_metres = 280000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 14;  -- Dunstanburgh Castle
UPDATE locations SET shore_normal_bearing_degrees =  80, effective_fetch_metres = 270000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 16;  -- Cullernose Point
UPDATE locations SET shore_normal_bearing_degrees =  80, effective_fetch_metres = 270000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 15;  -- Black Hole
UPDATE locations SET shore_normal_bearing_degrees =  85, effective_fetch_metres = 260000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 17;  -- Howick Scar
UPDATE locations SET shore_normal_bearing_degrees =  80, effective_fetch_metres = 250000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 210; -- The Bathing House, Craster
UPDATE locations SET shore_normal_bearing_degrees =  85, effective_fetch_metres = 250000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 18;  -- Rumbling Kern
UPDATE locations SET shore_normal_bearing_degrees =  85, effective_fetch_metres = 250000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 19;  -- Sugar Sands

-- ╔═══════════════════════════════════════════════════════════════════════╗
-- ║  NORTHUMBERLAND — Alnmouth to Tynemouth                             ║
-- ║  Coast faces E. Fetch 200–250km. Shelf 25–28m.                     ║
-- ╚═══════════════════════════════════════════════════════════════════════╝

UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 250000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 20;  -- Alnmouth
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 250000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 211; -- St Cuthbert's Cross, Alnmouth
UPDATE locations SET shore_normal_bearing_degrees =  85, effective_fetch_metres = 240000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 21;  -- Amble
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 240000, avg_shelf_depth_metres = 27, is_coastal_tidal = TRUE WHERE id = 22;  -- Low Hauxley
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 230000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 23;  -- Cresswell Beach
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 230000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 24;  -- Newbiggin-by-the-Sea
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 220000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 25;  -- Cambois Beach
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 220000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 26;  -- Blyth Beach
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 230000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 27;  -- Seaton Sluice
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 240000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 215; -- Collywell Bay
UPDATE locations SET shore_normal_bearing_degrees =  85, effective_fetch_metres = 250000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 28;  -- St. Mary's Lighthouse
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 240000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 29;  -- Cullercoats
UPDATE locations SET shore_normal_bearing_degrees =  85, effective_fetch_metres = 250000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 30;  -- Tynemouth

-- ╔═══════════════════════════════════════════════════════════════════════╗
-- ║  TYNE AND WEAR — East-facing North Sea coast                       ║
-- ╚═══════════════════════════════════════════════════════════════════════╝

UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 240000, avg_shelf_depth_metres = 27, is_coastal_tidal = TRUE WHERE id = 65;  -- Trow Rocks
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 240000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 68;  -- Marsden Rock
UPDATE locations SET shore_normal_bearing_degrees =  90, effective_fetch_metres = 240000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 67;  -- Souter Lighthouse

-- ╔═══════════════════════════════════════════════════════════════════════╗
-- ║  NORTH YORKSHIRE COAST — Tees to Staithes                          ║
-- ║  Coast faces N/NE. Fetch 300–400km. Shelf 28–30m.                  ║
-- ╚═══════════════════════════════════════════════════════════════════════╝

UPDATE locations SET shore_normal_bearing_degrees =   0, effective_fetch_metres = 400000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 53;  -- Redcar
UPDATE locations SET shore_normal_bearing_degrees =  30, effective_fetch_metres = 380000, avg_shelf_depth_metres = 30, is_coastal_tidal = TRUE WHERE id = 54;  -- South Gare & Paddy's Hole
UPDATE locations SET shore_normal_bearing_degrees =  45, effective_fetch_metres = 350000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 55;  -- Saltburn-by-the-Sea
UPDATE locations SET shore_normal_bearing_degrees =  30, effective_fetch_metres = 350000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 59;  -- Staithes

-- ╔═══════════════════════════════════════════════════════════════════════╗
-- ║  NORTH YORKSHIRE COAST — Runswick to Scarborough                   ║
-- ║  Coast faces NE–E. Fetch 200–300km. Shelf 22–27m.                  ║
-- ╚═══════════════════════════════════════════════════════════════════════╝

UPDATE locations SET shore_normal_bearing_degrees =  20, effective_fetch_metres = 300000, avg_shelf_depth_metres = 27, is_coastal_tidal = TRUE WHERE id = 60;  -- Runswick Bay
UPDATE locations SET shore_normal_bearing_degrees =  40, effective_fetch_metres = 280000, avg_shelf_depth_metres = 27, is_coastal_tidal = TRUE WHERE id = 61;  -- Sandsend
UPDATE locations SET shore_normal_bearing_degrees =  50, effective_fetch_metres = 280000, avg_shelf_depth_metres = 27, is_coastal_tidal = TRUE WHERE id = 75;  -- Whitby Piers
UPDATE locations SET shore_normal_bearing_degrees =  55, effective_fetch_metres = 270000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 63;  -- Saltwick Bay
UPDATE locations SET shore_normal_bearing_degrees =  55, effective_fetch_metres = 260000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 77;  -- Robin Hood's Bay
UPDATE locations SET shore_normal_bearing_degrees =  55, effective_fetch_metres = 260000, avg_shelf_depth_metres = 25, is_coastal_tidal = TRUE WHERE id = 78;  -- Boggle Hole
UPDATE locations SET shore_normal_bearing_degrees =  85, effective_fetch_metres = 200000, avg_shelf_depth_metres = 23, is_coastal_tidal = TRUE WHERE id = 80;  -- Scarborough

-- ╔═══════════════════════════════════════════════════════════════════════╗
-- ║  NORTH YORKSHIRE COAST — Filey to Bridlington                       ║
-- ╚═══════════════════════════════════════════════════════════════════════╝

UPDATE locations SET shore_normal_bearing_degrees = 100, effective_fetch_metres = 180000, avg_shelf_depth_metres = 22, is_coastal_tidal = TRUE WHERE id = 81;  -- Filey Brigg
UPDATE locations SET shore_normal_bearing_degrees =  10, effective_fetch_metres = 350000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 83;  -- Thornwick Bay
UPDATE locations SET shore_normal_bearing_degrees =  20, effective_fetch_metres = 350000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 85;  -- Selwicks Bay & High Stacks
UPDATE locations SET shore_normal_bearing_degrees =  20, effective_fetch_metres = 340000, avg_shelf_depth_metres = 28, is_coastal_tidal = TRUE WHERE id = 84;  -- Breil Nook
UPDATE locations SET shore_normal_bearing_degrees = 110, effective_fetch_metres = 150000, avg_shelf_depth_metres = 20, is_coastal_tidal = TRUE WHERE id = 86;  -- Bridlington North Beach
