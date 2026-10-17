# Handoff: Map tab on phone — peek sheet (Option A)

## Overview
On an iPhone the Map tab currently opens with the "Tonight, or tomorrow?" card, the tide strip and the scored-locations toast all stacked on the map, covering almost all of it. The user closes them one by one before they can see the map.

This design moves every map panel into **one bottom sheet that starts collapsed**. When collapsed it shows three summary buttons (Other windows · Tide · Layers). Tapping one opens the sheet on that section. The map fills the rest of the screen.

It applies **only below the mobile breakpoint** (`hooks/useIsMobile.js`). Tablet and desktop are unchanged.

## About the design files
`Map Mobile Minimised.html` is a **design reference built in HTML**. It's a working Leaflet prototype that shows the intended look and behaviour; it is not production code. Rebuild it in this repo's React environment using the existing patterns (`components/MapView.jsx`, `components/BottomSheet.jsx`, `components/TideIndicator.jsx`, `react-leaflet`, `hooks/useIsMobile.js`). Don't port the imperative DOM code.

The file shows two options side by side. **Option A (left phone, `#pa`, `variant==='a'`) is the chosen one.** Ignore Option B.

Its spots, scores and tide extremes are mock data. Use the real forecast and tide data already feeding the Map tab.

## Fidelity
**High-fidelity.** Colours, type, spacing and copy are final. The masthead, tabs and window pill already exist in the app and should stay as they are. The new parts are the **peek sheet**, the **tide visibility rule**, the **Tide mode setting** and the **auto-fading toast**.

## Relationship to earlier handoffs
- `design_handoff_tide_window/` says to put the phone tide strip "full-width above `#gnav`", with "no toggle, no mode". **On phone, this handoff replaces both points:** the strip content moves into the sheet's Tide section, and a Tide mode setting (Auto / Always / Off) is added. The tide model, fit tiers, chip glyphs, dimming and next-fit logic are unchanged. Reuse them.
- `design_handoff_map_landing/`: the "Tonight, or tomorrow?" landing card no longer auto-opens on phone. Its content becomes the sheet's Windows section.
- Desktop and tablet keep the existing behaviour.

---

## Screens / states
Screenshots are in `screenshots/`:

1. `01-closed-default.png` — first load. Sheet collapsed, nothing else open.
2. `02-windows-open.png` — Windows section open.
3. `03-tide-open.png` — Tide section open.
4. `04-layers-open.png` — Layers section open.
5. `05-poor-window-tide-hidden.png` — selected window is Poor, so the Tide button is removed.
6. `06-inland-tide-hidden.png` — no coastal spot in view, so the Tide button is removed.

### Frame (iPhone, 390 × 844 reference)
Top to bottom: status bar → masthead (existing) → tabs (existing) → **map area** (flex:1) → Safari chrome. Inside the map area:
- **Window pill** (existing): absolute, `top:10px; left:10px; right:10px`, height 44. Its ‹ › step buttons are 44×44.
- **Toast** "★ PhotoCast-scored locations shown": absolute, `top:62px`, centred. **Fades out after 3000 ms** (opacity transition .5s, then `pointer-events:none`).
- **Peek sheet**: absolute, `left:0; right:0; bottom:0`.

About 440 px of map stays visible with the sheet collapsed, against roughly 0 today.

### Peek sheet
- Container: `background: rgba(22,17,13,.97)`, `border-top: 1px solid #4A3A2E`, `border-radius: 16px 16px 0 0`, `box-shadow: 0 -10px 30px rgba(0,0,0,.5)`, flex column.
- **Height: 74px collapsed, 356px open.** Transition `height .26s cubic-bezier(.3,.7,.2,1)`. (With a native drag sheet, use the same two detents.)
- Handle: 14px row with a centred 36×4 bar, radius 2, `#4A3A2E`.
- **Peek row**: `display:flex; gap:6px; padding:0 10px 10px`. It holds three buttons, each 46px tall, `border:1px solid #3A2C23`, radius 10, `background:#1E1712`, and a column layout with two lines:
  - line 1, key: IBM Plex Mono 8.5px, letter-spacing .1em, uppercase, `rgba(242,231,211,.5)`
  - line 2, value: IBM Plex Sans 12.5px / 600, a single line that truncates with ellipsis

| Button | Flex | Key | Value |
|---|---|---|---|
| Other windows | 1 | `OTHER WINDOWS` (becomes `CLOSE` while this section is open) | First non-Poor window other than the selected one, as `{day} AM` or `{day} PM` + its verdict in mono 10px, coloured by verdict. The verdict never truncates (`flex:none`); the day text truncates. |
| Tide | 1 | `TIDE AT THIS LIGHT` | wave glyph + `{High\|Mid\|Low}{ ↑\|↓ when Mid}{ · N dim when N>0}` in mono 11.5px `#8FC0C7`. Uses a tide-tinted style: border `rgba(111,168,176,.4)`, bg `rgba(111,168,176,.08)`. |
| Layers | 0 0 58px, centred | `LAYERS` | ☰ |

- **Active button** (its section is open): border `rgba(201,162,75,.6)`, bg `rgba(201,162,75,.1)`. Active Tide button: border `rgba(111,168,176,.8)`, bg `rgba(111,168,176,.16)`.
- **Tide button appearing:** when it goes from hidden to shown, play one pulse: `box-shadow 0 0 0 0 rgba(111,168,176,.7) → 0 0 0 10px transparent` over 1.2s. Don't play it on first load.
- **Body** (visible only when open): `border-top:1px solid #3A2C23; padding:4px 14px 14px; overflow:auto`. Only one section is shown at a time.

### Section: Windows
- Heading: "Tonight, or tomorrow?" in IBM Plex Sans 16px / 700, letter-spacing -.01em.
- List of upcoming windows (the same data as the pill and the existing landing card). Each row is at least 48px tall, with a 1px `#3A2C23` divider between rows, containing: event tag (SUNSET or SUNRISE, the existing styles), day in 13.5px / 600, time in mono 11px at 50% ink, and the verdict right-aligned in mono 10.5px / 600, letter-spacing .1em.
- Selected row: bg `rgba(201,162,75,.09)`, `inset 3px 0 0 #C9A24B`.
- Tapping a row selects that window. The pill updates and the sheet **stays open**, so the user can compare windows against the map.

### Section: Tide
This is the existing tide strip content, laid out vertically:
- Key `TIDE AT THIS LIGHT` (mono 9px, .12em, uppercase, 50% ink).
- Phase line: `{PHASE} TIDE, {FALLING|RISING}` in mono 13px / 600 `#8FC0C7`, then `{height} m · {event time}` in mono 10.5px at 70% ink.
- Chart: full width, about 92px tall. Band `rgba(111,168,176,.05)`; dashed HIGH, MID and LOW lines at `rgba(242,231,211,.12)`; curve `#6FA8B0` 2px (with `vector-effect:non-scaling-stroke`); HW and LW labels at the extremes; a vertical line at the event time in `#E0A542` 1px; a light dot `#8FC0C7` with r 4.5 and a 1.5px `#161310` stroke; x-axis labels 00, 06, 12, 18, 24.
- Dimmed line (mono 11px): "**N coastal spots** are dimmed; they want high water." When N = 0: "No coastal spots in view are held back by the tide."
- Next-fit link (mono 11px, `#6FA8B0`, at least 32px tall): "Next high water on the light · {window} ›". Tapping it selects that window (existing next-fit logic).

### Section: Layers
Rows use `justify-content:space-between`, with the label in mono 10px, .1em, uppercase, 70% ink:
- **Show:** segmented Heat | Pins (170px wide, 36px tall).
- **Regions:** the existing Regions picker ("All regions ▾").
- **Tide:** segmented **Auto | Always | Off** (220px wide). Default Auto.
- Hint, mono 9.5px at 50% ink: "Auto: shown when the light is Maybe or better and the coast is in view."
- Legend: "Poor [ramp] Worth it" (the existing ramp).

Segmented control: border `1px #4A3A2E`, radius 8. Selected segment: bg `rgba(201,162,75,.16)`, text `#EBD9A8` / 600.

The Regions, Heat/Pins and Filters controls that used to sit at the bottom of the map on phone move into this section. Nothing else floats on the map.

---

## Interactions & behaviour
1. **Nothing opens on load.** The sheet starts collapsed, the landing card does not auto-open, and the toast fades after 3 s.
2. **Tapping a peek button** opens the sheet on that section. Tapping the active button again closes it. Tapping a different button switches section without closing.
3. **Only one section is open at a time.**
4. **Any map interaction closes the sheet**: `mousedown`, `touchstart`, `dragstart` or `zoomstart` on the map. Taps inside the sheet do not close it.
5. **The ‹ › pill buttons** step the window without opening anything. Tapping the pill body toggles the Windows section.
6. **Tide visibility (Auto mode):** the Tide button and all tide cues (chip wave glyphs, coastal dimming) show only when **both** of these are true:
   - the selected window's verdict is **Maybe or Worth it** (not Poor), and
   - at least one coastal rated location is inside `map.getBounds().pad(0.12)`.

   This is recomputed on `moveend` and whenever the window changes. If the Tide section is open when tide becomes hidden, close the sheet. The rule also requires a solar event (sunrise or sunset), as in the earlier tide handoff.
7. **Tide mode:** Always ignores both conditions. Off hides every tide cue, including chip glyphs and dimming.
8. **Counts are in-view counts.** "N dim" and "N coastal spots" include only dimmed coastal spots inside the current bounds.

## State
```
selectedWindowIndex : number              // existing, shared with the pill
sheetSection        : null | 'win' | 'tide' | 'lay'   // null = collapsed
tideMode            : 'auto' | 'always' | 'off'       // persisted per user (settings or localStorage)
heatMode            : boolean                         // existing Heat/Pins
coastalInView       : number              // derived on moveend
dimmedInView        : number              // derived: coastal, in view, tide misfit
tideVisible = tideMode==='always' || (tideMode==='auto' && verdict!=='poor' && coastalInView>0 && isSolar)
toastVisible        : boolean             // true on mount, false after 3000ms
prevTideVisible     : boolean             // used to trigger the one-shot pulse
```
No new API data is needed.

## Design tokens (all already in the app)
- bg `#181210`, surface `#221A15`, panel `#1E1712`, border `#3A2C23`, border-light `#4A3A2E`
- ink `#F2E7D3`; ink-2 `rgba(242,231,211,.7)`; ink-3 `rgba(242,231,211,.5)` (inside the map frame, use ≥ .66 on opaque panels for AA)
- go `#8AAE72`, marginal `#E0A542`, poor `#C8452F` (verdict text `#E0735E`), tide `#6FA8B0` / `#8FC0C7`, home `#C9A24B`, coral `#E8593F`, dawn `#8FA8C4`
- Fonts: IBM Plex Sans (UI), IBM Plex Mono (data and labels), Newsreader (wordmark only)
- Radii: 16 (sheet top), 10 (peek buttons, pill), 8 (segmented)
- Hit targets ≥ 44px, except in-list links, which are ≥ 32px

## Assets
No new assets. The wave glyph is the existing tide glyph (a 16×9 SVG path in `currentColor`).

## Files
- `Map Mobile Minimised.html` — the prototype. Option A is `makePhone('pa','a')`; see the `variant==='a'` branches and the `.sh` / `.pk` / `.pb` / `.pane` CSS.
- `screenshots/` — the six states listed above.

## Verify
1. At a 390×844 viewport on first load, the sheet is 74px tall and no other panel is open.
2. With a coastal view and a Worth it window, the Tide button shows. Step to a Poor window and it disappears; pan inland and it disappears.
3. Tide Off removes the chip glyphs and dimming. Always keeps them on a Poor window.
4. Dragging the map with the sheet open collapses it.
5. The "Other windows" value never cuts the verdict word.
6. Desktop and tablet are unchanged.
