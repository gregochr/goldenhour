# Overlay convergence (O-6) — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order. Each phase lands as its own
reviewed commit and PR before the next starts; the plan's **§0 Status block is the source of truth
between sessions**. If a session dies mid-phase, start a new one with the same prompt: step one of
every prompt is reading the current state from the repo.

The plan: `docs/engineering/overlay-convergence-plan.md`. **§5 D1–D6 must be confirmed by the owner
before C1 starts** — a session that finds §0 still saying "RECOMMENDED, not yet taken" stops and
says so.

**The three things every session must internalise:**
1. **This is a retirement, not a re-skin.** The overlay is deleted in C5; nothing is built inside it,
   and no phase before C5 touches an `overlayMode` branch except to leave it alone.
2. **A capability moves BEFORE its door does.** C1 (handoff fields), C2 (detail parity) and C3 (a tab
   with no solar forecast) exist so that C4 can move seven doors without a reader losing anything the
   overlay showed. Do not reorder.
3. **One structured door, `source:'plan'`.** The legacy `handoff*` props are never extended; they die
   with the overlay.

**Multi-agent note.** The one multi-agent step is the pre-commit adversarial review: ~5 read-only
prosecutor lenses over the diff (runtime, "did a SOLAR window's pixels or props move", test quality,
accessibility incl. focus after a door moves tabs, project conventions) and one refuter per surviving
charge, defaulting to REFUTED without citable evidence. Review agents never edit, never `git
checkout --`, never `git stash`. Paste the phase's plan section and §4/§5 into every reviewer's
prompt.

---

## C1 — the handoff contract

```
Read docs/engineering/overlay-convergence-plan.md in full; confirm §0 says D1–D6 are TAKEN (stop if
not) and `gh pr list --state open` shows no overlay/handoff/breadcrumb PR. Read the existing door
end to end: App.jsx `openMapTabFromPlan` and `mapTabHandoff`, WindowFirstMapPane's `handoff` →
`planHandoff` mapping (and its nulling of the legacy `handoff*` props), MapView's nonce-keyed
`planHandoff` effect and its `askWindow` effect (the model for selecting a night row),
components/map/MapBreadcrumb.jsx and its `onReturnToPlan`/`clear`, utils/mapOverlay.js
(`normalizeMapTrigger`, `buildMapOverlay` — the seven trigger shapes), and the tests
MapViewPlanHandoff.test.jsx, AppOpenMapTabFromPlan.test.jsx, mapOverlay.test.js.

Build plan §3 C1: move `normalizeMapTrigger` (+ the headline logic as `doorHeadline`) to
utils/mapDoor.js with its 25 tests re-targeted; extend the structured handoff with `night`,
`focusNames`, `focusPoints`, `filterAction`, `darkSky`, `title`, `caption` per the plan; apply each
in MapView's `planHandoff` effect; the breadcrumb shows title · caption and names every lens,
`clear` removes the temporary lenses (and the origin, as today) and restores the field whole. NO
door moves in this phase and NO `overlayMode` branch changes — utils/mapOverlay.js keeps a
re-export so the overlay still works. Tests for every new field, the breadcrumb text per door kind,
and a `focusNames` lens on the heat field that `clear` fully restores.

Gate on exit codes: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
Adversarial review (5 lenses + refuters, read-only). Changelog `changelog.d/<date>-overlay-c1.md`
(`### Added — the Plan→Map door carries every overlay trigger (overlay convergence, C1)`); §0 row;
commit on `feature/overlay-c1-handoff`; do not push.
```

## C2 — detail parity

```
Read the plan §1 #3/#6, §3 C2, §4 #2/#6/#8, §5 D3 (the owner's FINAL list — build exactly it).
Read MarkerPopupContent.jsx end to end (every section the list names, and `getForecastDetail`),
then MapCallout.jsx + utils/mapCallout.js and LocationFourDaySheet.jsx + utils/locationSheet.js, and
their tests. For each listed fact: find its served source (the DTO/briefing field the popup reads),
add it to the location sheet's window row expansion (or the callout where the list says so) in that
surface's own vocabulary and style — never by importing MarkerPopupContent or its CSS — with
PropTypes and a test per fact (present when served, absent when not, LITE/PRO gating identical to
the popup's). The live TideIndicator is NOT ported; ADMIN Run forecast is NOT ported (O-7). Leave
MarkerPopupContent untouched (PromptTestView still mounts it).

Gate, review (one lens: byte-identical sheet/callout output for a location with none of the new
facts), browser check per plan §8 (owner signs in), changelog
(`### Added — the location sheet and callout carry the forecast detail the overlay popup showed
(overlay convergence, C2)`), §0 row, commit on `feature/overlay-c2-detail`, no push.
```

## C3 — a Map tab without a solar forecast

```
Read the plan §2 (the `hideDayCard` precedent), §3 C3, §4 #4, §5 D4. Read App.jsx's
`hasForecastData` and every `hasForecastData ?` gate (the Map pane mount, the door enablement, the
overlay hatch), MapView's `auroraAvailable`, utils/mapEvents.js `nightRow`/`bestOfNight` and the
D-14 "night rows only where stored results exist" rule, WindowControl's empty-roster behaviour, and
the tests MapViewAuroraNight, MapViewAuroraLiveNight, mapEvents.test.js.

Build §3 C3: the Map pane is offered when `hasForecastData || auroraStatus?.active` (PRO/ADMIN —
LITE has no aurora status); with no solar rows the window control holds the night row(s) alone and
the tab opens on the aurora row; a LIVE alert with no stored results gets a live-night aurora row
(`bestOfNight` over the live `auroraScores`); the field (A2), the viewline, the callout and the
peek sheet all work on it; the landing card does not open (nothing solar to say). Tests: a
no-forecast + active-alert mount shows the tab with one row; a LITE account is unchanged; a quiet
alert (inactive) with no forecast still withholds the tab.

Gate, review, browser check with Simulate STRONG against an empty local forecast, changelog
(`### Changed — the Map tab opens on an aurora alert even before any sunrise or sunset is forecast
(overlay convergence, C3)`), §0 row, commit on `feature/overlay-c3-aurora-only-tab`, no push.
```

## C4 — move the doors

```
Read the plan §1 #1/#2, §3 C4, §4 #3/#4/#7, §6. Confirm C1–C3 merged. Read every producer named in
§1 #1 and the shell's `openMapTab` (which closes Plan dialogs first — keep that), App.jsx's
`handleShowOnMap`/`handleAuroraViewOnMap`/`openFullMapTab`/`mapOverlay` state and its
settings-edge close.

Move the doors in the plan's order, each through `openMapTab(openMapTabFromPlan(...))` with the C1
trigger shape; the banner last, with `night`. Delete `handleShowOnMap`, `handleAuroraViewOnMap`,
`openFullMapTab`, the `mapOverlay` state, the settings-edge overlay close and the `<MapOverlay>`
mount (MapOverlay.jsx itself and MapView's branches go in C5 — but with no mount, their tests that
render the overlay through App go now). A door must NOT move the Plan tab's selected date. On the
Map tab itself the banner's press selects the night row and changes nothing else. Tests: one per
door (right window, right callout/lens, breadcrumb text, Plan date unmoved, Plan dialogs closed,
focus lands on the map pane not <body>); App.test.jsx's two overlay cases re-targeted.

Gate, review (a11y lens on focus after each door), browser check of all seven doors per plan §8 at
1280 and 390, changelog (`### Changed — every "Show on map" opens the Map tab; the Plan-tab map
overlay no longer opens (overlay convergence, C4)`), §0 row, commit on
`feature/overlay-c4-doors`, no push.
```

## C5 — delete the overlay

```
Read the plan §1 #3/#5/#6, §3 C5, §6. Confirm C4 merged (no mount of MapOverlay remains:
`grep -rn MapOverlay frontend/src` finds only the component and its test). Then delete, in this
order, running the full gate after each: (1) MapOverlay.jsx + test, the rest of utils/mapOverlay.js;
(2) MapView.jsx's overlay render branches (context bar, drawer, both MarkerPopupContent mounts,
`'medallions'` toolbar, phone BottomSheet) and their CSS; (3) overlay logic (HandoffPopupController,
PopupResizer, the overlay /drive-times fetch → `driveMinutesFor` reads `reachById` only, the
`retry: !overlayMode` and the other `overlayMode` forks, `zoomSnap`'s branch, `focus`/
`emphasiseLocationName`/`overlayMode`/legacy `handoff*` props + PropTypes + the WindowFirstMapPane
nulling of them); (4) ForecastTypeSelector.jsx + tests after relocating `EVENT_TYPE_LABELS` to
whoever else reads it; (5) HourlyComfortTable's hidden-header mode; (6) `fetchTravelDayRanges` if C2
left it without a reader. KEEP MarkerPopupContent (PromptTestView), markerUtils, makeMarkerIcon.
Re-target or delete the ~50 overlay test cases per plan §1 #5 — delete only the "absent on overlay"
negatives; re-target anything that pins behaviour the tab also has. `grep -rn overlayMode
frontend/src` must find nothing when you are done.

Gate, review (lens: every solar-window and aurora-window test still green, no tab behaviour moved —
compare `MapViewHeat`/`MapViewPlanHandoff` outputs before/after), changelog (`### Removed — the
Plan-tab map overlay (overlay convergence, C5)`), §0 row, commit on `chore/overlay-c5-delete`,
no push. Report MapView.jsx's new line count.
```

## C6 — docs sweep

```
Read the plan §3 C6 and grep the repo for `overlay`, `overlayMode`, `MapOverlay`, `frozen`,
`MarkerPopupContent`, `ForecastTypeSelector`, `medallion` in CLAUDE.md and docs/engineering/*.md.
Rewrite each sentence the series made false as a dated retirement note (never delete history that
explains a decision); mark map-tab-v2-plan.md O-6 DECIDED with the PR numbers; close
v1-retirement-plan.md §8 item 9's overlay route; update the plan's §0 to COMPLETE. Changelog
(`### Docs — the overlay convergence recorded (overlay convergence, C6)`); commit on
`docs/overlay-c6-sweep`, no push.
```
