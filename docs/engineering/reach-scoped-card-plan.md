# Reach-scoped card plan — the headline and the best place answer "within my drive"

Status: **planned 2026-11-05; revision 4.** Two review rounds (seven read-only lenses) and the owner's
answers (§4) are folded in. Order: **R1 → R1b → R1c → R2 → R3**, one PR each, each from an updated
`main` after the previous phase merged.

Owner request (paraphrased): when I change the drive filter, the spread follows the places I can reach,
but the best place and the rating come from everywhere. Both should come from within my chosen drive.

## 1. What is true today

The window card (`WindowFirstHeatStrip.jsx`, descriptors from `utils/windowFirstCards.js`
`buildWindowCards`) carries two populations:

| Row | Population | Source |
|---|---|---|
| Headline word and mean (`Poor 1.7★`), BEST BET pick, haze, `blanked`, thin count | the **top-ranked region of the whole roster** (`BriefingWindow.verdict`/`pick`), or the away origin's own region | served: `BriefingRegionEvaluationRollup.enrich` → `PlanWindowProjector` |
| Spread histogram | the **pool**: origin-scoped, canopy-filtered, reach-gated, pre-floor | client, licensed (plan-matrix A10) |
| Best row (`4★ Helvellyn`) | the same pool, `bestReachOf(pool)` | client, licensed (plan-matrix A11) |

**Defect 1: the headline ignores the tier**, by design (module doc of `WindowFirstHeatStrip.jsx`).

**Defect 2: an unroutable place counts as reachable from everywhere.** `gateSpotsByReach`
(`utils/reachLens.js:217`) passes any spot whose `driveMinutes` is null (`window-first-redesign-plan.md`
§2.5 rule 1, "absence means unknown, never out of reach"). The rule was written for a reader with no
drive times, but it also fires for a reader whose drive times are measured except for a few places. The
main cause of a single null is OpenRouteService returning no route (`DriveDurationService:96-102`), and
an omission is never retried (`DriveTimeRefreshJob.needsRefresh` javadoc). A summit such as Helvellyn
(pin 54.577, -2.931) is the obvious case, and as a high-rated mountain it wins the Best row. A benign
second cause is a location added after the reader's last measurement, which the nightly job picks up
(`rosterGrewSince`, `DriveTimeRefreshJob:393, :443`).

**Also found:** moving a location's coordinates invalidates no drive time (`LocationService.update`,
`:236-`); only the admin "Run now" re-measures.

## 2. Decisions

- **D1. Three kinds of drive, not two.** Each (origin, location) drive is `MEASURED` (a row exists),
  `PENDING` (no row, and the origin's last full measurement is null or earlier than the location's
  `drive_target_changed_at`), or `UNROUTABLE` (no row, and the origin was fully measured after the
  location's drive target last changed). A finite tier keeps `MEASURED <= tier` and `PENDING`, and drops
  `UNROUTABLE`. Under **Any** nothing changes. A reader with no `MEASURED` entry at all (no postcode,
  Lite, a cleared home) is unchanged: everything passes and no surface says "within reach". §2.5 rule 1
  is amended, not repealed: absence still means unknown, but a confirmed omission no longer does.
- **D1a. `locations.drive_target_changed_at`.** A new column (R1), initialised to `created_at`, bumped
  whenever the point ORS routes to changes: a coordinate move (from R1) or a drive-point change (R1c).
  The same bump deletes that location's `user_drive_time` and `region_drive_time` rows, so the drive
  reads PENDING until re-measured. `LocationRepository.findMaxCreatedAt` (`:170`) becomes the max of this
  column, so `rosterGrewSince` re-measures every home that night with no other change. The region
  matrix needs no trigger: `RegionDriveTimeRefreshJob.runScheduled` re-measures every base nightly.
  A **re-enable** (disabled to enabled, in `LocationService.setEnabled` and in `resetFailures` for an auto-disabled
  place) also bumps it, but deletes nothing: the nightly roster reads only enabled places, so a place switched off
  during a sweep has no rows from it and would otherwise read UNROUTABLE from every home. The point did not move,
  so the kept rows stay valid, the missing pairs read PENDING, and `rosterGrewSince` re-measures them.
- **D1b. A stamp means "fully measured, from this point, as of this instant".** It is taken **before**
  the roster read (a location created during the ORS call must not read UNROUTABLE). It is written only
  when ORS returned a duration slot for every destination (a short answer stores its rows without
  stamping, so the rest stay PENDING). For a region base it is written only if the base is still the
  one that was routed (conditional scoped update on `base_lat/base_lon`). The same three rules apply to
  the home stamp `app_user.drive_times_calculated_at` if `DriveDurationService` has the same
  read-then-call order. Match `ROSTER_VISIBILITY_MARGIN` (`DriveTimeRefreshJob.java:445`) wherever a
  stamp is compared to `drive_target_changed_at`, and say so in a comment.
- **D2. "Measured" is a fact about the reader and origin, computed once.** `reachMeasured` moves from a
  per-card `allSpots.some(...)` to one value in `WindowFirstBriefingContext` for the active origin,
  passed as a lens input to every gate. No surface re-derives it from a subset.
- **D3. Away plans are in scope (owner).** The away origin's matrix (`/api/regions/drive-times`,
  `Map<Long, Map<Long, Integer>>`, `RegionController.java:77-80`) keeps its shape. `/api/regions` serves
  a per-region `drivesMeasuredAt`, and `/api/locations` serves `driveTargetChangedAt`. The client derives
  away status from those two and the matrix, in `planOrigin.js`. **An away card stays its own region's
  card**: under a tier, R2 prunes only the origin region to the places within the tier from its base;
  it never re-ranks other regions, so a tier tap cannot swap the region the card is about.
- **D4. The home headline is recomputed server-side by the same engine, region by region.** For each
  window the server prunes every region's slots to the in-reach locations (D1's rule), re-runs the
  rollup's per-region arithmetic and `PlanWindowProjector`'s ranking and pick on the pruned tree, and
  serves the top region's verdict, mean, confidence, `blanked`, thin count and pick, plus every pruned
  region's `{regionName, displayVerdict, meanRating}` (R3 needs them). The meaning is the served
  headline's meaning on a smaller map. Nothing is pooled across regions, no new thresholds are added,
  and the client derives no verdict. **Parity:** at a tier that reaches every location, home equals the
  served window, and away equals the served origin region.
- **D5. One source for the pruned ratings.** The computation starts from the memoised served briefing
  (`getCachedBriefingForApi()`), whose slots already carry the enriched ratings. It never re-reads
  `getScoresForEnrichmentBulk` / `loadServeTriage`.
- **D6. The scope is always said.** Every surface that prints a tier-scoped word also prints the scope,
  visibly and in speech, spelled as printed.
- **D7. Pro and Admin only.** Lite's tier is locked to Any (`useReachLens.js:88`), so D1 is inert for it.
  The endpoint returns 403 to Lite and is never called for a shaped body (`picksScoped`).
- **D8. This builds plan-matrix §8 O-4 for the headline only.** A10, A11, A27, tide fit and the
  `· N rated` count stay client-side, with O-4 still their exit. The owner's request answers
  `map-landing-plan.md` §6 Q4 for the headline and pill only.
- **D9. The Map pill follows the drive tier (owner), in its own phase R3.** The Map has its own drive
  filter, its own My area / Everywhere scope, and a region line under the pill that needs every region's
  verdict, so it is too large to share R2's PR.
- **D10. The card shows only the in-reach headline (owner).** It gets no region-wide line; the popup's
  region rows carry each region's own verdict.
- **D11. Unroutable places get a named drive point (owner), enforced (owner).** For example, Helvellyn
  drives to **Glenridding**. Phase R1c.

## 3. Phases

Each phase is built by Sonnet in its own worktree. Each has its own `changelog.d/` entry, needs the
backend build ladder and the frontend's four commands green, and needs the 95/90 per-class gate and the
90% changed-lines gates green on both sides. Migrations are proven only in CI (no Docker locally): read
the latest number from the tree, and write a Testcontainers test. Backend stubs pin every argument (no
`any()` except in `never()` and lambdas). Mutation proofs run in an isolated worktree.

### R1: an unroutable place fails a tier (home and away)

Backend:
1. Migration: `locations.drive_target_changed_at TIMESTAMP NOT NULL`, backfilled from `created_at`, and
   `regions.drives_measured_at TIMESTAMP NULL`. Both entity fields are `updatable = false,
   insertable = false` (`insertable = true` for the location's on create), each written only by a scoped
   `@Modifying` update (the column-scoped write rule). `database-migrations.md`.
2. D1a: `LocationService.update` on a coordinate change bumps `drive_target_changed_at` and deletes the
   location's `user_drive_time` and `region_drive_time` rows, in the same transaction.
   `findMaxCreatedAt` → max of `drive_target_changed_at` (rename it; update the `rosterGrewSince`
   javadoc).
3. D1b: stamp-before-read, full answer only, conditional on base unchanged, in
   `RegionDriveDurationService` (`:76`, `:89`, `:97`) / `RegionDriveTimeWriter`, and in
   `DriveDurationService` if it has the same shape. `RegionService.setBase` / `clearForRegion` null the
   stamp (already correct for the clear).
4. `ReachService` (`:84-100`) adds `driveStatus` per enabled location for the home reach. The path is
   already personal and never cached.
5. `/api/regions` serves `drivesMeasuredAt`; `/api/locations` serves `driveTargetChangedAt` (check both
   payloads' contract tests and ETags: they are shallow body hashes, so a new field simply changes them).
   `RegionDriveMatrixMemo` is unaffected because the matrix shape is unchanged.

Frontend:
6. The home `driveStatus` rides `reachById`. `planOrigin.js` `originReachMap` derives away status from the
   matrix, `drivesMeasuredAt` and `driveTargetChangedAt` (one pure function, tested, with the same margin
   as the server). `buildWindowSpots` copies status onto each spot (`windowFirstSpots.js:253`). The
   context computes `reachMeasured` once for the active origin (D2). The SWR cache of the matrix
   (`WindowFirstBriefingContext.jsx:400-404`) is unchanged because the matrix shape is unchanged.
7. `gateSpotsByReach(spots, limitMinutes, { measured })`: with a finite tier and `measured`, it drops
   `UNROUTABLE` and keeps `PENDING` and `MEASURED <= limit`. Otherwise it behaves as today.
8. Thread the one `measured` through every gate: `windowFirstCards.js:800` (pool → spread, tide fit,
   Best), `windowFirstRegions.js:110-118`, `windowSpotBrowse.js:138, :222` (add the parameter),
   `planConflicts.js:87`. Decide and comment each of these:
   - `isFarSpot`
   - `windowFirstSpread.js:299` `poolWithinReach` and the A28 `spreadQualifier` silence (PENDING still
     makes a pool not fully measured)
   - `planSearch.js:278, :385`
   - `WindowSheetDialog.jsx` `withinReach`
   - `MapView.jsx:3479-3484`, which drops every null drive when its filter is set: record that as the
     deliberate Map exception, unchanged until R3
9. **The "beyond" Best row never calls an unroutable place far.** `verdictBeyondReach`
   (`WindowFirstHeatStrip.jsx:232-247`) says the region's rated places are "all further than 45 min".
   The card carries `reachUnroutableRemoved` (rated spots dropped as UNROUTABLE). When it is non-zero
   the line says those places have no route from home (or from the base), and it is suppressed if none
   was far. Test both cases and the mixed case.
10. The spread tooltip says `N places have no route from home` when the gate dropped at least one. The
    count is over `allSpots` minus `outsideCount`, so it matches `reachTotal`. The sheet counts over its
    own tier (`WindowSpotSheet.jsx:176`). The sheet that opens on Any (`WindowFirstShell.jsx:3294`) still
    lists those places with no drive shown, which is consistent, and a comment says so.
11. Docs:
    - amend `window-first-redesign-plan.md` §2.5 rule 1 (and :970) and `plan-matrix-plan.md`
      A10/A11/A28
    - every code restatement: `reachLens.js:33-37, :240`, `windowFirstSpots.js:147`,
      `WindowSheetDialog.jsx:238, :590`, `settingsApi.js` ~118, the `DriveTimeRefreshJob.needsRefresh`
      javadoc, `UserSettingsService.java:282`
    - `plan-tab.md`, `map-tab.md`, `conditions.md`, `platform.md` (a coordinate move now invalidates),
      `architecture-decisions.md`, `api-endpoints.md`

Tests:
- `reachLens` (measured/unmeasured × Any/tier × MEASURED/PENDING/UNROUTABLE/over-limit).
- The card builder: a Helvellyn-shaped UNROUTABLE spot rated above every measured spot is not
  `bestReach` at 45 min, is at Any, and a PENDING one still is.
- The beyond line.
- The rail and the card agree on one location across two windows.
- An all-unmeasured reader is unchanged.
- Away: an unroutable place from the base, a null base stamp (everything PENDING), and a base just
  moved.
- The stamp rules: created during the call, a short answer, the base moved mid-flight.
- A coordinate move invalidates and bumps.
- Mutations: drop `measured` from the gate, and treat UNROUTABLE as PENDING. Each must fail a card test.

### R1b: admin view of unroutable places

- A read-only admin endpoint lists, per location, the homes and bases that read it UNROUTABLE (D1's
  derivation, aggregated): for example "Helvellyn: no route from 3 of 3 homes, 2 of 4 bases".
- It is shown on the Operations tab's drive-time panel with a count badge, and flagged
  "Needs a drive point" on the admin Locations list and on the location page. Admin only; Pro and Lite
  never see it.
- No migration. Docs: `api-endpoints.md` and the Operations section of `platform.md`.

### R1c: a named drive point, enforced where there is no route

A drive point has the same shape as a region base (`base_name/base_lat/base_lon`, V145): a name a reader
recognises, plus the point ORS routes to.

1. Migration: nullable `locations.drive_point_name`, `drive_point_lat`, `drive_point_lon`. The service
   enforces all three or none, like region bases. They are column-scoped (`updatable = false`) and
   written by a scoped update that also applies D1a (bump `drive_target_changed_at`, delete the rows).
2. **Lookup by name.** `PostcodesIoClient` gains a `/places?q=` call with its own `PlaceResult` record
   (`name_1`, `local_type`, `latitude`, `longitude`, `county_unitary`, `region`). An empty result is
   200 with an empty list, not 404; results are unsorted. Sort by `GeoUtils.distanceKm` from the
   location, filter at 25 km (a named, justified constant: it catches a Glenridding in another county),
   and reuse `failureFor`. A postcode goes through the existing lookup. The admin picks a match and may
   nudge the point on a map.
3. **Only ORS uses it.** `DriveDurationService.java:87` and `RegionDriveDurationService.java:96` send
   the drive point when set. `ReachService.java:97` (miles) and `AskTools.java:2058` (home radius) stay
   straight-line from the location itself. Stored-minute readers (`planConflicts`, Ask's
   `driveMinutesByLocation`) follow automatically.
4. **Shown, sparingly.** The reach payloads (home `ReachEntry` and the away derivation) gain `driveTo`
   (the drive point name). It prints only on the spot's drive line in the callout/peek sheet and on the
   sheet's meta line, as `1h 20min to Glenridding`. Miles are dropped on that line when a drive point is
   set, because the miles are measured to the summit. It is not printed on list rows, leave times or
   popup verdicts. The admin location page's PLACE section shows `Drive to: Glenridding`.
5. **Enforced (owner).**
   - **422 on create, on a coordinate move, and on clearing a drive point**, when the target point
     cannot be routed: "No road route reaches this point. Add a drive point, e.g. the nearest village
     or car park." The form then shows the drive-point field as required. Every other edit (sky, mist,
     tide, names) is never refused.
   - **The check:** a one-row ORS matrix call made **before** the transaction. The source is the
     location's region base, falling back to the nearest base. A known-routable control destination
     goes in the same call. An all-null row means the source is bad, which counts as "unavailable".
     Parse `destinations[]` snap metadata (`OrsMatrixResponse`, `OpenRouteServiceClient.java:115`,
     reads only `durations` today), and verify how ORS answers an unsnappable point (null vs a 4xx such
     as 6010) with a recorded fixture.
   - **Never blocked by an outage.** Use a new `OrsRateLimiter.tryWithPermit(timeout)` (today's
     `acquire()` waits forever, `:51`). A timeout, no base, an ORS error, or an unconfigured ORS
     (`fetchDurations` → `List.of()`) means "unavailable", and the save is allowed. Flyway seed inserts
     bypass the service, so seeds and tests are unaffected.
   - **Existing places:** flag and badge only (R1b), never a 422 on an unrelated edit.
   - **Refuse only where a drive point would help (owner intent, R1c review):** a target ORS could not place on a road
     (a null `destinations[]` entry, or a 6010 naming it) is refused; a target ORS snapped but returned no duration for,
     with the control routed (a ferry-only island from a mainland base), is UNAVAILABLE and the save is allowed. The
     server also rejects (400) a drive point more than 25 km from the pin, on create and update.
6. Tests: all-or-none; place lookup (results, empty, 25 km filter, sorting, a failure); the
   routability check (routable, unroutable destination, bad source, timeout, unconfigured, a 4xx
   fixture); a 422 only on the three triggers; invalidation and bump; the nightly job picks it up; only
   the two ORS callers use the point; admin 403 for non-admins.
7. Docs: `platform.md` drive times, `database-migrations.md`, `api-endpoints.md`, `map-tab.md` (the
   `to Glenridding` line).

### R2: the reach-scoped Plan headline

Backend:
1. **Stamp what the rollup needs onto the enriched slot**, so the served briefing alone is enough (D5).
   That means `forced` (today only in the rollup's `cached` map, `BriefingRegionEvaluationRollup:215-220`)
   and `triaged` (disposition ∪ `triageReason`, `:187-194`). Both are `@JsonIgnore`. A contract test
   proves the served `/api/briefing` body is byte-identical before and after.
2. **Extract, don't fork.** Pull the per-region block of `enrich` (`:81-331`) into a method that takes a
   region's enriched slots plus its weather `verdict()`. The block covers:
   - stats over all slots (coverage) and over voting slots
   - the gate
   - the display verdict
   - the mean
   - the confidence

   `enrich` then calls the method. A characterisation test over real shapes must be green **before**
   the extraction, and the existing rollup tests must pass untouched after it.
3. `ReachVerdictService.compute(user, originRegionId?)`, run for each tier in {45, 90, 150}:
   - **Home:** copy the served tree and prune each region's slots to in-reach (D1; a null `locationId`
     is out of reach). Re-run step 2 per region, then `PlanWindowProjector`'s ranking, window verdict
     and pick. A region left empty is dropped before ranking; a window left empty gets the projector's
     existing empty answer; the window-level canopy rule (`PlanWindowProjector:446-449, :519-523`)
     applies unchanged.
   - **Away:** prune only the origin region (D3), from the base matrix, and re-run step 2 for it.
   - Neighbour-outlier flags ride the slots.
4. `GET /api/user/settings/reach-verdicts[?origin={regionId}]`:
   - Pro/Admin `@PreAuthorize`; 403 for Lite; 400 for a region with no base.
   - Body: `{ measured, briefingVersion, tiers: { "45": [windows…], "90": […], "150": […] } }`. Each
     window is `{ date, targetType, displayVerdict, meanRating, confidence, blanked, thinCount, pick,
     pickRegion, regions: [{ regionName, displayVerdict, meanRating }] }`.
   - Not on `REVALIDATABLE_READ_PATHS`, so it gets no-store. Add it to
     `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` and to the drive-time `@PreAuthorize`
     routes list (`api-endpoints.md:485`).
5. **`briefingVersion` on both payloads.** The wire has no epoch today (`ServedBriefingEpoch` is an
   in-memory counter). Add a content hash of the enriched ratings and dispositions, computed once per
   memoised served briefing, to `/api/briefing` (shared, not personal), and echo it here.
6. Cost: no Claude call, and one drive-time query per request. No memo unless one is measured to be
   needed.

Frontend:
7. `settingsApi.getReachVerdicts(originId)` is fetched by `WindowFirstBriefingContext` when all of these
   hold:
   - the reader is Pro/Admin
   - not `driveLocked`
   - not `picksScoped`
   - the context-level `reachMeasured` is true for the active origin

   It is fetched once per (`briefingVersion`, origin). A version mismatch is discarded and refetched
   once. Until a matching answer exists, the card shows today's word **without** scope words, which is
   true as printed. A tier tap never refetches: all three tiers are already held.
8. `buildWindowCards` takes the reach map. When the active tier has an entry for the window, these come
   from it: `verdict`, `verdictLabel`, `verdictMean`, haze `confidence`, `blanked`, thin count and
   `pick`. The card also carries `verdictScope: { tierMinutes }` and `regionVerdictMean`/`regionVerdict`
   (the served values, for H2). The word stays a LOOKUP on the served `displayVerdict`, and no
   comparison operator enters the verdict path.
9. **Scope line.** The verdict row is one line with no wrap and an 83-89px value column
   (`WindowFirstHeatStrip.jsx:1600-1605`), so the scope gets its own short line directly under it. Use
   the compact spelling the Spread row already uses (`briefingDisplay.js:30-44`, `within 1h30`). In the
   hidden sentence (`:1460`) the scope follows the verdict word directly, spelled as printed. A test
   checks that every visible string is a substring of the spoken one (WCAG 2.5.3). Screenshot at 320px
   and on desktop.
10. **Every Plan-side consumer of `card.verdictLabel` is decided**, and the phase log records each:
    - **Scoped, with the scope words:** the matrix card and its hidden sentence,
      `WindowSheetDialog.jsx:546` (header badge) and `:389` (live message), `windowFirstStrip.js:100`,
      `planSearch.js:319-320`, `locationSheet.js:896` `stateLabel`.
    - **Regional by definition, unchanged:** `WindowRegionRail` region rows.
    - **Unchanged:** Ask's `AskPickFacts`.
    - **Left to R3:** the Map surfaces (`MapRegionPanel`, `MapWindowPanel`, the pill).
11. **H2 keeps working.** `verdictBeyondReach` reads `regionVerdictMean` (the scoped mean is null exactly
    when H2 should fire, `:229`). Its title names the region-wide verdict: `Northumberland is Worth it;
    its rated places are beyond 45 min`, and R1's unroutable wording still applies. A test pins the pair
    "Too few rated / within 45 min" with the beyond line.
12. The Order control's "Best" follows the active tier's mean when the headline is scoped.

Docs (same PR):
- `architecture-decisions.md` Backend-heavy (O-4 built for the headline; still the exit for the other
  members)
- CLAUDE.md's Backend-heavy line, if its wording changes
- `api-endpoints.md`, `plan-tab.md`, `plan-matrix-plan.md` §8 O-4, `map-landing-plan.md` §6 Q4
- this file's phase log

Tests:
- Characterisation, then extraction parity.
- **Served parity:** at full reach, home equals the served window and away equals the served origin
  region (verdict, mean, confidence, pick) over a realistic fixture.
- Pruning: UNROUTABLE out, PENDING in, null `locationId` out, a region emptied, a window emptied.
- The stamps survive and stay off the wire.
- The endpoint: Lite 403, no base 400, no MEASURED → `measured: false`, three tiers present.
- The caching test.
- The context: gating, one fetch per version and origin, mismatch discarded, a tier tap synchronous
  with no intermediate word.
- The card builder with and without an entry; the scope line and speech; the H2 pair; the Order
  control.
- Phone and desktop identical under Any.

### R3: the Map pill follows the drive tier (D9)

1. **One drive state.** The Map's drive filter (`MapView.jsx:1378`, `useState(0)`, copied from the Plan
   only on handoff at `:2125`) is replaced by `useReachLens`, so the Map and the Plan bar share one tier.
   The values already match (`FiltersPopover.jsx:388-392`, `reachLens.js:55`). The Map's "All" is Any.
   This is a visible change: the Map now opens on the same tier the Plan bar shows (a weekday default of
   45 min). The changelog says so.
2. **Scope × tier.** The pill's verdict follows My area / Everywhere (`MapView.jsx:3637-3640`); under a
   tier, the regions in scope are pruned by reach, so the two intersect. The pill reads R2's per-window
   `regions[]` for the active tier, intersected with the Map's scope, through the existing `mapVerdict.js`
   ranking (`:254-305`). That keeps `sharingCount`, `allInScope` and `scopedRegionCount` (`:420-427`)
   working. This is a server-verdict input, not a client derivation. `map-tab.md:33`'s "exactly one
   verdict channel" rule is rewritten to name it.
3. **Every Map figure is decided**, and the phase log records each:
   - the `MapWindowPanel.jsx:145-147` header verdict
   - the `· N rated` thin count (`MapView.jsx:3876-3881`)
   - the "is better" line (`landingRows`/`betterNext`, `:3893`)
   - the stepper ticks and `activeWindowTier` (`:4236`)
   - the `map-landing-plan.md:557-562` four figures (tally, `allPoor`, `nextWorthIt`, "N of M at
     4★+")
   - `MapRegionPanel` region rows (regional by definition)
4. **Scope words.** On desktop they go on the region line. On phones (≤639px) the region line and the
   rated count are hidden (`index.css:5262-5263`), and even "TOO FEW RATED" swaps to a short form at
   320-430px (`WindowControl.jsx:960-963`), so there the scope words replace the hidden region line's
   slot. They are spoken in full everywhere. Measure at 320px, 375px and desktop.
5. The Plan/Map agreement test (`mapVerdict.test.js:519-534`) is extended to every tier at Everywhere
   scope, and both origins. Its pinned My-area divergence stays.
6. Docs: `map-tab.md`, `map-landing-plan.md`, `architecture-decisions.md`.

## 4. Owner answers (2026-11-05)

- **Q1 → popup only.** No region-wide line on the card (D10).
- **Q2 → the Map follows too** (D9). After review it is its own phase, R3, because it needs one shared
  drive state, a scope × tier rule and the per-region verdicts.
- **Q3 → away plans are included** (D3), as the away card's own region pruned from its base.
- **Q4 → build the drive point in this series**: a named place (e.g. Glenridding), enforced where no
  route exists (D11, R1c).

## 5. Phase log

| Phase | Branch | PR | Merged | Notes |
|---|---|---|---|---|
| R1 | `fix/reach-unroutable-drive` | [#1717](https://github.com/gregochr/goldenhour/pull/1717) | 2026-11-06 (squash 30767fcf9) | Built 2026-11-05 (V220). Notes: the home stamp already took its instant before the roster read in both `DriveTimeRefreshJob` and `UserSettingsService`; the only home change was `DriveDurationService` treating a short ORS answer as no answer (the client already refused one). `RegionDriveTimeWriter.storeIfBaseUnchanged` makes the region rows conditional on the base too, not only the stamp. The card also carries `reachFarRemoved`, `reachUnroutableCount` and `reachOriginName` beside `reachUnroutableRemoved`. `regions.drives_measured_at` is not backfilled: every away drive reads PENDING until the first nightly region sweep after deploy. |
| R1b | `feature/admin-unroutable-locations` | [#1718](https://github.com/gregochr/goldenhour/pull/1718) | 2026-11-06 (squash 36be2ebaf) | Built 2026-11-06. `GET /api/admin/drive-times/unroutable` (`UnroutableLocationService`): stamps and id pairs by four small queries, then `DriveStatus.of` per (origin, location) in memory, so the count is by construction what a reader's card does. The denominator is MEASURED + UNROUTABLE origins (PENDING left out of both counts). The "Operations drive-time panel" is the Scheduler job panel for `drive_time_refresh` (there is no separate drive section). No migration. |
| R1c | `feature/location-drive-point` | [#1719](https://github.com/gregochr/goldenhour/pull/1719) | 2026-11-06 (squash f14f5bcea) | Built 2026-11-06 (V221). Notes: the map picker for nudging the point was not built (no map widget exists in the admin UI; the plan said not to build one), so the point is a looked-up place or a postcode as returned. The ORS fixtures for an unsnappable point (`contract/ors/probe-*.json`) are hand-written from ORS's documented behaviour (a null duration and a null `destinations[]` entry; HTTP 404 code 6010 as the error form), not recorded: there is no ORS key in tests and no live call is allowed. The client handles both forms, and a 6010 that names the source or the control (or no coordinate) reads as UNAVAILABLE, never as a refusal. The guard is a separate service called by `LocationController` before the transactional `LocationService` call (the controller holds no logic; the service stays free of ORS). The control destination is central Birmingham; a source with no route to it (a ferry-only island base) reads UNAVAILABLE and the save is allowed. `LocationService.update` invalidates (D1a) only when the ROUTING point moves (drive point when set, else the pin), so a pin move behind a drive point or a rename over the same coordinates deletes nothing. The reach payload's `driveTo` and the roster's `drivePointName` are the same for every reader (no personal data). `driveTo` is added to a reach entry only when set, so every other entry is shaped as before. The place lookup's 25 km cut (`DrivePointLookupService.MAX_DISTANCE_KM`) does not apply to a postcode, which the admin typed exactly. |
| R2 | `feature/reach-scoped-headline` | [#1720](https://github.com/gregochr/goldenhour/pull/1720) | 2026-11-06 (squash 0617b0d09) | Built 2026-11-06. Notes: see "R2 build notes" below the table. |
| R3 | `feature/map-pill-reach-tier` | | | Built 2026-11-06. Notes: see "R3 build notes" below the R2 notes. |

## 6. Review record

**Round 1** (backend engine, frontend reach gate, UI honesty, architecture/privacy) changed:
- per-region re-ranking on a pruned tree, instead of cross-region pooling
- the served briefing as the only source
- a real `briefingVersion`
- all three tiers in one response
- PENDING separated from UNROUTABLE
- `measured` made reader-level
- the beyond line, picks, `blanked`, the Order control, the verdict consumers and the Map invariant
  brought into scope
- the scope words on their own line
- the admin view as a read-only phase
- the "simulated" bullet dropped

**Round 2** (away base, drive point, Map pill) changed:
- `drive_target_changed_at` (a deleted row would otherwise read UNROUTABLE, and coordinate moves
  invalidated nothing)
- stamp-before-read, full-answer-only and base-unchanged stamping
- the away matrix shape left alone, with status derived client-side from served stamps
- away cards prune only their own region
- the ORS check before the transaction, with a control destination, snap metadata and
  `tryWithPermit`
- the 422 narrowed to create, move and clear
- a `PlaceResult` record for postcodes.io places
- `driveTo` on reach payloads, with miles dropped beside it
- the Map split into R3, with one shared drive state, scope × tier and per-region verdicts

### R2 build notes (2026-11-06)

- **Backend.** `BriefingSlot` gains two `@JsonIgnore` components, `forced` and `triaged`, stamped by `enrich` (class-level
  `@JsonIgnoreProperties({"forced","triaged"})` too: Jackson 2 refuses an ignored creator property that appears in the input).
  `BriefingRegionEvaluationRollup.rollUpRegion` is the extracted per-region block; `enrich` and `rerollRegion` both call it
  (the characterisation test is `BriefingRegionEvaluationRollupCharacterisationTest`, committed first). `DailyBriefingResponse`
  gains `briefingVersion` (`BriefingVersion`, SHA-256 of the ratings, dispositions, stamps and UK date, 16 hex chars), set once
  per assembly in `ServedBriefingAssembler.assembleForPlan`. `PlanWindowProjector.reproject` re-projects pruned windows with the
  projector's own `draft`, `rank` and `selectPicks` over the SERVED render horizon. Two completeness guards caught real gaps and
  were updated: `RecordWitherPreservationTest` found that `withPickScope` dropped the new field, and the Lite classification
  tests (`LitePickShaperTest`, `BriefingRoleShaperTest`, `LitePickSentinelTest`/`ReviewedTypes`) now classify the three new components.
- **A pruned region re-runs the gloss rule.** The extracted block drops a region's gloss when its verdict moves, so a region
  whose verdict a tier changes (Worth it to Poor over its near places) carries no BEST BET candidate at that tier: its prose was
  written about the whole region. A region the tier leaves unchanged keeps its gloss and its pick.
- **Away picks.** The projector runs over the full tree with only the origin region pruned, so a pick is the forecast's pick among
  windows; it is kept on an away card only when it names the origin region (the rule the client already applied to the served pick).
- **Extra body fields.** Each window also carries `topMeanRating` (the top region's mean regardless of eligibility, the Order
  control's key) beside `meanRating` (the mean the card may PRINT: the region's when verdict-eligible or UNRATED, else null).
  `thinCount` is the number of rated places behind a rated word whose region is verdict-eligible only through force-evaluation
  (`sampleSufficient` false, `forcedSample` true); it adds no threshold. A place not on the reader's reach list is kept (unknown),
  as the client keeps a spot with no reach entry; a null `locationId` is out.
- **Scope words.** The scope is printed in the Spread qualifier's compact spelling (`within 45m`, `within 1h30`, `within 2h30`) and spoken in its long form (see the review fixes below). It sits on its own grid row under the verdict row (`wf-hc-scope`).
- **`card.verdictLabel` consumers, each decided:**
  - matrix card and its hidden sentence (`WindowFirstHeatStrip`): **scoped, with the scope words** (scope line, then the scope straight after the word in the sentence);
  - `WindowSheetDialog` header badge: **scoped**, the scope printed beside the badge (`window-sheet-verdict-scope`);
  - `WindowSheetDialog` live message: **scoped**, the scope voiced straight after the word;
  - `windowFirstStrip.js` (`buildHeatStripCards`): **folds** `verdictScope`, `regionVerdict`, `regionVerdictMean`, `scopedThinCount` and the `served*` trio, only when the card wears a scoped headline;
  - `planSearch.js` window rows: **scoped**, `time · Poor · within 45m`;
  - `locationSheet.js` `stateLabel`: **unchanged**; it is printed only on an away (travel-day) row, which is never scoped;
  - `WindowRegionRail` region rows: **regional by definition, unchanged**;
  - Ask's `AskPickFacts`: **unchanged** (its card is a slot-level verdict from `askModel`, not the window card);
  - Map surfaces (`MapRegionPanel`, `MapWindowPanel`, the pill, `mapDrilldown`): **left to R3**. `WindowFirstMapPane` keeps handing the Map the SERVED confidence and pick (`servedConfidence`, `servedPickKind`, `servedPickRegion`) so Map output is unchanged.
- **H2.** `verdictBeyondReach` reads `regionVerdictMean` on a scoped card (the scoped mean is null exactly when nothing rated is in reach) and its title names the region-wide verdict: "Northumberland is Worth it; its rated places are beyond 45 min", with R1's unroutable wording kept.
- **Order control.** There is no Order control in this tree; its key is `card.topMeanRating`, which now follows the tier's `topMeanRating` while a scoped headline is worn.

**R2 review fixes (2026-11-06).**
- **Away card and the canopy rule.** `PlanWindowProjector.ScopedWindow` carries `all` (the window's regions before the
  window-level canopy rule thins `ranked`), and the away branch finds the origin region there by name, as the client's
  `originRegion` does. A base whose only place in reach is a wood keeps its card.
- **A blanked far region is dropped at a finite tier.** A region with no slot has no place in reach, so `prune` drops it like
  any emptied region; the window it alone filled takes the projector's empty answer (AWAITING).
- **Honesty for an emptied sample.** A pruned region left with no rated place is worded as the served filter words a
  zero-coverage region (`BriefingHonestyFilter.blankedForReach`): STAND_DOWN only when a surviving place carries the
  `triaged` stamp (the weather really stood it down), AWAITING otherwise. The served filter reads the latest dispositions;
  D5 forbids re-reading them, so the stamp (from the same evidence) stands in.
- **`briefingVersion` also hashes the rendered events** (they bound where a pick may land), and each window carries
  `topRegionName` (the in-reach top region; away, the origin region).
- **Fetch key.** `useReachVerdicts` keys on `briefingVersion | origin | homeSettingsVersion | driveTimesVersion | user`
  (the inputs `getReach` keys on, plus who is signed in) and forgets what it held when the key becomes null.
- **Speech.** The scope is printed short (`within 45m`) and spoken long (`within 45 min`, `within 1h 30min`: the Spread
  qualifier's own `spoken` form, `verdictScopeSpoken`), in the matrix sentence and the dialog's live message. The
  substring test maps visible to spoken as the qualifier's does.
- **Scoped card extras.** `movement` is null while a scoped headline is worn (the served mover names a region the word is no
  longer about); `hotRegionName` is the in-reach `topRegionName`, with the served one kept as `regionHotRegionName` for H2.
- **Grid baseline.** While any card wears a scope, every other non-away card reserves a hidden `.wf-hc-scope.reserved` row so
  Spread and Best share a baseline; under Any, or with nothing held, no row is added. The ellipsis sits on an inner block
  span (`.wf-hc-scope-t`), not the flex container.

### R3 build notes (2026-11-06)

- **One drive state.** `MapView`'s `useState(0)` is gone; it reads the Plan bar's `useReachLens` (the pane hands it down as
  `reachLens`, with the provider's once-computed `reachMeasured`). The popover writes `selectTier`, the crumb's reach clear
  and "Clear all" write Any, so the Plan bar follows. A door handoff no longer copies `limitMinutes` (the sheet door's own
  widened tier is not carried; the bar's tier is the one tier). **The tier is applied only where the reader's drives are
  measured**: a weekday opens on 45 min, and an unmeasured reader (no postcode) would otherwise have the whole Map emptied by
  the old rule that drops a place with no drive time. The popover shows the shared tier (Any when drive-locked) even then, as
  the Plan bar does. A mount with no lens (tests, any host without the provider) keeps a local state pinned to Any. The Map's
  pin filter stays the deliberate R1 exception (drops every place with no drive time); it governs what is DRAWN, while the
  pill's population uses the Plan's `gateSpotsByReach` (`utils/mapReach.js`).
- **Scope x tier.** `buildReachVerdictIndex` indexes the held tier's per-window `regions[]`; each record keeps its position
  in the server's best-first order (`reachRank`) because the entries carry no sample-gate flag, and `windowVerdict` selects the
  best-ranked in-scope record with a finite mean (so an `AWAITING` lead with no mean is skipped as the served ranking skips
  it). The regions in scope for a reach verdict are `regionNamesOf(reachPool)`, `reachPool` being the scope pool narrowed by
  the tier, so `sharingCount`/`allInScope`/`scopedRegionCount` count regions in scope AND in reach. A window the answer does
  not name keeps the served verdict (no scope words); a named window with nothing in reach has no verdict (the pill is empty,
  as the field is). The verdict carries `scope: {tierMinutes}`.
- **Every Map figure, decided:**
  - pill word, region line, `+N` / `everywhere` tally: **scoped** (reach records, scope x tier);
  - `MapWindowPanel` header verdict (`:145-147`): **scoped**, with the scope beside it (`wf-win-panel-verdict-scope`); its note
    (`windowPanelNote`) says the verdict is over the places within the tier and the rows are each region as a whole, and
    replaces the tally sentences ("N regions are Worth it, so the choice is drive time") that would sit beside rows listing all
    regions;
  - `MapRegionPanel` and `MapWindowPanel` region rows, and their `N of M at 4★+`: **regional by definition, unchanged**
    (served verdicts, scope-pool counts);
  - the pill's `· N rated` (`MapView.jsx:3876-3881`): **counted over `reachPool`** for a scoped verdict
    (`buildThinCounts` `reachPool`), the scope pool for any other row; it is the Map's own client count (licensed member (f)),
    not the server's `thinCount`, because the server's is over the top region of Everywhere, not of the reader's scope;
  - the "is better" line (`landingRows`/`betterNext`): **follows the scoped verdicts**; every window is answered at the one
    tier, so the comparison is like-for-like; the pill beside it carries the scope words;
  - stepper ticks and `activeWindowTier`: **follow the scoped verdicts** (colour only; the pill is labelled);
  - the dropdown rows and the phone's Windows section / Other windows value: **follow the scoped verdicts**, captioned
    `Verdicts within 45m` (the Other windows value speaks the scope and prints none: that cell never cuts);
  - the haze (`conf`/`confidenceTier`) and the medallion (`pickKind`/`pickRegion`): **the card's**, which is the tier's while
    the Plan wears a scoped headline (the R2 `served*` trio was removed, nothing reads it);
  - the four `map-landing-plan.md:557-562` figures: the tally is scoped as above; `allPoor` and `nextWorthIt` **no longer
    exist in code** (the landing card was removed); "N of M at 4★+" is regional by definition.
- **Scope words.** Desktop and tablet: on the pill's region line (`.wf-win-verdict-sub`: region capped at 72px so the line is
  no wider than an unscoped one, scope `flex: none`). At <=639px the stylesheet already hides the region line and the rated
  count; the region separator is hidden too and the scope takes the slot. Printed `within 45m` (aria-hidden), spoken
  `within 45 min` (sr-only, in the pill's accessible name). **Not measured in a real browser** (320px/375px width of the
  second line against the day label): see the PR report.
