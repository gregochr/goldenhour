# Neighbour guard — plan

**Status:** plan, G0. Owner decision 2026-10-17: build option (b) of
`docs/engineering/same-cell-rating-spread-plan.md` in its "withhold" form, whether or not the FAR
bucket moves to Sonnet 5.5, and build and merge the phases without further interaction unless a
product decision blocks them. Reviewed adversarially (three lenses: code seams, product behaviour,
tests and rollout) before merge; §10 records what the review changed.

## 1. What it is for

A sky rating far above SEVERAL neighbours that see the same sky is far more likely to be model noise
than a real local difference (`same-cell-rating-spread-plan.md` §3: Haiku gives near-twin inland
neighbours stars 2+ apart 6–11% of the time, Sonnet 5.5 0.2–0.3%). The harm is when a surface NAMES
that one place as the best: Ask's top pick for Sunday 18 Oct was a lone 4★ at Caw Gap, with Cawfields
1★ and Steel Rigg 2★ within 2.3 km.

The guard marks such a slot as a **neighbour outlier**. A marked slot is never NAMED as a single best
place (§3). It keeps its star everywhere a star is shown.

## 2. The rule

A served slot is a neighbour outlier when ALL of these hold:

1. It is a sky slot: not canopy (`!slot.canopy()`), in a region (`BriefingEventSummary.regions()`;
   unregioned slots are neither candidates nor neighbours), in a SUNRISE or SUNSET event summary,
   and with a `skyRating` of at least `min-rating` (default 3).
2. **No supporter.** No OTHER sky slot in the same window (same date and target type, across every
   region of that day) within `radius-km` (default 5 km) has a `skyRating` within `margin - 1` of it.
   With the default `margin` 2, every nearby place, at ANY height, is rated at least 2 stars lower.
3. **Enough witnesses.** At least `min-neighbours` (default **2**) of those nearby sky slots are at a
   similar height: both places have no elevation, or both have one and they differ by at most
   `max-elevation-gap-m` (default 150 m).

A slot with a null `locationId`, no coordinates or a null `skyRating` is neither a candidate nor a
neighbour (fail open). Rows written before V164, a bluebell blend and a sky-not-forecast substitution
carry no `skyRating`.

Why each choice, measured in §7 or argued:

- **`skyRating`, never the served `claudeRating`.** The served star averages in each coastal place's
  own tide score; on the served star 46 of Sonnet's 47 candidates are tide, not noise
  (`scripts/measurements/same-cell-rating-spread/results.md`). For an inland slot the two are equal.
- **Distance, not the grid cell.** Caw Gap is alone in its `grid_lat`/`grid_lng` cell, so a same-cell
  rule never flags it. The served slot carries no solar-horizon cloud, so the measurement's "solar
  inputs within 5 points" test is not available at serve time.
- **At least two neighbours.** With ONE disagreeing neighbour there is no evidence which rating is the
  noisy one: a noisy LOW neighbour would make an honest 3★ look like an outlier. Every flag the
  one-neighbour rule raised on the served cache (5 in 18 windows) had exactly one neighbour; with two
  required, the served cache raises none and Sonnet's 36 runs raise none, while Haiku's FAR bucket
  still raises some (6 in 16 runs under the full rule), and Caw Gap is caught.
- **Supporters at any height, witnesses at a similar height.** The app rates an inversion overlook
  high exactly when the valley below is in cloud, so two valley places must not outvote a fell top.
  But a nearby place that AGREES is evidence for the high rating wherever it stands. So any nearby
  place can clear a slot, and only places at a similar height can count against it. Elevation is set
  on 65 of the 238 enabled sky places (§7), so "similar height" pairs a place with an elevation only
  with others that have one: a fell top is never outvoted by places whose height is unknown. The first
  draft counted unknown heights as similar and excluded dissimilar places from BOTH roles, which let
  two equally high neighbours flag Latrigg (4★) while Castlerigg (3★, 1.7 km, 158 m lower) was
  ignored; the asymmetric rule clears it.
- **5 km.** Flags first rise with the radius (a row with no neighbour at a small radius can never be
  flagged) and then fall (more neighbours, harder to be above every one), while a larger radius
  compares a coast with a fell. 5 km catches Caw Gap.
- **Margin 2, floor 3.** A 1-star gap is the model's ordinary resolution; 2 is the measurement's
  definition of disagreement. With margin 2 a floor below 3 is unreachable, so the floor's bound is
  3–5 and it exists to let an operator guard only 4★+ claims.
- **Above EVERY nearby place.** One nearby place rated within `margin - 1` clears the slot.

## 3. Where it applies

Every surface that NAMES one place as the best:

| surface | today | with the guard |
|---|---|---|
| Ask pick eligibility (`AskSnapshot.isPickEligible`), and through it `rank_spots`, the answer validator, the thread re-join, the Ready freshness re-check, `ReadyRelevance`, `ReadyQuestion`'s anchors and the stub | rating ≥ 3 in a verdict-eligible region | also not a neighbour outlier |
| Ask's `list_windows` (`AskTools.windowInfo`) `bestRating` | the window's served best, which includes the outlier | the same figure over the same slots with neighbour outliers left out (verdict-ineligible regions still count, as today), so the model is never told "up to 4★" beside a 3★ list |
| The served BEST BET / ALSO GOOD destination (`PlanWindowProjector.topSlot`), and through it the pick dialog's "Open <name> on map", `GET /api/briefing/digest`'s window pick and Ask's `windowInfo` pick | the region's highest-rated slot | the highest-rated slot that is not an outlier; if the top slot WAS an outlier, the next must be rated ≥ `min-rating`, else the pick names the region only (destination null, which every consumer already handles) |
| The Plan card's best-reachable line (`windowFirstCards.js` `bestReach`), its popup header "best N★ within reach", the search row's figure (`planSearch.js`) and the strip thumbnail copy (`windowFirstStrip.js`) | the head of the reach pool | the first pool member that is not an outlier; if an outlier was skipped and that member is rated below 3, or none is rated, a new **"no clear pick"** state (§4 G3) |
| The dawn-race caption (`WindowSheetDialog.jsx`, `raceSpot`) | `bestReach`, else `spots[0]` | `bestReach`, else the first spot that is not an outlier |

Deliberately NOT changed, because they show a star rather than name a best place, and the owner's
spec is that the star stays: the map (heat field, chips, pins, callout, the drilldown's top four with
their leave-by times), the region mean, verdict, rail cell and its `best N★`, the spread histogram,
the popup's ranked spot strip and "see all" sheet, the location sheet's `◎ best here` (a place's own
windows), the served window `bestRating` (the map pill's `N★ best`, the digest's figure) and the
window choice itself (BEST BET picks a REGION; the guard changes only which place in it is named).
The night windows (astro, aurora) are untouched: the guard is solar only.

**Stated divergences, accepted.** Within the Plan popup, the header can read "best 3★ within reach"
above a strip whose first card is the 4★ outlier and a rail cell reading "best 4★"; the map pill can
read "4★ best" while the BEST BET dialog names a 3★ place; the digest pairs the served `bestRating`
with the guarded pick. In each the number is the forecast and the name is a recommendation. A quiet
visible mark on the outlier is the fix if this confuses readers, and is an owner question (§9), not a
blocker.

**Out of scope, stated:** the hand-started run's notification (`ForecastService` →
`NotificationDispatcher`, which names a place at its raw rating), and gloss and best-bet prose (which
read aggregate rollups and name no single place from per-location ratings).

## 4. Design

### G1a — `skyRating` from the row (backend, small)

`EvaluationViewService.toEnrichmentResult` builds the enrichment result from a `forecast_evaluation`
row, when that row wins the cache-versus-row precedence, with the 8-argument constructor, so
`skyRating` is null although `forecast_evaluation.sky_rating` (V164) holds it. Pass
`row.getSkyRating()` through; it is written together with `rating` from the same result
(`ForecastResultHandler.scoreEvaluationRow`), so it is never from another run. Effects, intended and
stated: a row-served slot shows the tide-fit block's "· sky N★" clause where it applies, as a
cache-served slot already does, and the build path persists that `skyRating` into
`daily_briefing_cache`. On the served cache today every rated slot already carries a `skyRating`
(§7), so the visible change is small. Its own PR because it is a user-visible change independent of
the guard.

### G1b — the guard (backend)

- **`NeighbourOutlierGuard`** (new, `service/`), a pure function over a response's days and a
  `Map<Long, LocationPoint>` (`id, lat, lon, elevationMetres`), returning the set of
  `(locationId, date, targetType)` keys flagged, applying §2's supporter and witness tests. Distance is `GeoUtils.distanceMiles` (the one
  distance formula in the codebase) behind a latitude/longitude bounding-box prefilter, with the
  radius converted from km once (named constant). Per window it compares only the sky slots with a
  `skyRating`; with the prefilter the cost is a few thousand haversines a serve.
- **The flag rides the served slot.** `BriefingSlot` gains `@JsonInclude(NON_NULL) Boolean
  neighbourOutlier`, `TRUE` or null, never `FALSE`, so an unflagged payload serialises exactly as
  today (pinned on both the Jackson 2 graph that persists and the Jackson 3 graph that serves). The
  current 17-argument canonical constructor is KEPT as an explicit constructor passing null (its 12
  test callers compile unchanged); the shorter ones are unchanged. A new wither
  `withNeighbourOutlier(Boolean)` stores the value verbatim (the preservation test passes `false`);
  every existing wither (`withTide`, `withEclipse`, `withClaudeScores`) PRESERVES it, and the static
  `canopySlot` passes null. Preserving in `withClaudeScores` is safe because the flag is set only
  after enrichment, and it satisfies `RecordWitherPreservationTest`, which gets the row
  `Wither.of(BriefingSlot.class, "withNeighbourOutlier", "neighbourOutlier")`.
- **Computed at serve only.** A new step in `ServedBriefingAssembler.assembleForPlan`, after
  `attachMovement(assembleWithoutPlan(...))` and BEFORE `PlanWindowProjector.apply`, so the
  projector's `topSlot`, the digest and Ask (all through `getCachedBriefingForApi`) see it, and it
  judges the ratings actually served after the honesty filter has emptied zero-coverage regions. The
  build path (`getCachedBriefing`, `persistBriefing`) never runs it, so `daily_briefing_cache` never
  carries the flag.
- **Coordinates.** A `LocationPointProvider` (new) memoises `(id, lat, lon, elevation_m)` for enabled
  locations for 60 seconds, timed on the injected `Clock`, from one JPQL projection on
  `LocationRepository` (`SELECT new …`, which has precedent and runs on H2). The serve path already
  reads the enabled roster inside `EvaluationViewService`, but drops the coordinates before the
  assembler and `LocationService.findAllEnabled` is uncached, hence a memo rather than a second
  roster read per serve. At most one extra query a minute; it sits outside CLAUDE.md's
  scoped re-enrichment query counts, which stay as they are, and the BUILD count (8) is unchanged.
  A location moved or disabled within the minute is judged on its old point once.
- **`PlanWindowProjector.topSlot`** skips an outlier; if it skipped one, the next must be rated ≥
  `min-rating`, else null. `BriefingWindow.Pick.locationName`'s javadoc ("must match the slot the
  header star came from", "or null when none is rated"), `topSlot`'s, and `WindowPickDialog.jsx`'s
  comment ("Omitted… when the region has nothing rated") are updated: the destination is the best
  slot that is not an outlier, and a null destination now happens on ordinary windows. Every
  consumer is null-safe today: `WindowPickDialog` omits the location block and button,
  `windowFirstCards` maps it to null, `BriefingDigestService` copies null, Ask's `pickKind` and
  `windowInfo` handle it.
- **Config** `NeighbourGuardProperties`, the `AskProperties` pattern (a mutable `@Component`
  `@ConfigurationProperties("photocast.neighbour-guard")` with bound-checking, startup failing on a
  bad value): `enabled` (**false** until G4), `radius-km` (5.0, 1–20), `min-neighbours` (2, 1–5),
  `margin` (2, 2–4), `min-rating` (3, 3–5), `max-elevation-gap-m` (150, 0–1000). Documented in
  `application-example.yml`. Every key is optional. With `enabled` false: no flag, no query.
- **Shipped OFF.** G1b merges with `enabled` false so the Plan pick, the best-reachable line and Ask
  never disagree about a place in between phases. G4 turns the default on once G2 and G3 are merged.
  Production's hand-edited config sets none of these keys (verify on the host before G4's release
  note); the rollback after G4 is `photocast.neighbour-guard.enabled: false` there and a restart.
- **Logging.** `NeighbourGuardLog` (new, injectable sink, the `AskDenialCounter` shape): INFO the
  first time a (location id, date, type, sky rating) is flagged in this JVM, bounded at 1,024 keys,
  ids and ints only; and one INFO summary whenever the briefing's `generatedAt` changes or the
  flagged count changes: flagged N of M rated sky slots over W windows, sky-rating coverage K%. The
  summary is the figure G4 and later reviews read.
- **Wiring.** `ServedBriefingAssembler`'s constructor gains the provider and the properties;
  `BriefingServiceTest` and `ServedBriefingAssemblerTest` construct it directly and are updated.

### G2 — Ask (after G1b merges)

- `AskSnapshot.Slot` gains `boolean neighbourOutlier` (a 9-argument constructor stays, passing
  false); `AskSnapshotBuilder.toSlot` reads `Boolean.TRUE.equals(slot.neighbourOutlier())`.
- `isPickEligible` adds `!slot.neighbourOutlier()`.
- `AskSnapshot.Window` (or `windowInfo`) reports `bestRating` as the highest rating among the window's
  pick-eligible slots, null when there are none.
- **Wording.** An earlier thread pick that is now an outlier re-joins with its own line, "· held back:
  rated well above the places beside it", tested before the generic not-eligible branch in
  `AskThreadContext`'s pick line (its rating has not fallen, so "no longer rated well enough to pick"
  would be false). The stale-thread can't-answer fires when no tool was called and every submitted
  pick is in `ThreadFact.droppedPicks`, which today records no reason. G2 adds a `heldBack` pair set
  to `ThreadFact`, filled where the thread is reconciled with the live snapshot. When every dropped
  submitted pick is held back, the missing text is "those places rate well above their neighbours"
  (7 words, inside `MISSING_WORDS` 8); when the reasons are mixed, "those places can no longer be
  picked" (7 words); when none is held back, today's text, unchanged. The prompt's thread rules say a
  held-back place must not be described as rated low.
- Ask keeps its invariant that tools return only pick-eligible places: a reader asking about a
  guarded place by name is treated as a place that is not offered, as a 2★ place is today.

### G3 — client (after G1b merges; parallel with G2, touching only frontend files and the Plan bullets of CLAUDE.md)

- `windowFirstSpots.buildWindowSpots` copies `neighbourOutlier` (true or absent) onto each spot.
- `windowFirstCards` picks `bestReach` as the first spot of the reach pool that has a rating and is
  not an outlier; if an outlier was skipped and that spot is rated below 3, `bestReach` is null and
  the card carries `bestReachWithheld: true`. The pool, the histogram and every count are unchanged.
  A filter over a served flag: the already-licensed filter/map/select class of CLAUDE.md's
  Backend-heavy bullet.
- `bestReachLine` gains a state for `bestReachWithheld`, tested after `if (best)` and BEFORE the
  `empty` and `verdictBeyondReach` fall-throughs (otherwise a withheld pool, which has rated spots,
  falls through to "not scored yet"): visible "no clear pick", muted; spoken text beginning with the
  label word (SC 2.5.3), "Best, no clear pick". Its title has two wordings: with a measured drive
  (`card.reachMeasured`), "The highest rating within reach is well above every place around it, so
  none is named."; without one, "The highest rating here is well above every place around it, so none
  is named.", because no reach sentence may say "within reach" without a measured drive.
- `windowFirstStrip.js`'s fold forwards `bestReachWithheld` beside `bestReach` (it passes no unknown
  field through), so the popup header and `planSearch`'s figure, which read the strip descriptor, can
  take the same state; the popup header's "nothing rated yet" branch must not fire for it.
  `raceSpot`'s fallback skips outliers.
- A running page keeps the JS it loaded, so a phone opened before the deploy keeps naming the outlier
  on the card until it reloads; Ask and the BEST BET pick are server-side and are guarded at once.

### G4 — enable and sweep

Turn `enabled`'s default to true, re-run `serve-guard.sql` and record the counts, update CLAUDE.md's
cross-references and this plan's status, changelog.

## 5. Phases

| phase | scope | depends on |
|---|---|---|
| G0 | this plan, `guard-tuning.sql`, `serve-guard.sql` | — |
| G1a | row `skyRating` pass-through | G0 |
| G1b | guard, flag, provider, config (off), logging, `topSlot`, CLAUDE.md, changelog | G1a |
| G2 | Ask | G1b merged |
| G3 | client | G1b merged (parallel with G2) |
| G4 | default on, counts, docs sweep | G2 and G3 merged |

Each phase: build, local gate, adversarial review of the diff, fix, PR, CI and Codex green, merge.
Agent worktrees branch from main, so a phase starts only after its dependency has merged.

## 6. Tests that must exist

**G1a.** A row-served slot carries the row's `sky_rating`; a cache-served slot is unchanged; a row
with a rating and no `sky_rating` serves null.

**G1b — the guard (pure).** Each clause necessary, so a mutant removing any one fails a test:
- canopy CANDIDATE never flagged; canopy NEIGHBOUR ignored (a low wood beside a 4★ does not flag it);
- unregioned slot neither candidate nor neighbour; a non-solar summary ignored;
- `min-rating` floor exactly at and one below, run under a non-default config (`min-rating` 4) since
  the default floor is unreachable at margin 2;
- `margin` at 2 and 3 on both sides of the boundary (≥, not >);
- radius just inside and just outside, inclusive at the boundary; an east-west pair inside 5 km only
  with the cos-latitude term (kills a dropped term), and a pair that distinguishes swapped lat/lon;
- `min-neighbours`: one neighbour does not flag at the default; two do; under `min-neighbours` 1 one
  does;
- supporter: one nearby place within `margin - 1` clears it, AT ANY HEIGHT (a 3★ place 200 m below a
  4★ clears it: the Latrigg/Castlerigg case);
- witnesses: a nearby place 200 m below, both elevations known, does not count toward
  `min-neighbours`; a place with an unknown elevation is not a witness for one with a known
  elevation, and is for one without; the gap boundary at 150 (inclusive);
- null `skyRating` on candidate or neighbour fails open; null `locationId` and missing coordinates
  neither flag nor count; the same location never its own neighbour;
- a different date, and the other target type on the same date, are never neighbours; regions do not
  separate neighbours;
- `skyRating` not `claudeRating`: a tidal slot whose combined star differs but whose sky star agrees
  is not flagged, and one whose sky star differs is;
- Caw Gap's real numbers as a fixture (4 against 1 at 2.25 km and 2 at 2.2 km).

**G1b — assembly and projector.** End to end through `assembleForPlan`: an outlier top slot moves the
window `Pick`'s destination name and id to the next slot (kills a guard moved after the projector); a
zero-coverage region's raw slots are not neighbours (kills a guard moved before the honesty filter);
the flag survives `stripSlotTide`; `withTide`, `withEclipse`, `withClaudeScores` and `canopySlot`
preserve it; `enabled` false sets nothing and never calls the provider; the persisted payload
(Jackson 2) has no key; the served JSON (Jackson 3) has the key only when true; `topSlot`'s floor
after a skip (next at 2★ gives null); an all-outlier region names no place. Properties: each bound,
both sides. Logging: first time only, the bound evicts, the summary fires on a `generatedAt` change
and not on a repeat serve.

**G2.** `isPickEligible` clause test extended; `rank_spots` omits the outlier; the validator drops a
submitted outlier; a thread pick that became an outlier re-joins with the held-back line and is not
offered; the all-held-back stale thread gives the new missing text; a Ready answer whose pick became
an outlier is withheld; `windowInfo.bestRating` is the best eligible rating.

**G3.** `bestReach` skips an outlier to the next spot; skip-then-below-3 and all-outlier give the
"no clear pick" state with its title and spoken text, and never "not scored yet"; no outlier gives
exactly today's `bestReach`; the histogram counts the outlier; the popup header and the search figure
follow; `raceSpot` never names an outlier; "within reach" is absent without a measured drive.

## 7. Measured basis

**Stored rows by run** (`guard-tuning.sql`, rows with a `sky_rating`, which exists from 2026-10-04;
counts are flagged ROWS over the runs shown; Sonnet's T+0 and T+1 rows come from the same cycles;
the "any neighbour" columns flag a row 2+ above every neighbour inside the radius, the last two add
the plan's tests):

| bucket | model | T+ | runs | same cell | 3 km | 5 km | 8 km | 12 km | 5 km, ≥ 2 neighbours | **the plan's rule** |
|---|---|---|---|---|---|---|---|---|---|---|
| near (until 4 Oct) | Haiku 4.5 | 0 | 2 | 4 | 5 | 7 | 1 | 2 | 1 | **0** |
| FAR | Haiku 4.5 | 2 | 10 | 6 | 12 | 14 | 10 | 12 | 7 | **5** |
| FAR | Haiku 4.5 | 3 | 6 | 1 | 3 | 6 | 4 | 4 | 1 | **1** |
| near | Sonnet 5.5 | 0 | 17 | 0 | 1 | 2 | 5 | 4 | 0 | **0** |
| near | Sonnet 5.5 | 1 | 19 | 1 | 2 | 2 | 4 | 4 | 0 | **0** |

The Caw Gap 4★ of 16 Oct (its 2026-10-16 01:07 run) is flagged at every radius from 3 to 12 km and
under the plan's rule (neighbour maximum 2★, six neighbours at a similar height inside 5 km), and
missed by the same-cell rule, which finds no neighbour for it. Elevation is set on 65 of the 238
enabled sky places (min 40 m, max 815 m; 45 at 200 m or more), all ten Hadrian's Wall sites among
them.

**The served cache** (`serve-guard.sql`, the rule applied to `cached_evaluation` as the app serves it:
one result per place per window, whatever run or model wrote it, across regions), 18 windows from
10 to 18 October: every rated sky slot carried a `skyRating` (100% coverage). With one neighbour
allowed, 0–2 flags a window (Berwick-upon-Tweed and Cocklawburn 3 against Spittal 1, Egton Lone Tree 4
against 2, Ullswater South Shore 4 against Aira Force 2, Newbiggin 3 against Cambois 1: each with
exactly one neighbour). **Under the plan's rule, 0.** The served cache by then held the re-scored
Sonnet ratings for the Caw Gap window.

So at the defaults the guard raised 6 flags in 16 Haiku FAR runs (about one every two or three
nights) and none in 36 Sonnet runs or 18 served windows. The stored-row proxy groups by run hour, model and horizon, which the served cache does not;
the served-cache measure is the one the defaults rest on.

## 8. Known limits

- Distance and elevation are proxies for "same sky". A place can honestly out-rate two nearby
  neighbours at its own height (a sea-facing headland beside two inland valleys); the guard withholds
  its NAME for that cycle, and its star stays. Elevation is known for about a quarter of sky places,
  so a place with one is judged only against others with one.
- The guard sees only the served `skyRating`. Neighbours whose results came from different runs are
  compared as served, which is what the reader sees.
- It cannot catch a noisy rating with fewer than two neighbours (most places, and every force-evaluated
  far-out headline, which has no rated neighbours), or a cluster rated high together.

## 9. Owner questions (not blocking)

1. Should the outlier wear a quiet visible mark where its star is still shown (the popup strip card,
   the callout), so the stated divergences in §3 explain themselves? The plan ships silent, per the
   spec "the map and region mean keep the star".

## 10. What the adversarial review changed

The draft was reviewed by three read-only agents (code seams; product behaviour; tests, rollout and
measurement). Adopted: a minimum of two neighbours and an elevation test, made asymmetric in a second round
(one-neighbour flags carry no evidence of which rating is wrong; a nearby place supports a high
rating at any height, but only places at a similar height may count against it, so a fell top above
an inversion is not outvoted by the valley); a serve-time measurement over
`cached_evaluation` in place of the stored-row proxy alone; the "no clear pick" state (the draft's
fall-through would have printed "not scored yet" over rated bars); the popup header, search figure
and dawn-race fallback; a rating floor after a `topSlot` skip; Ask's `bestRating` and its held-back
wording; the new wither and the wither-preservation test; `GeoUtils` with a prefilter instead of a
second distance formula; a 60-second memo in place of a per-serve query; the `AskProperties` config
pattern and `application-example.yml`; shipping off until G2 and G3 land; splitting G1; a summary log
line; and the mutant-killing tests in §6. Considered and not adopted: an observer-point cloud
similarity clause (unmeasured, and the two-neighbour rule already removes every served false flag
found); letting Ask report a guarded place (Ask's tools return only pick-eligible places, as for a
2★ place).
