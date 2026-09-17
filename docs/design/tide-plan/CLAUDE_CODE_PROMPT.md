# Claude Code prompt — Plan tab: tide alignment, summarised

Paste everything below into Claude Code from the repo root, with `design_handoff_tide_plan/`
copied into the repo (or its path substituted).

---

Implement **tide alignment on the Plan tab's window cards**, from the design bundle in
`design_handoff_tide_plan/`.

**Read `design_handoff_tide_plan/README.md` first and in full.** It is the specification: every
threshold, gate, hex value and copy string in it is final and intended to be matched. This
prompt is the working order, not a substitute for it.

## This depends on work already in flight

The Map tab tide design (`design_handoff_tide_window/`) is **already being implemented**. It
owns the model this change consumes:

- the `want: 'high' | 'mid' | 'low'` field on coastal locations,
- level at a solar event, interpolated between real tide extremes,
- the bands (0.75 / 0.25) and the fit tiers (0.80 / 0.62).

**Do not implement any of that twice, and do not change it.** `plan-tide-v6.js` duplicates the
model at the top of the file only so the prototype runs standalone — in the app it must import
the one implementation. If the map work has not landed yet, build the model there, not here.

This change adds **no new data**.

## What the files are

- `Plan Tide Summary.html` + `plan-tide-v6.js` — the design, as a working prototype. **Design
  reference, not production code.** Recreate in React using this repo's patterns
  (`components/HeatmapGrid.jsx`, `components/DailyBriefing.jsx`, `hooks/useIsMobile.js`). Do not
  port imperative DOM code.
- `heat-field.js`, `plan-data.js` — unchanged kernel and mock catalogue, so the prototype runs.

The notes column is part of the spec, and the two `cut` notes are load-bearing.

## Build order

1. **The glyph on the named spot.** The card already computes its best reachable location; mark
   it when that location is coastal and its tier is `match`. **A miss is silent** — the pick is
   chosen on score and a second ranking must not enter that line. README §1.
2. **The chip.** A topic chip in the topics line, first in the line, reusing the existing topic
   chip component with the tide tint. Gate: `coastalInReach >= 4 && matched >= max(3,
   ceil(coastalInReach * 0.5))`. Below the gate the card says **nothing** — no greyed chip, no
   zero. README §2.
3. **The run ranking.** `matched DESC, meanFit DESC, window ASC`, where `meanFit` is the mean fit
   of the **matched** locations. Do not rank by count alone: in the prototype's own data all
   three live windows match the same nine locations, and count-only picked the first of three
   identical numbers. README §3.
4. **The emphasis.** Only when more than one window is live, only on the winner: brighter ink,
   `700` weight, the hairline pill, and `best of N` after a divider. `N` is the count of live
   windows. A rank on every chip in the run is rejected. README §3.
5. **Pool consistency.** All counts come from the same reachable-named pool the card's rating,
   spread and named spot already use. Then read `OPEN 1` and decide whether the *ranking* should
   instead be scope-wide for stability.
6. **The window popup** gains one tide line: state, and `N of M coastal spots on tide`. Nothing
   more — the curve belongs to the map.
7. **Drop the demo toggle.** `〜 Best tide, emphasised` in the prototype's toolbar is a design
   control for comparing the two treatments. It is not a feature.
8. **Decide before ship:** `OPEN 2` (share a run vocabulary with
   `design_handoff_tide_runs/`'s `SPRING RUN n/4` chip rather than inventing a second one) and
   `OPEN 1`.

## Verify by measurement

Run all seven checks in the README's *Verify by measurement* section and report the numbers, not
screenshots. Checks 1, 3 and 5 each guard a decision that is easy to undo by accident:

1. Cards with a chip = windows passing the gate (prototype default: **3 of 6**).
2. Exactly one `.tdtop` when more than one window is live; none otherwise.
3. With counts tied, the emphasis lands on the highest `meanFit` — Thursday sunset in the
   prototype, not tonight.
4. Glyph is match-only; a mismatched named spot produces no glyph and no text.
5. ★ rating, verdict word and spread histogram identical across all three `want` values.
6. No chip overflows its card at desktop, iPad or phone.
7. Emphasised chip ≥ 4.5:1 on its pill; plain chip ≥ 4.5:1 on the card.

## Do not change

The ★ rating, the verdict word, the spread histogram, the card border colours, the pick system
(`Best bet` / `Also good`), the thumbnail field, or the Map tab. Do not fold tide into any
score. Do not add a `Tide` badge to the card border — it was built, tested and cut, and README §5
says why.
