# Handoff: Big seas and storm stand-down on the Map tab

## Overview

Two additions to the Map tab, both prompted by a storm on Saturday 24 Oct that the app never showed.

1. **Big seas.** This answers one question: *should I go to the coast to see waves breaking over the piers and lighthouses?* It works at any time of day. When high water lines up with sunrise or sunset, that's the window to drive for.
2. **Stood down, drawn.** Stood-down locations have no light score, so today they draw no heat, and a region the storm has ruled out looks the same as an empty one. They now get a hatch with the region and the cause named on it.

The Met Office warning line from an earlier iteration was **dropped**. Big seas answers the question this feature is for.

## About the design files

`Map Storm Stand-down.html` is a **design reference built in HTML**. It's a prototype of the intended look and behaviour, not production code. Rebuild it in the PhotoCast frontend using its existing Map tab components, heat field and tokens. The prototype is built on `map-tide-v5.js`, a copy of the Map tab prototype, with the changes marked below.

All sea, wave and high-water figures in the mock are **invented** for the scenario. The masthead light times are left over from the summer data and should be ignored.

## Fidelity

**High fidelity** for the two new elements: the big-seas chip and line, and the stand-down hatch and label. Everything else on the screen is the existing Map tab and is unchanged.

---

## Backend

### What exists

- `MarineClient` (MarineClient.java:29) fetches 7-day hourly `wave_height`, `swell_wave_height` and `wave_direction` from Open-Meteo Marine.
- `MarineWaveRefreshService` runs once per briefing cycle (BriefingService.java:511), one call per coastal location, and writes to `marine_wave` (V123).
- Tide extremes come from WorldTides `/api/v3?extremes` and are stored as high and low water with time and height. Big seas only needs high-water time and height, which are stored values. No interpolation is involved.

### Change 1: fetch period

Add `wave_period` and `swell_wave_period` to `HOURLY_PARAMS`. Period is what separates long-period swell, which breaks over a pier, from short wind chop.

### Change 2: sample at high water

Today the hourly series is sampled once at sunrise and once at sunset (MarineWaveRefreshService.java:83-86), and the rest is discarded.

- **Keep** the sunrise and sunset samples. The king, spring and storm-surge pills, `StormSurgeFactsBuilder`, `TideRunBuilder` and the facts lines read them.
- **Add**, for each **daylight** high water in `tide_extreme` within the 7-day horizon: the **peak** `wave_height` in a ±90-minute window around high water, with `wave_direction`, `wave_period`, `swell_wave_height` and `swell_wave_period` at that peak hour. Key the rows by `(location_id, hw_time)`.
- Daylight means high water between civil dawn and civil dusk. A night high water whose ±90-minute window reaches into daylight should be included, and the window clipped to daylight.
- **Fix the stale V123 comment.** It says rows are sampled "at the aligned high-water time", but the code samples at sunrise and sunset.

### Change 3: location fields (structure locations only)

| field | type | meaning |
|---|---|---|
| `structure` | `PIER \| BREAKWATER \| LIGHTHOUSE \| null` | null = not a big-seas location |
| `faces` | int, 0–359 | bearing the structure faces out to sea |

Mock set, for seeding:

| location | structure | faces |
|---|---|---|
| Roker Pier & Lighthouse | PIER | 45 (NE) |
| St Mary's Lighthouse | LIGHTHOUSE | 90 (E) |
| Amble (harbour piers) | PIER | 45 (NE) |
| Saltburn Pier | PIER | 0 (N) |
| Whitby (piers below the Abbey) | PIER | 0 (N) |
| Eyemouth | BREAKWATER | 0 (N) |

Whitby is currently the "Whitby Abbey" location. The chip labels it "Whitby piers". Decide whether to add a separate location.

### Licence

Open-Meteo's free API is for non-commercial use. That's fine while you're the only user. Look at it again before charging anyone; it covers the marine calls already in production too.

---

## The rule

A structure location has **big seas** at a high water when all of these hold:

| test | default | notes |
|---|---|---|
| peak `wave_height` in HW ±90 min | ≥ 3.0 m | placeholder; tune per coast over a winter |
| angle between `wave_direction` and `faces` | ≤ 60° | `wave_direction` is where the waves come *from*; compare it with `faces` directly |
| `wave_period` at peak | ≥ 8 s | screens out wind chop |
| high water in daylight | required | see the daylight rule above |

It's **aligned** when the HW ±90-minute window contains sunrise or sunset. Report the gap as minutes between high water and the solar event, with "before" or "after": `49 min before sunset`.

The **window** shown to the user is HW ±90 min, clipped to daylight, as `15:30–18:15`.

A **day-level summary** (for the line under the window control): per local day, take the qualifying high waters across all structure locations. Report the earliest from, the latest to, the high-water time of the best structure, the max wave height, the dominant direction, the structure count, and whether any is aligned (and with which solar event).

Thresholds are placeholders. Log every evaluation so they can be tuned against real nights.

### Stood down

There's no new data for this. Stand-down already exists per slot (`STANDDOWN` verdict, `standdownReason`). The frontend draws it. The label text comes from `standdownReason`, mapped to a short cause word (the mock shows `storm`).

---

## Screens

All on the **Map tab**. Screenshots are in `screenshots/`, desktop frame at 994 × 880 and iPhone at 390 wide.

### 01 · Thursday: heads-up — `01-thursday-heads-up.png`

The current window has no big seas, but a later one in the horizon does. A single compact line sits under the window control:

- badge `⚡ BIG SEAS`
- line 1: `Saturday 15:30–18:15 · high water near sunset`
- line 2: `Waves 4.1 m from NNE onto 6 piers and lighthouses`
- action: `See Saturday ›`, which jumps the window control to the **aligned** event of that day (Saturday sunset)

No chips are drawn on the map on days without big seas.

### 02 · Saturday sunrise: on the day, not aligned — `02-saturday-sunrise.png`

- The line shows the full form: `15:30–18:15 · high water 16:58` / `Waves 4.1 m from NNE onto 6 piers and lighthouses · watch from high ground, not the pier`, with the action `On the sunset ›`.
- Chips sit on each qualifying structure, without the ring.
- Three regions are hatched as stood down (Pennines, Moors, Dales). Northumberland is still rated and shows heat.

### 03 · Saturday sunset: aligned — `03-saturday-sunset-aligned.png`

- The line's first row adds the gap: `15:30–18:15 · high water 16:58, 49 min before sunset`. There's no action, because you're already there.
- Chips are **ringed**.
- Every northern region is stood down: hatch plus `REGION / STOOD DOWN · STORM` labels. Only the Peak District, which is within scope, keeps heat, so the pill reads `Maybe · the Peak`. When every region in scope is stood down, the pill reads `Stood down`.
- Footer: `… · 181 stood down · storm`.
- In the week menu, the aligned event's row gets a `⚡` in the topics column.

### 04 · Sunday sunrise: second window — `04-sunday-sunrise.png`

- Spring tide, seas easing: `06:49–09:00 · high water 07:31, 42 min after sunrise`, 4 structures, ringed.

### 05 · iPhone — `05-iphone-saturday-sunset.png`

- The line goes full width under the window control and wraps to as many lines as it needs. The badge doesn't wrap.

---

## Components

### Big-seas chip (`.bsc`)

Placed at the structure's coordinates through the existing label collision pass, **before** the location chips so it wins space. On days with big seas, the ordinary location chip for that structure is suppressed so it doesn't appear twice.

- Container: `display:flex; gap:5px; padding:2px 6px 2px 4px; border-radius:5px; background:rgba(14,22,24,.92); box-shadow: inset 0 0 0 1px rgba(111,168,176,.7), 0 2px 8px rgba(0,0,0,.55)`
- Font: IBM Plex Mono 9.5px (10px on phone)
- Content: `⚡` (10px) · name in 600 weight, `--ink #F2E7D3` · height `4.3 m` in 600 weight, `#A9D6DC`, with a left rule `1px rgba(111,168,176,.45)` and 5px padding
- **Aligned**: `box-shadow: inset 0 0 0 1.5px #8FC0C7, 0 0 0 3px rgba(111,168,176,.18), 0 2px 10px rgba(0,0,0,.6)`
- Hover tooltip (the existing `#tip`): name / `Big seas · 15:30–18:15` / `High water 16:58 · 49 min before sunset` (when aligned) / `Waves 4.3 m from NNE, 11 s · faces NE, onshore` / `region · drive`
- Hidden at zoom ≥ 13
- Shown on solar events only. Night events (astro, aurora) don't show chips.

### Big-seas line (`#warn` in the mock)

Sits inside the window-control group, on its own row under the pill (`width:100%; max-width:580px`, no max on phone).

- Container: `display:flex; align-items:center; gap:9px; background:rgba(24,18,15,.94); border:1px solid rgba(111,168,176,.5); border-radius:9px; padding:6px 10px; min-height:36px; backdrop-filter:blur(7px)`. IBM Plex Mono 10.5px.
- Badge: `⚡ BIG SEAS`, 9px, 700 weight, letter-spacing .11em, uppercase, `color:#12100E; background:#8FC0C7; border-radius:4px; padding:3px 6px; white-space:nowrap`
- Line 1: 600 weight, `--ink`
- Line 2: 9.5px, `--ink-2 rgba(242,231,211,.66)`, `text-wrap:pretty`
- Action: 10px, `--tide #6FA8B0`, hover `#8fc4cc`, no border
- **Visibility:** show the full form on a day with big seas. Show the compact form when a later day in the horizon has big seas. Otherwise hide. On a night event of a big-seas day, show the full form.

### Stand-down hatch (canvas, drawn with the heat field)

- For the current event, take every location whose slot is STANDDOWN. Build a union of discs, radius = `HeatField.radiusFor(map, 6500 m, 22, 150)`, clipped to the land path like the heat.
- Fill 1: `rgba(16,15,19,.66)`. Fill 2: an 8 × 8 repeating pattern of 1.2px diagonal lines (45°) in `rgba(184,197,220,.5)`.
- Draw it after the heat and before the coast stroke. It shows in Heat and Pins views.
- Stood-down locations are removed from the heat pool and from the region verdict mean. A region with every location stood down has `sd:true`, `mean:0`.
- The cool grey is deliberately **off** the red–amber–green ramp, so it can't be read as a score.

### Stand-down label (`.sdl`)

At the projected centroid of the region's stood-down locations, through the collision pass, and only at zoom < 11.2.

- Line 1: region short name, IBM Plex Mono 9.5px, 600 weight, letter-spacing .09em, `#D3DAE8`
- Line 2: `STOOD DOWN · STORM`, 8.5px, uppercase, letter-spacing .09em, `#B8C5DC`
- Text shadow: `0 1px 3px rgba(0,0,0,.95), 0 0 9px rgba(0,0,0,.85)`

### Legend additions

Two keys under the existing ramp:
- **Big seas**: `⚡` — "waves onto a pier or lighthouse at high water · ringed when high water is on the light"
- **Stood down**: a 20 × 9 hatch swatch — "ruled out by the weather, cause named on the ground"

### Pill and footer

- Pill verdict: `Stood down` (tier poor) when every region in scope has `sd:true`.
- Footer count adds ` · N stood down · storm` when N > 0, in `#C9D2E4`.

---

## Tokens used

| token | value |
|---|---|
| `--tide` | `#6FA8B0` (coastal teal) |
| tide light | `#8FC0C7`, `#A9D6DC` |
| stand-down ink | `#D3DAE8`, `#B8C5DC`, `#C9D2E4` |
| stand-down hatch | `rgba(184,197,220,.5)` on `rgba(16,15,19,.66)` |
| `--ink` / `--ink-2` | `#F2E7D3` / `rgba(242,231,211,.66)` |
| fonts | IBM Plex Mono (all new UI), existing Plex Sans / Newsreader elsewhere |

Teal and not amber: big seas is a coastal topic, and amber already means Maybe on the field. The existing `STORM_SURGE` accent `#f59e0b` should not be used on the map.

## Files

- `Map Storm Stand-down.html`: the prototype. Inline `window.STORM` holds the mocked scenario (stand-down regions per window, the two big-seas days).
- `map-storm.js`: the Map tab prototype with the additions: `sdRids`, `isSd`, `drawStorm`, `drawSeas`, `paintWarn` (the big-seas line), plus the `pool`, `regionsFor` and `evVerd` changes.
- `heat-field.js`, `plan-data.js`: shared prototype dependencies, unchanged.
- `screenshots/`: the five states above.

## Open

- Thresholds (3.0 m, 60°, 8 s, ±90 min) need tuning against real winter data.
- Whitby: use the Abbey location, or add a piers location.
- The surge contribution (observed minus predicted water level) isn't in the rule. Add it later if the storm-surge pill's figure proves reliable.
