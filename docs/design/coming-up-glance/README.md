# Handoff: Coming up tidy + "At a Glance" rename

## Overview
Three changes to PhotoCast:
1. **Remove the Recurring conditions panel** from the Coming up tab.
2. **Rebuild Next four days** as four day tiles listing that day's topics as chips (replaces the current lanes layout). Weather-driven topics (valley mist, inversions, Saharan dust) now surface here, and only here.
3. **Rename the Plan tab**: "At a Glance" on desktop/iPad, "Glance" on phone. Also add an "In next 4 days" tag to dated Coming up entries that overlap the strip.

## About the design files
`Coming Up - No Recurring Panel.html` is a **design reference built in HTML**, not production code. Recreate it in the existing PhotoCast codebase, using its components, tokens and patterns (`components/ViewToggle.jsx` etc.). Entry cards below the strip are abbreviated in the mock. Keep the existing entry card internals (tide chart, coastal spots by day, etc.) unchanged apart from the new tag.

## Fidelity
**High-fidelity** for the Next four days strip, tab labels and new tag. The surrounding masthead and entry cards are simplified stand-ins, so keep the production versions of those.

## Screenshots (`screens/`)
- `00-current-coming-up-full.png`: current Coming up tab. The Recurring conditions panel (circled) is what gets removed.
- `00-current-next-four-days.png`: current lanes version of Next four days (to be replaced).
- `01-desktop-coming-up.png`: target, desktop/iPad.
- `02-phone-coming-up.png`: target, phone.
- `03-desktop-next-four-days.png`: target strip, close-up.

## 1. Remove Recurring conditions
- Delete the panel and its data plumbing from the Coming up view.
- The tide row is already covered by the dated list. Dust, inversions and mist move to the strip (below).
- Update the list's footer copy to: *"This list starts where the four-day forecast stops and shows dates fixed in advance. Weather-driven topics can't be known that far out, so they appear only in Next four days."*
- Filter chip counts should reflect dated entries plus occurrences within the four-day window (e.g. Air & dust 0 when none are forecast). The mock adds a "Mist" chip. Treat this as optional and follow the existing category set if mist already lives in another category.
- Optional: the "lately about twice a week" baseline rate can move into the hot topic's Map detail. Not required for this handoff.

## 2. Next four days strip

### Container
- Bordered box: `1px solid #4A3A2E`, radius 10px, padding 12px 14px, bg `rgba(0,0,0,.14)`, vertical gap 10px.
- Header row (space-between, baseline):
  - Left: "NEXT FOUR DAYS", IBM Plex Mono 10px/600, letter-spacing .14em, uppercase, `rgba(242,231,211,.7)`. Desktop appends "5 topics · from the forecast" (mono 10px/400, `rgba(242,231,211,.5)`, no uppercase, 8px left margin). Hidden on phone.
  - Right: link "At a Glance →" (phone: "Glance →"), mono 11px, `#C9A24B`. Goes to the At a Glance tab.

### Day tiles
- Grid: 4 equal columns `repeat(4,minmax(0,1fr))`, gap 8px (phone 5px). Always 4 columns. No horizontal scroll.
- Tile: `1px solid #4A3A2E`, radius 9px, bg `rgba(0,0,0,.2)`, padding 8px 10px 10px (phone 6px 5px 7px), column gap 8px.
- Today's tile: border `rgba(242,231,211,.5)`, and its relative label is full ink.
- Day header (baseline row, 7px gap):
  - Weekday: mono 9px/600, .12em, `rgba(242,231,211,.5)`, e.g. "SAT".
  - Date: Plex Sans 19px/700, line-height 1 (phone 16px).
  - Relative: mono 9px, right-aligned. "today" / "tomorrow" / "in 2 days" / "in 3 days". On phone use "today" / "tmrw" / "+2" / "+3" and stack under the date (left-aligned, 8px).
- Empty day: render nothing in the chip area. The tile stays.

### Topic chip
One chip per topic per day, stacked (gap 4px). The whole chip is a link.
- Body: padding 4px 7px (phone 3px 5px), radius 5px, bg `color-mix(in oklch, <topic colour> 13%, transparent)`.
- Line 1: 7×7px swatch (radius 2px, topic colour) + name, Plex Sans 12px/600 (phone 10.5px), ink `#F2E7D3`. Right-aligned badge (desktop only):
  - `PEAK` / `FULL`: mono 8.5px, .08em, uppercase, in topic colour, on the peak day of a dated topic.
  - `WX`: mono 8px, uppercase, `rgba(242,231,211,.5)`, `1px solid #4A3A2E`, radius 3px. Marks weather-driven topics.
- Line 2: the day's key value, mono 9.5px (phone 8.5px), `rgba(242,231,211,.7)`, indented 13px on desktop to align with the name, single line with ellipsis.
- Continuation day (multi-day run after its first day): transparent bg, `1px dashed color-mix(in oklch,<colour> 45%,transparent)`, name weight 500 in ink-2. This makes a run read as one thing across tiles.
- Phone labels must not ellipsize or break mid-word. Use short names: Surge, Spring, Invert, Mist, Moon. The full name can go in the accessible label.

### Topic colours
- Tide / coastal `#6FA8B0`
- Storm surge `#D9805F`
- Sun & moon `#C9A24B`
- Mist / inversion `#9DB59A` (new, mock value; match the existing hot-topic colour if one exists)
- Air & dust `#B88FC9` (mock value; same caveat)

### Chip content (from the mock, Sat 24 – Tue 27 Oct)
- Storm surge: "HW 16:02 · +0.4 m", peak
- Spring tide: first day "4.4 m · run starts", then "4.8 m", then "4.9 m · top 7%" with peak
- Supermoon: "rise 16:34", FULL
- Valley inversion: "dawn · 9/10", WX
- Valley mist: "dawn · 8/10", WX

Mist and inversion scores are illustrative. Use the real model score (same /10 scale and thresholds as the old panel: inversion ≥ 9/10, mist ≥ 8/10 standout).

### Footnote
Mono 10px, `rgba(242,231,211,.5)`: *"WX = weather-driven, only shown once it's in the forecast. Tap a tide or moon chip to jump to its entry below; tap a WX chip to open that dawn on the Map."*

### Interactions
- Dated chip (tide, moon, surge with an entry): scroll the Coming up list to that entry (no `scrollIntoView`; use the scroll container's `scrollTo`) and briefly highlight it.
- WX chip: open the Map tab at that day/time with the relevant hot topic active (reuse existing hot-topic deep links).
- Hover/focus: border becomes the topic colour, bg rises to ~20% mix. Visible focus ring for keyboard users.

## 3. "In next 4 days" tag
- On any dated Coming up entry whose date range overlaps the four-day window.
- Placed in the entry's tag row alongside ALMANAC / BIGGEST UNTIL …
- Style: mono 8.5px, .08em, uppercase, padding 1px 5px, radius 3px, ink `#F2E7D3`, bg `color-mix(in oklch,#6FA8B0 22%,transparent)`, border `color-mix(in oklch,#6FA8B0 55%,transparent)`.
- Phone: the entry title goes on its own block line, with tags wrapping in a row underneath, so a two-line title never overlaps the tags.

## 4. Tab rename
- Plan → **At a Glance** (desktop, iPad) / **Glance** (phone breakpoint). Keep the ◉ glyph.
- Internal value can stay `plan`. Change only the label (`components/ViewToggle.jsx`, `ALL_OPTIONS`).
- Update every user-facing reference: "On Plan →" → "At a Glance →" / "Glance →"; "Open in Plan" → "Open in At a Glance" (phone "Open in Glance"); Ask prompts and empty states that name the tab.
- Phone tabs: equal-width (`flex:1`), min-height 36px within a 44px hit area.

## State
- `forecastWindow`: next 4 days from local today, derived from the same source as At a Glance.
- `dayTopics[day]`: ordered list of `{topicId, kind:'dated'|'weather', name, shortName, colour, value, isPeak, isContinuation, entryId?, mapLink?}`. Order: dated first by start date, then weather by score.
- Entry `inNext4Days` = entry range ∩ forecastWindow ≠ ∅.

## Tokens
- bg `#181210`, page `#0e0b09`, border `#3A2C23`, border-light `#4A3A2E`
- ink `#F2E7D3`, ink-2 `rgba(242,231,211,.7)`, ink-3 `.5`, ink-4 `.28`
- Fonts: IBM Plex Sans (UI), IBM Plex Mono (labels, values), Newsreader (wordmark, entry body)
- Radii: 3 (tags), 5 (chips), 7 (date boxes), 9 (tiles, cards), 10 (strip), 12 (app frame)

## Files
- `Coming Up - No Recurring Panel.html`: the design reference (desktop + phone side by side).
- `screens/`: before/after screenshots listed above.
