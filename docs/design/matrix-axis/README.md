# Handoff: The days ahead — naming the matrix axis

## Overview

The Plan tab's "The days ahead" strip is a matrix: one column per day, sunrise on the top row and sunset on the row beneath. Nothing in the layout said so — the only signal was a small `SUNRISE` / `SUNSET` caption on each card, in the same grey as every other caption, which readers (including the design owner) routinely missed.

This change makes the axis explicit and keeps it on screen:

1. **Row rails.** A full-width labelled band across the top of each row — `SUNRISE` in a cool dawn blue, `SUNSET` in coral — each followed by a rule that fades out across the days.
2. **Card chips.** The per-card caption becomes a tinted chip in the same two colours, so a card read on its own (or on the phone, where the rails are dropped) still states which end of the day it is.
3. **Sticky headings.** The day-tile header row pins under the lens/filter bar once the strip reaches it, and each row's rail pins under the day tiles for exactly as long as its own cards are on screen — the sunrise rail leaves with the sunrise cards, leaving the sunset rail and its cards. Everything releases when the strip scrolls past.

Everything else on the tab — data, cards, thumbnails, popup, regional planner, hot topics — is unchanged from the previous version (`Plan Tab with Heat v4`, documented in `design_handoff_plan_matrix/`).

## About the design files

The files in this bundle are **design references created in HTML** — a prototype showing intended look and behaviour, not production code to copy. The task is to **recreate these behaviours in the target codebase's existing environment** (the PhotoCast React frontend under `frontend/` / `components/` / `screens/`) using its established components, tokens and patterns. The HTML/JS here is a specification you can measure, not a module to import.

## Fidelity

**High fidelity.** Colours, typography, spacing and sticky offsets below are final values taken from the prototype. Recreate them pixel-for-pixel with the codebase's own primitives.

## Scope: what changed

Three deltas to the existing "days ahead" strip. Nothing else in the tab needs touching.

### 1. Row rails

**Structure.** On desktop/tablet the strip is a vertical stack of three blocks, not one grid — this is what lets each rail pin independently of the other:

```
.hstrip                 flex column, gap 7px
  .dhrow                grid of day tiles      — sticky (see §3)
  .hrow.am              flex column, gap 7px
    .rail.am            sunrise label          — sticky inside .hrow.am
    .hcards             grid of sunrise cards
  .hrow.pm
    .rail.pm            sunset label           — sticky inside .hrow.pm
    .hcards             grid of sunset cards
```

`.dhrow` and `.hcards` share the same track definition so columns line up: `grid-template-columns: repeat(<dayCount>, minmax(0,1fr)); gap: 8px; align-items: stretch`. **`minmax(0,1fr)` matters** — with plain `1fr` the card's min-content width (raised by the new chip) overflows the track on narrow viewports.

On the phone the strip is instead a single 2-column grid in normal flow, and **nothing in it is sticky** — a day group is a heading plus one row of two cards, so the heading is barely ever off screen, and a third sticky layer under the masthead and lens bar would cost scroll height permanently to restate what the card's time and chip already carry: a full-width day heading, then that day's one or two cards (a solo card spans both columns). The rails are not rendered there. The prototype branches on viewport in `renderStrip()` and re-renders on viewport change; in React, render the two structures from the same window list.

**Rail markup and spec** (`.rail`, one per row, `grid-column: 1 / -1`):

- Layout: `display:flex; align-items:center; gap:9px; padding:4px 2px 3px` (plus the sticky rules in §3)
- Label: IBM Plex Mono 600, **9.5px**, `letter-spacing:.16em`, `text-transform:uppercase`, `line-height:1`. Copy: `Sunrise` / `Sunset`
- Rule (`.rln`): `flex:1; height:1px`
  - sunrise: `linear-gradient(90deg, color-mix(in srgb, #8FA8C4 42%, transparent), transparent)`
  - sunset: `linear-gradient(90deg, color-mix(in srgb, #E8593F 42%, transparent), transparent)`
- Label colour: sunrise `#8FA8C4` (new token `--dawn`), sunset `#E8593F` (`--coral`)

**Phone:** rails are hidden (`display:none`). The phone layout stacks day-by-day in two columns, so a row band has no row to name; the card chips carry the fact instead.

### 2. Card chips

The card's top-left caption (`.hc .ar`), previously mono 9px in `--ink-3`, becomes a tinted chip:

| Property | Value |
| --- | --- |
| Type | IBM Plex Mono 600, 9px, `letter-spacing:.11em`, uppercase, `line-height:1` |
| Box | `padding:3px 6px`, `border-radius:4px`, `white-space:nowrap`, `min-width:0`, `overflow:hidden` |
| Sunrise | text `#8FA8C4`; background `color-mix(in srgb, #8FA8C4 15%, transparent)`; `box-shadow: inset 0 0 0 1px color-mix(in srgb, #8FA8C4 30%, transparent)` |
| Sunset | text `#EE8064`; background `color-mix(in srgb, #E8593F 15%, transparent)`; `box-shadow: inset 0 0 0 1px color-mix(in srgb, #E8593F 30%, transparent)` |
| Phone | `letter-spacing:.04em; padding:2px 4px` — keeps the card's min-content width inside its grid track |

Copy: `Sunrise` / `Sunset` (rendered uppercase by CSS). Derived from the window's `when` field: `/sunrise/i` test. The chip replaces the earlier arrow glyph deliberately — a down arrow for sunset reads as a falling forecast.

### 3. Sticky headings

Two sticky layers, stacked under the two bars that already stick (masthead, then the lens/filter bar).

```css
.dhrow {                                   /* the day tiles */
  position: sticky;
  top: calc(var(--mastH, 48px) + var(--lensH, 44px) - 1px);
  z-index: 22;
  padding-top: 9px;
  background: linear-gradient(180deg, var(--bg) 0 82%, transparent);
}
.rail {                                    /* each row's own label */
  position: sticky;
  top: calc(var(--mastH, 48px) + var(--lensH, 44px) + var(--dhH, 40px) - 2px);
  z-index: 20;
  padding: 4px 2px 3px;
  background: linear-gradient(180deg, var(--bg) 0 70%, transparent);
}
```

Because each `.rail` is a child of its own `.hrow`, sticky confines it to that row: it holds beneath the day tiles while any of its cards are in view, then is carried off with the last of them. Scrolling through the strip therefore reads: both rails visible → sunrise rail pinned → sunrise rail leaves with its cards → sunset rail pinned with its cards.

- `--mastH`, `--lensH` and `--dhH` (day-tile row height) are **measured, not hard-coded** — both bars change height when the lens controls wrap or the viewport class changes. In the prototype they are written onto the scroll container from `offsetHeight` on load, on view change and on a debounced resize. In React, measure with a ResizeObserver on both bars.
- The `- 1px` closes the hairline between the lens bar's bottom border and the pinned tiles.
- The fading background stops cards from colliding with the tiles as they pass underneath; it must be the page background (`--bg: #181210`), not the panel colour.
- Behaviour: the section scrolls normally → tiles reach the underside of the filter bar and hold → they release when the strip's last row scrolls past. Standard sticky semantics; no scroll listener.
- Z-order in play: masthead `45`, lens bar `30`, day tiles `22`, rails `20`, cards default. The popup sheet sits at `60`/`70` and must stay above all of them.

## Interactions & behaviour

Unchanged from v4: clicking a card opens the window popup; hover tints the card by verdict; best bet / also good ride the card border as legends; empty cells state why they are empty (`this morning has gone`, `past the end of the forecast`).

New: nothing is clickable in the rails — they are labels, not filters. If a future version wants "show sunrises only", the rail is the natural place for it, but it is out of scope here.

## State

No new state. The rails and chips are derived from each window's `when` field; the sticky offsets are derived from measured bar heights.

## Design tokens

New:

| Token | Value | Use |
| --- | --- | --- |
| `--dawn` | `#8FA8C4` | sunrise rail label, rule, card chip |

Existing tokens used by this change: `--coral #E8593F`, `--bg #181210`, `--border #3A2C23`, `--ink-3 rgba(242,231,211,.42)`, mono family `IBM Plex Mono`. Chip sunset text uses a lightened coral `#EE8064` for contrast on the 15% wash.

Spacing introduced: rail `gap:9px`, rail `padding:4px 2px 3px`, sticky tile `padding-top:9px`, strip `gap:7px`, card grids `gap:8px`.

## Assets

None. No new icons or images; the rails are text plus a 1px gradient rule.

## Files in this bundle

| File | What it is |
| --- | --- |
| `Plan Tab with Heat v5.html` | the prototype — all CSS, including the three changes above |
| `plan-tab-v5.js` | render logic: `renderStrip()` builds the row blocks and rails; `card()` builds the chip via `sunMark()`; `measureMast()` writes `--mastH` / `--lensH` |
| `plan-data.js`, `heat-field.js` | data and the heat kernel, unchanged — needed only to run the prototype |

Open the HTML directly in a browser; it needs no build step. The relevant code is `renderStrip()` (row blocks, rails, phone branch), `sunMark()` (chip) and `measureMast()` (sticky offsets).

For the surrounding tab — lens bar, cards, thumbnails, popup, regional planner — see the earlier bundle `design_handoff_plan_matrix/`, which this change sits on top of.
