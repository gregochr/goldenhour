# Sub-regions: guidebook areas, Lite picks, and Ask — build plan

Status: **plan v2**, 2026-10-26. Reviewed by five adversarial lenses (data model, enforcement and caching, Ask,
picks and the change rule, consistency and phasing); every finding is folded in, and §10 records what each lens
changed. Owner-approved inputs: the sub-region list and allocation (§3, Appendix A), the change rule's shape (§4.3),
and that Ask must understand sub-regions (§6). Open decisions are in §9; no phase starts until the questions marked
as blocking it are answered.

## §1 What and why

**Product change.** A Lite account picks up to *n* **sub-regions** (§9 Q1; the design brief assumes 3) and sees
forecast ratings only for locations inside them. Pro and Admin see everything and never pick. There are no users yet
(owner, 2026-10-26), so no existing Lite reader is migrated.

**This supersedes part of a recorded owner decision.** `docs/engineering/reference/roles-and-gating.md`, *Freemium
split* (2026-10-25, "matching photocast.online's pricing list, FAQ and Terms §4"), gives Lite "the star rating, the
verdict, stand-downs, region prose, hot topics and Coming up" with no geographic limit. This plan narrows that to the
reader's picks (owner, 2026-10-26). The narrowing takes effect only in S6 (§8), whose PR rewrites that section and
`api-endpoints.md`, and which is gated on the public pricing, FAQ and Terms being updated first.

**Why sub-regions rather than regions.** The four enabled regions are too few and too uneven to ration: Northumberland
& Tyneside holds 101 locations from Berwick to Hartlepool, North York Moors & Coast 36.

**Where the sub-regions come from.** The contents pages of six photography guides (Long Valley Books for all four
regions; fotoVUE for the Lake District and Northumberland), adjusted by the owner and checked against the production
roster exported on 2026-10-26 (263 locations, all enabled): 24 sub-regions of 6 to 16 locations (§3).

**What sub-regions are not.** Not a forecast unit. Region verdicts, `VerdictSampleGate`, the honesty filter and every
rating store stay keyed on region. A sub-region is a labelled set of locations used for **gating** and **naming**; it
never carries a verdict. (Sub-region *verdicts* would need the unbuilt R1 measurement in `ask-window-context-plan.md`
§3; nothing here depends on it.)

## §2 Decisions (planner recommendations; the owner may overrule any)

| # | Decision |
|---|---|
| D1 | **A sub-region is a V189 named area with `area.kind = 'SUB_REGION'`** (`'NAMED'` for the existing kind; the column is new). Ask's area machinery then knows every sub-region's name and aliases. Membership does **not** use `area_location` (D3). |
| D2 | **Sub-regions partition their region.** Every location with a region belongs to exactly one sub-region of that region. Ordinary `NAMED` areas keep their freedom (overlap, partial cover). |
| D3 | **Sub-region membership lives in its own table**, `location_sub_region (location_id BIGINT PRIMARY KEY REFERENCES locations(id) ON DELETE CASCADE, area_id BIGINT NOT NULL REFERENCES area(id) ON DELETE RESTRICT)`. The primary key is the "one per location" rule, enforced by Postgres with nothing to drift. `area_location` cannot carry it: `AreaEntity.locationIds` is an `@ElementCollection` (`AreaEntity.java:55-59`) that Hibernate rewrites as bare `(area_id, location_id)` rows (`AreaService.setLocations`, `:144-145`). Writes go through one scoped service with native `INSERT … ON CONFLICT (location_id) DO UPDATE`; `AreaService.setLocations` refuses a `SUB_REGION` area (400, "use the location's sub-region field"). `RepositoryAskAreas.load()` (`:47-50`) and `AreaService.toDto` read a `SUB_REGION` area's members from this table. `area.region_id` (FK, `ON DELETE RESTRICT`) is required when `kind = 'SUB_REGION'` (CHECK). |
| D4 | **Picks live in their own table**, `app_user_sub_region (user_id … REFERENCES app_user(id) ON DELETE CASCADE, area_id … REFERENCES area(id) ON DELETE CASCADE, PRIMARY KEY both)`, plus two `app_user` columns, `sub_regions_grace_until DATE NULL` and `sub_regions_changed_at TIMESTAMP NULL`, both `updatable = false` and written only by scoped repository updates (*Column-scoped writes*). |
| D5 | **The change rule** (§4.3): a grace period of `grace-days` starting at the reader's **first** choice; after it, a change that swaps anything out is allowed once per `lock-days`. Every comparison is on UK civil dates. Config: `photocast.sub-regions.lite-limit` (default 3), `grace-days` (7), `lock-days` (30), validated at startup (`@PostConstruct`, as `RegistrationProperties.java:57-61`). |
| D6 | **The server enforces; the client renders; gated is not missing.** For a shaped Lite response the server strips every rating field of an unpicked location (§4.2) **and marks it** (`gated: true` on the slot, row or entry, and a served `allowedLocationIds` on the briefing). The client never decides what is outside the picks (*backend-heavy*), and never counts a gated location as unrated (§5.4). |
| D7 | **The two-question rule, applied precisely.** Hot topics and Coming up stay free in every region for every role as *what is happening*: labels, location names, facts, counts. Any **rating embedded inside** a hot topic (the bluebell topic's per-location `score`, `bluebellSummary` and "Best" badge, `BluebellHotTopicStrategy.java:254-268`) is a rating and is stripped outside the picks. |
| D8 | **Fail closed.** `allowed = everything` only for a caller positively `PRO_USER` or `ADMIN`, or with enforcement off. Any other role, a failed picks read, an unregioned location, or a location with no sub-region yet is outside the picks. |
| D9 | **Enforcement ships behind `photocast.sub-regions.enforce` (default `false`)**, and the picker UI is drawn only when the GET says `enforced: true` (§9 Q10), so no reader is asked to choose before choosing means anything. |
| D10 | **Pre-selection is server-side.** The GET returns, for a reader with a home and no picks, the *n* sub-regions nearest home (distance to each sub-region's member centroid). It is a suggestion only; the server never saves picks the reader did not submit (§9 Q9). |

## §3 The sub-regions

| Region | Sub-region | Locations |
|---|---|---|
| Northumberland & Tyneside | Berwick & Holy Island | 12 |
| | Bamburgh to Howick | 13 |
| | Alnwick & the Coquet Coast | 7 |
| | Druridge Bay to Tynemouth | 14 |
| | Cheviots & Coquetdale | 12 |
| | **Tynedale** *(§9 Q2; the Wall and the South and North Tyne)* | 16 |
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
| | **Inland North York Moors** *(§9 Q3)* | 13 |

Member lists are in Appendix A. Owner calls in the allocation: Teesdale's three waterfalls are Swaledale; Ingleton,
Dentdale and Ribblesdale merge as The Three Peaks; Wharfedale is not split; Kielder & Otterburn folds into the Wall
sub-region (it held only Hareshaw Linn); the Central Lakes split along Long Valley's line.

### §3.1 Names

Each name must pass three rules: `NameNamespaceGuard.areaNameClash` (not equal to another area name or alias; not a
whole-word run inside any location name or alias, `NameNamespaceGuard.java:108-138`); not a run inside any **region
name**; and not a run inside, nor containing, another area's name or alias (`AskTools.matchArea` picks by run length,
`AskTools.java:1214-1234`). Results on the 2026-10-26 roster:
- **"Langdale"** is a run inside three location names, so the sub-region is **"The Langdales"** (Long Valley's title).
- **"Hadrian's Wall"** is the V189 named area (seeded with the 12 Wall sites; production may have been edited since
  through the A3 admin screen, so S1 re-reads it). Recommended: the sub-region is **"Tynedale"** and the Wall area
  stays an ordinary overlapping area (§9 Q2). A question naming both resolves to the Wall by run length (two words
  against one), deterministically.
- **"The North York Moors"** is a run inside the region name "North York Moors & Coast", so Ask's `look_up_place`
  would answer a question about the whole region with the 13 inland members, Whitby and the coast left out.
  Recommended: **"Inland North York Moors"** (§9 Q3), whose words are not a run of the region name.
- Every other name passes. **Consequence for the admin:** `locationNameClash` / `locationAliasClash` (via
  `areaContainedIn`) will refuse any later location name or alias containing a sub-region name **or alias**
  ("Swaledale Meadows", "Druridge Bay Country Park"). Accepted; the guard's own message explains it.

### §3.2 Aliases

**Area aliases must be at least two words** (`AreaService.java:79,103`, `cleanAliases(…, 2, …)`; named-areas plan).
So the one-word dale and town names ("Teesdale", "Keswick", "Ennerdale") cannot be seeded unless the owner lowers the
minimum to one word for `SUB_REGION` areas only (§9 Q4, a small code change). The two-word aliases below pass every
rule in §3.1 (checked by script against the roster, the region names and each other, 2026-10-26):

| Sub-region | Aliases |
|---|---|
| The Langdales | Langdale Valley |
| Swaledale | Upper Teesdale |
| Sunderland & the Durham Coast | Durham Heritage Coast, Durham coast, Sunderland coast |
| The Three Peaks | Three Peaks, Yorkshire Three Peaks |
| Robin Hood's Bay to Flamborough | Flamborough Head |
| Cheviots & Coquetdale | Cheviot Hills, Simonside Hills |
| Druridge Bay to Tynemouth | Druridge Bay |
| Alnwick & the Coquet Coast | Coquet coast |
| Wharfedale | Upper Wharfedale, Lower Wharfedale |
| Wensleydale | Upper Wensleydale |
| Wasdale, Eskdale & Duddon | Duddon Valley |
| Buttermere, Loweswater & Ennerdale | Buttermere valley |
| Tynedale | South Tyne |

If Q4 allows one-word aliases, add (each passes §3.1; several are words of their own sub-region's name, which is
allowed): Teesdale, Ribblesdale, Dentdale, Langdales, Ambleside, Windermere, Hawkshead, Derwentwater, Borrowdale,
Keswick, Haweswater, Eskdale, Ennerdale, Teesside, Flamborough, Coquetdale, Rothbury, Malhamdale, Airedale, Kielder.
**Refused by the guard** because a location carries the name (a reader asking gets those locations, which is the right
answer anyway): Holy Island, Lindisfarne, Berwick, Craster, Alnwick, Warkworth, Grasmere, Rydal, Coniston, Ullswater,
Wasdale, Loweswater, Crummock, Scarborough, Filey, Malham, Ingleton, Great/Little Langdale, Derwent Water, Breamish
Valley. **Left out by choice:** "Durham", "Tyneside" (would match two scopes), "the Cheviots" (matches only when "the"
is typed; "Cheviot Hills" covers it), fotoVUE's compass names (they do not map one-to-one).

## §4 Behaviour

### §4.1 Who picks

- **Lite**: holds 0..*n* picks. With enforcement on and zero picks, no ratings are visible and the zero-picks prompt
  offers the D10 suggestions (§9 Q9).
- **Pro, Admin**: no picks, no picker (GET status `NOT_APPLICABLE`; PUT 403). Settings says "Pro covers every
  sub-region" to Pro, nothing to Admin.
- **Pro→Lite demotion**: no picks yet, so the next choice is a first choice and starts a grace period (§4.3).
- **Lite→Pro promotion**: picks and dates are kept, unused; a later demotion finds them unchanged.
- **Delete and re-register** gives a fresh account and a fresh grace period. Accepted: Lite is free and registration
  is capped; tying picks to an email address costs more than the loophole.

### §4.2 What a shaped Lite response strips (enforcement on; per unpicked location unless stated)

| Endpoint | Stripped | Kept |
|---|---|---|
| `GET /api/briefing`, slots (`BriefingSlot`, already `forLite` for Pro fields) | `verdict`, `claudeRating`, `skyRating`, `displayVerdict`, `standdownReason`, `flags`, `neighbourOutlier`; set `gated: true` | location id, name, region, event, tide **state** facts |
| `GET /api/briefing`, region (`BriefingRegion`) | `bestRating`, `meanRating`, `meanRatingDelta` (reconstructable per location by subtraction; `bestRating` *is* an unpicked star when the reader holds no pick in the region) | `scoredLocationCount`; `displayVerdict`, `verdictLabel` and prose per §9 Q5 |
| `GET /api/briefing`, window and day peak (`BriefingWindow`, `BriefingDayPeak`) | `bestRating` | `verdict`, `confidence` per §9 Q5 |
| `GET /api/briefing`, hot topics | bluebell `bluebellLocationMetrics` (`score`, `exposure`, `bluebellSummary`), the "Best" badge, `BluebellMetrics.bestScore`; shaped **after** `withLiveOverlays`, at the edge | labels, `locationNames`, counts, facts |
| `GET /api/briefing`, unregioned slots (`BriefingEventSummary.unregioned`) | everything rated (no sub-region can cover them, D8) | as slots |
| `GET /api/briefing/digest` | each window's `pick`, `headline`, `regionName`, `locationName`, `locationId` (already for Lite) **plus** `bestRating` | `verdict`, `confidence` per §9 Q5 |
| `GET /api/briefing/evaluate/scores` | the row's rating fields; `gated: true` | id, date, event |
| `GET /api/forecast` (list, `?date=`, sparse rows) | `rating`, `triageReason`, `triageMessage`, `bluebellSummary`; `gated: true` | location, date, event, weather inputs |
| `GET /api/forecast/{id}` | the same; an id outside the picks gets the stripped DTO, never the full one (ids are enumerable) | as above |
| `GET /api/astro/conditions` | per §9 Q7: `stars`, `summary`, the `*Explanation` prose | location id, name |

**Checked and unchanged:** operator notifications (email, Pushover and toast go to the operator only,
`EmailNotificationService.java:71`); account email; the run and status SSE streams (counts and health only); Ask,
Ready, aurora and NLC (Pro only); Rewind and forecast history (Admin only); `/api/locations` and the almanac (no
ratings). **Accepted residual:** `GET /api/outcome/ratings` (no role gate) returns the reader's own past ratings with
the day's `forecastRating`; a demoted reader keeps seeing their own history.

### §4.3 The change rule, as the API states it

Let `today` = `ForecastHorizon.today(clock)` (UK civil date). Stored: `graceUntil` (a date, inclusive) and
`changedOn` = `ForecastHorizon.civilDate(sub_regions_changed_at)`.

- **First choice** (the reader holds no picks): always allowed; sets `graceUntil = today + grace-days − 1` if it is
  null, and stamps `changed_at`.
- **During grace** (`today ≤ graceUntil`): any change is allowed and stamps `changed_at`.
- **After grace**: a change that only **adds** picks while under the limit is allowed and stamps nothing (nothing is
  swapped out). Any change that removes a pick needs `today ≥ nextChangeOn`, where
  `nextChangeOn = max(graceUntil + 1, changedOn + lock-days)`, and stamps `changed_at`.
- A PUT equal to the current set is a 200 no-op and stamps nothing.
- **If `lite-limit` is lowered** below what a reader holds, the GET flags `overLimit: true`, and a PUT that only
  removes picks is allowed without a lock until the reader is within the limit. Until then, shaping uses the first *n*
  picks by pick time.
- **When an admin action removes a reader's pick** (a sub-region deleted, merged, or changed to `NAMED`), that reader's
  `changed_at` is cleared, so the re-pick is free. Moving a location between sub-regions silently changes what a pick
  covers; accepted.

GET `/api/user/settings/sub-regions` returns `{ enforced, limit, picks[], options[] (grouped by region, with counts and
two example names), suggested[], status, freeUntil?, nextChangeOn?, overLimit? }`, `status` ∈ `FIRST_CHOICE`,
`FREE_UNTIL`, `OPEN`, `LOCKED`, `NOT_APPLICABLE`. PUT `{ areaIds }`: 400 for an empty list, more than *n*, a duplicate
or a non-`SUB_REGION` id; 403 for Pro/Admin; 409 `SUB_REGIONS_LOCKED` with `nextChangeOn`; 200 with the new state.
**Concurrency:** the lock check and the stamp are one conditional `UPDATE … WHERE id = :id AND (changed_at IS NULL OR
changed_at < :cutoff)` (0 rows → 409), in the same transaction as the picks delete and insert. New timestamps are
written with `LocalDateTime.now(clock)`; only civil dates are compared.

**Admin tools:** `GET`/`PUT /api/users/{id}/sub-regions` and `DELETE /api/users/{id}/sub-regions/lock` (clears
`changed_at`), ADMIN only; a picks column in user management.

## §5 Engineering

### §5.1 Data and admin (S1)

Migration `V191__sub_regions.sql` (Postgres; read the number from the tree):
- `area.kind VARCHAR(16) NOT NULL DEFAULT 'NAMED'` with a CHECK of the two values; `area.region_id` (FK, `ON DELETE
  RESTRICT`), CHECK `kind <> 'SUB_REGION' OR region_id IS NOT NULL`; `location_sub_region` (D3).
- Seed the 24 areas and §3.2's aliases. Members are matched **by location name and by region**
  (`l.region_id = a.region_id`), each candidate list carrying the current and the corrected spellings, as V189 did
  (the owner is fixing seven misspellings in production: Edlinghham, Houseteads Fort, Innonimate Tarn, Ladore Falls,
  Lamley Viaduct, Peel Craggs, Pen-Y-Gent).
- **Pre-flight** (`DO $$`, raising): the four regions exist; the 24 candidate lists are pairwise disjoint; no seeded
  name or alias breaks a §3.1 rule, using the guard's exact folding (lower-case, strip `'` and `’`, every
  non-alphanumeric run to one space: `regexp_replace(…, '[^[:alnum:]]+', ' ', 'g')`). A raise blocks production boot,
  so the folding is proven in the migration test. **Post-check** (`RAISE NOTICE`): the count of enabled, regioned
  locations without a sub-region (a fresh CI database holds only seeded locations).
- **Local and unit-test schemas** come from Hibernate (`ddl-auto: update` locally, `create-drop` in tests; no Flyway),
  so: `@ColumnDefault("'NAMED'")` on `kind`, with null read as `NAMED`; the CHECKs and keys are proven only in the
  Testcontainers test; `AskLocalFixtureSeeder` seeds one sub-region so local Ask and the picker have data. If `kind` is
  `@Enumerated(STRING)`, any future value needs a `LocalH2EnumWidener.TARGETS` line.
- **Before writing the S1 brief, grep the migration tests that plant locations and then migrate to latest**
  (`NorthernBluebellWoodsMigrationTest`, `BigSeasHighWaterMigrationTest`, …) for planted names that would trip the
  pre-flight.

Backend: `AreaEntity` gains `kind` and `region`; `AreaDto` and `frontend/src/api/areaApi.js` gain both; a
`SubRegionMembershipService` is the only writer of `location_sub_region` (move = upsert; the location's region must
equal the sub-region's); `LocationService.add` / `update` accept an optional `subRegionId`; a region change on a
location deletes its now-mismatched membership and logs it; deleting a `SUB_REGION` area with members is refused
(409), and deleting a region that has sub-regions is refused by the FK and reported in words. Disabled locations keep
their membership; unregioned and disabled locations are not counted as "uncovered". The admin Areas section shows kind
and region, and "N enabled locations have no sub-region: …".

### §5.2 Picks API (S2)

The tables and columns of D4; `UserSubRegionService` (§4.3, pure, `Clock`-injected); the user and admin endpoints of
§4.3; `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` gains `/api/user/settings/sub-regions` (the path is
already outside the ETag filter). The registration step calls the same GET and PUT. It comes **after `HOME`**
(`RegisterPage.jsx:12`; `/set-password` signs the reader in, `AuthController.java:458-489`), so D10 has a home to work
from, and it skips itself when `status` is `NOT_APPLICABLE` (`default-role` is configurable,
`RegistrationProperties.java:33`) or `enforced` is false.

### §5.3 Enforcement (S3)

`LitePickShaper` sits beside `BriefingRoleShaper`, at the same controller edges, and is given `allowedLocationIds`
from one picks read per request (D8: everything only for positive PRO/ADMIN or enforcement off; empty on any
failure). Internal callers (Ask, the digest service, the pipeline) keep full data, as P2 did.

**Caching (§9 Q8).** The ETag filter runs at `HIGHEST_PRECEDENCE`, before Spring Security, and decides by path only
(`HttpCachingConfig.java:150-161`); it sets `private, no-cache` before the chain runs. So the per-response switch is in
the controller: a shaped Lite response returns `ResponseEntity.ok().cacheControl(CacheControl.noStore())`, which
replaces the preset header, and `ShallowEtagHeaderFilter` then declines to add an ETag for `no-store` (Spring's
`isEligibleForEtag`; confirm on the Spring 7 jar in the build). Why: a body shaped by picks reveals the picks, which
are pre-filled from the home postcode, and a `private, no-cache` body may sit in the browser's disk cache after logout,
where JavaScript cannot clear it (the localStorage SWR cache *is* cleared on logout, `AuthContext.jsx:98`). Cost: Lite
loses 304s on `/api/briefing`, the largest polled payload. Pro, Admin and the flag-off path are unchanged.

### §5.4 Frontend (S4, S5)

**S4, the picker** (from the Claude Design output): the registration step after "Where's home?" and the Settings
section, drawn only when the GET says `enforced`. After a successful PUT the client clears the SWR entries for the
briefing and forecast (`clearSwrCache`) and refetches. Nothing is derived on the client: status, dates, suggestions
and counts come from the GET.

**S5, outside the picks** (from a second design, §9 Q5): every surface that counts rated against total must route
gated locations out of both numbers, never into "unrated" or "insufficient sample": the spread histogram
(`hasSpreadSample`), thin-sample `· N rated`, `card.tideFit`, the Map's `scopeCount`, the region panel counts, and the
heat field (`buildHeatSpots` drops spots without data). Also the Map Filters scope and the `My area | Whole catalogue`
segment (`utils/planningArea.js`), the Plan matrix region cards and the location sheet for unpicked places, and Coming
up. The house Pro pattern throughout (`ProUpsellDoor`: a pill, worded text, no link, no "upgrade"), phone-only rules,
and the dialog-stacking limits.

## §6 Ask

Sub-regions are areas (D1), so `look_up_place` and `rank_spots` know their names and aliases for Pro and Admin. Ask is
Pro-only, so it never filters by picks. **Small Ask changes, in S1:**
- `RepositoryAskAreas.load()` reads `SUB_REGION` members from `location_sub_region` (D3).
- **One area per call.** `look_up_place` returns the single longest area match (ties alphabetical) and `rank_spots`
  takes one `areaName`, so "Swaledale or Wensleydale?" silently answers Swaledale. Add an "Also matched areas: …" note
  to the `look_up_place` result and one prompt sentence: call once per named area.
- `KNOWN_AREAS_CAP` (`AskTools.java:146`, 200 characters) cuts the "Known:" list to about 8 of 25 names; raise it so
  every name fits.

**Checked and unchanged:** result sizes (an area result is capped at `MAX_AREA_WINDOWS` 4 × `MAX_AREA_PLACES` 6 and
carries a member count, not the members, so the `AskToolsNamedAreaTest` 4,500-character pin holds); areas are not in
the prompt; the snapshot load is memoised; the typed cache key; Ready intents (every new name holds a word outside
`ReadyIntentRules.EVERY_ACCEPTED_WORD`, so `couldMatchAny` already declines them, and `namesAPlace` can only decline
more).

## §7 Reference docs to update (in the phase that changes the behaviour)

S1: `ask-photocast.md` (the `SUB_REGION` kind, the one-area-per-call note), `database-migrations.md` (V191). S2:
`api-endpoints.md` (user and admin sub-region endpoints), `configuration.md` (`photocast.sub-regions.*`),
`platform.md` (the registration step). S3: `api-endpoints.md` (the §4.2 strip list), `architecture-decisions.md`
(gated is not unrated). S5: `map-tab.md`, `plan-tab.md`. S6: `roles-and-gating.md` (*Freemium split* rewritten), and a
CLAUDE.md invariant line ("Lite ratings outside the reader's picks are stripped and marked `gated` at the controller
edge; never on the client").

## §8 Delivery

| phase | what | blocked by | tests (in addition to the gates) |
|---|---|---|---|
| **S0** | this plan | — | — |
| **S1** data, admin, Ask | V191; `kind`, `region`, `location_sub_region`; membership service; `LocationService` and `AreaService` changes; admin Areas UI; the §6 Ask changes | Q2, Q3, Q4 | `SubRegionsMigrationTest` (Testcontainers, following `NamedAreasMigrationTest`: migrate to 190, plant, migrate to 191; the 24 names and aliases; both CHECKs; the PK refusing a second sub-region; the pre-flight raising on a clash and on its folding); membership service move/refuse/region-change tests; `AreaService.setLocations` refusing a sub-region; Ask "also matched areas" and the cap |
| **S2** picks API | tables, columns, `UserSubRegionService`, user and admin endpoints, config validation | Q1, Q11 | `UserSubRegionServiceTest` on a fixed `Clock` at every boundary of §4.3 (grace end, lock end, a BST midnight, a no-op PUT, add-only, over-limit, admin removal); controller 400/403/409; the concurrent-PUT conditional update; the `HttpCachingConfigTest` entry; a mutation check on the lock comparison (back up with `cp`) |
| **S3** enforcement | `LitePickShaper` on every §4.2 row, `gated` markers, `no-store`, fail-closed; the flag stays off | Q5, Q6, Q7, Q8 | `LitePickShaperTest` (flag on/off × LITE, PRO, ADMIN, no role; a completeness test over every rating field, as `forLite`'s); per-endpoint controller tests including `/api/forecast/{id}` outside the picks; `HttpCachingIntegrationTest`: shaped Lite → `no-store` and no ETag, Pro and flag-off → `private, no-cache` + ETag |
| **S4** picker UI | registration step, Settings section | Q10, the Claude Design output | role-query tests (`frontend-test-standards.md`), the hidden-when-not-enforced branch, no `<a>`, no "upgrade", the phone branch, SWR cleared after PUT |
| **S5** outside-picks UI | §5.4 S5, built and tested against flag-on bodies locally | Q5, Q9, its design | every rated/total surface with gated spots; the same role and phone tests |
| **S6** flip | `enforce: true` in prod config; the §7 S6 docs; `swrCache.js` `PREFIX` bump (`v3` → `v4`) so no pre-flip body paints | public pricing, FAQ, Terms updated (owner) | a config test pinning the prod value |

Each phase: Sonnet builds in its own worktree from a brief cut from this file and runs the gates (backend build
ladder; frontend lint, Vitest, audit, build); the planner reviews the diff; one PR; CI and Codex green before merge.
The order deliberately differs from `pro-features-gate-plan.md` (client before server): here the flag keeps the
server strip dark until S5's client is in, so S3 may land first.

## §9 Open questions for the owner

1. **Q1 (S2): how many picks for Lite?** The design brief assumes 3.
2. **Q2 (S1): the Wall.** Recommended: the sub-region is **"Tynedale"** and the V189 "Hadrian's Wall" area stays as it
   is. The alternative, promoting the Wall area to the sub-region, would make Ask's "Hadrian's Wall" include Allen
   Banks, Lambley Viaduct, Letah Wood and Hareshaw Linn (Bellingham).
3. **Q3 (S1): the inland Moors name.** Recommended: **"Inland North York Moors"**. It must not be a run of the region
   name "North York Moors & Coast".
4. **Q4 (S1): one-word aliases for sub-regions** (Teesdale, Keswick, Ennerdale …)? Recommended: **yes, for
   `SUB_REGION` only**, since that is how people name these places, at the cost of a small guard change and its tests.
5. **Q5 (S3, S5): what Lite sees outside its picks.** The locations themselves (markers, names) with a Pro door, or
   nothing? And the region-level `displayVerdict`, `verdictLabel` and prose, and the window `verdict`/`confidence`:
   free (they summarise every location, including unpicked ones; the prose counts spots such as "three rated Go" but
   names none) or stripped? Recommended: locations visible without ratings; region and window verdicts and prose
   free; every per-location star and every best or mean star stripped (§4.2).
6. **Q6 (S3): hot topics** free in every region, with embedded per-location ratings stripped (D7)? Recommended: yes.
7. **Q7 (S3): astro night-sky stars** (`/api/astro/conditions`, per location, open to Lite today) and **past
   windows** (`/api/forecast?date=` in the past): inside the ration or outside it? Recommended: both inside (astro is a
   per-location star; past windows inside is simplest and consistent).
8. **Q8 (S3): shaped Lite bodies sent `no-store`** (§5.3)? Recommended: yes.
9. **Q9 (S5): zero picks.** Recommended: no default picks saved; the zero-picks prompt shows the suggestions.
10. **Q10 (S4): picker hidden until enforcement is on?** Recommended: yes.
11. **Q11 (S2): the change rule as written in §4.3** (grace from the first choice; add-only changes free after grace;
    every swap stamps the lock)?

## §10 Review record (2026-10-26)

| Lens | What it changed |
|---|---|
| Data model | `area_location.partition` dropped (Hibernate's `@ElementCollection` writes NULL, bypassing the rule) for `location_sub_region` with the location as PK; region-checked, disjoint, folding-exact seed; local-schema defaults; FK delete behaviour; uncovered-location counting; S1 test list. |
| Enforcement | Astro conditions and the bluebell hot topic found carrying per-location ratings; region, window and digest best and mean stars stripped; the full per-endpoint strip list written now; `/api/forecast/{id}` and unregioned slots; `no-store` moved from the filter (path-only, before security) to the controller; fail-closed picks read. |
| Ask | Area aliases must be two words (20 of v1's were one); "The North York Moors" would hijack the region; one area per call made explicit with a note; `KNOWN_AREAS_CAP`; the result-size worry withdrawn; Ready intents confirmed safe. |
| Picks and rule | Grace started at pending registration, not at use: it now starts at the first choice; every swap stamps; civil dates; an atomic lock; cascades; the registration step after HOME; admin tools; config validation and a lowered limit. |
| Consistency | Supersession of the 2026-10-25 freemium split recorded, with an S6 flip; gated must not read as unrated (honesty filter); picker hidden until enforced; missing surfaces (scope controls, Plan cards, astro, past windows, SWR); per-phase tests; new questions Q7–Q10. |

## Appendix A — allocation (production roster, 2026-10-26)

Location names as stored on 2026-10-26, misspellings included; ids are production ids (a fresh database's ids
differ, which is why V191 matches by name).

**North York Moors & Coast**

- **Redcar to Whitby** (13): Infinity Bridge (51), Redcar (53), Runswick Bay (60), Saltburn-by-the-Sea (55), Saltholme (206), Saltwick Bay (63), Sandsend (61), South Gare & Paddy's Hole (54), Staithes (59), The Tees Transporter Bridge (52), Whitby Abbey (76), Whitby Piers (75), Whitby Town (62)
- **Robin Hood's Bay to Flamborough** (10): Bempton Cliffs (82), Boggle Hole (78), Breil Nook (84), Bridlington North Beach (86), Filey Brigg (81), Ravenscar View (79), Robin Hood's Bay (77), Scarborough (80), Selwicks Bay & High Stacks (85), Thornwick Bay (83)
- **Inland North York Moors** (13; name §9 Q3): Bank Top (93), Cockshaw Hill (88), Doctors Wood, Grosmont (231), Egton Lone Tree (94), Falling Foss (96), Hasty Bank (89), Hawnby Hill (91), Hole of Horcum (97), Newton Wood, Roseberry Topping (261), Rievaulx Abbey (92), Roseberry Topping (87), Sutton Bank (90), Thomason Foss (95)

**Northumberland & Tyneside**

- **Berwick & Holy Island** (12): Berwick-Upon-Tweed (1), Cocklawburn Beach (3), Duddo Stone Circle (31), Emmanuel Head (7), Guile Point (208), Holy Island Causeway (8), Holy Island Harbour (5), Lindisfarne Castle (4), Lindisfarne Priory (6), Ross Sands (207), Routin Lynn (32), Spittal Beach (2)
- **Bamburgh to Howick** (13): Bamburgh Castle (10), Beadnell (11), Black Hole (15), Budle Bay (9), Cullernose Point (16), Dunstanburgh Castle (14), Embleton Bay (13), Howick Scar (17), Low Newton-by-the-Sea (12), Rumbling Kern (18), Stag Rock Lighthouse (209), Sugar Sands (19), The Bathing House, Craster (210)
- **Alnwick & the Coquet Coast** (7): Alnmouth (20), Alnwick Castle (34), Amble (21), Edlinghham (36), Low Hauxley (22), St Cuthbert's Cross, Alnmouth (211), Warkworth Castle (35)
- **Druridge Bay to Tynemouth** (14): Bluebell Wood, Morpeth (228), Blyth Beach (26), Bothal Weir (214), Bothal Woods (212), Cambois Beach (25), Collywell Bay (215), Cresswell Beach (23), Cullercoats (29), Newbiggin-by-the-Sea (24), Plessey Woods (227), Seaton Sluice (27), St. Mary's Lighthouse (28), The River Wansbeck, Bothal (213), Tynemouth (30)
- **Cheviots & Coquetdale** (12): Blawearie (222), Cheviot (219), College Valley (218), Harbottle Hills (37), Hepburn Moor (221), Hethpool (217), Ingram and the Breamish Valley (223), Raven's Crag & Bowden Doors, Belford (220), Simonside (38), Thrunton Wood (224), Windy Gyle (216), Yeavering Bell (33)
- **Tynedale** (16; name §9 Q2): Allen Banks (40), Caw Gap (43), Cawfields (42), Crammel Linn Waterfall (225), Cuddy's Crags (49), Hareshaw Linn (39), Highshield Crags (47), Hotbank Crags (48), Houseteads Fort (50), Irthing Gorge (259), Lamley Viaduct (226), Letah Wood (260), Peel Craggs (45), Steel Rigg & Winshield Crags (44), Sycamore Gap (46), Walltown Crags (41)
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
