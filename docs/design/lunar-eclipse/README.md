# Handoff: Lunar eclipse — extending the ECLIPSE almanac topic

## Overview

PhotoCast already has (or is building, see `reference/solar-eclipse-handoff-README.md`) an `ECLIPSE` almanac topic for the partial **solar** eclipse of 12 Aug 2026. This handoff extends it to **lunar** eclipses, worked through on the deep partial lunar eclipse of **Friday 28 August 2026** (umbral magnitude 0.930, maximum 05:13 BST; the moon sets from north-east England while still in shadow).

The feature shows up in four places over the event's life:

| When | Surface | What |
|---|---|---|
| T−90 → T−4 | **Coming up** tab | Featured row; solid ◆ tab badge + "Rare" banner when it enters the window |
| T−1, 18:00 | **Top of Plan** | Promoted strip (eclipse variant) with the new **dawn race** timeline |
| T−4 → T−0 | **Plan cards** | Emphasised `🌘 Lunar eclipse · 05:13` topic chip on the Friday sunrise card |
| On tap | **Window popup** | Topic row with (i) science tip, dawn race for the selected location, locations ranked on western horizon |

One genuinely new component: **the dawn race**. Everything else reuses existing components with new data.

Also in this bundle: a **copy change to Coming up** — all user-facing "bits" (surprisal scores) are replaced by plain language. See §6.

## About the design files

The HTML files here are **design references** — prototypes showing intended look, copy and behaviour. They are not production code. Recreate them in the PhotoCast frontend (React, `frontend/src/components`, existing `HOT_TOPIC_STYLES` catalog, existing hot-topic pill and promoted-strip components) using its established patterns. Do not ship the HTML.

## Fidelity

**High-fidelity.** Colours, type, spacing, radii and copy are final. Palette is the existing PhotoCast dark warm-neutral skin.

Strip before building: the doc prose in `Lunar Eclipse.html` (`.hd`, `.life`, `.bar` section dividers, `.cap` captions, `.cmp` table, `.spec` list, `.foot`), and the Desktop/Phone demo bar. In `Coming Up.html` and `Plan Tide Summary.html`, the `.hd` intro, `.demobar`s and `.notes`/`.nb` blocks are design commentary.

---

## 1. Coming up — featured row

**Reference:** `Lunar Eclipse.html` § 01. Existing Coming up entry component (`.ent` / `.card` in `Coming Up.html`) — no new component.

- Rail: date box `FRI / 28 / AUG`, countdown `in 30 days` (mono 9px, `--ink-4`).
- Card: `--tc: #C4787F` (`--ecl`), solid left rule (almanac = fixed), `feat` sizing (name 15px/700).
- Title row: `🌘` · **Deep partial lunar eclipse** · `NEW` flag (only while unseen) · `ALMANAC` kind tag · pick tag `SETS IN SHADOW` · right-aligned `93%` (mono 12px/600, accent).
- Why (Newsreader 13.5px/1.55, `--ink-2`):
  > Earth's shadow covers all but a sliver of the full moon, and the shadowed part turns copper. Maximum is at 05:13 with the moon **7° above the west-south-west horizon**, and it sets an hour later still in shadow. A low, clear western view is worth more than a dark site.
- Facts (mono 10.5px): `max 05:13 · moon WSW 244°, 7° up` · `in shadow 03:34 → sets 06:16` · `clear to the west 28 of 57 sites`
- **Next line** (new optional slot, mono 10px `--ink-3`): `Next from the UK: a total eclipse, Sun 31 Dec 2028`. Replaces the solar `rare`/`once` line when `returnYears < 10`.
- `why2` (dashed top border): **No filter needed.** The shadowed moon is about ten stops darker than the lit edge, so bracket your exposures rather than trust the meter.
- Action: `See the plan for 28 Aug →` → opens Plan on that window.

**Tab badge:** solid `◆` (Interrupt band — see Rarity). Banner above list (`.since.rare`): `◆ Rare — a deep lunar eclipse entered the window, Fri 28 Aug. The first visible from here since March 2025.` + `Mark seen` (clears badge and NEW flag).

## 2. Top of Plan — promoted strip, lunar variant

**Reference:** `Lunar Eclipse.html` § 02. Same `.promo` component as the solar variant (`reference/Eclipse Topic v2 (solar).html`).

- Container: `border:1px solid rgba(196,120,127,.4); border-left:3px solid #C4787F; border-radius:11px; background: linear-gradient(180deg, rgba(196,120,127,.07), transparent 70%), #221A15`.
- Header `.ph`: kicker `🌘 Eclipse × clear west · tomorrow` (mono 10px/600, .11em, uppercase, `#D79AA0`); headline `The moon sets 93% in shadow, low over a clear western horizon` (14.5px/700); flex rule; right note `next from the UK Dec 2028 · 2h 42 in shadow before it sets` (mono 10px `--ink-3`, year in `--ink`).
- Figures row (4-col grid, gap 20px): `in shadow / 93% / same everywhere` · `maximum / 05:13 / moon 7° up, WSW` · `clear to the west / 28 of 57 / sites evaluated` · italic serif note right-aligned: *maximum lands just before blue hour, so the sky still frames the copper*. Figure value 15px/600 `--ink`, labels mono 11px `--ink-2`.
- **Dawn race** (see §5) with header `THE DAWN RACE · in shadow against the brightening sky · Dunstanburgh`.
- Footer `.pfoot`: `◑ No filter needed · bracket, the shadow is ~10 stops under the lit edge` (`#D79AA0`) · `⏰ leave by 04:20 for Dunstanburgh` (`#EBD9A8`) · site list `Dunstanburgh · Bamburgh · Seaton Sluice` · `Plan the drive →` (`--tide`, right).
- Unlike the solar filter warning, the exposure note is **not** a safety item — it may be dismissible.
- Phone (<560px container): figures go 2-col, note spans full width left-aligned.

## 3. Plan cards — topic chip

**Reference:** `Lunar Eclipse.html` § 03, and live in `Plan Tide Summary.html` (Friday sunrise card; data injected in an inline `<script>` before `plan-tide-v6.js`).

- Topic catalog entry (see §7). Rarest first → `w:0`, so it leads the topics line.
- Chip uses the **emphasised** variant from the tide work, because it is the only topic with a clock time:
  `padding:1px 8px; border-radius:999px; background:rgba(196,120,127,.14); box-shadow: inset 0 0 0 1px rgba(196,120,127,.42); color:#E3AEB3;` mono 10.5px/600. Content `🌘 Lunar eclipse` then a divider (`border-left:1px solid rgba(196,120,127,.45); padding-left:6px`) and `05:13`.
- Card time remains the sunrise window (`06:11`); the chip carries the eclipse time.
- Tooltip/title: `Partial lunar eclipse — 93% in shadow at 05:13, moon 7° above WSW, sets 06:16`.

## 4. Window popup

**Reference:** `Lunar Eclipse.html` § 04.

- Header pills: `◎ Worth it` · `🌘 Partial lunar eclipse` (topic pill, accent tint 14%, border 48%) · other topics.
- Topic row (`.trow`, left rule accent): `🌘` · **Partial lunar eclipse** · `(i)` · `93% at 05:13 · moon 7° above WSW · sets 06:16, still in shadow` · `all regions`.
- `(i)` hover tip (330px, title `WHY IT TURNS COPPER, NOT BLACK`, mono 10px accent-light; body Newsreader 12.5px):
  > Earth's atmosphere bends a little sunlight into its own shadow and filters out the blue on the way, so the shadowed moon glows the colour of every sunrise and sunset on Earth at once. 93% is a fraction of the moon's *diameter* in shadow, not its area. The lit sliver on the lower-left edge will still be much the brightest thing in the frame.
  Click on `(i)` must `stopPropagation()`.
- Dawn race directly beneath, for the **selected location** (redraws on location tap), plus legend: `in the umbra` / `below the horizon`.
- Location list label: `LOCATIONS · RANKED ON THE WESTERN HORIZON AT 244°, 7°`. Ranking switches to west-horizon clearance (`clearToDeg` ascending, then rating). Each spot adds a line in `#E3AEB3` mono 10.5px: `◑ clear to 1° · WSW over the castle`, plus a one-line serif foreground note.
- Footer: `◑ no filter · bracket` · `28 of 57 sites clear to the west` · `◍ Show on map →`.

## 5. New component — the dawn race

A horizontal timeline, 03:00 → moonset (clip 06:30). Positions are `(t − 03:00) / 210 min`.

- Track: `height:46px; border-radius:7px; border:1px solid #3A2C23;` background gradient (reuses masthead light-rule stops):
  `linear-gradient(90deg,#141a26 0%,#161d2b 54%,#26313F 64%,#4A3550 72%,#7C4A56 80%,#B4553C 88%,#E0A542 96%,#E8B866 100%)` — generate stop positions from civil dawn / blue / golden / sunrise for the location in production.
- Phase ticks: dashed 1px `rgba(242,231,211,.25)` verticals at civil dawn, blue, golden, with mono 8.5px uppercase labels (hidden on phone).
- Umbra band: from U1 to `min(U4, moonset)`, bottom 6px, height 9px, `rgba(196,120,127,.85)` with 1px striations; left end rounded 5px.
- After moonset: diagonal hatch `rgba(242,231,211,.1)` to the end = eclipse continues below horizon.
- Markers: 1.5px vertical line + 9px dot at bottom — maximum `#D79AA0`, sunrise `#E0A542`, moonset `#F2E7D3` (square dot).
- Label row beneath (mono 10px `--ink-3`, value 10.5px/600 `--ink`, max value in `#D79AA0`): `03:00`, `03:34 enters shadow`, `05:13 max · 7°`, `06:11 sunrise`, `06:16 moonset`. On phone (<560px) hide `03:00` and `sunrise` labels.
- For a total eclipse, add a second, denser band for U2→U3 (totality). Not designed yet — see open questions.

## 6. Coming up — plain-language copy (no more "bits")

`Coming Up.html` has been updated so no user-facing string shows a surprisal score. Scoring logic is unchanged; only the copy.

- Rare banner: `◆ Rare — the Orionids peak entered the window, 21 Oct. It comes once a year, which is rare enough to flag on its own.`
- Announced banner: `1 announced — the king tide on the November supermoon entered the window, 26 Nov. The biggest tide of the year.`
- Condition lines: e.g. `about one a week · load usually 4/10, 7/10 on a heavy one`; `most mornings · strength usually 5/10, 8/10 on a strong one`; `7 runs in 90 days · range usually 4.7 m, 5.0 m on a big one`. Peak values without score (`8/10`, `9/10`, `5.2 m`).
- Occurrence list: score column becomes a word — `exceptional` (≥7), `above usual` (≥4.5), `typical`.
- Dust row fact: `heaviest of 12 since June`; bar note `The other 11 plumes this summer were lighter and stayed in the strip.`
- Coincidence cards: `Counted as one event, not two.` replaces "scored as the maximum … N bits".
- The design notes and matrix beneath the frame still explain the model in bits — that's for engineering, not UI.

## 7. Data model & catalog

- **Type:** `ECLIPSE`, source `ALMANAC`, new field `body: 'sun' | 'moon'`. One type — shared scorer, strip, accent. `body` switches safety line (sun: mandatory filter warning; moon: optional exposure note), glyph, and whether the dawn race renders (moon only, for now).
- **Accent:** `--ecl #C4787F` (shared with solar). Light variant `#D79AA0`; chip text `#E3AEB3`.
- **Glyph:** `🌘` on card chips, Coming up and popup; `◑` in mono text lines.
- **Per event** (stored once, seeded from ephemeris, never computed at runtime): `umbralMagnitude` (0.930), `kind: 'partial'|'total'|'penumbral'`, `contacts{P1,U1,U2?,max,U3?,U4,P4}` UTC — for 28 Aug 2026: U1 02:34, max 04:13, U4 05:52 UTC. `returnYears` (2.3).
- **Per location** (derived at forecast time): `moonAltAtMax`, `moonAzAtMax`, `moonset`, plus existing civil dawn / sunrise. Drives the dawn race.
- **Eligibility:** moon ≥3° up at maximum, or ≥3° up for ≥30 min in the umbra. **Penumbral-only eclipses never raise a topic.**
- **Site scoring:** reuse the solar horizon-obstruction test (bearing + altitude) → `clearToDeg`. Here bearing 244°, altitude 7°.
- **Weather:** verdict uses low-cloud cover in the WSW sector below 15°, not whole-sky cloud.
- **Map:** pins only (sites with `clearToDeg ≤ 3`) with a thin ray at the bearing. No gradient shading — magnitude is uniform.
- **Rarity / badge:** `returnYears < 10` → `next` line, not `rare` line. Tab badge from the Coming up surprise model: UK-visible lunar eclipse mean gap ~900 days → Interrupt band → solid ◆.
- **Promotion:** at T−1 18:00 for any almanac event whose maximum falls between 00:00 and next sunrise.

## Design tokens

```
--bg #181210   --surface #221A15   --surface-light #2A2019   --panel #1E1712
--border #3A2C23   --border-light #4A3A2E
--ink #F2E7D3   --ink-2 rgba(242,231,211,.66)   --ink-3 .42   --ink-4 .26
--go #8AAE72   --marginal #E0A542   --poor #C8452F   --tide #6FA8B0   --home #C9A24B
--coral #E8593F   --dawn #8FA8C4   --air #8FA3B8
--ecl #C4787F   --ecl-light #D79AA0   chip text #E3AEB3
Fonts: IBM Plex Sans (UI), IBM Plex Mono (labels/figures), Newsreader (prose)
Radii: pills 999px · cards 9px · strip 11px · popup 13px · tags 4px
```

## Open questions (product)

1. **T−1 promotion** — recommended as decided for night-time almanac events.
2. **Dawn race on the strip or popup-only?** Strip makes the T−1 promo the tallest thing on Plan.
3. **Totals** — next UK total is 31 Dec 2028 at moonrise (dusk race, reversed). Design now or when it enters the window?
4. **Best bet** — should a clear-west eclipse lift a window's pick if light scores poorly?

## Files

- `screenshots/01–04-lunar.png` — desktop: Coming up row, T−1 promoted strip, Plan cards, window popup. `05–08` — the same four at phone width (390px).

- `Lunar Eclipse.html` — **the spec**: all four screens + comparison, catalog, open questions.
- `Coming Up.html` — Coming up tab with the lunar row (older illustrative date, 27 Sept) and the plain-language copy change.
- `Plan Tide Summary.html` + `heat-field.js`, `plan-data.js`, `plan-tide-v6.js` — live Plan tab with the lunar chip on Friday sunrise (open the HTML; the three JS files must sit alongside).
- `reference/Eclipse Topic v2 (solar).html`, `reference/solar-eclipse-handoff-README.md` — the solar feature this extends.

Figures are for Dunstanburgh (55.49°N) and approximate; site counts and horizon clearances are illustrative. The Coming up and Plan Tide Summary mocks use their own illustrative dates; `Lunar Eclipse.html` is canonical.
