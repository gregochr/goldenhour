# Handoff: PhotoCast — window-first Plan tab

## Overview

The Plan tab had grown to roughly 2,600px of vertical scroll because the page was laid out as **windows × features**: "Close to home" repeated once per solar window, then spring tides repeated per run, then noctilucent cloud, then aurora, then a day calendar, then a full regional briefing. Every module independently re-answered *when*.

This redesign inverts the loop. **Time is the outer loop, features are attributes.** Each shooting window (Today sunset, Tomorrow sunrise, …) is a single card that carries everything relevant to it: its verdict, its Best Bet narrative, its tide state, its snow state, its aurora/NLC deltas, and its nearby spots. Tides and aurora stop being page sections and become properties of the window they affect.

Result: ~1,180px with three windows, ~1,500px with six (two open, four collapsed), on the same feature set. Nothing was deleted — it was re-parented.

Target: solo-operator app about to run a **pilot with ~5 friends**. Priority is comprehensibility and reproducibility over cleverness.

## About the Design Files

The files in this bundle are **design references created in HTML** — prototypes showing intended look and behaviour, not production code to copy. The task is to **recreate these designs in the target codebase's existing environment** (this project is React + Vite: `App.jsx`, `components/`, `hooks/`, `context/`, `utils/`, `api/`) using its established patterns, state management and styling approach.

Do not port the vanilla-JS render functions. Read them for structure and exact values, then build real components.

## Fidelity

**High-fidelity.** Colours, type, spacing, radii and copy are final and should be matched. The palette is the existing PhotoCast dark warm-neutral skin already used in the app (see Design Tokens). Interaction timings are specified and should be matched.

Two deliberate exceptions, both marked in the mock and **not for production**:
- The five demo buttons above the app frame (Multi-user, Away, Six solar events, Winter day, iPhone). Scaffolding for reviewing states that cannot occur in August. **Strip them.**
- The three annotation cards below the app frame (design rationale). **Strip them.**

---

## Screens / Views

### 1. Shell (always present)

**Purpose** — identity, which days exist, and which tab you are in.

**Layout** — single column, `max-width: 1080px`, centred, `background: #181210`, `border: 1px solid #3A2C23`, `border-radius: 12px`, `overflow: hidden`. Page background `#0e0b09`, body padding `26px 20px 80px`.

**Masthead** — `display:flex; gap:12px; padding:16px 18px 14px; border-bottom:1px solid #3A2C23`.
- Logo: 28×28 circle, `conic-gradient(from 200deg, #E0A542, #C8452F, #C9A24B, #E0A542)`.
- Wordmark "PhotoCast": 20px / 700 / `letter-spacing:-.02em`.
- Strapline "AI sunrise, sunset, and aurora forecasting": IBM Plex Mono 10px, `rgba(242,231,211,.42)`.
- Right group (`margin-left:auto`): status pill "● UP v2.17.7" (mono 10px, `padding:4px 9px`, `border-radius:999px`, border `rgba(138,174,114,.4)`, fill `rgba(138,174,114,.12)`, text `#A8C795`), then ⚙ and "Sign out" ghost buttons (mono 10.5px, `border:1px solid #3A2C23`, `border-radius:7px`, `padding:5px 10px`).

**Day rail** — `display:flex; gap:8px; padding:13px 18px 0`. **This is a read-out, not a control**: no hover, no click, no text selection (`user-select:none`). It answers "which day" for every tab, which is why it sits in the shell **above** the tab bar and is never duplicated inside a tab.

Each cell: `flex:1`, `border:1px solid #3A2C23`, `border-radius:9px`, `background:#1E1712`, `padding:9px 11px 10px`. Contents, top to bottom:
1. Row: `DOW` (mono 9.5px, `letter-spacing:.1em`, ink-3) · day number (16px/700) · day name (12.5px/600) · optional flag "◎ BEST" (`margin-left:auto`, mono 9.5px/600, `padding:2px 6px`, `border-radius:5px`, fill `rgba(138,174,114,.16)`, text `#A8C795`).
2. Sun times "↑ 05:22 ↓ 21:14" (mono 10px, ink-3), or "✈ Away · business" when unavailable.
3. Verdict "Maybe · sunset" (mono 10.5px) — colour by verdict: Worth it `#A8C795`, Maybe `#E0A542`, Poor/none `rgba(242,231,211,.42)`.

Today's cell adds `.now`: `border-color:rgba(201,162,75,.55); background:rgba(201,162,75,.08)`. Unavailable days get `opacity:.45`.

**Rail footer** — mono 10px ink-3, `padding:6px 18px 0`: left "Home · Northumberland & Tyneside", right (`margin-left:auto`) "Edit reach · forecast 52m ago by Sonnet".

**Tabs** — `display:flex; gap:6px; padding:12px 18px 0`, then a 1px `#3A2C23` rule. Tab: 12.5px/500, `background:#1E1712`, `border:1px solid #3A2C23`, **`border-bottom-width:0`** (not `border-bottom:none` — that resets width to 3px), `border-radius:8px 8px 0 0`, `padding:8px 14px`. Active: `background:#221A15`, ink 700, `box-shadow:inset 0 2px 0 #C9A24B`. Tabs: **Plan** (default), **Coming up** (with count), **Map**, and **Manage · admin** pushed right with `margin-left:auto` and `border-style:dashed` (existing admin tab — unchanged, gated).

### 2. Lens bar (Plan only)

**Purpose** — the one thing that changes today's decision: how far you will travel today.

`position:sticky; top:0; z-index:20; padding:11px 18px; background:#221A15; border-bottom:1px solid #3A2C23; display:flex; gap:8px; flex-wrap:wrap`.

**Critical layout rule:** each label must be wrapped with its control in `.lgrp{display:flex;align-items:center;gap:8px}` so a wrap can never orphan a label from the buttons it names.

- Label "How far tonight" — mono 10px/600, `letter-spacing:.1em`, uppercase, ink-3.
- Segmented control: `45 min` / `1h 30` / `2h 30` / `Any`. Container `border:1px solid #3A2C23; border-radius:8px; overflow:hidden; background:#1E1712`; buttons mono 11px, `padding:6px 11px`, divided by 1px borders; active `background:rgba(201,162,75,.13); color:#EBD9A8; font-weight:600`.
- Chip "today only" — mono 9px uppercase, `padding:2px 5px`, `border-radius:4px`, fill `rgba(224,165,66,.14)`, text `#EFC377`. Visible only when reach ≠ the day's default.
- "Back to 2h 30" — mono 10.5px, tide blue, visible only when off-default.
- Right (`margin-left:auto`): "2h 30 · weekend default · 39 spots across 3 windows", mono 10.5px ink-3.

**Deliberately only one control.** Rating is already the sort order; location type is a word on each card. Both live in the drilldown (see 5).

**Time, not miles**, because that is how the decision is made — and each card still states miles.

### 3. Promoted event strip (Plan, conditional)

**Purpose** — a *coincidence* is the thing worth driving for. One attribute is a row inside a window; **two attributes landing on the same window** earns a full-width strip, framed around the drive.

**Rule: at most one strip. Highest rarity wins.** Enforce in code, not by convention.

Structure: `border:1px solid rgba(111,168,176,.4)`, `border-left:3px solid #6FA8B0`, `border-radius:11px`, `background: linear-gradient(180deg, rgba(111,168,176,.07), transparent 70%), #221A15`.
- Header: kicker (mono 10px/600, `letter-spacing:.11em`, uppercase, `#9CCBD1`) · title (14.5px/700) · flexible 1px rule · right meta (mono 10px ink-3).
- Body grid: figure blocks (mono 11px label, 15px/600 value) + a chart or further figures + an italic Newsreader 12.5px "why" clause, right-aligned.
- Footer: 1px top border, `background:rgba(0,0,0,.18)`, mono 10.5px ink-3, with a tide-blue action pushed right.

Two variants are mocked:
- **Tide** (default): "≈ BIGGEST TIDES OF THE MONTH — The biggest of the three lands on tomorrow's sunset", meta "tonight · **tomorrow** · Monday — 61 coastal locations, 2 regions". Tide curve SVG 320×44 (`stroke:#6FA8B0`, 1.6px) with two amber dashed window markers; labels sit in their own 16px band **below** the curve (`.chart{height:58px;padding-bottom:16px}`, svg 42px, labels `bottom:0`) — do not overlap curve and labels.
- **Snow × clear sky** (winter): purple-grey `#B7CBD8` accent, figures "drive 2h 05 / 96 mi · leave 03:10", "alpenglow 05:04–05:31", "rated excellent 23 of 57", why "tops white, valleys clear — the combination you drive for".

Copy rule: **no invented vocabulary.** "Spring tide run 2 of 3" was replaced by naming the three nights and marking the biggest, plus one explanatory clause: "Around each new and full moon the tides run bigger for two or three nights — this is the middle one, and the largest."

### 4. Window card (Plan, the core component)

One card per solar window, chronological. `border:1px solid #3A2C23`, `border-radius:11px`, `background:#1E1712`. Tonight's card adds `.lead`: `border-color:rgba(201,162,75,.42)` and `background: linear-gradient(180deg, rgba(201,162,75,.06), transparent 55%), #1E1712`.

**Header** (`display:flex; gap:10px; padding:12px 14px 10px; flex-wrap:wrap`):
- Optional kicker "TONIGHT" (mono 10px/600, uppercase, `#C9A24B`).
- When "Sunset" / "Tomorrow sunrise" (15.5px/700) · time "21:11" (mono 13.5px/600).
- Meta "best 4.0★ · 23 within reach" (mono 11px ink-3).
- Flexible 1px rule, then badges, then an "Open ▾ / Collapse ▴" text button (mono 10.5px, tide blue).

**Verdict colours are semantic and consistent everywhere** (rail, badge, drilldown):
| Verdict | Text | Fill | Border |
|---|---|---|---|
| Worth it | `#A8C795` | `rgba(138,174,114,.14)` | `rgba(138,174,114,.5)` |
| Maybe | `#EFC377` | `rgba(224,165,66,.14)` | `rgba(224,165,66,.5)` |
| Poor | `#E58C7A` | `rgba(200,69,47,.12)` | `rgba(200,69,47,.4)` |

Other badges (mono 10px, `padding:3px 8px`, `border-radius:999px`): tide `#9CCBD1`, NLC `#BFB6E8`, aurora/positive `#A8C795`, snow `#C6D8E3`, neutral `rgba(242,231,211,.66)` on `rgba(255,255,255,.04)`.

**Attribute rows** (`.rows`, `margin:0 14px 11px`, `gap:7px`) — one slim row per real condition:
- Tide row: `display:grid; grid-template-columns:auto auto 1fr auto; gap:12px; padding:7px 11px`, `border:1px solid rgba(111,168,176,.28)`, `border-left:2px solid #6FA8B0`, `background:rgba(111,168,176,.055)`, `border-radius:8px`. Contents: kicker "≈ TIDE", a 104×24 sparkline with an amber marker dot at the window time, then facts as mono 10.5px spans ("mid tide, **falling**" · "HW 19:28 · **1h43 before sunset**" · dim "4.9 m · 1.2 m above an average tide"), then a tide-blue action.
- Snow row: identical geometry, `#B7CBD8` accent, no sparkline. Only rendered when snow exists.

Copy rule: **state the physical fact, never a count of your own data.** "11 aligned" was removed everywhere — it is a fact about the database, not about tonight.

**Narrative** (`.wtop`, `display:grid; grid-template-columns:1.25fr 1fr; gap:15px; padding:0 14px 12px`; single column when there is no second region):
- Best Bet: `border-left:2px solid #8AAE72; padding-left:11px`. Kicker row "◎ BEST BET" + region right-aligned (mono 10px uppercase ink-3). Headline Newsreader 15.5px/500, `line-height:1.32`. Body 12px, `line-height:1.58`, ink-2. Optional location line (mono 10.5px ink-3).
- Also good: same but `border-left-color:#E0A542`. **Only ever the second region for the same window** — a cross-day "also good" cannot exist in this model; it is simply the Best Bet of the window it belongs to.

**Spot film strip** — one horizontal row, ~3.5 cards visible.

Geometry is load-bearing; get it exactly right:
```css
.strip{position:relative;padding:0 14px}                    /* gutter on the WRAPPER */
.spots{display:flex;gap:8px;padding:0 0 11px;               /* no horizontal padding on the scroller */
       overflow-x:auto;scroll-snap-type:x proximity;scrollbar-width:none}
.spots::-webkit-scrollbar{display:none}
.spots>.spot{flex:0 0 calc((100% - 24px)/3.5);scroll-snap-align:start}
```
- **Do not put the horizontal gutter on the scroll container** — with `scroll-snap-align:start` the browser snaps the first card flush to the scrollport and eats the padding, leaving resting `scrollLeft:14` and the first card misaligned with everything above it.
- **Do not set `scroll-behavior:smooth` on the container, and do not pass `behavior:'smooth'` to `scrollBy`** — scroll-snap cancels the animation every frame and the arrows do nothing. Plain `scrollBy({left})` still lands on a card edge because of snap.
- Edge fades signal more content: `.strip.more:after` (right, 56px, `linear-gradient(90deg,transparent,#1E1712 78%)`, inset by the 14px gutter) and `.strip.back:before` (left, 40px, mirrored). Toggle both from a scroll listener. `.win.lead` needs `#221b15` in the gradient to match its tinted background.
- Cards are sorted **rating desc, then drive time asc** — one shared comparator used by both the strip and the drilldown, because both footers claim that order.

Spot card: `border:1px solid #3A2C23`, `border-radius:8px`, `padding:9px 10px`, `background:rgba(0,0,0,.16)`, `min-height:78px`, `display:flex; flex-direction:column; gap:4px`. Hover `translateY(-2px)` + `border-color:#4A3A2E` (120ms). Contents: name (12.5px/600) with rating chip right (mono 10px/600, `padding:2px 5px`, `border-radius:5px`, fill `rgba(138,174,114,.16)`, text `#A8C795`); **region** (mono 10px ink-3); drive "45 min · 23 mi" (mono 10px ink-3, or `#9CCBD1` + `border-color:rgba(111,168,176,.3)` when beyond weekday range, e.g. "1h 48 · 78 mi · weekend only"); "◍ Open on map →" pinned to the bottom with `margin-top:auto`, turning tide-blue on hover.

Region — not location type — is the third line. Type told the user Blyth Beach is coast, which they knew; region tells them which direction they are driving and matches the Best Bet's own region line.

**Footer** (`padding:8px 14px`, 1px top border, `background:rgba(0,0,0,.16)`, mono 10.5px ink-3): sort statement, film-strip control (‹ › 24×22 buttons + "5 of 7 loaded"), and "See all N →" pushed right. **Arrows render only on `@media (hover:hover) and (pointer:fine)`** and never on the phone layout — touch swipes natively and the arrows would duplicate in the tightest space on screen.

**Collapsed state** — beyond the next two windows, cards collapse to the header alone (`.win.collapsed`, `padding:10px 14px`, when-text 13.5px), still stating time, verdict, best rating, count and badges. Six open windows would be 2,600px again; six with two open is ~1,500px, and any card opens on click.

**Unavailable days** — a single dashed row: "✈ Mon 3 – Tue 4 · away on business — 3 windows not generated" with "sun times still shown in the rail" and an action. Solo this reads "not generated"; with users it reads "muted, still generated".

**Doors** — two cards at the bottom (`display:flex; gap:8px`) to Regional planner (heatmap + full briefing) and Hot topics. Title 12.5px/600 with a mono arrow right; sub-line mono 10px ink-3.

### 5. Drilldown sheet

**Purpose** — browsing the whole loaded list for one window, with the filters that only matter while browsing.

Opened from "See all N →". Fixed overlay, scrim `rgba(8,6,5,.72)`; panel `left:50%; top:34px; transform:translateX(-50%)`, `width:min(880px, 100vw - 40px)`, `max-height:calc(100vh - 68px)`, `background:#181210`, `border:1px solid #4A3A2E`, `border-radius:13px`, `box-shadow:0 30px 80px rgba(0,0,0,.7)`, internal flex column.
- Header: window name + time (15.5px/700), meta "23 spots · best 4.0★" plus "· widened for browsing" only when its reach differs from the page lens, and a "Close · Esc" button right.
- Filter bar: three segmented controls — reach (inherited from the lens), rating floor (Any / 3★+ / 4★+), location type (Any / Coast / River & lake / Upland / Landmark).
- List: `display:grid; grid-template-columns:repeat(3,1fr); gap:8px` (2 columns under 620px), same spot cards, region line additionally carries the type word here because this is where the type control lives.
- Empty state: "Nothing matches — widen the reach or drop the rating floor."
- Footer: "Showing N of M loaded · ranked by rating, then drive" and the persistence statement.

### 6. Coming up

**Purpose** — the one question a window cannot answer: *when is the next one?* Dated events worth planning a trip around, next 90 days. Full markup in `Plan Window First.html`.

Row: `display:grid; grid-template-columns:112px 1fr auto; gap:14px; padding:11px 14px`, 1px bottom border. Date block (mono 11px, bold 12.5px lead), then title 13px/600 with a kind tag, sub-line 12px ink-2, right meta mono 10.5px ink-3. Past events `opacity:.45`.

Kind tags: **Almanac** (`rgba(111,168,176,.14)` / `#9CCBD1`) = fixed by orbital mechanics, plan months out. **Forecast** (`rgba(138,174,114,.14)` / `#A8C795`) = firms up ~3 days ahead.

The word "Almanac" survives only as a row tag. It was rejected as a tab name — librarian language nobody remembers.

**Rule: seasons live here permanently; Plan only ever carries tonight's delta.** This is what stops Plan re-growing.

---

## Interactions & Behavior

| Interaction | Behaviour |
|---|---|
| Tab click | Switch pane. Plan / Coming up / Map. Manage is admin-gated and unchanged. |
| Day rail | **Inert.** Read-out only. |
| Window "Open ▾ / Collapse ▴" | Toggle that window. Default open: the next two windows. |
| Spot hover (desktop) | After **140ms**, a peek panel opens below the card: fade + 5px rise, `opacity .15s`, `transform .18s cubic-bezier(.2,.7,.2,1)`. Closes 160ms after leaving the strip, 120ms after leaving the panel; Esc closes. Panel stays open while hovered. |
| Peek contents | 300px wide, `background:#2A2019`, `border:1px solid #4A3A2E`, `border-radius:11px`, `box-shadow:0 20px 48px rgba(0,0,0,.6)`, plus a rotated 11px arrow tethering it to the card. Header: name (13.5px/700) + window (mono 10px ink-3). Body: amber star row + rating + drive; italic Newsreader 12.5px *why* clause; two score bars — **Fiery Sky** (`linear-gradient(90deg,#B5A06A,#E0A542 45%,#C8452F)`) and **Golden Hour** (`linear-gradient(90deg,#6B6453,#C88E2E 45%,#F5C518)`), 5px tall, `border-radius:999px`. Footer: hint left, "◍ Open on map →" right. |
| Spot click (desktop) | Go to Map, centred on that spot, **carrying the window and lens**; map header states the window and offers "← Back to Plan". |
| Spot tap (phone) | No hover exists, so the first tap opens the same peek full-width (`calc(100% - 24px)`); its footer link goes to the map. |
| Film strip | Native swipe/trackpad; ‹ › advance two cards (desktop only); edge fades update on scroll; arrows disable at each end. |
| "See all N →" | Open drilldown for that window. Scrim click or Esc closes; type filter resets on close. |
| Window re-render | The peek node must live **outside** the re-rendered list container (cards render into an inner `#winlist`; the peek is appended to the outer `#wins`, which is the positioning context) or it is destroyed on every render. |

## State Management

```
reach          '30' | '60' | '120' | 'any'   — page lens; default = today's own reach
openWindows    Set<windowId>                 — default: next two
dRate          'any' | '3' | '4'             — drilldown rating floor
dLoc           'any' | 'coast' | 'river' | 'upland' | 'landmark'
dReach         inherits reach on open
dWin           window being drilled, or null
peekFor        spot name currently peeked, or null
```

**Persistence policy — persist taste, expire investigations.** Key `photocast.lens.v2`, payload `{reach, dRate, day}`.
- `dRate` (rating floor) is taste: persists indefinitely.
- `reach` is an investigation: restored **only if the stored `day` matches today**, so at the day roll the day's own reach takes back over. Marked "today only" in the UI while off-default.
- `dLoc` never persists — resets when the sheet closes.

Rationale: a filter still silently on three days later hides most of the app. That is the classic sticky-filter bug. Bump the key on any schema change so stale values cannot resurrect under new labels.

**Signal decay — stateless for the pilot.** Event copy states the *delta*, computed from data alone: "clearest in 11 nights", "top third of the season", "biggest tides of the month". "It is NLC season" is true for 62 nights and useful on one. A per-user exposure counter (demote strip → row → badge → silence as you are told repeatedly) is designed but **deliberately not implemented**: during a pilot every user must see the same page on the same night so bug reports are reproducible, and silence must never be ambiguous. If season fatigue appears, use a stateless threshold (only show when tonight is in the top third of the season so far), not a counter.

## Data Requirements

Per **window**: local datetime, kind (sunrise/sunset), verdict, best rating, count of spots within each reach tier, badge list, Best Bet {region, headline, body, optional location}, optional Also Good (same window only), tide {state, direction, nearest HW/LW time, offset from window, range, range-vs-average, sea state, curve points, marker position}, optional snow {depth, elevation line, age, sites affected}, spot list.

Per **spot**: name, rating, drive time, drive miles, region, location type, beyond-weekday-reach flag, *why* clause, Fiery Sky score, Golden Hour score.

Per **day**: date, sun times, verdict, availability, that day's reach default (weekday vs weekend).

**Scoring is per site and window, not per user** — see `Unit Economics.html`. One generation serves every user; a user's home address and calendar only decide what they are *shown* (drive times cached per user, reach as a lens, away days muted). Keep briefing prose **regional** and personalise by *selection*, not generation — per-user prose is the one thing that converts a fixed cost into a marginal one. New regions are the real step cost.

## Responsive Behaviour

Phone breakpoint is demonstrated by a `.mob` class on the wrapper (`max-width:390px`); implement as real media queries.

- Masthead: hide strapline and ghost buttons.
- Rail: `overflow-x:auto`, cells `flex:0 0 150px`, scrollbar hidden.
- Tabs and lens bar: horizontally scrollable, `flex-wrap:nowrap`; keep group labels visible at 9px (do not hide them — the control would be unlabelled); hide the right-hand count.
- Window header: rule hidden; meta and badges each take a full row; the expand button pushes right.
- Attribute rows: single column, sparkline hidden, facts stack.
- Narrative: single column.
- Spot strip: `flex:0 0 72%` (~1.4 cards visible); arrows hidden.
- Promoted strip: title takes its own row; figures go 2-up; the *why* clause spans and left-aligns.
- Doors: stack.

## Design Tokens

```css
--bg:#181210          /* app frame */        --surface:#221A15      /* raised */
--surface-light:#2A2019 /* peek */           --panel:#1E1712        /* cards */
--border:#3A2C23                             --border-light:#4A3A2E
--ink:#F2E7D3                                --ink-2:rgba(242,231,211,.66)
--ink-3:rgba(242,231,211,.42)                page: #0e0b09
--go:#8AAE72          /* worth it */         --marginal:#E0A542     /* maybe */
--standdown:#C8452F   /* poor */             --tide:#6FA8B0
--home:#C9A24B        /* close-to-home, active tab */
--nlc:#9B8FD4                                --snow:#B7CBD8
```
Badge text variants: `#A8C795` go · `#EFC377` maybe · `#E58C7A` poor · `#9CCBD1` tide · `#BFB6E8` nlc · `#C6D8E3` snow · `#EBD9A8` active segment.

**Type** — IBM Plex Sans (400/500/600/700) for UI; IBM Plex Mono (400/500/600) for all data, times, counts and kickers; Newsreader (400/500 + italic) for narrative headlines and *why* clauses. Sizes in use: 20 / 15.5 / 14.5 / 13.5 / 12.5 / 12 / 11 / 10.5 / 10 / 9.5 / 9px. Mono kickers: 10px / 600 / `letter-spacing:.1–.11em` / uppercase.

**Radii** — 13 sheet · 12 app frame · 11 cards & peek · 9 rail cells & doors · 8 spot cards, rows, segmented controls · 7 ghost buttons · 5 chips · 999px badges.

**Spacing** — 18px shell gutter, 14px card gutter, 8px between cards, 10px between stacked blocks, 6–7px inside rows.

**Motion** — 120ms card hover; 140ms peek delay; 150ms opacity / 180ms `cubic-bezier(.2,.7,.2,1)` transform on the peek; 160/120ms close delays; 200ms width transitions on bars.

**Shadows** — peek `0 20px 48px rgba(0,0,0,.6)`; sheet `0 30px 80px rgba(0,0,0,.7)`; phone frame `0 0 0 8px #241c17, 0 26px 60px rgba(0,0,0,.6)`.

## Assets

None. No images, icon fonts or SVG illustrations. The logo is a CSS conic gradient; the tide curves are inline SVG paths; all glyphs are Unicode (◎ ○ ◍ ≈ ✦ ▣ ❄ ✈ ↑ ↓ ‹ › ★ ☆ ⌂ ⚙). Fonts load from Google Fonts.

## Files

| File | What it is |
|---|---|
`Plan Window First v2.html` | **The design of record.** Full Plan tab, film strip, drilldown, peek, phone layout, verdict colours, plain-language reach. Demo buttons top; annotations bottom — strip both. |
`Plan Window First.html` | v1. Retained for the **Coming up** tab in full, which v2 stubs. |
`Plan Length Options.html` | Where it started: window-first agenda vs a four-tab split, side by side, with heights and trade-offs. Context for *why*. |
`Signal Decay.html` | The exposure-decay model (strip → row → badge → silence). **Not for the pilot** — reference for after. |
`Unit Economics.html` | Cost model: fixed vs marginal, what scales per user, where the model breaks. Drives the "generate once, personalise by selection" rule. |
`Adversarial Review.html` | Hostile self-review: ten charges with prosecution, defence and verdict, plus the pre-pilot cut list. Read before adding anything. |
`Index.html` | Hub linking all of the above. |

## Build Order Suggested

1. Shell: masthead, inert day rail, tabs.
2. Window card with header, verdict badges, Best Bet — static data, one window.
3. Spot film strip (get the scroll geometry right first: gutter on the wrapper, no smooth behaviour).
4. Attribute rows (tide, then snow).
5. Lens bar + reach filtering + persistence policy.
6. Collapse/expand and the six-window case.
7. Hover peek and click-to-map.
8. Drilldown sheet.
9. Coming up.
10. Responsive pass.

## Known Traps (all of these bit during the design)

- `border-bottom:none` on tabs resets bottom width to 3px; use `border-bottom-width:0`.
- Re-rendering the card list destroys a peek node parented inside it.
- Horizontal padding on a snapping scroll container is eaten by the first snap point.
- `scroll-behavior:smooth` plus scroll-snap = programmatic scrolling silently does nothing.
- A fixed 4-column grid leaves ragged empty cells whenever the filtered list is short; the strip avoids this, but any grid fallback needs `auto-fit`.
- Footers that claim a sort order must actually sort; use one shared comparator.
- Footers that claim a count must state what is drawn ("showing top 4 of 7"), or the number contradicts the cards.
- Labels must be grouped with their controls or flex-wrap orphans them.
