# Collapse controls, hot-topic day dots, tide aligned count — plan

Source: design handoff `design_handoff_collapse_and_topic_pips/` (README + `Collapse and Topic Pips v2.html`
+ `screens/*.png`), received 2026-11-03. One PR.

## Owner decisions (2026-11-03)

- **Phone is out of scope.** The design's §3 split bar is not built: the phone keeps its peek sheet
  (Tide section, the hot-topics `sheet` variant, Ask since #1648). §1 and §2 are desktop and tablet only
  (`useIsMobile` false). §4's wording and §5 still reach the phone where those surfaces render there.
- **Session-only collapse, both panels start collapsed.** The Hot topics panel's `localStorage`
  preference (`TOPIC_STRIP_OPEN_KEY`) and the tall-frame default (`OPEN_BY_DEFAULT_MIN_FRAME_PX`) are
  removed for the panel variant. Its open state becomes `MapView` state, the `tideStripCollapsed`
  precedent (the pane is never unmounted, so no storage is needed). The phone sheet variant's own key
  (`TOPIC_STRIP_OPEN_SHEET_KEY`) is untouched.

## Changes

### 1 · Shared collapsed row (desktop/tablet)
- `MapView.jsx`: `tideStripCollapsed` defaults to `true`. New `topicStripCollapsed` state, default `true`.
- `MapTopicStrip.jsx` panel variant: collapsed header uses the tide strip's collapsed-row layout and its
  `wf-tide-strip-toggle` button style: label `HOT TOPICS` (open: `HOT TOPICS · NEXT FOUR DAYS`), button
  `Open ▴` collapsed / `▾` open with `aria-label="Collapse"`; whole row clickable. With no topics in the
  four days, the dots are replaced by `none ahead` (muted) and the button stays.
- The existing tide-yield / `held` interplay (topic-strip-tide-yield-plan) keeps working with the new
  state owner.

### 2 · Day dots on the collapsed Hot topics row
- One group per forecast day (today..+3) from the served `fourDayGrid`; up to 3 dots per day in the
  panel's row order, colour from the existing topic palette; hollow ring for an empty day; no count.
- Current day (the selected window's date) marked: fill, bold label, 2px underline. On that day only,
  a dot glows when its topic's bar covers the selected window's column, and is a hollow ring when it
  covers only the other light that day.
- topic → windows is derived from each grid bar's `start`/`end` columns (eight columns: day × sunrise/
  sunset). No new client derivation of a verdict; this is presentation of the served grid.
- Days with topics are `<button>`s (spans otherwise). Hover/focus-visible border + tooltip above
  (copy per design §2.3); `aria-label` names the destination ("Show Sun sunrise").
- Click selects that day's first column that has a topic and an EV row (sunrise first) through the same
  path the window control's › uses. Clicking the current day does nothing. Panel stays collapsed.
- Window control flashes (border + ring) for 700ms with a .5s transition on a dot jump.
- Expanded grid: current-day header gets the fill + underline; in the current day, the active
  sub-column's bar glows and the other sub-column's bar is dashed. Topic-name column 104px with
  ellipsis.

### 4 · Tide aligned count
- Collapsed tide row: drop the `at HH:MM` time; replace `· N dimmed` with a `flex:none` count span:
  `K of N aligned` / `none of N aligned` (muted) / `all N aligned`, N = `namedCoastal.length`,
  K = N − `dimmed.length`. No count when `fitKnown === false` or N = 0. Same wording in the phone peek
  sheet's Tide section only if it currently prints the dimmed count on its one-line summary.

### 5 · Coming up: closable entries
- `WindowComingUpEntry.jsx`: header button after the value (`▾` aria-label "Close" / `Open ▴`). Closed
  entry shows only the header line; padding and title size drop; body paragraph and dashed info block
  hidden; date box keeps the date only.
- Default open = tagged "In next 4 days" OR starts within 30 days (UK civil date via existing helpers /
  `appNow()`); later entries closed. Per-entry overrides kept for the session (`sessionStorage`, try/
  catch, keyed by entry id).
- `WindowFirstComingUp.jsx` title row: `Close all` when every entry is open, else `Open all`.
- Deep links (Next-four-days chip and At a Glance's pill focus) open a closed target entry before the
  existing container scroll.

## Out of scope
Phone split bar (§3). The tide footer's "next fit" jump label wording.

## Docs
Update `docs/engineering/reference/map-tab.md` (panel defaults, day dots, aligned count) and the
reference file that describes Coming up (grep), in the same PR.
