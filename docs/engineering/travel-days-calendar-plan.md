# Travel days calendar: month view, ranges as bars, drag to draft

The owner's Claude Design handoff "PhotoCast Travel Days" (2026-11-01, artboards 7, 8 and 14 of the Runs redesign)
upgrades the calendar on **Operations → Runs → Travel days**. It replaces the five-week strip from
`runs-redesign-plan.md` §5.6 (R5). Frontend only; the server already serves everything (`GET ?includePast=true`,
`POST`, `PUT`, `DELETE`). One PR.

## 1. What changes

| Today (R5) | After |
|---|---|
| Five weeks from this week's Monday, fixed | One month, always six weeks (42 cells) from the Monday on or before the 1st; ‹ › page by month; **Today** jumps back |
| Every cell is a `<button>` | One focusable `role="grid"` with an active-descendant cursor (keyboard model below) |
| Away cells are separate tiles | Days of the **same range** join into one bar per week row |
| An empty day from today on opens Add with From set | A press on a free day starts a **draft** (drag for mouse/pen; one click is a one-day draft) |
| Past empty days are inert | Every free day can be drafted (the server accepts any dates; the handoff draws no exception) |
| "1 Nov" on the 1st | Day numbers only; outside-month days are dimmed |

The list, the panels, the hash (`#manage/traveldays/{id}`), the save/remove flows and validation are unchanged except
where §4 says.

## 2. Decisions

- **D1. Selection is the range id, not a walk over away days.** The API has ids and the view already selects by
  `useHashSelection`. "Click an away day selects its range" means `rangeForDay(ranges, iso)` → `select(range.id)`.
  The selected range's days get the `sr` look.
- **D2. Bars join within one range, not across neighbouring ranges.** A cell joins its left neighbour when the
  neighbour is in the same week row and `rangeForDay` gives the same id; likewise to the right. Two ranges that touch
  (Mon–Tue, then Wed) show a gap, so a click's target is never ambiguous. (The handoff's mock joins any away
  neighbours because it has no ids; the README allows ids.)
- **D3. Touch drafts with two taps, not a drag.** A drag on a touch screen fights the page scroll, and the calendar is
  full width on a phone. With `pointerType === 'touch'`: the first tap on a free day sets the draft start ("From
  {date} · pick an end date"), the second tap sets the end (the keyboard's Enter model). Mouse and pen drag. The
  legend hint reads "Drag across days to add a range" with a fine pointer and "Tap two days to add a range" with a
  coarse one (Tailwind `pointer-fine:` / `pointer-coarse:` variants, both spans rendered, CSS chooses).
- **D4. The view month lives in `TravelDaysView`**, because a list row, the hash and a created range all move it. Rule:
  **any selection that does not come from a calendar cell moves the calendar to the range's start month** (row click,
  Back/Forward/pasted hash once the ranges have loaded, a range created from the draft bar or the Add panel). A cell
  press never moves the month (clicking a trailing 2 Nov while October shows keeps October, as the mock does).
- **D5. The draft lives in `TravelDaysView` too**, because the Add panel reads it. With the Add panel open, a
  completed draft fills From and To (§4.3) and no bar shows; without it, the bar shows under the grid.
- **D6. Clock.** Every "today" here stays `new Date()` → `ukDateStr` (R-D7: Operations never follows Rewind).
- **D7. "Saves roughly {n} forecasts a day"** (frame 8) needs a server figure that does not exist: dropped, as the
  handoff's own note allows.

## 3. Calendar (`components/traveldays/TravelDaysCalendar.jsx`, rewritten)

### 3.1 Props

```
ranges, now, viewMonth ({year, month0}), onViewMonthChange, selectedId,
draft ({start, end|null} | null), onDraftChange, addOpen (bool),
onSelectRange(range, iso), onAddDraft()  // the bar's Add range
draftSaving (bool), draftError (string)
```

The calendar owns only transient state: `focusDate` (the keyboard cursor, ISO or null) and the live drag
(`{start, current}` in a ref plus a state copy for the preview).

### 3.2 Layout and look (tokens from `index.css`; `max-w-[520px]`, none below 640px via `max-[639px]:max-w-none`)

- **Header** (`min-h-8`, space-between, gap 8): "{Month}" Plex Sans 500 13px `text-plex-text` + " {year}" in
  `text-plex-text-muted`. Right: **Today**, **‹** (`aria-label="Previous month"`), **›** (`aria-label="Next month"`):
  `bg-plex-surface-light`, no border, radius 5, h-7, `text-plex-text-secondary`, 12px; ‹ › are 28×28 with a 15px
  glyph; Today `px-2.5`. Hover `text-plex-text bg-white/8`. Today is `disabled` (opacity .4, default cursor) when the
  shown month is today's UK month. On a phone the three buttons are at least 44px tall (`max-[639px]:min-h-11`, ‹ ›
  also `min-w-11`).
- **Weekday row**: M T W T F S S, mono 10px, `text-plex-text-muted`, centred, `aria-hidden`.
- **Grid**: 7 columns `minmax(0,1fr)`, gap 3px, 42 cells. Cell: mono 12px, centred, `min-h-9` (`max-[639px]:min-h-11`),
  radius 5, `bg-plex-surface-light text-plex-text-secondary`, `cursor-pointer select-none`. States combine:
  - outside month: `opacity-35`; past: `opacity-45`;
  - today: `shadow-[inset_0_0_0_1.5px_var(--color-plex-text)] text-plex-text`;
  - away: `bg-tide/55 text-plex-text`; joined-left: `-ml-[3px] rounded-l-none`; joined-right: `rounded-r-none`
    (D2); hover on a non-away cell: `bg-white/8`;
  - selected range: `bg-tide/90`;
  - draft (committed draft or live drag preview, inclusive, either direction): `bg-tide/18`,
    `shadow-[inset_0_0_0_1.5px] shadow-tide/90` (or the arbitrary-value equivalent), `text-plex-text`, `opacity-100`
    (overrides past/outside dimming). If a day is both today and draft, the draft ring wins.
  - keyboard cursor: `outline-2 outline-offset-1 outline-plex-text`, shown **only while the grid is
    `:focus-visible`** (`group` on the grid, `group-focus-visible:` on the cell).
- **Draft bar** (under the grid; only when `draft` and not `addOpen`): flex wrap, gap 10, `px-2.5 py-2`, radius 6,
  `bg-plex-surface-light`, 12px `text-plex-text-secondary`, `data-testid="travel-draft-bar"`.
  - Complete: **"{Ddd D Mmm} → {Ddd D Mmm}"** (one date when one day; ink, 500) "· {n} day(s)"; if any draft day is
    already away, "· overlaps an existing range" in `text-plex-text`; spacer; **Clear** (`textButtonClass`); **Add
    range** (`btn-secondary`), `disabled` while overlapping or saving ("Adding…" while saving).
  - Start only: "From **{date}** · pick an end date", spacer, Clear.
  - `draftError` shows under the bar's text as `role="alert"` `text-plex-error`.
  - Dates use `formatDay(iso, now)` (year only outside this UK year).
- **Legend**: the existing key (Away · no forecasts, Today — the Today swatch's ring becomes 1.5px), plus the D3 hint
  `ml-auto text-plex-text-muted`, mono 10.5px.

### 3.3 Accessibility

- The grid: `role="grid"`, `tabIndex={0}`, `aria-label="{Month} {Year}"`, `aria-activedescendant` = the cursor cell's
  id when there is a cursor, `aria-describedby` → the legend hint. Rows: seven-cell wrappers with `role="row"` and
  `className="contents"` so the CSS grid is unchanged. Cells: `role="gridcell"`, `id` from a `useId` prefix + ISO,
  `aria-label` = `cellLabel(...)` (unchanged; the date already says which month), `aria-selected` true on the selected range's cells,
  `aria-current="date"` on today, `data-testid="travel-cell-{iso}"`, `data-away`, `data-selected`, `data-draft`.
- No outline on the grid itself (`outline-none`); the cursor outline is the focus indicator.
- A live region (`aria-live="polite"`, visually hidden) announces the month on ‹ › / PageUp/PageDown / Today
  ("November 2026").

### 3.4 Pointer

- `pointerdown` (primary button only) on a non-away cell:
  - `pointerType === 'touch'`: no drag. If a draft exists with no end, set its end to this day; otherwise start a
    new draft `{start: iso, end: null}` (D3). With the Add panel open, the second tap fills the form (§4.3).
  - mouse/pen: `preventDefault()` (no text selection), start a drag `{start: iso, current: iso}`. A
    `document` `pointermove` resolves the cell under the pointer with `document.elementFromPoint` →
    `closest('[data-date]')` **inside this grid** and updates `current`; `pointerup` on `document` ends it and
    commits `{start: min, end: max}` via `onDraftChange`. `pointercancel` drops it. Listeners are removed on end and
    on unmount.
- `click` on an away cell (any pointer): `onSelectRange(range, iso)` and the cursor moves to that day, so a keyboard
  user continues from there. A press on an away cell never starts a draft.

### 3.5 Keyboard (grid focused; `preventDefault` on every handled key)

- ← → ±1 day, ↑ ↓ ±7 days; PageUp/PageDown ±1 month keeping the day number (clamped to the month's last day, e.g.
  31 Jan → 28 Feb); **T**/**t** today; **Home**/**End** start/end of the cursor's week row (an addition, standard for
  grids).
- When the cursor leaves the shown month, the month follows it (`onViewMonthChange`).
- First key with no cursor: the cursor starts on today if today's month is showing, else the 1st of the shown
  month, and the key is NOT applied (the first arrow press just shows the cursor). Exception: T always goes to today.
- **Enter / Space** on an away day: `onSelectRange`. On a free day: no draft or a complete draft → new draft
  `{start: day, end: null}`; a draft with no end → end = day (normalised order), then §4.3 if the Add panel is open.
- **Escape**: if a draft exists, clear it and `stopPropagation()` (so `usePanelEscape` does not also close the
  panel); otherwise let it through.
- ‹ › and Today buttons clear the cursor (‹ ›) or set it to today (Today) and keep the draft and selection.
- Focus stays on the grid across every re-render (it is one element that never remounts).

## 4. View (`TravelDaysView.jsx`) and the other components

### 4.1 New view state

- `viewMonth` `{year, month0}`, initially today's UK month (from `ukDateStr(new Date())`).
- `draft` `{start, end|null} | null`; `draftSaving`, `draftError`.
- The D4 rule: `openRange` (row) sets `viewMonth` to the range's start month before `select`. For the hash (Back,
  Forward, pasted link, initial deep link): when `selectedRange` changes to a range that is not the last one a cell
  opened, move `viewMonth` to its start month. Implement with a ref set by `openFromCell` (`cellSelectedId`) and the
  existing adjust-while-rendering block (`panelKey`), which already runs on every `selectedId` change; the move waits
  until `selectedRange` resolves (ranges loaded).
- `handleCreated` (Add panel and draft bar) moves `viewMonth` to the created range's start month.

### 4.2 Draft bar Add range

`onAddDraft`: guard with a ref against a double press; `draftSaving=true`; `addTravelDay({startDate, endDate,
note: null})`; on success clear the draft and run the same reload-then-select path as `handleCreated` (with no Add
key to check: select the new range unless another panel was opened meanwhile — reuse `selectedIdRef`/`addKeyRef` to
decide: select only if no Add panel opened and the selection did not change since the press). On failure
`draftError` = `placeServerError(err, 'Failed to add travel range').message`, keep the draft. A new draft or Clear
clears `draftError`.

### 4.3 The Add panel and the draft

- `TravelAddPanel` gains an optional prop `fill` `{start, end, seq}`. An effect keyed on `fill.seq` calls
  `set('startDate', start)` and `set('endDate', end)` (the hook's `set` already clears field errors). The note is
  untouched. The summary box ("{n} days" / "{from} to {to}, both included.") already follows the form.
- In the view: when a draft completes (both ends) while `adding` is set, bump `fill` and clear `draft` (no bar).
  `addOpen` = `adding != null`.
- Opening the Add panel (header Add range) while a complete draft exists seeds From/To from it and clears the draft
  (the reader drafted, then decided to add a note). Opening it with a start-only draft seeds From.
- The old "an empty day from today on opens Add with From set" path (`onAddFrom`, `openAdd(iso, iso)`) is removed;
  `originCell` focus-return now focuses the grid and puts the cursor on that day (pass a `focusRequest`
  `{iso, seq}` prop, or expose an imperative `focusDay` via a ref — pick the prop).

### 4.4 Selection mirrors

- Selecting by cell or row already highlights the `DataGrid` row (`selectedId`) and opens the range panel; nothing
  new. The Past group is collapsed by default: selecting a past range from the calendar expands the Past group so its
  row is visible (`setGroupOpen(o => ({...o, past: true}))` when the selected range is past).
- Closing the panel clears the calendar's `sr` look (it is derived from `selectedId`).
- Removing or editing a range leaves a draft alone; its overlap flag recomputes from the reloaded ranges.

### 4.5 Remove range section (the handoff's "known issue")

The handoff reports "Remove range" rendering twice with the heading over the Effect paragraph in the owner's
screenshot. The current source renders it once. Verify in the browser at 1280+, 640–999 (overlay) and phone; if it
reproduces, fix it so the section reads once: heading, "These days are forecast again from the next run.", Remove.
If it does not reproduce, say so in the PR and change nothing.

### 4.6 Utils (`utils/travelDays.js`)

Replace `CALENDAR_WEEKS` / `calendarCells(now)` with:

- `monthOf(iso)` → `{year, month0}`; `shiftMonth({year, month0}, n)`; `monthLabel({year, month0})` → `{month:
  'November', year: '2026'}`;
- `monthCells({year, month0}, now)` → 42 cells `{iso, day, inMonth, isToday, isPast, col}`;
- `addMonthsClamped(iso, n)` for PageUp/PageDown;
- `joinFlags(ranges, cells)` or a per-cell `{joinLeft, joinRight}` helper (D2);
- `draftSpan(draft)` → `{start, end}` ordered; `draftOverlaps(ranges, span)`;
- `draftText(span, now)` for the bar.

All pure, all noon-UTC ISO arithmetic as the file already does. `rangeForDay`, `cellLabel`, `formatDay` stay.

## 5. Tests (`docs/engineering/frontend-test-standards.md`)

Rewrite the calendar parts of `TravelDaysView.test.jsx`, `TravelDaysReview.test.jsx` and `travelDays.test.js`
(`travel-cell-*` buttons are gone; cells are gridcells clicked with `fireEvent.pointerDown`/`pointerUp` or
`userEvent.pointer`). Pin the clock with `vi.setSystemTime` (Sat 31 Oct 2026, as the handoff). Cover:

- utils: 42 cells starting Monday for months starting on Mon and on Sun; year wrap (Dec → Jan, Jan → Dec);
  `addMonthsClamped` 31 Jan → 28 Feb (and 29 in a leap year); joins within one range break at Sun/Mon and between two
  touching ranges; overlap detection; draft text for one day / many / start only.
- calendar: Today disabled on the current month, enabled after ›; ‹ › change the grid label; selected-range cells
  `aria-selected`; joined classes on a Mon–Wed range and a range crossing Sunday.
- view: clicking an away cell opens its panel and marks the list row; clicking a list row for a December range moves
  the calendar to December; a deep link `#manage/traveldays/{id}` lands on that month once loaded; a cell click on a
  trailing next-month away day does not move the month.
- drafting: mouse drag right-to-left gives an ordered draft and the bar text; a plain click is a one-day draft; Clear
  and Esc clear it (and Esc with a draft does not close an open range panel); an overlapping draft disables Add range
  and shows the sentence; Add range posts `{startDate, endDate, note: null}`, reloads, selects the new range and
  moves to its month; a 400 shows the server sentence and keeps the draft; double press posts once.
- Add panel: open Add, drag 30 Nov → 2 Dec fills From/To and the summary, keeps a typed note, shows no bar.
- touch: two taps make a draft; one tap shows "pick an end date".
- keyboard: arrows move the cursor (`aria-activedescendant`), leaving the month moves the month, PageDown keeps the
  day, T goes to today, Enter on away selects, Enter twice on free days drafts, Esc clears.
- Mutation check: break the D2 same-id rule and the Escape `stopPropagation` and confirm a test fails (back up with
  `cp`, never `git checkout --`).

## 6. Docs

- `runs-redesign-plan.md` §5.6: a one-line pointer that the calendar is superseded by this plan.
- `reference/platform.md`: if it describes the five-week calendar, update it (grep "five-week" / "Travel days").
- `changelog.d/20261101-travel-days-calendar.md` (`### Changed — Travel days calendar: month view, drag to add`).

## 7. Plan review findings (2026-11-01; these OVERRIDE §§3–5 where they differ)

Two adversarial reviews (state/event ordering; fidelity/a11y/Tailwind) were run on §§1–6. Every item below was
checked against the code and is binding.

**State and ordering**

- **R1. Deep links move the month.** The `panelKey` block is seeded with `useState(selectedId)`, so it never fires on a
  first load at `#manage/traveldays/{id}`. Add a separate adjust-while-rendering block keyed on `selectedRange?.id`
  (seeded `null`, so the first resolve counts) that moves `viewMonth` to the range's start month unless the selection
  came from a cell.
- **R2. The "a cell selected it" flag is state, consumed once.** `eslint-plugin-react-hooks` 7's `refs` rule rejects
  reading a ref during render. Keep it as state (`cellSelectId`). The R1 block clears it when it reads it, and
  `openRange` and the create paths clear it too. That way Back/Forward to a range a cell opened earlier still moves
  the month.
- **R3. Touch acts on `click`, never `pointerdown`.** `pointerdown` fires before the browser knows a touch is a scroll,
  so a swipe would set a draft. Record the `pointerType` of the last `pointerdown`; in the cell's `onClick`, a touch
  click runs the two-tap logic (D3). Mouse and pen drag from `pointerdown` and use `setPointerCapture` on the grid
  (with `pointermove`/`pointerup`/`pointercancel`/`lostpointercapture` on the grid, resolving the cell with
  `elementFromPoint`). The click that follows a mouse drag's `pointerup` must not then also run the click logic: guard
  with a "drag just ended" flag. Call `grid.focus({preventScroll: true})` in the `pointerdown` handler; `select-none`
  stops text selection, so do not rely on `preventDefault` for focus.
- **R4. No stale fill.** Do not keep `fill` in view state across Add panels. Store the dates inside `adding`
  (`{from, to, key, fillSeq, fillStart, fillEnd}`) and add `initialTo` to `TravelAddPanel` beside `initialFrom` for
  the opening values. For later fills while the panel stays open, the panel seeds `useRef(fillSeq)` at mount and
  applies only a *changed* seq. Ignore fills while a save is in flight (`savingRef.current`).
- **R5. A draft-bar save can't wipe a newer draft.** Snapshot the draft at the press. On success, clear the draft only
  if it is still that snapshot (functional update comparing start/end). While `draftSaving`, ignore new drafting and
  don't let header Add seed from the draft being posted.
- **R6. Split-mode only.** From 640 to 999px the list is `inert` under a scrim while a panel is open, and on a phone it
  is hidden. So "drag fills the open Add panel" and "Esc with a draft keeps the panel open" can only happen at 1000px
  and up. Write those tests at that width (or with the layout's wide mode). Clear the draft whenever the Add panel
  closes (Cancel, ×, Esc, created), so a draft never reappears as a bar on a phone.
- **R7. Focus.** Selecting a range (click or Enter on an away day, or a row) changes `selectionKey`, and
  `DataSplitLayout` then moves focus to the panel title. That is the Data shell's rule, and it stays. §3.4's line
  "a keyboard user continues from there" is withdrawn. When a panel that a calendar day opened closes, the calendar's
  `focusRequest` effect calls `grid.focus({preventScroll: true})` and puts the cursor on that day. It runs after the
  layout's own close-focus effect, so it wins. Test that `document.activeElement` ends on the grid.
- **R8. Escape.** `usePanelEscape` listens on `document` (bubble phase) and returns on `defaultPrevented`. Calling
  `preventDefault()` on Esc while a draft exists is the contract; drop `stopPropagation`. The mutation check removes
  the `preventDefault` (§5).

**Look, fidelity, a11y**

- **R9. One value per property.** In Tailwind 4, which of two same-property utilities wins depends on the order of the
  generated stylesheet, not on className order. So cell classes come from one pure `cellClasses(state)` in
  `utils/travelDays.js` (or a sibling) that emits exactly one background, one opacity, one ring and one text colour,
  resolved in the mock's cascade order:
  - background: base < away < selected < draft;
  - opacity: base 100 < outside 35 < past 45, and draft forces 100;
  - ring: today < draft.

  Unit-test a past draft day (opacity 100), a today+draft day (tide ring), and an away+draft day (draft fill).
- **R10. Rings.** Use `inset-ring-[1.5px] inset-ring-tide/90` for the draft and `inset-ring-[1.5px]
  inset-ring-plex-text` for today, chosen by R9.
- **R11. Visible focus on Tab-in.** The grid's `onFocus` sets the cursor when it is null (today if today's month is
  showing, else the 1st of the shown month). Every key then applies at once, as in the mock; §3.5's "the first key
  only shows the cursor" is withdrawn.
- **R12. Rows are real grids, not `display: contents`.** WebKit has dropped `display:contents` roles. Use an outer
  `flex flex-col gap-[3px]` with `role="grid"`; each week is a `role="row"` `grid grid-cols-7 gap-[3px]`. Joins with
  `-ml-[3px]` still work within a row.
- **R13. ARIA selection.** Set `aria-multiselectable="true"` on the grid. Every cell carries `aria-selected` as
  `"true"` or `"false"`.
- **R14. Drafts are announced.** `cellLabel` gains ", selected" (a cell in the selected range) and ", in draft" (a
  cell in the draft). The month live region also speaks the draft bar's sentence when the draft changes.
- **R15. Remove range matches the mock.** Screenshot 01 draws it as one row with no uppercase section label. On the
  left, "Remove range" (sans 13px, `text-plex-text`) with "These days are forecast again from the next run."
  (`text-[12px] text-plex-text-secondary`) under it; the coral outline Remove on the right. Wrap the row so a narrow
  panel stacks it. It is a `<section aria-labelledby>` pointing at the "Remove range" text, with the panel's hairline
  above it. This replaces §4.5's "change nothing". The mono uppercase label sitting over the same words was the
  "twice". Keep `data-testid="travel-panel-remove-section"` and the delete test id.
- **R16. Today button hover only when enabled:** `enabled:hover:text-plex-text enabled:hover:bg-white/8`.

**Deliberate deviations, recorded:**

- Phone cells and nav buttons are 44px (touch targets; the R5 rule) where screenshot 06 shows about 36 and 28.
- On a coarse pointer the hint reads "Tap two days to add a range" (D3).
- The handoff's "below 640px" is a container query on the app; `max-[639px]` is the viewport, which is equivalent
  here.
- Bar dates carry the year outside this UK year (`formatDay`).
- The header Today button puts the cursor on today, but focus stays on the button, so the cursor shows once the grid
  is focused.
