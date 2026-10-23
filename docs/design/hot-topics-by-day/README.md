# Handoff: Hot topics by day — Coming Up strip + Map

## Overview
Two linked changes that show **which hot topic happens on which day, and where**:

1. **Coming Up tab — "Next four days" lanes.** Replaces the dashed one-line summary ("Now — Mon 26 · Three topics on those four days …") between Recurring/Standing conditions and the month list.
2. **Map tab — hot topics per window.** Topic chips under the window control, rings on qualifying locations, a docked four-day strip, and a new daylight **High water** event for Big seas days.

## About the Design Files
The files in this bundle are **design references created in HTML** — prototypes showing intended look and behaviour, not production code to copy directly. Recreate them in the PhotoCast codebase using its existing React components, patterns and data layer. `map-storm.js` / `map-topics.js` are prototype logic to read for behaviour, not to ship.

## Fidelity
**High-fidelity.** Colours, type, spacing and interactions are final and use the existing PhotoCast tokens. All forecast data (topic days, peaks, sunrise/sunset links, Friday midday swell, wave heights) is **mocked**.

---

## 1 · Coming Up — "Next four days" lanes
Reference: `Coming Up v2.html` → `.fd` block. Screens: `screenshots/coming-up-desktop.png`, `coming-up-phone.png`.

### Layout
- Container `.fd`: margin-top 14px; padding 10px 13px 12px; border 1px `#4A3A2E`; radius 9px; bg `rgba(0,0,0,.12)`; flex column, gap 8px.
- Header row: label "NEXT FOUR DAYS" (IBM Plex Mono 9.5px/600, letter-spacing .12em, uppercase, `--ink-3`) left; "On Plan →" (mono 10.5px, `#C9A24B`) right.
- Grid `.fdl`: `grid-template-columns: 118px repeat(4, minmax(0,1fr)) 20px`; row-gap 6px. **Every item is explicitly placed** (grid-row + grid-column) — auto-placement broke this twice.
  - Row 1: empty cell, then 4 day headers (cols 2–5).
  - Rows 2…n: one per topic. Col 1 label; bars/dots in cols 2–5; col 6 = jump button (only for runs that continue past the window).
- Today shade `.fdnow`: grid-column 2, grid-row 1 / last; bg `rgba(242,231,211,.05)`; radius 7px; under everything.

### Components
- **Day header** `.fdh`: border 1px `#4A3A2E`, radius 7px, bg `rgba(0,0,0,.2)`, padding 3px 0 4px, margin 0 3px 3px. DOW mono 8.5px/600 .12em `--ink-3`; date Plex Sans 15px/700. Today: border `--ink-3`.
- **Topic label** `.fdn`: emoji 12px + name Plex Sans 12.5px/600, gap 6px. Phone: short name (`Spring tide` → `Tide`).
- **Bar** (multi-day span) `.fdb`: height 22px, margin 0 3px, radius 5px, bg topic colour 22% (`color-mix`), border topic colour 45%. Left text mono 10px (`tonight → Sat`, `runs on`); right `.pkm` peak label in topic colour/600 incl. light window (`peak Sat · after dusk`, `peak Tue · ↓ sunset`). Hover: border full colour, bg 32%.
- **Open bar** (runs past Mon): right border dashed, right radius 0, followed by **jump button** `.fdj` (20×22, dashed border, ↓, topic colour) → scrolls to the next matching entry in the month list and briefly outlines it (1.6s). Tooltip states the target ("Next run in the list · 10–15 Sep") because the current run isn't listed.
- **Dot** (single window) `.fdo`: 16px circle, topic colour, halo `0 0 0 4px` colour 18%; carries `↑` (sunrise) or `↓` (sunset) glyph in `#1B1411`.
- **Tap-through**: bar/dot → opens Plan on that date with the topic selected (prototype shows a toast "Opens Plan on Sat 29 · Aurora →").

### Phone (≤390)
Label column 76px, jump column 16px; bar text hidden, only `.pkm` (9px) shown; header margins 0 2px.

---

## 2 · Map — hot topics per window
Reference: `Map Hot Topics.html` (+ `map-topics.js`, and the gated additions in `map-storm.js`). Screens: `screenshots/map-01…05`.

### Rule: topics belong to the solar event, Big seas belongs to the day
- Dust, Inversion, King tide, NLC, Aurora → per **window** (`WTOPICS[windowId]`). Only of interest at dawn/dusk.
- Night events (Astro/Aurora) inherit that evening's sunset topics (+ Aurora on aurora nights).
- **Big seas** → per **day**: shows on every window that day; the window where high water lands on the light is "on the light".

### "Where" without region data
Derived from each topic's existing `needs` against the location set:
- `needs:'coast'` (King tide) → coastal locations in scope → "66 coastal".
- `needs:'lake'` (Inversion) → "lake & valley".
- Big seas → the named structures in the sea record (`at[]`) → "4 structures".
- No `needs` (Aurora, NLC, Dust) → **sky-wide**.
Future (e.g. Snow in the Lakes) needs only a location tag such as `high`.

### A · Topic chips `#tchips`
Full-width row inside the window-control bar, under the Big seas warning line. Flex wrap, gap 6px.
- Chip `.tch`: pill (radius 999), bg `rgba(24,18,15,.92)`, border 1px `#3A2C23`, padding 4px 10px 4px 8px, min-height 30px. Emoji + name (Plex Sans 12px/600 `--ink`) + where (mono 9.5px `--ink-3`). On Big seas HW days the name reads `Big seas · HW 12:40`.
- Hover: border = topic colour. Selected: border topic colour, bg colour 20% over panel, halo 3px colour 18%.
- Empty: "No hot topics on this window".
- Phone: "where" text hidden.

### B · Rings on the map
Tap a chip (toggle). Located topics: 22px ring, 2px border topic colour, halo 3px 22% + glow 12px 55%, at each qualifying location (z under chips). All location chips dim to opacity .5 while a topic is selected. Sky-wide topics: centred notice instead of rings — "🌌 **Aurora possible** · sky-wide — every location in range sees it or none does". Selection persists across windows while the topic is present; clears when it isn't.

### C · Four-day strip `#tlanes`
Docked bottom-left: left/bottom 12px, width 440px, bg `rgba(20,15,12,.94)`, border `#3A2C23`, radius 10px, shadow `0 14px 34px rgba(0,0,0,.5)`. Collapsible header "HOT TOPICS · NEXT FOUR DAYS ▾" (mono 8.5px/600 .13em).
- Grid: `92px repeat(8, minmax(0,1fr))` — four days × (sunrise ↑, sunset ↓). Rows: day headers (span 2 cols), ↑/↓ sub-row, then 24px per topic. All explicitly placed.
- Bars `.tlb`: contiguous windows merge into one bar; bg colour 26%, border 50%, radius 5px. Bar containing the current window: full-colour border; if also the selected topic: solid fill + glow.
- Current window column shaded `rgba(242,231,211,.07)` (HW event shades the whole day).
- **HW tick** on the Big seas row: vertical 2px line positioned proportionally between that day's sunrise and sunset; label "HW 12:40" above (mono 8.5px/600 `#8FC0C7`). Current: 3px `#12100E` with 1.5px `#F2E7D3` outline (must contrast with the selected solid-teal bar).
- Interactions: bar → jump to that window with topic selected; row label → select topic (or jump to its first window); tick → jump to the High water event.
- Legend chip/panel sit above the strip (`bottom: strip height + 22px / +66px`); footer count moves right.
- Phone: full width, bottom 60px above the nav; label column 70px; footer hidden.

### D · Daylight "High water" event
For Big seas days where high water is in daylight but not on a light window (e.g. Fri 12:40):
- Inserted into the window list chronologically between sunrise and sunset. Kind badge `HIGH WATER` in tide teal (`#8FC0C7`, bg 16%, inset border 34%).
- Pill: `HIGH WATER · Tomorrow 12:40 · BIG SEAS` (verdict word "Big seas", neutral tier — not a light score).
- Big seas structures are ringed (aligned style); warning line: "11:30–14:00 · high water 12:40, midday"; off the event, its button reads "At high water 12:40 ›".
- Lead copy: "High water at 12:40 with 3.4 m waves from NNE. Not a light window: this is for spray over the piers in flat midday light."
- Data needed: per Big seas day `{day, hw, from, to, hs, dir, per, at:[{location, hs, face}]}` with `align` = `Sunrise` | `Sunset` | `High water`.

---

## State
- Coming Up: none beyond existing; links target Plan(date, topic) and a list entry id.
- Map: `selectedTopic: string|null`, `stripOpen: boolean`, existing `eventIndex`. Derived: `topicsOn(event)`, `locationsFor(topic, event)`, `columnOf(event)`.

## Design tokens (existing)
`--bg #181210` · `--surface #221A15` · `--panel #1E1712` · `--border #3A2C23` · `--border-light #4A3A2E` · `--ink #F2E7D3` · `--ink-2 rgba(242,231,211,.66)` · `--ink-3 rgba(242,231,211,.42)` (.66 inside the map frame) · `--home #C9A24B` · `--tide #6FA8B0`.
Topic colours: Aurora `#8AAE72` (Coming Up) / `#7C8DD6` (map data) · NLC `#8E86D6` · King/Spring tide `#6FA8B0` · Inversion `#8FA3B8` / `#B4BEC0` · Dust `#F5991F` · Big seas `#8FC0C7`. Use the app's canonical topic colour map — the two mocks inherited slightly different sets.
Type: IBM Plex Sans (UI), IBM Plex Mono (data/labels), Newsreader (prose). Radii 5 / 7 / 9 / 10 / 999.

## Files
- `Coming Up v2.html` — Coming Up with the lanes (`.fd*` CSS + markup, small inline script for toast/jump).
- `Map Hot Topics.html` — map mock; CSS for chips/rings/strip/HW at the end of `<style>`; STORM data with `hwEv:true` and the Fri midday sea.
- `map-topics.js` — chips, rings, strip, HW tick logic.
- `map-storm.js` — base map prototype; HW event additions are gated on `STORM.hwEv` (search `hw`).
- `plan-data.js`, `heat-field.js` — mock data and heat field used by the map.
- `screenshots/` — desktop and phone captures of both.
