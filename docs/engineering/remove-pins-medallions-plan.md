# Remove the Map tab's Pins mode and the location medallions

Status: R1 merged (#1378); R2 built. Plan, 2026-10-24. Owner request: "Can we get rid of all pins and medallions? They're a relic from the past."

## 1. What is being removed, and what is not

**Removed**

- **Pins mode**: the Heat | Pins segment (desktop toolbar `wf-map-view-heat`/`wf-map-view-pins`, phone peek
  sheet Layers "Show" row `wf-map-peek-view-*`), `heatView` state in `MapView.jsx`, `heatPinsOn` and everything
  that only exists for it: `components/map/PinsLayer.jsx`, `MapLegendPanel`'s `pins` variant,
  `MapHeatLayer`'s `stoodRated` prop and Pins else-branch, `heatField.js` `drawStandDownTiles`,
  `MapTopicRings`' `inPins` / `PINS_RING_RADIUS`, the `!heatPinsOn` azimuth guard, the `|| heatPinsOn` arms of
  `bigSeasShown` / `hwRingsOn` / `ringsAvailable` (they reduce to `heatOn`), the `spotOf` `isStandDown` field if
  PinsLayer is its only reader, the `.wf-pin*` CSS.
- **The medallions**: the Leaflet `<Marker>` location discs `MapView.jsx` draws for every visible location, with
  `makeMarkerIcon` and its cache, `markerStyles` and its injecting effect, `getContentProps`, the `Marker` import,
  and `markerUtils.js`'s `buildMarkerSvg` / `buildStandDownSvg` / `markerLabelAndColour` / `scoreColour`.
  Then everything in `MapHeatLayer.jsx` that exists only to hide them (`MARKER_INTERACTIVE_ALPHA`,
  `FADED_PANES`, `MARKERS_INERT_CLASS`, `markersAreInteractive`, `applyMarkerFade`, `restoreMarkerPanes`, the
  unmount restore, their comment block) and the `.wf-markers-inert` CSS.

**Why the medallions can go without a replacement.** They are already invisible and inert in production:
`heat.enabled` is `heatSpots.length > 0` (`WindowFirstMapPane.jsx`), heat is offered in every mode
(solar, astro, aurora — `MapView.jsx` `heatOffered` doc), and `MapHeatLayer` holds the marker panes at opacity 0
with `inert` whenever it is mounted. The only state where they show is a roster with zero joinable locations,
where the centre pill already speaks. **Except at first load**: the owner sees the medallions flash when the
map first opens (2026-10-24). They are mounted before `MapHeatLayer` (lazy, behind `d3-geo`) has mounted and
applied the fade, and before `heatSpots` exists at all, so for that interval they are visible. That flash is
the user-facing defect R2 fixes; R2's browser check must load the map cold and confirm nothing appears before
the field. The click target that opens the callout in Heat is the `MapLabels` chip
(`selectMapLocation`), which stays.

**Kept (named "medallion" in code but not a map marker)**

- The "◎ Best bet" / "Also" pick chip: `WindowControl.jsx` `Medallion`, `MapLandingCard` `wf-land-medallion`,
  `MapWindowPanel`, `MapRegionPanel`, and its CSS. It is the window control's pick vocabulary, not a pin.
- `MarkerPopupContent.jsx`: its production consumer is the admin `PromptTestView`.
- `markerUtils.js`'s `STAND_DOWN_COLOUR` (used by `FiltersPopover`) and `NO_DATA_COLOUR` if any survivor reads it.
- `MapLabels` home label, the ⌂ control, `MapTopicRings` (its own pane), Ask picks, the counts footer.
- The `legendHandoverFraction` / `fadeAt(zoom).markers` half: delete the `markers` half only if nothing but the
  marker fade reads it after R2; otherwise leave it.

## 2. Phases (one PR each, branched from updated `main`)

### R1 — remove Pins mode

1. Delete the `heatView` state and both toggle segments. The desktop toolbar cluster keeps the unscored line
   and the colour key; the phone Layers pane keeps Regions / Filters / legend rows. Fix the stale peek-sheet
   comment that mentions Pins.
2. Replace `heatOn` with `heatOffered` semantics (field on whenever heat is offered); delete `heatPinsOn` and
   simplify each consumer. `MapHeatLayer` is always `fieldEnabled` when mounted, so remove the prop or pin it
   true, and drop the Pins else-branch and `stoodRated`.
3. Delete `PinsLayer.jsx`, its lazy import and mount; `MapLegendPanel`'s `pins` variant (keep the heat
   variant); `drawStandDownTiles`; `MapTopicRings` `inPins`.
4. Delete the `.wf-pin*` CSS and any segment CSS only the toggle used (`.wf-seg` stays if anything else uses it —
   grep).
5. Tests: delete `PinsLayer*.test.jsx` and the Pins describe blocks (`MapHeatLayer.test.jsx` "Pins mode /
   fieldEnabled", stand-down-tiles cases, `MapViewHeat` "azimuth dropped in Pins mode", the Pins legend cases,
   `MapLegendPanel` pins cases, `MapTopicRings` inPins cases, `heatFieldStandDown` drawStandDownTiles). In the
   ~11 files that click `*view-pins*`, delete the Pins arm of the test, never the heat arm. Remove the stale
   `vi.mock('.../PinsLayer')` lines. Fix `readFileSync` cascade tests that read `.wf-pin` selectors
   (`mapTopicDimCascade`, `askMapCascade`, `MapViewMobileTideRule`). Add one test that the Map view segment is
   gone on desktop and phone (`queryByTestId('wf-map-view-pins')` null with heat offered, with the field mounted
   so the absence is not vacuous).
6. Docs: `docs/engineering/reference/map-tab.md` — remove or rewrite every Pins passage (azimuth D-9 note,
   P10, chips/rings, HW event, stand-down hatch, big seas, verdict/picks, peek sheet, past windows). The
   no-clustering note argues from Pins: keep the rule, reword the argument to the heat field alone.
7. `changelog.d/20261024-remove-map-pins-mode.md` (`### Removed — Map tab Pins mode`).

### R2 — remove the hidden location medallions

1. Delete the marker loop and its helpers in `MapView.jsx`, the `Marker` import, `markerStyles` and its effect.
2. Delete the marker-pane fade/inert machinery in `MapHeatLayer.jsx` and `.wf-markers-inert` CSS.
3. Shrink `markerUtils.js` to the surviving constants (or move them beside their reader); keep the export names.
4. Tests: delete `MarkerIcon.test.jsx`, `markerInertCascade.test.jsx`, the `MapHeatLayer` marker-pane
   assertions. Rewrite (do not delete) the tests that use medallions as a probe of which locations are visible
   (MapViewStaleWindow, MapViewAuroraLiveNight, MapViewAuroraLiveFetch, MapViewAstroNightFetch,
   MapViewNightScoresLoading, MapViewStarFilter, MapViewTypeFilter, MapViewAstro, MapViewHeat marker-swap) so
   they assert the same filter/visibility behaviour through what is still rendered (the `heat` spots handed to
   `MapLabels`/the heat layer, or the counts footer). If a probe's behaviour no longer has any user-visible
   output, say so in the PR and delete it. Remove stale `vi.mock` of `markerUtils` where nothing imports it.
5. E2E `test/e2e/forecast.spec.js` waits for `.leaflet-marker-icon`: point it at a `MapLabels` chip.
6. Docs: map-tab.md medallion passages; comment-only mentions in `MapLabels.jsx`, `heatHandover.js`,
   `heatGeometry.js`, `WindowFirstMapPane.jsx`, `standDown.js`, `MapLegendPanel.jsx`, backend
   `BriefingEvaluationController.java` / `ForecastListDto.java` ("marker medallion" comments). No backend
   behaviour changes.
7. `changelog.d/20261024-remove-map-medallions.md`.

## 2a. Review corrections (plan review, 2026-10-24)

**R1**
- `AskPeekStateTable.test.jsx` (:363, :1347–1361) uses `wf-map-peek-view-pins` only as a focusable control in
  the Layers body to test Escape focus restoration. Re-point it at another Layers-body control (the Regions or
  Filters row); do not delete those tests.
- `MapViewHeat.test.jsx:503` ("does not remount the marker layer on the Heat↔Pins toggle") is deleted in R1.
- `MapViewMobileTideRule.test.jsx:287` asserts MapLabels AND PinsLayer in one test: keep the MapLabels half.
  Its `readFileSync` at :460 has no `.wf-pin` selector; leave it. The real `.wf-pin` readers are
  `askMapCascade.test.jsx` (:56, :81, :120, :142–144, :180) and `mapTopicDimCascade.test.jsx` (drop the
  `['pin','wf-pin']` row of its `describe.each`).
- map-tab.md passages also to fix: :8 (v2 summary, "Regions/Heat-Pins/Filters bar"), :10 ("never on Pins"),
  :12 ("shows in Pins view", the `chromeKey` for `PinsLayer`), :31 ("`PinsLayer` places only the HOME label").
- Fix the comment at `MapHeatLayer.jsx` ~:515 that names PinsLayer.

**R2**
- Most medallion-probe test files render `MapView` with no `heat` prop, so `MapLabels` (gated on `heatOn`) and
  the counts footer (gated on `heatOffered`) are not mounted there. Each rewrite needs a real `heat` fixture
  (`enabled: true`, `spots`/`areaSpots` matching `locations`) and a `MapLabels` mock that captures `spots`, in
  the pattern of `MapViewStaleWindow.test.jsx:87` and `MapViewHeat`'s `labelSpotsProps`. Map
  `markerLabelAndColour(rating)` spies to `spots[].rating`, and marker counts to `spots.length` or the footer's
  N. ⚠️ The footer N is `scopedVisibleLocations.length` and excludes the selected location and Ask picks that
  `labelSpots` appends; the two counts are not interchangeable.
- Add these to the rewrite list (their marker arm is the only coverage of live behaviour):
  `MapViewDriveOverride.test.jsx:284–293` (drive filter under an away origin), 
  `MapViewAuroraUnavailableFloor.test.jsx:351–366` (admin "unknown" lens and its switch-off),
  `MapViewAuroraOnlyTab.test.jsx:164` ("draws the live place on the map").
- Delete `MapViewHeat.test.jsx:1526` (LITE premium arcs on a marker: medallion-only).
- `NO_DATA_COLOUR` goes (its only other reader was PinsLayer). `fadeAt(zoom).markers` STAYS
  (`legendHandoverFraction` feeds `MapLegendPanel`).
- e2e `forecast.spec.js:66–110` is a whole "marker interaction" describe: re-target it to a `MapLabels` chip.
- Docs also: `conditions.md:6, :8` (🐾 / 💦 marker), `roles-and-gating.md:55` (marker SVG arcs), map-tab.md
  :15 and :29. CLAUDE.md's no-clustering line stays true; leave it.
- No frontend coverage threshold exists, so deletion cannot fail a gate; coverage loss is a review question only.

**Second review (hidden dependencies), 2026-10-24**

R1:
- After the segment goes, `wf-map-toolbar` can render empty (`windowUnscored && !unscoredLineShown`, e.g. a
  High-water row). Gate the toolbar div on having a child to render. The "segment absent" test must use a
  state where the colour key shows.
- Dead after R1, delete: `standGround.rated` (read only via `stoodRated`), the `spotOf` `isStandDown` field and
  `MapLabels`' `isStandDown` propType (its comment says PinsLayer), PinsLayer's `glow` prop plumbing. Keep
  `isStandDownLocation` (filters use it) and `heatField.js` `paintStoodOnTiles` (Heat's hatch).
- Pins was the only per-location stand-down fill (`STAND_DOWN_COLOUR`). Heat shows stand-down by the hatch,
  region labels, legend key, phone ground row and footer count: accepted, no new visual. The admin
  `FiltersPopover` "— stand-down" swatch (:143) keys a colour nothing paints any more: render it as the hatch
  swatch the Legend panel uses for stand-down (reuse that class/markup) instead of the `STAND_DOWN_COLOUR`
  dot. If that is not cleanly reusable, keep the dot and say so in the PR.

R2:
- **Zero-joinable state.** `buildHeatSpots` drops locations with no `regionName`, so a roster where no location
  joins has `heatOffered` false with locations present, and the medallions are the only thing on the map. R2
  must not leave a silent blank map there: when `!heatOffered && visibleLocations.length > 0`, show the centre
  pill (same component and styling as the no-forecast pill) reading "No places to draw on the map yet". Test it.
- `hwEventSweep.test.jsx:44–45, 508–521` reads marker `icon.options` to prove High water borrows no sunset arcs.
  The arcs exist only on medallions: delete that half and say so in the PR; re-probe the rating half through
  `MapLabels` spots if it has one.
- e2e `forecast.spec.js` uses `.leaflet-marker-icon` four times (waits :70–72, :98; force-clicks :105, :110):
  all move to a `MapLabels` chip.
- Sweep the ~15 test files that only `vi.mock` `Marker` / `markerUtils` and drop the mocks.
- Stale comments to fix: `MapView.jsx` ~:2305, :2417, :4183, :6707–6713 (marker click still bound);
  `MapLabels.jsx:36–41` and `index.css:3812–3817` (claim a nothing-scored window shows medallions);
  `heatHandover.js:30–34`; `scoreRamp.js:27`; `windowFirstSpots.js:46`; `index.css:171`.
- Only `Marker` leaves the react-leaflet import; `L` stays.

## 3. Gates

Each phase: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`, all
green. R2's backend edits are comments only; run `./mvnw checkstyle:check` if a Java file is touched.
Browser check against the local backend (port 8083) where possible: toolbar has no segment, field renders,
callout still opens from a label chip, phone Layers pane intact.

## 4. Risks

- A test file asserting a heat behaviour only through its Pins arm loses coverage: each deleted Pins arm must be
  checked for a heat-arm equivalent.
- Lazy-absence tests: an absent segment must be asserted with the toolbar rendered.
- Phone-only changes stay phone-only; desktop toolbar loses only the segment.
