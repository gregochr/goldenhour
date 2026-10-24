# Topic ring names — plan

Owner, 2026-10-24: "Could the storm surge gold rings have location names? The gold rings are cool, but
they don't tell me where to go!"

## 1. Why the rings are nameless today

`MapTopicRings` rings the topic's places (`activeTopicChip.places`), which `placesFor` joins to the
SCOPED roster, deliberately **not** to `labelSpots`, so a stood-down or star-floored place is still
ringed (`docs/engineering/reference/map-tab.md`, "hot-topic chips, rings and the chrome-height hook").
The name chips come from `labelSpots` only. On a poor evening (the owner's screenshot: 176 of 263
stood down, 9 shown) almost every surge place is stood down, so it is ringed but has no chip. Even a
ringed place that IS in `labelSpots` competes for the zoom budget like any other chip, and every
ordinary chip, ringed or not, dims to .5 while a topic is selected.

## 2. Change (frontend only)

1. **Ringed places get chips.** In `MapView.jsx`, a new memo `chipSpots` (fed to `MapLabels` and
   `PinsLayer` in place of `labelSpots`; nothing else switches) is `labelSpots` with every spot whose
   place is ringed marked `topicRing: true`, plus a spot APPENDED for each ringed place not already
   in `labelSpots`, built by the same per-location mapping `labelSpots` uses (hoist `spotOf` to a
   `useCallback` so both memos share it; no copy). Identity: id first, name second (the `heatSpots`
   doctrine); a ringed place with no catalogue row or no finite coordinates is skipped.
   - `labelSpots` itself is unchanged, so the tide strip's counts, the counts footer, Ask, the
     big-seas suppression set and everything else reading it are unchanged.
   - **Big seas is excluded**: when the selected topic is Big seas its structures are already named
     by big-seas chips (which suppress ordinary chips), so nothing is marked or appended.
   - With no topic selected (or rings not drawn: `topicRingsDrawn` false), `chipSpots === labelSpots`
     (same reference, so nothing downstream re-runs).
2. **Ringed chips are offered first.** `utils/mapLabels.js#chipCandidates`: spots carrying
   `topicRing` are offered after Ask picks and the selected location and before every ordinary
   candidate, all of them, outside the density budget (like picks; the placer's collision pass still
   arbitrates, so a crowded cluster at low zoom names what fits and zooming in names the rest — never
   stacked, never shrunk). Among themselves they keep the existing sort. Update the Javadoc's order
   paragraph.
3. **Ringed chips do not dim.** `MapLabels` and `PinsLayer` write `data-topic-ring="true"` on a
   ringed spot's chip / pin (both the ordinary chip and an Ask pick chip may carry it; only the
   ordinary path matters for the dim). `index.css`'s topic-dim selector gains
   `:not([data-topic-ring])` on both lines, so the ringed names stand out and the rest step back.
   Update the comment block above it.
4. An appended stood-down spot draws exactly as any stood-down chip/pin already does (its
   `isStandDown`, no star); no new chip style.

## 3. Tests (Vitest, `docs/engineering/frontend-test-standards.md`)

- `mapLabels` unit: ringed spots precede ordinary ones, are offered beyond the budget and out of view
  ordering is preserved; picks still first, selected before ringed; no `topicRing` → output
  identical to today.
- `MapView` (existing topic-ring tests are the pattern): selecting a topic whose places include a
  stood-down place (absent from `labelSpots`) renders a chip with that place's name in Heat and a pin
  in Pins; deselecting removes it; Big seas appends nothing; the tide strip / footer counts are
  unchanged by the selection.
- `mapTopicDimCascade.test.jsx`: a `[data-topic-ring]` chip and pin stay at opacity 1 while
  `data-topic-selected` is set; an ordinary one still reads .5.

## 4. Docs

`docs/engineering/reference/map-tab.md`'s rings bullet: ringed places are named (chips offered first,
appended when filtered out, not dimmed; Big seas excluded). `changelog.d/20261024-topic-ring-names.md`.

## 5. Amendments after adversarial review (2026-10-24)

These override §2–§3 where they differ.

- **A1 Order (not ahead of the region guarantee).** `chipCandidates` order: Ask picks → selected →
  `bestPerRegion` → ringed spots **in view** (`inViewNames`; the budget does not apply to them) → the
  ordinary in-view list up to the budget. An out-of-view ringed spot is not offered. Tests: a
  region-best survives at low zoom with 60 ringed spots; an out-of-view ringed spot is not offered.
- **A2 Which places are appended: every ringed place.** The ring itself is drawn past the reader's
  filters (scope only), so a ring that is drawn is named, whatever withheld its chip (stand-down, star
  floor, subject chips, drive filter, dark sky, a door lens). Planner's call on the owner's ask ("they
  don't tell me where to go"); flagged to the owner. Test: a drive-filtered ringed place is appended.
- **A3 Pins scope.** A pin carries no name (the name is its tooltip and `aria-label`), so in Pins the
  change adds a pin for a filtered-out ringed place and un-dims ringed pins; it does not draw names.
  Heat names them. Stated in the reference doc; test wording says "pin", not "named".
- **A4 Region labels unchanged.** Appended spots carry `topicRingAdded: true` and are excluded from
  `MapLabels`' region-label input (`regionLabelItems` and `withStandDownLabels`' inputs). Test: region
  labels identical with and without the topic selected.
- **A5 Identity.** Join a place to the catalogue with `locationsById.get(place.id) ??
  locations.find(name)` and feed `spotOf` the CATALOGUE row (heat roster entries lack `lon`,
  `tideType`). Skip the append when EITHER the id or the name is already in `labelSpots` (every
  downstream key is the name).
- **A6 CSS and harness.** Update the dim comment block. Cascade tests: ringed chip and pin stay 1;
  ringed + `data-ask='fade'` still reads .25. `scripts/measurements/label-obstacle/harness/main.jsx`
  mirrors the chip markup byte-for-byte: add the attribute there (absent by default).
- **A7 Aurora.** `omitUnrated` exempts `topicRing` spots as it exempts `askPick` (MapLabels and
  PinsLayer), so a located NIGHT topic's unrated place is still named.
- **A8 Wording/tests.** MapLabels chips have no stand-down style: a stood-down ringed chip simply has
  no star. `fallbackChipSpots` test on a big-seas day with King tide selected: a ringed structure keeps
  its big-seas chip and gets no duplicate ordinary chip. `MapViewTopics.test.jsx` mocks MapLabels and
  PinsLayer, so assert on their `spots` prop, including `===` the `labelSpots` the tide strip gets
  when no topic is selected.
