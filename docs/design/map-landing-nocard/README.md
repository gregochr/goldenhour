# Handoff: Map tab — remove the landing card, add "is better" to the window pill

## Overview
The Map tab used to open with a "Tonight, or tomorrow?" landing card on top of the map, specified in `design_handoff_map_landing/`. Users close it straight away, and the first test user found the screen overwhelming. This increment **removes the card**. The one thing the card said that the window pill could not was the comparison: *the next window is better than this one*. That comparison moves onto the pill as one short, tappable line.

Everything else in `design_handoff_map_landing/` stays as specified: the verdict, region label, medallion, stepper ticks, picks, Best bet chip, panels and filter rules.

## About the design files
The files in this bundle are **design references created in HTML**: a working Leaflet prototype that shows the intended look and behaviour. It is not production code. Recreate the change in the existing React codebase using its patterns (`react-leaflet`, `components/MapView.jsx`, etc.). Do not port the imperative DOM code.

## Fidelity
High-fidelity. Colours, type and copy are final.

## What to remove
1. **The landing card component** and everything it renders: the header ("Tonight, or tomorrow?" / "Tomorrow — sunrise or sunset?"), the subhead, both rows, the "elsewhere" pick lines, the all-Poor "Neither is worth the drive. Next up: …" branch, the footer line and the ✕.
2. **Its state**: the `land` / `landFrom` flags, the dismiss handler, and the Escape listener that only served the card. Keep Escape for the other panels.
3. **The reopen row** at the top of the window pill's week menu (`◎ Tonight, or tomorrow? ›`). The menu now starts directly with the window rows and still ends with `▤ This window, region by region ›`.
4. **Open question OPEN 3** in the earlier handoff (how often the card appears). It no longer applies.
5. On phone, `design_handoff_map_mobile_sheet/` said the card no longer auto-opens and its content becomes the sheet's Windows section. The sheet's Windows section is **unchanged**. Only the "card" reference goes away.

## What to add: the "is better" line on the pill

### When it shows
All of these must be true:
- The current window is the **first solar window** in the event list (the window the map opens on).
- There is a second solar window after it.
- The second window's verdict tier is **strictly higher** than the first's. Tier order: Poor (0) < Maybe (1) < Worth it (2). Use the same verdict source as the pill (API regional verdict, with the field fallback at ≥ 3.7 / ≥ 2.8).
- The viewport is not phone width (see Responsive).

Otherwise nothing renders. If both windows are in the same tier there is no line, and on any other window there is no line either.

Night events (astro, aurora) are never the first or second window for this rule, because only solar windows count.

### Copy
`{day label} {event name, lowercase} is better ›`

Examples: `Tomorrow sunrise is better ›`, `Tonight sunset is better ›` (if the first solar window is this morning's sunrise). The day label uses the same formatter as the pill (`evLabel`).

### Placement and style
Inside the window pill (`#wnow`), after the verdict + region block and before the `▾` caret:
- Font: the pill's mono family, 10px, letter-spacing .02em, `white-space: nowrap`.
- Colour: `#A8C795` (the Worth it ink). On hover: `#CFE3C2` with an underline.
- Separator: `border-left: 1px solid #3A2C23`, `padding-left: 8px`, `margin-left: 2px`.
- Cursor: pointer. Give it `role="button"` and an accessible name. It is inside a button, so in React render it as a separate sibling button inside the pill's flex container, not a nested `<button>`.

### Behaviour
- Tapping the line selects that second window. Do the same as choosing it from the week menu: heat, labels, verdict and steppers all update. It must not also open the pill's week menu (stop propagation).
- After the switch the current window is no longer the first, so the line disappears. The ‹ stepper tick shows the previous window's colour as before.
- Filters do not affect the line (same rule as the verdict). Scope (`My area` / `Whole catalogue`) does.

### Responsive
- Desktop and iPad: shown.
- iPhone width: hidden (`display:none`). The pill has no room, and the stepper's verdict ticks still show the next window's tier. Do not let the line push the control group onto two rows at any size (check 4 in the earlier handoff still applies).

## State
No new state. The line is derived from: current window index, the solar event list, and the per-window verdict tier for the current scope.

## Design tokens used
- `#A8C795` Worth it ink, `#CFE3C2` hover
- `#3A2C23` border
- Pill mono family and sizes as already specified in `design_handoff_map_landing/`

## Verify by measurement
Report numbers, not screenshots.
1. Cold open with tonight Maybe and tomorrow sunrise Worth it: exactly one "is better" element in the pill, with text `Tomorrow sunrise is better ›`.
2. Cold open with both windows in the same tier: zero such elements.
3. Tap the line: selected window index = second solar window; week menu not open; element count now zero.
4. Step to any non-first window: zero elements.
5. Apply 4★+ / Seascape filters: element presence unchanged from step 1.
6. iPhone width: element not displayed; control group is still one row.
7. No landing card element exists in the DOM at load, after Escape, or after opening and closing the week menu.

## Files
- `Map Landing v2.html` + `map-tab-v4-nocard.js`: the prototype. See `betterNext()` and `paintWin()` in the JS for the rule. `S.demo` and the "Walk it" buttons are demo-only: they demote tonight to Maybe because the mock forecast never produces the state. **Do not port them.**
- `heat-field.js`, `plan-data.js`: unchanged kernel and mock catalogue, so the prototype runs.
