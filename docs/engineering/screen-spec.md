# PhotoCast screen specification

**Status:** the single source of truth for PhotoCast's one screen, as of 2026-11-24, against v2.42.0 (`684e25b`). It **supersedes** the screen parts of these handoffs: `design_handoff_one_screen`, `design_handoff_places_and_watching`, `design_handoff_one_screen_polish`, `design_handoff_glance_to_map`, `design_handoff_find_and_favourites` (UI parts) and `design_handoff_weather_warnings` (placement parts). Where any of them disagrees with this document, this document wins. New ideas are judged against it, and it is amended rather than worked around.

**Owner rules:** no feature flags (the owner is the only user and adopts each change as it ships). **Be ruthless:** everything a change replaces is deleted in the same PR, including code, CSS, tests, E2E, docs and guide text.

## 1. The job
PhotoCast is **a workflow for planning a shoot**, not a set of data screens:

> **When** (Further ahead) → **which morning** (This week) → **where** (the map view) → **go** (callout, Leave by)

One screen, read top to bottom in that order. The map is a view opened *from* a sunrise or sunset, never a destination of its own.

## 2. Principles (cross-cutting; already in force, restated here)
- **Honesty:** a favourite, a watch or a warning never changes a rating, verdict, Best bet or ranking order beyond tie-breaks. A bad day looks bad.
- **The two-question rule:** what is happening (topics, warnings, tides) is never silenced by a stand-down; where is worth going (ratings) is.
- **Backend-heavy:** the client renders served fields. Any new client-side derivation needs a line in `architecture-decisions.md`.
- **Personal data** (favourites, watches) never rides an ETag-shared payload.
- **Lite vs Pro:** Pro features show Lite a `PRO` pill that opens a worded upsell. The backend's 403 is the real enforcement.
- **Phone first:** every decision is made at 390 × 844 first, then checked on desktop.

## 3. Shared patterns (one of each)
| Pattern | Rule |
|---|---|
| **Rating** | `★` means stars and nothing else (`4★`, `3★+`). |
| **Yours** (a bookmarked place or area, a watched date) | A **bookmark mark** (filled ribbon, lilac `#C9A0E8`), never `★`. The words are "Bookmark" / "Bookmarked" for places and "Watch" / "Watching" for dates. The mark is decorative; the accessible name carries the meaning. |
| **Warnings** | The Met Office level triangle plus the level word (see weather-warnings-plan). |
| **Expand in place** | An entry card opens below itself (`aria-expanded`). Controls such as Watch or Bookmark are real buttons *beside* the card's own button, never inside it. |
| **Motion** | One motion only: card → map view (§6.4). Everything else is instant. All of it respects `prefers-reduced-motion`. |
| **Empty state** | One dashed hint line that teaches the next action, dismissible, shown until the first save. |
| **Pro gate** | A `PRO` pill on the control; pressing it opens the worded upsell under it. |
| **Undo** | Every removal (unbookmark, unwatch) shows a toast with Undo, about 3 s. |

## 4. Space budget (phone, 390 × 844)
| Band | Height | Rule |
|---|---|---|
| Masthead | ≤ 52 px | brand, ⌕ Find, ☰. No light times on a phone (§6.4 has them). |
| Lens, full | ≈ 80 px | sticky; two rows, then a short count (`6 spots · 4 windows`). |
| Lens, past This week | 0 px | not drawn once This week has scrolled away (§6.6, amended 2026-11-25). |
| This week heading | ≈ 28 px | carries the week's best (§6.3). |
| Your places | ≈ 34 px | one line, scrolls sideways; absent for a reader with nothing bookmarked (the hint replaces it once). |
| A quiet day | ≈ 34 px | folded (§6.5). |
| **Targets** | — | the first day starts ≤ 220 px down. With two or more quiet days, the whole week fits the first screen. (Prototype measurement: 217 px; 4 of 4 days; week 541 px, against 258 px; 3 of 4; 719 px today.) |

A new element must state its height and what it displaces, and must fit this table.

## 5. The screen, top to bottom
1. **Masthead:** brand · origin chip (desktop) · ⌕ Find (⌘K) · Ask (`/`, desktop field / phone as today) · ☰ (Operations for admin, Settings, Guide, Sign out). **Desktop only:** today's light times (`07:11 blue · 07:53 golden · 15:52 golden · 16:34 blue`), labelled "today".
2. **Lens** (sticky): Drive · Rated, then the count (`6 spots · 4 windows`).
3. **This week** heading: `This week — best Fri sunrise, Crimdon Beach 4★ →` · `Further ahead ↓` with the watched count.
4. **Your places:** one line of chips, places and areas only.
5. **The days:** four day columns (one column on a phone). Each day has its lanes (warnings first, then watched dates, then topics) and its sunrise and sunset cards, or a folded quiet line.
6. **Further ahead:** `★ Watching` group (pinned), then filter chips (All · Coastal · Night sky · Sun & moon; the choice is remembered), then month groups of entry cards. 90 days.
7. **The map view:** a full-screen overlay on a phone, near-full (inset about 18 px) on desktop. Opened from any card, lane, chip, Find result or link.

## 6. Changes from v2.42.0
Each D below is one change. Files are starting points; the planner confirms them in the code.

### 6.1 D1: a separate mark for "yours" (first, small)
- Replace `★` as the favourite/watch mark everywhere: `YourPlacesRow` (the `★ Your places` heading and chips), `shared/FavouriteToggle`, `WatchDateControl`, map markers' favourite badge, spot cards, `RegionsJump` / region tiles (`★ My places` → bookmark mark, `My places`), Find's resting group, the watched lane and Coming up / Further ahead entries.
- Toggle labels: **Bookmark / Bookmarked** (places, areas) and **Watch this date / Watching** (dates).
- Ratings keep `★`. Ask copy that says "★" for favourites changes too (the prompt line and any UI strings).

### 6.2 D2: places and watched dates separate
- **Your places** = places and areas only. Order: best rating first (then drive time); quiet ones ("nothing above 2★", "not rated yet") last and muted. One line per chip: `[mark] Crimdon Beach · Fri sunrise 4★`.
- **Watched dates leave Your places.** Delete the watched-chip path (`buildWatchedChips` in `utils/watchedDates.js` and its rendering, plus the sibling ✕ buttons in `YourPlacesRow`), with their tests.
- **Watching** = a group pinned at the top of **Further ahead**, made of the **same entry cards** (`WindowComingUpEntry`, via `watchKeysMatching`). A watched entry *moves* there (no duplicates); unwatching it returns it to its month. Opening a watched card shows the same detail panel and the Watching toggle.
- **Once a watched date is inside the forecast,** it is its day's lane in This week, with the mark and the plan: `[mark] King tide · 4.6 m` / `sunrise 07:54 · best Crimdon Beach 4★ · tide on the light →`. That opens the map view on that window (served fields only, as `watchedDates.js` does today).
- **The jump button:** `Further ahead ↓` shows the watched count (and the NEW badge rules as today).

### 6.3 D3: the week's best in the heading
- `This week — best <day> <sunrise|sunset>, <spot> <n>★ →`, from the served Best bet (no derivation); it opens that map view. With no Best bet: `This week` alone. Lite: the Best bet as Lite already sees it.
- Delete the separate **"The days ahead"** heading (`WindowFirstHeatStrip` `wf-hstrip-k`) and the `· forecast` suffix. The help `?` that sat on it moves next to the This week heading.

### 6.4 D4: the map view header carries the light; the motion
- **Header:** ‹ · `SUNRISE Fri 27 · 07:54 GO` · `best 4★ within 45 min · blue 07:11 · golden 07:54–08:36` · › · ✕. Use served light times for that day (MastheadTickLine's source), never a client calculation.
- **Masthead light times:** removed on phones; kept on desktop as today's, labelled `today`.
- **Motion:** the card's thumbnail grows into the overlay (about 230 ms, ease-out), then the live map cross-fades in. Closing reverses it (about 180 ms) onto the card that opened it, and focus returns to that card. Use the View Transitions API where available; elsewhere, no animation. **Never animate the live Leaflet map. Never animate ‹ › stepping or a link-opened view.** Under reduced motion it's a 100–120 ms fade.

### 6.5 D5: quiet days fold
- A day whose sunrise **and** sunset are both ≤ 2★ (served ratings), with **no** warning and **no** watched-date lane, folds to one line: `Wed 25 · nothing above 2★ ▸` (a button, `aria-expanded`). Opening it shows the day as normal for the session.
- Topic lanes on a quiet day (mist, snow) still show **inside** the folded line as a count (`· 1 topic`) so nothing is hidden silently. An unscored window never counts as quiet.

### 6.6 D6: the lens belongs to This week
- **Amended 2026-11-25 (owner):** the lens gates only This week (the cards, the heatmap and the count), so once the reader scrolls past This week's last day it is **not drawn** at all, rather than collapsing to one line. It comes back as soon as This week does. No animation; the page doesn't jump. Widths, focus and the verdict exception: `docs/engineering/lens-this-week-only-plan.md`.
- *As first specified and built in P4 (superseded):* a one-line collapse `Drive 45 min · any rating · 6 spots · 4 windows ▾` that opened in place, on phones only.

### 6.7 D7: remove repetition
- The lens summary becomes the count (`6 spots · 4 windows`). Delete the reach/default/rating words that repeat the buttons (`formatLensReadout` parts) and their tests; keep "weekday default" only as the drive button's own title/tooltip if it's still needed. Amended 2026-11-24 (owner): the count names the windows it spans, and 'within reach' when the drive tier hid some, because cards across windows count one place more than once.

### 6.8 D8: the empty state teaches
- A Pro reader with nothing bookmarked or watched sees, once, in place of Your places: `[mark] Bookmark places and watch dates to build your plan.` with ✕. It's gone after the first save or a dismissal (per reader, stored with their settings). Lite sees the same line with the `PRO` pill and the upsell.

## 7. What goes (ruthless, per change)
- **D1:** every `★`-as-favourite string and glyph, and the test assertions on them.
- **D2:** `buildWatchedChips` and the watched-chip rendering and ✕ siblings in `YourPlacesRow`, their CSS and tests. Any Your places sort or label code that only existed for mixing dates with places.
- **D3:** the "The days ahead" `h2` and its CSS (`wf-hstrip-k` if unused elsewhere), and the `· forecast` suffix.
- **D4:** the phone masthead's light-time row and its CSS. Masthead light-time code paths used only on phones.
- **D7:** the lens readout's redundant clauses and their formatter branches.
- After each PR, re-run the import-graph orphan check and grep, and keep deleting until clean. **Never lower `vite.config.js` coverage thresholds.**

## 8. Guides and landing (part of every PR that changes what a reader sees)
- **`planning.html`** (the screen guide): the bookmark mark and its words (D1), Your places vs Watching (D2), the best in the heading (D3), the map header's light times and the motion (D4, in a sentence), quiet days (D5), the collapsing lens (D6), the empty hint (D8). Update its screenshots.
- **`your-places.html`:** the bookmark, places only; watched dates live in Further ahead and move into This week.
- **`map.html`:** the header with the window's blue and golden hour.
- **`finding-places.html`:** Find's resting group uses the bookmark.
- **`ask-photocast.html`:** "your places" wording, if it shows ★.
- **Landing:** the pricing list and FAQ say "Bookmark places and watch dates".
- Grep every guide and landing page for `★` used as "yours", "The days ahead" and "Your places" next to dates, and fix each hit. Update `guide.js` and `guideSections.test.js` if section ids change.

## 9. Phases (one PR each, in order)
1. **P1, D1 + D7:** the bookmark mark and the trimmed lens summary. Small and visible.
2. **P2, D2:** places vs watching, with Watching built from the shared entry cards and the watched lane's plan.
3. **P3, D3 + D5 + D8:** the best in the heading, quiet days and the empty hint.
4. **P4, D4 (header and light) + D6:** the map header's light, the masthead light on phones only, and the collapsing lens.
5. **P5, D4 motion:** card → map view and back.

Each PR includes its own guide changes (§8), its deletions (§7) and the reference doc updates (`plan-tab.md`, `map-tab.md`).

## 10. Acceptance (phone, 390 × 844, real data)
- No `★` anywhere means "yours". Bookmarks and watches show the bookmark mark, and every toggle's accessible name says Bookmark or Watch.
- Your places holds only places and areas, best first, quiet ones last. Watched dates appear only in Watching (Further ahead) or as a lane on their day.
- Opening a watched entry in Watching shows the same panel as in the month list. Unwatching (with Undo) returns it to its month.
- The heading names the served Best bet, and pressing it opens that map view.
- On a week with two quiet days, all four days are on the first screen. The first day starts ≤ 220 px down.
- A quiet day with a warning or a watched date never folds.
- After scrolling past the week, the lens is gone and Further ahead's bar sits at the top; scrolling back brings the full lens, with no jump.
- The map view header shows that window's blue and golden times. The phone masthead has no light times; desktop has today's.
- Card → map → close animates both ways with focus back on the card. Under reduced motion it fades. ‹ › never animates.
- Lint, tests (coverage floors unchanged), audit, build, the E2E smoke and a11y are all green. The orphan check is clean.
- The guides match the screen (grep checked).

## 11. References
- `One Screen Polish.html`: the switches for D1, D3–D8 and the space meter (recommended settings = this spec).
- `Places and Watching.html`: D2, with Watching built from the shared entry cards.
- `One Screen.html`: the overall screen and the map view as built.
- Code anchors: `YourPlacesRow.jsx`, `utils/watchedDates.js`, `WatchDateControl.jsx`, `FurtherAhead.jsx`, `WindowComingUpEntry.jsx`, `WindowFirstHeatStrip.jsx`, `WindowGlanceLanes.jsx`, `MastheadTickLine.jsx`, `WindowFirstMapPane.jsx`, `shared/FavouriteToggle.jsx`, `map/RegionsJump.jsx`, `PlanSearch.jsx`.

## 12. Screenshots (`screenshots/`, from the prototypes at 2×)
The map in 06 and 08 is a stand-in drawing; the real view is today's map. Ratings and dates are illustrative.

| File | Shows |
|---|---|
| `01-phone-today.png` | v2.42.0 as it is: ★ for yours, full quiet days, two headings, a long lens summary, light times in the masthead. |
| `02-phone-spec.png` | This spec: the bookmark mark, the best in the heading, quiet days folded, `6 spots`. The whole week on one screen. |
| `03-phone-spec-scrolled-lens-collapsed.png` | D6: the one-line lens after scrolling past the week. |
| `04-phone-quiet-day-opened.png` | D5: a quiet day opened in place. |
| `05-phone-new-reader-hint.png` | D8: the one-time hint for a reader with nothing saved. |
| `06-phone-map-view-header-light.png` | D4: the map view header with that window's blue and golden hour. |
| `07-desktop-spec.png` / `09-desktop-today.png` | desktop after and before (today's light times stay in the desktop masthead, labelled "today"). |
| `08-desktop-map-view.png` | the near-full overlay with the light in its header. |
| `10-phone-watching-entry-open.png` | D2: a watched king tide opened in the Watching group, the same card and panel as in the month list. |
| `11-phone-places-only-and-watched-lane.png` | D2: Your places holds only places; the watched king tide is Friday's lane with the plan. (This prototype predates D3/D5/D7, so its heading and days are as today.) |
