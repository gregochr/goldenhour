# Glance topic strip — the "Next four days" topics move from Coming up to At a Glance

Design: the owner's handoff "PhotoCast Glance Hot Topic Strip" (Option A, `Glance Topic Strip.html` +
`screenshots/A-in-day-header.png`). One phase, one PR, frontend only.

## What changes

1. **Coming up** loses the "Next four days" block (`WindowComingUpFourDays`). Its head, legend, filter
   chips and month list are unchanged.
2. **At a Glance** ("The days ahead") gains, under each day's date chip, that day's hot topics as
   full-column-width pills, plus a right-aligned key in the section head and an
   `All in Coming up →` door under the topic row.
3. **The window cards** lose their plain topic-name labels (`facts.topics` without a clock). The tide
   chip (`∿ N on tide`) stays, and a **clocked** topic (eclipse, `chipClock(badge)` non-null) stays,
   because its instant is a per-window fact the day header cannot carry. The card's spoken sentence
   loses exactly the clauses its visible labels lose — the two are built from the same list and must
   keep agreeing.

## Data — no new derivation

The pills are built by the existing, licensed `buildFourDayTiles` (`utils/fourDayTiles.js`) from
`briefing.hotTopics`: same bucketing, chip order, `glance` value/short value/badge, weather flag and
`isContinuation`. Nothing new is derived on the client. Call it with `hasMapDoor: false` and no
`openableKeys` (the pill's only destination is Coming up). Index the result by date and render, for
each `matrix.days` column, the chips for that date; a day with no chips (or a matrix day outside the
four) renders an empty cell that keeps its column. When the whole four days carry no topic, render
neither the topic row nor the door (no empty state — the design says a topic-free day shows nothing).

Note: card topics were scope-filtered (`windowTopics` / `scopeNames`); the header, like Coming up's
block it replaces, is not. That is the design's stated data source and is intended.

## The pill

`<li><button>` per chip, grid `auto 1fr auto`, column gap 8px, padding 6px 10px, radius 8px.
Row 1: 8×8 dot (radius 2px, `--c` = the chip's `topicStyle` colour), name (Plex Sans 13px 600), optional
tag. Row 2 (cols 2–3): meta, Plex Mono 10.5px, ink-2 — `chip.value` on desktop/tablet,
`chip.shortValue` on phone (the existing paired-span pattern); `chip.name` / `chip.chipName` likewise.

> Update 2026-10-28: the phone short forms (`chipName`, `shortValue`) and the hidden tags were dropped here, because the phone cell is full width. The phone now prints the same name, tags and value as desktop. Coming up's four-day tiles still use the short forms.

- **Solid** (`isContinuation === false`): fill = the chip colour at 8%, border 1px at 22%.
- **Dashed** (`isContinuation === true`): transparent, `1px dashed rgba(ink, .28)`, name weight 500 in ink-2.
- Tag: `chip.badge` (PEAK/FULL …) mono 9px 600, .1em tracking, chip colour; `WX` for `chip.weather`,
  same type in ink-2 inside a 1px border-light box, radius 3px, padding 0 4px. Both `aria-hidden`.
- Hover/focus-visible: border to the chip colour at 50%. Forced-colors: `CanvasText` borders, dashed kept.
- Use the app's existing tokens (`--color-plex-*`) wherever the handoff's literal has an equivalent.

### Accessibility (carried over from `WindowComingUpFourDays`, which this replaces)

- Accessible name built from CONTENT, no `aria-label` (WCAG 2.5.3): visible name + value, then an
  `sr-only` tail: badge word, "continues", "weather-driven", the long weekday, and the destination
  ("shown in Coming up" when it has a jump, else "opens Coming up").
- Each day's stack is a `<ul>` only when it has chips (no "list, 0 items"). The day is not repeated as
  a group name — the date chip directly above already names it; the weekday rides the sr tail.
- Literal `{' '}` separator text nodes between sibling spans, per the separator rule (below).

### The key — a deliberate deviation from the handoff's words

The handoff's key reads `| fixed  ⋮ could still move`, but its own data rule ("the data and
fixed/could-move rule are the same as now") makes the dashed pill mean **continuation** (the same
topic carried on from the day before — Thu/Fri's spring tide in the mock), not forecast uncertainty.
Labelling a continuation "could still move" would print a claim the data does not make, and it would
collide with Coming up's own legend, where dashed genuinely means forecast-dependent. The key ships as
`| starts  ⋮ continues` (same marks: 2×12 solid bar, 2px dotted left border 12px tall; mono 10.5px
ink-3; `aria-hidden`, since every pill already says "continues" in its name). Flagged to the owner.

## Placement and layout

- **Desktop/tablet** (`wf-hstrip-rows`): a new, **non-sticky** row `.wf-dtrow` between the sticky
  `.wf-dhrow` and the first `.wf-hrow`, same grid (`repeat(var(--dc,4), minmax(0,1fr))`, gap 8px —
  the cards' gap, not the mock's 12px, so the columns stay the cards' columns). It must NOT go inside
  `.wf-dhrow`: that row is sticky and its height is measured into `--wf-dh-h` for the rails' pin, so
  taller tiles would pin a much taller band over the cards. The topics scroll away under the pinned
  date chips, which is fine.
- **Phone** (single day-major grid, `--c`/`--r` placed cells): the stack sits directly under that
  day's date chip in the same column. The builder decides the cleanest route (a cell in a new matrix
  row, or inside the day header cell if the phone header is not sticky) and states why in a comment;
  desktop/tablet must be provably unchanged by the phone route and vice versa (phone-only rule).
- The door `All in Coming up →` sits right-aligned under the topic row (mono 10.5px, tide colour), a
  `<button>` calling the same Coming up route with no entry.
- Section head: the key sits at the right end of `.wf-hstrip-h`, after the rule.

## The route to Coming up (shell)

- `WindowFirstShell` passes the strip `comingUpEntries={comingUp.events?.entries}` and
  `onOpenComingUp(entryId | null)`.
- The pill's jump uses `buildFourDayTiles`'s existing `jumpFor` rule, with `renderedEntryIds` = every
  entry id the Coming up list renders under the **All** filter (the builder confirms what "All" renders;
  if the list paginates or windows, only those ids). No jump → `onOpenComingUp(null)`.
- `onOpenComingUp` stores a `comingUpFocus` request `{ entryId, seq }` in shell state, then
  `selectTab('coming-up')`. It must survive `selectTab` (which clears dialogs, not this).
- `WindowFirstComingUp` takes `focusRequest` + `onFocusHandled`. When a request arrives and the panel is
  visible: if the entry is not rendered under the active filter, set the filter to `all`; once rendered,
  `scrollToEntry(entryId)` (it scrolls, rings and focuses); then `onFocusHandled()`. With a null entry,
  or an entry that never renders (feed not ready/failed), it falls back to the tab's ordinary arrival
  and still clears the request. Never loops, never throws.

## Deletions and moved docs

- Delete `WindowComingUpFourDays.jsx`, its test, `comingUpFourDaysCss.test.js` and the `.wf-cu-fd-*`
  CSS. **Move** its class doc's "separator rule" section verbatim into the new component's class doc
  and repoint every comment that cites it (`WindowControl.jsx`, `MapBreadcrumb.jsx`, `MapCallout.jsx`,
  `WindowComingUpEntry.jsx`, `comingUpHandoff.js` — grep `WindowComingUpFourDays`).
- `fourDayTiles.js` stays (now feeding the Glance strip); update its class doc's consumer names.
- Remove the now-unused `WindowFirstComingUp` props only if nothing else uses them.
- Update `docs/engineering/reference/plan-tab.md` (and any other reference doc that describes the
  block or the card topic labels) in the same PR.

## Tests

- New component test: pills per day keyed by date; solid vs dashed; tag/WX; accessible name with the
  sr tail; empty day renders no `<ul>`; no topics → no row and no door; jump vs no-jump tap calls
  `onOpenComingUp` with the right argument; door calls it with null.
- Strip test: the topic row sits between the day row and the first card row on desktop, outside
  `.wf-dhrow`; phone places each stack under its own day; cards no longer render plain topic labels
  but still render the tide chip and a clocked topic; the card's accessible name drops the plain topic.
- Coming up: no `coming-up-fourdays`; a `focusRequest` for an entry hidden by the active filter
  switches to All and calls `scrollToEntry`; a null request clears without scrolling.
- Shell: pressing a pill selects the Coming up tab.
- Mutation-check at least the dashed/solid rule and the filter-reset path.

## Plan review corrections (supersede the sections above where they conflict)

R1. **Tap routes keep the old block's four, first match wins** — the handoff's "click opens Coming up"
holds only where Coming up lists the topic. Aurora, inversion, mist, dust and every WX topic have no
almanac counterpart (`ALMANAC_TYPES_BY_TOPIC`), and Coming up's footer says so. So:
(a) `chip.jump` → `onOpenComingUp(entryId)`; (b) `chip.mapDoor` → `onShowOnMap({kind:'event', …})`
exactly as before; (c) `chip.target` → `onGoToPlanWindow(target, type)` (the popup with the topic
emphasised — this keeps `goToPlanWindow`/`planWindowKeys`/emphasis alive, so none of it is dead code);
(d) else `onOpenComingUp(null)`. So `buildFourDayTiles` is called with `hasMapDoor`, `forecastDates`
and `openableKeys` exactly as the shell passes them to Coming up today (`NO_MAP_DOOR`,
`NO_FORECAST_DATES` included). The sr destination copy follows the route: "shown in Coming up",
"opens the Map that morning/evening", `opens ${GLANCE_LABEL}`-style window wording, "opens Coming up"
— use the tab-label constants, never literals.
The door reads **`Dated events in Coming up →`** (another deviation: "All" would be false for the
weather topics). Rewrite `comingUpFeed.js`'s `FOOTER` ("appear only in Next four days") and its test
so it names the new place.

R2. **Columns vs the four dates.** The matrix may start tomorrow (after today's sunset) or hold a
"this morning has gone" column. Render pills only for matrix days whose date is one of the four; a
day's topics show whatever its cards are (the topics are about the day). `isContinuation` is shown
as dashed only when the PREVIOUS calendar date is itself a rendered matrix column; otherwise force
solid, so no column opens with a dashed pill whose start is invisible. Test both.

R3. **Phone.** The phone grid flows (`grid-row: auto`, the day header spans `1/-1`, no sticky header).
Emit a full-width (`1/-1`) topic cell right after `renderDayHeader(day)` in the phone loop. Never put
the stack inside `renderDayHeader` (shared with the sticky, measured desktop `.wf-dhrow`). Add
`MATRIX_ROW.topics` (renumber `MATRIX_ROW` and its tests) so the D4 transient (phone DOM at desktop
width, placed by `--r`) stays correct.

R4. **The Coming up route.** `renderedEntryIds` passed to the strip is empty unless
`comingUp.status === 'ready'`. Two phases in `WindowFirstComingUp`: set the filter to `all` if needed,
then an effect keyed on `[focusRequest, activeFilter, hidden, status]` calls `scrollToEntry`; its
`false` return means "not rendered" → fall back. The null/fallback path scrolls the window to the top
and focuses the Coming up TAB (as `goToPlan` does for Plan, `WindowFirstShell.jsx` ~857), since the
pressed pill's panel is now hidden. Every path calls `onFocusHandled()` exactly once.

R5. **Card topic removal.** Visible chips and the spoken sentence read ONE list: `facts.topics`
filtered to clocked badges. `.wf-hc-tps` stays reserved. Tests that asserted scope-filtered card
labels (e.g. `WindowFirstShell.test.jsx` ~1018 and the scope cases in `WindowFirstHeatStrip.test.jsx`)
now pass vacuously — move those assertions to the popup/sheet, which is still scoped. The header is
unscoped (as the Coming up block was): a Lite reader on a sub-region-shaped body may see a tide topic
for a region outside their picks; accepted, stated in `plan-tab.md`.

R6. **Separator rule.** Move only "The rule" section (and "Why the test suite disagrees" and the
"wrong twice" note) into the new component's class doc with a fresh preamble; repoint every citer:
`grep -rn WindowComingUpFourDays frontend/src` (incl. `WindowComingUpEntry.jsx`, `MapCallout.jsx`,
`MapBreadcrumb.jsx`, `WindowControl.jsx`, `jobRunSlotDatesAbroad.test.jsx`,
`WindowComingUpEntry.test.jsx`, `fourDayTiles.test.js`).

R7. **Accessibility.** Desktop DOM order is all four headers then all stacks, so each stack is a
`role="group"` with `aria-labelledby` pointing at its day header (give `renderDayHeader`'s element an
id, plus an sr-only "Wednesday 28, today" text in it if it lacks one — check what it says now). The
sr tail keeps the long weekday. The old phone rule hiding badge/WX boxes carries over.
