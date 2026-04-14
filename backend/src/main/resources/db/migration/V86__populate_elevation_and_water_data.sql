-- ============================================================
-- Populate elevation_m and overlooks_water for cloud inversions
-- and general location metadata.
--
-- Sources: OS map data, Wainwright fell heights, verified
-- viewpoint elevations. Water assessment based on direct
-- line-of-sight to lake, tarn, reservoir or sea.
-- ============================================================

-- ============================================================
-- A) INVERSION-SUITABLE: >= 200m + overlooks significant water
--    These locations will trigger inversion forecasting.
-- ============================================================

-- Lake District — elevated viewpoints overlooking lakes
UPDATE locations SET elevation_m = 597, overlooks_water = true WHERE id = 196; -- Innominate Tarn & Haystacks Summit Tarn (~597m, overlooks Buttermere valley)
UPDATE locations SET elevation_m = 470, overlooks_water = true WHERE id = 195; -- Warnscale Bothy (~470m, above Buttermere)
UPDATE locations SET elevation_m = 469, overlooks_water = true WHERE id = 153; -- Lingmoor Fell (469m, overlooks Langdale and distant Windermere)
UPDATE locations SET elevation_m = 451, overlooks_water = true WHERE id = 188; -- Cat Bells (451m, ridge overlooking Derwentwater)
UPDATE locations SET elevation_m = 388, overlooks_water = true WHERE id = 184; -- Hallin Fell (388m, overlooking Ullswater)
UPDATE locations SET elevation_m = 368, overlooks_water = true WHERE id = 187; -- Latrigg (368m, overlooking Derwentwater and Bassenthwaite)
UPDATE locations SET elevation_m = 362, overlooks_water = true WHERE id = 56;  -- Side Pike (362m, overlooking Blea Tarn and Great Langdale)
UPDATE locations SET elevation_m = 350, overlooks_water = true WHERE id = 200; -- Wastwater View from Lingmell Gill (~350m, overlooking Wastwater)
UPDATE locations SET elevation_m = 350, overlooks_water = true WHERE id = 181; -- Yew Crag (~350m, overlooking Ullswater)
UPDATE locations SET elevation_m = 335, overlooks_water = true WHERE id = 167; -- Loughrigg Fell (335m, overlooking Grasmere, Rydal Water, Windermere)
UPDATE locations SET elevation_m = 321, overlooks_water = true WHERE id = 174; -- Gummer's How (321m, overlooking Windermere south basin)
UPDATE locations SET elevation_m = 270, overlooks_water = true WHERE id = 186; -- Tewet Tarn (~270m, tarn with fell backdrop)
UPDATE locations SET elevation_m = 260, overlooks_water = true WHERE id = 192; -- Surprise View (~260m, classic Derwentwater inversion spot)
UPDATE locations SET elevation_m = 238, overlooks_water = true WHERE id = 171; -- Orrest Head (238m, panoramic over Windermere)
UPDATE locations SET elevation_m = 235, overlooks_water = true WHERE id = 201; -- Devoke Water Boathouse and Birker Fell (~235m, remote tarn)
UPDATE locations SET elevation_m = 215, overlooks_water = true WHERE id = 165; -- Tarn Hows (~215m, iconic tarn)
UPDATE locations SET elevation_m = 210, overlooks_water = true WHERE id = 162; -- Wise Een Tarn (~210m, quiet tarn)
UPDATE locations SET elevation_m = 200, overlooks_water = true WHERE id = 191; -- Ashness Bridge (~200m, classic Derwentwater viewpoint)

-- Yorkshire Dales — elevated viewpoints overlooking water
UPDATE locations SET elevation_m = 380, overlooks_water = true WHERE id = 114; -- Malham Tarn (~380m, highest lake in England)
UPDATE locations SET elevation_m = 350, overlooks_water = true WHERE id = 118; -- Embsay Crag and Reservoir (~350m, overlooks reservoir)
UPDATE locations SET elevation_m = 260, overlooks_water = true WHERE id = 121; -- Semer Water (~260m, natural lake)
UPDATE locations SET elevation_m = 200, overlooks_water = true WHERE id = 151; -- Fewston Reservoir (~200m, reservoir level)

-- Northumberland / Hadrian's Wall — crags overlooking loughs
UPDATE locations SET elevation_m = 345, overlooks_water = true WHERE id = 44;  -- Steel Rigg & Winshield Crags (345m, overlooks loughs)
UPDATE locations SET elevation_m = 300, overlooks_water = true WHERE id = 47;  -- Highshield Crags (~300m, overlooks Crag Lough)
UPDATE locations SET elevation_m = 250, overlooks_water = true WHERE id = 42;  -- Cawfields (~250m, near quarry lake)

-- North York Moors
UPDATE locations SET elevation_m = 325, overlooks_water = true WHERE id = 90;  -- Sutton Bank (325m, overlooking Lake Gormire)

-- ============================================================
-- B) OVERLOOK WATER but lower elevation (< 200m)
--    Won't trigger inversions but overlooks_water is useful metadata.
-- ============================================================

-- Lake District — lakeside and tarn-side locations
UPDATE locations SET elevation_m = 190, overlooks_water = true WHERE id = 57;  -- Blea Tarn (~190m, between the Langdales)
UPDATE locations SET elevation_m = 160, overlooks_water = true WHERE id = 182; -- Brothers Water (~160m, lake level)
UPDATE locations SET elevation_m = 145, overlooks_water = true WHERE id = 179; -- Ullswater South Shore (~145m)
UPDATE locations SET elevation_m = 145, overlooks_water = true WHERE id = 183; -- The Duke of Portland Boathouse (~145m, Ullswater)
UPDATE locations SET elevation_m = 130, overlooks_water = true WHERE id = 164; -- Yew Tree Tarn (~130m)
UPDATE locations SET elevation_m = 100, overlooks_water = true WHERE id = 159; -- Little Langdale Tarn (~100m)
UPDATE locations SET elevation_m = 100, overlooks_water = true WHERE id = 194; -- Buttermere (~100m, lake level)
UPDATE locations SET elevation_m = 96, overlooks_water = true WHERE id = 197;  -- Crummock Water (~96m, lake level)
UPDATE locations SET elevation_m = 94, overlooks_water = true WHERE id = 166;  -- Loughrigg Tarn (~94m)
UPDATE locations SET elevation_m = 90, overlooks_water = true WHERE id = 161;  -- Kelly Hall Tarn (~90m)
UPDATE locations SET elevation_m = 78, overlooks_water = true WHERE id = 189;  -- Derwent Water East Shore (~78m)
UPDATE locations SET elevation_m = 78, overlooks_water = true WHERE id = 190;  -- Derwent Water South & West Shore (~78m)
UPDATE locations SET elevation_m = 62, overlooks_water = true WHERE id = 175;  -- Grasmere Shoreline (~62m)
UPDATE locations SET elevation_m = 60, overlooks_water = true WHERE id = 199;  -- Wastwater (~60m, lake level)
UPDATE locations SET elevation_m = 55, overlooks_water = true WHERE id = 177;  -- Rydal Water (~55m)
UPDATE locations SET elevation_m = 46, overlooks_water = true WHERE id = 160;  -- Coniston Jetties (~46m)
UPDATE locations SET elevation_m = 42, overlooks_water = true WHERE id = 172;  -- Millerground (~42m, Windermere shore)
UPDATE locations SET elevation_m = 40, overlooks_water = true WHERE id = 169;  -- Waterhead (~40m, Windermere north)
UPDATE locations SET elevation_m = 40, overlooks_water = true WHERE id = 173;  -- Bowness Bay & Cockshott Point (~40m)

-- ============================================================
-- C) ELEVATED but DON'T overlook water
--    Elevation useful for snow-on-tops feature, but no inversion.
-- ============================================================

-- Cheviots and Northumberland
UPDATE locations SET elevation_m = 815, overlooks_water = false WHERE id = 219; -- Cheviot (815m, highest in Cheviots)
UPDATE locations SET elevation_m = 619, overlooks_water = false WHERE id = 216; -- Windy Gyll (619m, high Cheviots)
UPDATE locations SET elevation_m = 440, overlooks_water = false WHERE id = 38;  -- Simonside (440m, overlooks Coquet valley but no lake)
UPDATE locations SET elevation_m = 361, overlooks_water = false WHERE id = 33;  -- Yeavering Bell (361m, hillfort, no lake)
UPDATE locations SET elevation_m = 350, overlooks_water = false WHERE id = 37;  -- Harbottle Hills (~350m, moorland)
UPDATE locations SET elevation_m = 300, overlooks_water = false WHERE id = 221; -- Hepburn Moor (~300m, moorland)
UPDATE locations SET elevation_m = 250, overlooks_water = false WHERE id = 222; -- Blawearie (~250m, moorland)

-- Hadrian's Wall sections (no direct lake view)
UPDATE locations SET elevation_m = 300, overlooks_water = false WHERE id = 49;  -- Cuddy's Crags (~300m)
UPDATE locations SET elevation_m = 290, overlooks_water = false WHERE id = 41;  -- Walltown Crags (~290m)
UPDATE locations SET elevation_m = 290, overlooks_water = false WHERE id = 43;  -- Caw Gap (~290m)
UPDATE locations SET elevation_m = 280, overlooks_water = false WHERE id = 45;  -- Peel Craggs (~280m)
UPDATE locations SET elevation_m = 280, overlooks_water = false WHERE id = 48;  -- Hotbank Crags (~280m)
UPDATE locations SET elevation_m = 280, overlooks_water = false WHERE id = 50;  -- Houseteads Fort (~280m)
UPDATE locations SET elevation_m = 270, overlooks_water = false WHERE id = 46;  -- Sycamore Gap (~270m)

-- North York Moors
UPDATE locations SET elevation_m = 400, overlooks_water = false WHERE id = 89;  -- Hasty Bank (400m, Cleveland Plain, no lake)
UPDATE locations SET elevation_m = 320, overlooks_water = false WHERE id = 87;  -- Roseberry Topping (320m, no lake)
UPDATE locations SET elevation_m = 300, overlooks_water = false WHERE id = 91;  -- Hawnby Hill (~300m, moorland)
UPDATE locations SET elevation_m = 260, overlooks_water = false WHERE id = 97;  -- Hole of Horcum (~260m, no water)

-- Castlerigg (elevated but views are of fells, not directly overlooking a lake)
UPDATE locations SET elevation_m = 210, overlooks_water = false WHERE id = 185; -- Castlerigg Stone Circle (210m)
