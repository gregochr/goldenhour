# Map warning row (M1–M4) — plan

Source: the owner's design handoff `design_handoff_map_warning` (2026-11-28), reproduced below verbatim, followed by the planner's notes on how it meets the map's code.


## Context (owner, 2026-11-28)
"The weather warning on the map (in a browser on a laptop) looks untidy. The timeline wraps. The button looks isolated. Should it be a button, or text with a link?" Then: "the button looks unnecessarily large on my phone too" and "we need an expand/collapse chevron too".

Reviewed against `MapWarningChips.jsx`, `WarningChip.jsx` (`WarningFooter`), `WindowWarningsBody.jsx` and the guide shots `warn-map-chips.webp` / `warn-callout.webp`.

No feature flags. Be ruthless: the Details button, the map's warning panel and its sheet, their CSS and tests are deleted in the same PR.

## What's wrong today (screenshots 01, 02, 10)
- One `flex-wrap` line has to carry four things: the chip (a **link** to the Met Office), the validity (`Fri 27 Nov, 04:00–14:00`), a **Details button** (our panel), and the licence line. At 370 px the validity and the button each wrap to their own line, so the button sits alone.
- The chip and Details look like a pair but go to different places: one leaves the app in a new tab, the other opens ours. That is why "button or link?" has no good answer while both exist.
- `Read it on the Met Office site` makes the licence line too long, so "site" and the `?` are stranded on a second line. The map card callout has the same problem, because it uses the same `WarningFooter`.
- On a phone the Details button is 32 × 44 px with a border, for something you rarely press. It's the biggest thing in the box.

## The answer: neither. The whole row is the control (design A)
**M1: one disclosure row per warning.** Each warning is a single `<button aria-expanded aria-controls>`: `[level chip] validity ⌄`.
- The **chip is no longer a link.** In this row it's text, the level and type printed in the official colours, as now.
- **Chevron:** a drawn 14 px chevron at the right, ⌄ closed, rotating to ⌃ open (150 ms, none under `prefers-reduced-motion`).
- **Target:** the row is the full box width and at least 36 px tall, which replaces the 32 × 44 bordered button. Hover and open use `plex-surface-light`; the focus ring sits inside the row.
- **Validity** never splits (`white-space: nowrap`). If the row is too narrow, the whole validity drops under the chip (screenshot 06); the chevron stays pinned right and vertically centred.
- **The box** is a `rounded-[10px]` card the **width of the event bar** (`w-full` of the chrome group, not `inline-block`), so its edges line up with the bar above. Rows are divided by a 1 px `plex-border` rule.

**M2: expand in place.** Opening a row expands the warning **inside the box, directly under its row**:
- the full validity with "UK time";
- "What's changed" (if updated);
- the headline and the full what-to-expect, as `WindowWarningsBody` renders them today;
- then "Read it on the Met Office site ↗" and the style guide's "What should I do" link.

Behaviour:
- One open at a time; opening another closes the first.
- The detail scrolls past **300 px** on a laptop and **44vh** on a phone.
- Escape or the chevron collapses it, and focus returns to the row.
- It closes when the event or date changes.
- This is the same on laptop and phone. **Delete `MapWarningPanel`**, its `MapInfoPanel`/`BottomSheet` use, its `dialogHistory` entry and `MAP_WARNING_PANEL_ID`. One less stacked dialog.

**M3: a one-line licence line, the same everywhere.** `WarningFooter` becomes:

`<a>Met Office Weather Warnings © Crown Copyright 2026 ↗</a>` … `?`

- **The attribution itself is the Met Office link** (to the warnings overview, new tab). Its accessible name is "Met Office Weather Warnings © Crown Copyright 2026, Met Office warnings page, opens in a new tab". `Read it on the Met Office site` is deleted from the footer and kept only inside an expanded warning (M2).
- The `?` sits at the far right of the box footer on the map. On narrower surfaces (the map card callout, the lanes, the location sheet), the last word, the ↗ and the `?` stay together in one `nowrap` span, so nothing is ever stranded.
- **Stale feed:** a line of its own above the footer, "Last checked 3 h ago. These may be out of date.", replacing the "— check the Met Office" link (the attribution is now that link). Plan §8's rule that an empty list never reads as an all-clear is unchanged.
- Every surface using `WarningFooter`/`ServedWarningFooter` gets this, so the map card callout (`warn-callout.webp`) is fixed too.

**M4: a shorter validity on the map row.** On the map the date is already in the event bar, and warnings run at most about a week ahead, so a weekday is unambiguous:
- same day: `Fri 04:00–14:00`;
- across days: `Fri 18:00 – Sat 12:00`.

Add `validityCompact(warning)` next to `validityShort` in `utils/warnings.js`, with tests for the BST/GMT change and midnight. The full date and "UK time" stay in the expanded text, and the row's accessible name says "valid … UK time".

**Many warnings:** show up to **3 rows**, then a `+N more warnings` row that reveals the rest in place. Order is the style guide's (level, then impact, likelihood and start), as now.

## Rejected
- **B, text with a link** (screenshot 09): `[chip] Fri 04:00–14:00 … Details` plus `Met Office ↗`. It's tidier than today, but still two links side by side with different destinations, and the tap target is the word "Details".
- **A minimise chevron for the whole box:** the style guide wants the weather type in text and visible on first view wherever a warning shows. Collapsing the box to a count would hide the type. Collapsed, A is already 67 px for one warning, versus 109 today.

## Constraints kept
- The level word and type are always printed, in the official colours with the guide's ink, never colour alone. The `Updated` label stays.
- No geometry, no icons, no logo.
- The attribution stays visible whether rows are open or closed.
- No link inside a button: the row is a button, and the links are outside it (the footer) or in the expanded region below it.
- No personal data. `/api/warnings` is unchanged.
- The two-dialog rule gets easier (one fewer).

## Sizes (from the prototype, 370 px chrome)
| Case | Today | A |
|---|---|---|
| One warning | 109 px | 67 px |
| Two warnings | 169 px | 103 px |
| Two, phone | 169 px | 103 px |

## User guide and landing
- `landing/guide/weather-warnings.html` "Map chip row" (L67): rewrite. Each warning is a row under the topic chips; click it, or the chevron, to open the full warning in place. The licence line links to the Met Office. Remove "Press Details". Also update any mention of the map panel or sheet.
- `landing/guide/map.html#warnings`: same wording. Retake `shots/warn-map-chips.webp` (one row, closed, at 1600 × 617) and **add** `shots/warn-map-open.webp` (one row open). Update both alt texts.
- Retake `shots/warn-callout.webp` with the one-line footer. Check `this-week-warning.webp` and any other shot showing a `WarningFooter`, and retake it if the footer is visible.
- `guide-search.js`: update the warnings entry text if it mentions Details.
- Landing: no change. Its warning feature line doesn't describe the map control.

## Acceptance
- At 1280 × 800 and at 390 px, with one, two, four (+1 more), overnight-and-updated, and stale cases:
  - no wrapped validity fragment;
  - no row where the chevron isn't on the right;
  - the footer is one line on the map.
- No `map-warning-toggle`, `MapWarningPanel` or `MAP_WARNING_PANEL_ID` remains in `src/`. Tests are rewritten for the row, and no coverage floor is lowered.
- axe passes. Each row has `aria-expanded`, and `aria-controls` points at its region. Escape restores focus. Keyboard: Tab through rows, Enter/Space to toggle.
- `WarningFooter` renders "Met Office Weather Warnings © Crown Copyright 2026" verbatim as a link to the overview URL on every surface. A test asserts the exact string.

## Files
- `Map Warning Row.html`: a clickable prototype. Switch between Today, A and B, the case, and laptop or phone; click rows.
- `screenshots/`:
  - `01`, `02`: today, laptop;
  - `03`–`08`: A, laptop (one, two, open, overnight and updated, four, stale);
  - `09`: B;
  - `10`: today, phone;
  - `11`, `12`: A, phone (closed and open).
- `CLAUDE_CODE_PROMPT.md`

---

## Planner's notes (2026-11-28)

1. **State stays in `MapView`.** Keep `openWarningId` / `toggleWarning` / `warningShown` and the menu-exclusivity
   adjustment (`MapView.jsx` ~L1762–1775, ~L4150–4163): they already give "one open at a time", "closes when the
   window's warnings stop matching" and the Escape rung (~L7179). Add: clear `openWarningId` when
   `activeMapEventId` changes (M2 "closes on event or date change"); today it lingers if the same warning still applies.
2. **Delete** `MapWarningPanel`, `MAP_WARNING_PANEL_ID`, its mount (~L7800), the `BottomSheet`/`MapInfoPanel` imports
   in `MapWarningChips.jsx`, and `closeWarning`'s panel-specific focus logic: closing now returns focus to the row
   button (`warningOpenerRef`), via the Escape rung and the chevron. ⚠️ `.wf-warn-panel` CSS is shared with
   `.wf-bestbet-panel` (`index.css` ~L6460, and `restoreFocusTo`'s `closest('.wf-warn-panel, .wf-bestbet-panel')`):
   move the shared rules to `.wf-bestbet-panel` alone; don't break Best bet.
3. **In-place expansion grows `.wf-map-chrome-tl`.** That is acceptable: `useChromeTlHeight` re-places labels and
   panels hung under the group. But keep the row's `w-0 min-w-full` trick so the box contributes no width of its
   own to the group (the measured 504px label-obstacle bound in `index.css`). "The width of the event bar" means
   `w-full` inside that row, replacing `inline-block`.
4. The expanded region is `role="region"` with an `id` per warning (`aria-controls` on its row), `max-h-[300px]`
   laptop / `max-sm:max-h-[44vh]`, `overflow-y-auto`, and renders `WarningDetail`/`WindowWarningsBody` without its
   chip (the row already shows it) and without its footer.
5. `WarningChip` keeps its `href` mode for the other surfaces; only the map stops passing `href`.
