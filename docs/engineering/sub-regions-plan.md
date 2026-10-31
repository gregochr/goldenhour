# Sub-regions: guidebook areas, Lite picks, and Ask — build plan

Status: **plan v2, decided**, 2026-10-26 (owner answered §9 in full). Reviewed by five adversarial lenses (data model, enforcement and caching, Ask,
picks and the change rule, consistency and phasing); every finding is folded in, and §10 records what each lens
changed. Owner-approved inputs: the sub-region list and allocation (§3, Appendix A), the change rule's shape (§4.3),
and that Ask must understand sub-regions (§6). Open decisions are in §9; no phase starts until the questions marked
as blocking it are answered.

## §1 What and why

**Product change.** A Lite account picks up to *n* **sub-regions** (*n* is an admin setting, default 3: §9 Q1) and sees
forecast ratings only for locations inside them. Pro and Admin see everything and never pick. There are no users yet
(owner, 2026-10-26), so no existing Lite reader is migrated.

**This supersedes part of a recorded owner decision.** `docs/engineering/reference/roles-and-gating.md`, *Freemium
split* (2026-10-25, "matching photocast.online's pricing list, FAQ and Terms §4"), gives Lite "the star rating, the
verdict, stand-downs, region prose, hot topics and Coming up" with no geographic limit. This plan narrows that to the
reader's picks (owner, 2026-10-26). The narrowing takes effect only in S6 (§8), whose PR rewrites that section and
`api-endpoints.md`, and which is gated on the public pricing, FAQ and Terms being updated first. **S6 is built:** the
freemium split is rewritten and `enforce: true` is set in `application-prod.yml` (§11, *S6 notes*).

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
| D5 | **The change rule** (§4.3): a grace period of `grace-days` starting at the reader's **first** choice; after it, a change that swaps anything out is allowed once per `lock-days`. Every comparison is on UK civil dates. **The pick limit *n* is an admin setting in the database** (owner, 2026-10-26): a single-row `sub_region_policy (id SMALLINT PRIMARY KEY CHECK (id = 1), lite_limit SMALLINT NOT NULL DEFAULT 3 CHECK (lite_limit BETWEEN 1 AND 24), updated_at, updated_by)`, seeded with 3, read through a cached service and edited in the Operations tab (§5.2; moved to Data → Users on 2026-10-31). `grace-days` (7) and `lock-days` (30) stay in config (`photocast.sub-regions.*`), validated at startup (`@PostConstruct`, as `RegistrationProperties.java:57-61`). |
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
| | **Hadrian's Wall & Tynedale** *(§3.1)* | 16 |
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
- **"Hadrian's Wall & Tynedale"** (owner, 2026-10-26). The V189 named area "Hadrian's Wall" (seeded with the 12
  Wall sites) **stays as it is**, an ordinary `NAMED` area, so Ask's answers about the Wall stay precise. The
  sub-region is a new area holding those 12 plus Allen Banks, Lambley Viaduct, Letah Wood and Hareshaw Linn: the
  recognisable name in the picker, and accurate. **This is the one deliberate exception to the containment rule
  above:** the sub-region's name contains the Wall area's name. It resolves deterministically because
  `AskTools.matchArea` takes the longest matching run: a question saying "Hadrian's Wall" matches only the Wall area
  (the sub-region's longer name is not in the question), and one saying "Hadrian's Wall & Tynedale", or an alias such
  as "South Tyne" (§3.2), matches the sub-region. V191's pre-flight allows exactly this pair and nothing else.
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
| Hadrian's Wall & Tynedale | South Tyne, North Tyne |

Q4 allows one-word aliases for sub-regions, so V191 also seeds (each passes §3.1; several are words of their own sub-region's name, which is
allowed): Teesdale, Ribblesdale, Dentdale, Langdales, Ambleside, Windermere, Hawkshead, Derwentwater, Borrowdale,
Keswick, Haweswater, Eskdale, Ennerdale, Teesside, Flamborough, Coquetdale, Rothbury, Malhamdale, Airedale, and
Tynedale and Kielder (for Hadrian's Wall & Tynedale).
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
- **If the admin lowers the limit** below what a reader holds, the GET flags `overLimit: true`, and a PUT that only
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
  name or alias breaks a §3.1 rule (the one allowed exception: "Hadrian's Wall & Tynedale" containing "Hadrian's
  Wall"), using the guard's exact folding (lower-case, strip `'` and `’`, every
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

The tables and columns of D4; `sub_region_policy` (D5) with `GET`/`PUT /api/admin/sub-region-policy` (ADMIN) and a
"Lite sub-region picks" number control (2026-10-31: moved from the Operations tab's Sub-regions sub-tab, which is gone, to a *Lite limit* button in the Data → Users list header that opens it in a dialog), which says how many Lite readers hold more picks than a
lowered value before it is saved (they keep them until they next change, §4.3); `UserSubRegionService` (§4.3, pure, `Clock`-injected); the user and admin endpoints of
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
(gated is not unrated). S5: `map-tab.md`, `plan-tab.md`. S6: `roles-and-gating.md` (*Freemium split* rewritten), `landing/` (`index.html`, `faq.html`, `terms.html`), and a
CLAUDE.md invariant line ("Lite ratings outside the reader's picks are stripped and marked `gated` at the controller
edge; never on the client").

## §8 Delivery

| phase | what | blocked by | tests (in addition to the gates) |
|---|---|---|---|
| **S0** | this plan | — | — |
| **S1** data, admin, Ask | V191; `kind`, `region`, `location_sub_region`; membership service; `LocationService` and `AreaService` changes; admin Areas UI; the §6 Ask changes | Q3, Q4 | `SubRegionsMigrationTest` (Testcontainers, following `NamedAreasMigrationTest`: migrate to 190, plant, migrate to 191; the 24 names and aliases; both CHECKs; the PK refusing a second sub-region; the pre-flight raising on a clash and on its folding); membership service move/refuse/region-change tests; `AreaService.setLocations` refusing a sub-region; Ask "also matched areas" and the cap |
| **S2** picks API | tables, columns, `sub_region_policy` and its admin control, `UserSubRegionService`, user and admin endpoints, config validation | Q11 | `UserSubRegionServiceTest` on a fixed `Clock` at every boundary of §4.3 (grace end, lock end, a BST midnight, a no-op PUT, add-only, over-limit, admin removal); controller 400/403/409; the concurrent-PUT conditional update; the `HttpCachingConfigTest` entry; a mutation check on the lock comparison (back up with `cp`) |
| **S3** enforcement | `LitePickShaper` on every §4.2 row, `gated` markers, `no-store`, fail-closed; the flag stays off | Q5, Q6, Q7, Q8 | `LitePickShaperTest` (flag on/off × LITE, PRO, ADMIN, no role; a completeness test over every rating field, as `forLite`'s); per-endpoint controller tests including `/api/forecast/{id}` outside the picks; `HttpCachingIntegrationTest`: shaped Lite → `no-store` and no ETag, Pro and flag-off → `private, no-cache` + ETag |
| **S4** picker UI | registration step, Settings section | Q10, the Claude Design output | role-query tests (`frontend-test-standards.md`), the hidden-when-not-enforced branch, no `<a>`, no "upgrade", the phone branch, SWR cleared after PUT |
| **S5** outside-picks UI | §5.4 S5, built and tested against flag-on bodies locally | Q5, Q9, its design | every rated/total surface with gated spots; the same role and phone tests |
| **S6** flip | `enforce: true` in prod config; the §7 S6 docs; `swrCache.js` `PREFIX` bump (`v3` → `v4`) so no pre-flip body paints | the landing site in `landing/` is updated in the same S6 PR: `landing/index.html` (Lite tier: ratings for up to {limit} sub-regions you choose; Pro: every sub-region), `landing/faq.html` (Lite answers, plus a new entry on choosing sub-regions, the free first week and the 30-day lock), `landing/terms.html` (§4 tier wording); the owner releases it with `release-landing.sh` together with the enforcement flip, never before | a config test pinning the prod value |

Each phase: Sonnet builds in its own worktree from a brief cut from this file and runs the gates (backend build
ladder; frontend lint, Vitest, audit, build); the planner reviews the diff; one PR; CI and Codex green before merge.
The order deliberately differs from `pro-features-gate-plan.md` (client before server): here the flag keeps the
server strip dark until S5's client is in, so S3 may land first.

**S6 done (2026-10-27).** `photocast.sub-regions.enforce: true` in `application-prod.yml` (pinned by `SubRegionsProdYamlTest`; local and example stay `false`); `swrCache.js` `PREFIX` `v3` to `v4` (and the `photocast_swr:v3:` strings in `PlanErrorBoundary` and its test); *Freemium split* rewritten; the landing pages (`index.html`, `faq.html`, `terms.html`) say Lite ratings cover up to 3 sub-regions you choose. The owner releases the landing site with `release-landing.sh` together with the backend flip, never before. **S5b-2 (Coming up) followed after the flip and is built.**

## §9 Open questions for the owner

1. ~~Q1~~ **Decided (owner, 2026-10-26):** an admin setting, default 3 (D5, §5.2).
2. ~~Q2~~ **Decided (owner, 2026-10-26):** the sub-region is "Hadrian's Wall & Tynedale"; the V189 "Hadrian's Wall"
   area stays unchanged (§3.1).
3. ~~Q3~~–~~Q11~~ **Decided (owner, 2026-10-26): every recommendation accepted.** So: Q3 "Inland North York Moors";
   Q4 one-word aliases allowed for `SUB_REGION` areas only (`NAMED` areas keep the two-word minimum), and §3.2's
   one-word list is seeded; Q5 locations visible without ratings, region and window verdicts and prose free, every
   per-location, best and mean star stripped; Q6 hot topics free with embedded ratings stripped; Q7 astro stars and
   past windows inside the ration; Q8 `no-store` for shaped Lite bodies; Q9 no default picks, the zero-picks prompt
   shows suggestions; Q10 picker hidden until enforcement is on; Q11 the change rule as written in §4.3.

## §10 Review record (2026-10-26)

| Lens | What it changed |
|---|---|
| Data model | `area_location.partition` dropped (Hibernate's `@ElementCollection` writes NULL, bypassing the rule) for `location_sub_region` with the location as PK; region-checked, disjoint, folding-exact seed; local-schema defaults; FK delete behaviour; uncovered-location counting; S1 test list. |
| Enforcement | Astro conditions and the bluebell hot topic found carrying per-location ratings; region, window and digest best and mean stars stripped; the full per-endpoint strip list written now; `/api/forecast/{id}` and unregioned slots; `no-store` moved from the filter (path-only, before security) to the controller; fail-closed picks read. |
| Ask | Area aliases must be two words (20 of v1's were one); "The North York Moors" would hijack the region; one area per call made explicit with a note; `KNOWN_AREAS_CAP`; the result-size worry withdrawn; Ready intents confirmed safe. |
| Picks and rule | Grace started at pending registration, not at use: it now starts at the first choice; every swap stamps; civil dates; an atomic lock; cascades; the registration step after HOME; admin tools; config validation and a lowered limit. |
| Consistency | Supersession of the 2026-10-25 freemium split recorded, with an S6 flip; gated must not read as unrated (honesty filter); picker hidden until enforced; missing surfaces (scope controls, Plan cards, astro, past windows, SWR); per-phase tests; new questions Q7–Q10. |

## §11 S4 build notes (2026-10-27)

**The UI spec** for S4 is the owner's Claude Design export, "design_handoff_sub_regions" (Part A: the picker, A1 to A7;
Part B, the Users tab rebuild, is a later phase and was not built). The handoff lives with the owner, not in the repo;
where it and `frontend/src/index.css` disagree the codebase won (existing `card`, `btn-primary`, `btn-secondary`,
`Modal`, `BottomSheet`, `ErrorBanner`, `ProTag`).

**Two server-computed fields on `GET /api/user/settings/sub-regions`** (`SubRegionState`, NON_NULL), so the client does
no date arithmetic (*Backend-heavy*):

- `freeUntilIfChosenToday`: `FIRST_CHOICE` only. The grace end a first save today would set: `today + grace-days - 1`,
  or the grace end the reader already holds if it has not ended (an admin cleared every pick). Absent when that grace
  has ended. Used for the registration and sign-in rule line.
- `nextChangeOnIfSavedToday`: `OPEN`, and `FIRST_CHOICE` once grace has ended. The first day a swap would be allowed
  after a save today that stamps a lock, `max(graceUntil + 1, today + lock-days)` (§4.3), read on the UK calendar
  (`nextChangeOn(graceUntil, now, lockDays)`). Used for the save confirmation title.

**The handoff's date rule was wrong.** It titled the confirmation "today + lock-days - 1" (and asked whether the server
uses +30 or +29). The server's rule (§4.3) is `max(graceUntil + 1, changedOn + lock-days)`: a save on 26 October with
lock 30 is 25 November (`today + 30`, not `+ 29`), and a save late in the first week is held to `graceUntil + 1` if that
is later. Tested on a fixed `Clock`, including a BST midnight (`UserSubRegionServiceJpaTest`).

**Departures from the handoff, all deliberate.**

- *Phone save confirmation* replaces the content of the picker's own bottom sheet rather than opening a third dialog
  (Settings, sheet, confirmation would break the two-stacked limit). Desktop uses a `Modal` portalled to `<body>` so
  Settings' `inert` (`stacked`) does not reach it.
- *The sign-in prompt* is a modal dialog over the already-mounted app (the `PostcodePromptOverlay` shape), not a gate
  before it: the answer arrives one request after mount, and a gate would delay every sign-in with the flag off.
- *"Step 5 of 6"* is read off the registration flow list, and is correct whenever the step is shown (it skips itself
  otherwise).
- *Limit words* (one to ten) apply to the suggestion line only; the intro keeps digits.
- *Toasts* are a small portalled status line (`SubRegionToast`); the app has no toast system. A failed save is an
  inline alert, and the picker stays open for a retry. On 409 `SUB_REGIONS_LOCKED` the dialog closes, the state is
  read again, and the status line states the date.
- *"30 days"* in the copy is the config default (`lock-days`); the server sends no lock length, so a changed config
  would need the copy revisited.
- *Refetch after a save*: the briefing provider gains a `subRegionsVersion` prop (refetches briefing and ratings
  when it moves) and `App` also calls the forecasts' `refresh`; `clearSwrCache()` runs first, only on success.

**Review and browser-check follow-ups (same day).** Registration waits for the sub-region read with HOME's buttons held
(`PostcodeCapture` now awaits `onSaved`) and gives up after 4 s, carrying on to the Welcome. The sign-in prompt waits
while any other modal is up (`useModalPresent('data-sub-region-prompt')`), latches once shown, and the postcode prompt
counts it as foreign too: the two never stack, and the order is whichever the app learns of first (not fixed).
`useForecasts` drops a response older than one already applied. `nextChangeOnIfSavedToday` is also sent for
`FREE_UNTIL` (a grace-week save stamps), and the toast then says when the lock after the free week ends. The OPEN line
reads "Removing one starts a 30-day lock." (an add-only change stamps nothing). The phone picker sheet is the house
`tall` sheet (viewport less 24px, not the handoff's 64px) with a pinned header (title, status line, count) and footer;
its confirmation swaps to the default short sheet. LOCKED with room under the limit still labels the button View.

**Flag off is a no-op.** Every surface is gated on `enforced === true`: registration skips the step, sign-in shows
nothing, Settings renders no section (and an Admin makes no request). Pinned by tests in the registration, sign-in,
Settings and `App` suites.

### S4b Users rebuild (2026-10-27)

Part B of the same handoff: Manage → Data → Users rebuilt around the S2 admin endpoints (`UserManagementView` plus
`components/users/*`; behaviour in `reference/platform.md`, the wire in `reference/api-endpoints.md`).

**Backend, admin-only.** `GET /api/users` gains `subRegionStatus`, `freeUntil`, `nextChangeOn`, `overLimit`,
`awaitingVerification` and `verificationSentAt`. They come from `UserSubRegionService.adminSummaries`, one pick query
for the whole list; the status is the same private `standing(...)` the reader's own GET uses, so the two cannot drift.
`GET /api/users/{id}/sub-regions` now carries `options` (a Lite reader's; a Pro reader's kept `picks` were already
there), never `suggested`. `awaitingVerification` is `!enabled && password == ''`, the shape `adminResendVerification`
acts on (`VerificationTokenIssuer.issue` refuses anything else). The link's send time is one grouped query on the
unverified tokens (`findLatestUnverifiedCreatedAt`), read back in the JVM zone it was written in and sent as an instant.

**Last active needed no tracking.** `app_user.last_active_at` already exists (`JwtAuthenticationFilter` touches it at
most hourly, login stamps it) and `lastActiveAt` was already in the summary, so the column stays.

**Departures from the handoff.**

- *Resend verification email* acts at once (a toast confirms) with no dialog: the handoff's B6 lists no dialog for it
  and it is not destructive. *Enable account* likewise has none.
- *Save picks* is off with nothing chosen (Clear all picks is that action, and confirms) and over the limit; an
  unchanged selection may be saved, which clears the lock.
- *Terms* reads `Accepted {date} · {termsVersion}` with the stored string (for example "April 2026"), not "v3": the
  column is free text.
- *The role-change note* says "Admin sees every sub-region" for Admin (the handoff's wording is Pro's), uses the
  singular for one pick, and for a move between Pro and Admin, which changes nothing about sub-regions, shows none.
- *Escape* closes the whole panel from any mode (Change picks, Edit); an open dialog keeps the key.
- *`formFieldFocusRules`* now reads the shared form classes from `components/users/userFormClasses.js` (the
  `const inputClass` definitions moved out of `UserManagementView.jsx`).
- *P2 notes (Data tabs, 2026-10-31).* The Users tab moved onto the shared list-and-panel shell
  (`data-tabs-redesign-plan.md` P2). **A deliberate tablet change:** 1000 to 1023px went from list-replaced to split, and
  640 to 999px from list-replaced to an overlay under a scrim; Users also gained the phone page (a "‹ Users" back bar
  carrying Edit, safe-area insets, focus to the title and back to the row, the list `inert` behind an overlay). The
  `<table>` is now `DataGrid` (CSS grid, `role="button"` rows): the columns the old `compact` prop dropped (Status, Last
  active, Joined) are `secondary`, so a container query drops them when the panel narrows the list, instead of a JS prop;
  the old compact order (Sub-regions before Role) is not kept, the wide order (User, Role, Sub-regions, ...) holds in
  both. A card row keeps Role on line 1 and the sub-regions count on line 2, with a non-Active status under the role.
  The chip row still hides while a panel is open. Row accessible names now read the whole row
  (`anna_r, anna.r@example.com, Lite, Active, ...`). Paging keeps the open panel; filtering the open reader out of the
  list closes it. Esc still steps back from Change picks and Edit to the reader before closing (the "closes the whole
  panel" departure above describes the first build; the shipped behaviour and its tests are step-back).
  *P2 review follow-ups.* (i) The secondary columns drop by the list's own width (container query at 820px), so they
  also drop with no panel open when the list is narrow; a status other than Active then falls back under the role
  (at 820px and under, and in cards). (ii) Desktop deltas from the shared shell in the Users panels: section padding is
  14px, the panel body scrolls inside the panel (the header stays), the × is 36px, and Change picks' footer sits in the
  shell's footer. (iii) Only a filter change (search or chip) can close the panel on a filter-out; the admin's own
  refreshes (Enable under Disabled, Clear lock under Locked, a role or email edit, a picks save, a Lite-limit save) and a
  Back/Forward to a reader the filters exclude leave it open. (iv) Focus moves to the panel title on every mode change
  (view to Edit or Change picks, and back by Cancel, Save, Esc or the phone back bar); the phone back bar steps back from
  a sub-mode to the reader (labelled with the username) before it leaves for the list. (v) A row's accessible name is
  `username, role, status, n sub-regions, standing, last active, joined`: no address, no sub-region names.

## §11 S5 build notes (2026-10-27)

S5 is the Map, Plan and Coming-up treatment of the places outside a Lite reader's sub-regions (the owner's Claude Design
export "design_handoff_s5_outside_picks", tasks 1 to 9). The handoff lives with the owner, not in the repo; where it and the
codebase disagree the codebase wins. S5a (the Map) ships as **three PRs**, S5b (the Plan strip and rail, task 6, and
Coming up, task 8) follows. The plan below was revised after a three-lens review.

### Owner decisions (27 Oct 2026)

- Check 1 (best stars for a Lite reader's own places): the **served `picksBestRating`**.
- Check 2 (window-panel order): a Lite row is ordered by verdict tier, then name (Lite has no drive).
- Check 3 (pill star): **no star on the Lite pill**.
- Check 4 (coastal and dark-sky membership for Coming up): S5b.
- Check 5 (veil colour against `--color-topic-air`): the builder checks the veil.

### Planner decisions from the review (deviations from the handoff are marked with a diamond)

1. **Gating keys.** The field keys on each served row's `gated: true` (scores rows, briefing slots); chrome keys on the
   briefing's served `allowedLocationIds` being an **array** (`Array.isArray`, an empty array means shaped with zero picks).
   Never the role, never a client membership test. The briefing context must publish `allowedLocationIds`. Amended in S5a-1 review: a place is `gated` when the served `allowedLocationIds` (shaped body) does not list it, whatever its row says or whether it has one, because the shaper marks only rows that exist; a selection by a list the server decided, not a client rule.
2. **`gated` is its own heat state.** `heatSpots.windowState` gains `'gated'` from `row.gated`; gated spots are in neither the
   colour pool, the hatch, nor `thinRatedCount`'s rated or total, nor `scopeCount`, nor "k of m". `architecture-decisions.md`
   Backend-heavy records the gated filter on each licensed member it touches (`atFourPlus`/`placeCount`, `thinRatedCount`,
   spread, `scopeCount`); no new member.
3. **Served: each place's sub-region.** `LocationDto` gains `subRegionId` and `subRegionName` (role-free facts, ETag-safe, from
   `location_sub_region`). This feeds outside-row names, "{n} places in {k} sub-regions", the sheet block and the mixed-panel names.
4. **Served: `BriefingRegion.picksBestRating`** (NON_NULL, set only by `LitePickShaper.shapeRegion`, last):
   `BriefingSlot.votingSlots(region.slots())` (the region's own canopy rule) filtered to allowed ids, max taken through
   `BriefingRatingStats.compute`; neighbour outliers included exactly as region `bestRating` is. Null when none. Every wither
   carries it; a 22-arg convenience constructor keeps old callers; `ReviewedTypes` lists it; the sentinel gives unpicked slots a
   distinct star (5 vs picked 2) and asserts `"picksBestRating":2`, no 5 under the region, no key for `PickScope.nothing()` or unshaped.
5. **Served: the window's lead region for a shaped body.** Today `mapVerdict.windowVerdict` -> `pickTopEligibleRegion` skips
   regions with no `meanRating`, which the shaper strips, so a shaped Lite pill loses its verdict. The shaper sets
   `BriefingEventSummary.shapedLeadRegion` (NON_NULL, shaped only): the region the unshaped ranking would pick **restricted to
   verdict tier**, ties by name (never by a gated mean or star). The client reads it when shaped. Sentinel-covered.
6. ◆ **Region-label door dropped.** Region labels live in an `aria-hidden`, `pointer-events:none`, collision-culled pane
   (`MapLabels.jsx`). The zoomed-out third line is plain text: **{n} sub-regions picked** or **outside your sub-regions** (grey),
   no button. The region door lives in the region panel (and the window panel row opens it). This also keeps one door per surface.
7. ◆ **Scope segment for shaped Lite** is its own state: shown whenever shaped (Lite has no reach, so today's segment never
   shows), labels **Your sub-regions | Everywhere**, **camera only** (fits allowed bounds / roster); it does not touch `heatArea`
   or `scopeBasePool`. Hidden when zero picks.
8. **Zero picks** (`allowedLocationIds: []`, status `FIRST_CHOICE`): the veil covers the land, no outline, the legend note is
   replaced by **Choose your sub-regions in Settings to see ratings.** with a **Choose your sub-regions** button; the window-panel
   note line is omitted; no scope segment.
9. **S4 status -> action** (reuse S4's `subRegionCopy.js`/`settingsStatusLine`; never restate S4 rules): `FIRST_CHOICE` ->
   **Choose your sub-regions**; `FREE_UNTIL`/`OPEN` -> **Change your sub-regions**; `LOCKED` under the limit -> **Add a
   sub-region**; `LOCKED` at the limit -> no button, line **You can next change your sub-regions on {date}.**; `overLimit` ->
   **Change your sub-regions** (S4 copy explains); `NOT_APPLICABLE` -> never shaped, never reached. The status fetch stays out of
   `swrCache` and refetches on `subRegionsVersion`.
10. **Opening Settings on the picker.** `UserSettingsModal` `focusField` gains `'subRegions'`: scrolls to and opens
    `SubRegionSettingsSection` once its state loads. From the location sheet, close the sheet first (two dialogs max).
11. **Doors** reuse `ProUpsellDoor` unchanged except a new optional `note` prop (in `aria-describedby`). Constants
    `SUBREGIONS_ALL_`, `_REGION_`, `_PLACE_`. Copy rules: "your {n} sub-regions" uses the applied pick count, not the limit;
    singulars everywhere (place/places, sub-region/sub-regions); example places take the region's first two sub-regions' first
    `exampleLocations` (one if only one); collapse outside lists only when n > 4 (show 3 + "Show all {n}").
12. **Location sheet (gated).** `data-scope="picks"` grey variant of `wf-loc-out`; gated wins over `outsideScope`. Rating slot
    empty, no `unratedWord`/"Not scored". The first row opens for a gated place only once scores have loaded.
13. **Heat layers** in the shared kernel (`utils/heatField.js`) so S5b's `drawGeo` reuses them: veil (radius x 1.15, suppressed by
    all allowed places' coverage) and dashed outline at the 0.42 contour via a hand-written marching squares (no new dependency).
    Both take `opacity` and `focus`, clip to land, and are not drawn above `LAND_CLIP_MAX_ZOOM`. The veil also applies on
    astro/aurora nights (those are shaped too). Measure repaint cost.
14. **Dropping the star:** the pill dropdown rows and `nightPillName` must not print `—` or "not scored yet" for a gated subject;
    no-picks rows take the Pro-pill branch explicitly; allowed-but-unrated keeps "not scored".
15. **Lite window-panel rows:** regions with picks first, then verdict tier, then name; stat "{k} of {m} at 4★+" (no drive),
    "· {n} more outside" when any. The note line's "best region" is `shapedLeadRegion`.
16. **Grey dots:** the name chip is the tap target; its accessible name says "outside your sub-regions" and never falls through to
    "not rated".

### The phase split

- **S5a-1, data and field:** decisions 1 to 5, 13, 16, and the legend rows (handoff task 2). Backend and frontend.
- **S5a-2, chrome and panels:** 6, 7, 8, 14, 15, the window and region panels (tasks 2 to 4) without doors.
  Phone: the Map legend is desktop-only (`MapView.jsx` ~7169 `!isMobile`), so S5a-2 adds the outside-picks key and note to a phone surface, such as the peek sheet or the legend affordance.
- **S5a-3, doors, sheet, Settings:** 9 to 12, the task 7 doors, the task 5 sheet.

Each: Sonnet builds in its own worktree, gates, planner review plus a browser check against mocked `/api` with flag-on shaped
bodies, one PR, CI and Codex thumbs-up, merge.

### S5a-1 build notes

**Backend.** `LocationDto` gains `subRegionId`/`subRegionName` from one query (`SubRegionMembershipService.subRegionsByLocation`,
a join of `location_sub_region` and `area`), handed to `LocationDtoMapper.toDtoList(entities, map)`; the one-argument forms
still map to null. `BriefingRegion` gains the 23rd component `picksBestRating` (a 22-argument convenience constructor keeps the
old callers; every wither carries it; `withPicksBestRating`); `BriefingEventSummary` gains `shapedLeadRegion` (a five-argument
convenience constructor, `withShapedLeadRegion`). Both are set only by `LitePickShaper`. The lead region is chosen by
(verdict-eligible, has a mean, `displayVerdict` tier, has slots, name) over the canopy-eligible regions: the mean's *presence* is
asked because the client skips a mean-less region, so the unshaped pick never lands on one; its *value* never orders anything.
`LitePickSentinelTest` gives unpicked slots a 5 and the picked one a 2 and walks the region's JSON for any 5.

**Frontend.** New `utils/pickScope.js` (the shaped marker, `isGatedIn`, `splitGround`, `hasRatedState`). The veil and outline live
in `heatField.js` (`coverageGrid`, `contourLines`, `planOutsidePicks`, `paintVeil`, `paintOutline`), taken by `drawTiles` and
`drawGeo` through `opts.outside`/`opts.allowed`, and drawn by `MapHeatLayer` only below the land-clip zoom with the land path in
hand. `paintMasked` was split so the veil reuses the hatches' blur, clip and opacity.

**Deviations from the handoff, all deliberate.**

- *Veil ink* `rgba(156,158,160,.18)` (neutral grey) instead of `rgba(150,160,170,.20)`: the handoff's tint was within a few units
  of `--color-topic-air` (#8FA3B8), whose rings can be on the map together with the veil. A neutral grey at a lower alpha composes
  to about #393737 on the plate and reads as a wash beside a 2px #8FA3B8 stroke.
- *One allowed-coverage grid at radius x 0.9* serves both the outline and the veil's suppressor (the handoff suppresses at x 1.0).
- *A reader with no picks sees no dashed "your sub-regions" legend row*: no outline is drawn for them, and a key to a missing line
  would be false. They also get no "this event is not scored yet" line.
- *The window's served `bestRating` is null for a shaped Lite response*, which the unscored line, the colour key and the
  "not rated yet" hatch were gated on. For a shaped body they ask the place states whether any of the reader's own places is rated
  (`hasRatedState`); no star is shown anywhere.
- *A place with no score row* (a wood, a hide, a sky place unrated for the window) is gated from the served `allowedLocationIds` (`buildHeatSpots` takes it as a Set), not from its rows, so it is under the veil and out of every count; zero picks gates every place and the veil covers all the land.
- *The pill takes the lead region from the served field and falls back to the same order over the in-scope records* when the lead
  lies outside the scope segment, so a scope-limited Lite reader still gets a verdict word.

**Repaint cost.** Measured with a scratch script (not committed) on a 260-place roster, 40 of them allowed, over a 1280 x 800
frame at the Map's grid of 6: planning the two layers (both coverage grids and the contour) took 12, 19 and 42 ms at kernel radii of
30, 45 and 90 px with the field's own pitch, which is too much to spend on every frame of a pan. The plan therefore samples at
`max(grid, min(radius / 4, 12))`, which brings it to about 9, 7 and 13 ms at those radii (11 to 22 ms at the 150 px extreme). The
canvas draws (a temp-canvas blur and one `destination-in` for the veil, one stroked path for the outline) cannot be timed outside a
browser and were not measured. A paint is throttled to one per animation frame.

### S5a-2 build notes

Decisions 6, 7, 8 (rest), 14 and 15, the window and region panels without doors or the S4 change buttons, and the phone key.
The surface notes are in `docs/engineering/reference/map-tab.md` (*Outside your sub-regions: the chrome, the panels and the
phone key*). The choices that were the builder's to make:

- **Picks source (decision 6): `LocationDto.subRegionId`/`subRegionName` over the served `allowedLocationIds`**, not S4's
  `GET /api/user/settings/sub-regions`. Both are served facts and need no client rule; the first is already in the body on
  screen, so the label and the field cannot disagree after a refetch, and it needs no extra request. `useForecasts` and
  `buildHeatSpots` now carry the two fields; `WindowFirstMapPane` puts `regionPicks` and `allowedBounds` on `heat`. S5a-3's
  status fetch (the S4 GET, outside `swrCache`, refetched on `subRegionsVersion`) is still its own.
- **Stand-down precedence (decision 6).** A stood-down region label keeps its two lines and takes no picks line; a stood region
  always holds a pick, so no information is lost, and the label's measured box does not grow.
- **Scope segment (decision 7).** A Lite reader can have a `hasHome` (a postcode or an away origin), so the old segment stays
  exactly as it was and the new one is a second row, shown whenever `heat.allowedBounds` can be framed. `picksView` is its own state feeding `heatBounds` only (null until
  pressed, so the camera opens where it always did, and the roster frame shows pressed). `resetToMyArea` clears it.
- **Window-panel note (decision 15).** The handoff's "otherwise the note is unchanged" would print "Regions rank on that
  average" over a body with no means, so a shaped body whose lead region holds a pick gets one sentence on the order instead.
  The second sentence drops "too" when the best picked region's verdict differs from the lead's. A leading "The" in a region
  name is lowercased inside the sentence and the "the" before a name without one is dropped ("Best in the Yorkshire Dales",
  "Best in Northumberland & Tyneside").
- **"n places" on a no-pick row** counts every place the region holds (all are outside), so it matches the region panel's list,
  while "k of m at 4★+" keeps its `m` of allowed sky places.
- **Dropdown dash (decision 14).** The dash goes from a shaped body's window rows and unscored night rows (the server strips
  the window's best star, so it said "unscored" of every window); `nightPillName` drops "not scored yet". A solar row with no
  verdict keeps its dash, which says there is no verdict, not that a place is outside the picks.
- **Phone key.** The peek sheet's **Layers** section, beside the colour key and the ground key it already holds: the least
  intrusive surface, with no new control. Same gate as the desktop key; the note text is shared (`picksNoteText`).
- **Seams for S5a-3.** `MapRegionPanel`'s `outsideSlot` (inside the no-pick block) and `actionsSlot` (the actions row). The
  zero-picks legend line stays text.
- **Architecture note.** `regionPickSummaries` is a new named member of the Backend-heavy class (a count and a sort over
  served lists), recorded in `architecture-decisions.md`.

### S5a-3 build notes

Decisions 9 to 12, the task 7 doors (all but the coast door, S5b), the task 5 sheet and the S4 buttons the S5a-2 seams were
left for. Surface notes are in `docs/engineering/reference/map-tab.md` (*Outside your sub-regions: the status buttons, the
doors and the location sheet*). The choices that were the builder's to make:

- **One status read, in a context.** `SubRegionStatusProvider` sits inside `WindowFirstBriefingProvider` (which already holds
  `allowedLocationIds` and `subRegionsVersion`), reads only while the body is shaped, and also carries the app's Settings
  opener. A surface mounted without it (every earlier test) draws no button. The sheet takes its own opener from the shell, so
  it can close itself and the popup under it first.
- **Door counts are served facts, not the S4 GET.** "Total", the roster's sub-region count, the places the picks cover and the
  applied pick count all come from `regionPickSummaries` and `heat.spots`, so a door never waits on the status read. Only the
  example places need the S4 options, and a door whose options have not loaded simply omits "X and Y among them".
- **Door copy.** The handoff's "{short name}" region form is not a thing the roster carries; the door names the region as
  `regionInSentence` does ("the Yorkshire Dales", bare "Northumberland & Tyneside"), and the body reads "across the {k word}
  sub-regions of {region}" in place of the possessive. The all-door body prints "your {n word} sub-regions" from the applied
  pick count and "{n} place(s)" for the places they cover.
- **Region panel with picks has no S4 button**, only the Every-sub-region door under the actions (the handoff names no button
  there); a new `footerSlot` holds it. The region door uses the inline popover variant (the panel is a capped scroller).
- **The location sheet's `outsidePicks` is `isOutsidePicks(allowedLocationIds, id)`**, a shaped list that does not name the
  place; a place with no id is never claimed to be outside. An away row keeps its "away" state label even for a gated place
  (it is not a rating word). A wildlife hide's hourly body keeps the badge but not the "no ratings" block.
- **Callout.** The S5a-1 check that saw "Not rated" was `MapCallout`'s verdict slot (`unratedSlotWord` of the slot's served
  verdict); it now takes `outsidePicks` and says "Outside your sub-regions". The phone peek's per-window "Not rated" is about
  past windows, not places, and is unchanged.
- **Review fixes (second commit).** Map status lines say "your sub-regions", never "these" (`settingsStatusLine(state, subject)`; Settings' wording is unchanged), and the locked-with-room sentence and its predicate (`lockedAddLine`, `isLockedAdd`) are shared with Settings. A hide outside the picks keeps the line, button and door and drops only "has no ratings". The region door reads "in {region's} {k word} sub-regions, {a} and {b} among them". The sheet header names the sub-region. A gated place with no prose keeps a "Four days here" route in the callout. The status provider shows no state across a save, and the sheet's route hands Settings a focus fallback. On a phone the non-modal region panel stays mounted under Settings and the picker sheet: only two modals ever stack, within the rule.
- **Settings deep-open** adjusts state during render once the section's state has loaded (no setState in an effect), then
  scrolls; focus is not moved into the picker (the dialog's own focus stays), which a later accessibility pass may revisit.

## §11 S5b build notes (2026-10-27)

S5b is the Plan tab and Coming up for a Lite reader outside their picks (handoff tasks 6, 8 and 9; frames 08, 09, 10, 11). It builds on S5a-1..3 (decisions 1 to 16 above). The plan was revised after a two-lens review. Same gating rule: draw only on a shaped body (`Array.isArray(allowedLocationIds)`); gated means not in the served list. Pro, Admin and flag-off are byte-identical. One door per surface, at most two stacked dialogs, singulars ("1 place", "1 sub-region", "1 outside"), "Outside your sub-regions" never shortened, never "not rated" / "not scored" / "—" / "0" / the hatch for a gated subject.

**Two PRs: S5b-1 the Plan, S5b-2 Coming up.**

### The plan as built from (S5b-1 items, then S5b-2)

- **B1. A live regression, fixed first.** `LitePickShaper.shapeWindow` nulls `BriefingWindow.bestRating`; the card copies it (`windowFirstCards.js`), and the strip keyed `unscored` on `card.bestRating == null`, so every shaped card read not scored (plate hatch, `data-unscored`, "not scored" in the accessible name, thin count suppressed). For a shaped body `unscored` is `!hasRatedState(states)` over that window's heat-spot states, as `MapView` does.
- **B2. Gated spots on the Plan pool.** `buildWindowSpots` dropped `slot.gated`, so gated slots became unrated spots in the spread total and unrated bar, `hasSpreadSample`/`thinVerdictCount`, `spokenPoolPhrase`, the rail's `drawnCount`, the popup's spot strip and sheet and `planSearch`'s figure. The fix carries `gated: true` onto the spot (from `slot.gated` and from not-in-`allowedLocationIds`), keeps `allSpots`/`spots` intact (the Map and the eclipse `raceSpot` read them), derives `pool` as the places inside the picks (so `bestReach`, spread and `tideFit`), and carries `outsideCount`.
- **1. Thumbnails**: per-window `allowed`/`outside` point sets into `drawGeo`'s `opts.allowed`/`opts.outside`; the outline is 1px; repaint cost measured over up to 8 thumbnails. Zero picks: veil over all land, no outline, no `· 0 rated`.
- **2. Verdict line** unchanged; the thin `· N rated` is over allowed places only.
- **3. Spread row**: label **Spread (your sub-regions)**, histogram over allowed places only; zero picks omit the row.
- **4. Best row**: `bestReachOf(card.pool)` names the best allowed place and that place's own served star. `beyondReachLine` and the generic "not scored yet" must never fire for a gated reason: when the pool is empty because of the picks the row is omitted (zero picks), or says **None of your sub-regions are rated for this {sunrise|sunset} yet.** when allowed places exist and none is rated.
- **5. Legend line** under the strip: the **outside your sub-regions** (with a Pro pill, a label not a door) and **your sub-regions** swatches; the note is S5a's `picksNoteText`; zero picks add the single **Choose your sub-regions** button from `subRegionAction()`. Phone: swatches and note on their own wrapped line.
- **6. Region rail** (inside the modal `WindowSheetDialog`): order picks first, then verdict tier, then name (decision 15), rank 1 = `shapedLeadRegion` when it has picks; label for shaped (**By region · your sub-regions first**); All cell **{n} in your sub-regions · {m} outside** (zero picks: **{m} outside your sub-regions**, never "0 in"; no star); a no-pick region keeps its verdict and says **outside your sub-regions** (grey); a picked region says **best {x}★ · {n} places** with x the served `picksBestRating` (omitted when null) and **· {m} outside** when any; the meta wraps on a phone. Selecting a no-pick region: prose slot = region prose + the S4 status line and action + the region door; the spot list says **None of your sub-regions are in {region}.** (zero picks: no region door, the one Choose button lives in the legend). The S4 action from the popup closes the popup first through the existing `openSubRegions` path, so Settings and its confirm never make three dialogs.
- **7. Popup spot strip and sheet list**: allowed spots as today; gated spots after them, labelled **outside your sub-regions** (grey, no badge, no star); "N of M" counts allowed places only with "· {m} outside" apart.
- **8. `card.tideFit`/`tideRun`**: already inert for Lite (`LocationTideFact.forLite`); test only.
- **9. `regionSeries`** and the popup's region best-window line read a stripped `meanRating`: render silence, never "not scored".
- **Known residual, accepted:** an allowed spot's `neighbourOutlier` is computed against all its neighbours.
- **10. (S5b-2) Coming up membership** uses the Map's own predicates over served `/api/locations` facts and the served `allowedLocationIds` (a licensed selection, recorded in `architecture-decisions.md` like `regionPickSummaries`): coastal = `locationType` contains `SEASCAPE`; dark sky = `bortleClass <= DARK_SKY_THRESHOLD` (`utils/darkSky.js`), null Bortle = not dark. Import the predicates; never `tideType`.
- **11. (S5b-2) The line** under the door row (dashed top rule, grey dot, mono 10px) when no allowed place matches: **None of your sub-regions are on the coast. These days open the map with coastal spots shown outside your sub-regions.** / **None of your sub-regions are dark-sky sites. These nights open the map with dark-sky spots shown outside your sub-regions.** / zero picks: **Choose your sub-regions in Settings to see ratings at coastal spots.** / **…at dark-sky spots.** Chips and doors stay live; tiles and cards unchanged.
- **12. (S5b-2) The door**: one per tab, a sibling after the card (never inside), on the first rendered entry showing the line, by the `tideLightDoorId` rule, skipping an entry that carries the tide-light door; it carries the S4 status line and action and the Pro door (`SUBREGIONS_COAST_`: **Coastal spots everywhere**, "Pro rates all {n} coastal spots, Berwick to Bridlington, on every morning and evening of the run."; `SUBREGIONS_DARKSKY_`: **Dark-sky spots everywhere**, "Pro rates all {n} dark-sky spots, in every sub-region, on every night of the run."). Zero picks: the door is the Choose button only.

### S5b-1 build notes

The surface notes are in `docs/engineering/reference/plan-tab.md` (*Outside your sub-regions on the Plan*) and the Backend-heavy members in `architecture-decisions.md`. The choices that were the builder's to make:

- **The shaped switch reaches the cards through the lens object** (`lens.allowedIds`, a Set), because `buildWindowCards`' signature is at its limit; the context builds the Set once and the heat spots take the same one. Shaped-only fields (`outsideCount`, `picksScoped` on a card; `gated` on a spot) are spread on only for a shaped body, so a Pro, Admin or flag-off card is the very object it always was (the existing Plan snapshot and role tests pass unedited).
- **B1 reached a second surface.** The popup's field map (`WindowRowFieldMap`) keyed its "Not scored" chip and whole-plate hatch on the same served best. It takes an `unscored` override (the dialog passes `false` for a shaped body), and a shaped window with none of the reader's places rated hatches around those places only. The popup's field does not draw the veil or the outline (handoff task 6 names the thumbnails); the map there names the reader's places only.
- **A shaped plate is never hatched whole.** The handoff keeps the strip's "not rated yet" hatch; over a veiled plate it would also cover ground outside the picks, which is not "not rated yet". An unscored shaped window hatches around the reader's own unrated places (`ground.unrated`), and the card still says "not scored" in words.
- **Scores not loaded is silence.** `buildGroundSets` publishes `rated: null` until `/evaluate/scores` has answered, and the strip treats null as "not claimed" (no hatch, no "not scored"); the grounds themselves do not wait, since they come from the served list.
- **Counts follow the strip below them.** A region's "n places" and the All cell's "n in your sub-regions" are the places the window DRAWS inside the picks (the same array the spot strip renders), while "which regions hold a pick" is the served `regionPickSummaries`, so a picked region whose own place has no slot in the window is not mislabelled outside.
- **The Spread qualifier** is "your sub-regions" for a shaped body: a Lite reader's lens is pinned to "Any", so the plain label would have read "(any drive)".
- **A reader with no place of their own** gets no Spread row, no Best row, no thin count and no pool phrase on a card; the popup says **Choose your sub-regions in Settings to see ratings.** where it has no list, and the popup header leaves out its best line.
- **Repaint cost** (measured with a scratch script, not committed, on a 260-place roster with 40 allowed; planning the veil and the outline for 8 thumbnails, the work this phase adds to a repaint): 21 ms at 160px cards, 21 ms at 240px and 25 ms at 360px, 2.6 to 3.2 ms per thumbnail, against 60 to 210 ms for a whole-matrix repaint already. The canvas draws (the veil's blur and clip, one stroked path) cannot be timed outside a browser and were not measured. A repaint happens per layout change, not per render. A cheap further saving if it ever bites: the grounds are the same in every window (the list is the reader's, not the window's), so one plan per card width would serve all eight.
- **Accepted residual:** an allowed spot's `neighbourOutlier` is computed against all its neighbours (the server's guard sees every place), so a reader's place can be skipped in the Best row by a neighbour they cannot see. The star stays; the row may say "no clear pick".

### S5b-2 build notes (Coming up)

**The series is complete: S1 to S6, S5a-1..3, S5b-1 and S5b-2 are all built.** The surface notes are in `docs/engineering/reference/plan-tab.md` (*Outside your sub-regions on Coming up*), the licensed selection in `architecture-decisions.md`, the door in `roles-and-gating.md`. The choices that were the builder's to make:

- **One shared predicate file.** `utils/locationPredicates.js` holds `isCoastalLocation` and `isDarkSkyLocation`; `MapView`'s two dark-sky filters now call the latter. The Map's coastal filter is a generic type-chip filter (a place with no type passes), so it is not the same function; the Coming up predicate is the plan's "contains `SEASCAPE`".
- **The roster reaches the pane as a prop** (`locations`, from `WindowFirstShell`), not through the briefing context; the shaped switch is the context's `allowedLocationIds`. An unshaped body or a missing roster draws nothing, and the pane's markup is unchanged.
- **The line is silent until the roster loads**, and not drawn on an entry whose door is withheld (no row to put it under). A one-day entry reads "This day opens" / "This night opens".
- **The door count is the roster's**, per the plan ("n by the coastal predicate"), not the reader's. At a roster of one the body drops "all" and the range ("Pro rates the coastal spot on every morning and evening of the run.").
- **One door per tab; it takes the first line's subject.** It goes on the first entry that shows either line and carries that entry's subject (coastal or dark-sky), so a mixed tab still has exactly one. It draws no wrapper when there is neither a Settings opener nor a Pro door to show.
- **Residual (accepted):** coastal stays the strict SEASCAPE test, since widening it would make the line false. An allowed place with no location type therefore does not suppress the line, while the Map's type filter lets an untyped place through, so the coastal map view can show it. A test pins it.
- **Not done:** no browser pass (no backend or browser was started); the claims are tested, not seen.

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
- **Hadrian's Wall & Tynedale** (16): Allen Banks (40), Caw Gap (43), Cawfields (42), Crammel Linn Waterfall (225), Cuddy's Crags (49), Hareshaw Linn (39), Highshield Crags (47), Hotbank Crags (48), Houseteads Fort (50), Irthing Gorge (259), Lamley Viaduct (226), Letah Wood (260), Peel Craggs (45), Steel Rigg & Winshield Crags (44), Sycamore Gap (46), Walltown Crags (41)
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
