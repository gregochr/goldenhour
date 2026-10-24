# Four-day strip vs the tide strip: a press on the held header opens it (plan)

Status: planned 2026-10-24, revised after a two-lens adversarial review the same day. One PR.
Desktop and tablet only (the panel variant); the phone is unchanged.

## 1. The defect

The owner pressed the Map tab's **"Hot topics · next four days"** header and nothing happened.

- `MapView` passes `forceCollapsed={tideStripModel.visible || landingOpen}` to the panel
  `MapTopicStrip` (hot-topics-by-day-plan Q8). While forced, the header is `aria-disabled`, the
  `toggleOpen` press returns early, the ▾/▴ chevron is not drawn, and the reason
  ("Closed while the tide strip is showing") is `sr-only`.
- `stripModel().visible` (`utils/mapTideFit.js`) is true for **any** solar window with a served tide
  and a coastal spot in view. On the default northern view that is nearly every sunrise and sunset, so
  on desktop the four-day grid is close to unreachable, and a sighted reader sees a live-looking
  header that does nothing, with no word why.

Q8's intent stays: by default the grid never opens on its own over the tide strip (an open grid over an
open tide strip costs up to −37.1% of labels; `hot-topics-by-day-plan.md` §6 label table). What changes
is that **the reader's own press on the header is honoured, for the window on screen**.

## 2. The design: an open tide strip holds the grid; a press on the grid's header yields it, for this window

### State (all in `MapView`)

- `tideStripCollapsed` — unchanged: the tide strip's OWN toggle, session-lived, survives window
  changes on purpose (tide-window-plan T6 §4 #12).
- **New `tideYieldRowId`** (`useState(null)`): the id of the EV row on which the reader asked for the
  grid over the tide strip. Derived: `tideYielded = tideYieldRowId != null && tideYieldRowId === activeMapEvent?.id`.
  It is **cleared when the window moves away**, during render and not in an effect (React's adjust-state-on-
  change pattern, keyed on the active row id CHANGING), so the yield is **scoped to one window** and coming
  back to that window later does not revive it. Keying on the change, not on a mismatch, means a strip press
  that sets the yield to the target row before the row arrives (`selectEvRow` hands the date to the parent
  first) is not cleared by an intermediate render.
- The tide strip renders `collapsed={tideStripCollapsed || tideYielded}`.
- The four-day strip is **held** (drawn closed, header still pressable) while
  `tideStripModel.visible && !tideYielded`. This keys on `visible` exactly as Q8 does today, so a
  reader who collapses the tide strip with its own toggle does **not** pop the grid open (review
  finding: that combination was never measured and would happen without any press on the grid).

### Presses

| Press | Effect |
|---|---|
| Grid header while held | `setTideYieldRowId(activeMapEvent.id)`; grid preference stored '1'. The grid opens and the tide strip shows its collapsed row. Focus stays on the header. |
| Grid header while open and yielded | Grid preference stored '0' **and** `setTideYieldRowId(null)`: the tide strip returns open, so the reader is never left with both closed by a side effect. |
| Grid header while open, not held (no tide on this window) | As today (store '0'). |
| A bar, label or HW tick in the **open** grid that moves the window | Set `tideYieldRowId` to the **target** row's id in the same handler (a press can only come from an open grid, which is never held) (`pickTopicFromStrip`, `selectHwFromStrip`), so the grid the reader is using is not snatched shut on arrival at a tidal window. Panel variant only; the sheet handlers do not set it. |
| Tide strip "Open ▴" while yielded | `setTideYieldRowId(null)` and `setTideStripCollapsed(false)`: the tide strip opens, the grid falls back to held. The grid preference is untouched. |
| Tide strip toggle otherwise | As today. |
| Window change from anywhere else (window control, arrows, map, Ask…) | The yield is cleared as the window moves away: the tide strip is back to its own state, the grid is held again if the tide strip shows (and stays held on returning). |

The landing (welcome) card stays a **true forced collapse** (`forceCollapsed={landingOpen}`), header
`aria-disabled`, press inert. Landing + tide: forced wins.

### Full state table (panel, landing closed)

| tide visible | tideYielded | stored pref | grid | tide strip |
|---|---|---|---|---|
| no | – | '1' / absent+tall | open | absent |
| no | – | '0' / absent+short | closed (pressable, as today) | absent |
| yes | no | any | **held**: closed, pressable, ▴ drawn | own state (`tideStripCollapsed`) |
| yes | yes | '1' (a held press stores it) | open | collapsed row |

### Component API (`MapTopicStrip`)

- `forceCollapsed` / `forceReason`: kept, now only ever the landing case from `MapView`.
  **Visible cue:** while forced, the chevron slot inside the header button shows a short muted
  `aria-hidden` word, `· closed`, instead of nothing; the full reason stays the `sr-only` sibling span
  referenced by `aria-describedby` (unchanged element and position, so no extra row, no change to
  `--wf-chrome-bl-h` or the landing card's height cap, and the button's accessible name is unchanged).
- **New `held`** (bool, default false) and **`heldReason`** (string): drawn closed but pressable. While
  held: `open` is false whatever the stored preference; the header is enabled, `aria-expanded="false"`,
  no `aria-controls`, chevron ▴ drawn, and `aria-describedby` points at an `sr-only` sibling span
  "Opening it collapses the tide strip" (built from `heldReason`, e.g. `'the tide strip'`). Nothing is
  added inside the button (label in name).
- **New `onOpenHeld`**: `toggleOpen` while held calls `setPref(true)`, `writeOpenPreference(key, true)`
  and `onOpenHeld()`. The strip does NOT open itself locally; it opens because `MapView` re-renders with
  `held=false` (a unit test pins that the grid stays closed until that re-render).
- **New `onCloseYielded`** (optional): `toggleOpen` closing an open strip calls it after the
  preference write; `MapView` passes `() => setTideYieldRowId(null)`.
- `open = stored && !forceCollapsed && !held`. `forceCollapsed` beats `held`.
- The sheet variant is never passed `held`, `onOpenHeld` or `onCloseYielded`, and ignores them if it is
  (a unit test).

### Focus

- **The tide strip toggle loses focus today (review blocker, pre-existing).** `MapTideStrip`'s collapsed
  and open forms are separate returns; the toggle button is a different element in each
  (`MapTideStrip.jsx` ~218 vs ~558), so pressing "Open ▴" or "Collapse ▾" remounts it and focus drops to
  `<body>`. Fix in this PR: when the press came from the toggle (a ref flag set in the click handler),
  a `useLayoutEffect` on `collapsed` focuses the new `wf-tide-strip-toggle` (`preventScroll: true`).
  Tests in both directions.
- Grid header press while held: the header stays mounted; focus stays on it.
- Focus inside the open grid when it becomes held (the reader presses the tide strip's Open): focus is
  on the tide toggle, not the grid. The existing "focus in the grid when it goes away → header"
  effect still covers any other route; add a test that `held` becoming true with focus in the grid
  moves focus to the header.

### Label cost

Grid open + tide strip collapsed is a **new** combination, reached only by the reader's press for one
window. Measure it: add a `bl+tide-collapsed+open` surface to
`scripts/measurements/label-obstacle/` (harness + `analyse.mjs`), mark the old `bl+tide+open` surface
as still unreachable (an open tide strip always holds the grid), run `measure.mjs` and `analyse.mjs`,
and record the figures in §5 below and in `hot-topics-by-day-plan.md` §6. It is accepted as the
reader's explicit choice whatever it measures; the number is recorded, not gated.

### Considered and rejected

- *Only make the reason visible, keep the press inert.* It answers "why", but the grid stays
  unreachable on desktop on almost every window, which is the real problem.
- *The grid wins for the session (collapse `tideStripCollapsed` itself).* One press would leave every
  later coastal window with the tide strip collapsed, inverting Q8 for the session as a side effect.

### Not changed

- The phone (`variant="sheet"`; there is no tide strip on phones).
- `stripModel`, the tide strip's content, `onHeightChange`, `MapCallout`'s repaint triggers (both strips
  report height through `ResizeObserver`; `useChromeBlHeight` measures the whole bottom-left stack).

## 3. Tests (Vitest, `frontend/src/test/`)

`MapTopicStrip.test.jsx`:
- held, stored '1': grid absent, header enabled, `aria-expanded="false"`, ▴ drawn, described by
  "Opening it collapses the tide strip"; press → `onOpenHeld` once, stored '1', grid STILL absent
  until rerender with `held={false}`, then present.
- held, stored '0': press stores '1' and calls `onOpenHeld`.
- open, press → stored '0', `onCloseYielded` called.
- forced (landing): `· closed` drawn and `aria-hidden`, reason still described, press inert,
  `onOpenHeld` not called; forced + held behaves as forced.
- focus in the grid, rerender `held` → focus on the header.
- sheet variant ignores `held`/`onOpenHeld`.
- Switch the existing tests that pass `forceReason="the tide strip is showing"` (≈306–318, 390–410) to
  the landing reason; keep their coverage.

`MapTideStrip` tests: pressing "Open ▴" leaves focus on the new toggle; pressing "Collapse ▾" likewise.

`MapViewTopicStrip.test.jsx`:
- Rewrite "draws its header only while the tide strip shows" (≈278–290): tide strip open → grid held,
  header enabled; press → grid open and `wf-tide-strip-collapsed` rendered.
- Then the tide strip's "Open ▴" → tide open, grid held, `mapTopicStripOpen` still '1'.
- Yield is per window: after a held press, move the window with the window control to another tidal
  window → grid held, tide strip open again (with `tideStripCollapsed` false).
- A bar press in the yielded grid that moves to another tidal window → grid stays open, tide collapsed.
- Closing the yielded grid → tide strip open again.
- Reader collapses the tide strip with its own toggle → grid stays held (does not pop open).
- Landing open → header `aria-disabled`, `· closed` shown.
- Keep "opens again by itself when the tide strip goes" (≈292).

`mapTopicStripCss.test.js`: pin the rule for the `· closed` cue class, and assert it is not in the
phone sheet `@media (max-width: 639px)` rules.

## 4. Docs

- `docs/engineering/hot-topics-by-day-plan.md`: §"Map chrome" item 11's Q8 bullet (~177), the Q8 table
  row (~870), and the label-cost note (~935–936): a dated note pointing here.
- `docs/engineering/reference/map-tab.md`: the four-day strip / tide strip paragraph.
- Code comments: `MapView.jsx` ~7272–7275 and the `tideStripCollapsed` JSDoc (~1504), the
  `MapTopicStrip` class JSDoc (~66–82), the `index.css` landing-cap comment if it cites the rule.
- `scripts/measurements/label-obstacle/analyse.mjs` comments (~108–110) and its README if it lists
  surfaces.
- `changelog.d/20261024-topic-strip-opens-over-tide.md`: `### Fixed — …`.

## 5. Measured label cost

Measured 2026-10-24 with `scripts/measurements/label-obstacle/` (`measure.mjs` then `analyse.mjs`, the
real placer; new surface `tideCollapsedOpen` = the open grid over the tide strip's collapsed row, a 474x315
bottom-left stack). Labels removed against the tide strip alone (`bl+tide`), at scroll @0 / @175:

| Frame | tide + held header (`bl+tide+closed`) | **tide collapsed + grid open** (`bl+tide-collapsed+open`) | tide + open (unreachable) |
|---|---|---|---|
| desktop-1280 | −2.9 / −2.6 | **−5.7 / −7.3** | −13.5 / −25.0 |
| laptop-1024 | −1.5 / −4.2 | **−6.3 / −15.0** | −17.0 / −37.1 |
| tablet-820 | −2.6 / −6.5 | **−4.5 / −9.1** | −12.4 / −16.1 |
| phone-390 | not mounted | not mounted | not mounted |

The new combination costs between −4.5% and −15.0%, well under the −37.1% an open tide strip with an open
grid would cost. Recorded, not gated.

## 6. Gates

`cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`.
