# Handoff: Plan tab — tide alignment, summarised

## Overview

A **small increment on the Plan tab (v5)**, and a companion to the Map tab tide work in
`design_handoff_tide_window/` — which is **already in implementation**. This bundle does not
restate that design and does not change it. It adds two marks to the window card.

**The split between the two pieces, because it decides everything here.** The map answers
*what is the water doing, and does it suit this spot* — that is a curve, and it gets a curve.
The plan grid compares windows, so what it needs from tide is **a number you can scan down a
row**: is the coast live on this one, and if several are, which do I take. Six cards side by
side cannot each carry a curve and stay readable, and they do not need to.

**The change.**

| Mark | Where | What it says |
|---|---|---|
| Wave glyph on the named spot | before the spot name the card already prints | the place this card is sending you gets the water it wants |
| `〜 N on tide` chip | the topics line | N reachable coastal locations get their water on this window |
| Emphasis + `best of N` on that chip | same chip, on one window | of the live windows in this run, this is the one to take |

Both marks sit in slots the card already had. **No new row, no new caption, nothing
re-scored, nothing re-coloured.**

### Prerequisite

The `want` field and the level/fit model from `design_handoff_tide_window/` README §1. This
bundle **reuses them unchanged** — same enum, same cosine interpolation between tide extremes,
same band thresholds (0.75 / 0.25), same fit tiers (0.80 / 0.62). If that work has shipped,
this is a consumer of it and adds no data. `plan-tide-v6.js` duplicates the model only so the
prototype runs standalone; **do not port it twice.**

## About the design files

| File | What it is |
|---|---|
| `Plan Tide Summary.html` + `plan-tide-v6.js` | **The design.** A working prototype — design reference, **not production code**. Recreate in React with this repo's patterns (`components/HeatmapGrid.jsx`, `components/DailyBriefing.jsx`, `hooks/useIsMobile.js`). Do not port imperative DOM code. |
| `heat-field.js`, `plan-data.js` | Unchanged kernel and mock catalogue, so the prototype runs. **Do not re-port or re-tune.** |

The notes column is part of the specification. Two notes are `cut` notes and the rejections in
them are load-bearing — §5.

## Fidelity

**High.** Every threshold, hex, gate and copy string below is the value in the prototype.
Items flagged `OPEN` need a decision.

---

## 1 · The glyph on the named spot

The card already names one place: *the best-rated location you can actually reach on this
window*. If that location is coastal and its tier is `match`, the same wave glyph the map uses
sits before the name.

```
bs   = reachPool(window).sort(by rating desc, then drive asc)[0]     // already existed
show = bs.coast && tideFit(bs, window).tier === 'match'
```

| Property | Value |
|---|---|
| Glyph | `viewBox="0 0 14 8"`, rendered 13×7, `color:#8FC0C7`, `margin-right:5px`, `vertical-align:1px` |
| Name colour when marked | stays `var(--ink)` — the glyph carries the signal, not a recolour |
| Tooltip gains | ` · high water on the light` (the want, not the offset) |

**A miss is silent here.** The card's pick is chosen on *score*; marking it down for tide in the
same line would put two rankings in one slot. The chip below carries the coast-wide picture, and
the map carries the per-spot detail. This slot answers one question only: *will the place it is
sending me work?*

---

## 2 · The chip

Alignment is a fact about a **night**, and the topics line is the card's line for facts about a
night — so this is a topic chip, reusing `.tw` with `--tc:#8FC0C7`, sitting first in the line
before Aurora / NLC / King tide / Inversion.

### Copy

```
〜 9 on tide                       // a live window
〜 9 on tide │ best of 3           // the one to take, emphasised
```

Tooltip: `9 of 14 coastal locations in reach get the water they want — high water, rising,
3.9 m at 20:29 · 3 windows in this run are live · the most of any of them`.

### The gate

```
live(window) = coastalInReach >= 4
            && matched >= max(3, ceil(coastalInReach * 0.5))
```

Half the coastal locations in reach, floor of three. **A flat floor of three put a chip on all
six cards** in the first build, which is a caption rather than a signal; tying it to the share
also means it tightens sensibly as the reach filter does. Below the gate the card says nothing
at all — no greyed chip, no zero.

### Counts are over reachable, named locations

The same pool the card's rating, spread histogram and named spot already use
(`reachPool`). A tide that suits eleven locations you cannot get to is not a fact about your
morning. Consequence: **the marks move when the drive filter moves.** That is intended — see
`OPEN 1`.

---

## 3 · The run, and which day of it to take

This is the part that earns the emphasis. The tide runs on a roughly fortnightly beat, so a good
phase puts the coast live on **several consecutive windows** — three of six in the prototype's
forecast. The useful question stops being *is the coast live* and becomes **which of these do I
take**, and that cannot be answered by the count alone: in the prototype all three live windows
suit **the same nine locations**.

### Ranking

```
rank = matched DESC, then meanFit DESC, then window index ASC

meanFit(window) = mean over MATCHED coastal locations of ( 1 - |level - target[want]| )
```

Count first (more spots served is better), then **how well** those spots are served, then the
earlier window — a tie on both is a genuine tie, and the earlier one is the one you can act on
sooner. In the prototype this moves the emphasis from tonight's sunset to **Thursday sunset**,
where high water lands 17 minutes after the light rather than nearly an hour off it. Ranking by
count alone silently picked the first of three identical numbers.

### The emphasis

Only where `live > 1`, and only on the winner. `best of N` counts the **live windows in the
forecast**, not the calendar length of the tidal run.

| Property | Value |
|---|---|
| Ink | `--tc:#B4DDE2` (from `#8FC0C7`), glyph follows |
| Weight | `700` (from `600`) |
| Pill | `padding:1px 8px; margin:-1px -2px; border-radius:999px; background:rgba(111,168,176,.15); box-shadow:inset 0 0 0 1px rgba(111,168,176,.42)` |
| `best of N` | `.tdr`, `font-weight:600`, `color:rgba(180,221,226,.8)`, `padding-left:6px`, `border-left:1px solid rgba(111,168,176,.45)` |

Measured contrast: chip 6.55:1, `best of N` 5.44:1 on the pill.

**A rank on every chip in the run was rejected** — three claims to read instead of one. The
non-winning live windows say only their count, which is already the comparison.

---

## 4 · Design tokens

No new values beyond two tints of the existing tide colour.

| Token | Value | Use |
|---|---|---|
| `--tide` | `#6FA8B0` | glyph default |
| — | `#8FC0C7` | chip ink, glyph on the named spot |
| — | `#B4DDE2` | emphasised chip ink |
| — | `rgba(111,168,176,.15)` / `.42` | emphasis pill fill / hairline |
| `--ink` | `#F2E7D3` | the spot name, marked or not |
| `--mono` | IBM Plex Mono | both marks |
| `.tw` | existing topic-chip class | 10.5px / 600 / `--tc` |

---

## 5 · Rejected, and why it matters

### The `Tide` legend on the card border — built, tested, cut

A third mark rode the card's border, naming the one window where the coast was most live. Two
placements were tried: **top-left**, where it landed on the card's own `SUNSET` kind chip and
read as part of the row rail, and **bottom-left**, where it stacked directly under the chip and
said the same thing twice. Both had to be *searched for*, and that — not the placement — was the
finding.

The structural reason: that slot belongs to the **pick system**, which is a *ranking* — one
`Best bet` per forecast, mutually exclusive with `Also good`, and it is what the grid is ordered
by. Tide is not a ranking, it is a per-window count. A badge derived from it either reads as a
pick it is not, or gets skipped as chrome.

**The general rule this produced, which is the reusable part:** a new element announces a new
*kind* of fact; a stronger version of the *same* fact should look like the same element, louder.
The legend's one extra fact — *this is the best of the live windows* — is a difference of degree,
and the chip already states the degree, so it belongs in the chip. If a future increment wants
to promote tide further, **raise the emphasis; do not add an element.**

The `〜 Best tide, emphasised` toggle above the prototype frame turns the emphasis off so the
plain chip and the emphasised chip can be compared at your own screen width. It is a design
control, not a feature — do not ship it.

### Not folded into any score

Not the ★ rating, not the verdict word, not the spread histogram, not the border colour, not the
thumbnail field. Same reason tide does not dim the heat on the map: it would make a genuinely
clear morning read as a worse forecast, and the two facts could never be separated again.

---

## 6 · Open items

**OPEN 1 · the marks move with the reach filter.** All three are computed over *reachable named*
locations, so tightening `45 min` can drop a chip and move the emphasis. Correct in principle,
but it means the "best of N" claim is not stable across filter changes. Decide whether the
ranking should be computed over the whole **scope** (stable, comparable between sessions) with
only the count reach-bound, or left fully reach-bound as built. The prototype is fully
reach-bound.

**OPEN 2 · the run does not know it is a run.** `best of N` counts live windows *in the
forecast*, which is a four-day slice of something that may be a six-day tidal run. `design_handoff_tide_runs/`
already specifies a `SPRING RUN n/4` chip for exactly this framing on Hot Topics. Decide whether
these two should share a vocabulary before both ship — ideally yes, and ideally the plan chip
should be able to say the run continues past the forecast.

**OPEN 3 · one enum is not always enough.** `want: 'high' | 'mid' | 'low'` cannot express "high
water, but not a spring — the spray blows out the filter" or a spot that works at both extremes
and not in between. Ship the enum, but watch for the first user who needs a range.

**OPEN 4 · phone.** The prototype's phone layout stacks cards day by day and the topics line
wraps, so an emphasised chip plus three topics can take two lines on a 390px card. It fits, but
check it against a real four-topic night before ship.

---

## 7 · Verify by measurement

Report numbers, not screenshots.

1. **Gate.** Number of cards with a chip equals the number of windows satisfying
   `matched >= max(3, ceil(coastal*0.5))`. In the prototype's default state: **3 of 6**.
2. **Exactly one emphasis.** `.tdtop` count is 1 when any window is live and `live > 1`, else 0.
3. **Ranking is not first-past-the-post.** With the counts tied across live windows, the
   emphasised window is the one with the highest `meanFit` — in the prototype, Thursday sunset,
   not tonight.
4. **Glyph is match-only and silent on a miss.** A card whose named spot is coastal and mismatched
   shows no glyph and no text about it.
5. **No score moved.** ★ rating, verdict word and spread histogram are byte-identical with `want`
   set to each of high / mid / low.
6. **No overflow.** No chip exceeds its card's content width at desktop, iPad and phone.
7. **Contrast.** Emphasised chip ≥ 4.5:1 on its pill; plain chip ≥ 4.5:1 on the card.

---

## 8 · Files

| File | Notes |
|---|---|
| `Plan Tide Summary.html` | The design. Notes column is part of the spec; the two `cut` notes are the reasoning. |
| `plan-tide-v6.js` | Behaviour. Model and `tideStats` / `tdShowOf` / `tideLive` / `bestTideWin` near the top; the two marks inside `card()`. |
| `heat-field.js`, `plan-data.js` | Unchanged. |

### Screenshots

| File | State |
|---|---|
| `screenshots/01-sunset-row.png` | The sunset row: plain `〜 9 on tide` on two live windows, the emphasised `〜 9 on tide │ best of 3` on Thursday sunset, and the glyph on tonight's named spot. |
| `screenshots/02-best-of-card.png` | The winning card close up — emphasis and `best of 3` alongside `Best bet`, which it coincides with here. |
| `screenshots/03-plain-chip.png` | The same card with the emphasis toggled off, for the comparison §5 describes. |

Existing code this touches: `components/HeatmapGrid.jsx` (the card), `components/DailyBriefing.jsx`
(the window popup's one tide line), `api/tideApi.js` (`fetchTidesForDate`, `fetchTideStats`).
