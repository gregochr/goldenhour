# The phone heatmap: how far the change reaches

*Written 2026-08-11, before any CSS, because `HeatmapGrid` is shared with the frozen v1 arm and the
owner is running a side-by-side comparison of the two. Every number below was measured on the running
app (headless Chromium, local backend on 8083) rather than read off the source.*

## The report

> *"the desk top view gives me access to the full plan at the bottom of the screen - is that
> deliberately left out of the phone?"*

Reproduced at 390px:

| arm | 390px | 1280px |
|---|---|---|
| v2 | doors render (330×65), **Regional door absent** | Regional door 518×65 |
| v1 | "Open full table" expander **zero-box**, grid **not in the DOM** | expander 134×29, grid 830×310.5, 24 cells |

Nobody decided a phone reader does not need the full plan. `HeatmapGrid` has no phone layout, and both
arms inherited that.

## The two gates, and which arm each belongs to

`HeatmapGrid` has exactly two responsive classes in 1142 lines — `hidden sm:grid` (`:973`, the grid)
and `hidden sm:flex` (`:1099`, the away band) — and exactly two call sites:

| call site | arm | how the phone case is gated |
|---|---|---|
| `DailyBriefing.jsx:1555` | v1 (frozen) | an **ancestor** at `:1526` is `<div className="hidden sm:block">` |
| `WindowFirstRegionalPanel.jsx:126` | v2 | `WindowFirstDoors`' `!isMobile` hook, plus the component's own two classes |

**The change cannot reach v1.** Not "should not" — cannot. v1's only route to the grid is the
"Open full table" expander, and that button is itself inside the `display: none` ancestor below
640px. Measured at 390px: `wrapperDisplay: "none"`, expander not clickable, `gridPresent: false` —
the grid is never mounted, so what its own classes say is irrelevant. Above 640px the ancestor is
`display: block` and the component's `sm:` classes were already inert. There is no width at which
editing those two classes changes a pixel or a DOM node in v1.

So `HeatmapGrid`'s `hidden sm:` pair is **redundant in v1 and load-bearing only in v2**. Removing it
is a v2-only change wearing shared-component clothing. This is recorded because the reverse
assumption — that touching a shared component necessarily disturbs the frozen arm — would have
pushed this work into a fork of a 1142-line file, and two grids to keep in step for the length of the
pilot.

⚠️ **The v1 baseline above is the regression test.** Re-measure it after the change and diff; a v1
number that moves means the reasoning here is wrong, not that the number is noise.

## What the phone layout is, and why

The design's § "Responsive Behaviour" has **nine** bullets and **none of them is the heatmap** — this
layout is not in the mock, so it is designed here rather than transcribed. What the spec does supply
is a settled idiom for wide content on a phone, used three separate times (rail, tab bar, lens bar):
`overflow-x: auto`, `flex-wrap: nowrap`, scrollbar hidden.

The grid takes the same idiom: **a horizontal scroller with the region column pinned.** Rejected
alternatives, with the reason each lost:

- **Stack into one block per day.** Reads better on a phone and needs no sticky column — but the
  grid's DOM order is region-major (`region label, 6 cells` per row), so re-stacking it day-major
  cannot be done in CSS. It needs a second render path through a shared 1142-line component, which is
  a great deal more risk than the gain over scrolling.
- **Shrink to fit.** Measured envelope at 390px is **302px** inside `.wf-door-panel`. Seven columns
  in 302px gives 34px per cell against content that needs ~80px (the widest line is the weather
  clause, `☀14°C 8mph` at 10px mono). Not close.
- **Drop columns on a phone.** Silently answers a different question than the desktop does, on the
  one surface where the reader cannot see what was dropped.

### The measured envelope

`.wf-door-panel` inner width: **232px** at 320 · 272 at 360 · 287 at 375 · **302px at 390** · 324 at
412 · 342 at 430 · 551 at 639.

Event columns get a **96px floor** (80px of content inside `px-2`), which fits every line a cell
draws — the widest is the weather clause, `☀14°C 8mph` at 10px mono. Expressed as
`minmax(96px, 1fr)`, so `1fr` still wins wherever there is room and **no media query is involved**:
at 1280 the fr resolves to 142px, arithmetically identical to before. That matters beyond elegance —
`gridTemplateColumns` is an inline style, and a media query could not have reached it.

The region column resolves to its **140px** max, not the 100px floor first assumed: under
`min-width: max-content` (below) a `minmax()` track takes its maximum. That is a better outcome —
"Northumberland" alone measures ~92px at 13px, so 140px wraps region names cleanly onto two lines
with no mid-word breaks (verified on screen).

### Three things only measurement caught

1. **`position: sticky` on a grid item silently does nothing here.** A sticky item is clamped to a
   containing block derived from the grid *box*, and the grid box stays the port's 302px while the
   *tracks* overflow to 751px — so the pinned column had almost no travel and slid away. Measured at
   **x = −196** against a port at x = 0, i.e. gone. Fixed with `min-width: max-content` on the grid,
   which makes the box as wide as its tracks. `min-width` and not `width`: under `width: max-content`
   the `1fr` tracks size to their content and desktop collapses. Both branches were measured, in an
   isolated four-way probe, before either went into the repo.
2. **The pinned column's background must be the one actually behind the grid.** Anything else paints
   a visible block at desktop, where the column is pinned but never travels. `--color-plex-panel` was
   the first choice and drew exactly that — most obviously at the empty sub-header corner. The
   correct value is `--color-plex-surface`: measured, the nearest painted ancestor is
   `rgb(34, 26, 21)` in **both** arms (`.wf-door-panel` in v2, `.card` in v1), so one literal serves.
3. **Both `grid-column: 1 / -1` items need pinning too** — the drill-down (`:351`, the "full regional
   briefing" the door promises) and the poor-regions toggle (`:1086`, the sole affordance for the
   pooled rows). At the phone sizing the grid is 751px, so a spanning item is 751px inside a 302px
   port. The drill-down is the worse case: you open it by *tapping a cell*, so you are already
   scrolled right when it renders from the grid's x=0. Both take `position: sticky; left: 0` plus
   `width: 100cqw` — the **scroll port's** width, which is why `container-type: inline-size` is on
   the port rather than the grid.

### Rejected: scroll-snap on the columns

Mid-scroll a cell is half-hidden behind the pinned column, so its text truncates on the left, which
reads worse than the right-hand crop the rest of this app uses as its scroll affordance. Snapping
each event column clear of the pin would fix that, and it is deliberately not done: a whole day
(sunrise + sunset = 200px) **does not fit** beside the 140px pinned column in a 302px port, so
snapping per column would half-cut the day header that spans the pair — trading one imperfect
mid-scroll state for another, at the cost of a magic number coupled to a track width. The resting
state, which is what a reader actually opens onto, shows the pinned column, one whole event column
and the next cropped at its right edge: the affordance this project already uses (the spot strip is
sized to 4.5 cards for the same reason).

## What this does *not* do

- It does not touch `DailyBriefing`, and it must not: the frozen arm is what the comparison is against.
- It does not flip the flag default. Still v1, still a separate later change.
- It closes **one** manifestation of the rem/px seam rather than the seam itself. `WindowFirstDoors`
  gated the door on `useIsMobile` (`max-width: 639px`, **px**) over content hidden at Tailwind `sm:`
  (**40rem**); with the content no longer hidden the gate goes, and with it that file's disagreement.
  `useIsMobile`'s other callers are untouched and the seam is still live for them.
