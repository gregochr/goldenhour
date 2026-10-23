# Ask: named areas ("Hadrian's Wall") and place aliases ("the Heugh") — build plan

Status: **plan**, 2026-10-23, reviewed (two adversarial lenses, findings folded in; §9). Three phases, one
PR each: A1 (data), A2 (Ask), A3 (admin editing, UI). Each is built by a Sonnet subagent from this file;
§8 holds the briefs.

## §1 The defect

On 2026-10-23 a reader on the Map (Tomorrow sunrise, "Everywhere") asked Ask **"Does Hadrian's Wall look
good this weekend?"**. Ask answered **"Not in the forecast — Hadrian's Wall is not in the forecast
database"**, while the Map behind the answer showed Walltown Crags, Cawfields, Peel Craggs, Hotbank Crags,
Cuddy's Crags, Irthing Gorge and Crammel Linn Waterfall, all 4★.

**Cause (read from the code, not confirmed against production logs).** Ask's only way to talk about a
named place is `look_up_place` (`docs/engineering/ask-place-lookup-plan.md`, v2.30.4). It matches the
reader's name against **each location's own name**, as a contiguous run of whole words
(`AskTools.placeWords` / `containsRun`). No location is called "Hadrian's Wall", so the tool returns
`NO_SUCH_PLACE_NOTE`, and the typed prompt (`AskPromptBuilder.PLACE_FIT_AFTER`) tells the model to answer
`answerable: false`. Nothing in the data says which places are "on the Wall". `locations` has a region, but
a landmark is not a region.

**The same gap at the scale of one place.** Readers also call a single place by another name. Hartlepool
Headland (V138) is locally "the Heugh", after Heugh Lighthouse and the Heugh Battery. "How's the Heugh
looking?" fails the same way.

**The fix.** A small, admin-owned list of **named areas** (a name, its aliases, its member locations) and of
**location aliases** (other names for one location), both understood by `look_up_place`, and areas also by
`rank_spots`.

**Defence in depth (not a live defect).** `KeywordAskIntentMatcher.namesAPlace` keeps a question that names
a place or region away from a Ready answer. An area or alias question is already declined earlier today,
because `ReadyIntentRules.couldMatchAny` requires every word to be in its accepted vocabulary, and
"hadrians" / "wall" / "heugh" are not. Adding area names and aliases to `placeNames` only matters for a
future name made entirely of accepted words ("High Tops"), and A2 does it for that reason.

## §2 Decisions (planner recommendations; the owner may overrule any)

| # | Decision |
|---|---|
| D1 | An area is **explicit membership** (area → location ids), not geometry. A landmark can be a line (the Wall is 117 km) or an irregular patch, and a radius or box would pull in places such as Allen Banks that a reader would not call "on the Wall". |
| D2 | An area may cross regions. The question's scope still applies per member: a member in an out-of-scope region is not shown. (Whether the Wall's seed members actually span regions is not asserted here; §5's query shows it.) |
| D3 | `look_up_place` matches **areas as well as places**. An area result is an **explanation**, exactly like a place lookup: it adds nothing to evidence, so nothing from it can become a pick. |
| D4 | `rank_spots` gains an optional **`areaName`** filter, in typed conversations only. Its results are ordinary `rank_spots` results: pick-eligible only and recorded as evidence, so "where on the Wall should I go?" gets real picks. |
| D5 | Ready is untouched: no `areaName` in Ready's `rank_spots` schema, no new tool and no prompt change, so Ready's prompt and tool bytes do not change. |
| D6 | No association from `LocationEntity` to areas or aliases. Membership and aliases live in tables owned elsewhere, so no `setX()` + `save()` of a location can rewrite them (CLAUDE.md "Column-scoped writes"). |
| D7 | Seeded by migration in A1; editable by an admin in A3. |
| D8 | Solar windows only, like `look_up_place`. Night tools do not take an area in this series. |
| D9 | A **location alias** is another name for one location. The place matcher tests a slot's name **and** its aliases with the same `containsRun` rule. A row still shows the real name, plus the alias that matched, so the answer can say "Hartlepool Headland (the Heugh)". |
| D10 | **An exact place match beats an area.** `look_up_place` always runs both matches. If any place matches exactly (name or alias words equal to the query's words), the place result wins; otherwise an area match wins; otherwise the place result as today. So "Roman Wall Viewpoint" (if such a place existed) is never hidden by the area alias "Roman Wall". |
| D11 | The word "area" already means region scope in the prompt ("outside the area"). Every new prompt and schema sentence says **"named area"**. |

**Owner questions** (the build goes ahead on the recommendation unless the owner says otherwise):

- **Q1 — the Wall's members.** Production location names for the central Wall were added through the
  admin UI, not by migration, so the plan cannot read them from the tree. Only "Crammel Linn" (V50) and
  "Irthing Gorge" (V169) are migration-seeded, and the Map now shows "Crammel Linn Waterfall", so names
  get edited. The A1 seed therefore matches a **list of candidate spellings**; a spelling that does not
  exist inserts nothing. Candidates: names seen on the production Map (Walltown Crags, Cawfields, Peel
  Craggs, Hotbank Crags, Cuddy's Crags, Irthing Gorge, Crammel Linn Waterfall), plus names used in a real
  18 October neighbour-guard case (`NeighbourOutlierGuardTest`: Caw Gap, Steel Rigg, Walltown, Housesteads,
  Sycamore Gap, Hotbank), plus the variants Peel Crags and Crammel Linn. **Not** Allen Banks, Lamley
  Viaduct or Letah Wood. The owner checks the result with §5 and corrects it in A3.
  **Found while building A1:** V86's comments name production's Wall locations by id (41 Walltown Crags,
  42 Cawfields, 43 Caw Gap, 44 Steel Rigg & Winshield Crags, 45 Peel Craggs, 46 Sycamore Gap, 47
  Highshield Crags, 48 Hotbank Crags, 49 Cuddy's Crags, 50 Houseteads Fort). `application-prod.yml`'s
  location roster has the same exact names, including the misspelt **"Houseteads Fort"**. The seed list
  therefore also carries "Steel Rigg & Winshield Crags", "Highshield Crags", "Houseteads Fort" and
  "Housesteads Fort" (Codex P1). On a fresh database, V169's "Irthing Gorge" (a bluebell wood) joins too.
- **Q2 — area aliases.** "Hadrian Wall", "Roman Wall". **Not** "the Wall" or "Wall": an area alias must be
  at least two words.
- **Q3 — other areas.** None seeded. The owner adds "Kielder", "Northumberland coast" and the like in A3.
- **Q4 — location aliases.** Hartlepool Headland → "The Heugh", "Heugh Lighthouse", "Heugh Battery". A
  one-word location alias is allowed (it is matched as a whole word, exactly as location names already
  are).

## §3 What must not change

- `AskSnapshot.isPickEligible`, `candidates`, `isHeldBack`, `resolvePick`, the validator's pick rules, the
  BEST anchor, Ready answers, and the Plan and Map picks.
- `look_up_place` for a name that matches no area and no alias: same matching, status order, row caps and
  notes. Existing `AskToolsLookUpPlaceTest` cases stay green **unedited**.
- `AskEvidence.pairs` gains nothing from `look_up_place`, whether for a place, an alias or an area.
- Ready's tool list and prompt bytes (`AskReadyClaudeEngineTest` pins them).
- `LocationEntity`'s fields, and `PUT /api/locations/{id}`'s behaviour.
- No change to the Map or Plan UI. A3 touches only the admin location management view.
- Never change assertions under `src/test/java/.../regression/`.

## §4 Phases

### A1 — the data (backend, migration)

**4.1 Migration** `V189__named_areas.sql` (V188 is the latest on main at the time of writing; re-read it
and renumber if taken). Postgres syntax; the file stays UTF-8 (it contains `’`): say so in a comment.

```sql
CREATE TABLE area (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(80) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_area_name_lower ON area (lower(name));

CREATE TABLE area_alias (
    area_id BIGINT      NOT NULL REFERENCES area (id) ON DELETE CASCADE,
    alias   VARCHAR(80) NOT NULL,
    PRIMARY KEY (area_id, alias)
);

-- Locations are never hard-deleted today (V177's note); the cascade only keeps the join honest if one is.
CREATE TABLE area_location (
    area_id     BIGINT NOT NULL REFERENCES area (id) ON DELETE CASCADE,
    location_id BIGINT NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    PRIMARY KEY (area_id, location_id)
);
CREATE INDEX idx_area_location_location ON area_location (location_id);

CREATE TABLE location_alias (
    location_id BIGINT      NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    alias       VARCHAR(80) NOT NULL,
    PRIMARY KEY (location_id, alias)
);
```

Seed (Q1, Q2, Q4). Insert the area "Hadrian's Wall" and its two aliases. Then membership by **name**
(ids differ between environments), with a comment saying a missing spelling inserts nothing by design:

```sql
INSERT INTO area_location (area_id, location_id)
SELECT a.id, l.id
FROM area a
JOIN locations l
  ON lower(replace(l.name, '’', '''')) IN (
       'walltown crags', 'walltown', 'cawfields', 'caw gap', 'steel rigg', 'peel craggs', 'peel crags',
       'hotbank crags', 'hotbank', 'cuddy''s crags', 'housesteads', 'sycamore gap', 'irthing gorge',
       'crammel linn', 'crammel linn waterfall', 'steel rigg & winshield crags', 'highshield crags',
       'houseteads fort', 'housesteads fort')
WHERE a.name = 'Hadrian''s Wall';

INSERT INTO location_alias (location_id, alias)
SELECT l.id, v.alias
FROM locations l
CROSS JOIN (VALUES ('The Heugh'), ('Heugh Lighthouse'), ('Heugh Battery')) AS v (alias)
WHERE lower(l.name) = 'hartlepool headland';
```

**4.2 Entities and repositories.** Production and CI's integration context run `ddl-auto: validate`
(`application-prod.yml`, `IntegrationTestBase`), so the mapping must match the migration exactly. Local H2
is `ddl-auto: update`, and the test profile `create-drop`; neither has the SQL default.

- `entity/AreaEntity` (`@Table(name = "area")`): `id` (`GenerationType.IDENTITY`), `name`, and
  `createdAt` as `LocalDateTime`, set in `@PrePersist` (like `LocationEntity.createdAt`; not `Instant`,
  because the column is `TIMESTAMP`, not `TIMESTAMPTZ`). Two `@ElementCollection(fetch = LAZY)`
  **`Set`s**: `aliases` (`@CollectionTable(name = "area_alias", joinColumns = @JoinColumn(name =
  "area_id"))`, `@Column(name = "alias")`) and `locationIds` (`area_location`, `location_id`,
  `Set<Long>`). No `@ManyToMany` to `LocationEntity`; nothing is added to `LocationEntity` (D6).
- `entity/LocationAliasEntity` (`@Table(name = "location_alias")`) with an `@IdClass`, copying
  `UserDriveTimeEntity` / `UserDriveTimeId`: `Long locationId` and `String alias`, no `@GeneratedValue`,
  and an id class with `equals` / `hashCode`. No `@ManyToOne`.
- `AreaRepository extends JpaRepository<AreaEntity, Long>`, and `LocationAliasRepository` with
  `findAll()` and a `@Modifying deleteByLocationId(Long)` (used by A3).
- **Reading.** Every read that maps entities to anything else does it inside `@Transactional(readOnly =
  true)`, so the lazy sets never load outside a session. Controllers never return an entity. The
  `aliases × locationIds` product is tiny, so a simple read is fine; no statement-count test.

**4.3 Tests**

- Testcontainers, following `ValleyMistStarMigrationTest`: `Flyway.configure()…target("188").migrate()`,
  plant `locations` rows with JDBC (`INSERT INTO locations (name, lat, lon)`) named "Walltown Crags",
  "Cuddy’s Crags" (curly apostrophe) and "Allen Banks", then `target("189").migrate()`. Assert the first two
  joined the Wall and Allen Banks did not; the area has two aliases; the case-insensitive unique index
  refuses "hadrian's wall"; and the V138 "Hartlepool Headland" row (already present after migration, no
  planting needed) has the three aliases.
- The CI context's boot under `validate` is the proof that both entities map correctly. Name that in the PR.
- `@DataJpaTest` on H2 (the `LocationRepositoryTest` pattern): saving an area with aliases and members and
  reading it back inside a transaction; saving and reading location aliases; `deleteByLocationId`.
- No Docker locally, so the migration test runs only in CI's `Backend — Build, Test & Coverage` job.

**4.4 Docs and changelog.** `docs/engineering/reference/database-migrations.md`: one line for V189.
`changelog.d/20261023-named-areas-data.md`, `### Added — named areas and location aliases (data)`.

### A2 — Ask (backend)

**4.5 The snapshot carries areas and aliases**

- `AskSnapshot.Area(String name, List<String> aliases, Set<Long> locationIds)` with immutable copies (null
  reads as empty). Two new record components, **last**, after `nights`: `List<Area> areas` and
  `Map<Long, List<String>> locationAliases` (location id → aliases; immutable; null reads as empty).
  The current 8-argument canonical constructor becomes an **explicit delegating constructor** passing
  empty values, and the 7- and 6-argument ones keep delegating, so the 19 `new AskSnapshot(` call sites
  compile unchanged.
- A seam `AskAreas` (`@FunctionalInterface`, `Names load()`, where `record Names(List<AskSnapshot.Area>
  areas, Map<Long, List<String>> locationAliases)` and `Names.NONE` is empty). A `@Component`
  implementation over both repositories maps entities inside `@Transactional(readOnly = true)`.
  `AskSnapshotBuilder.build()` calls it once per build. Any exception is logged at WARN and read as
  `Names.NONE`, because Ask must still answer. `current()`'s memo bounds how often it runs.
- `KeywordAskIntentMatcher.placeNames` adds every area name, area alias and location alias (§1, defence in
  depth). Tests:
  - a question made only of Ready-accepted words that names an area declines (build a fixture area named
    with accepted words, e.g. the words of a real Ready question's place-free vocabulary);
  - `couldMatchAny` is false for "best spot on hadrians wall this weekend" (pins §1's reasoning);
  - "The Heugh" survives `AskQuestionSanitiser.normalise` on both sides.

**4.6 Matching**

All matching uses `AskTools.placeWords` (case, apostrophes, punctuation). The matchers are pure, static,
package-private and unit-tested.

- **Place, with aliases (D9).** A slot matches when `containsRun(placeWords(name), wanted)`, or the same
  holds for any alias of its location. It is `exact` when `wanted` equals the name's words or any alias's
  words. `matchedAlias` is set only when the name did not match: the alias whose words equal `wanted` if
  there is one, otherwise the matching alias with the fewest characters, ties broken case-insensitively
  alphabetically. Aliases come from unordered `Set`s and an unordered `findAll()`, so never rely on load
  order. `AskAreas` also sorts each alias list by that same rule, so the snapshot itself is deterministic.
  So "heugh" reports "The Heugh" (9 characters) ahead of "Heugh Battery" and "Heugh Lighthouse".
- **Area.** An area matches when the query's words contain the area's name words, or one alias's words,
  as a contiguous run: `containsRun(wanted, areaWords)`, the reverse direction from places, so "along
  hadrians wall" and "Steel Rigg on Hadrian's Wall" both contain it. When several areas match, the longest
  matched run wins, then name order. "Wall" alone matches nothing, since no alias is one word.

**4.7 `look_up_place`**

Run both matches over the in-scope windows, then (D10):

1. any **exact** place match → the place result, exactly as today (plus `matchedAlias`);
2. otherwise, an area match → the area result. If there were partial place matches too, add to `note`:
   "Also matched places: A, B; ask about one by name." (at most three names);
3. otherwise → the place result as today (partial matches, or nothing).

```java
@JsonInclude(NON_NULL)
public record LookUpPlaceResult(List<PlaceInfo> places, Map<String, String> reasons, String note,
        AreaInfo area) { }   // keep the existing constructors; area null unless an area won
public record PlaceInfo(..., String matchedAlias) { }   // nullable, last; keep the old constructor
public record AreaInfo(String name, int places, List<AreaWindow> windows) { }
public record AreaWindow(String windowId, String day, String event, int rated, int fourStarOrBetter,
        List<AreaPlace> places) { }
public record AreaPlace(long locationId, String name, Integer rating, String status) { }
```

- `AreaInfo.places` is the number of members in scope.
- **Windows.** Use the requested windows (default every window), at most `MAX_AREA_WINDOWS = 4`. A cut adds
  "Showing 4 of N windows; pass windowIds to narrow." to `note`.
- **Per window.** List the in-scope members that have a slot in it, sorted by rating descending (null
  last), then by name. Show at most `MAX_AREA_PLACES = 6`. If any window was cut, add **one** note:
  "Some windows list only their best 6 places; ask about a place by name."
- **Counts.** `rated` counts in-scope members with an in-range rating, excluding canopy slots.
  `fourStarOrBetter` counts those rated at or above `AskSnapshot.Spread.GAME_ON_FROM`. Both count **all**
  members, not only the six shown. These are deliberately **not** Spread's game-on figure: Spread also
  leaves out outliers and verdict-ineligible regions. Here every listed star is the served one, and the
  count must agree with the list.
- **Status and reasons.** `status` comes from `classify(region, slot)`, unchanged, and `reasons` gets each
  present status's text, as for places.
- **Scope and empty results.** If every member with a slot is out of scope: no windows, note "That named
  area is outside the regions this question is about." If no member has a slot in the windows: note "None
  of the named area's places is in these windows."
- **Every area result's `note` ends** with "To suggest places here, call rank_spots with areaName."
- **Budget.** The worst case must serialise to ≤ 4,500 characters, pinned by a test in the
  `AskToolsLookUpPlaceTest` style: 4 windows × 6 places, 60-character names, every status present, every
  note. The reviewer's estimate is about 3,800. If it does not fit, lower `MAX_AREA_PLACES` and report the
  figure. Re-measure the place pin with a 40-character `matchedAlias` on every row; it too must stay
  ≤ 4,500 (about 4,135 is estimated).
- **Evidence.** Nothing is recorded (D3). Test it for the area result and for an alias match.

**4.8 `rank_spots` with `areaName`**

- `RankSpotsArgs` gains a last component, `String areaName`. Keep both its 7- and 6-argument
  constructors.
- `AskToolSchemas.rankSpots` adds the property **only when `withPlaceLookup`** (D5), and `tools(...)`
  threads that flag through. The property is: `"areaName": {"type": "string", "description": "Only places
  in this named area (e.g. a landmark such as Hadrian's Wall), by its name or another name for it."}`.
- **Gate in `AskTools`.** `AskTools` has no place-lookup flag. The codebase's Ready test is
  `!user.hasUser()` (`ClaudeAskEngine`: `ready = !user.hasUser()`). So an `areaName` with no user →
  `fail("rank_spots", "areaName is not available in this conversation.")`.
- **Resolving the name.** Resolve `areaName` through the §4.6 area matcher (name or alias), so "Roman Wall"
  works. If nothing matches → `fail("rank_spots", "Unknown named area '<name capped at 40>'. Known: A, B.")`,
  with the joined list capped at 200 characters.
- **Filtering.** Filter the pool by `area.locationIds().contains(slot.locationId())` after the region
  filter. `snapshot.candidates(w, scope)` already applies scope and pick eligibility, so nothing is
  bypassed. Extend `heldBackNote` with the area's ids (null means no area filter), so it counts only
  held-back members. Evidence recording is unchanged.
- **Arguments.** `AskToolArguments.rankSpots` reads `areaName` (a string, or `BadArguments`).

**4.9 Prompt (typed only; `RULES` stays byte-identical)**

In `AskPromptBuilder.PLACE_FIT_AFTER`:

- Change "If look_up_place returns no places, read its note and answer answerable false" to "If
  look_up_place returns no places and no named area, read its note and answer answerable false".
  Without this, an area result (empty `places`) would be told to answer "not in the forecast": the very
  defect.
- Append: "The name may be a named area, a group of places such as a landmark ("Hadrian's Wall"):
  look_up_place then returns how its places rate in each window; answer from those stars, never as picks.
  To suggest places in a named area, call rank_spots with areaName. A place may also be found by another
  name ("the Heugh"); call it by its listed name, adding the name the reader used."

Change nothing else. Add to the `look_up_place` schema description: "The name may also be a named area,
such as a landmark, or another name for a place; the result then lists the named area's places for each
window." This schema is typed-only, so Ready is unaffected.

**4.10 Local fixture.** `AskLocalFixtureSeeder` (or a sibling in `src/local/`) adds a named area over two
of its fixture locations and one location alias, idempotently. Say in the PR that exercising it in the
browser needs `photocast.ask.stub: false`, which bills the real Anthropic key. `StubAskEngine` never calls
`look_up_place`, so the planner decides whether to do that browser check, not the builder.

**4.11 Tests** (read `docs/engineering/test-improvement-standards.md` first)

- **Matchers:**
  - "heugh" finds Hartlepool Headland with `matchedAlias` "The Heugh";
  - "heugh lighthouse" is exact, and its `matchedAlias` is "Heugh Lighthouse";
  - "hartlepool" finds it with no `matchedAlias`;
  - a place with no aliases behaves exactly as before;
  - the area matches by name and by alias, ignoring apostrophes and case;
  - a run inside a longer query matches the area;
  - "wall" alone matches no area;
  - when two areas match, the longest run wins.
- **`look_up_place`:**
  - D10's three branches, including a fixture place "Roman Wall Viewpoint": the query "Roman Wall
    Viewpoint" returns the place, and "Roman Wall" returns the area with the "Also matched places" note;
  - scope: a cross-region area in a single-region question shows only that region's members; all members
    out of scope gives the note and no stars;
  - the window cap and the per-window cap, each with its note, and the per-window note said once;
  - `rated` and `fourStarOrBetter` over all members, canopy excluded;
  - statuses and reasons;
  - evidence is empty after an area lookup and after an alias lookup;
  - both budget pins;
  - the busy-day `list_windows` followed by an unfiltered area lookup is not cut off (the
    `lookUpPlace_afterABusyDayListWindowsIsNotCutOff` pattern).
- **`rank_spots`:**
  - `areaName` filters, by name and by alias;
  - an unknown area is an error;
  - a conversation with no user is an error;
  - the held-back note counts only members;
  - a pick from an area-filtered result passes `AskAnswerValidator`.
- **`AskToolSchemasTest`:** the typed `rank_spots` has `areaName`; Ready's does not, and the Ready list is
  unchanged.
- **`AskPromptBuilderTest`:** the typed prompt has the amended sentence and the new one; Ready's prompt has
  neither.
- **`KeywordAskIntentMatcher`:** as in §4.5.
- **`AskSnapshotBuilderTest`:** `load()` is called once per build; a throwing `AskAreas` gives empty areas
  and aliases, and the snapshot is still built.
- **Mutation checks:** D10's order (area before an exact place), the evidence rule, the Ready schema gate,
  and the §4.9 "no named area" clause (a test must show a typed prompt without it). Break each, see a test
  fail, then restore with `cp`; never `git checkout --`.

**4.12 Regression class.** Create a **new** `AskNamedAreaRegressionTest` beside
`AskPlaceLookupRegressionTest`, tagged and registered the same way (the pom's PIT exclusion list), with
four cases:

1. "Does Hadrian's Wall look good this weekend?": answerable true; the summary names at least one member;
   not "not in the forecast".
2. "Where on Hadrian's Wall should I go on Saturday morning?": picks, every one a member.
3. "How's the Heugh looking tomorrow?": answerable true; mentions Hartlepool Headland.
4. "Does the Great Wall of China look good tomorrow?": answerable false.

Its assertions are **pending owner approval**. Do not run it.

**4.13 Docs and changelog.**
- `docs/engineering/reference/ask-photocast.md`: named areas and location aliases in the tool list; D3,
  D4, D5 and D10; the typed-cache note (§6).
- `changelog.d/20261023-ask-named-areas.md`, `### Added — Ask understands named areas such as Hadrian's
  Wall and other names such as the Heugh`.

### A3 — admin editing (backend + frontend, UI)

**4.14 Endpoints.** All are `@PreAuthorize("hasRole('ADMIN')")`, the `LocationController` pattern. There
is no business logic in controllers; a service owns every rule and write.

- `GET /api/areas` → `[{id, name, aliases[], locationIds[]}]` (DTOs mapped inside a read-only transaction).
- `POST /api/areas` `{name, aliases[]}` → 201.
- `PUT /api/areas/{id}` `{name, aliases[]}` → 200, or 404. **Load by id, change the name and aliases, and
  save in one transaction.** Never build a fresh entity from the request: its empty `locationIds` would
  delete every member. The membership endpoint is the only writer of `locationIds`. A test proves a rename
  keeps the members.
- `PUT /api/areas/{id}/locations` `{locationIds[]}` → replaces membership; 400 on an unknown location id.
- `DELETE /api/areas/{id}` → 204.
- `GET /api/locations/aliases` → `{locationId: [alias…]}`.
- `PUT /api/locations/{id}/aliases` `{aliases[]}` → replaces that location's aliases
  (`deleteByLocationId` then `saveAll`, in one `@Transactional` service method, after checking that the
  location exists: 404 otherwise). This is never routed through `PUT /api/locations/{id}` (D6). No route
  clashes with `LocationController`'s existing mappings.

**Validation** (409 with a message naming the clash; 400 for shape). Compare everything in its
`placeWords` form, so "Hadrians Wall" equals "Hadrian's Wall":

- An area name is 2–80 characters. An area alias is 2–80 characters and at least two words. A location
  alias is 2–80 characters.
- **One namespace.** Location names, location aliases, area names and area aliases are four sets. A new
  area name or area alias may not equal anything in them. A new location alias may not equal another
  location's name or alias, or any area name or alias.
- **No shadowing** (D10 handles exact place names; this keeps partial lookups predictable):
  - an area name or alias may not be a contiguous run inside any location name or location alias;
  - a location alias may not contain an area name or alias as a run.
  - Report the clashing name.
- **Location names are in the namespace too (Codex P1).** `LocationService.add` and the rename branch of
  `LocationService.update` today check only `existsByName`. Put the rules above in one shared component
  (e.g. `NameNamespaceGuard`, a service over the area, alias and location repositories), called by the
  area service, the location-alias service **and** both `LocationService` paths. For a location's name:
  - it may not equal any location alias, area name or area alias;
  - it may not contain an area name or area alias as a run.
  - Otherwise an admin could rename a place "Hadrian's Wall" and, by D10, hide the named area.
  - Keep each path's existing exception type and status code. Add tests to `LocationServiceTest` for a
    create and a rename that clash, and for a rename to a name with no clash.
  - Before building, check that no current location name breaks the rule: grep the migrations and
    `application-prod.yml`'s roster for "hadrian" and "roman wall". Report what you find.

Then:
- Fix the existing drift in `docs/engineering/reference/api-endpoints.md` (it documents `PUT
  /api/locations/{name}/reset-failures`; the code maps `/reset-failures`) and add the new endpoints.
- The role tests are the new controllers' own (403 for PRO_USER and LITE_USER); no test enumerates
  handler mappings.
- `HttpCachingConfig` is an explicit allowlist, so nothing is needed there; say so in the PR.

**4.15 UI.** Add an "Areas" section to `LocationManagementView` (Operations tab, admin only):

- a list of areas, each with its name, aliases and member count;
- create, rename, edit aliases, and delete (with a confirm);
- a member picker: the existing location list with checkboxes, filterable by name.

The location edit form gets an "Also known as" field: comma-separated aliases, saved by `PUT
/api/locations/{id}/aliases` **after** the location `PUT` succeeds. If only the aliases request fails, the
form says exactly that ("Location saved; its other names were not") and keeps the typed aliases.

Tailwind only, `data-testid`s, PropTypes. The section reads and writes only the A3 endpoints. The phone
keeps the Operations tab's existing behaviour; no phone-only work.

**4.16 Tests.**
- Controller tests: each status code, plus 403 for PRO_USER and LITE_USER.
- Service tests: every validation rule, including the normalised comparison and both shadowing rules, and
  that a rename keeps members.
- Vitest, following `docs/engineering/frontend-test-standards.md`:
  - the section's list, create, edit members and delete confirm, and its error states;
  - the alias field's two-request order and its partial-failure line.
- The UI review cadence applies: six lenses plus refuters, and a browser check on 8083 where the planner
  can sign in; otherwise say what was only tested.

**4.17 Docs and changelog.** `changelog.d/20261023-named-areas-admin.md`.

## §5 Owner checks

After A1 deploys (read-only; paste on the prod host):

```bash
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 <<'SQL'
SELECT a.name, l.name AS member, r.name AS region FROM area a
JOIN area_location al ON al.area_id = a.id JOIN locations l ON l.id = al.location_id
LEFT JOIN regions r ON r.id = l.region_id ORDER BY 1, 2;
SELECT name, enabled, round(lat::numeric, 3) AS lat, round(lon::numeric, 3) AS lon FROM locations
WHERE lat BETWEEN 54.85 AND 55.10 AND lon BETWEEN -3.25 AND -1.45 ORDER BY lon;
SELECT l.name, la.alias FROM location_alias la JOIN locations l ON l.id = la.location_id ORDER BY 1, 2;
SQL
```

The first query lists the Wall's members and their regions. The second lists every place, enabled or not,
in a box from Bowness to the Tyne, to add or drop in A3. The third should show the Heugh's three aliases.
After A2:
- Ask the screenshot question again, on the Map, set to "Everywhere". Expect the named area's places and
  stars, not "Not in the forecast".
- Ask "How's the Heugh looking?". Expect Hartlepool Headland.
- After approving the assertions, run `./mvnw test -Pprompt-regression -Dtest=AskNamedAreaRegressionTest`.

## §6 Risks

- **Budget.** An area lookup can be large. §4.7's caps and the two pinned worst cases hold it under half
  the conversation cap, the rule the place lookup already follows.
- **Seed names.** A wrong spelling inserts nothing, silently. §5 is the check, and A3 is the fix.
- **Name drift.** Membership and aliases are by id, so a rename keeps them; only the seed matches by name.
- **Caching.** An area-filtered `rank_spots` answer carries picks, so the typed cache may store it. Its key
  is scope, date, `generatedAt` and question, so an A3 edit shows from the next briefing build. Accepted;
  noted in the reference doc.

## §7 As built

(Filled in per phase.)

## §8 Subagent briefs

**Common to every phase:**

- Read this plan in full, then grep (do not read whole) `docs/engineering/reference/ask-photocast.md`, and
  read `docs/engineering/test-improvement-standards.md`.
- Branch from `main`. Never `git checkout --`, `git restore` or `git stash`. Commit as you go. **Do not
  push.**
- Never edit assertions under `src/test/java/.../regression/`.
- There is no JDK 25 and no Docker on this Mac. Run the backend gate as

  `cd backend && export JAVA_HOME=/Users/chrisgregory/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home MAVEN_OPTS=--enable-preview && ./mvnw clean verify --batch-mode --no-transfer-progress -Djava.version=23 -Dmaven.compiler.enablePreview=true -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false > "$SCRATCH/v.log" 2>&1; echo "exit: $?"`

  and `./mvnw checkstyle:check`, gating on exit codes.
- Do not start the backend, trigger a forecast run or make a real Anthropic call.
- If the code contradicts the plan, stop and report; don't guess.
- Report the commits, gate exit codes and test counts, the mutation checks, and every deviation, for §7.

**The phases:**

- **A1** (`feature/named-areas-data`): §4.1–§4.4.
- **A2** (`feature/ask-named-areas`, from main after A1 has merged): §4.5–§4.13.
- **A3** (`feature/named-areas-admin`, from main after A2 has merged): §4.14–§4.17, plus the frontend's
  four commands (`npm run lint && npm test && npm audit --audit-level=high && npm run build`).

## §9 Review record (2026-10-23)

Two adversarial lenses read the draft against the code.

**Blocker fixed.** The typed prompt's "no places → answerable false" sentence would have turned every
area result into "not in the forecast" (§4.9).

**Should-fixes taken:**
- **§1:**
  - the Ready-decline "defect" is not live; it is now defence in depth;
  - Irthing Gorge's region (Northumberland & Tyneside) does not make the Wall cross regions by itself.
- **D10:** an exact place match beats an area, so a place is never hidden.
- **A2 counts:** `fourStarOrBetter` over all members, distinct from Spread's game-on figure.
- **Budget:** 6 places per window; the cut note is said once.
- **Gates and constructors:**
  - the `areaName` gate is `!user.hasUser()`;
  - `areaName` resolves through the matcher;
  - the explicit delegating constructors for `AskSnapshot` and `RankSpotsArgs`.
- **A1 data:**
  - `validate` in production and CI: `LocalDateTime` with `@PrePersist`; the `@IdClass` precedent; reads
    inside read-only transactions;
  - the migration-test pattern (`target("188")`, plant, `target("189")`);
  - the seed's candidate spellings, since the Wall's production names are not in any migration;
  - a case-insensitive unique index on the area name.
- **A3:**
  - the rename-wipes-membership trap;
  - one normalised namespace, plus the shadowing rules;
  - the alias field's request order and partial-failure line.
- **§5:** a wider box, disabled places included, and the region shown.

**Nits taken:**
- "named area" wording (D11);
- the stub cannot exercise the path (§4.10);
- the `api-endpoints.md` drift;
- the UTF-8 note;
- the cascade rationale.
