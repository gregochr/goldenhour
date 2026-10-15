# Handoff: Rate a forecast ("How was it?") — v1, in-app

## Overview
Pro and Admin users record how a sunrise or sunset actually turned out, so the forecast can be checked against reality. The rating appears **inside the existing Map tab location callout** whenever the selected window is in the past. One tap records it. There are no notifications, email, new screens, history views or Plan tab changes in v1.

To reach past windows, the Map's existing **window control** (`‹ pill ›` plus window menu, from `design_handoff_map_landing/`) now steps backwards:
- The app loads **today plus two days of history** up front. On a Sunday evening the Fri, Sat and Sun sunsets can all be rated without another fetch.
- Pressing `‹` past the oldest loaded window **fetches one more day** from the backend, back to **T-4**. After that, `‹` is disabled.

## About the design files
The HTML files here are **design references**: prototypes showing the intended look and behaviour, not production code. Recreate them in the existing app (React 19, Tailwind, `react-leaflet`, the existing Map tab components and `hooks/useIsMobile.js`) using its established patterns. Do not port the prototype's imperative DOM code.

| File | What it is |
|---|---|
| `Outcome Prototype.html` | **The design.** A clickable prototype. Its demo bar switches View (Phone/Desktop), Role (Pro/Admin/Lite), Now (Thu 08:15 / Sun 19:30) and forces the next save to fail. Map pins and the coast are placeholders; use the real Map tab. |
| `Outcome Flow v2 (static states, superseded layout).html` | Earlier static boards: a bottom-sheet layout and a date strip. **The layout is superseded** by the prototype, which uses the current window control and callout. Kept only for the copy table and decisions list. |
| `screenshots/` | 15 captures of the prototype; see the end of this file. |

## Fidelity
**High** for the rating block, its states and copy. The map, pins, window control and callout chrome are reproduced from `design_handoff_map_landing/` for context; build those from that bundle and the existing code, not from this one.

---

## 1 · Where the rating block appears
- In the Map tab location callout (`#cal` in the prototype; the anchored callout from the Map landing bundle), **between the forecast "why" text and the action buttons** (`Four days here` / `Zoom to it`).
- **Only when the selected window has passed** (event time < now). Future windows show the callout exactly as today, including Drive / Leave by. Past windows hide Drive / Leave by.
- Phone and desktop use the same block. On phone the callout keeps the current anchored behaviour (296px wide); only tap-target sizes differ.
- **Not in the Plan tab**, which has no concept of past windows.

## 2 · Window control changes (Map tab)
- **Past windows** are reachable with `‹`, the left arrow key, or the window menu.
- **Pill for a past window:** `[SUNRISE|SUNSET chip] {day label} {HH:MM} [PAST]`. The `PAST` tag replaces the verdict word (Worth it / Maybe / Poor). Tag style: mono 9.5px, uppercase, letter-spacing .08em, `rgba(242,231,211,.42)` text, 1px `#4A3A2E` border, radius 4px, padding 1px 5px.
- **Day labels:** `Today`, `Yesterday`, then `{Dow} {d}` (e.g. `Fri 16`). Future days keep the existing labels (`Tomorrow`, weekday name).
- **Window menu** gains an `EARLIER` group above the existing day groups:
  - Header: `EARLIER` on the left; `rate how it went` on the right (no caps, normal weight).
  - First row, while more history can be fetched: **`Load {Dow} {d}`** in tide `#6FA8B0`, with sub-line `or press ‹ past the oldest window · fetches {Dow} {d}`. Clicking it fetches one older day.
  - Then each loaded past day: sub-header `{day label}` (mono 9.5px / 600, ink-2), followed by one row per event (sunrise first). Each row shows the event chip, the time (bold 12.5px), and on the right `Not rated` (ink-3) or `{n} rated` in star gold `#E5A00D`, where n = the number of locations the current user has rated for that window.
  - The existing future groups follow unchanged.
- **Default selection is unchanged.** The map opens on the next upcoming window.
- **Stepping past the oldest loaded window** (`‹`, ←, or the Load row) triggers a fetch:
  - While loading, the pill reads `Loading {Dow} {d}…` (≈600ms in the prototype).
  - Then selection lands on that day's **sunset**, so the next `‹` goes to its sunrise.
  - Once T-4 is loaded, `‹` is disabled at the oldest window (opacity .3).
- **Rated badge on pins:** a pin the current user has rated for the selected window gets a small badge top-right: `{n}★`, or `–` for not seen. Style: mono 9px/700, gold text on `#0e0b09`, 1px `#4A3A2E` border, radius 6px, padding 0 3px, line-height 14px, offset top −7px / right −9px.

## 3 · Rating block — states and exact copy
Container: 1px top border `#3A2C23`, padding-top 9px, column, gap 7px.

| State | Shows |
|---|---|
| **Unrated** | Row: **`How was it?`** (13px/600) left; `{Sunrise|Sunset} was {today|yesterday} {HH:MM}` or `… was {Dow} {d}, {HH:MM}` right (mono 9.5px ink-3). Then 5 star buttons in a 5-column grid (gap 5px). Then a full-width **`Didn't see it`** button. |
| **Saving** | Buttons at opacity .55, non-interactive (optimistic, ~350ms in the prototype). |
| **Rated** | `You rated ★★★☆☆ 3★` (stars 15px; filled gold `#E5A00D`, empty `#4A3A2E`; numeral mono 12px/600 ink) left; **`Change`** button right. The rating buttons are hidden. |
| **Not seen** | `You marked this not seen` + `Change`. |
| **Changing** | Like Unrated, but the right-hand meta reads `Tap to replace your {n}★` or `Tap to replace "not seen"`, and the current choice is highlighted. One tap replaces it (upsert, never duplicate). |
| **Save failed** | Block stays open (or returns to Changing if a value existed) with `● Not saved. Tap a star again.` The 8px dot is marginal amber `#E0A542`, the only amber in the flow; text 12px ink-2. |
| **Lite** | `How was it?` + `Pro` pill (existing `ProPill`). Buttons at opacity .45. Line: `Rate how the sky turned out.` Tapping shows `Rating is part of Pro.`; wire it to the existing upgrade path. |
| **Admin** | Own rating block as above, plus a collapsible `All ratings · {n}` box (collapsed by default, ▾/▴). Rows: `{username}` · stars or `Not seen` · timestamp (`HH:MM` today, else `{Dow} HH:MM`). Footer: `Rated against the run at {Dow} {d} {Mon}, {HH:MM} · {Xh} before {sunrise|sunset}`. Empty state: `No ratings yet.` |
| **Future window** | No block. |

Star button: min-height 44px, 1px `#4A3A2E` border, radius 9px, bg `#1E1712`. Content: `★` (16px, ink-3) over the numeral (mono 10.5px/600, ink-2). Hover: border ink-3. Selected: border `#E5A00D`, bg `rgba(229,160,13,.12)`, star gold. aria-label: `Rate the {sunrise|sunset} {n} out of 5`.
`Didn't see it`: min-height 40px, 1px `#3A2C23` border, radius 9px, 12.5px/500 ink-2; selected looks like a selected star.
`Change`: min-height 40px, padding 0 12px, 1px `#4A3A2E` border, radius 8px, 12.5px/500 ink-2.

**Neutrality rules (do not break):**
- Never say the forecast was right or wrong.
- No comparison or verdict between forecast and observed.
- No effusive thanks.
- The forecast star stays where it already is in the callout (the verdict strip), visible before and after rating.

## 4 · Data and backend
- **Outcome record (per user).** Primary key: `(user_id, location_id, event_date, event_type)`. Fields: `rating` (1–5, **nullable**; null means "not seen"), `forecast_run_id` (the last run before the event), `created_at`, `updated_at`. Writing is an **upsert**.
- **Which forecast is rated.** Always the **last forecast run before the event time**, even if it was stale because of batch failures. Staleness is useful data. Store `forecast_run_id` (or run timestamp) so the age of the forecast is recoverable.
- **Initial load.** `/forecast` returns today, the upcoming days and **2 past days**. Showing a past window is a database read only.
- **Older days.** A new on-demand read, e.g. `GET /forecast?date=YYYY-MM-DD`, one day per call, limited to T-3 and T-4. It reads stored rows only.
- **Never trigger `runForecast`** or any batch or model call from viewing or rating a past window.
- **Ratings load.** Fetch the current user's outcomes for the loaded date range alongside the forecasts. Fetch again with each older-day request.
- **Admin.** Read all users' outcomes for the selected (location, date, event) and show **usernames**.
- **Roles.** Pro and Admin can write. Lite can read the UI but writes are rejected server-side.
- The existing `OutcomeModal.jsx` (went out / sliders / notes) is not part of this flow and is not currently wired into any screen. Leave it as is unless told otherwise.

## 5 · State (client)
- `selectedWindowIndex`, `loadedPastDays` (2 → max 4), `fetchingOlder` (day label | false)
- `myOutcomes: Map<"loc|date|event", {rating|null, at}>`
- Per open callout: `changing`, `saveError`, `saving`, `adminListOpen`
- **Save:** optimistic. On failure, roll back to the previous value and show the error line.
- **Keyboard:** ← and → step windows; ← at the oldest loaded window fetches an older day.

## 6 · Design tokens (from the existing app)
`--bg #181210 · --surface #221A15 · --surface-light #2A2019 · --panel #1E1712 · --border #3A2C23 · --border-light #4A3A2E · --ink #F2E7D3 · --ink-2 rgba(242,231,211,.66) · --ink-3 rgba(242,231,211,.42) · star #E5A00D · star-off #4A3A2E · star-selected bg rgba(229,160,13,.12) · go #8AAE72 · marginal/amber #E0A542 · poor #C8452F · tide #6FA8B0 · home #C9A24B · dawn #8FA8C4 · coral #E8593F`

Fonts: IBM Plex Sans (UI), IBM Plex Mono (meta, numerals), Newsreader (wordmark and the callout's "why" text). Radii: 9px buttons, 11px callout and menu, 4–6px tags. Minimum tap target 44px on phone (40px for secondary buttons).

## 7 · Screenshots
1. `01-phone-default-next-window`: cold open on the next window (unchanged behaviour)
2. `02-phone-window-menu-earlier`: menu with the Earlier group and the Load row
3. `03-phone-unrated`: past window, callout with rating block
4. `04-phone-rated`
5. `05-phone-changing`
6. `06-phone-not-seen`
7. `07-phone-save-failed`
8. `08-phone-lite-locked`
9. `09-phone-admin-all-ratings`
10. `10-phone-fetching-older-day`: pill loading state
11. `11-phone-oldest-T-4-stepper-disabled`
12. `12-phone-future-window-no-rating`
13. `13-desktop-unrated`
14. `14-desktop-admin`
15. `15-desktop-window-menu`

## Open items
- Server: confirm how many past days `/forecast` returns today, and set it to 2.
- Check that pin labels do not collide with the rated badge at dense zoom levels on the real map.
