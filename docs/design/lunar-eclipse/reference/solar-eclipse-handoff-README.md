# Handoff: ECLIPSE — a new almanac event type

## Overview

PhotoCast raises **hot topics** from two different sources, and only one of them is a forecast. Aurora, Saharan dust, cloud inversion and snow come off model runs with a 0–3 day horizon. Spring tides, king tides, supermoons, meteor showers and equinoxes come off an **ephemeris** — orbital mechanics, exact and knowable years ahead. The Plan screen already names this split (`ALMANAC` vs `FORECAST` in the topic source field, and the kind tags in the previous version's Coming up rail).

This handoff adds a fourteenth topic type, **`ECLIPSE`**, to the almanac side. It is prompted by the deep partial solar eclipse over the UK on **Wednesday 12 August 2026** — around 90% coverage nationally, the closest to total the British Isles has seen since 1999, and nothing comparable again until 2081.

An eclipse is the purest almanac event there is: NASA publishes contact times to the second for every eclipse through 3000 AD. Nothing about it needs a model run. The only uncertainty is cloud, which the app already forecasts separately.

**Nothing new was invented for this.** The design reuses the v2 promoted strip, the v2 `.ph .rare` rarity label, and the existing enriched hot-topic pill. Two things are genuinely new and are flagged as such: one CSS custom property (`--ecl`), and one piece of backend work (west-horizon site scoring).

Target: solo-operator app in pilot. Priority is comprehensibility and reproducibility over cleverness.

## About the Design Files

The files in this bundle are **design references created in HTML** — prototypes showing intended look and behaviour, not production code to copy. The task is to **recreate these designs in the target codebase's existing environment** (React + Vite: `App.jsx`, `components/`, `hooks/`, `context/`, `utils/`, `api/`) using its established patterns, state management and styling approach.

Read the HTML for structure and exact values, then build real components.

## Fidelity

**High-fidelity.** Colours, type, spacing, radii and copy are final and should be matched. The palette is the existing PhotoCast dark warm-neutral skin (see Design Tokens).

Strip before building: the explanatory prose at the top of `Eclipse Topic v2.html`, the `.bar` section dividers, the `.cap` italic captions, the `.spec` definition list, and the `.foot` block. All are annotation, not product.

## Files in this bundle

- **`Eclipse Topic v2.html`** — the spec. Shows the eclipse promoted strip at the top of Plan, and the enriched hot-topic pill. This is the source of truth for copy, figures and colour.
- **`Plan Window First v2.html`** — the Plan redesign this sits inside, included for context. **Not** part of this task; it is the surrounding design the eclipse must match. Its own handoff is `design_handoff_window_first/`.

Source design files live in the project at `design/`.

---

## Dependency

This work **sits on top of the window-first v2 Plan redesign**. The promoted strip (`.promo`) is a v2 component. If v2 has not shipped, either build the promoted strip as part of this task or fall back to the previous version's Coming up rail (`design_handoff_window_first/`), where the eclipse becomes an `ALMANAC` row like any other. The strip is the better home and the design assumes it.

---

## 1. Promoted strip — eclipse variant

**Purpose** — the single most consequential thing happening, at the top of the Plan pane, with enough figures to decide whether to drive.

**Structure** — identical to the existing `snowp` and `tidep` strips. Four parts:

**`.promo`** — `border:1px solid rgba(196,120,127,.4)`, `border-left:3px solid var(--ecl)`, `border-radius:11px`, background `linear-gradient(180deg, rgba(196,120,127,.07), transparent 70%), var(--surface)`, `overflow:hidden`.

**`.ph`** (header) — `display:flex; align-items:center; gap:10px; padding:11px 14px 8px; flex-wrap:wrap`
- `.k` kicker — `◐ Eclipse × clear west`. Mono 10px/600, `letter-spacing:.11em`, uppercase, colour `#D79AA0` (the strip accent lightened, exactly as `snowp` lightens `--snow` to `#C6D8E3`).
- `.t` headline — `94% of the sun goes, 11° above a clear western horizon`. 14.5px/700, `letter-spacing:-.01em`. A sentence, not a label.
- `.rule` — flex spacer, 1px `--border`.
- `.rare` — `nothing comparable from the UK until 2081 · 1h 49 of it`. Mono 10px `--ink-3`, with `2081` in `--ink` inline. **Reuses the existing class unchanged** — same treatment as the snow strip's "first coincidence since 2 Mar".

**`.pb.drive`** (figure row) — the existing four-column variant, `grid-template-columns:auto auto auto 1fr; gap:20px; padding:0 14px 11px`. Three `.fig` then one `.why`:

| | label | value | sub |
|---|---|---|---|
| 1 | `coverage` | **94%** | `88–96% by region` |
| 2 | `maximum` | **19:13** | `sun 11° up, WNW` |
| 3 | `clear to the west` | **31 of 57** | `sites evaluated` |

`.why` — serif italic 12.5px, right-aligned, max-width 250px: *low enough that a foreground finally fits in the frame*

**`.pfoot`** — `padding:8px 14px`, top border, `rgba(0,0,0,.18)`, mono 10.5px `--ink-3`. Three items:
1. `.warn` — `⚠ Certified solar filter on the lens — not only over your eye`, colour `#D79AA0`. **New modifier class.** See Safety below.
2. Location list — `Bamburgh · Dunstanburgh · Whitley Bay`
3. `.go` — `Plan the drive →`, `margin-left:auto`, `--tide`

## 2. Hot-topic pill

The enriched two-row pattern from `design_handoff_hot_topics/`, unchanged. Accent `--ecl` via the existing `--ac` custom property.

**Row 1** — `◐` glyph · `Deep partial eclipse` · `(i)` · `↓ Today · 19:13` · `— 94% covered, lowest sun of any UK eclipse this century` · `◍ all regions` · `▸`

**Row 2** — three facts and a note:
- `max` **94%** covered · sun `↖ WNW`, only **11°** up
- `contacts` **18:17 → 19:13 → 20:06**
- `.opt` `☀` sets **20:56**, 50 min after last contact
- `.fact-note` — *a clear low western horizon matters more than the last 2%*

**Tooltip** (`(i)` hover) — heading `Why 94% still isn't dusk`, body verbatim from the HTML. Explains that brightness falls with uncovered area, not perceived darkness; at maximum the remaining sliver still throws roughly a twentieth of full sunlight.

Glyph is `◐` text, not emoji, matching v2's text-glyph kickers and tabs. `HOT_TOPIC_STYLES` currently stores emoji — either add a `glyph` field or accept the emoji `🌘` if that catalog shape is load-bearing elsewhere.

The filter warning lives on the promoted strip only. Do not repeat it on the pill.

---

## Design Tokens

Existing palette unchanged. **One addition:**

```
--ecl: #C4787F;
```

Chosen to sit with the *topic* hues (`--tide:#6FA8B0`, `--nlc:#9B8FD4`, `--blue:#7C8DD6`, `--snow:#B7CBD8`) at matching lightness and chroma — not with the *semantic* three (`--go:#8AAE72`, `--marginal:#E0A542`, `--standdown:#C8452F`). Deliberately **not** `--standdown`, which is close in hue but means *don't go*. Nothing in this palette exceeds roughly 0.08 chroma; a saturated red would read as an error state.

Derived values: strip border `rgba(196,120,127,.4)`, wash `rgba(196,120,127,.07)`, kicker and warning text `#D79AA0`.

Typography, spacing and radii are all inherited. No new type sizes.

---

## Data model

`ECLIPSE` topics are **precomputed and stored**, never derived per forecast run.

Per region:

```
magnitudePct        number   e.g. 94
contacts            { first, max, last }   ISO datetimes
sunAltitudeAtMax    number   degrees, e.g. 11
sunAzimuthAtMax     number   degrees or compass string, e.g. 'WNW'
sunsetTime          ISO datetime
```

On the event record:

```
returnYears         number|null   55 for this eclipse, null for a spring tide
```

`returnYears` is a **new optional field on all almanac events**, used to generate the `.rare` string rather than authoring it by hand. Above roughly 10 years it earns the rarity line; below, it stays silent. This is what stops the label appearing on every spring tide run.

Source the ephemeris from a published table (NASA Five Millennium Canon, or an ephemeris library) and seed it. Do not compute eclipse geometry at runtime.

## New backend work: west-horizon site scoring

The **`clear to the west — 31 of 57`** figure is the only thing on the strip you cannot compute today, and it is the most useful number on it. It needs a horizon-obstruction test for a site against a fixed bearing and altitude (here WNW, 11°).

Existing site data has elevation and aspect but not a horizon profile. Options, cheapest first:

1. **Aspect + elevation heuristic** — score sites whose aspect falls within ±45° of the bearing and which are not overlooked. Fast, rough, probably good enough for a pilot.
2. **Terrain raycast** — sample a DEM along the bearing out to ~20 km and find the maximum subtended angle. Accurate, reusable for every low-sun event (sunset alignments, the supermoon rise, low winter light), and the reason it may be worth doing properly.

If neither is feasible in time, drop the third `.fig` and let `.pb` fall back to its three-column base variant. The strip then reads thinner than the snow and tide versions, which is the trade-off.

---

## Safety

**Non-dismissible.** Any surface that raises `ECLIPSE` must carry the solar filter warning. This is the only topic in the catalog where the subject is the sun itself, and the app is actively telling people to point cameras at it.

- Promoted strip: `.pfoot .warn`, first item, always visible.
- Map popup and any expanded card: same copy.
- No dismiss control, no `localStorage` suppression, no "don't show again".

Copy: *Certified solar filter on the lens — not only over your eye.* The second clause matters. Eclipse glasses protect the eye at the viewfinder while the lens concentrates unfiltered sunlight onto the sensor.

---

## Interactions & Behaviour

- **Strip click / `Plan the drive →`** — opens the drive planner for the listed sites, as the snow strip does.
- **`◍` region list** — the eclipse covers every region, so the map **shades a gradient** (88% north-east → 96% south-west) rather than dropping pins. This is different from every other topic and is the one map change.
- **`(i)` hover** — mechanism tooltip; click must `stopPropagation()`.
- No new animations.

## Promotion timing — **needs a product decision**

The promoted strip currently shows tonight's coincidence. An eclipse at 19:13 has to be decided the evening before, because the drive is planned then. Recommend promoting almanac events with `returnYears > 10` at **T−1 evening** rather than on the day.

This is a rule change to promotion, not a content change, and it affects every future rare almanac event. Flagged rather than assumed.

## Open decisions

1. **Promotion at T−1** for rare almanac events (above). Recommended, not decided.
2. **West-horizon scoring depth** — heuristic or terrain raycast, or ship without it.
3. **Backfill.** If `returnYears` and the rarity line ship for `ECLIPSE` only, the distinction reads as a one-off for one event. The other five almanac types should get the field populated at the same time even if none of them currently exceed the threshold.

## Assets

None. Glyphs are Unicode text (`◐ ↓ ↖ ☀ ◍ ▸ ⚠`). Fonts are the existing IBM Plex Sans / IBM Plex Mono / Newsreader pipeline.

---

## Figures — provenance

UK-average magnitude ~90%, ranging 88% in north-east Scotland to ~96% in Cornwall and the Isles of Scilly. Contact times shown (18:17 / 19:13 / 20:06) are **London**; maximum falls between roughly 19:05 and 19:13 BST across the country, with the sun 6–13° above the WNW horizon. Partial phase lasts about 1h 49m.

Site counts (`31 of 57`, `23 of 57`) and the named locations are **illustrative** — replace with real output.
