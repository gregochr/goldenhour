# Handoff: Coming up — one chronology, with a surprise model

## Overview

The **Coming up** tab is being rebuilt. It currently renders a flat 11-row table of almanac events with no colour, uniform row weight, repeated copy, and no actions. This handoff replaces it with:

1. A **handoff row** stating the boundary with Plan (Plan owns the next four days).
2. A **standing conditions strip** — one line per topic that occurs too often to announce, each expandable to show every occurrence and the score it received.
3. A **chronology** of dated entries at two densities, in the existing topic colours, each ending in one action.
4. A **conditional tab badge** with four escalation bands.

The same work **removes the Hot topics panel from the Plan tab** (see §10).

Underneath all of it is a scoring model that decides what appears where. **The model is the substance of this handoff** — the visual work is straightforward, the classification is not.

---

## ⚠️ First question before any implementation

**Do we have the historic data the model needs, and at what granularity?**

Every threshold in §3 is computed from history. If the data is not there, the model degrades in a specific and non-obvious way, so this must be answered before the strip or the badge is built. Please confirm, per topic type (`AURORA`, `DUST`, `INVERSION`, `SNOW`, `SPRING_TIDE`, `KING_TIDE`, `NLC`, `SUPERMOON`, `METEOR_SHOWER`, `EQUINOX`, `SOLSTICE`, `ECLIPSE`, and any others):

| # | Question | Why it decides the build |
|---|---|---|
| 1 | **Do we store a per-day record of whether each topic was present, going back how far?** Or do we only store topics attached to live forecast runs and discard them? | The rarity axis needs a mean inter-arrival time. Without a presence log there is no rate, and the strip cannot exist. |
| 2 | **Do we store the intensity value** — inversion strength 0–10, dust load 0–10, tidal range in metres, Kp — **for past occurrences, not just current ones?** | The magnitude axis is a percentile against the topic's own history. Without stored intensities every occurrence scores at the median and nothing is ever promoted. |
| 3 | **How many months of history exist per topic?** Named separately — they will differ. | Under 60 observations no percentile is trustworthy (§3, cold start). Topics below that need the documented fallback, not a silently wrong score. |
| 4 | **Is there ≥ 2 years for any topic?** | Season-matching (§3) needs it. Without it, use a trailing 60-day window and accept misclassification at the turn of each season. |
| 5 | **Are tide predictions available for the full 90-day forward window**, with range in metres and HW/LW times per port? | The deterministic branch of the rarity axis, the tide sparkline amplitudes, and the alignment facts all depend on it. |
| 6 | **Is there an ephemeris for supermoons, equinoxes, solstices, meteor peaks and eclipses**, or are those hardcoded? | Determines whether §3's zero-arrival-surprise branch reads real data or a table. |
| 7 | **Can we compute "landed within 90 minutes of a light window"** for a past occurrence, or only for live ones? | The peak gate (§3) needs sunrise/sunset for historic dates. Usually cheap to backfill — confirm. |
| 8 | **Do we have a topic-presence log per region, or only nationally?** | The strip says "all 4 regions" / "Dales & Lakes". Without regional granularity, drop those clauses rather than fake them. |

### If the answer to 1 or 2 is no

Do not approximate a percentile from live data — it will produce confident nonsense. Instead:

- **Start logging now.** One row per topic per day per region: `topic_type`, `date`, `region_id`, `present`, `intensity`, `landed_on_window`. This is the whole dependency.
- **Ship an interim rule** in place of the model, and say so in the UI: a topic is standing if it is **configured** as standing (a hand-maintained list), and an occurrence is promoted if its intensity clears a **hand-set constant** per topic type. Constants live in config, not code.
- Keep the four band names and the escalation intact. They are a UX contract and should not change when the model arrives — only the numbers behind them.
- Revisit after 90 days of logging, which is the minimum for the 60-day trailing window plus a margin.

### Also worth answering

9. **What is the real forecast horizon per topic?** The design states three days for cloud and about five for dust transport. If those are wrong, the dashed-rule entries and the footer copy need correcting — the design deliberately does not flatten this to one number.
10. **How often does the classifier run?** The design assumes nightly, after the forecast run. If it runs per-request, add caching — the percentile computation should not sit in the request path.

---

## About the design files

The files in this bundle are **design references created in HTML** — prototypes showing intended look and behaviour, not production code to copy. The task is to **recreate these designs in the existing frontend** (React, per `frontend/src/components/`), using its established components and patterns. `DailyBriefing.jsx`, `HotTopicStrip.jsx` and `HeatmapGrid.jsx` are the existing neighbours; match their conventions.

## Fidelity

**High-fidelity.** Colours, type, spacing and interaction states are final and should be recreated closely. All values are given in §8. The one exception is the **scoring numbers** in the prototype — `7.9 bits`, `p97`, `2.8` — which are illustrative. Real values come from the model.

---

## 1 · The handoff row (Plan / Coming up boundary)

The reason this exists: **there is a hot topic on every forecast window**, so listing the next four days here duplicates Plan in a worse format. The boundary is stated out loud rather than left implicit.

A dashed row above the chronology, full width:

- `display:flex; align-items:center; gap:13px; flex-wrap:wrap; min-height:40px`
- `padding:9px 13px`, `border:1px dashed #4A3A2E`, `radius:9px`, `background:rgba(0,0,0,.12)`, `cursor:pointer`
- Hover: `border-color:#C9A24B`, `background:rgba(201,162,75,.05)`
- Left: mono 9.5px/600, `letter-spacing:.12em`, uppercase, `#ink-3` — `Now — Mon 31` (dynamic: today to the last day Plan shows)
- Middle: mono 10.5px `#ink-2` — `Three topics live on those four days`, followed by inline 6px `radius:2px` swatches in topic colour with the topic name after each
- Right (`margin-left:auto`): mono 10.5px `#C9A24B` — `On Plan →`
- Click navigates to the Plan tab

**The chronology never contains a date inside Plan's window.** This is a hard rule, not a preference — see §3, eligibility.

---

## 2 · Standing conditions strip

Sits between the filter chips and the chronology. Container: `border:1px solid #3A2C23`, `radius:9px`, `background:rgba(0,0,0,.16)`, `overflow:hidden`.

**Header** — `padding:8px 12px 7px`, `border-bottom:1px solid #3A2C23`:
- Title: mono 9.5px/600, `letter-spacing:.12em`, uppercase, `#ink-3` — `Standing conditions`
- Sub: mono 10px `#ink-4` — `frequent · never announced · always one click away`

**Row** (one per standing topic) — clickable, `min-height:40px`, `padding:9px 12px`, `border-top:1px solid rgba(58,44,35,.6)`, hover `background:rgba(255,255,255,.022)`:

`display:grid; grid-template-columns:auto minmax(0,168px) 1fr auto; gap:11px; align-items:center`

| Cell | Content | Style |
|---|---|---|
| swatch | — | 7×7px, `radius:2px`, topic colour |
| name | `Saharan dust` + kind tag | 12.5px/600; tag mono 8.5px/600 `letter-spacing:.1em` uppercase, topic colour at `opacity:.72`, `margin-left:7px` |
| rate | `12 plumes since 14 June · about one a week` | mono 10.5px `#ink-3`, `<b>` → `#ink-2` |
| peak | `peak thickest Wed 2 Sept · 8/10 · 7.9 bits` + caret `▾` | mono 10.5px topic colour; `peak` label `#ink-4`; `<b>` → `#ink` |
| quant | `rarity 2.8 · mean gap 6.9 days · load median 4/10, p90 7/10` | mono 10px `#ink-4`, `grid-column:2/-1`, `margin-top:-2px` |

Caret rotates 180° when open (`transition:transform .15s`).

**Kind tag values** — derived, not authored: `persistent` (present most days), `recurrent` (arrives in bursts on a stable rate), `deterministic` (on an ephemeris).

### 2.1 Expansion panel — the important part

Collapsed by default. **Critical CSS note:** the panel uses `display:flex`, which beats the UA `[hidden]{display:none}` rule. You must include `.occ[hidden]{display:none}` or the panel is permanently open. This bit us during design.

Panel: `padding:3px 12px 10px 30px`, `background:rgba(0,0,0,.2)`, `border-top:1px solid rgba(58,44,35,.6)`.

Header line: mono 9.5px uppercase `#ink-4` — `every occurrence in the window · 2 held back, 4 in the list, 1 inside Plan` (counts computed).

Occurrence rows: `display:grid; grid-template-columns:74px 62px 68px 112px 1fr; gap:10px`, mono 10.5px, `border-top:1px solid rgba(58,44,35,.4)`:

`date` · `value` · `N.N bits` · optional reason tag · status

**Three statuses, and they must be accurate:**

| Status | Text | Styling |
|---|---|---|
| held back | `held back` | `#ink-3` throughout |
| promoted | `in the list →` | date/value `#ink` 600, status + bits in topic colour |
| inside Plan | `inside Plan's four days →` | date/value `#ink-2` 600, status + bits `#C9A24B` |

A row marked `in the list →` **must** correspond to a real chronology entry, and a row inside Plan's four days **must** use the third status — it can never have a Coming up row. Getting this wrong makes the link a dead promise.

Reason tag (mono 9px `#C9A24B` `opacity:.8`) appears only where the score is not the topic's own magnitude — e.g. `max w/ supermoon`. Required wherever §3's max rule applied, because the strip is the one place scores sit side by side for comparison and an unexplained outlier reads as an arithmetic bug.

**Why the panel exists:** frequent, average occurrences are still good information — the user may simply choose to ignore them. The bands are an escalation of *delivery*, not of worth, and nothing is discarded. The panel is the handle on everything held back.

---

## 3 · The scoring model

### The question

An occurrence is worth announcing when it is **surprising**. Surprise has two components, and they **compensate** rather than gate — either axis alone can carry an occurrence up a band:

- Infrequent and large → needs attention
- Infrequent and average → still gets attention
- Frequent and large → gets attention
- Frequent and average → still good information, available on request, never announced

Both components are expressible as surprisal in bits, which puts them on one scale and lets them add.

```
S = rarity + magnitude
```

### Rarity

```
rarity = log2(mean gap between occurrences, in days)
```

Estimated on a **trailing 60 days**. Reference points: daily ≈ 0, weekly ≈ 2.8, fortnightly ≈ 3.9, monthly ≈ 4.9, quarterly ≈ 6.5, annual ≈ 8.5.

Three ways to estimate the rate, all producing the same units:

- **Persistent** — present on most days; use daily prevalence. Inversions at 23/30 → rarity 0.4.
- **Recurrent** — arrives in bursts; use mean inter-arrival. Dust at 12 arrivals / 76 days → rarity 2.8.
- **Deterministic** — on an ephemeris, so the rate is exact rather than estimated. Spring tides at 14.8 days → rarity 3.9.

**Rarity is not unpredictability.** A spring tide is perfectly predictable and still only fortnightly. Measuring the *information content* of its arrival would score it zero and drop it off the surface; measuring its **rate** puts it at 3.9. Predictability governs confidence, not importance.

**Why not daily prevalence alone:** dust is present with p ≈ 0.14, which is 2.8 bits per day and looks highly newsworthy. But arrivals are not independent — eleven weeks of weekly dust makes the twelfth entirely expected. Information lives in the **inter-arrival** distribution, not the daily one.

### Magnitude

```
magnitude = -log2( P(X >= x) )
```

against that topic's own intensity history over a **trailing 90 days**. Reference points: median ≈ 1.0, p90 ≈ 3.3, p97 ≈ 5.1, p99 ≈ 6.6.

Each topic keeps its own scale — inversion strength 0–10, dust load 0–10, tidal range in metres, Kp. **Never compare across types**, only against a topic's own past.

### Bands

| Total S | Band | Delivery |
|---|---|---|
| `< 5` | **On request** | Strip line only. One click to open. |
| `5 – 7.5` | **In the list** | A chronology row. |
| `7.5 – 9.5` | **Announced** | A row, plus a count badge on the tab. |
| `≥ 9.5` | **Interrupt** | A row, plus a solid coral `◆` badge — no number. |

The band edges are **calibration knobs, not design decisions**. `5`, `7.5` and `9.5` are placeholders. Set them against a year of real backlog targeting roughly **10 badges a year**, of which one or two clear the top contour, and a chronology under about fifteen rows. Put them in config.

The two badge shapes are the third and fourth bands of one surface, not two kinds of judgement. Above 9.5 there is only ever one thing in play, so the badge drops the number.

### Rules that are not negotiable

**Never add causally linked topics.** A king tide and a supermoon are one piece of orbital mechanics — the moon's perigee both enlarges it and stretches the tide. Summing their surprisal double-counts a single cause into a false alarm. **Take the maximum.** Only genuinely independent coincidences add, and those are the ones that deserve a badge. Wherever the max was taken, label it in the UI (§2.1).

**Peak gate.** A promoted occurrence must land **within 90 minutes of a light window**. A 9/10 inversion at two in the afternoon is not worth listing whatever it scores.

**Eligibility.** Only dates **beyond Plan's four days** can badge or take a chronology row. Inside that horizon there is always something, so nothing there can ever fire a badge — this is the whole reason the badge can be silent.

**Evidentiary bar.** Require **≥ 5 occurrences in the trailing 60 days** before trusting a rate. Two Kp 6 storms in sixty days gives a 95% Poisson interval of roughly 0.4–11, which is no answer; storms stay events.

**Hysteresis.** A **1-bit dead zone** at each band edge. Without it a topic sitting on a contour moves between strip and list every night and the tab looks broken.

**Seasonality is a feature.** Rates are re-estimated nightly on a trailing 60 days. Dust at one a week scores rarity 2.8 in August; by November the gap stretches to a month and it scores 4.9 — the same plume, two bands higher. The first plume after a quiet autumn earns a row on rarity alone. Nothing is special-cased; the axis moved. With ≥ 2 years of history the window should be **season-matched** (this August against previous Augusts) or the tab misclassifies at the turn of every season.

**Cold start.** Under **60 observations** for a topic, no rate or percentile is trustworthy. Assume high rarity, list it, **do not badge**. Over-listing something new for two months is a smaller failure than silently swallowing it.

### "Standing condition" is observed, not assigned

A topic is standing when its **typical** occurrence scores below 5. It is a description of where a topic's distribution sits on the surface, not a flag someone sets. It follows that a topic can move between the strip and the chronology on its own as its season turns — which is correct behaviour, not a bug.

---

## 4 · Chronology entries

Two things earn a row: a **date fixed by orbital mechanics**, or the **forecast peak of a standing condition**. Nothing else.

`display:grid; grid-template-columns:66px 1fr; gap:12px; margin-bottom:7px` (phone: `54px 1fr; gap:9px`)

### Date rail

- Box: `border:1px solid #3A2C23`, `radius:7px`, `background:rgba(0,0,0,.22)`, `padding:4px 0 5px`, centred
- Day-of-week: mono 8.5px/600 `letter-spacing:.12em` `#ink-3` — omit for date ranges
- Number: 17px/700 `line-height:1.1` `letter-spacing:-.02em`
- For ranges, the number slot becomes mono 12.5px/600 (`10–15`)
- Month: mono 8.5px/600 `letter-spacing:.12em` `#ink-3`
- **Runs crossing a month use both slots** — `26 SEP` over `–1 OCT`. Do not collapse to `26–1 SEP`; it is not a date range.
- Countdown below the box: mono 9px `#ink-4`, centred, `margin-top:4px` — `in 13 days`, `tomorrow`, `now`

### Card

- `border:1px solid #3A2C23`, `border-left:2px solid <topic>`, `radius:9px`
- `background: color-mix(in oklch, <topic> 6%, transparent)`; hover `10%`
- `padding:9px 13px 10px`, `cursor:pointer`, `transition:background .14s, border-color .14s`
- **`border-left-style:dashed` when the entry is forecast-driven** and can still move. Solid means the date cannot change. This must be paired with the legend (§5) or it is undecodable.

Contents in order:

1. **Title row** (`flex, align-items:baseline, gap:9px, flex-wrap:wrap`):
   - Name — 13.5px/600, or 15px/700 on feature cards
   - Optional `NEW` flag — mono 8.5px/600 uppercase, `#1B1411` on `#E8593F`, `radius:4px`, `padding:2px 6px`
   - Kind tag — mono 8.5px/600 `letter-spacing:.1em` uppercase, topic colour `opacity:.8`, `border:1px solid color-mix(in oklch, <topic> 34%, transparent)`, `radius:4px`, `padding:1px 5px`
   - Optional superlative tag — mono 8.5px/600 uppercase, `#EBD9A8` on `rgba(201,162,75,.14)`. **Must be falsifiable-proof against the data** — "biggest until November" not "biggest of the autumn", because a later, larger run exists in the same season and both strings are visible at once when the strip is open.
   - Headline metric (`margin-left:auto`) — mono 12px/600 in topic colour: `8/10`, `Kp 6`, `~20/hr`, `twice a year`
2. **Prose** (feature cards only) — Newsreader 13.5px, `line-height:1.55`, `#ink-2`, `max-width:76ch`, `text-wrap:pretty`
3. **Facts row** — `flex, wrap, gap:4px 16px`, mono 10.5px `#ink-2`; keys `#ink-4`, `<b>` `#ink`, `<em>` topic colour 600
4. **Threshold line** (optional) — mono 10px `#ink-3`, `margin-top:7px`, `padding-top:7px`, `border-top:1px dashed #3A2C23`. States the bar the entry cleared: *"The other 11 plumes this summer scored 3.2–4.4 and stayed in the strip."* Required on any promoted peak of a standing condition — without it the omissions look like bugs and the inclusions look arbitrary.
5. **Action** — mono 10.5px topic colour, `margin-top:8px`, `opacity:.85` → 1 on card hover. Exactly one per entry, into the rest of the app: `See the plan for 2 Sept →`, `Show coastal spots for 12 Sept →`, `Show dark-sky spots →`.

Fresh entries (arrived since last view) also get `box-shadow: inset 0 0 0 1px rgba(232,89,63,.24)`.

### Tide sparkline

Reuses the drilldown's wave, with the range carried by **amplitude** — so the big run is visibly taller without reading a number.

- SVG `viewBox="0 0 104 24"`, rendered 84×24, `preserveAspectRatio="none"`, `overflow:visible`
- `amp = min(10, 3 + (range - 3.3) * 3.5)` where 3.3 m is the average tide
- Sample every 2px: `y = 12 - amp * cos(2π(x-41)/62)` for high water on the window; `y = 12 + amp * cos(...)` for low water (the wave inverts)
- Ghost wave behind at `amp = 3` (an average tide), same path fn, `stroke-width:1`, `opacity:.26` — this is the `+1.9 vs avg` figure drawn rather than said
- Live wave `stroke-width:1.5`, `stroke-linejoin:round`, topic colour
- Marker at `x=41`: dashed vertical line to `y=12` (`#E0A542`, `stroke-dasharray:2 2`, `opacity:.75`) and a `r=2.4` filled circle — holds HW or LW on the window
- Numeric label beside it: `<b>5.2 m</b> +1.9 vs avg`, `<b>` `#ink`, delta `#ink-4`

### Coincidence card

When two topics fall on the same dates, they are **one card**, not two rows — the overlap is usually the most valuable thing on the page and two rows hide it.

- `flex-direction:column`; each line `display:flex; align-items:baseline; gap:9px; padding:5px 0; flex-wrap:wrap`
- Lines after the first: `border-top:1px dashed #3A2C23`
- Per line: 7×7px `radius:2px` swatch in *that* topic's colour, name 13px/600, facts mono 10.5px `margin-left:auto`
- Below both: a joining sentence, Newsreader 13.5px `#ink-2`, `margin-top:7px` — states **why** they coincide and, where the max rule applied, the arithmetic: *"One cause, so the pair scores as the maximum of the two, not the sum: 9.0 bits."*

### Month rules

`flex, align-items:center, gap:12px, padding:19px 0 9px` (first: `padding-top:11px`). Month mono 10px/600 `letter-spacing:.15em` uppercase `#ink-2`; year mono 10px `#ink-4`; then `flex:1; height:1px; background:#3A2C23`.

---

## 5 · Header, legend, chips, footer

**Header** — title 16px/700 `letter-spacing:-.015em`; sub mono 10.5px `#ink-3` — `· dated events beyond Plan's four days, next 90 days`.

**Legend** (`margin-left:auto`, mono 9.5px `#ink-3`) — two items, each a 2×13px `radius:1px` bar plus a word:
- `fixed` — solid `#ink-3`
- `still firming` — `repeating-linear-gradient(180deg, #ink-3 0 3px, transparent 3px 6px)`

**Required whenever any entry uses a dashed rule.** Without it the dashed border is undecodable.

**Filter chips** — `flex, gap:6px, wrap`, `padding:11px 0 4px`, `border-bottom:1px solid #3A2C23`. Chip: mono 10.5px, `border:1px solid #3A2C23`, `background:#1E1712`, `radius:20px`, `padding:5px 12px`, `min-height:30px`. Active: `border-color:rgba(201,162,75,.5)`, `background:rgba(201,162,75,.12)`, `color:#EBD9A8`. Each carries a 6px dot in its family colour and a count in `#ink-4`.

Families: `All` · `Coastal` · `Night sky` · `Sun & moon` · `Air & dust`. Counts are live.

**Footer** — `margin-top:14px`, `padding:10px 13px`, `border:1px solid #3A2C23`, `radius:8px`, `background:rgba(0,0,0,.18)`, mono 10.5px `line-height:1.65` `#ink-3`.

Copy, with live counts:

> **This list starts where Plan stops.** Two things earn a row: a date fixed by orbital mechanics, and the forecast peak of a standing condition. **8** here are fixed — as certain three months out as they are tonight — and carry a solid left rule. **1** is a forecast peak on a dashed rule and can still move; horizons differ by topic, from three days for cloud to about five for dust transport. Routine occurrences of the conditions above are never listed, only opened.

The counts must be derived. An earlier draft hardcoded "all 9 dates are fixed" and then contradicted itself when a forecast entry was added.

---

## 6 · The tab badge

On the `Coming up` tab button. Tab becomes `display:inline-flex; align-items:center; gap:8px`.

| State | Markup | Style |
|---|---|---|
| nothing new | no element at all | — |
| Announced (S 7.5–9.5) | count | mono 10px/600, `min-width:17px`, `height:17px`, `padding:0 5px`, `radius:9px`, `background:rgba(232,89,63,.18)`, `color:#F0917C`, `border:1px solid rgba(232,89,63,.45)` |
| Interrupt (S ≥ 9.5) | `◆`, no number | as above but `background:#E8593F`, `color:#1B1411`, `border-color:#E8593F`, `box-shadow:0 0 0 3px rgba(232,89,63,.16)`, `font-size:9px` |

**Not a count of contents.** The tab always holds ~9 entries; a count of them would read `9` forever and become wallpaper. It counts only **arrivals into the 90-day window since the user last opened the tab** that clear a band. Expect it a handful of times a year. Silence is the normal state and is what makes it worth looking at.

Forecast topics do **not** badge on arrival. They are live several times a week and already have the aurora banner and the Plan pills.

### The badge must land somewhere

A count with nothing to find is a dead end. The same state renders a line above the chips:

`flex, align-items:center, gap:10px, wrap`, `margin-top:10px`, `padding:8px 12px`, `border:1px solid rgba(232,89,63,.28)`, `background:rgba(232,89,63,.07)`, `radius:8px`, mono 10.5px `#ink-2`; `<b>` → `#F0917C`. Interrupt state raises both to `.5` / `.12`.

Copy: `◆ 9.7 bits — the Orionids peak entered the window, 21 Oct. Annual, so rarity alone carries it over the top contour.`

Right side: a `Mark seen` button (mono 10px, `border:1px solid #3A2C23`, `radius:6px`, `padding:4px 9px`, `min-height:28px`) which clears the badge. Arriving entries also carry the `NEW` flag (§4).

---

## 7 · Interactions

| Element | Behaviour |
|---|---|
| Handoff row | Navigates to the Plan tab |
| Filter chip | Filters the chronology; counts stay static (they describe the unfiltered set) |
| Condition row | Toggles its occurrence panel. Caret rotates. Multiple may be open. |
| Occurrence marked `in the list →` | Scrolls to and highlights that chronology entry |
| Occurrence marked `inside Plan's four days →` | Navigates to Plan for that date |
| Chronology card | Opens the existing drilldown for that date/window |
| Card action link | The stated destination — plan for a date, map filtered to coastal or dark-sky spots |
| `Mark seen` | Clears the badge and all `NEW` flags; persists per user |

Responsive: below 820px the condition grid collapses to `auto 1fr` with rate, peak and quant stacking in column 2; occurrence rows drop to `64px 56px 1fr` and hide status and reason. Entry rail narrows to 58px, then 54px on phone.

---

## 8 · Design tokens

Existing PhotoCast tokens — do not introduce new ones.

```
--bg:#181210        --surface:#221A15    --surface-light:#2A2019
--panel:#1E1712     --border:#3A2C23     --border-light:#4A3A2E
--ink:#F2E7D3       --ink-2:rgba(242,231,211,.66)
--ink-3:rgba(242,231,211,.42)   --ink-4:rgba(242,231,211,.26)
--go:#8AAE72        --marginal:#E0A542   --poor:#C8452F
--tide:#6FA8B0      --home:#C9A24B       --coral:#E8593F
```

**Topic colours** — five families, no new hues. These already appear on the heat pills, the tide row and the drilldown.

| Family | Hex | Topics |
|---|---|---|
| Coastal | `#6FA8B0` | spring tide, king tide |
| Aurora | `#8AAE72` | aurora |
| Air | `#8FA3B8` | cloud inversion, snow |
| Night sky | `#9B8FD4` | NLC, meteor showers |
| Sun & moon | `#C9A24B` | equinox, solstice, supermoon |
| Dust | `#C08552` | Saharan dust |

Eclipses escalate to `#f87171` on rarity, per the existing Eclipse Topic design.

**Type** — `IBM Plex Sans` (UI), `IBM Plex Mono` (all data, times, scores, labels), `Newsreader` (prose sentences only). Sizes as specified per component; nothing below 8.5px, and 8.5px only for uppercase mono tags.

**Radii** — 4px tags, 7px date box, 8px footer/chips-active, 9px cards and strip, 20px chips.

---

## 9 · State

```
comingUp: {
  handoffWindow: { from, to, liveTopics: [{ type, label, colour }] },
  conditions: [{
    type, name, kind,            // persistent | recurrent | deterministic
    rarity, rateLabel, quantLabel,
    peak: { date, value, bits, label },
    occurrences: [{ date, value, bits, status, reason }]
                                 // status: heldBack | promoted | insidePlan
  }],
  entries: [{
    id, type, family, month, rail: { dow, day, month, countdown },
    isForecast,                  // drives the dashed rule
    name, kindTag, superlative, metric,
    prose, facts, threshold, action,
    coincidence: [{ colour, name, facts }] | null,
    joinNote, tide: { range, delta, phase }, bits, isNew
  }],
  badge: { band, count } | null, // null = no element rendered
  lastSeenAt
}
```

Client state: `activeFilter`, `openConditions: Set`, `highlightedEntryId`.

`lastSeenAt` persists per user. Everything else comes from the nightly classifier — do not compute percentiles in the browser.

---

## 10 · Removing Hot topics from the Plan tab

Same release. The panel is a table of contents for content that now lives in three better places: the heatmap pills, the drilldowns, and this tab.

- Delete the `Hot topics` collapsible panel from the Plan tab.
- The `Regional planner` panel becomes **full width**. The side-by-side pairing forced a symmetry the content never had — the planner is a large grid, Hot topics was a short list, and pairing them made the grid read as half a thing.
- `HotTopicStrip.jsx` is still used elsewhere; check call sites before removing anything from it.
- No functionality is lost: routine topics stay on their window, persistent ones appear in the conditions strip, and peaks get chronology rows.

---

## 11 · Assets

None. All graphics are inline SVG generated from data — the tide wave, the surprise matrix in the design notes. No icon or image files.

---

## 12 · Files in this bundle

| File | What it is |
|---|---|
| `Coming Up.html` | **The design of record.** Full tab in the app shell, three viewport toggles, three badge states, and the design notes and model spec below the frame. |
| `Plan Tab with Heat v3.html` | The Plan tab this sits beside — source of the masthead, tab bar and topic colours. Also where the Hot topics panel is removed from. |
| `plan-tab-v3.js` | Renderer for the above; contains the original tide row (`tideRow()`) the sparkline derives from. |
| `Eclipse Topic.html` | Prior art for the almanac/forecast split and the rare-event escalation. |

The scoring matrix, the band definitions and every rule in §3 are also rendered in `Coming Up.html` below the frame, with a plotted chart showing where each example occurrence falls. Read that alongside this README — it is the same content, drawn.
