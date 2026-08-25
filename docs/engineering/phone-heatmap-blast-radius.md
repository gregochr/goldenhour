# The phone heatmap: how far the change reaches

**Status: historical.** Written 2026-08-11 while v1 and v2 ran side by side; v1 (`DailyBriefing.jsx`
and its `:1555`/`:1526` gates below) is deleted entire (`docs/engineering/v1-retirement-plan.md`),
so only the v2 row of every comparison below still describes the running app.

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

**Removing the two `hidden sm:` classes cannot reach v1.** Not "should not" — cannot. v1's only route
to the grid is the "Open full table" expander, and that button is itself inside the `display: none`
ancestor below 640px. Measured at 390px: `wrapperDisplay: "none"`, expander not clickable,
`gridPresent: false` — the grid is never mounted, so what its own classes say is irrelevant. Above
640px the ancestor is `display: block` and the `sm:` classes were already inert.

### ⚠️ But the rest of the change *did* reach v1, and saying otherwise was the first draft's mistake

The two classes are not the whole change. A `minmax(96px, 1fr)` floor, a scroll port and
`min-width: max-content` are all inside the **shared component**, which v1 mounts at every width
≥ 640px. Measured on the running app, before the fix:

| v1 viewport | event column *before* | *after*, unconditional floor |
|---|---|---|
| 640px | 68.3px, squeezed, never overflowed | 96px **+ horizontal scroller** |
| 720px | 81.7px | 96px + scroller |
| 780px | 91.7px | 96px + scroller |
| 830px and up | 100 / 111px | unchanged |

So the arm the redesign is being *compared against* silently changed across a ~165px band containing
iPad portrait — and the two widths this doc originally offered as its regression test, 390 and 1280,
are precisely the two that bracket that band without entering it. A regression test that cannot fail
is worse than none, because the next reader trusts it.

**The fix is a `scrollable` prop on `HeatmapGrid`, defaulting to `false`.** `WindowFirstRegionalPanel`
passes it; `DailyBriefing` does not. Every rule is scoped under `.heatmap-scroller`, which is the only
class the flag toggles, so the `heatmap-pin`/`heatmap-span` hooks can stay emitted unconditionally
(`HeatmapDrillDown` is a separate component and would otherwise need the flag threaded into it) and
simply match nothing without the port. Re-measured after the fix, v1 reproduces its pre-change
numbers exactly at 640 / 680 / 720 / 780 / 830 / 900 / 1280 — no port, no overflow, same column
widths. **That** is the regression test: seven widths, not two, and it includes the band.

The general lesson, which is the reason this file exists: *a shared component's blast radius is not
the diff's most conspicuous line.* The `hidden sm:` removal was the obvious change and was the one
that could not reach v1; the one-token track-list edit was the one that could.

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

### Four more the adversarial review caught, all reproduced before fixing

1. **`.heatmap-span` was unscoped, and a container-relative length fails *silently*.** `100cqw`
   resolves against the small viewport when no query container matches, so in v1 — which declares no
   container — the drill-down rendered at `100svw`: **measured 1280px wide inside an 830px grid**,
   spilling out of the card. Now scoped, and `cqw` → `cqi`, the unit that names the axis an
   `inline-size` container actually provides.
2. **A focused cell landed entirely underneath the pinned column.** Focus scrolls by the *minimum*
   amount, putting the cell's left edge at x = 0 — exactly where the opaque 140px pin sits. A 96px
   cell was not clipped but hidden, focus ring and all (WCAG 2.4.11), on **every** row transition,
   since tabbing off a row's last cell moves focus leftwards to the next row's first. Fixed with
   `scroll-padding-left: 144px` — the horizontal twin of the `scroll-margin-top: 60px` this codebase
   already buys for the sticky lens bar — plus a `:focus-visible` elevation so a stale number
   degrades to "overlapped" rather than "invisible". Verified across all 24 cells: minimum left gap
   143px against a 140px pin.
3. **The drill-down fitted the port; its own header did not.** One shrinkable child and five that
   cannot shrink meant 424px of content in a 302px panel, with the 🗈 "Show on map" button **123px
   past the right edge** — and because the panel is sticky, scrolling took the button with it, so it
   was unreachable at every scroll position, not merely clipped. Fixed with `flex-wrap` on that row,
   scoped to the port; it is self-limiting, so it stays inert at 1016px.
4. **`opacity: 0.06` on a faded region label composited the pin's own background** down to 6%, so
   scrolling cells read straight through the one column that exists to stay readable. The fade moved
   to an inner span; `aria-hidden` stayed on the outer box.

Also corrected rather than fixed: the P9 note claiming `.wf-lens` is "the only `position: sticky` in
the app" with no stacking context between it and the cells. Both clauses died here —
`container-type: inline-size` applies `contain: layout`, which creates one — so
`.wf-door-panel .heatmap-cell-hoverable:hover { z-index: 10 }` is now redundant in v2. Kept, because
removing `container-type` would re-arm the defect it was written for.

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

## Found, deliberately not fixed — recorded so they are decisions rather than oversights

- **The grid has no table semantics, and now that is the phone's only view of the plan.** In 1173
  lines there is no `role="grid"`, `row`, `columnheader` or `rowheader`; a cell is a `role="button"`
  div whose accessible name is its own text (`"Worth it sunset ☁-8°C 28mph 3 king tides 4.5★"`) with
  no region and no date in it. So the row identity the pinned column exists to preserve has never
  existed for a screen reader — VoiceOver gets ~42 near-identical buttons. **This is pre-existing and
  affects both arms equally**, which is exactly the species CLAUDE.md hands to the pre-pilot sweep to
  be decided once across both. Worth naming here because it also weakens the WCAG 1.4.10 argument
  below: two-dimensional scrolling is permitted for content that *needs* it, and a data table is the
  canonical example — but this only reads as a table to sighted users.
- **The poor-regions toggle's focus ring clips left and right.** It is `sticky; left: 0` filling the
  port, and the port buys ring room only at the bottom. The obvious fix — horizontal padding plus a
  negative margin, as `.rail-scroller` does — moves the padding box that `left: 0` resolves against,
  which would put a gap beside the pinned column where cells show through. Cosmetic, partial (three
  sides of the ring survive), and the safe fix is not obvious; left alone rather than traded for a
  worse defect.

## Verified on WebKit — the first time in this series

Every phase from P4 to P15a recorded "no iOS Safari, ever" as an accumulating gap, and two reviewers
named sticky-on-a-grid-item as the highest-value thing left to check: its containing block is derived
from the grid box, and that derivation is exactly where engines have historically diverged. If WebKit
disagreed, the pinned column simply would not pin on the owner's phone.

**It agrees.** Playwright WebKit 26.5 at 390×844 with `hasTouch`, against the running app:

| | Chromium | WebKit |
|---|---|---|
| port / content | 302 / 740 | 302 / 740 |
| pinned column after a full 438px scroll | holds at x=0 | **holds at x=0** |
| `100cqi` on the spanning items | 302px | **302px** |
| page itself overflows | no | no |
| real `tap()` on a cell | — | **no tooltip, drill-down opens at 302px, no overflow** |

`(pointer: coarse)` reports true there, so the touch fix is confirmed on the engine it was written
for rather than inferred from spec.

⚠️ **This is WebKit, not an iPhone.** Same engine, but not the same device: no real touch surface, no
iOS Safari chrome, no dynamic viewport units in play, no VoiceOver. It removes the engine risk, not
the device risk.

**Getting a WebKit run at all needs one step**, and the failure mode wastes time: the build cached on
this machine (`webkit-2248`) is version-mismatched against the installed Playwright, and **hangs on
launch rather than erroring**. `npx playwright install webkit` fixes it (~83 MB).

## Still not verified

- No real device, no screen reader, no axe, no Lighthouse, no forced-colors, nothing above 1440px.

## What this does *not* do

- It does not touch `DailyBriefing`, and it must not: the frozen arm is what the comparison is against.
- It does not flip the flag default. Still v1, still a separate later change.
- It closes **one** manifestation of the rem/px seam rather than the seam itself. `WindowFirstDoors`
  gated the door on `useIsMobile` (`max-width: 639px`, **px**) over content hidden at Tailwind `sm:`
  (**40rem**); with the content no longer hidden the gate goes, and with it that file's disagreement.
  `useIsMobile`'s other callers are untouched and the seam is still live for them.
