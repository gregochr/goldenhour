# Handoff: a Glance card opens the Map, and the window popup is deleted

## Decision (owner, 2026-11-20)
"I look at At a Glance to see the next few days, but then I drill into the map." Tapping a Glance card now **switches to the Map tab with that solar window selected**, keeping the drive and rating lens. The window popup (`WindowSheetDialog`) and everything that exists only for it are **deleted. Be ruthless:** no dead code, CSS, tests, docs or guide text survives.

## What moves to the Map before the popup goes
Almost everything on the popup already exists on the Map. These four don't, and they move first:

| From the popup | To the Map | Notes |
|---|---|---|
| **"See all N →" ranked list** (`WindowSpotSheet`, rating then drive) | a **☰ List** button in the Map's top-right group, opening the same sheet for the selected window | The only "best first, every spot" view. On a phone it's a bottom sheet; a row closes it and opens that place's callout. `WindowSpotSheet` and `WindowSpotCard` **stay**. |
| **Best bet reason** (`WindowPickDialog`: the advisor's `headline` + `detail`) | a **◎ Best bet · why** pill in the window control (`WindowControl` / `MapWindowPanel`) that expands the reason in place | Today the reason is only readable in the popup (the Glance badge carries it as a `title` tooltip only). If the owner would rather drop it, see "Claude cost" below. |
| **The full warning** (`WindowWarningRow`: headline, what to expect, what to do, validity, link) | the Map's warning chip (`MapWarningChips`) **expands** to the same `WindowWarningRow` | `WindowWarningRow` survives (Coming up uses it). The chips stay a summary until pressed. |
| **Regions best first** (the popup's tiles were sorted by verdict) | the Map's Regions menu (`RegionsJump`) lists regions best first, with the verdict word and "best 4★ · 21 in reach" | Keep `★ My places` and starred areas at the top, as built in F4. |

Already on the Map, so nothing to move: the window verdict and ‹ › stepping (window control), confidence (`MapWindowPanel`), region write-up (`MapRegionPanel`, the same served gloss), hot-topic rows (map chips), tide (`MapTideStrip`), drive and Leave by (callout), sub-region doors (`SubRegionDoors` / `SubRegionActions` in `MapView`), favourites (callout, `RegionsJump`), and the eclipse sentence (`EclipseSpotLine`; only the decorative `DawnRace` track goes).

## Every door that opens the popup today now opens the Map
The popup opens from `WindowFirstHeatStrip` (cards), `WindowFirstShell` (Find's "Go to window", Coming up and window-key routes), `MapView` and `map/WindowControl`. Repoint every one:
- **A Glance card** → `selectTab('map')` with that window. Lens and origin are kept. Accessible name: "Open Saturday sunrise on the map". A past or travel-day card stays a non-button `div`, as today.
- **Find → a window**, on any tab → the Map with that window selected. This replaces "collapse and flash on Glance" (find-and-favourites F1); update that plan's table.
- **Coming up → a window** → the Map, not Glance.
- **The Map's own** "open the popup" affordances → removed. The Map is now the destination.
- **The aurora banner's "View on map"** already goes to the Map. Drop its "close the popup first" step.

## Delete list (computed from the import graph at `8d2ef6a`; re-run it on the branch)

**Components (whole files)**
`WindowSheetDialog.jsx`, `WindowRegionRail.jsx`, `WindowProseSlot.jsx`, `WindowSpotStrip.jsx`, `WindowSpotPeek.jsx`, `WindowPickDialog.jsx` (after its reason moves to the Map), `WindowAttributeRow.jsx`, `WindowTopicRows.jsx`, `WindowRowFieldMap.jsx`, `MovementMark.jsx`, `DawnRace.jsx`, `chart/WindowTideSparkline.jsx`.

**Hooks and utils (whole files)**
`hooks/useSpotPeek.js`, `hooks/useIsCoarsePointer.js`, `utils/windowSpotPeek.js`, `utils/firstClause.js`.

**Exports left with no live caller** (delete them; also check for in-module use first):
`confidenceUtils.scaleRgbaAlpha` · `dawnRace.formatRaceTime` / `raceModel` / `raceSentence` (keep whatever `EclipseSpotLine` still calls) · `heatField.kmPerPx` · `movement.movementChip` · `windowFirstCards.CONFIDENCE_VERDICTS` · `windowFirstMatrix.dayNumber` · `windowFirstRegions.activeFilterClauses` / `buildRegionRows` / `gateSpotsByRegion` · `windowFirstRows.CHART_H` / `CHART_W` / `topicFacts`. Then repeat: each deletion can orphan more.

**Shell state and dialog plumbing** (`WindowFirstShell.jsx`): `openWindowKey`, `openPick`, `openOverPopup`, `stackedOverPopup` and every branch that exists because a layer could stand on the popup; the `useBackLayer` entries for the popup and pick dialog; any `dialogHistory` entries, `mapForeignModal` cases and `selectTab` clean-up written for the popup. The rule "at most two dialogs stacked" becomes easier to hold. Keep the rule, but delete the code paths that only existed to enforce it over the popup.

**CSS** (`src/index.css`): about 130 class names are used only by the deleted files, giving about 256 selector hits. The prefixes are `wf-wsh`, `wf-race`, `wf-prose`, `wf-row`, `wf-peek`, `wf-region`, `wf-topic`, `wf-trow`, `wf-rr`, `wf-mchip`, `wf-film`, `wf-frow`, `wf-mgeo`, `wf-mhint` and a few singles. Check each class against the live tree before deleting it; some prefixes are shared.

**Tests** (`src/test/`)
- **Delete:** `DawnRace`, `DawnRaceBranches`, `WindowAttributeRowBranches`, `WindowProseSlot`, `WindowRegionRail`, `WindowRegionRailPicks`, `WindowSpotPeek`, `WindowSpotStrip`, `firstClause`, `firstClauseEdges`, `movementToneCascade`, `useSpotPeekBranches`, `windowSpotPeekEdges`, `WindowSheetDialog`, `WindowSheetDialogPicks`, `WindowSheetDialogWarnings`, and `WindowPickDialog*`.
- **Edit** (remove the popup cases, keep the rest; delete the file if nothing is left): `FavouriteSurfaces`, `LiteShapedTidePlan`, `WindowAttributeRow`, `WindowFirstBriefingPicksLock`, `WindowFirstResponsive`, `WindowFirstShellPicksRail`, `WindowFirstShellRegion`, `WindowRowFieldMap`, `WindowTopicRows`, `WindowTopicRowsTideLocked`, `mapChipFlipCascade`, `planScoreConsistency`, `smallUtilsBranches`, `unratedVerdictWords`, `viewportHooks`, `windowSpotPeek`.
- **Coverage floor:** removing tests and code together should keep `vite.config.js` thresholds. **Never lower them.** If a number falls, add tests for the new Map pieces.

**E2E** (`src/test/e2e/`): `smoke/drilldown-plan.spec.js` (rewrite as "card → Map → List → callout"), and the popup steps in `smoke/back.spec.js`, `smoke/door1-obstacles.spec.js`, `smoke/nginx.spec.js` and `a11y/a11y.spec.js`. The smoke gate is a required check.

**Backend:** a frontend property scan found no served field read only by the popup, and both Claude calls the popup shows are used elsewhere. Still sweep: any DTO field, builder branch or test that exists only for the popup goes, and so does its `api-endpoints.md` / reference line.

**Docs:** `docs/engineering/reference/plan-tab.md` has about 37 mentions of the popup. Rewrite the card-click section, delete the popup and pick-dialog sections, and fix the stacking and Back rules. Also update `map-tab.md` (List, Best bet why, the warning expand, Regions order) and `CLAUDE.md`'s two popup mentions (the Dialogs invariant). Mark earlier plans' popup-only parts as superseded with a one-line pointer here (e.g. `design_handoff_reach_field`'s `WindowRowFieldMap` part).

## User guides and landing (required in this series)
- **`landing/guide/at-a-glance.html`**: rewrite "Tap a card to open it…" (line ~78): a card opens the Map with that sunrise or sunset selected. Remove the popup bullet (~100) and any popup screenshot.
- **`landing/guide/map.html`**: add **List** (the ranked list), **Best bet · why**, the warning chip's full view and the best-first Regions menu. Explain how you arrive from a Glance card.
- **`landing/guide/weather-warnings.html`**: the "Window popup" entry (~lines 51–52, 66, 86) and the `shot-warn-popup` figure → the Map's warning chip and Coming up.
- **`landing/guide/your-places.html`**: lines ~44–45 mention "the region choices in a sunrise or sunset popup" and "the spot cards in a … popup". Change to the Map's Regions menu and the List.
- **`coming-up.html`**, **`reading-the-forecast.html`**, **`ask-photocast.html`**, **`getting-started.html`**: grep for "popup", "open a card" and "window sheet"; fix every hit.
- **In-app guide** (`utils/guide.js`, `guideSections.test.js`): delete section ids that pointed into the popup, and repoint any `GuideLink` that lived in it.
- **New screenshots** (webp) for the Map additions, from a local build. Delete the old popup shots.
- **Landing `index.html` and `faq.html`**: grep for "popup" and "tap a card"; fix any hit.

## Claude cost
**Binning the popup saves no Claude spend.** The region write-up is the briefing gloss (~$4.3 per 30 days); it also feeds the Map's region panel and the callout fallback, so it stays. The Best bet advisor (~$2.8 per 30 days) chooses the Glance Best-bet card, so it stays. Its `headline`/`detail` reason moves to the Map. **Owner option:** if the reason isn't wanted on the Map, drop `detail` from the advisor's output schema to save its output tokens, a few tenths of a dollar a month. Record the decision in `claude-cost-ledger.md` either way.

## Phases (one PR each)
- **G1, Map gains the four pieces:** List, Best bet · why, the warning expand, Regions best-first. The popup still exists, so nothing is lost if G2 slips.
- **G2, repoint every door and delete:** cards, Find and Coming up go to the Map; delete the whole list above, including CSS, tests and E2E. Update the docs.
- **G3, guides and landing:** everything in the guides section. Release only after G3.

## Acceptance
- Tapping any future Glance card lands on the Map with that window selected and the same lens. Back returns to Glance.
- Find "sat sunrise" on any tab lands on the Map with that window. Coming up window links do the same.
- `grep -rn "WindowSheetDialog\|WindowPickDialog\|openWindowKey\|stackedOverPopup\|wf-wsh" frontend/src` returns nothing.
- Re-running the import-graph orphan check finds **no** file reachable only from deleted code, and no export without a live caller among those listed.
- Frontend coverage thresholds are unchanged or higher; lint, tests, audit, build and the E2E smoke are green.
- `grep -rni "popup" landing/` returns only uses unrelated to the window popup (e.g. the map callout, if the guides call it that), each checked.
- Lite: List, the warning expand and Regions work as for Pro. Best bet · why follows today's Lite rule for the pick.

## Files
- `Glance to Map.html`: a clickable prototype. Tap a card to reach the Map; try ☰ List, ◎ Best bet · why, the warning chip and ◎ Regions.
- `CLAUDE_CODE_PROMPT.md`: a ready-to-paste prompt.

---

## G1 implementation notes (from reading the code at `8d2ef6a`)
- `WindowFirstBriefingContext` already exposes `windowCards`, `reachLens`, `ratingLens`; `MapView` can find the selected window's card by `date` + `eventType` (see `utils/windowKeys.js` `windowKey`). `typesByName` is `buildLocationTypeMap(locations)` (shell line ~1385).
- List: `WindowSpotSheet` props are `card, barTierId, barFloorId, openTierId, reachLocked, typesByName, onClose, onOpenSpot, escapeEnabled`. Mount it from `MapView` beside `.wf-map-chrome-tr` (desktop) and as the phone bottom sheet; a row closes it and opens that place's callout.
- Best bet why: `WindowPickDialog` shows `pick.headline` + `pick.detail`; the pick comes from the window card. Render it inside `map/WindowControl.jsx`.
- Warning expand: `MapWarningChips` currently renders `WarningStrip` chips (each links to the Met Office page); expand to `WindowWarningRow` with `card` built from the warnings for that window.
- Regions best-first: `RegionsJump` + `utils/regionsJump.js` (`buildRegionBestIndex`); keep `★ My places` and starred areas on top.

---

# Review corrections (2026-11-20, six adversarial lenses). These SUPERSEDE the handoff text above where they differ.

## Phasing (revised)
G1 Map gains the four pieces → **G2a** repoint every door (popup unreachable but present) → **G2b** delete the popup, shell state and orphaned components/hooks/utils → **G2c** delete orphaned exports, CSS by exact class, tests, E2E, comment scrub, docs → G3 guides and landing. The one-line at-a-glance.html fix moves into G2a so production guides are never stale. Each phase ships tests with its code (per-file coverage floors are 95% lines / 90% branches on every `src` file, plus the global floor; never lower).

## G1 design
- **List.** Keep `WindowSpotSheet` a **shell-owned Modal** (`sheetKey`/`sheetCard`, `useBackLayer`); it is then foreign to `.wf-map-tab`, so every Map Escape/outside-press rule stands down via `foreignModalOver`. `MapView` gets an `onOpenList` callback carrying the Map's selected window key; close the Map's menus when it fires; gate it with `foreignModalOver(mapPaneRef.current)`. Do NOT mount it inside `MapView`. `selectTab` already clears `sheetKey`. Add the List to `mapForeignModal`'s doc and pin a test that `foreignModalOver` is true while open. A row selects the spot in place (not `handleSpot`/`openMapTab`) and closes the List; the callout is not a Back layer, so it stacks freely.
- **Card lookup.** `windowCards` has no card for past, travel-day, D-13 filler and night rows: hide/disable ☰ List with an explicit reason there. Add a `windowCardsByKey` lookup (`windowKey`) and pass `selectedCard` to `MapView`.
- **Floors.** The Map's rating floor is `minStars` (default 3, persisted `mapFilterMinStars`), NOT the context `ratingLens` (default Any). Pass the Map's `minStars` and tier as `barFloorId`/`barTierId`; intersect `card.allSpots` with the Map's drawn/in-scope set (focus lens, area lens, My-area scope, no-drive-time filter) so List counts match Map counts. Test count parity.
- **Phone.** `.wf-map-chrome-tr` is desktop/tablet only. On a phone add a "See all N" row in the peek sheet's Windows section (no fifth peek button) hosted in a `BottomSheet`, not the centred Modal. Gate on `isMobile`; test 375/390px; prove desktop/tablet DOM unchanged.
- **Best bet · why.** The Map receives only `pickKind`/`pickRegion` today (strip fold drops headline/detail); carry the full served `card.pick` via `selectedCard`. Render nothing when `pick` is null (Lite: server nulls it; away-origin scoped-out; orphan "also"). Never re-pick or re-scope on the client. NB the pick headline/detail are the **region gloss** (`PlanWindowProjector.candidate`), not the advisor. Put the pill outside `WindowControl`'s flex row (crowds the phone pill); `aria-expanded` + `aria-controls`, no new `role=status` (`#wf-win-status` exists).
- **Warning expand.** Do not fake a `card`. Extract `WindowWarningsBody({warnings})` (WarningDetail → WarningAdviceLink → WarningFooter) and keep `WindowWarningRow` as the card adapter (the shape `WindowComingUpWarning` uses). Input is `warningsForMapWindow` (polled list). Chip stays a Met Office link; the toggle is a **separate per-warning button** (a chip is never inside a disclosure button); attribution visible open or closed; no toggle for the stale-only state. Render the detail in a panel hung UNDER the chrome group (or the peek sheet on phone), capped height, never inside the group (would re-place map labels; the 504px label-obstacle bound).
- **Regions best-first.** Do NOT reuse the popup's `buildRegionRows`. Build `panelRows` for the jump menu too (`windowPanelOpen || openMapMenu === 'jump'`); add a pure `regionsJump.sortBestFirst(jumpRows, panelRows)` over the existing `byMeanThenName`, taking tier/mean/best from the one `buildRegionVerdictIndex` record (not canopy-unfiltered `buildRegionBestIndex`). Wording is the Map's own "k of m at 4★+", NOT "N in reach" (`reachWordHolds` honesty rule). Nights have no region verdicts: keep the current order. Shaped bodies keep `buildPanelRegionRows`' pick-first order. `★ My places`/starred stay above the sort (already a separate group). Too-few-rated sorts last and is never called Poor. Verify Lite's `panelRows` are served. Two-line rows; test 390px.

## Back
A tab switch pushes no history entry, so "Back returns to Glance" is unmet today. Add one shell `useBackLayer({open: mapArrivedFrom != null, mode: 'close', onBack: () => selectTab(from)})`, set only by a Glance-card / Find / Coming-up door, cleared by any manual tab press, `selectTab` and the breadcrumb return. It counts toward `MAX_LAYER_DEPTH = 2`: refuse opening the List over Ask (and Ask over the List) or raise the cap deliberately. New invariant: add to CLAUDE.md's Back line and a `back.spec.js` case. A Glance card uses the existing `showOnMap` (`kind: 'event'`) door; `openMapTabFromPlan` writes `minRating` (persists to `mapFilterMinStars`), so "same lens" is partly false and the plan must say so.

## Doors (corrected)
Real popup openers: `WindowFirstHeatStrip` card `onClick` (`onOpenWindow`), `goToPlanWindow`, `pickFindWindow` (its Map-tab branch already calls `openMapTab`). `MapView` and `map/WindowControl` open no popup (nothing to repoint). Missed: `tapTopic`/`tapWarning` in HeatStrip call `onGoToPlanWindow`; decide their Map destination. "Coming up → a window" has no door today (`goToPlan(date)` only switches tab): decide whether to add one. Aurora "View on map" must KEEP `closeShellDialogs`; only drop `openWindow(null)` in `openMapDoor`.

## Delete list (corrected)
All 16 whole files confirmed orphaned once the popup goes. Wrong entries: `dawnRace.formatRaceTime` and `movement.movementChip` are live (keep; drop `export` only on movementChip); `windowFirstMatrix.dayNumber`, `windowFirstRows.CHART_W/H`, `windowFirstCards.CONFIDENCE_VERDICTS` are used in-module (un-export only); `windowFirstRegions.buildRegionRows` is NOT deleted blindly (`buildRegionSeries` is live in the same file; G1 does not reuse it); `activeFilterClauses`, `scaleRgbaAlpha`, `kmPerPx`, `topicFacts`, `raceModel`, `raceSentence` confirmed dead. Missed: `windowFirstRows.buildWindowRows`/`tideSparkline` and `card.rows`; `heatField.centroid`/`aspect` (keep `drawGeo`); many tests that import dead exports (dawnRace*, confidenceUtils*, heatField, reachRings, windowFirstRegions*, windowFirstRows, movement*, ScoreBar, MapCallout, LocationFourDaySheet, WindowFirstShell*, HeatStrip, mapDoors). **Shell:** `openOverPopup` and `stackedOverPopup` are shared by the List, four-day sheet, area sheet and Find: RENAME (`openSheetLayer`, `stackedOverLayer`), do not delete; only `openPick`, `openWindowKey`, `emphasis*`, `focusedRegion`, the ←/→ stepping and `onOpenPick` go. Acceptance grep must be reworded to "popup-only" names and the comments in `planConflicts.js`, `WindowFirstLensBar`, `AuroraBanner`, `locationSheet.js`, `Modal.jsx`, `windowFirstCards.js`, `MapWindowPanel` scrubbed. **CSS:** delete by exact class only. DANGEROUS shared prefixes: `wf-peek-bar*` (ScoreBar), `wf-topic-strip*`, `wf-topic-rings-pane`, `wf-topics*` (Map). Safe: `wf-wsh*`, `wf-race*`, `wf-prose*`, `wf-trow*`, `wf-rr*`, `wf-rrail`, `wf-frow*`, `wf-film*`, `wf-rowmap`, `wf-rows`, `wf-mchip*`, `wf-mgeo`, `wf-mhint`, `wf-peek-chip`.

## Claude cost (corrected; supersedes the handoff's section)
- Binning the popup alone saves **$0**: the Map region panel and callout read the region gloss headline AND detail.
- The Best-bet advisor does **not** choose the Glance card (`PlanWindowProjector.selectPicks` is deterministic); it writes `bestBets`, which no frontend surface reads. It feeds `pipeline_run_pick` (admin cycle comparison), the replay tool and honesty/fallback paths.
- Follow-up PRs (separate from G1–G3): **(1) skip gloss for day 5**, which the briefing glosses (`BRIEFING_WINDOW_DAYS = 5`) but nothing renders (`PLAN_OWNED_DAYS = 4`): ~$0.4–0.9 per 30 days, no prompt change, guard the date rollover. **(2) Retire the advisor call**: ~$2.8 per 30 days, owner decision; first trace its aurora link (`AuroraRegionSelector`, `BestBetAuroraPromptRegressionTest`) and confirm the admin comparison is unused. Dropping only `detail` (~$0.3) is not recommended. Figures are estimates; confirm with `measured-costs.sql`. Record the decision in `claude-cost-ledger.md`.
