# The days ahead as lanes — plan

Design handoff: "At a Glance — The days ahead as lanes" (2026-11-07, `design_handoff_days_ahead_lanes`). One PR,
frontend and guide copy only. No backend change (see §4).

## 1. What changes

1. **Lanes replace the stacked per-day pills** in At a Glance's "The days ahead" (desktop and tablet). One
   compact row per *run* of a topic, placed under its day and spanning the columns it covers. Rows are packed
   by CSS (`grid-auto-flow: row dense`), so single-day events on different days share a row.
2. **Removals**, each with all its supporting code (component, CSS, tests, doc pointers):
   - the `WX` box on the pills (the only place the app draws it);
   - `GlanceTopicDoor` ("Dated events in Coming up →");
   - `GlanceTopicKey` (the "starts / continues" key in the section head);
   - the footer note "hatched bar — not yet rated" and the `hatchedSpread` memo that exists only for it
     (the histogram's hatched bar itself stays);
   - `WindowGlanceDayTopics` and its `wf-dt-*` pill styles (deleted, not flagged).
3. **Best-location name** on the sunrise/sunset cards (`.wf-hc-best`): mono 10.5px, weight 400, secondary ink
   (`--color-plex-text-secondary`, the handoff's `--ink-2`). The star badge is unchanged. `.none` and `.beyond`
   keep their own rules; `.beyond`'s comment about "PRIMARY ink" is updated, since the base is now secondary
   too and `.beyond` follows it.
4. **Slim empty slots.** In the desktop/tablet card rows only — every rule scoped to
   `.wf-hstrip-rows .wf-hcards > …`, so the phone keeps today's look:
   - `.wf-hgap` ("this morning has gone", "past the end of the forecast", or silent): `min-height: 0;
     align-self: start; padding: 12px 10px`, mono 11px, dashed border kept. ⚠️ Ink stays
     `--color-plex-text-secondary`: the app's `--ink-3` (`--color-plex-text-muted`) measures 3.2–3.5:1 and
     fails AA (index.css comments near 1725 and 2795).
   - The away card ("Not forecast", `card.away`): `align-self: start; min-height: 0; padding: 9px 10px`; the
     card stays a column; its `.wf-hc-top` (the SUNRISE/SUNSET chip) is `display: none` under that desktop
     scope only (the phone has no rails and needs the chip, index.css ~2090); its time/verdict row
     (`.wf-hc-pls`, already a `auto 1fr` grid) stays as it is, so the verdict stays right-aligned. The verdict
     keeps its **11px** size (12px "Not forecast" overflows the measured 79–85px value column at 640 and
     docked 1024). Spacing to `gap: 8px` between time and verdict only if it does not change the rated cards.
   - ⚠️ **Do not mount the heat-field canvas for an away card, in either layout**, rather than hiding it:
     a hidden canvas measures 0. And **`measureKey` (WindowFirstHeatStrip.jsx ~1317) must skip `card.away`**,
     with the measuring gate's `enabled` becoming `cards.some((c) => !c.away)`: today it takes the first
     placed card, so an away card in column 1 would wait for a ref that never arrives, spend the 30-frame
     budget and leave **every** heat map blank. Test: an away sunrise in column 1, the other canvases paint.
     State in the PR what the phone away card now looks like (chip and time/verdict, no map).
   - A row only gets shorter when every slot is empty; otherwise the rated cards set its height (that falls
     out of `align-self: start` on the empty ones).
5. **Legend footer:** "Stood down — ruled out by the weather" → "stood down — ruled out by the weather".
6. **Guide copy** (`landing/guide/at-a-glance.html` `#days`, `landing/guide/reading-the-forecast.html` terms
   list): remove the WX sentence and the WX definition; describe lanes (one row per event, spanning its days;
   a dotted left edge means it began before the first day shown; a faded right edge means it runs on past the
   last); replace the "All in Coming up →" sentence with "tap an event to open it in Coming up, or use the
   Coming up tab for the full list". `guideSections.test.js` must stay green.

## 2. The lane model (`utils/fourDayTiles.js`)

`glanceTopicsByColumn` is replaced by **`buildGlanceLanes(days, columnDates, hotTopics)`** in the same
module, so the licence entry in `architecture-decisions.md` ("Backend-heavy") still covers it; update that
entry's wording. It re-buckets served chips, derives no fact.

- Input: `days` from `buildFourDayTiles`, **`matrix.days`** (every column, each with its date and whether it
  is live, i.e. not all-away), and the served `hotTopics`. ⚠️ `startCol`/`endCol` are the **matrix index + 1**,
  never a position in the live-dates list: the lanes grid has `--dc = matrix.columns` columns, away ones
  included (review finding 1). Liveness is used only to break runs.
- **Which days a topic covers, for lanes.** A NIGHT topic covers its own dusk date(s) only (`date` …
  `endDate`), not the next morning, so a one-night aurora is a one-column lane under the night it falls and
  an NLC dated on the last day does not "continue after". Every other topic covers its `laneDates` dates.
  (This differs from the pills, which showed a night topic on both days; intended.)
- A **run** is a maximal sequence of live matrix columns, consecutive calendar dates, where each day holds
  **the same event**: the same served topic (same `type` and same `date`, the topic's identity), or for
  tides the same tide run (consecutive run days — read the served tide-run fields on the topic to
  identify the run; find them in the backend's `TideRunDay` / glance decorator). Two separate MIST topics on
  Sat and Sun are two lanes; tonight's aurora and last night's are two lanes. A gap column (absent date, or
  an away column) breaks a run.
- Each lane: `{ key, type, name, colour, startCol, endCol (1-based, inclusive), detail, detailDate, peak,
  startsBefore, endsAfter, dates, jump, mapDoor, target }`.
  - `detail` is the **PEAK day's** served `glance.value` when the run has one, else the first day's;
    `detailDate` is the day it came from, and the spoken tail says which day it describes ("4.6 m on
    Sunday"). Printed whole, never composed or truncated.
  - Routes: for each of `jump`, `mapDoor`, `target`, the first chip in the run that has one (review finding
    5); `tapTopic`'s precedence (jump > mapDoor > target > Coming up) is unchanged and the spoken destination
    is built from the same lane fields.
  - `peak`: the date of the run's chip whose `badge === 'PEAK'`, or null. **Only PEAK is drawn** (the
    handoff: "Only PEAK remains as a possible tag"); `FULL` is no longer drawn on the lane, and PEAK is drawn
    once.
  - `startsBefore`: a served topic of the same event has a covered date on the day before the run's first
    date. `endsAfter`: likewise the day after the run's last date, and that day is not itself a matrix
    column holding the same event. ⚠️ **Read only served dates.** The briefing serves topics clipped to
    today … the last plan date (`BriefingService` → `getHotTopics(today, lastPlanDate)`, and the tide
    strategies clip to it), so for tides these edges fire only at a gap or when the matrix starts tomorrow;
    a run that really began yesterday or runs past Tuesday is not visible to the client. That is accepted
    for this PR and stated in the PR body; serving a tide run's real extent was the follow-up, which
    **shipped in `tide-run-extent-plan.md`** (`tideRun.runStart`/`runEnd`). Never fall back to the almanac for this (D14).
- **Rows are assigned here, not by CSS** (layout review 7: `row dense` would draw a later DOM lane to the
  left of an earlier one, so Tab would run right-then-left, WCAG 2.4.3). Take the lanes longest span first,
  then earliest `startCol`, then the existing chip order (non-weather before weather, served order); give
  each the first row whose columns `startCol..endCol` are free (first-fit); then **sort the output by
  (row, startCol)**, which is the DOM order. Each lane carries `row` (1-based).
- `buildFourDayTiles` keeps `weather` (it orders chips and gates `mapDoor`); the chip's `isContinuation` is
  removed if nothing reads it after this change (the lane's `startsBefore` replaces it).

## 3. The component (`components/WindowGlanceLanes.jsx`)

- Desktop/tablet (`.wf-hstrip-rows`): one `.wf-lanes` grid between `.wf-dhrow` and the first `.wf-hrow`,
  replacing `.wf-dtrow`; **the same `--dc` columns and the same column gap as `.wf-dhrow` and `.wf-hcards`**
  so lanes line up with the date chips (the handoff's 12px is its mock's gap; this grid's is the app's own),
  row gap 5px. Spacing: `.wf-hstrip-rows` is a flex column with `gap: 7px`, so the lanes take
  `margin-top: 3px` to land 10px under the date chips. Each `<li>` sets `grid-column: startCol / endCol+1`
  and `grid-row: row` **inline** (never through the `grid-column: var(--c)` selector list at index.css
  ~1584: on a lane, `--c` is the colour). Rendered only when there is at least one lane. Lanes take the
  cards' `scroll-margin-top` (index.css ~1702) so a focused lane is never under the sticky date row.
- Markup: a `<ul>` of `<li>` lanes each holding one `<button>` (the whole lane is the target). Accessible
  name built from content as the pills' was (no `aria-label`, WCAG 2.5.3): visible name, a `{' '}` separator,
  visible detail, then an `sr-only` tail opening with ", " and joined with ", ": "peak <Weekday>" when
  `peak`; the detail's day when the lane spans more than one ("on Sunday"); the span ("Sunday" or "Sunday to
  Tuesday"); "began earlier" / "continues after <last weekday>" for the edges; the destination wording from
  today's `destination()` (moved over, reading the lane's own route fields). Example: "Spring tide 4.6 m,
  peak Monday, on Monday, Sunday to Tuesday, began earlier, shown in Coming up". Swatch and PEAK tag
  `aria-hidden`. Pin the exact strings in tests.
- ⚠️ **The fade must not mask the text or the focus ring.** Put the lane's background, border and the
  ends-after `mask-image` on a `::before` layer (position absolute, inset 0, z-index -1 inside an isolated
  button), so the name, detail and the 2px gold `:focus-visible` outline are never masked. Forced colours:
  border `CanvasText`, dotted left edge `CanvasText`, the ends-after right border stated explicitly.
- Lane CSS, per the handoff: flex, `align-items: center; gap: 8px; flex-wrap: wrap; row-gap: 0;
  min-height: 28px; padding: 4px 10px; border-radius: 6px; line-height: 1.35`; background `#1F1A17`, border
  1px `--color-plex-border-light`; hover/focus-visible border at `--ink-3`; focus ring as the pills had.
  Swatch 8×8 r2 `var(--c)`. Name sans 12.5px 600 primary ink. Detail mono 10.5px 400 secondary, wraps,
  **never truncates** (no `nowrap`/ellipsis). Multi-day (`endCol > startCol`): `linear-gradient(90deg,
  color-mix(in srgb, var(--c) 14%, transparent), color-mix(in srgb, var(--c) 5%, transparent))` — exactly the
  handoff's values for a tide, and the topic's own colour for others. `data-starts-before`: left border
  `2px dotted` secondary ink, left corners square. `data-ends-after`: right border transparent, right corners
  square, `mask-image: linear-gradient(90deg, #000 calc(100% - 36px), rgba(0,0,0,.25))`. AURORA: background
  `#241F33`, border `#4A3F6E`. Forced-colours rules carried over from the pills.
- **Phone** (`isMobile`, phone markup unchanged otherwise): the phone grid has no day columns, so each day's
  full-width cell after its header lists that day's lanes that cover it, as the same lane rows (single-column,
  no spanning), with the dotted left edge when the lane covers the previous day too (or `startsBefore` on its
  first day), and the right fade only on the lane's last day when `endsAfter`. Each copy's spoken tail is
  day-relative ("continues from Sunday", "continues to Tuesday"). A day with none renders nothing. The cell
  needs the replacement of today's `.wf-hstrip > .wf-dtcell { grid-column: 1 / -1 }` (index.css ~1925) so it
  stays full width. Phone rules stay inside `@media (max-width: 639px)`; desktop unchanged by them.
- The `?` `GuideLink` in the section head is unchanged.

## 4. Backend: nothing removed, and why

`HotTopic.glance.weather` (`GlanceLine.weather`) is **shared**: besides the WX box it orders chips
(non-weather first) and gates the chip's Map door (`buildChip`'s `mapDoor`), and `LitePickShaper` carries it
through. So it stays in the payload and the PR says so. No other backend field existed only for the WX box,
the key, the door or the hatched note.

## 5. Tests and gates

- Delete `WindowGlanceDayTopics.test.jsx` and `glanceTopicStripCss.test.js`; add `WindowGlanceLanes.test.jsx`
  and `glanceLanesCss.test.js` (the same CSS-pinning style). Extend `fourDayTiles.test.js` with
  `buildGlanceLanes`: single day; a three-day run; two types sharing a day; a gap column breaking a run; an
  away column; `startsBefore` / `endsAfter` from served dates both true and false; PEAK on a middle day;
  ordering. Update `WindowFirstHeatStrip.test.jsx`: lanes render in the dtrow's place, no door, no key, no
  hatched note, lowercase stood-down, away card has no canvas and no sun chip, a lane press routes through
  `tapTopic` (jump, map door, window, fallback).
- `buildGlanceLanes` tests also cover: an away column shifting nothing (lane under Mon–Tue stays under
  Mon–Tue); a one-night aurora is one column; two separate MIST topics on adjacent days are two lanes; a tide
  run is one lane with the PEAK day's detail; routes taken from a later chip when the first has none; first-fit
  rows and the (row, startCol) order.
- `WindowFirstShellSticky.test.jsx` (~519) pins a selector list containing `.wf-dtrow`: update it to the new
  class, do not delete it.
- Repoint every comment that cites `WindowGlanceDayTopics`'s class doc (the separator rule) to the new
  component, and move that rule's text over **verbatim**.
- Gates: `npm run lint && npm run test:coverage && npm audit --audit-level=high && npm run build`, plus the
  changed-lines check. Changelog: `changelog.d/20261107-days-ahead-lanes.md`. Update
  `docs/engineering/reference/plan-tab.md` and `architecture-decisions.md` where they describe the pills,
  door, key or WX.
- Verify in the browser at desktop, tablet and phone widths (local backend 8083), saying which claims were
  seen. jsdom cannot check these, so the browser pass must: lanes aligned with the date chips at 640, 1024
  and 1440; no horizontal overflow; the away card's "Not forecast" not spilling at 640 and 1024; Tab order
  following the visual order; a focused ends-after lane's ring fully visible; all heat maps painted with an
  away card present.
