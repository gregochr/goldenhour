# Claude Code prompt — doors from Plan to Map

Paste below into Claude Code from the repo root, with `design_handoff_map_tab/` copied in.

---

Make the Map tab reachable from the Plan tab, per
`design_handoff_map_tab/INCREMENT_plan_to_map_doors.md`. **Read that file in full first** — it
is the spec, and it records a defect and two open questions that matter. This prompt is the
working order.

Design reference: `Plan Tab with Heat v5.html` + `plan-tab-v5.js` (the doors) and
`Map Tab v2.html` + `map-tab-v2.js` (the receiving end). Both are Leaflet/DOM prototypes —
recreate in React with this repo's patterns, do not port imperative code.

## Build order

1. **A single `driveOf(s)` on the Map tab**, and route every drive-time read through it: the
   reach filter, chip tooltips, the callout facts, leave-by, the region jump list's distances,
   the location sheet rows and header. `s.min` is from DH3 4NG, `s.lmin` from an away base;
   both already exist. Do this first — it is what makes origin honourable, and it is the change
   most likely to leave a straggler.
2. **Origin state on the Map tab.** It currently has no origin concept at all, just a hard-coded
   `HOMEPT`. Add one. The home marker and reach rings render **only** for a home origin — do not
   invent a coordinate for an away base; the Plan tab plots none either.
3. **The handover payload**: window index, region, rate, reach, origin, optional location. Map
   the window index **on the map's side** — `EV` interleaves astro and aurora rows, so a Plan
   window index is not an `EV` index. In the app this is shared state across a tab switch, not
   a URL.
4. **The breadcrumb** on the Map tab: where you came from, every carried fact named (origin
   first), and a `clear` that resets filters and origin. Seed it as a label obstacle like every
   other overlay control.
5. **Door 2** — wire the location sheet's existing `◍ Show on map →` button. It already exists
   with no handler.
6. **Door 1** — `◍ Open in map →` on the window sheet's field, top-right. Do **not** overload
   the field's existing click gesture (it filters by region). **Seed the button's measured rect
   into the `placeLabels` obstacle array** — this is where I broke it: unseeded, it covered a
   5★ rating in 4 of 6 windows.
7. **Door 3** — the thumbnail glyph, with `stopPropagation` so the card's own `openWin` does not
   also fire. Build it, then judge it; see the open question.

## Verify

1. Each door lands on the correct window — test one whose `EV` index differs from its Plan index.
2. Region, rate, reach and origin arrive applied; the breadcrumb names each; `clear` resets all.
3. From a Lake District plan: every number measures from Keswick, no marker, no rings.
4. **No overlay control covers a field label.** Sample `elementFromPoint` across each chip's full
   width, in **every** window — the defect only appeared in 4 of 6.
5. Door 3 does not also open the window sheet.

## Do not

- Do not pass an `EV` index across the handover.
- Do not send a parameter nothing reads. I wrote `org` into the URL and never read it, so a
  Lakes-based plan silently measured from DH3 4NG while the breadcrumb asserted what it carried.
  Either honour a fact or state that it changed.
- Do not draw anything over a field without seeding it into the label placer's obstacle array.
  Both tabs have that mechanism and both have now been broken by forgetting it.
- Do not make the thumbnails live Leaflet maps. Six tile maps on one screen is slow and they
  would be six views of the same ground. The static field is correct — it needed to be a door.
- Do not invent base coordinates to get a marker for an away origin.

## Ask before deciding

- **Door 3** — keep or drop? It is under the 44px hit target and hover-revealed on desktop.
  Build it so it can be judged, then raise it.
- **The return trip** — should `← Plan` reopen the window sheet you left, or return to the plan
  itself? Reopening preserves your place but leaves a modal over the answer you went to check.

Say what you changed and which checks you ran.
