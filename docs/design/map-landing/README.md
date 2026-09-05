# Handoff: Map tab — verdict, picks and the landing card

## Overview

An **increment on the Map tab (v2)**, not a redesign of it. Everything in
`design_handoff_map_tab/` still stands: the field kernel, the coast mask, the basemap, the one
chronological event list, the filter popover, the label density rules, the anchored callout and
the shared location sheet. This bundle adds the one thing that surface could not say.

**The problem.** The map draws one window at a time and colours it by *per-location score*, so
it never stated the verdict the Plan tab states on every card — Worth it / Maybe / Poor — and it
never said that a *different* window was better. A ramp legend is not a sentence: a screen full
of amber does not add up to a decision, because the reduction from a field to a verdict is work
the reader was being left to do by eye.

**The change, in three parts.**

| Part | What it does |
|---|---|
| Verdict on the window control | The pill states the verdict for the window it names, and names the region that verdict is true of. A verdict tick under each stepper says whether the night either side is better. |
| The landing card | On a cold open the map answers *should I go tonight or in the morning* — the next two solar windows, each with verdict and region, with the week's Best bet riding the row it belongs to. |
| One drilldown | window -> regions -> region -> the location sheet that already exists. One new level, three entrances. |

The design was explored as three options (`Map Verdict Options.html`: on the control, a week
rail, picks pinned to the ground). **Option A won and is the only one to build.** The other two
are in the bundle as the reasoning, and the two `Set aside` notes on `Map Landing.html` say why
they lost. Do not build them.

## About the design files

| File | What it is |
|---|---|
| `Map Landing.html` + `map-tab-v4.js` | **The design.** A working Leaflet prototype — design reference, not production code. Recreate in React with this repo's patterns (`react-leaflet`, `components/MapView.jsx`, `components/BottomSheet.jsx`, `hooks/useIsMobile.js`). Do not port imperative DOM code. |
| `Map Verdict Options.html` + `map-tab-v3.js` | The three-way comparison. Context and rejected alternatives. |
| `heat-field.js` | Unchanged from the Map tab v2 bundle. **Do not re-port or re-tune.** Listed only so the prototype runs. |
| `plan-data.js` | Mock catalogue. Replace with real API data; keep the shape. |

The notes column down the right of `Map Landing.html` is part of the specification: it records
what was tried and rejected, and the rejections are load-bearing (a filled medallion, a pick
chip, dismiss-on-drag, naming the least-bad region).

## Fidelity

**High.** Every hex, threshold, rule and copy string below is the value in the prototype and is
intended to be matched. Three things are flagged `OPEN` and need a decision or real data.

---

## 1 · The verdict

### Where the number comes from

For a window, take every rated location in scope, group by region, and take each region's
**mean** score. The window's verdict is the **strongest region's mean**.

```
verdict(window) = vWord( max over regions( mean( score(location, window) ) ) )
vWord: mean >= 3.7 -> "Worth it"   >= 2.8 -> "Maybe"   else -> "Poor"
```

Two rules about what does and does not move it:

- **Filters must not move it.** Minimum rating, reach, subject and dark-sky change what the map
  *draws*, never what the night *is*. Hiding 3-star locations cannot be allowed to turn a Maybe
  into a Worth it.
- **Scope does move it.** `My area` vs `Whole catalogue` changes which regions are candidates at
  all, so it changes the verdict, the region named beside it and both picks. Recompute on scope
  and origin change; cache on `(windowIndex, scopeKey)`.

`OPEN 1 — reconcile with the API.` The prototype derives the verdict from the field because it
has no verdict endpoint. Production already has one: `region.verdict` (`GO` / `MARGINAL` /
`STANDDOWN`), mapped to Worth it / Maybe / Poor in `components/HeatmapGrid.jsx` and
`components/DailyBriefing.jsx`. **Use the API's verdict, not a second computation** — the whole
point of the word is that the Map tab and the Plan tab cannot disagree about a night. Keep the
field-derived path only as the fallback where no regional verdict exists, and keep the
thresholds above so the fallback lands in the same place.

### Colours

| Tier | Fill / bar | Text on dark |
|---|---|---|
| Worth it | `#8AAE72` | `#A8C795` |
| Maybe | `#E0A542` | `#EFC377` |
| Poor | `#C8452F` | `#E5806C` |
| Night event (no verdict) | `#6E7C8C` | `#9FB0C0` |

The v2 rule still holds: **never draw ramp or verdict colour as text on a coloured fill.** The
fill carries the colour, the ink is `#FFFFFF` / `#0F172A` via `HeatField.ink()`.

### Naming the region

`Worth it` alone invites the reader to apply the word to the whole field, which is the one thing
it is not. So the verdict names the region it is true of — and counts the ones that share it:

| Case | Label | Example |
|---|---|---|
| One region in that tier | region name | `WORTH IT · the Lakes` |
| Several, not all | top region `+N` | `WORTH IT · Northumberland +2` |
| **Every** region in scope (n > 1) | no name | `POOR · everywhere in your area` |

Both failures here were made and fixed during the design. Naming only the strongest
**overclaims** (three regions worth it read as *only* the Lakes); naming the top region when
they are all Poor presents **the least-bad region as a destination**, which is the same
overclaim inverted.

Short region names, used everywhere the region appears in chrome:

```
ntw   -> Northumberland     penn    -> the Pennines    nymc -> the Moors
lakes -> the Lakes          dales   -> the Dales       peak -> the Peak
borders -> the Borders      highlands -> the Highlands
```

`OPEN 2 — highest or nearest.` The named region is currently the highest-rated. When three
regions are Worth it the real tiebreak is drive time, so the nearest qualifying region may be
the better name. Needs a call; the count is what tells the user which situation they are in
either way.

### Night events have no verdict

Astro and aurora run on a different model (darkness, clarity, Kp). They state that model's own
word — `Clear`, `Cloudy`, `Kp 5` — in the slate colour, with **no** verdict tint on the pill,
and they are **not** candidates for the week's picks or for the landing card's two rows. Before
this rule the aurora night won Best bet on a score that means something else.

---

## 2 · The window control

Structure, left to right: kind chip, day label, time, medallion (if this window is a pick),
verdict word with the region stacked under it, caret.

```
[ < ] [ SUNSET  Tonight  20:28  (ALSO GOOD) | WORTH IT         v ] [ > ]
  ^                                         | Northumberland +2      ^
  verdict tick = the tier of the window one step back / forward
```

- **Verdict tint**: `box-shadow: inset 3px 0 0 <tier fill>` on the pill, border tinted to match.
  Night events get no tint.
- **Verdict cell**: word in tier text colour, mono, 10px, `letter-spacing:.1em`, uppercase, with
  a 1px left rule at `--border-light`; region stacked under it at 9px, `--ink-2`, sentence case.
  Stacked, not inline: inline cost about 120px and pushed the stepper under the nav cluster.
- **Stepper tick**: 11x3px, 4px from the bottom, centred, filled with the neighbouring window's
  tier colour. Hidden when the stepper is disabled.
- **Layout constraints that must survive** — all three were regressions during the design:
  - The control group must be **bounded** (`max-width: calc(100% - 344px)`). It is absolutely
    positioned and shrink-to-fit, so an unbounded group grows rightward *under* the
    right-anchored Regions / Heat-Pins / Filters cluster at the same z-index, and that strip
    goes dead to clicks.
  - The **day label never truncates** (`flex:none`). It is the fact the control exists to state.
    The medallion words and the region yield instead.
  - At phone width the pill must be able to shrink (`min-width:0`) and the medallion drops its
    **words** while keeping its **glyph** — as separate elements. `font-size:0` plus
    `::first-letter` does not work: the medallion glyphs are symbols, not letters, so the chip
    renders as an empty bordered box.

### Responsive

| Frame | Verdict region | Medallion words | Control |
|---|---|---|---|
| Desktop | shown | shown | one row, top-left |
| iPad | shown | shown | one row, top-left |
| iPhone | hidden | hidden (glyph at 11px) | one row; nav cluster moves to the bottom bar |

---

## 3 · The picks

```
candidates = every (solar window, region) pair in scope with mean >= 2.8
rank       = region mean x (0.78 + 0.22 x window confidence)
Best bet   = highest rank
Also good  = highest rank differing from Best bet in BOTH window and region
```

Confidence nudges the rank so a day-6 guess cannot beat tonight on a rounding error. Two picks
on the same night are one pick, hence the both-differ rule. If no candidate clears 2.8 there are
**no picks**, and nothing is drawn.

Presentation, deliberately quiet:

- Outline chip only — BEST BET (`#A8C795`), ALSO GOOD (`#EFC377`), 1px inset ring, no fill. A
  filled chip became the loudest thing on a control whose job is the verdict.
- On the pill **only when the window on screen is a pick**. Nothing when it is not.
- On every row of the pill's menu, where a window is actually being chosen.
- **No chip pointing at other windows.** Built, then cut: it put a second navigation control on
  the map to answer a question the user arrives from the Plan tab already having answered.

---

## 4 · The landing card

The question it exists to answer, in the user's words: *should I go tonight or in the morning —
and if neither, when can I scratch the itch?* That is a comparison of **two windows**, which is
why it is not a week summary.

```
+- 376px, top:60 left:12, z 1300 --------------------------+
| Tonight, or tomorrow?                                 X  |
| YOUR NEXT TWO WINDOWS · FROM DH3 4NG                     |
+----------------------------------------------------------+
| SUNSET   Tonight   20:28   (ALSO GOOD)     WORTH IT      |
|                                            Northumb. +2  |
| SUNRISE  Tomorrow  05:42   (BEST BET)      WORTH IT      |
|                                            the Lakes +1  |
+----------------------------------------------------------+
| Best bet   Thursday sunset · the Peak                 >  |
+----------------------------------------------------------+
| Stays until you close it · after that the pill carries   |
| the verdict, the region and the medallion                |
+----------------------------------------------------------+
```

- **Rows** are the next two **solar** windows from now. Each row is live: selecting it sets that
  window and closes the card.
- **Header is derived from the rows it is showing** — same day gives `Tomorrow — sunrise or
  sunset?`, different days gives `Tonight, or tomorrow?`. Never hard-coded; a header naming
  windows that are not on screen was a real defect.
- **Picks ride their row** when they are one of the two. When a pick falls later in the week it
  becomes one quiet line (Also good in `--ink-2`, no colour). **Picks earlier than the first row
  are suppressed** — a window that has passed is not an answer.
- **When both rows are Poor** the card stops comparing: *Neither is worth the drive. Next up:
  &lt;window&gt; · &lt;region&gt; · Worth it ›*, naming a window **strictly later than both rows** and
  **only** one that is Worth it. Offering a window the sentence above has just called not worth
  the drive is the failure to avoid.
- **Dismissal**: the close button, Escape, or selecting a row. **Not** map click, drag, zoom,
  wheel or outside tap. Panning to the region it just named is *reading* the card, not finishing
  with it — dismiss-on-drag punished exactly the move the card invites.
- **Recoverable**: the same header text is the first row of the pill's menu, above the windows
  and beside the region-by-region row. Dismissing used to be irreversible, which quietly made
  closing it a risk.

`OPEN 3 — when it opens.` The prototype shows the card on every load. That is right on a cold
open and wrong on the fourth visit in an evening. Candidates: **once per forecast run** (the
recommendation), once per day, or suppress when the window you left is still current. Needs the
run-age value the app already has.

---

## 5 · The drilldown

One new level, entered three ways (pill menu footer row, the landing card, a pick chip). It ends
in the **existing location sheet** — `design_handoff_plan_matrix/README.md` section 3,
implemented in `plan-tab-v5.js renderSpot()`. **Extend that sheet; do not build a second one.**

**Window panel** — header: window label, time, confidence percentage, medallion if a pick;
verdict chip in tier colour. Then one note, branched four ways:

| Case | Copy |
|---|---|
| One region in the tier | *Verdict is the strongest region's average. Regions rank on that average, not on their ceiling — the reach filter only decides what the map draws.* |
| Several | *N regions are worth it for this window, so the choice between them is drive time.* plus the sentence above |
| All in scope, and Poor | *Poor in all N regions in your area — nothing here is worth the drive on this window. Rows are ranked by average, best first.* |
| Night event | *A night event, scored from darkness, clarity and Kp rather than the solar forecast. It has no Worth it / Maybe / Poor of its own and cannot be the week's Best bet.* |

Region rows: name, `<drive> · N of M at 4 stars+`, verdict word (or an em dash for night
events), ramp swatch plus `N star best`. **Ranked by mean, ceiling shown beside it** — a single
5-star in a flat region is a lucky location, not a good night, and the two numbers have to be
readable together.

**Region panel** — back arrow to the window panel; region name, window, medallion, verdict chip;
stats line (`N of M at 4 stars+ · nearest <drive> · average N stars`); top four locations (name,
tide glyph where the tide lands on the light, `<drive> · leave <time>`, ramp swatch plus stars);
the region's narrative for that window; actions `Zoom to region` and `Four days at <full
location name>`.

The action label prints the **full** location name. Splitting on whitespace produced
"Four days at Infinity" and "Four days at Scott's", which are not places.

**Panels do not close when you click the map.** Week menu, region panels, Filters and Legend are
all *about* the map, so panning while one is open is using it. They close on their own chip,
their close button, or Escape. Tapping bare ground still deselects a location — that is a
selection, not a panel.

---

## Verify by measurement, not by eye

Every one of these was got wrong at least once while designing it.

1. **Filters do not move the verdict.** Assert the pill's word and region are identical before
   and after changing minimum rating, reach, subject and dark-sky. Then assert they *do* change
   when scope flips to `Whole catalogue`.
2. **Three region-label cases render.** Force one-region, several-regions and all-regions states
   and assert `the Lakes`, `Northumberland +2`, `everywhere in your area`. The third regressed.
3. **Picks.** Only solar windows are candidates; the two picks differ in both window and region;
   no picks at all when nothing clears 2.8.
4. **The control is one row at three widths.** Measure that the next-window stepper's right edge
   is left of the Regions chip's left edge at desktop, iPad and iPhone; assert the day label's
   `scrollWidth === clientWidth`; assert the medallion glyph's computed font-size is non-zero on
   phone.
5. **The card survives the map.** With it open, pan, zoom, wheel and click the ground: it stays.
   Then close, Escape and a row selection each dismiss it. Assert the reopen row exists in the
   pill menu and reopens with the same header text.
6. **The card cannot contradict itself.** In the all-Poor state, assert both rows read Poor and
   the "next up" window index is strictly greater than both rows and is a Worth it. Assert no
   pick line names a window index below the first row.
7. **Night events.** No verdict word, no tint, not pick candidates, panel note variant present.

## Do not change

The field kernel, the coast mask, the ramp, the bloom gate, the basemap and reference layer, the
label density rules, the anchored callout, the reach rings, the location sheet, the Plan tab.
This increment touches the window control, adds one card and two panels, and changes what closes
a panel. Nothing else.
