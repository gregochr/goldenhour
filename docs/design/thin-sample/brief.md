# Brief for Claude Design: a heat field that shows what was not looked at

## The problem, in one screenshot

Plan tab, viewed Thu 22 Oct 2026. The **Sat 24 sunrise** card is the strip's BEST BET, reads
`Worth it 4.0★`, and its field is a bright orange bloom over Northumberland & Tyneside. It looks
like the best morning of the week.

It rests on **6 rated places of 83** within the reader's 2h30 reach. The only thing on the card that
says so is the spread row's quiet `6/83 rated`, two rows below the verdict. The owner reads it
because he knows it is there; a new reader sees the colour and the gold border and stops.

Compare **Today 22 sunrise** beside it: `Worth it 3.7★`, the field orange across the whole north,
the histogram full (34 · 71 · 108 at 2–4★). That card is honest at a glance. Sat looks just as
confident and is not.

## Why the field over-claims

The kernel (`frontend/src/utils/heatField.js`) paints **rated places only**. An unrated place adds
nothing, so its ground stays the plain dark plate, and the dark plate also means "no places here".
On the field, *nobody looked* and *nothing to see* look the same. The only damping the field
applies is the confidence haze (`conf`, from the forecast horizon: T+2 is medium), which is the
same whether 6 or 80 places were rated.

The hatch already means "not scored" — but today it is all-or-nothing per window: a window with
no rating at all gets the whole plate hatched (`hatchPlate`, Fri 23 and Sat 24 sunset in the
screenshot); a window with one rating gets none.

## What the owner wants designed

The field must stay coloured — colour is the point of the Plan tab — but the picture itself must
show how much of the map the colour actually stands on, so a thin window reads as thin without
the reader having to find the spread row.

The starting idea (not a decision): **hatch the ground around each unrated place**, with the
existing hatch, so Sat sunrise becomes a small warm patch inside a large hatched area, while Today
22 (almost everything rated) looks much as it does now. Other approaches are welcome if they read
better.

## Facts the design can rely on

- Per window the client holds the whole catalogue of places with coordinates (`spots`) and the
  rated points (`pointSets`), so "unrated places in this window" is known per window, per place.
- The field and the hatch are drawn on canvas; the existing hatch is 45°, 7px gap, 0.6px line,
  ink `rgba(242,231,211,.18)`, clipped to the coastline (`hatchPlate`).
- The kernel already has a coverage clamp (`1 - exp(-sw/1.15)`): warmth fades with distance from
  rated places, so colour does not interpolate across empty ground.
- The legend under the strip already reads `unshaded — not scored`; today that is barely visible
  because unshaded and empty land look alike.
- The spread row's own gate (`windowFirstSpread.js`): bars only when `rated >= 5` and
  `rated >= half` of the reach pool. Below it the row prints `N/M rated`.
- The same kernel paints the **Map tab** heat field (`MapHeatLayer`), at real map scale.

## Decisions the design must make

1. **The treatment.** Per-place hatch around unrated places, a field-wide dimming scaled by the
   rated share, a stippled/greyed "unknown" tone, or something else. It must not dim or recolour the
   rated places themselves: a real 4★ is still a real 4★.
2. **Which population.** The field colours the whole forecast; the spread row counts the reader's
   reach pool. The treatment should follow the field's own population (every place drawn), and the
   design should say so in the legend.
3. **Where the hatch and the colour meet.** A rated place next to an unrated one: which wins at the
   overlap, and how sharp the edge is at a 300px card and at map scale.
4. **The legend line.** Whether `unshaded — not scored` becomes something like a hatch swatch
   `· not rated yet`, and how it sits beside the existing ramp and haze entries.
5. **The verdict line.** Whether a thin window also states its sample beside the verdict
   (`Worth it 4.0★ · 6 rated`) or the picture alone carries it. If it is added: when (the spread
   row's gate is the obvious threshold) and what it says on a phone card.
6. **The Map tab.** Does the same treatment apply at map scale, and does it still read when the
   reader has zoomed in on one region?

## What must not change

- The colour ramp, the bloom and the confidence haze.
- A window with **no** rating keeps the whole-plate hatch it has today.
- Stars, verdicts and BEST BET/ALSO GOOD are not changed by this design. (Whether a window crowned
  on a handful of ratings should wear BEST BET is a separate owner question, recorded below.)
- Muting by colour, never by opacity over a translucent card (an earlier review found AA
  contrast failures that way).

## Out of scope, recorded

- **BEST BET on a thin sample.** Sat sunrise is probably crowned through the force-evaluation
  exemption (`BriefingRegion.forcedSample`), which exists to let a few hand-picked far-out ratings
  crown a day. Not checked against production. Whether such a window should also take the strip's
  BEST BET is an owner decision, not a design one.

## Deliverable

A Design canvas with artboards built from the app's real tokens:

1. The Plan strip, desktop, with the screenshot's six windows (Today 22 well rated, Sat 24 sunrise
   6/83, Fri/Sat unscored, Today sunset 180 rated) — before and after.
2. The Sat 24 sunrise card alone at phone width (about 300px card).
3. The legend line, before and after.
4. The Map tab at a thin window, zoomed out and zoomed in on one region.
