# Handoff: Plan card — Spread row

## Overview
A small, high-fidelity change to the **Spread** row on every Plan tab card (sunrise/sunset window cards). Two changes:

1. **Dynamic label.** `Spread` now states the pool it was counted over: `Spread (within 45m)`, `(within 1h30)`, `(within 2h30)`, `(any drive)` — following the drive/reach filter in the lens bar.
2. **Counts surfaced.** The 1★→5★ histogram previously hid its counts in a hover `title`. Each bar now prints its count underneath. The `title` stays as a fallback.

Nothing else on the card changes: verdict word, rating pill, best-spot name, tide chip, topics, thumbnail.

## About the design files
The files in this bundle are **design references built in HTML** — a working prototype of intended look and behaviour, **not production code**. Recreate the change in the existing codebase using its patterns (the Plan grid / card components, e.g. `components/HeatmapGrid.jsx`, `components/DailyBriefing.jsx`, `hooks/useIsMobile.js`). Do not port the prototype's string-template rendering.

## Fidelity
High-fidelity. Match sizes, colours and type exactly.

## The row

### Structure
The card's info block `.pls` is a 2-column grid (`auto | 1fr`): label left, value right. The rating pill on the best-spot row sits in that same left column.

**The Spread row must span both columns** (`grid-column: 1 / -1`) as its own flex row with `justify-content: space-between; gap: 8px`. If the longer label sits in the shared left column it widens it, and the `5★ <Location>` row below spreads out — that was a regression and is the reason for the wrapper.

```
.sprow  (grid-column 1/-1, flex, space-between, gap 8px)
├── .k2.sk   label, flex column, 2 lines
│   ├── "Spread"
│   └── <small> "(within 45m)"
└── .pv      right-aligned
    └── .hist   flex row, align-items:flex-end, gap 2px
        └── .hb × 5   flex column, justify-content:flex-end, gap 2px, width var(--cw), height 25px
            ├── <i>    bar
            └── <em>   count
```

### Label
| Part | Spec |
|---|---|
| Both lines | mono, 8.5px, weight 600, uppercase, colour `--ink-3` |
| `Spread` | letter-spacing `.1em` |
| qualifier `<small>` | letter-spacing `.06em`, `white-space: nowrap` |
| Stack | flex column, `align-items:flex-start`, `gap:1px`, `line-height:1.15` |

Qualifier copy, keyed by reach value:

| Reach | Text |
|---|---|
| `45` | `(within 45m)` |
| `90` | `(within 1h30)` |
| `150` | `(within 2h30)` |
| `any` | `(any drive)` |

When planning from a non-home origin the drive times are already from that origin; the label copy does not change.

### Bars
| Prop | Value |
|---|---|
| Bar width | 7px, radius 1px |
| Bar height | `max(2, round(count / maxCount × 13))` px; empty bucket = 1px |
| Bar colour | heat ramp at that star value (`rgb(ramp(k+1), .92)`), empty = `rgba(242,231,211,.13)` |
| Column gap | 2px |
| Column height | 25px (bar + 2px + count) |

### Counts
| Prop | Value |
|---|---|
| Type | mono, 9px, weight 600, `line-height:1`, letter-spacing `-.06em` |
| Colour | `--ink-2`; zero counts `--ink-3` at opacity .6 |
| Format | `< 1000` raw; `< 9950` → one decimal + `k` (`1.2k`, trailing `.0` dropped); else rounded `k` (`12k`) |

### Scaling with catalogue size
Counts grow as locations are added, so the column width is computed per card:

- `chars` = longest formatted count on that card
- `--cw = clamp(9px, chars × 5.2px, 17px)` — every column uses the same width so bars stay evenly spaced
- If `chars > 3`, counts drop to 7.5px with letter-spacing `-.1em` (class `.hist.sm`)
- `.pv` keeps `overflow:hidden` as a safety net — the histogram must never draw over the label

Worst case on the narrowest card (iPad, `.pls` 177px wide): 5 × 17 + 4 × 2 = 93px, which fits beside the label.

### Tooltip
Keep the existing `title` on `.hist`: `"{n} locations within reach — {c5} at 5★, {c4} at 4★, …"`.

## Data / state
No new data. Counts come from the same **reachable named** pool the card's verdict, best spot and tide chip already use (`scope ∩ named ∩ driveTime ≤ reach`), **not** filtered by the rating floor. Re-render the row when the reach filter or origin changes.

## Responsive
Same markup on desktop, iPad and phone. In the "sideways" card layout the `.pls` grid rows are `21px 27px 32px 1fr` (Spread row is 27px, up from 20px).

## Screenshots
- `screenshots/01-within-45m.png` — reach 45 min: single-digit counts
- `screenshots/02-within-2h30.png` — reach 2h30: two-digit counts
- `screenshots/03-any-drive.png` — reach any: `(any drive)` label

## Files
- `Plan Tide Summary.html` — prototype + CSS. Search `.sprow`, `.k2.sk`, `.hist`.
- `plan-tide-v6.js` — `RSHORT`, `fmtN`, `histOf()` and the Spread row inside `card()`.
- `heat-field.js`, `plan-data.js` — unchanged kernel and mock data, included so the prototype runs. Do not port.
