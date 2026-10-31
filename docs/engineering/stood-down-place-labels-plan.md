# Stood-down place labels on the Map tab — plan

Status: planned 2026-10-31, revised the same day after a six-lens adversarial review (placement,
data, phone/a11y, visual/copy/roles, tests, performance/architecture). One phase, one PR.

## 1. Why

On 2026-10-31 Tynemouth's sunrise was stood down three runs running for poor visibility
(3.1 km by the 02:58 run). It was right: the coast there was in thick mist. On the Map tab the
place was only hatched ground — zoomed in, a hatched coastline with no name on it — while
Bamburgh 70 km north carried a 4★ chip. Pressing such a place (where possible) opened a callout
headed **"Poor"**, the same word a 1–2★ place wears.

Today a stood place is named only by the region stand-down label (`standDownRegionItems`,
`utils/mapLabels.js:157`), only when the WHOLE region stood down and the map is zoomed out
(`REGION_LABEL_MAX_ZOOM` = 11.2). The admin-only Filters › "stand-down" toggle (`showStandDown`)
turns stood places into ordinary star-less chips; it stays as it is.

Owner decisions (2026-10-31):
- **Stood places are named, never scored.** A stand-down is not a 1★: the model was never asked.
  No star, no ramp colour, no heat contribution.
- **"murk" → "visibility"** as the `LOW_VISIBILITY` cause word everywhere (region labels too).
- **"Stood down", not "Poor", for a single place with no star whose served verdict is
  STAND_DOWN.** Scoped by the facts: `DisplayVerdict.STAND_DOWN` means "rated 1–2★ OR triage
  stand-down with no rating" (`DisplayVerdict.java`). The one home of the unrated-slot word,
  `unratedSlotWord` (`utils/locationSheet.js:466`), runs only when a slot has no rating, so its
  STAND_DOWN branch is exactly the stand-down case. Change it there: the Map callout and the
  location sheet's window rows follow. Region- and window-level "Poor" (`VERDICT_LABEL`) and a
  rated slot's "Poor" are unchanged — they mix low ratings. Ask's pick cards are out of scope
  (a pick is a recommendation; an unrated STAND_DOWN pick does not arise).

## 2. What the reader gets

From zoom **≥ 11.2** (exactly where region stand-down labels stop), each weather-stood place in
view that fits gets a muted label button:

```
desktop/tablet:  [hatch] Tynemouth  stood down · visibility
phone (tiny):    [hatch] Tynemouth  stood down
```

- **Cause word**: `STAND_DOWN_CAUSE_WORD[reason]` — cloud / rain / visibility. **`GENERIC` and any
  unknown reason print no cause** ("stood down" alone): a Lite reader's tide stand-down is served
  as GENERIC (`TriageReason.forLite`, `ForecastDtoMapper.java:214`) and must not read "weather".
  (The region label's own mixed-cause "weather" is unchanged.)
- **Phone**: when `frame.tiny` (`MapLabels.jsx:470`) is set, name (90px ellipsis, as the region
  label rule at `index.css` ~7357) + "stood down"; the cause stays in the accessible name and the
  callout. `data-tiny` on the button, with its CSS rule.
- **Accessible name**: `"<name>, stood down: poor visibility"` / `"…: cloud"` / `"…: rain"`;
  GENERIC/unknown → `"<name>, stood down"`. One helper owns this wording.
- **Look — "ruled out", not "dimmed"**: never quieten with opacity (opacity is how the map DIMS a
  chip: tide-miss .72, topic dim, Ask fade .25). Use the cool stand-down ink
  (`--color-standdown-ink-2` for the cause, `--color-standdown-ink` for the name), a dotted
  hairline ring, the same plate as a chip. The button carries BOTH `wf-maplab-chip` and
  `wf-maplab-stood` so the Ask fade, topic dim, hover and focus rules (which select
  `.wf-maplab-chip`) apply; any chip rule that must NOT apply (rating colour) is keyed to
  `.wf-maplab-chip-m`/`-r`, which the stood button does not render.
- **Swatch**: the stand-down hatch (`wf-legend-swatch-standdown`) at ≥ 13px wide with the
  `.wf-legend-swatch` inset ring (~`index.css` 8110) so the veil reads on the chip plate, a
  `forced-colors` dotted border as at ~8146, `aria-hidden`.
- **Press** selects the place (`onSelect(name)`). The stood button itself shows the selection
  (`data-selected="true"`) — it is NOT swapped for an ordinary chip (that unmounted the focused
  node and dropped focus to `<body>`). The callout's verdict reads "Stood down".
- **No tooltip** (`hoverSpot` resolves only through `frame.chips`).
- Every role sees it (stand-downs are free, `roles-and-gating.md` ~43). A Lite place outside its
  sub-region picks is `gated`, not `stood` (`heatSpots.js` ~119, ~230), so never appears.

## 3. Rules

1. **Pool** = `standGround.stood` (`MapView.jsx` ~2919), the list the hatch paints: weather
   stand-downs only (`TIDE_MISALIGNED` excluded), narrowed only by the dark-sky pool and a door's
   spot-set lens; SOLAR windows only (past ones included). Rating floor, chips, drive and scope do
   not narrow it. (So with the admin toggle on, a stood place outside the scope gets a stood label
   rather than a chip — accepted.)
2. **Agreement guard.** Keep a stood point only when `isStandDownLocation(loc)` (`MapView.jsx`
   ~3152) is also true for its place. The hatch reads briefing score rows only; the callout and
   `getRatingForLocation` let a forecast-row rating win. Without the guard, a slot triaged in the
   briefing row but rated 1–2★ in the forecast row would be labelled "stood down" over a place the
   callout rates. With it, the label is a subset of both definitions.
3. **Name on the point.** `stoodPointsFor` (`heatSpots.js` ~340) adds `name: spot.name` to each
   stood point — no join, no "unjoined" branch.
4. **Built once, memoised.** In `standGround`'s own `useMemo`, build `stoodPlaces`: each point
   gets `name`, `reason`, `lat`, `lng`, `key`, `driveMinutes` (from `driveMinutesFor`, joined here
   so the callback never becomes a `MapLabels` prop), pre-sorted by drive ascending (unknown last)
   then name, and filtered by rule 2. Empty → a module-level frozen constant. Never built inline at
   the `<MapLabels>` site (a new identity re-runs `paint` and re-measures every label on every
   MapView render; `MapLabels.jsx` ~477, ~547, ~555).
5. **In `paint` (live zoom and bounds), via a pure `stoodPlaceCandidates`** in `utils/mapLabels.js`:
   - zoom gate: `STOOD_PLACE_LABEL_MIN_ZOOM` defined AS `REGION_LABEL_MAX_ZOOM`;
   - in view with the SAME unpadded `bounds.contains` chips use (~415-424) — never padded (the
     nudge ladder could pull an off-screen point's box into the frame);
   - de-dup by `heatSpotKey` against the chip candidates `paint` builds (`frame.chips`),
     `frame.fallbackChips` and the big-seas chips — EXCEPT the selection: a selected stood place
     stays a stood label (rule in §2), and the selection push into `labelSpots`
     (`MapView.jsx` ~4409) skips a place that is in `stoodPlaces`;
   - budget: `budgetLeft = max(0, chipBudget(zoom) − budgetedChipCount)` where
     `budgetedChipCount` is the in-view, budget-spending ordinary chips only
     (`inView.filter(s => !s.topicRing).slice(0, budget).length`, computed in `paint`) — NOT
     `frame.chips.length`, which also holds Ask picks, best-per-region, topic-ring places and the
     selection, none of which spend the budget. Take the first `budgetLeft` candidates.
6. **Placement: a third pass, after everything.** Stood labels are NOT added to
   `labelTierOrder` or either existing pass (the big-seas probe pass ~627-634 and the full pass
   ~650). After the compact-dot fallback loop (~656-672) and before the rated-badge fix-up
   (~674), run `placeLabelPass(stoodItems, w, h, [...obstacles, ...placed.values()])` (the seeded
   obstacles include the clamped phone `bottomInset`). So a stood label can never take an Ask
   pick's dot space, a chip, a big-seas chip, a region name, a ring or home. Keys `stood:${name}`;
   a separate `stoodRefs` Map (never `chipRefs`, keyed by bare name). The badge fix-up and the dot
   loop never touch `stood:` boxes.
7. **DOM order**: render stood buttons after every chip (outside `inDomOrder`), so Tab order
   follows placement priority.
8. **Focus on close**: `closeCallout` (`MapView.jsx` ~6002-6011) focuses the selected chip — add
   `[data-testid="map-label-stood"][data-selected="true"]` to that selector.
9. **Fades follow chips** (classes in §2). The tide strip, region panel, footer counts, verdict
   pill and heat field are unchanged.
10. **Phone and desktop both get it** — this is not phone-only work; desktop/tablet behaviour
    outside the new labels is unchanged.

## 4. Backend-heavy licence

`docs/engineering/reference/architecture-decisions.md` line 12 (Map scope class) says "Members:
those eight, nothing else" and "None of the eight re-derives…". Update the count and the closing
ban sentence, change member (i)'s `LOW_VISIBILITY` "murk" to "visibility", and add a member:
stood place labels — a filter, join and lexical map over the served stood state and
`triageReason`; it rates and thresholds nothing; its drive order is a display order only and never
claims "within reach". The `unratedSlotWord` change is a lexical map over the served
`displayVerdict` for an unrated slot, as before.

## 5. Files

- `frontend/src/utils/standDown.js`: `LOW_VISIBILITY: 'visibility'`, JSDoc (~44, ~63); a helper
  for the per-place cause (GENERIC/unknown → null) and the accessible name.
- `frontend/src/utils/heatSpots.js`: `name` on stood points.
- `frontend/src/utils/mapLabels.js`: `STOOD_PLACE_LABEL_MIN_ZOOM`, pure `stoodPlaceCandidates`.
- `frontend/src/utils/locationSheet.js`: `unratedSlotWord` STAND_DOWN → "Stood down"; its JSDoc.
- `frontend/src/components/map/MapLabels.jsx`: `stoodPlaces` prop (PropTypes), third pass,
  `stoodRefs`, render, `data-selected`, `data-tiny`.
- `frontend/src/components/MapView.jsx`: `stoodPlaces` in `standGround`'s memo; agreement guard;
  selection-push skip; `closeCallout` selector.
- `frontend/src/index.css`: `.wf-maplab-stood` rules near the stand-down region rules
  (~7301-7360), tokens ~54-56; `:focus-visible` (copy ~7428); Ask-fade focus/hover restore
  (~7859-7865); topic dim (~7090) and phone variant (~7954) coverage; `forced-colors`; `data-tiny`.
- Docs: `map-tab.md` line 23 ("murk" → "visibility"; the per-place label), the callout's verdict
  word wherever `map-tab.md` / `plan-tab.md` documents "Poor" for an unrated stand-down slot;
  `architecture-decisions.md` (§4).
- `changelog.d/20261031-stood-down-place-labels.md` (`### Added — …`).

## 6. Tests

Pure (`test/mapLabels.test.js` unless noted):
- `STOOD_PLACE_LABEL_MIN_ZOOM === REGION_LABEL_MAX_ZOOM`; zoom 11.19 → region stand-down label
  present, stood none; 11.2 → the reverse.
- `stoodPlaceCandidates`: unpadded in-view filter; de-dup by key (`#id` and `@name` points, two
  places sharing a display name); selection kept; budget 0 → none, budget 1 with 3 candidates →
  exactly 1; order by drive then name.
- `bigSeasChips.test.js` (~187-200): `labelTierOrder` is unchanged (stood is not a tier).
- `standDown` helper: visibility/cloud/rain words; GENERIC and unknown → no cause; aria strings.
- `heatSpots.test.js`: stood points carry `name`.
- `locationSheet.test.js`: `unratedSlotWord('STAND_DOWN')` → "Stood down"; UNRATED and unknown
  unchanged.

`test/MapLabels.test.jsx` (`makeMap({zoom})` + `withMeasuredLabels`):
- renders name, "stood down · visibility", swatch, no star; press → `onSelect(name)`; aria-label.
- never displaces: a stood place ranked FIRST on drive time colliding with a rated chip → the
  chip wins; colliding with an Ask pick's dot → the dot wins.
- budget: 35 budgeted rated chips in view at 11.2 (`chipBudget(11.2)` = 35) → 0 stood; 34 → 1.
- `frame.tiny` → "stood down" without the cause; `bottomInset` obstacle blocks a stood label.
- selected stood place renders the stood button with `data-selected`, no chip.

`test/MapViewStandDown.test.jsx` (assert `labelProps.last.stoodPlaces`, zoom-independent):
- a weather stand-down → present; a mixed window (HIGH_CLOUD + TIDE_MISALIGNED) → only the
  weather place; dark-sky pool and door lens narrow it; a past window; astro/aurora → empty;
  agreement guard: briefing row triaged + forecast row 2★ → absent.
- keyboard press then Escape returns focus to the stood label.

CSS / cascade:
- `askMapCascade.test.jsx` (~56, ~80) and `mapTopicDimCascade.test.jsx`: a stood row fades/dims.
- `standDownCss.test.js`: new rules use the stand-down tokens; no `opacity` on `.wf-maplab-stood`;
  `forced-colors` rule present.

Update every existing "murk" expectation (`standDownGround.test.js:96, 222`,
`mapLabels.test.js:719, 726`, `MapViewStandDown.test.jsx:378, 445`) and every "Poor" expectation
that comes from `unratedSlotWord` for an unrated stand-down (e.g. `MapCallout.test.jsx`,
`locationSheet.test.js`, `LocationFourDaySheet*.test.jsx`) — and ONLY those; region/window "Poor"
expectations must stay.

Gates: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`.
Browser check on the local backend (port 8083) at desktop and phone width: zoomed out (region
label, no place labels), zoomed in (place labels), a dense area (rated chips win), Ask active,
pressing a stood label (callout says "Stood down"), Escape (focus returns).

## 7. Out of scope

- The admin "stand-down" filter, the hatch, the region label's rules (other than the word), the
  footer count, the verdict pill, region/window "Poor".
- Tide stand-downs (`TIDE_MISALIGNED`): not hatched, not labelled.
- A per-place label below zoom 11.2.
- Ask pick cards' verdict word.
