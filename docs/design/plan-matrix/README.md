# Handoff: Plan tab — the day/window matrix

## Overview

The Plan tab answers one question: **where and when should I go shooting in the next four days.** It shows six solar windows (sunrise and sunset for each day in the forecast) as a matrix of heat-map thumbnails — a column per day, sunrise row above sunset row. Each thumbnail carries its own verdict, its own best reachable location, and any hot topic (aurora, king tide, inversion…) on that night. Tapping a thumbnail opens a popup with the full field, the region breakdown, tide, and the ranked locations.

Everything is computed relative to an **origin** — a postcode by default, or any region the user searches for. Changing the origin reframes the whole tab: the maps refit, the drive times recompute, the best-bet moves, and topics that need geography the new scope doesn't have drop out of the list.

This replaces an earlier version that showed the same six windows as a list of expandable rows. The list said the same thing four times per row (thumbnail word, best 5★, confidence %, verdict pill) and the accordion moved the page under the user's finger. The list, the accordion, the per-row second map and the order-by control are all deleted.

## About the design files

**The files in this bundle are design references created in HTML.** They are prototypes showing intended look and behaviour, not production code to lift wholesale. The task is to **recreate these designs in the target codebase's existing environment** — React, Vue, SwiftUI, native, whatever is already there — using its established patterns, component library and styling approach. If no environment exists yet, choose the framework appropriate to the project and implement there.

**One exception, and it matters:** `heat-field.js` should be ported close to as-is. It is the shared heat kernel — the only place that decides what a score looks like — and both the Plan tab and the Map tab draw through it. Forking it produces two surfaces that disagree about what a 4★ area looks like, which is the bug it was written to prevent. Port it as a module, keep its two host adapters (`drawGeo` for canvas, `drawTiles` for a Leaflet basemap), and let both tabs call it.

`plan-data.js` is fixture data (a seeded PRNG stands in for 204 rated locations). Replace it with the real API; keep the shapes it implies, documented under **Data shapes** below.

## Fidelity

**High fidelity.** Colours, type, spacing, radii, thresholds and interaction states are all final and are given exactly below. Recreate pixel-accurately using the codebase's existing primitives. The three viewport widths in the prototype (a Desktop/iPad/iPhone toggle above the frame) are a demo affordance only — in production they are breakpoints, and the toggle plus its `localStorage` key should not ship.

---

## Screens / views

### 1. Plan tab (`screens/01-desktop-plan-top.png`, `02-desktop-matrix.png`, `05-ipad-plan.png`, `07-phone-plan.png`)

Vertical stack inside a scroll container: masthead (sticky) → tab bar → lens bar (sticky) → body.

**Masthead.** Sticky, `z-index 45`. Background `linear-gradient(180deg,#241B16,#1B1411)`, `border-bottom 1px var(--border)`, padding `14px 22px 0` (iPad `13px 20px 0`, phone `9px 14px 0`).

- Wordmark "PhotoCast": Newsreader 600, 26px / iPad 23px / phone 19px, `letter-spacing -.022em`, `#F9F1E2`. Kicker "FIELD GUIDE TO LIGHT": mono 9px, `.2em`, uppercase, `--coral`. Both sit right of a 25px left pad holding a film-perforation spine — `repeating-linear-gradient(180deg, var(--border-light) 0 7px, transparent 7px 15px)`, 11px wide, `border-right 1px var(--border)`. Phone: 19px pad, 8px spine.
- Right cluster: status chip ("UP" + 6px `--go` dot with `0 0 6px` glow, version in `--ink-3`, hidden on phone), 15px circle cog, "Sign out" button (11.5px, hidden on phone).
- **Light rule:** 4px tall, `border-radius 2px`, `margin-top 12px`. `linear-gradient(90deg,#26313F 0%,#4A3550 9%,#B4553C 19%,#E8593F 25%,#E0A542 33%,#F2E7D3 50%,#E0A542 66%,#E8593F 74%,#7C4A56 84%,#2E3446 93%,#26313F 100%)` — a day of light left to right.
- **Tick line** under the rule, `padding 5px 0 8px`. This is the **only** statement of where the plan is computed from; there is no separate origin chip or breadcrumb anywhere in the tab.
  - Origin button: `min-height 30px` (phone 34px), mono 9.5px `.13em` uppercase, `--ink-2`; name in `--ink` 600. Home pin SVG 13px in `--home`; when the origin is a region, a map-pin SVG in `--tide` and the button gains `border-color rgba(111,168,176,.4)` / `background rgba(111,168,176,.09)`. Then a 1px full-height separator, then a search glyph and a `/` keyboard chip (chip hidden on phone). Hover: `border-color var(--border)`, `background rgba(255,255,255,.03)`. Label reads `Home · DH3 4NG` at home, `<Region> · from <base>` away.
  - Home button (30px, phone 34px square, `--home` house glyph) appears only when the origin is not home.
  - Times, right-aligned: mono 9px `.13em` uppercase `--ink-3`, with the two golden-hour times in `--marginal` 500. Four on desktop/iPad (`blue, golden, golden, blue`), the middle two only on phone.
- Clicking the origin button swaps the tick line for a **search field** (`min-height 38px`, `border 1px rgba(201,162,75,.5)`, `radius 9px`, input 14.5px 500) and opens the dropdown below the masthead. See view 4.

**Tab bar.** `Plan · Coming up · Map`, plus `Operations` pushed right (hidden on phone). Tabs: 12.5px 500, `padding 8px 14px`, `radius 8px 8px 0 0`, `background var(--panel)`, no bottom border. Active: `background var(--surface)`, 600, `box-shadow inset 0 2px 0 var(--home)`. Followed by a 1px `--border` rule.

**Lens bar.** Sticky at `top: var(--mastH)` (measured from the masthead at runtime), `z-index 30`, `background var(--surface)`, `padding 10px 22px`, `border-bottom 1px`. Gains `box-shadow 0 12px 26px rgba(0,0,0,.5)` and `border-bottom-color var(--border-light)` once stuck (driven by an IntersectionObserver on a 1px sentinel above it).

Two controls only:
- **How far to travel** (label changes to `Drive from <base>` when away): segmented `45 min · 1h 30 · 2h 30 · Any`.
- **Rated**: segmented `Any · 3★+ · 4★+`.

Segmented buttons: mono 11px, `min-height 34px` (phone 38px), `border-right 1px var(--border)` between, container `border 1px var(--border)` `radius 8px` `background var(--panel)`. Active: `background rgba(201,162,75,.15)`, `#EBD9A8`, 600. The rating group's active state is green instead: `rgba(138,174,114,.16)` / `#B5CFA3`.

When reach is not the default, a `TODAY ONLY` marker (mono 9px uppercase, `rgba(224,165,66,.14)` / `#EFC377`) and a `Back to 45 min` reset link (`--tide`) become visible — reserved space, `visibility` not `display`, so nothing shifts. Right-aligned count: `<b>40</b> of 51 locations within reach · 4★+`.

On phone the lens stacks into two labelled rows, each segmented control `flex: 1`.

**Body.** `padding 13px 22px 20px`, flex column, `gap 10px`.

1. **Messages** (`#msgs`, empty by default) — origin/lens conflicts. See Interactions.
2. **Section head:** `THE DAYS AHEAD` (mono 9.5px 600 `.1em` uppercase `--ink-3`) + 1px flexible rule + count `204 rated locations · 51 named` (mono 10px, hidden on phone). When the origin is a region the head reads `THE DAYS AHEAD · THE LAKE DISTRICT`.
3. **The matrix** — see below.
4. **Legend footer:** a 60×6px gradient bar `linear-gradient(90deg,#C8452F,#E0A542 52%,#8AAE72)` + `poor → worth it` + `later days render hazier — lower confidence` + right-aligned `Tap a window for its regions, tide and locations` (mono 10px `--ink-3`; the right-hand hint is desktop-only).
5. **Change line:** `Since your last look 52m ago · Thursday sunset ▲0.5 in Northumberland & Tyneside · …` — the two windows that actually moved, named, with the region that moved them. Mono 10px, window names in `--ink-2` 600.
6. **Beyond line** (only when planning from home and something is out of range): `Beyond 3h and not in the field: Highlands & Skye — search to plan from one →`, mono 10px `rgba(242,231,211,.34)`, link in `--tide`.

#### The matrix (`.hstrip`)

`display: grid`, `grid-template-columns: repeat(var(--dc), 1fr)` where `--dc` is the number of distinct days in the forecast (4 in the fixture), `gap: 7px 8px`. Children are **explicitly placed** (`grid-column: var(--c); grid-row: var(--r)`) rather than flowed: row 1 is the day headers, row 2 sunrise, row 3 sunset. Placement is derived from the data — group windows by `dow + dn`, one column per day — so a seven-day forecast gives seven columns with no layout change.

Phone transposes the same markup with `grid-template-columns: repeat(2, 1fr)` and `grid-column/row: auto`, with day headers spanning `1 / -1`; a day holding only one window gets the full row width (`.hc.solo`) and the empty cell is `display: none` — a phone has no width to spend on a hole.

**Day header (`.dh`).** Flex, `gap 8px`, `padding 0 2px 3px`. A calendar tile then a hairline rule running to the right edge of the column:
- Tile: grid, centred, `padding 3px 6px 4px`, `border 1px var(--line)`, `radius 5px`, `background rgba(0,0,0,.24)`. Weekday mono 8px 600 `.12em` `--ink-3`; date mono 14px 600 `--ink-2`, `font-variant-numeric: tabular-nums`.
- Rule: `flex: 1`, `height 1px`, `background var(--line)`.
- Today's column: tile `border-color: color-mix(in srgb, var(--home) 45%, transparent)`, date in `--home`. No "TODAY" word.

**Empty cell (`.hg`).** Two cells are empty by definition — this morning has gone, and the last evening is past the end of the forecast. They say so rather than being closed up: `border 1px dashed var(--line)`, `radius 9px`, `min-height 110px` (phone 64px), centred mono 9.5px `--ink-3` reading `this morning has gone` / `past the end of the forecast`.

**Window card (`.hc`).** A button. `border 1px var(--border)`, `radius 9px`, `background var(--panel)`, `padding 6px 6px 7px`, flex column `gap 5px`, `overflow: visible` (the pick legend overhangs the top edge). Hover: `translateY(-2px)`, `border-color var(--border-light)`. Contents top to bottom:

1. **Sun row** (`min-height 19px`): the word `SUNRISE` or `SUNSET`, mono 9px 600 `.09em` `--ink-3` (`--home` when this card's popup is open). Deliberately a word, not an arrow — a down arrow for sunset reads as a falling forecast.
2. **Canvas** — the heat field. `width: 100%`, `radius 5px`, `background #13100e`. Height = card width × `clamp(HeatField.aspect(fit), 0.78, 1.0)`. Drawn at `cardWidth − 12` px, measured **per card** because a solo phone card spans the full row.
3. **Value grid** (`.pls`): `grid-template-columns: auto 1fr`, `gap 4px 6px`, rows `min-height 20px`. Four rows, so every card's rows land on the same baselines:
   - time (mono 12px 600 `--ink-2`) | **verdict word**, right-aligned, mono 11px 600 — `Worth it` `#A8C795`, `Maybe` `#EFC377`, `Poor` `#E58C7A`
   - `SPREAD` (mono 8.5px 600 `.1em` uppercase `--ink-3`) | a 5-bar histogram, bars 5px wide in a 14px box, one bar per star band 1★→5★, height proportional to count (min 2px), each bar filled with that band's ramp colour at `.92` alpha, empty bands `rgba(242,231,211,.13)`. `title` gives exact counts. A lone spike on the right reads *one good spot, drive to it*; a right-weighted block reads *the whole area is on*.
   - rating (mono 12px 600, in that rating's ramp colour) | **the best location you could actually reach**, mono 11px 600 `--ink`, right-aligned, `line-height 1.3`, wrapping to two lines rather than ellipsing. `title` carries region · drive · leave-by. When nothing is in reach: label `Best`, value `nothing in reach` in `--ink-3` 400.
   - topics row, spanning both columns (`min-height 21px`, reserved even when empty so a topic-free card doesn't shorten its neighbours): every topic on that night, named in full, flex-wrapped `gap 4px 9px`, right-aligned, rarest first. Each is a glyph + short name, mono 10.5px 600 in the topic's own colour. Nothing is collapsed behind a `+2` and nothing depends on hover.
4. **Verdict tint** on the whole card, not a chip: `.vg` `linear-gradient(rgba(138,174,114,.12), …)` over `--panel`; `.vm` `rgba(224,165,66,.1)`; `.vp` `rgba(200,69,47,.09)`. Hover deepens to `.17 / .15 / .14` over `--surface`. Low enough that it never competes with the field inside it.
5. **The pick rides the border** as a fieldset legend: `.hc.best` gets `border-color #8AAE72` + `inset 0 0 0 1px rgba(138,174,114,.45)`; `.hc.also` gets `border-color rgba(138,174,114,.5)`. The label sits *in* the border line — `position: absolute; top: -8px; right: 13px; padding: 0 7px; background: inherit`, mono 10px 700 `.13em` uppercase, `line-height 16px`; `BEST BET` `#B6D49F`, `ALSO GOOD` `#8CA87A` 600. This keeps the verdict word in the same column on all six cards.
6. Open state (`.hc.on`): `border-color rgba(201,162,75,.62)`, `rgba(201,162,75,.11)` wash, no shadow, and the sun word / time go `--home`.

**Exactly one card is `BEST BET` and one is `ALSO GOOD`** — top and runner-up by the best regional average in the window, and the runner-up is suppressed if it grades Poor. A recommendation that fires on half the week is not a recommendation. `ALSO GOOD` landing on a `Maybe` card is expected and fine.

### 2. Window popup (`screens/03-window-popup.png`, `04-…-region-picked.png`, `06-ipad-…`, `08-phone-…`)

Opens over the plan; the plan does not move. Scrim `rgba(8,6,5,.74)`. Card: `position absolute; left 50%; translateX(-50%); top 16px; width min(780px, 100% - 32px); max-height calc(100% - 32px)`, `background var(--bg)`, `border 1px var(--border-light)`, `radius 13px`, `box-shadow 0 30px 80px rgba(0,0,0,.72)`. iPad: `width calc(100% - 28px)`. Phone: full-screen, no radius or border.

**Header** (`padding 12px 15px`, `border-bottom 1px`, `linear-gradient(180deg, rgba(201,162,75,.07), transparent)` over `--surface`):
- Date box: `border 1px var(--border-light)`, `radius 7px`, `background rgba(0,0,0,.2)`, weekday mono 9px 600 `.1em` `--ink-3` over date 15px 700.
- Title `Thursday sunset` 16px 700 `-.02em`; time mono 13px 600 `--ink-2`; then the verdict badge, the pick badge if any, and one pill per topic (pills use the topic colour via `color-mix`).
- Second line, mono 10px `--ink-3`, `gap 12px`: `best 5★ within reach · average 4.1★ across 40 locations · ◐ 65% confidence · ▲0.5 since 52m ago`. Phone abbreviates.
- Nav right: `‹ 5/6 › esc`, 30px square buttons (phone 38px, and the nav moves to its own full-width row above the title).

**Body** — two columns on desktop and iPad (`grid-template-columns: minmax(280px,39%) 1fr`, `gap 0 14px`, `padding 0 15px 11px`), single column on phone.

*Left: the field.* `border 1px var(--border)`, `radius 9px`, height = width × `clamp(aspect, 0.88, 1.34)` (phone `0.5–0.95`). Two label layers over the canvas, placed in one greedy pass — regions claim space first, then the strongest locations; a label that can't fit is dropped rather than overlapped:
- Region names: mono 9.5px 600, `#F2E7D3`, `text-shadow 0 1px 3px rgba(0,0,0,.95), 0 0 10px rgba(0,0,0,.8)`, at the region's projected centroid. The focused region's name is omitted (the pill and prose already name it).
- **Location chips** — the reason the field resolves into places rather than areas: a 5px square marker, the name (mono 9px 600 `--ink`), and that window's rating in its ramp colour behind a 1px divider. `background rgba(14,11,9,.84)`, `inset 0 0 0 1px var(--border-light)`, `radius 5px`. Chips flip to the left of their point when the right side won't fit. Max 8 (phone 6). They come from the same filtered pool the cards below are ranked from, so the map can never name a spot the list has excluded. Clicking one opens the location sheet.
- Bottom-left hint: `tap a region` / `tap the region again to clear` / `one region in scope`.

*Right: regions and prose.*
- `THIS WINDOW BY REGION · RANKED · TAP TO FILTER THE LOCATIONS BELOW` (mono 9px 600 `.1em` uppercase; the tail clause is hidden on phone).
- Region cards (`grid auto-fit minmax(128px,1fr)`, iPad `150px`, phone 2 columns, `gap 6px`): `border 1px var(--border)` with `border-left 2px` in the grade colour, `radius 8px`, `background var(--panel)`, `padding 7px 9px 8px`, `min-height 56px`. Name 11.5px 600; verdict word mono 9.5px 600 in the grade colour; footer mono 9px `--ink-3` — `best 5★ · 10 in reach`, or a `--tide`-coloured `2h 38min away` when nothing is in reach. Selected: `rgba(201,162,75,.1)` / `rgba(201,162,75,.5)`. First card is `All 7 regions` (left border `--home`) and acts as the clear.
- **Prose slot** — always rendered, at the same `min-height 124px` (iPad 112px, phone unconstrained), whether or not a region is picked. Unpicked it reads the window as a whole; picked it reads that region and its header gains the delta and the count of its locations below. This is the point: picking a region **swaps words and repaints the field**, it does not insert a panel that shoves tide and locations down the popup. Title 14px 700; body Newsreader 13px / 1.5 `--ink-2`, `max-width 74ch`.

*Full width below:*
- **Topic rows** (only when the night has topics): `border 1px var(--border)` + `border-left 2px` in the topic colour, `background color-mix(in oklch, var(--tc) 6%, transparent)`, `radius 8px`, `padding 7px 11px`, `gap 10px`. Glyph 13px, name 12px 600, a 14px circled `i` whose `title` is the science note, the detail line mono 10.5px `--ink-2` (truncating), and a right-aligned scope note mono 9.5px `--ink-3` — `coastal regions · 4 in scope`.
- **Tide row** (coastal windows only): `grid auto auto 1fr`, `gap 12px`, `border rgba(111,168,176,.28)` + `border-left 2px var(--tide)`, `background rgba(111,168,176,.055)`. Key `≈ TIDE` mono 10px 600 uppercase `#9CCBD1`; a 104×24 SVG sparkline (`--tide` stroke 1.5) with a dashed `--marginal` marker line and 2.4r dot at the window time; then three facts, mono 10.5px, with the significant words in `--ink` 600. Sparkline hidden on phone.
- **Ranked locations** — a horizontal snap strip, cards `flex 0 0 calc((100% - 24px)/3.5)` desktop, `/2.6` iPad, `76%` phone. `border 1px var(--border)`, `radius 8px`, `padding 9px 10px`, `min-height 118px`, hover `translateY(-2px)`. Name 12.5px 600; rating chip in the ramp colour (`bg` at `.17`, ring at `.4`); region and `🚗 1h 18min · 46 mi` mono 10px `--ink-3`; `↰ leave 19:46` mono 10.5px with the time in `#EBD9A8` 600; footer `◉ Four days here →` turning `--tide` on hover. Sorted by rating then drive time, capped at 8.
- **Footer** (`border-top`, `rgba(0,0,0,.22)`, mono 10.5px `--ink-3`): `Ranked by rating, then drive time.` + an active-filter chip (`rgba(201,162,75,.1)`, ring `rgba(201,162,75,.28)`, `#EBD9A8`) + right-aligned `See all 33 →` in `--tide`.

### 3. Location sheet — four days here (`screens/11-location-four-days.png`)

Opens over everything (`z-index 70` vs the window popup's 60), so a chip on the map or a card in the strip opens it without closing the popup underneath. Card `width min(680px, 100% - 36px)`, `top 20px`; full-screen on phone.

Header: back chevron, name 16.5px 700, meta line mono 10.5px `--ink-3` (`region · 22 min from Keswick`, plus an `outside your plan` badge in `--marginal` when the location isn't in scope), `esc` button right. Then a lead block (`linear-gradient(180deg, rgba(201,162,75,.06), transparent)`): kicker `THE NEXT FOUR DAYS HERE · 1 OF 6 WINDOWS AT 4★+` mono 9px uppercase, then Newsreader 14px / 1.55.

Event rows, one per window: `grid 52px 1fr`, `gap 11px`, `padding 8px 11px`, `border 1px var(--border)`, `radius 10px`, `background var(--panel)`. Date box, then `Sunrise 05:42 ◎ BEST` (14px 700 + mono 12.5px 600 + `.tag` mono 9px uppercase `#EBD9A8`), the rating chip right-aligned in its ramp colour, and a caret. Below: `↰ leave 05:00 · 22 min · ◐ 88%` mono 10.5px. The best row gets `border-color rgba(201,162,75,.45)` and a gold gradient wash; rows at 2★ or below get `opacity .62`. Clicking a row expands its prose (Newsreader 12.5px / 1.5). The best window starts expanded.

Footer: `◎ Plan from <region> →` (sets the origin) and `◍ Show on map →`.

### 4. Search (`screens/09-search-open.png`, `10-origin-lake-district.png`)

Triggered by the origin button or `/`. The masthead's tick line is replaced by the input; the dropdown is absolutely positioned under the masthead (`left/right 22px`, phone 12px), `background var(--surface-light)`, `border 1px var(--border-light)`, `radius 0 0 11px 11px` with no top border, `box-shadow 0 26px 60px rgba(0,0,0,.66)`.

Three result kinds, in this order, each under a mono 9px uppercase section title with a lighter hint clause:
- **Windows** — "opens it". Matches day and time-of-day words (`thursday sunset`, `tomorrow dawn`, `fri`, `21`).
- **Regions** — "re-points the plan and the heat". Selecting one **sets the origin**.
- **Locations** — "four-day view". Capped at 5.

Row (`.res`): `grid auto 1fr auto auto`, `gap 11px`, `padding 8px 10px`, `radius 8px`. Glyph (`◇` location, `◎` region in `--home`, `◷` window in `--tide`); name 13.5px 600 with the matched span in `<mark>` (`rgba(201,162,75,.28)` / `#F6E9C8`); sub-line mono 10px `--ink-3` (region, drive time, and `outside your plan` in `--marginal` where relevant); then the best figure (mono 12px 600 over a 10.5px caption); then an action chip in `--tide` — `4 DAYS` / `PLAN FROM HERE` / `PLANNING NOW` / `OPEN WINDOW` (hidden on phone). Selected/hover: `rgba(201,162,75,.1)` with `rgba(201,162,75,.32)` border.

Empty query shows three windows and three recent locations. No match shows `Nothing called "xyz"` plus two suggestions. Footer strip: `↑↓ move · enter open`.

Matching is accent- and punctuation-insensitive, expands `&` to `and`, folds `saint` to `st`, and scores prefix matches highest — `st marys`, `stmarys` and `bait island` (an alias) all find St Mary's Lighthouse.

---

## Interactions & behaviour

**Origin.** The single most important behaviour. `setOrigin(o)` clears the region filter, the open location, and the search, sets reach to `2h 30` at home / `1h 30` away, and re-renders everything. Consequences, all derived rather than special-cased:
- Every heat field refits to the new scope's bounding box (`HeatField.bbox(spots, pad)`), so all seven maps zoom together.
- Drive times switch from `min` (from home) to `lmin` (from the region's local base), which re-ranks every list and recomputes every leave-by.
- Best bet and also-good recompute, and can move to a different window.
- Topics re-filter (see below), so the topic list changes without any per-region table.
- The lens label becomes `Drive from Keswick`; the section head becomes `THE DAYS AHEAD · THE LAKE DISTRICT`; a region lead paragraph appears above the strip.

**Topic eligibility is derived, not declared.** Each topic may name a location property it `needs` — `coast` for a king tide, `lake` for an inversion — and a region qualifies only if it holds a named location with that property. Aurora and NLC carry no requirement because at this latitude the whole planning area sees them or none of it does. So planning from the Lake District drops the tide topic by itself, and the popup row states the scope and how many regions qualify. Ordering is rarest-first: aurora, NLC, king tide, inversion, dust.

**Conflict messages** (above the strip, so they're about the whole plan rather than one window):
- Nothing within the chosen reach → names the count in scope and the closest location, with a `Widen to 1h 30min →` action.
- Rating floor shuts the week out → explains that the floor came from planning at home, names the actual ceiling and where it is, and offers `Show the week as it is →` or `drop the floor to 4★+ →`. Style: `border rgba(224,165,66,.4)`, `background rgba(224,165,66,.07)`, `radius 11px`, title 13.5px 700 `#EFC377`, body mono 11px, actions in `--tide`.

**Region focus in the popup.** Clicking a region card, or the field within 26% of the canvas width of a region's centroid, toggles that region as the focus. The field repaints with every other region faded almost out, the location list filters, the prose swaps, and the footer chip updates. Clicking again (or `All regions`) clears. Nothing moves position.

**Keyboard.** `/` opens search from anywhere. `↑ ↓` move the selection, `Enter` activates, `Esc` closes search → then the location sheet → then the window popup, in that order. `← →` step between windows while a popup is open.

**Confidence.** `CONF = [0.95, 0.88, 0.82, 0.72, 0.65, 0.57]` per window. The kernel desaturates and thins the field as confidence falls, so a day-4 guess cannot look as authoritative as tonight. Shown numerically only in the popup header.

**Transitions.** Card hover `transform .12s` / `border-color .14s` / `background .14s`. Lens stuck shadow `.18s`. Reveal-on-hover footer `opacity .14s`. Nothing else animates; there are no entrance animations.

**Redraw discipline.** Canvas work is measured, so it must run after layout: `drawThumbs()` and `drawBig()` retry on `requestAnimationFrame` (up to 30 frames) while the container measures ≤ 0, and both re-run on debounced `resize` (170ms) and on `document.fonts.ready`. In a component framework, draw in a layout effect after measuring, and re-measure per card — a solo phone card is twice the width of a paired one.

## State management

```
origin    : Region | HOME        // HOME = {all:1} meaning "every region in your area"
win       : number | null        // which window's popup is open
reg       : string | null        // region focus inside the popup
spot      : Location | null      // which location sheet is open
reach     : '45' | '90' | '150' | 'any'
rate      : 'any' | '3' | '4'
searching : boolean
q, sel    : search query and highlighted index
exp       : Set<number>          // expanded rows in the location sheet
```

Everything else is derived. Four memoised selectors — scope region ids, scope locations, filtered pool, window ranking — are invalidated together on every state change; in the prototype that's an explicit cache bust at the top of `render()`, in a modern framework they are plain memos keyed on `(origin, reach, rate)`.

Derived rules worth stating explicitly:
- `verdict(avg)`: `≥ 3.7` → *Worth it*, `≥ 2.8` → *Maybe*, else *Poor*. Card grade uses the best **regional average** in the window, not the peak location.
- The spread histogram and the "best in reach" name are measured over named locations in reach **without** the rating filter — an average of things that already passed a 4★ filter always reads 4-something.
- `leaveBy = windowTime − driveTime − setupTime`.
- `GLANCE = 180` minutes defines "your area": a region is in the field if it's a home region or its nearest named location is within three hours. Beyond that it's reachable only by moving the origin, and it's named in the beyond line. Both tabs frame themselves with this so they cannot disagree about what "your area" means. **The framing rule is drive time, not region count** — adding Scotland and the Peak District doesn't change the layout, it changes what's inside three hours.

## Data shapes

```
Region   { id, n, al[], home?, all?, base, lead? }
Window   { id, lbl?, dow, dn, when, time, lead, tide? {f, path, mx, my} }
Location { n, rid, lat, lng, coast, lake, min, mi, lmin, al[], named, r[6], dark }
Topic    { ic, n, sh, c, w, needs?, scope?, sci }
WTOPICS  { [windowId]: [{ t: topicKey, d: detail }] }
NARR     { [regionId]: { [windowIndex]: prose } }
WHY      { [locationName]: { [windowIndex]: prose } }
CONF[6], DELTA{ [regionId]: [6] }, RUNAGE
```

`r[]` is one rating per window, 1–5. Unnamed locations exist so the heat field has something to interpolate between — roughly 4 per named anchor — and are excluded from every list and every average that a human reads. Prose is per-region and per-location and never restates a number.

## The heat kernel — `heat-field.js`

Port this close to as-is. Public surface:

```
HeatField.load()                          // fetches world-atlas 50m coastline, caches it
HeatField.field(pts, w, h, opts)          // the kernel: bucketed inverse-distance field
HeatField.paint(ctx, w, h, pts, opts)     // paint a field into any 2d context
HeatField.drawGeo(cv, w, h, spots, win, opts)   // host A: canvas + d3 projection, clipped to coastline
HeatField.drawTiles(cv, map, spots, win, opts)  // host B: over a Leaflet basemap
HeatField.bbox(spots, padDeg)             // GeoJSON MultiPoint corners (never a ring)
HeatField.latLngBounds(spots, padDeg)     // [[s,w],[n,e]] for Leaflet fitBounds
HeatField.aspect(fit) / proj(w,h,fit) / centroid(spots, rid, project)
HeatField.ramp(score) / rgb(c, a) / clamp(v, a, b) / fit(cv, w, h) / radiusFor(map, m, lo, hi)
```

`opts`: `{ grid, radius, blur, line, conf, focus, fit }`. Spatial bucketing is what makes 200+ locations viable — do not replace it with a naive per-pixel loop over all points.

Values this design passes in:
- Thumbnails: `{ grid: 4, radius: max(10, cardW × 0.155), blur: 2.4, line: 0.5, conf: CONF[i], fit }`
- Popup field: `{ grid: 6, radius: max(20, boxW × 0.072), blur: 3.6, line: 0.85, focus, conf, fit }`

One gotcha preserved in a comment there: the bounding box must be a corner `MultiPoint`, never a polygon ring — a ring's winding order can be read as the whole globe, which silently fits the projection to the world instead of the area you asked for.

## Design tokens

```
--bg            #181210     page / popup ground
--surface       #221A15     lens bar, headers, active tab
--surface-light #2A2019     dropdown
--panel         #1E1712     cards, inactive tabs
--border        #3A2C23     default hairline
--border-light  #4A3A2E     raised hairline, hover
--line          #3A2C23     grid lines: day rule, calendar tile, empty-cell dash
--ink           #F2E7D3     primary text
--ink-2         rgba(242,231,211,.66)
--ink-3         rgba(242,231,211,.42)
--go            #8AAE72     good / Worth it
--marginal      #E0A542     marginal / Maybe
--poor          #C8452F     poor
--tide          #6FA8B0     tide, links, secondary actions
--home          #C9A24B     origin, selection, "yours"
--coral         #E8593F     brand kicker
```

Text on tinted grounds uses lifted variants of the grade colours, not the tokens themselves: `#A8C795` (good), `#EFC377` (marginal), `#E58C7A` (poor), `#B6D49F` / `#8CA87A` (best bet / also good), `#EBD9A8` (gold-on-dark), `#9CCBD1` (tide-on-dark), `#F9F1E2` (wordmark).

**Score ramp** — the single source of truth for what a rating looks like, interpolated linearly between stops (in `heat-field.js`):

```
1★  rgb(176, 58, 42)
2★  rgb(200, 69, 47)
3★  rgb(224,165, 66)
4★  rgb(176,190,116)
5★  rgb(138,174,114)
```

**Type.** IBM Plex Sans (400/500/600/700) for UI; IBM Plex Mono (400/500/600) for every number, time, label and key — anything tabular or coded; Newsreader (400/500/600 + italic) for prose only (region narrative, location reasoning, lead paragraphs). The wordmark is Newsreader 600.

**Scale.** 8/8.5/9/9.5/10/10.5/11/11.5/12/12.5/13/13.5/14/14.5/15/16/16.5/19/20/23/26 px. Mono labels are 8.5–10px with `.06–.13em` tracking, uppercase. Body prose 12.5–14px.

**Radii.** 2 (light rule) · 4 (kbd chip) · 5 (calendar tile, canvas, chips) · 6–7 (small buttons, date boxes) · 8 (segmented, region cards, spot cards) · 9 (window cards, empty cells, map box, search field) · 10–11 (event rows, message blocks) · 13 (popup) · 999 (badges).

**Spacing.** 2/3/4/5/6/7/8/9/10/11/12/13/14/15/16/18/20/22 px. Grid gaps 6–8. Body gutter 22px desktop and iPad, 14px phone.

**Shadows.** `0 12px 26px rgba(0,0,0,.5)` stuck lens · `0 26px 60px rgba(0,0,0,.66)` dropdown · `0 30px 80px rgba(0,0,0,.72)` popup · device bezels are prototype-only.

**Viewports.** Desktop 1180px wide × 900px tall frame · iPad 834 × 1000 · iPhone 390 × 812. Minimum touch target 34px, 38px on phone.

## Assets

No images and no icon library. Every glyph is either a small inline SVG (home pin, map pin, search) or a text character (`◉ ◍ ◎ ◇ ◷ ≈ ↰ ▲ ▼ ‹ › ✦ 🌊 🌌 ☁️ 🚗`). The emoji are the hot-topic glyphs and the drive marker; if the target platform renders them inconsistently, swap them for the icon set already in the codebase and keep the topic colour.

External dependencies:
- Google Fonts: IBM Plex Sans, IBM Plex Mono, Newsreader.
- `d3@7.9.0` — used for `geoMercator`, `geoPath`, `mean/max/min`, `json`. If the codebase already has a projection library, only the projection and the array helpers are needed.
- `topojson-client@3.1.0` and `world-atlas@2.0.2` `countries-50m.json` — the coastline the thumbnails clip to. Fetched at runtime in the prototype; in production bundle a trimmed GB coastline instead. Nothing renders until this resolves, hence the `loading coastline…` line.

## Files

| File | What it is |
|---|---|
| `Plan Tab with Heat v3.html` | The design. Markup, all CSS, and the design notes under the frame. |
| `plan-tab-v3.js` | Plan tab logic: matrix, cards, popup, location sheet, search, origin. |
| `plan-data.js` | Fixture data — regions, windows, locations, topics, prose, deltas. Replace with the API. |
| `heat-field.js` | **The shared heat kernel. Port close to as-is.** |
| `Map Tab with Heat.html` + `map-tab.js` | The Map tab, included because it's the second host of the same kernel (`drawTiles`) — reference for keeping one kernel, not two. |
| `screens/*.png` | 11 screenshots: desktop, iPad and phone; the matrix; the popup open and with a region picked; search; a region origin; the location sheet. |

## Open questions for the port

1. **Plan vs Map, division of labour.** Proposal to confirm: **Plan is time-first** (which window, ranked by when) and **Map is space-first** (which area, explored freely). Both draw the same field; they differ in what the primary axis is. Worth settling before either grows features, because the temptation is for each to absorb the other.
2. **Marker colours on the Map tab don't match the field.** The field colours a score with the ramp above; `markerUtils.js` still colours markers on a grey→gold monochrome scale. Two systems for one quantity. Flag for a decision — don't unilaterally pick one, as the whole rating palette is under separate review.
3. **The lens could move into the tick line.** It's down to two controls (reach and rating). If those are genuinely the only two, they could sit beside the origin in the masthead and give the plan its whole vertical back. Worth trying, cheap to revert.
4. **`localStorage`.** The prototype stores only the viewport toggle (`photocast.heat.viewport`), which shouldn't ship. If the real app wants to persist anything from this tab, origin and reach are the two the user would notice being forgotten.
5. **Empty-state matrix.** The fixture always has six windows across four days. Confirm the intended rendering for a forecast that starts mid-day (one column with a single window) and for a scope with no locations at all — the current code degrades to dashed "empty by definition" cells, which reads correctly for the first case and is untested for the second.
