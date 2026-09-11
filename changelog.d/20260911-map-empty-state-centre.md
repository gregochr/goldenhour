### Changed — the Map tab's "No forecast to show." moves to the centre of the map

The empty-state line added in #807 sat in the top-right key slot beside "This event is not scored
yet". On a map that is genuinely blank — no field, no chips, no pins, and often no window control to
speak for itself — the corner is not where the eye goes, which is the whole reason the surface has to
say anything. It now sits centred in the map body (owner call).

It is a full-bleed `inset: 0` overlay across the entire map, which makes two things load-bearing:

- **It must never swallow a pan.** ⚠️ The first cut set `pointer-events: none` on the wrapper and a
  comment claimed the chip "inherits it". Measured in a browser it did not: the chip reuses
  `.wf-map-key` for its look, and `.wf-map-key` deliberately sets `pointer-events: auto` — correct
  in its home, where the toolbar is click-through and its controls must stay clickable. Borrowing the
  class borrowed that override. `elementFromPoint` at the chip's centre returned the chip rather than
  the map: a 138×25 dead zone dead-centre on the one screen whose only remaining job is being panned
  away from. The fix is a scoped `.wf-map-empty .wf-map-key { pointer-events: none }` — two classes
  against one, so it wins without `!important`, and the toolbar's keys keep their `auto`. A new
  cascade test pins the winner and a control proves the scoping; a green suite, clean lint and a
  successful build had all passed over it.
- **It sits on an existing rung of the z-ladder**, 1000: above Leaflet (panes ≤700, controls 800),
  below the landing card (1050) and the chrome corners (1100), so a panel or popover always covers it
  rather than the reverse. All of them share the `map-container` stacking context, so these values
  genuinely compete — pinned alongside the rest of the ladder.

⚠️ **Centring it put it where the label placer did not know to look.** `MapLabels` places region
names and chips greedily around seeded obstacles, and a centred label paints over that layer.
Measured before seeding: a region name overlapped it by **311 px²**, its bottom edge under the chip.
The chip (never its `inset: 0` wrapper, which would seed the whole frame and drop every label) now
joins `OBSTACLE_SELECTOR` in both `MapLabels` and `PinsLayer`; after seeding the overlap is zero and
no label was dropped. The placer's own doc warns that a new obstacle "is not free — measure it",
because seeding reshuffles the greedy pass on a busy map. That cost cannot occur here: the selector
is a live DOM query, and the chip exists only while the rating gate has already removed every rated
chip — pinned by the existing test that the line is absent whenever a window is on screen.

⚠️ A comment edit mid-change also closed a CSS comment early, turning the paragraph after it into a
bogus selector that silently swallowed the whole `.wf-map-empty` rule. Lint and build both passed;
the chip was measured sitting full-width *below* the map before the stray `*/` was found.
