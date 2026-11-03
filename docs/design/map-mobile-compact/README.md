# Handoff: phone Map tab — compact chrome

## Overview
On iPhone the Map tab gives the map under half the screen. The masthead wraps to several lines, the origin row and sun times take two rows, the tabs are tall, the Ask bar sits above the peek row, and the attribution wraps to two lines. This handoff gives that space back to the map.

The changes apply **below the mobile breakpoint only** (`hooks/useIsMobile.js`), with one exception: the **unset-postcode nudge colour applies on all platforms** (see §6).

`Map Mobile Compact.html` is an HTML design reference. Rebuild it in the existing React components; don't port the DOM code. The left phone recreates today; the right phone is the design. The buttons above the phones switch the origin state.

## Screenshots
Left phone is today, right is proposed. In `screenshots/`:
1. `01-home-today-vs-proposed.png`: Home origin; map visible goes from about 34% to 55% of the screen
2. `02-away-origin-row.png`: Away origin, one-line row with ⌂
3. `03-unset-red-nudge.png`: no postcode, red nudge
4. `04-menu-open.png`: ⋯ menu
5. `05-ask-open.png`: Ask section open from the peek row
6. `06-attribution-expanded.png`: attribution tapped open

## 1. Masthead (phone)
One row, 34px, then the light rule (2px, `margin-top:6px`).
- Wordmark: Newsreader 20px / 600, with the spine. **No tagline.**
- Sun times, right-aligned: mono 10.5px `#E0A542`, `↑07:10  ↓16:29` (arrows 9px at 85%).
- Status: an 8px dot only (`#8AAE72` with glow; existing status colours for other states). No version text.
- ⋯ menu: 40×40 hit area, 30×30 visible box (`1px #3A2C23`, radius 8). Opens a popover (210px, `#1E1712`, `1px #4A3A2E`, radius 12) with: status line `● UP · v2.36.0`, then **Settings**, **Change home postcode**, **Sign out** (rows ≥44px). The settings cog and Sign out button leave the masthead.
- Desktop and iPad keep the current masthead, including the origin row and sun times.

## 2. Origin row (phone)
- **Home:** hidden. The HOME marker and reach rings on the map show the origin.
- **Away:** one 36px row: ⌂ button (32×28, returns home) + the existing away group (`◉ WHITBY · drive times from here ⌕`), single line, place name truncates.
- **Unset:** one 36px row with the nudge `◉ SET YOUR HOME POSTCODE ›` (colours in §6).

## 3. Tabs (phone)
13px, 7px vertical padding (about 34px tall), `padding-top:6px` on the bar. All four tabs stay; Operations keeps its dashed border.

## 4. Peek row (phone)
The full-width Ask bar above the peek row is removed. Sheet collapsed height returns to **74px**.
Four buttons, `gap:5px; padding:0 8px 10px`:
| Button | Width | Key | Value |
|---|---|---|---|
| Other windows | flex 1 | `OTHER WINDOWS` | `Tmrw AM` (truncates) + verdict mono 9.5px, `flex:none` |
| Tide | flex 1 | `TIDE` | wave + `Mid ↑ · 17 dim`, mono 11px |
| Ask | 44px | `ASK` | ✦ in `#EBD9A8` |
| Layers | 44px | `LAYERS` | ☰ |
Keys 8px mono, .08em; values 12px / 600. Tapping Ask opens the sheet on an Ask section with the input focused and the existing suggestions below it. Other sections and behaviour are unchanged from `design_handoff_map_mobile_sheet/`.

## 5. Attribution (phone)
Single line, 8.5px, `white-space:nowrap; text-overflow:ellipsis; max-width:240px`, sitting just above the collapsed sheet. Tap toggles the full text. Keep the attribution control's z-index **below the sheet** so it never renders over an open sheet. Confirm the truncated form meets Esri's attribution terms before shipping.

## 6. Unset-postcode nudge — ALL platforms
The "Set your home postcode" state of the origin group is red, on desktop, iPad and phone:
- border `1px solid rgba(200,69,47,.75)`
- background `rgba(200,69,47,.12)`
- text and pin `#EE8064` (same as the SUNSET tag; passes AA on the masthead)
- hover: border `rgba(200,69,47,.95)`, text `#F49A84`

This replaces the coral-text-only `.wf-tick-nudge` rule and applies the colour to the group (`.wf-tick-group`), not just the label.

## Verify
1. At 390×844, Home origin: the masthead is one line, there's no origin row, and the sheet is 74px. The map gets about 50% of the screen, up from about 35%.
2. Switch to Away or Unset: a single 36px row appears.
3. The ⋯ menu holds Settings, Change home postcode and Sign out.
4. "WORTH IT" and "17 dim" are never truncated at 390px.
5. With the sheet open, the attribution is hidden behind it.
6. Unset postcode shows the red group on desktop and iPad too.
