# Sub-regions: guidebook areas, Lite picks, and Ask — build plan

Status: **plan**, 2026-10-26, unreviewed. Owner-approved inputs: the sub-region list and allocation (§3, Appendix A),
the change rule (§4.3), and that Ask must understand sub-regions (§6). Open decisions are in §9; the phases in §8
do not start until the ones marked *blocking* are answered.

## §1 What and why

**Product change.** A Lite account picks up to *n* **sub-regions** (§9 Q1; the design brief assumes 3) and sees
forecast ratings only for locations inside them. Pro and Admin see everything and never pick. This is a second
freemium axis beside the existing field-level split (`docs/engineering/reference/roles-and-gating.md`,
*Freemium split*). There are no users yet (owner, 2026-10-26), so there is no migration of existing Lite readers
to manage.

**Why sub-regions rather than regions.** The four enabled regions are too few and too uneven to ration: Northumberland
& Tyneside holds 101 locations from Berwick to Hartlepool, North York Moors & Coast 36. One region of four is either
a quarter of the product or, in Northumberland, a two-hour drive.

**Where the sub-regions come from.** The contents pages of six photography guides (Long Valley Books for all four
regions; fotoVUE for the Lake District and Northumberland), adjusted by the owner and checked against the production
roster exported on 2026-10-26 (263 locations, all enabled). 24 sub-regions, 6 to 16 locations each (§3).

**What sub-regions are not.** They are not a forecast unit. The regional verdict, `VerdictSampleGate`, the honesty
filter and every rating store stay keyed on region. A sub-region is a labelled set of locations used for **gating**
and **naming** only; it never carries a verdict of its own. (The statistical case for sub-region *verdicts* is a
separate, unbuilt measurement: `ask-window-context-plan.md` §3, R1. Nothing here depends on it.)

## §2 Decisions (planner recommendations; the owner may overrule any)

| # | Decision |
|---|---|
| D1 | **A sub-region is a named area** (V189 `area` / `area_alias` / `area_location`) with `kind = 'SUB_REGION'`. Ask's named-area machinery (`AskAreas`, `look_up_place`, `rank_spots` area filter, `NameNamespaceGuard`) then understands every sub-region with no new Ask code (§6). The existing kind is `NAMED`. |
| D2 | **Sub-regions partition their region.** Every location belongs to exactly one sub-region, and that sub-region belongs to the location's own region. Ordinary `NAMED` areas keep their current freedom (overlap, partial cover). |
| D3 | **The partition is enforced in the database where Postgres can, and in the service where it cannot.** `area_location.partition` (nullable `VARCHAR(16)`, `'SUB_REGION'` on sub-region rows, NULL otherwise) with `UNIQUE (location_id, partition)`: Postgres treats NULLs as distinct, so named-area rows are unconstrained and a location can hold at most one sub-region row. `area.region_id` (nullable FK) is required when `kind = 'SUB_REGION'` (CHECK). "Same region as the location" and "every location has one" are service rules (§5.1), surfaced to the admin as a count of uncovered locations. |
| D4 | **Picks live in their own table**, `app_user_sub_region (user_id, area_id, PK both)`, written only by a scoped service (the *Column-scoped writes* invariant), plus `app_user.sub_regions_changed_at TIMESTAMP NULL` (`updatable = false`, written by a scoped repository update). |
| D5 | **The change rule** (owner, 2026-10-26): free changes for 7 days from `app_user.created_at`; after that, one change per 30 days counted from `sub_regions_changed_at`. A first choice made with no current picks is always allowed (registration, a Pro→Lite demotion). Three config keys, `photocast.sub-regions.lite-limit` (default 3), `grace-days` (7), `lock-days` (30), validated at startup. |
| D6 | **The server enforces; the client renders.** Ratings outside a Lite reader's picks are **absent** from every payload (*no blurred metrics*), stripped at the controller edge beside today's `BriefingRoleShaper`. A client lock alone is never the gate. |
| D7 | **The two-question rule holds.** Hot topics and Coming up answer "what is happening" and stay free in every region for every role. Only ratings ("where is worth going") are rationed. (Recommended earlier, never answered: §9 Q4.) |
| D8 | **Enforcement ships behind `photocast.sub-regions.enforce` (default `false`).** Data, picks and the picker can land and be tested before any Lite reader loses anything; the flag flips once the outside-picks design (§9 Q3) is built. |
| D9 | **Pre-selection is server-side.** `GET /api/user/settings/sub-regions` returns the options, the reader's picks, their status (§4.3) and, when the reader has a home and no picks, the *n* sub-regions nearest home (distance from home to each sub-region's member centroid). The client never computes distances. |

## §3 The sub-regions

| Region | Sub-region | Locations |
|---|---|---|
| Northumberland & Tyneside | Berwick & Holy Island | 12 |
| | Bamburgh to Howick | 13 |
| | Alnwick & the Coquet Coast | 7 |
| | Druridge Bay to Tynemouth | 14 |
| | Cheviots & Coquetdale | 12 |
| | Hadrian's Wall *(name: §9 Q2)* | 16 |
| | Newcastle & Durham | 12 |
| | Sunderland & the Durham Coast | 15 |
| The Lake District | Windermere & Coniston | 11 |
| | The Langdales | 9 |
| | Ambleside, Grasmere & Rydal | 12 |
| | Wasdale, Eskdale & Duddon | 8 |
| | Buttermere, Loweswater & Ennerdale | 7 |
| | Keswick & Borrowdale | 10 |
| | Ullswater & Haweswater | 7 |
| The Yorkshire Dales | The Three Peaks | 14 |
| | Malhamdale & Airedale | 8 |
| | Wensleydale | 7 |
| | Swaledale | 12 |
| | Wharfedale | 15 |
| | Nidderdale | 6 |
| North York Moors & Coast | Redcar to Whitby | 13 |
| | Robin Hood's Bay to Flamborough | 10 |
| | The North York Moors | 13 |

Member lists by location id and name are in Appendix A. Owner calls recorded in the allocation: Teesdale's three
waterfalls are Swaledale; Ingleton/Dentdale and Ribblesdale merge as The Three Peaks; Wharfedale is not split;
Kielder & Otterburn folds into the Wall sub-region (it held only Hareshaw Linn); the Central Lakes split along
Long Valley's line.

**Names checked against `NameNamespaceGuard.areaNameClash`** (an area name may not equal another area name or
alias, nor sit as a whole-word run inside any location name or alias):
- **"Langdale" fails** (inside "Great Langdale Green Lane", "The Langdale Boulders", "Little Langdale Tarn"), so the
  sub-region is **"The Langdales"**, Long Valley's own title.
- **"Hadrian's Wall" is taken** by the V189 named area, seeded with the 12 Wall sites only (Walltown to Housesteads, Irthing Gorge, Crammel Linn). The sub-region
  adds Allen Banks, Lambley Viaduct, Letah Wood and Hareshaw Linn. §9 Q2.
- Every other name passes against the 2026-10-26 roster. The reverse rule, `locationNameClash`, means that **after
  this lands no location may be named or renamed to contain a sub-region name** ("Swaledale Meadows" would be
  refused). Accepted; the admin sees the guard's own message.

**Aliases seeded with the sub-regions.** Checked on 2026-10-26 against the production roster with the guard's own rule
(no alias may sit as a whole-word run inside a location name or alias); V191's pre-flight re-checks them in SQL.

| Sub-region | Aliases (pass) | Refused by the guard (a location carries the name) |
|---|---|---|
| The Langdales | Langdales, Langdale Valley | Great Langdale, Little Langdale |
| Swaledale | Teesdale, Upper Teesdale | — |
| Sunderland & the Durham Coast | Durham Heritage Coast, Durham coast, Sunderland coast | — |
| The Three Peaks | Three Peaks, Yorkshire Three Peaks, Ribblesdale, Dentdale | Ingleton |
| Ambleside, Grasmere & Rydal | Ambleside | Grasmere, Rydal |
| Windermere & Coniston | Windermere, Hawkshead | Coniston |
| Keswick & Borrowdale | Derwentwater, Borrowdale, Keswick | Derwent Water |
| Ullswater & Haweswater | Haweswater | Ullswater |
| Wasdale, Eskdale & Duddon | Eskdale, Duddon Valley | Wasdale |
| Buttermere, Loweswater & Ennerdale | Ennerdale | Loweswater, Crummock |
| Redcar to Whitby | Teesside | — |
| Robin Hood's Bay to Flamborough | Flamborough, Flamborough Head | Scarborough, Filey |
| Cheviots & Coquetdale | the Cheviots, Cheviot Hills, Coquetdale, Rothbury, Simonside Hills | Breamish Valley |
| Druridge Bay to Tynemouth | Druridge Bay | — |
| Berwick & Holy Island | — | Holy Island, Lindisfarne, Berwick |
| Bamburgh to Howick | — | Craster |
| Alnwick & the Coquet Coast | Coquet coast | Alnwick, Warkworth |
| Malhamdale & Airedale | Malhamdale, Airedale | Malham |
| Wharfedale | Upper Wharfedale, Lower Wharfedale | — |
| The North York Moors | North York Moors | — |
| Tynedale *(if Q2 (b))* | South Tyne, Kielder | — |

Deliberately left out though the guard would pass them: "Durham" and "Tyneside" (a word of another sub-region's
alias or of the region name "Northumberland & Tyneside", so one question would match two scopes), "the Moors",
"North/South/Western Lakes" (fotoVUE's compass names do not line up one-to-one with these sub-regions).

The rule this exposes: **a well-known place name usually collides with a location named after it**, and the guard is
right to refuse it: an exact place match beats an area (named-areas plan D10), so the alias would never be reached.
"Holy Island" asked of Ask finds the Holy Island locations, which is the right answer anyway.

## §4 Behaviour

### §4.1 Who picks

- **Lite**: must hold 1..*n* picks to see ratings when enforcement is on. Zero picks (a demoted Pro, or a reader who
  never finished registration) sees no ratings and a prompt to choose (§9 Q6).
- **Pro, Admin**: no picks, no picker; Settings says "Pro covers every sub-region" (Pro) or nothing (Admin).
- **A Pro→Lite demotion** keeps no picks (Pro never had any), so the reader's first choice is free (D5).
- **A Lite→Pro promotion** leaves the picks row in place, unused; a later demotion finds them again and the lock
  clock is unchanged.

### §4.2 What changes for a Lite reader (enforcement on)

Ratings for locations outside the picks are absent from: `GET /api/briefing` (the location slots), `GET
/api/briefing/digest` (window picks and names), `GET /api/briefing/evaluate/scores`, `GET /api/forecast` (list,
`?date=`, sparse rows) and `GET /api/forecast/{id}`. The exact field list is fixed in S3's review against
`docs/engineering/reference/api-endpoints.md` (it already lists what `BriefingRoleShaper` strips for Lite).
Unchanged for everyone: `GET /api/locations` (admin-managed config, not ratings), hot topics and Coming up (D7),
`GET /api/tides`, astro conditions, aurora status. Region-level verdict and prose: §9 Q3.

### §4.3 The change rule, as the API states it

`GET /api/user/settings/sub-regions` returns a `status` the client renders without computing anything:

| status | when | fields |
|---|---|---|
| `FREE_UNTIL` | `now < created_at + grace-days` | `freeUntil` (date) |
| `OPEN` | past the grace period and `sub_regions_changed_at` is null or older than `lock-days` | — |
| `LOCKED` | otherwise | `nextChangeAt` (date) |
| `NOT_APPLICABLE` | Pro, Admin | — |

`PUT /api/user/settings/sub-regions` with `{ areaIds: [...] }`: 400 for an empty list, more than *n*, a duplicate,
or an id that is not a `SUB_REGION` area; 403 for Pro/Admin (nothing to set); 409 `SUB_REGIONS_LOCKED` with
`nextChangeAt` when locked; 200 with the new state otherwise. A PUT whose set equals the current set is a 200
no-op that does **not** start a lock. Outside the grace period a successful change sets `sub_regions_changed_at`
to now; inside it, the column is left untouched (so the first post-grace change is always available). Dates are
UK civil dates through `ForecastHorizon` and the injected `Clock` (the *"Today"* invariant).

## §5 Engineering

### §5.1 Data (S1)

Migration `V191__sub_regions.sql` (Postgres; the number is read from the tree at build time):
- `area.kind VARCHAR(16) NOT NULL DEFAULT 'NAMED' CHECK (kind IN ('NAMED','SUB_REGION'))`,
  `area.region_id BIGINT NULL REFERENCES regions(id)`, `CHECK (kind <> 'SUB_REGION' OR region_id IS NOT NULL)`.
- `area_location.partition VARCHAR(16) NULL CHECK (partition IS NULL OR partition = 'SUB_REGION')`,
  `UNIQUE (location_id, partition)`.
- Insert the 24 sub-regions, their aliases (§3, guard-checked) and members **by location name**, matching both the
  current and corrected spellings as V189 did (the owner is fixing six misspellings through the admin screen in
  parallel: Edlinghham, Houseteads Fort, Innonimate Tarn, Ladore Falls, Lamley Viaduct, Peel Craggs, Pen-Y-Gent).
  Region resolved by name with V137's renamed names. Pre-flight `DO $$` block: the four regions exist; no sub-region
  name or alias equals an existing area name/alias or sits inside a location name/alias (the guard's rule in SQL,
  on the same word folding as `PlaceNames.words`). Post-check: a `RAISE NOTICE` (not an exception) with the count of
  enabled locations left without a sub-region, because a fresh CI database holds only the seeded locations.
- `LocalH2EnumWidener`: not needed (`kind` is a VARCHAR with a CHECK, not an `@Enumerated` column) — confirm in review.

Backend: `AreaEntity` gains `kind` and `region`; `AreaService` keeps sub-region membership consistent (adding a
location to a sub-region removes it from its previous one; the location's region must match; deleting a sub-region
with members is refused); `LocationService.create`/`update` takes an optional `subRegionId` and moves the
membership; a region change on a location clears a now-mismatched sub-region and logs it. The admin Areas section
shows the kind and region, and a line "N locations have no sub-region" with their names.

### §5.2 Picks (S2)

`app_user_sub_region` table, `app_user.sub_regions_changed_at`; `UserSubRegionService` (the rule in §4.3, pure and
`Clock`-injected); `GET`/`PUT /api/user/settings/sub-regions` in `UserSettingsController`. The path is under
`/api/user/settings*`, which `HttpCachingConfig` never filters; add it to
`HttpCachingConfigTest.personalDataPathsAreNeverFiltered` (the *personal data* invariant). Registration needs no new
endpoint: the new step calls the same PUT after `SET_PASSWORD`, when the reader is signed in.

### §5.3 Enforcement (S3)

A `LitePickShaper` beside `BriefingRoleShaper`, applied at the same controller edges, given the reader's allowed
location ids (`null` = everything, for Pro/Admin or with the flag off). Internal callers (Ask, digest service,
pipeline) keep full data, as P2 did.

**The caching question (blocking: §9 Q5).** `/api/briefing`, `/api/briefing/digest`, `/api/briefing/evaluate/scores`
and `/api/forecast` are ETag-revalidated (`HttpCachingConfig.REVALIDATABLE_READ_PATHS`), which means
`Cache-Control: private, no-cache` and a body the browser may keep on disk after logout. Today that is accepted
because the bodies carry no user-authored text and no home-derived data. A body filtered by picks reveals the picks,
and picks are pre-filled from the home postcode, so a shaped body is arguably *home-derived personal data* at rest.
Options: (a) a Lite response shaped by picks is sent `no-store` (skips the ETag filter for that response only; Lite
loses 304s); (b) accept it, on the grounds that the picks are a coarse, user-confirmed preference, and record the
exception in `HttpCachingConfig`'s Javadoc and the invariant. Recommendation: **(a)**.

### §5.4 Frontend (S4, S5)

S4: the registration step and the Settings section, from the Claude Design output (brief: the owner holds it;
mirror its copy). S5: what a Lite reader sees for locations outside the picks, from a second design (§9 Q3).
Both follow the house Pro pattern (`ProUpsellDoor`: a pill, worded text, no link, no "upgrade"), phone-only rules
(`useIsMobile`), and the dialog-stacking limits. The client derives nothing: status, dates and suggestions come
from the GET.

## §6 Ask

Sub-regions are areas (D1), so from S1 onward `look_up_place` answers "how's Swaledale looking?" with the area result
and `rank_spots` ranks within it, for Pro and Admin (Ask is Pro-only; Lite never reaches it, so Ask never filters by
picks). What S1 must check, not build:
- **Result sizes.** `AskToolsNamedAreaTest` pins the worst named-area result under 4,500 characters; the Wall
  sub-region (16 members) is larger than the V189 area (13). Re-measure.
- **Two areas in one question.** With the Wall area and a Wall sub-region both present, a question naming both
  must resolve predictably (named-areas plan D-rules). §9 Q2's choice decides whether this case exists at all.
- **`placeNames`** gains 24 names and their aliases (defence in depth for Ready intents): confirm no Ready rule's
  vocabulary now matches a sub-region name ("The North York Moors" is all ordinary words; check `ReadyIntentRules`).
- **The typed cache** keys on scope, date, `generatedAt` and question; an admin's sub-region edit shows from the
  next briefing build, as for named areas.

## §7 Reference docs to update (in the phase that changes the behaviour)

`roles-and-gating.md` (Freemium split, the Lite picks rule), `api-endpoints.md` (the new endpoints and the S3 field
list), `database-migrations.md` (V191), `configuration.md` (`photocast.sub-regions.*`), `ask-photocast.md` (named
areas: the `SUB_REGION` kind), `map-tab.md` / `plan-tab.md` (S5). A CLAUDE.md invariant line only if S3 adds one
("Lite ratings are filtered by picks at the controller edge; never on the client").

## §8 Delivery

| phase | what | size | blocked by |
|---|---|---|---|
| **S0** | this plan; review | XS | — |
| **S1** data + admin | V191, entity/service/admin changes, partition rules, Ask checks (§6) | M | Q2 |
| **S2** picks API | table, column, service, endpoints, config keys | M | Q1 |
| **S3** enforcement | `LitePickShaper` on five endpoints, flag `enforce` (off) | M | Q5, Q3 (region prose) |
| **S4** picker UI | registration step + Settings section | M | Claude Design output |
| **S5** outside-picks UI | Lite view of unpicked locations; then flip `enforce` | M | Q3, its design |
| **—** public terms | photocast.online pricing, FAQ, Terms §4 | owner | before `enforce` flips |

Each phase: Sonnet builds in its own worktree from a brief cut from this file, runs the local gates (backend build
ladder; frontend lint, Vitest, audit, build), the planner reviews the diff, one PR, CI and Codex green before merge.

## §9 Open questions for the owner

1. **Q1 (blocking S2): how many picks for Lite?** The design brief assumes 3.
2. **Q2 (blocking S1): the Wall.** (a) Promote the V189 "Hadrian's Wall" area to the sub-region and add Allen Banks,
   Lambley Viaduct, Letah Wood and Hareshaw Linn to it, so Ask's "Hadrian's Wall" grows to include them; or
   (b) name the sub-region **"Tynedale"** (the district that holds the Wall, the South Tyne and Bellingham) and keep
   the Wall area as an ordinary overlapping area. Recommendation: **(b)**; Ask keeps its precise Wall answer and the
   picker shows "Sycamore Gap, Housesteads, Allen Banks…" as examples.
3. **Q3 (blocking S5): what does Lite see outside its picks?** The locations themselves (markers, names) with a Pro
   door, or nothing? And are the **region verdict and region prose** free (they are summaries over every location in
   the region, including unpicked ones)? Recommendation: locations visible without ratings; region verdict and prose
   free.
4. **Q4: hot topics and Coming up free in every region (D7)?** Recommendation: yes.
5. **Q5 (blocking S3): shaped bodies and the browser cache** (§5.3). Recommendation: `no-store` for shaped Lite bodies.
6. **Q6: a Lite reader with zero picks** sees no ratings until they choose. Acceptable, or a default (nearest *n*
   to home, else none)?

## Appendix A — allocation (production roster, 2026-10-26)

Location names as stored on 2026-10-26, misspellings included; ids are production ids (a fresh database's ids
differ, which is why V191 matches by name).

**North York Moors & Coast**

- **Redcar to Whitby** (13): Infinity Bridge (51), Redcar (53), Runswick Bay (60), Saltburn-by-the-Sea (55), Saltholme (206), Saltwick Bay (63), Sandsend (61), South Gare & Paddy's Hole (54), Staithes (59), The Tees Transporter Bridge (52), Whitby Abbey (76), Whitby Piers (75), Whitby Town (62)
- **Robin Hood's Bay to Flamborough** (10): Bempton Cliffs (82), Boggle Hole (78), Breil Nook (84), Bridlington North Beach (86), Filey Brigg (81), Ravenscar View (79), Robin Hood's Bay (77), Scarborough (80), Selwicks Bay & High Stacks (85), Thornwick Bay (83)
- **The North York Moors** (13): Bank Top (93), Cockshaw Hill (88), Doctors Wood, Grosmont (231), Egton Lone Tree (94), Falling Foss (96), Hasty Bank (89), Hawnby Hill (91), Hole of Horcum (97), Newton Wood, Roseberry Topping (261), Rievaulx Abbey (92), Roseberry Topping (87), Sutton Bank (90), Thomason Foss (95)

**Northumberland & Tyneside**

- **Berwick & Holy Island** (12): Berwick-Upon-Tweed (1), Cocklawburn Beach (3), Duddo Stone Circle (31), Emmanuel Head (7), Guile Point (208), Holy Island Causeway (8), Holy Island Harbour (5), Lindisfarne Castle (4), Lindisfarne Priory (6), Ross Sands (207), Routin Lynn (32), Spittal Beach (2)
- **Bamburgh to Howick** (13): Bamburgh Castle (10), Beadnell (11), Black Hole (15), Budle Bay (9), Cullernose Point (16), Dunstanburgh Castle (14), Embleton Bay (13), Howick Scar (17), Low Newton-by-the-Sea (12), Rumbling Kern (18), Stag Rock Lighthouse (209), Sugar Sands (19), The Bathing House, Craster (210)
- **Alnwick & the Coquet Coast** (7): Alnmouth (20), Alnwick Castle (34), Amble (21), Edlinghham (36), Low Hauxley (22), St Cuthbert's Cross, Alnmouth (211), Warkworth Castle (35)
- **Druridge Bay to Tynemouth** (14): Bluebell Wood, Morpeth (228), Blyth Beach (26), Bothal Weir (214), Bothal Woods (212), Cambois Beach (25), Collywell Bay (215), Cresswell Beach (23), Cullercoats (29), Newbiggin-by-the-Sea (24), Plessey Woods (227), Seaton Sluice (27), St. Mary's Lighthouse (28), The River Wansbeck, Bothal (213), Tynemouth (30)
- **Cheviots & Coquetdale** (12): Blawearie (222), Cheviot (219), College Valley (218), Harbottle Hills (37), Hepburn Moor (221), Hethpool (217), Ingram and the Breamish Valley (223), Raven's Crag & Bowden Doors, Belford (220), Simonside (38), Thrunton Wood (224), Windy Gyle (216), Yeavering Bell (33)
- **Hadrian's Wall** (16): Allen Banks (40), Caw Gap (43), Cawfields (42), Crammel Linn Waterfall (225), Cuddy's Crags (49), Hareshaw Linn (39), Highshield Crags (47), Hotbank Crags (48), Houseteads Fort (50), Irthing Gorge (259), Lamley Viaduct (226), Letah Wood (260), Peel Craggs (45), Steel Rigg & Winshield Crags (44), Sycamore Gap (46), Walltown Crags (41)
- **Newcastle & Durham** (12): Angel of the North (64), Denton Dene (257), Gosforth Nature Reserve (70), Hardwick Hall Country Park (230), Houghall Woods (229), Jesmond Dene Woods (258), Lands Wood, Winlaton Mill (255), Low Barns, Witton Le Wear (69), Newcastle and Gateshead Quayside (74), Ragpath Wood (256), Washingwell Wood (254), Wharton Park (58)
- **Sunderland & the Durham Coast** (15): Blackhall Rocks (250), Copt Hill (66), Crimdon Beach (251), Hartlepool Headland (252), Hawthorn Hive (247), Horden Beach (249), Marsden Rock (68), Nose's Point & Blast Beach (246), Penshaw Monument (253), Roker Pier & Lighthouse (243), Seaham Chemical Beach (245), Seaham North Pier & Lighthouse (244), Shippersea Bay, Easington (248), Souter Lighthouse (67), Trow Rocks (65)

**The Lake District**

- **Windermere & Coniston** (11): Bowness Bay & Cockshott Point (173), Coniston Jetties (160), Dorothy Farrer's Spring Wood (263), Gummer's How (174), Kelly Hall Tarn (161), Millerground (172), Orrest Head (171), Tarn Hows (165), Tilberthwaite and Hodge Close Quarry (163), Wise Een Tarn (162), Yew Tree Tarn (164)
- **The Langdales** (9): Blea Tarn (57), Cathedral Cavern (158), Great Langdale Green Lane (154), Lingmoor fell (153), Little Langdale Tarn (159), Side Pike (56), Slater's Bridge (157), The Langdale Boulders (155), The River Brathay at Elterwater (156)
- **Ambleside, Grasmere & Rydal** (12): Clappersgate Bridge (168), Grasmere Shoreline (175), Loughrigg Fell (167), Loughrigg Tarn (166), Loughrigg Terrace (239), Rydal Cave (176), Rydal Grotto (178), Rydal Water (177), Skelghyll Woods (236), Stockghyll Force (170), Waterhead (169), White Moss Common (238)
- **Wasdale, Eskdale & Duddon** (8): Birks Bridge (202), Devoke Water Boathouse and Birker Fell (201), Low Wood, Wasdale (240), Muncaster Castle Gardens (242), Stanley Ghyll Force (203), Trough House Bridge (204), Wastwater (199), Wastwater View from Lingmell Gill (200)
- **Buttermere, Loweswater & Ennerdale** (7): Buttermere (194), Crummock Water (197), Holme Wood, Loweswater (241), Innonimate Tarn & Haystacks Summit Tarn (196), Rannerdale Knotts (235), Scale Force (198), Warnscale Bothy (195)
- **Keswick & Borrowdale** (10): Ashness Bridge (191), Brandelhow Woods (237), Castlerigg Stone Circle (185), Cat Bells (188), Derwent Water East Shore (189), Derwent Water South & West Shore (190), Ladore Falls (193), Latrigg (187), Surprise View, Derwent Water (192), Tewet Tarn (186)
- **Ullswater & Haweswater** (7): Aira Force (180), Brothers Water (182), Hallin Fell (184), The Cockpit Stone Circle (205), The Duke of Portland Boathouse (183), Ullswater South Shore (179), Yew Crag (181)

**The Yorkshire Dales**

- **The Three Peaks** (14): Catrigg Force (108), Craven Lime Works (106), Dent Head Viaduct (102), Howgills view (103), Ingleton Waterfalls (98), Norber Erratics (104), Pen-Y-Gent (110), Ribblehead Viaduct (111), Scaleber Force (105), Southerscales (101), Stainforth Force (107), Twisleton Scar (99), White Scars (100), Winskill Stones (109)
- **Malhamdale & Airedale** (8): Embsay Crag and Reservoir (118), Gordale Scar (113), Hardcastle Crags (232), Janet's Foss (116), Malham Cove (112), Malham Hawthorn (115), Malham Tarn (114), Skipton (117)
- **Wensleydale** (7): Aysgarth Falls (124), Bolton Castle (123), Cotter Force (119), Hardraw Force (120), Mill Gill Force (122), Semer Water (121), West Burton Falls (125)
- **Swaledale** (12): Angram & Thwaite Barns (130), Crackpot Hall (129), East Gill Force (127), Gunnerside Meadows (132), High Force (71), Kisdon Force (128), Low Force (72), Muker Meadows (131), Old Gang & Surrender Smelt Mills (133), Richmond, North Yorkshire (134), Summerhill Force and Gibson's Cave (73), Wain Wath Force (126)
- **Wharfedale** (15): Almscliffe Crag (147), Arncliffe (136), Arthington viaduct (148), Bolton Abbey (142), Burnsall (141), Conistone Limestone Pavement (138), Cray Waterfalls (135), Ilkley Moor (145), Kilnsey Crag (137), Linton Falls (139), Middleton Woods (234), Otley Chevin (146), Posforth Gill Force (144), Scale Haw Force (140), The Strid (143)
- **Nidderdale** (6): Brimham Rocks (150), Fewston Reservoir (151), Fountains Abbey (149), Hackfall Wood (233), Knaresborough (152), Nidd Gorge (262)
