-- V191: sub-regions -- sub-regions-plan S1.
--
-- A sub-region is a named area (V189) with kind 'SUB_REGION': a labelled set of locations that partitions
-- its region (every regioned location belongs to exactly one sub-region of its region). It is used for
-- naming (Ask) now and, in later phases, for gating; it never carries a verdict.
--
--   area.kind        'NAMED' (the V189 kind, the default) or 'SUB_REGION'.
--   area.region_id   the region a sub-region partitions; required for a SUB_REGION (CHECK).
--   location_sub_region
--                    one row per location: the PRIMARY KEY on location_id is the "one sub-region per
--                    location" rule. area_location cannot carry it (Hibernate rewrites that table as bare
--                    pairs), so sub-region membership has its own table, written only by
--                    SubRegionMembershipService.
--
-- Members are seeded BY NAME AND REGION because ids differ between environments. Each area's candidate list
-- holds the names as stored on 2026-10-26 (misspellings included) and the corrected spellings, so the seed
-- is right before and after the owner fixes the seven misspelt names in production. A spelling that matches
-- no location inserts nothing, by design; the post-check below reports what is left uncovered.
--
-- This file is UTF-8: the folding below mentions a right single quotation mark. Do not re-save it in
-- another encoding.

ALTER TABLE area
    ADD COLUMN kind      VARCHAR(16) NOT NULL DEFAULT 'NAMED',
    ADD COLUMN region_id BIGINT,
    ADD CONSTRAINT ck_area_kind CHECK (kind IN ('NAMED', 'SUB_REGION')),
    ADD CONSTRAINT fk_area_region FOREIGN KEY (region_id) REFERENCES regions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_area_sub_region_has_region CHECK (kind <> 'SUB_REGION' OR region_id IS NOT NULL);

CREATE TABLE location_sub_region (
    location_id BIGINT NOT NULL PRIMARY KEY REFERENCES locations (id) ON DELETE CASCADE,
    area_id     BIGINT NOT NULL REFERENCES area (id) ON DELETE RESTRICT
);
CREATE INDEX idx_location_sub_region_area ON location_sub_region (area_id);

-- The seed, in session-scoped scratch tables (dropped at the end).
CREATE TEMP TABLE pf_area (name TEXT NOT NULL, region TEXT NOT NULL);
CREATE TEMP TABLE pf_alias (area TEXT NOT NULL, alias TEXT NOT NULL);
CREATE TEMP TABLE pf_member (area TEXT NOT NULL, candidate TEXT NOT NULL);

-- The folding of PlaceNames.words, which the name guard and Ask use: lower case, apostrophes (straight and
-- curly) removed, every other run of non-letters-and-digits one space.
CREATE FUNCTION pg_temp.v191_fold(t TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $f$
    SELECT btrim(regexp_replace(lower(replace(replace(t, '''', ''), '’', '')), '[^[:alnum:]]+', ' ', 'g'))
$f$;

INSERT INTO pf_area (name, region) VALUES
    ('Redcar to Whitby', 'North York Moors & Coast'),
    ('Robin Hood''s Bay to Flamborough', 'North York Moors & Coast'),
    ('Inland North York Moors', 'North York Moors & Coast'),
    ('Berwick & Holy Island', 'Northumberland & Tyneside'),
    ('Bamburgh to Howick', 'Northumberland & Tyneside'),
    ('Alnwick & the Coquet Coast', 'Northumberland & Tyneside'),
    ('Druridge Bay to Tynemouth', 'Northumberland & Tyneside'),
    ('Cheviots & Coquetdale', 'Northumberland & Tyneside'),
    ('Hadrian''s Wall & Tynedale', 'Northumberland & Tyneside'),
    ('Newcastle & Durham', 'Northumberland & Tyneside'),
    ('Sunderland & the Durham Coast', 'Northumberland & Tyneside'),
    ('Windermere & Coniston', 'The Lake District'),
    ('The Langdales', 'The Lake District'),
    ('Ambleside, Grasmere & Rydal', 'The Lake District'),
    ('Wasdale, Eskdale & Duddon', 'The Lake District'),
    ('Buttermere, Loweswater & Ennerdale', 'The Lake District'),
    ('Keswick & Borrowdale', 'The Lake District'),
    ('Ullswater & Haweswater', 'The Lake District'),
    ('The Three Peaks', 'The Yorkshire Dales'),
    ('Malhamdale & Airedale', 'The Yorkshire Dales'),
    ('Wensleydale', 'The Yorkshire Dales'),
    ('Swaledale', 'The Yorkshire Dales'),
    ('Wharfedale', 'The Yorkshire Dales'),
    ('Nidderdale', 'The Yorkshire Dales');

INSERT INTO pf_alias (area, alias) VALUES
    ('The Langdales', 'Langdale Valley'),
    ('The Langdales', 'Langdales'),
    ('Swaledale', 'Upper Teesdale'),
    ('Swaledale', 'Teesdale'),
    ('Sunderland & the Durham Coast', 'Durham Heritage Coast'),
    ('Sunderland & the Durham Coast', 'Durham coast'),
    ('Sunderland & the Durham Coast', 'Sunderland coast'),
    ('The Three Peaks', 'Three Peaks'),
    ('The Three Peaks', 'Yorkshire Three Peaks'),
    ('The Three Peaks', 'Ribblesdale'),
    ('The Three Peaks', 'Dentdale'),
    ('Robin Hood''s Bay to Flamborough', 'Flamborough Head'),
    ('Robin Hood''s Bay to Flamborough', 'Flamborough'),
    ('Cheviots & Coquetdale', 'Cheviot Hills'),
    ('Cheviots & Coquetdale', 'Simonside Hills'),
    ('Cheviots & Coquetdale', 'Coquetdale'),
    ('Cheviots & Coquetdale', 'Rothbury'),
    ('Druridge Bay to Tynemouth', 'Druridge Bay'),
    ('Alnwick & the Coquet Coast', 'Coquet coast'),
    ('Wharfedale', 'Upper Wharfedale'),
    ('Wharfedale', 'Lower Wharfedale'),
    ('Wensleydale', 'Upper Wensleydale'),
    ('Wasdale, Eskdale & Duddon', 'Duddon Valley'),
    ('Wasdale, Eskdale & Duddon', 'Eskdale'),
    ('Buttermere, Loweswater & Ennerdale', 'Buttermere valley'),
    ('Buttermere, Loweswater & Ennerdale', 'Ennerdale'),
    ('Hadrian''s Wall & Tynedale', 'South Tyne'),
    ('Hadrian''s Wall & Tynedale', 'North Tyne'),
    ('Hadrian''s Wall & Tynedale', 'Tynedale'),
    ('Hadrian''s Wall & Tynedale', 'Kielder'),
    ('Windermere & Coniston', 'Windermere'),
    ('Windermere & Coniston', 'Hawkshead'),
    ('Ambleside, Grasmere & Rydal', 'Ambleside'),
    ('Keswick & Borrowdale', 'Derwentwater'),
    ('Keswick & Borrowdale', 'Borrowdale'),
    ('Keswick & Borrowdale', 'Keswick'),
    ('Ullswater & Haweswater', 'Haweswater'),
    ('Redcar to Whitby', 'Teesside'),
    ('Malhamdale & Airedale', 'Malhamdale'),
    ('Malhamdale & Airedale', 'Airedale');

-- Candidate member names per sub-region (Appendix A of the plan), stored spellings first.
INSERT INTO pf_member (area, candidate) VALUES
    -- Redcar to Whitby (13)
    ('Redcar to Whitby', 'Infinity Bridge'),
    ('Redcar to Whitby', 'Redcar'),
    ('Redcar to Whitby', 'Runswick Bay'),
    ('Redcar to Whitby', 'Saltburn-by-the-Sea'),
    ('Redcar to Whitby', 'Saltholme'),
    ('Redcar to Whitby', 'Saltwick Bay'),
    ('Redcar to Whitby', 'Sandsend'),
    ('Redcar to Whitby', 'South Gare & Paddy''s Hole'),
    ('Redcar to Whitby', 'Staithes'),
    ('Redcar to Whitby', 'The Tees Transporter Bridge'),
    ('Redcar to Whitby', 'Whitby Abbey'),
    ('Redcar to Whitby', 'Whitby Piers'),
    ('Redcar to Whitby', 'Whitby Town'),
    -- Robin Hood's Bay to Flamborough (10)
    ('Robin Hood''s Bay to Flamborough', 'Bempton Cliffs'),
    ('Robin Hood''s Bay to Flamborough', 'Boggle Hole'),
    ('Robin Hood''s Bay to Flamborough', 'Breil Nook'),
    ('Robin Hood''s Bay to Flamborough', 'Bridlington North Beach'),
    ('Robin Hood''s Bay to Flamborough', 'Filey Brigg'),
    ('Robin Hood''s Bay to Flamborough', 'Ravenscar View'),
    ('Robin Hood''s Bay to Flamborough', 'Robin Hood''s Bay'),
    ('Robin Hood''s Bay to Flamborough', 'Scarborough'),
    ('Robin Hood''s Bay to Flamborough', 'Selwicks Bay & High Stacks'),
    ('Robin Hood''s Bay to Flamborough', 'Thornwick Bay'),
    -- Inland North York Moors (13)
    ('Inland North York Moors', 'Bank Top'),
    ('Inland North York Moors', 'Cockshaw Hill'),
    ('Inland North York Moors', 'Doctors Wood, Grosmont'),
    ('Inland North York Moors', 'Egton Lone Tree'),
    ('Inland North York Moors', 'Falling Foss'),
    ('Inland North York Moors', 'Hasty Bank'),
    ('Inland North York Moors', 'Hawnby Hill'),
    ('Inland North York Moors', 'Hole of Horcum'),
    ('Inland North York Moors', 'Newton Wood, Roseberry Topping'),
    ('Inland North York Moors', 'Rievaulx Abbey'),
    ('Inland North York Moors', 'Roseberry Topping'),
    ('Inland North York Moors', 'Sutton Bank'),
    ('Inland North York Moors', 'Thomason Foss'),
    -- Berwick & Holy Island (12)
    ('Berwick & Holy Island', 'Berwick-Upon-Tweed'),
    ('Berwick & Holy Island', 'Cocklawburn Beach'),
    ('Berwick & Holy Island', 'Duddo Stone Circle'),
    ('Berwick & Holy Island', 'Emmanuel Head'),
    ('Berwick & Holy Island', 'Guile Point'),
    ('Berwick & Holy Island', 'Holy Island Causeway'),
    ('Berwick & Holy Island', 'Holy Island Harbour'),
    ('Berwick & Holy Island', 'Lindisfarne Castle'),
    ('Berwick & Holy Island', 'Lindisfarne Priory'),
    ('Berwick & Holy Island', 'Ross Sands'),
    ('Berwick & Holy Island', 'Routin Lynn'),
    ('Berwick & Holy Island', 'Spittal Beach'),
    -- Bamburgh to Howick (13)
    ('Bamburgh to Howick', 'Bamburgh Castle'),
    ('Bamburgh to Howick', 'Beadnell'),
    ('Bamburgh to Howick', 'Black Hole'),
    ('Bamburgh to Howick', 'Budle Bay'),
    ('Bamburgh to Howick', 'Cullernose Point'),
    ('Bamburgh to Howick', 'Dunstanburgh Castle'),
    ('Bamburgh to Howick', 'Embleton Bay'),
    ('Bamburgh to Howick', 'Howick Scar'),
    ('Bamburgh to Howick', 'Low Newton-by-the-Sea'),
    ('Bamburgh to Howick', 'Rumbling Kern'),
    ('Bamburgh to Howick', 'Stag Rock Lighthouse'),
    ('Bamburgh to Howick', 'Sugar Sands'),
    ('Bamburgh to Howick', 'The Bathing House, Craster'),
    -- Alnwick & the Coquet Coast (7)
    ('Alnwick & the Coquet Coast', 'Alnmouth'),
    ('Alnwick & the Coquet Coast', 'Alnwick Castle'),
    ('Alnwick & the Coquet Coast', 'Amble'),
    ('Alnwick & the Coquet Coast', 'Edlinghham'),
    ('Alnwick & the Coquet Coast', 'Edlingham'),
    ('Alnwick & the Coquet Coast', 'Low Hauxley'),
    ('Alnwick & the Coquet Coast', 'St Cuthbert''s Cross, Alnmouth'),
    ('Alnwick & the Coquet Coast', 'Warkworth Castle'),
    -- Druridge Bay to Tynemouth (14)
    ('Druridge Bay to Tynemouth', 'Bluebell Wood, Morpeth'),
    ('Druridge Bay to Tynemouth', 'Blyth Beach'),
    ('Druridge Bay to Tynemouth', 'Bothal Weir'),
    ('Druridge Bay to Tynemouth', 'Bothal Woods'),
    ('Druridge Bay to Tynemouth', 'Cambois Beach'),
    ('Druridge Bay to Tynemouth', 'Collywell Bay'),
    ('Druridge Bay to Tynemouth', 'Cresswell Beach'),
    ('Druridge Bay to Tynemouth', 'Cullercoats'),
    ('Druridge Bay to Tynemouth', 'Newbiggin-by-the-Sea'),
    ('Druridge Bay to Tynemouth', 'Plessey Woods'),
    ('Druridge Bay to Tynemouth', 'Seaton Sluice'),
    ('Druridge Bay to Tynemouth', 'St. Mary''s Lighthouse'),
    ('Druridge Bay to Tynemouth', 'The River Wansbeck, Bothal'),
    ('Druridge Bay to Tynemouth', 'Tynemouth'),
    -- Cheviots & Coquetdale (12)
    ('Cheviots & Coquetdale', 'Blawearie'),
    ('Cheviots & Coquetdale', 'Cheviot'),
    ('Cheviots & Coquetdale', 'College Valley'),
    ('Cheviots & Coquetdale', 'Harbottle Hills'),
    ('Cheviots & Coquetdale', 'Hepburn Moor'),
    ('Cheviots & Coquetdale', 'Hethpool'),
    ('Cheviots & Coquetdale', 'Ingram and the Breamish Valley'),
    ('Cheviots & Coquetdale', 'Raven''s Crag & Bowden Doors, Belford'),
    ('Cheviots & Coquetdale', 'Simonside'),
    ('Cheviots & Coquetdale', 'Thrunton Wood'),
    ('Cheviots & Coquetdale', 'Windy Gyle'),
    ('Cheviots & Coquetdale', 'Yeavering Bell'),
    -- Hadrian's Wall & Tynedale (16)
    ('Hadrian''s Wall & Tynedale', 'Allen Banks'),
    ('Hadrian''s Wall & Tynedale', 'Caw Gap'),
    ('Hadrian''s Wall & Tynedale', 'Cawfields'),
    ('Hadrian''s Wall & Tynedale', 'Crammel Linn Waterfall'),
    ('Hadrian''s Wall & Tynedale', 'Cuddy''s Crags'),
    ('Hadrian''s Wall & Tynedale', 'Hareshaw Linn'),
    ('Hadrian''s Wall & Tynedale', 'Highshield Crags'),
    ('Hadrian''s Wall & Tynedale', 'Hotbank Crags'),
    ('Hadrian''s Wall & Tynedale', 'Houseteads Fort'),
    ('Hadrian''s Wall & Tynedale', 'Housesteads Fort'),
    ('Hadrian''s Wall & Tynedale', 'Irthing Gorge'),
    ('Hadrian''s Wall & Tynedale', 'Lamley Viaduct'),
    ('Hadrian''s Wall & Tynedale', 'Lambley Viaduct'),
    ('Hadrian''s Wall & Tynedale', 'Letah Wood'),
    ('Hadrian''s Wall & Tynedale', 'Peel Craggs'),
    ('Hadrian''s Wall & Tynedale', 'Peel Crags'),
    ('Hadrian''s Wall & Tynedale', 'Steel Rigg & Winshield Crags'),
    ('Hadrian''s Wall & Tynedale', 'Sycamore Gap'),
    ('Hadrian''s Wall & Tynedale', 'Walltown Crags'),
    -- Newcastle & Durham (12)
    ('Newcastle & Durham', 'Angel of the North'),
    ('Newcastle & Durham', 'Denton Dene'),
    ('Newcastle & Durham', 'Gosforth Nature Reserve'),
    ('Newcastle & Durham', 'Hardwick Hall Country Park'),
    ('Newcastle & Durham', 'Houghall Woods'),
    ('Newcastle & Durham', 'Jesmond Dene Woods'),
    ('Newcastle & Durham', 'Lands Wood, Winlaton Mill'),
    ('Newcastle & Durham', 'Low Barns, Witton Le Wear'),
    ('Newcastle & Durham', 'Newcastle and Gateshead Quayside'),
    ('Newcastle & Durham', 'Ragpath Wood'),
    ('Newcastle & Durham', 'Washingwell Wood'),
    ('Newcastle & Durham', 'Wharton Park'),
    -- Sunderland & the Durham Coast (15)
    ('Sunderland & the Durham Coast', 'Blackhall Rocks'),
    ('Sunderland & the Durham Coast', 'Copt Hill'),
    ('Sunderland & the Durham Coast', 'Crimdon Beach'),
    ('Sunderland & the Durham Coast', 'Hartlepool Headland'),
    ('Sunderland & the Durham Coast', 'Hawthorn Hive'),
    ('Sunderland & the Durham Coast', 'Horden Beach'),
    ('Sunderland & the Durham Coast', 'Marsden Rock'),
    ('Sunderland & the Durham Coast', 'Nose''s Point & Blast Beach'),
    ('Sunderland & the Durham Coast', 'Penshaw Monument'),
    ('Sunderland & the Durham Coast', 'Roker Pier & Lighthouse'),
    ('Sunderland & the Durham Coast', 'Seaham Chemical Beach'),
    ('Sunderland & the Durham Coast', 'Seaham North Pier & Lighthouse'),
    ('Sunderland & the Durham Coast', 'Shippersea Bay, Easington'),
    ('Sunderland & the Durham Coast', 'Souter Lighthouse'),
    ('Sunderland & the Durham Coast', 'Trow Rocks'),
    -- Windermere & Coniston (11)
    ('Windermere & Coniston', 'Bowness Bay & Cockshott Point'),
    ('Windermere & Coniston', 'Coniston Jetties'),
    ('Windermere & Coniston', 'Dorothy Farrer''s Spring Wood'),
    ('Windermere & Coniston', 'Gummer''s How'),
    ('Windermere & Coniston', 'Kelly Hall Tarn'),
    ('Windermere & Coniston', 'Millerground'),
    ('Windermere & Coniston', 'Orrest Head'),
    ('Windermere & Coniston', 'Tarn Hows'),
    ('Windermere & Coniston', 'Tilberthwaite and Hodge Close Quarry'),
    ('Windermere & Coniston', 'Wise Een Tarn'),
    ('Windermere & Coniston', 'Yew Tree Tarn'),
    -- The Langdales (9)
    ('The Langdales', 'Blea Tarn'),
    ('The Langdales', 'Cathedral Cavern'),
    ('The Langdales', 'Great Langdale Green Lane'),
    ('The Langdales', 'Lingmoor fell'),
    ('The Langdales', 'Little Langdale Tarn'),
    ('The Langdales', 'Side Pike'),
    ('The Langdales', 'Slater''s Bridge'),
    ('The Langdales', 'The Langdale Boulders'),
    ('The Langdales', 'The River Brathay at Elterwater'),
    -- Ambleside, Grasmere & Rydal (12)
    ('Ambleside, Grasmere & Rydal', 'Clappersgate Bridge'),
    ('Ambleside, Grasmere & Rydal', 'Grasmere Shoreline'),
    ('Ambleside, Grasmere & Rydal', 'Loughrigg Fell'),
    ('Ambleside, Grasmere & Rydal', 'Loughrigg Tarn'),
    ('Ambleside, Grasmere & Rydal', 'Loughrigg Terrace'),
    ('Ambleside, Grasmere & Rydal', 'Rydal Cave'),
    ('Ambleside, Grasmere & Rydal', 'Rydal Grotto'),
    ('Ambleside, Grasmere & Rydal', 'Rydal Water'),
    ('Ambleside, Grasmere & Rydal', 'Skelghyll Woods'),
    ('Ambleside, Grasmere & Rydal', 'Stockghyll Force'),
    ('Ambleside, Grasmere & Rydal', 'Waterhead'),
    ('Ambleside, Grasmere & Rydal', 'White Moss Common'),
    -- Wasdale, Eskdale & Duddon (8)
    ('Wasdale, Eskdale & Duddon', 'Birks Bridge'),
    ('Wasdale, Eskdale & Duddon', 'Devoke Water Boathouse and Birker Fell'),
    ('Wasdale, Eskdale & Duddon', 'Low Wood, Wasdale'),
    ('Wasdale, Eskdale & Duddon', 'Muncaster Castle Gardens'),
    ('Wasdale, Eskdale & Duddon', 'Stanley Ghyll Force'),
    ('Wasdale, Eskdale & Duddon', 'Trough House Bridge'),
    ('Wasdale, Eskdale & Duddon', 'Wastwater'),
    ('Wasdale, Eskdale & Duddon', 'Wastwater View from Lingmell Gill'),
    -- Buttermere, Loweswater & Ennerdale (7)
    ('Buttermere, Loweswater & Ennerdale', 'Buttermere'),
    ('Buttermere, Loweswater & Ennerdale', 'Crummock Water'),
    ('Buttermere, Loweswater & Ennerdale', 'Holme Wood, Loweswater'),
    ('Buttermere, Loweswater & Ennerdale', 'Innonimate Tarn & Haystacks Summit Tarn'),
    ('Buttermere, Loweswater & Ennerdale', 'Innominate Tarn & Haystacks Summit Tarn'),
    ('Buttermere, Loweswater & Ennerdale', 'Rannerdale Knotts'),
    ('Buttermere, Loweswater & Ennerdale', 'Scale Force'),
    ('Buttermere, Loweswater & Ennerdale', 'Warnscale Bothy'),
    -- Keswick & Borrowdale (10)
    ('Keswick & Borrowdale', 'Ashness Bridge'),
    ('Keswick & Borrowdale', 'Brandelhow Woods'),
    ('Keswick & Borrowdale', 'Castlerigg Stone Circle'),
    ('Keswick & Borrowdale', 'Cat Bells'),
    ('Keswick & Borrowdale', 'Derwent Water East Shore'),
    ('Keswick & Borrowdale', 'Derwent Water South & West Shore'),
    ('Keswick & Borrowdale', 'Ladore Falls'),
    ('Keswick & Borrowdale', 'Lodore Falls'),
    ('Keswick & Borrowdale', 'Latrigg'),
    ('Keswick & Borrowdale', 'Surprise View, Derwent Water'),
    ('Keswick & Borrowdale', 'Tewet Tarn'),
    -- Ullswater & Haweswater (7)
    ('Ullswater & Haweswater', 'Aira Force'),
    ('Ullswater & Haweswater', 'Brothers Water'),
    ('Ullswater & Haweswater', 'Hallin Fell'),
    ('Ullswater & Haweswater', 'The Cockpit Stone Circle'),
    ('Ullswater & Haweswater', 'The Duke of Portland Boathouse'),
    ('Ullswater & Haweswater', 'Ullswater South Shore'),
    ('Ullswater & Haweswater', 'Yew Crag'),
    -- The Three Peaks (14)
    ('The Three Peaks', 'Catrigg Force'),
    ('The Three Peaks', 'Craven Lime Works'),
    ('The Three Peaks', 'Dent Head Viaduct'),
    ('The Three Peaks', 'Howgills view'),
    ('The Three Peaks', 'Ingleton Waterfalls'),
    ('The Three Peaks', 'Norber Erratics'),
    ('The Three Peaks', 'Pen-Y-Gent'),
    ('The Three Peaks', 'Pen-y-ghent'),
    ('The Three Peaks', 'Ribblehead Viaduct'),
    ('The Three Peaks', 'Scaleber Force'),
    ('The Three Peaks', 'Southerscales'),
    ('The Three Peaks', 'Stainforth Force'),
    ('The Three Peaks', 'Twisleton Scar'),
    ('The Three Peaks', 'White Scars'),
    ('The Three Peaks', 'Winskill Stones'),
    -- Malhamdale & Airedale (8)
    ('Malhamdale & Airedale', 'Embsay Crag and Reservoir'),
    ('Malhamdale & Airedale', 'Gordale Scar'),
    ('Malhamdale & Airedale', 'Hardcastle Crags'),
    ('Malhamdale & Airedale', 'Janet''s Foss'),
    ('Malhamdale & Airedale', 'Malham Cove'),
    ('Malhamdale & Airedale', 'Malham Hawthorn'),
    ('Malhamdale & Airedale', 'Malham Tarn'),
    ('Malhamdale & Airedale', 'Skipton'),
    -- Wensleydale (7)
    ('Wensleydale', 'Aysgarth Falls'),
    ('Wensleydale', 'Bolton Castle'),
    ('Wensleydale', 'Cotter Force'),
    ('Wensleydale', 'Hardraw Force'),
    ('Wensleydale', 'Mill Gill Force'),
    ('Wensleydale', 'Semer Water'),
    ('Wensleydale', 'West Burton Falls'),
    -- Swaledale (12)
    ('Swaledale', 'Angram & Thwaite Barns'),
    ('Swaledale', 'Crackpot Hall'),
    ('Swaledale', 'East Gill Force'),
    ('Swaledale', 'Gunnerside Meadows'),
    ('Swaledale', 'High Force'),
    ('Swaledale', 'Kisdon Force'),
    ('Swaledale', 'Low Force'),
    ('Swaledale', 'Muker Meadows'),
    ('Swaledale', 'Old Gang & Surrender Smelt Mills'),
    ('Swaledale', 'Richmond, North Yorkshire'),
    ('Swaledale', 'Summerhill Force and Gibson''s Cave'),
    ('Swaledale', 'Wain Wath Force'),
    -- Wharfedale (15)
    ('Wharfedale', 'Almscliffe Crag'),
    ('Wharfedale', 'Arncliffe'),
    ('Wharfedale', 'Arthington viaduct'),
    ('Wharfedale', 'Bolton Abbey'),
    ('Wharfedale', 'Burnsall'),
    ('Wharfedale', 'Conistone Limestone Pavement'),
    ('Wharfedale', 'Cray Waterfalls'),
    ('Wharfedale', 'Ilkley Moor'),
    ('Wharfedale', 'Kilnsey Crag'),
    ('Wharfedale', 'Linton Falls'),
    ('Wharfedale', 'Middleton Woods'),
    ('Wharfedale', 'Otley Chevin'),
    ('Wharfedale', 'Posforth Gill Force'),
    ('Wharfedale', 'Scale Haw Force'),
    ('Wharfedale', 'The Strid'),
    -- Nidderdale (6)
    ('Nidderdale', 'Brimham Rocks'),
    ('Nidderdale', 'Fewston Reservoir'),
    ('Nidderdale', 'Fountains Abbey'),
    ('Nidderdale', 'Hackfall Wood'),
    ('Nidderdale', 'Knaresborough'),
    ('Nidderdale', 'Nidd Gorge');

-- Pre-flight. A raise here fails the migration and so blocks boot, which is the point: a clash is fixed in
-- the data, not papered over.
--   * the regions exist;
--   * no location name matches the candidate lists of two sub-regions;
--   * no seeded name or alias equals another area's name or alias, is a whole-word run inside one, or
--     contains one (Ask's matchArea picks by run length) -- the single allowed pair is the sub-region
--     "Hadrian's Wall & Tynedale" containing the V189 area "Hadrian's Wall";
--   * no seeded name or alias is a whole-word run inside any location name, location alias or ENABLED
--     region name (a disabled region is invisible to Ask, and "Teesdale" is one).
-- Comparison is on v191_fold, padded with a space each side so only whole words match.
DO $$
DECLARE
    problem TEXT;
BEGIN
    SELECT string_agg(DISTINCT a.region, ', ') INTO problem
    FROM pf_area a
    WHERE NOT EXISTS (SELECT 1 FROM regions r WHERE r.name = a.region);
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'V191 pre-flight: region(s) missing: %', problem;
    END IF;

    -- The name check below compares different owners, so a seeded name that is already an area's own name
    -- (the unique index would fail the INSERT with a less helpful message) is caught here.
    SELECT string_agg(a.name, ', ') INTO problem
    FROM pf_area a
    WHERE EXISTS (SELECT 1 FROM area x WHERE pg_temp.v191_fold(x.name) = pg_temp.v191_fold(a.name));
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'V191 pre-flight: sub-region name(s) already exist as an area: %', problem;
    END IF;

    SELECT string_agg(d.candidate, ', ') INTO problem
    FROM (SELECT lower(replace(candidate, '’', '''')) AS candidate
          FROM pf_member
          GROUP BY lower(replace(candidate, '’', ''''))
          HAVING count(DISTINCT area) > 1) d;
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'V191 pre-flight: candidate member name(s) listed under two sub-regions: %', problem;
    END IF;

    CREATE TEMP TABLE pf_name (owner TEXT NOT NULL, is_seed BOOLEAN NOT NULL, raw TEXT NOT NULL, f TEXT NOT NULL);
    INSERT INTO pf_name (owner, is_seed, raw, f)
    SELECT name, TRUE, name, pg_temp.v191_fold(name) FROM pf_area
    UNION ALL
    SELECT area, TRUE, alias, pg_temp.v191_fold(alias) FROM pf_alias
    UNION ALL
    SELECT name, FALSE, name, pg_temp.v191_fold(name) FROM area
    UNION ALL
    SELECT a.name, FALSE, aa.alias, pg_temp.v191_fold(aa.alias) FROM area_alias aa JOIN area a ON a.id = aa.area_id;

    SELECT string_agg(x.owner || ' ''' || x.raw || ''' vs ' || y.owner || ' ''' || y.raw || '''', '; ') INTO problem
    FROM pf_name x
    JOIN pf_name y
      ON x.owner <> y.owner
     AND (x.is_seed OR y.is_seed)
     AND position(' ' || x.f || ' ' IN ' ' || y.f || ' ') > 0
    WHERE NOT (x.raw = 'Hadrian''s Wall' AND NOT x.is_seed AND y.raw = 'Hadrian''s Wall & Tynedale' AND y.is_seed)
      AND NOT (y.raw = 'Hadrian''s Wall' AND NOT y.is_seed AND x.raw = 'Hadrian''s Wall & Tynedale' AND x.is_seed);
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'V191 pre-flight: area names clash (equal, inside or containing): %', problem;
    END IF;

    SELECT string_agg(s.raw || ' in location ''' || l.name || '''', '; ') INTO problem
    FROM pf_name s
    JOIN locations l ON position(' ' || s.f || ' ' IN ' ' || pg_temp.v191_fold(l.name) || ' ') > 0
    WHERE s.is_seed;
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'V191 pre-flight: area name(s) inside a location name: %', problem;
    END IF;

    SELECT string_agg(s.raw || ' in location alias ''' || la.alias || '''', '; ') INTO problem
    FROM pf_name s
    JOIN location_alias la ON position(' ' || s.f || ' ' IN ' ' || pg_temp.v191_fold(la.alias) || ' ') > 0
    WHERE s.is_seed;
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'V191 pre-flight: area name(s) inside a location alias: %', problem;
    END IF;

    SELECT string_agg(s.raw || ' in region ''' || r.name || '''', '; ') INTO problem
    FROM pf_name s
    JOIN regions r ON r.enabled AND position(' ' || s.f || ' ' IN ' ' || pg_temp.v191_fold(r.name) || ' ') > 0
    WHERE s.is_seed;
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'V191 pre-flight: area name(s) inside a region name: %', problem;
    END IF;

    DROP TABLE pf_name;
END
$$;

INSERT INTO area (name, kind, region_id)
SELECT a.name, 'SUB_REGION', r.id
FROM pf_area a
JOIN regions r ON r.name = a.region;

INSERT INTO area_alias (area_id, alias)
SELECT a.id, p.alias
FROM pf_alias p
JOIN area a ON a.name = p.area AND a.kind = 'SUB_REGION';

-- By name AND region: a same-named location in another region is not a member. The replace() folds a curly
-- apostrophe to a straight one, as V189 did.
INSERT INTO location_sub_region (location_id, area_id)
SELECT l.id, a.id
FROM pf_member m
JOIN area a ON a.name = m.area AND a.kind = 'SUB_REGION'
JOIN locations l
  ON lower(replace(l.name, '’', '''')) = lower(m.candidate)
 AND l.region_id = a.region_id;

DROP FUNCTION pg_temp.v191_fold(TEXT);
DROP TABLE pf_member;
DROP TABLE pf_alias;
DROP TABLE pf_area;

-- Post-check: report, never fail. A fresh CI database holds only the seeded locations; production should
-- read 0 once the seven misspellings are either left alone (both spellings are matched) or fixed.
DO $$
DECLARE
    uncovered INTEGER;
    names TEXT;
BEGIN
    SELECT count(*), string_agg(l.name, ', ' ORDER BY l.name) INTO uncovered, names
    FROM locations l
    WHERE l.enabled
      AND l.region_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM location_sub_region m WHERE m.location_id = l.id);
    RAISE NOTICE 'V191: % enabled, regioned location(s) have no sub-region%', uncovered,
        CASE WHEN uncovered > 0 THEN ': ' || names ELSE '' END;
END
$$;
