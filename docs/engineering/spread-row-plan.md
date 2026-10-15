# Plan tab — the Spread row: pool label and visible counts (2026-10-15)

**Source**: the design bundle vendored at `docs/design/spread-row/` — `README.md` (the spec),
`CLAUDE_CODE_PROMPT.md` (the designer's working order), `Plan Tide Summary.html` + `plan-tide-v6.js`
(the prototype), three screenshots. `docs/design/spread-row/VENDORING.md` records what was copied
and what was not. Read the spec before any phase; **this document is the *port* plan**: what the
codebase already has, what is genuinely new, where the spec and the code disagree on purpose, and
how the work cuts into single-session phases for **Sonnet** sessions.

**The one-sentence version.** The Spread row on every Plan card already draws the right picture
(a five-band histogram over the reach-gated pool, a sixth hatched bar for the unrated remainder,
text instead of bars below the minimum-sample gate). Two things it does not say out loud: **which
pool** the bars were counted over (the reach tier is in the lens bar, three cards away from the row
that depends on it) and **how many** are in each band (pointer-only, in the `title`). This increment
prints both — a second label line `(within 45m)` / `(within 1h30)` / `(within 2h30)` / `(any
drive)` and a count under every bar — and changes nothing else on the card.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (prosecutor lenses + one refuter per charge, all read-only, run in the FOREGROUND so their
results return to the session that launched them) → fix survivors → browser/fixture measurement (§7)
→ changelog.d entry → commit → push → PR → CI and Codex green → merge. Frontend gate before any
push: `npm run lint && npm test && npm audit --audit-level=high && npm run build`, gated on exit
codes. Never tag; never run `release.sh`. Paste the relevant section of THIS plan and the spec into
every review agent's prompt — an agent cannot open a path.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *spread*, *histogram*,
*card*, *reach*. Checked 2026-10-15 at plan time: three open PRs (#1132 a jsdom bump, #843 and #815
docs), none touching the Plan card.

---

## §0 Status

**Status: S0 written 2026-10-15 against `origin/main` at `dc60c563` (#1177, v2.27.4).** Phases
S1–S3 pending. Owner decisions this plan needs are in §6; **none blocks any phase** — §5 takes the
defaults and every phase builds on them unless the owner says otherwise on the phase PR.

Phase log (S0 creates the first row; every phase appends its own in the same commit as its code —
the commit column names the PR once it lands):

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| S0 | `claude/spread-row-enhancement-7c99de` | #1180 | 2026-10-15 | Plan + bundle vendored at `docs/design/spread-row/`. No code. Codex reviewed the plan twice and found four P1s, all accepted: round 1 — the qualifier must key on the pool-wide `facts.withinReach` (a mixed pool would otherwise wear "(within 45m)" over unmeasured spots — §2 #5), and the spec's 177px worst-case card does not exist here (the grid is 116–157px at six of seven geometries — §1 #7, the stacked state §2 #2, §4 #10, §6 Q4); round 2 — the spoken sentence must name the tier the label names (§6 Q1 decided yes; `spokenPoolPhrase`, the tooltip unchanged) and the muted token is under AA on the well (zero counts quietened by weight, §2 #4, §4 #6); round 3 — the wrapper's children fall outside `.wf-hc-pls > span`, so they need their own `display: flex` or the stacked histogram sits left (§2 #2 ⚠️, S2 tasks 1 and 6). |

---

## §1 What the codebase already has — corrections to the spec

Numbered so a phase log can cite them. Every file:line was read against `dc60c563`; **re-verify
before editing**, code moves.

### 1. The row, the pool and the gate all exist; the spec describes a prototype without two of them

The spec says to recreate the row "in the existing Plan card component, e.g. `HeatmapGrid.jsx`,
`DailyBriefing.jsx`". Neither is the Plan card: `DailyBriefing.jsx` died in v1 retirement D2, and
`HeatmapGrid` is the popup's grid. **The card is `components/WindowFirstHeatStrip.jsx`** (the
value grid at `:1294–1388`, the Spread row at `:1323–1373`), its maths is
`utils/windowFirstSpread.js` (`buildSpread`, `spreadBars`, `unratedBar`, `spreadRowState`,
`spreadTitle`), and its CSS is `index.css:1941–2190` (`.wf-hc-pls`, `.wf-hc-k`, `.wf-hc-pv`,
`.wf-hc-hist`, `.wf-hc-hist i`, `.wf-hc-hist .wf-hc-hist-unrated`, `.wf-hc-hist-note`).

The pool is already the one the spec names — `card.pool`, "scope ∩ named ∩ driveTime ≤ reach", before
the rating floor (`utils/windowFirstCards.js`, plan-matrix §4 A10). Nothing new is derived for the
counts: `spreadBars(spread)` already carries `count` per band and `unratedBar(spread)` the
remainder's.

Two things the prototype does not have, and the codebase keeps (plan-matrix §4 A27, owner decision
2026-09-29):

- **The minimum-sample gate.** Below `rated >= 5 && rated >= total·0.5` the row prints `none rated
  yet` or the compact `N/M rated` instead of bars (`spreadRowState`). The spec's counts are a
  bars-mode feature; the text state keeps its text. The NEW label line applies in both states.
- **The sixth hatched bar.** The unrated remainder is drawn to the LEFT of 1★, hatched, with a wider
  gap, on the bands' own scale, with its own 4px floor. It gets a count under it like the five bands
  (its count IS the "not yet rated" figure the tooltip and the spoken sentence already name), and it
  takes part in the per-card column width (§2 #3).

### 2. The reach tier is already on the card — two fields, both needed

`buildWindowCards` publishes `card.reachLimitMinutes` (the active tier's threshold, null under "Any")
and `card.reachMeasured` (whether any spot in the window has a measured drive — `windowFirstCards.js:752–757`),
forwarded by `windowFirstStrip.js:163–166` for H2's "beyond the tier" line. The label reads these;
no new prop reaches the strip and nothing is read from `localStorage` or the lens bar's own state.

### 3. The tier's words are derived, never authored

`REACH_TIERS` (`utils/reachLens.js:50–54`) derives each tier's `label` from `formatDriveDuration`
(`utils/briefingDisplay.js:140–146` — `45 min`, `1h 30min`, `2h 30min`, `Any`), and
`WindowFirstHeatStrip.jsx:171` records why: "one formatter derives the lens bar's chip labels, so
'45 min' here and there agree". The prototype's `RSHORT = {'45':'45m','90':'1h30','150':'2h30'}` is
a hand table keyed by id. The port derives the compact form from `limitMinutes` the same way the
long form is derived (§2 #1), so a retuned tier cannot leave the row naming a threshold the lens bar
no longer offers.

### 4. The count colours are tokens this stylesheet already has

The spec's `--ink-2` / `--ink-3` are the prototype's own names. On the card, `.wf-hc-k` (the
label) is `--color-plex-text-secondary` (rgba(242,231,211,.66)) and the muted ink is
`--color-plex-text-muted` (.42). The counts use those two (§2 #4) — no new token, and no hex typed
from the spec (plan-matrix §3 rule 9: tokens in `@theme static`).

### 5. There is no "sideways" card layout here

The spec's "in the sideways card layout the `.pls` grid rows are `21px 27px 32px 1fr`" describes
`Plan Tide Summary.html:202`, a prototype toolbar mode. `grep -n sideways frontend/src/index.css`
finds nothing; the card's rows take a shared `min-height: 20px` (`.wf-hc-pls > span`) so every
card's rows land on the same baselines. The 27px figure is applied as the Spread row's own minimum
height on its wrapper (§2 #2), which keeps the cross-card alignment the shared minimum exists for.

### 6. The grid is `aria-hidden`; the card speaks through one sentence

`.wf-hc-pls` is `aria-hidden="true"` (`WindowFirstHeatStrip.jsx:1294`) and every fact a reader acts
on is in the card's accessible name (`:1129–1195`: `poolPhrase`, `unratedPhrase`, `facts.state.spoken`).
So the new label line and the printed counts reach a screen reader only through that sentence —
§2 #5 says what changes there, and what deliberately does not.

### 7. The card is narrower than the spec's own worst case — at every breakpoint the spec measured

The spec sizes its worst case on "the narrowest card (iPad, `.pls` 177px wide)": five 17px columns
plus gaps, 93px, "fits beside the label". This card grid is narrower than that in most of its
real geometries, measured in Chromium with the real faces (`index.css:2016–2021`, the H1 note):
the VALUE column alone is 70.3px at a 320px viewport (two columns below 640px, `index.css:2925`),
90.3 at 360, 97.8 at 375, 79.3 at 640 (four columns), 111.3 at 768, 85.3 at a docked 1024 and
175.3 at an undocked one — so the whole value grid (label column ≈ 40px + 6px gap + value column)
is roughly 116 / 136 / 144 / 125 / 157 / 131 / 221px. The six-column histogram with the well and
the hatched bar's gap is 74px at one-character counts (`6×9 + 5×2 + 4 + 6`), 82px at two, 114 at
three and 122 at four; the longest qualifier, `(WITHIN 2H30)`, is ≈73px at 8.5px mono with .06em
tracking. Side by side with the spec's 8px gap that is 155px at the SMALLEST — wider than every
geometry above except the undocked desktop and (barely) 768px. The spec's layout therefore cannot
be applied as a single rule; §2 #2 adds the stacked state and §4 #10 records the disagreement. (A
Codex review of the plan PR, #1180, found this; the plan's first cut had carried the spec's 177px
figure unexamined.)

---

## §2 What is genuinely new

1. **A compact drive-duration formatter** beside the long one — `formatDriveDurationCompact(minutes)`
   in `utils/briefingDisplay.js`: `45 → "45m"`, `90 → "1h30"`, `150 → "2h30"`, `60 → "1h"`,
   `null → null`. `REACH_TIERS` gains `shortLabel` derived from it (`'any'`'s is `null` — the row
   has its own words for that tier). One formatter, two forms, both derived.
2. **The row wrapper.** `.wf-hc-sprow`, a grid child spanning both columns (`grid-column: 1 / -1`),
   a flex row `justify-content: space-between; gap: 8px; min-height: 27px` (§1 #5), holding the
   two-line label (`.wf-hc-k.wf-hc-sk`: `Spread` + `<small>` qualifier, flex column, `gap: 1px`,
   `line-height: 1.15`, 8.5px/600/uppercase, `Spread` at `.1em`, the qualifier at `.06em` and
   `white-space: nowrap`) and the value cell (`.wf-hc-pv`, `overflow: hidden` kept as the safety
   net). ⚠️ **The wrapper's two children are no longer direct children of the grid**, so the
   `.wf-hc-pls > span` rule (`display: flex; align-items: center; min-width: 0; min-height: 20px`)
   no longer reaches them, and the base `.wf-hc-pv` rule sets only `justify-content`,
   `flex-wrap`, `gap` and `overflow` — never `display`. Both children therefore need their own
   `display: flex; align-items: center; min-width: 0` on a `.wf-hc-sprow > span` rule (the
   prototype's own `.pls .sprow > span { display: flex; min-width: 0 }`), written at (0,2,1) or
   better so it is not shadowed, or the histogram sits at the LEFT of its line in the stacked state
   and the label loses its vertical centring (a Codex review of #1180 caught the omission). The wrapper is the whole point of the spec's own regression note: a longer label in the
   shared `auto` column widens it and pushes the `5★ <name>` row below out of shape.
   **Two states, decided by the CARD's width, never by content** (§1 #7): side by side where the
   card is wide enough, and STACKED — the label line first, the histogram on its own line below,
   right-aligned (`margin-left: auto`) — where it is not. The switch is a container query on the
   card (`.wf-hc { container-type: inline-size; container-name: wf-card }`, the precedent is
   `@container wf-tabrow` at `index.css:12650`), threshold `wf-card (max-width: 170px)` on the
   card's content box: sized so two-character counts (82px) beside the longest qualifier (73px)
   and the 8px gap, 163px, fit side by side at 171px and above, and stack below it. A card-width
   rule, not `flex-wrap`, because every card in a grid row shares a width and so makes the SAME
   decision — a content-driven wrap would let two neighbours' Best rows land on different
   baselines, the cross-card alignment the shared `min-height` exists for. Three-character counts
   (114px → 195px side by side) still overflow between 171 and ~195px and the value cell's
   `overflow: hidden` clips them; that is a hundred-plus rated places in one band within reach,
   accepted and recorded (§6 Q4) rather than widened for. Stacked, the row is the label's two
   lines plus the well: ≈ 20 + 2 + 29px; cards in a stacked row grow together.
   ⚠️ `container-type: inline-size` applies layout containment, which makes the card a stacking
   context; the pick legend (`BEST BET` / `ALSO GOOD`) and the card's hover `transform` must be
   checked still to paint as they do today — S2 verifies it in the fixture, both on the hovered
   and the picked card.
3. **Counts under the bars, one column width per card.** Each bar becomes a column
   (`.wf-hc-hb`: flex column, `justify-content: flex-end; gap: 2px; width: var(--cw)`), the bar an
   `<i>` (7px wide, radius 1px — up from 5px, §4 #1), the count an `<em>` (mono, 9px/600,
   `line-height: 1`, `letter-spacing: -.06em`). `--cw = clamp(9px, chars × 5.2px, 17px)` where
   `chars` is the longest formatted count on THAT card across all six columns (the hatched bar's
   count included); when `chars > 3` the histogram takes `.sm` (7.5px, `-.1em`). Set as an inline
   custom property on `.wf-hc-hist` (the card already sets `--c`/`--r` inline; a per-card number is
   what custom properties are for). Three pure helpers in `windowFirstSpread.js`, unit-tested:
   `formatSpreadCount(n)` (`< 1000` raw; `< 9950` one decimal + `k`, trailing `.0` dropped; else
   rounded `k`), `spreadColumnWidthPx(chars)` (the clamp, with the three constants named) and
   `spreadCountLayout(bars, unratedBar)` → `{chars, widthPx, small}`.
4. **Count colour.** Every count, zero included, in `--color-plex-text-secondary` (§1 #4). A zero
   count is quietened by WEIGHT (400 against the others' 600), never by a lighter ink: the spec's
   `--ink-3` at .6 maps to this stylesheet's `--color-plex-text-muted` (rgba(242,231,211,.42)),
   which composites over the well (`--color-heat-sea`, #13100e) at about 3.55:1 — under the 4.5:1
   AA asks of 9px text, and further under at the `.sm` 7.5px (a Codex review of #1180 measured it;
   the plan's first cut had assigned the muted token). S2 measures the secondary ink on the well
   at both sizes (§7); if 7.5px/400 fails there, the zero count keeps 600 and the distinction is
   the figure alone.
5. **The qualifier.** `spreadQualifier({reachLimitMinutes, withinReach})` in
   `windowFirstSpread.js` → `"within 45m"` | `"within 1h30"` | `"within 2h30"` | `"any drive"` |
   `null`, rendered as `(…)`. **`withinReach` is the strip's EXISTING per-card `facts.withinReach`**
   (`WindowFirstHeatStrip.jsx:693`: `poolWithinReach(pool)` — every spot in the pool has a measured
   drive — or `card.reachMeasured` for an empty pool), the very predicate the tooltip's and the
   spoken sentence's "within reach" already read, so the three surfaces agree by construction.
   `null` — no second line at all — when a tier is active and `withinReach` is false: an unknown
   drive passes every tier (plan-matrix §2.5 rule 1), so a pool holding even one such spot was not
   wholly gated at 45 minutes and the label would be counting places it never measured. ⚠️ Not
   `card.reachMeasured` (the plan's first cut): that is true as soon as ANY spot in the window has a
   drive, so a mixed pool — some measured, some unknown — would wear "(within 45m)" over bars that
   count the unknown ones too, and the row would disagree with its own tooltip (a Codex review of
   #1180 caught it). Under "Any" the qualifier is `any drive` whatever `withinReach` says — it
   claims no gate. **The helper answers in TWO forms**: `{text: "within 45m", spoken: "within 45
   min"}` — the compact form for the label, the lens bar's own long form (`REACH_TIERS[].label`,
   lower-cased) for the spoken sentence, where "45m" would be voiced as a letter — and
   `{text: "any drive", spoken: "any drive"}`; `null` is null for both.
   **The spoken sentence names the same pool the label names** (§6 Q1, decided on the plan PR —
   a Codex review of #1180 pointed out that under Any with measured drives the card would SHOW
   "any drive" and SAY "within reach", and that under a finite tier the threshold the sighted
   reader gets would never reach a screen reader): `spokenPoolPhrase(total, qualifier)` in
   `windowFirstSpread.js` → `"7 locations within 45 min"` / `"7 locations, any drive"` /
   `"7 locations"` (null qualifier — exactly today's bare clause), with `poolPhrase`'s own plural
   rule; the strip's `spokenPool` reads it in place of `poolPhrase` in BOTH the bars and the text
   branches. The TOOLTIP keeps `poolPhrase(total, withinReach)` — "N locations within reach — …"
   — unchanged: the spec says keep it, and it sits beside the label that already names the tier,
   so a pointer reader sees no mismatch.
6. **The spoken sentence gains the band counts in bars mode** — the counts are now VISIBLE text, so
   a reader of the accessible name must get the same figures (SC 1.3.1, the principle
   `unratedPhrase`'s own export comment states). `spreadBandsSpoken(spread)` in
   `windowFirstSpread.js`: the NON-ZERO bands only, highest first, worded (`"2 at 5 stars, 3 at 3
   stars"` — never `★`, which a screen reader may skip or name), appended after `poolPhrase` and
   before `unratedPhrase` in the bars-mode branch of `spokenPool` (`WindowFirstHeatStrip.jsx:1148`).
   Zero bands are omitted so a six-card strip stays scannable; the tooltip keeps all five.
   Below the gate nothing changes: there are no visible counts to match.
7. **The tooltip is kept exactly** (`spreadTitle` unchanged — the spec says keep it, and its
   per-band sentence is now the full form of what the counts show).

---

## §3 Phases

Sequential, one Sonnet session each, one branch each, `claude/spread-row-s<N>-<slug>`. Each phase
re-verifies every file:line it touches against `origin/main` before editing. **S1 and S2 touch the
same two files** (`windowFirstSpread.js`, its test) so they are NOT run in parallel.

### S1 — the maths, no visible change

Files: `utils/briefingDisplay.js`, `utils/reachLens.js`, `utils/windowFirstSpread.js`, and their
tests (`test/briefingDisplay.test.js` or wherever `formatDriveDuration` is pinned — find it;
`test/reachLens.test.js`; `test/windowFirstSpread.test.js`).

1. `formatDriveDurationCompact` (§2 #1) beside `formatDriveDuration`, with the same null rule.
   Pin all four tiers' outputs AND that it agrees with the long form on the hours/minutes split
   (one input table, both formatters, so the two cannot drift).
2. `REACH_TIERS[].shortLabel` derived from it; `'any'` → `null`. Pin that every tier's `shortLabel`
   is the compact form of its own `limitMinutes` (iterate `REACH_TIERS`, never a hand table).
3. `formatSpreadCount`, `spreadColumnWidthPx`, `spreadCountLayout` (§2 #3). Pin the spec's own
   examples (`999`, `1000 → "1k"`, `1200 → "1.2k"`, `9949 → "9.9k"`, `9950 → "10k"`,
   `12345 → "12k"`), the clamp's three regimes (1 char → 9px, 3 chars → 15.6px, 4+ → 17px),
   `small` flipping at exactly 4 chars, and that the hatched bar's count takes part in `chars`
   (a pool with 1,200 unrated and single-digit bands must answer `chars: 4`).
4. `spreadQualifier` (§2 #5): the four tiers with `withinReach: true` → `{text, spoken}` pairs
   (`within 45m` / `within 45 min`, `within 1h30` / `within 1h 30min`, `within 2h30` / `within 2h
   30min`, `any drive` / `any drive`); `{45, withinReach: false}` → `null`;
   `{null, withinReach: false}` → the any pair; `{null, withinReach: true}` → the any pair. Pin
   that `text` is built from `shortLabel` and `spoken` from `label`, not a table (mutate a tier's
   `limitMinutes` in the test's own copy and assert both follow). The helper takes a plain boolean;
   it is the CALLER (S2) that must pass the strip's pool-wide `facts.withinReach`, and S2 pins that
   with a mixed pool. Then `spokenPoolPhrase(total, qualifier)` (§2 #5): `"7 locations within 45
   min"`, `"7 locations, any drive"`, `"7 locations"` for null, `"1 location …"` singular — and
   a test that its null form equals `poolPhrase(total, false)` exactly, so the sentence for a
   mixed pool is byte-for-byte what it is today.
5. `spreadBandsSpoken` (§2 #6): non-zero bands only, highest first, `stars` worded, `""` for an
   all-zero spread; and a test that the words it uses are the same five the tooltip's bands use,
   just filtered and reworded (drive both from one `buildSpread`).
6. No component change. `npm test` must leave `WindowFirstHeatStrip.test.jsx` byte-for-byte green.

Acceptance: the five helpers exist, exported, documented in the module's own header voice (what it
is for, why the hatched bar counts toward `chars`, why `null` for an unmeasured tier); the gate
passes; `changelog.d/20261015-spread-row-s1-helpers.md` (`### Changed`). Review lenses: runtime
behaviour, test quality (against `docs/engineering/frontend-test-standards.md`), project
conventions (CLAUDE.md "Backend-heavy" — these are the already-licensed filter/map class over the
A10/A27 pool, not a new member; say so in the header).

### S2 — the row

Files: `components/WindowFirstHeatStrip.jsx` (the Spread row and `spokenPool` only),
`index.css` (the value-grid block only), `test/WindowFirstHeatStrip.test.jsx`.

1. Wrap the Spread row in `.wf-hc-sprow` (§2 #2), with the `.wf-hc-sprow > span` rule restoring
   what `.wf-hc-pls > span` no longer gives its two children (§2 #2's ⚠️). Both states — bars and
   the `.wf-hc-hist-note` text — live inside the wrapper, so the label line is drawn in both. Pin that `.wf-hc-k` for
   "Spread" is no longer a direct grid child (the regression the spec names: measure that the
   rating pill's column width is unchanged with and without the qualifier — `getBoundingClientRect`
   in jsdom reads 0, so assert it by the cascade: the `auto` column's only remaining children are
   the time, the rating pill and the Best label).
2. The qualifier `<small>` from `spreadQualifier({reachLimitMinutes: card.reachLimitMinutes,
   withinReach})` where `withinReach` is the card's EXISTING `facts.withinReach` (§2 #5) — never
   `card.reachMeasured` directly; absent (no element, not an empty one) when it answers `null`.
   `data-testid="wf-heat-spread-qualifier"`. Pin all four tier strings and the `any drive` cases
   through the rendered row, AND the mixed pool: a 45-minute tier, `reachMeasured: true`, a pool
   with one spot whose `driveMinutes` is null → no qualifier, while the same pool with every drive
   measured → `(within 45m)`. That pair is the test a `reachMeasured` mutant fails.
3. Counts: each `<i>` becomes a `.wf-hc-hb` column with its `<em>` count
   (`data-testid="wf-heat-spread-count"`, `data-star`; the hatched bar's `wf-heat-spread-count-unrated`),
   `--cw` and `.sm` from `spreadCountLayout`. Keep every existing `data-testid` on the bars so the
   A27 tests keep passing unchanged where they can; pin: counts print under the right bars; a zero
   count carries the muted class; `--cw` is `9px` for a single-digit card and `17px` for a `1.2k`
   card; `.sm` only when some count is 4 chars; the hatched bar's count is the unrated remainder.
4. CSS, in the existing block with the existing voice (every rule there explains itself): `.wf-hc-sprow`,
   `.wf-hc-sk`, `.wf-hc-hb`, `.wf-hc-hist em`, `.wf-hc-hist.sm em`, the zero-count ink, and the
   well's height raised to hold a 25px column plus its padding. ⚠️ The specificity trap the block
   already documents: `.wf-hc-hist i` is (0,1,1) and `.wf-hc-pls > span` is (0,1,1); any override of
   a `.wf-hc-pls` child or of the bar must be written at least as specific. Bar width 7px moves
   from the unrated rule to the shared `i` rule (the hatched bar keeps its `margin-right` and its
   hatch). `.wf-hc-hist` keeps `cursor: help` — the tooltip stays (§2 #7).
5. `spokenPool` reads `spokenPoolPhrase(poolTotal, qualifier)` in place of `poolPhrase` in both
   branches (§2 #5) and gains `spreadBandsSpoken` in bars mode (§2 #6). Pin the full accessible
   name on a bars-mode card under a measured 45-minute tier (the existing `getByRole('button',
   {name: …})` test at `:620` is the model — its expected string changes, and that change is the
   point), under Any with measured drives ("…, any drive, …" — never "within reach"), on a mixed
   pool (the bare "7 locations", exactly today's words), and that below the gate only the pool
   clause moves. The tooltip's expected strings do not change — pin one to prove it.
6. The stacked state (§2 #2): `container-type: inline-size; container-name: wf-card` on `.wf-hc`
   and `@container wf-card (max-width: 170px) { .wf-hc-sprow { flex-wrap: wrap } .wf-hc-sprow >
   .wf-hc-pv { margin-left: auto; flex-basis: 100% } }` (or the equivalent that puts the histogram
   on its own right-aligned line) — ON TOP OF the base `.wf-hc-sprow > span { display: flex;
   align-items: center; min-width: 0 }` rule from task 1, since the nested `.wf-hc-pv` keeps its
   `justify-content: flex-end` only while it IS a flex container (§2 #2's ⚠️). Pin by the cascade
   in jsdom (the computed `display` of the nested value cell is `flex`) and by geometry in the §7
   fixture (the histogram's right edge meets the cell's right edge in the stacked state). No separate markup, no `useIsMobile` branch — the same DOM at
   every width, which is what the spec asks. jsdom has no layout, so the switch is proven in the
   §7 fixture at every width in the table, and the pick legend and hover transform are checked
   there too (the containment stacking-context note in §2 #2).

Acceptance: the gate passes; §7 measured and recorded in this plan's phase log; the three
screenshots in `docs/design/spread-row/screenshots/` matched at desktop by eye (a headless Chromium
screenshot of the fixture, attached to the PR); `changelog.d/20261015-spread-row-s2-counts-and-label.md`
(`### Changed`). Review lenses: runtime behaviour, CSS/tokens (including the AA figures in §7),
test quality, accessibility (the spoken sentence; nothing new is pointer-only), project
conventions, and "what this makes harder later" (the wrapper is now the one place a seventh column
would go).

### S3 — docs sweep and the plan-matrix amendment

Files: this plan (§0, phase log, §4 count, §7 table), `docs/engineering/plan-matrix-plan.md` §4
(a new **A28** row — the label and counts, citing this plan), CLAUDE.md's Plan tab bullet (the
spread histogram ⚠️ gains one sentence: the row names its pool and prints its counts; the
qualifier is silent for an unmeasured tier), `docs/design/spread-row/VENDORING.md`'s final count.
No code. One Sonnet session; a docs-only PR (CI's docs-only detector skips the suites).

---

## §4 Disagreements with the spec, on purpose

| # | spec | here | why |
|---|---|---|---|
| 1 | Bars 7px, no well, empty band `rgba(242,231,211,.13)` | Bars 7px **in the existing dark well** (`--color-heat-sea`, padded), empty band stays `rgba(242,231,211,0.40)` | The well is where every bar's contrast was measured (SC 1.4.11, the stylesheet's own table); the empty band's .40 clears 3:1 against it where .13 would not. The width follows the spec; the ground does not. |
| 2 | Five columns | **Six** — the hatched unrated bar keeps its place to the LEFT of 1★, with a count | A27 Part 2 (2026-09-29): the remainder is a real fact the card must disclose; its count is the "not yet rated" figure the tooltip already states. It takes part in `chars`. |
| 3 | Bars for any pool | The minimum-sample gate stands: below it the row prints text, with the new label line above | A27 Part 1, owner decision. A count under a bar drawn from one rated spot in 31 would make the noise look like data twice over. |
| 4 | The label "follows the drive/reach filter" always | **Silent** (no second line) when a tier is active and the card's pool-wide `facts.withinReach` is false — any spot without a measured drive | CLAUDE.md's rule for six existing surfaces: "within reach" is claimable only where a drive time existed to gate on, and the tooltip already reads this exact predicate (`poolWithinReach`), so the label cannot disagree with it. "(any drive)" is kept under Any for every reader — it names no gate. |
| 5 | `RSHORT` hand table keyed by tier id | `shortLabel` derived from `limitMinutes` by `formatDriveDurationCompact` | §1 #3 — one formatter, two forms; a retuned tier cannot strand the label. |
| 6 | `--ink-2` counts, `--ink-3` at `.6` for zero | `--color-plex-text-secondary` for every count; a zero count at weight 400 instead of a lighter ink | §1 #4, §2 #4 — the muted token is ~3.55:1 on the well, under AA for 9px text. S2 measures the secondary ink at 9 and 7.5px. |
| 7 | "sideways" layout rows `21px 27px 32px 1fr` | `min-height: 27px` on the wrapper; no layout mode | §1 #5 — the layout does not exist here; the row's own minimum keeps cross-card alignment. |
| 8 | The grid's label is the only place the pool is named | The spoken sentence ALSO names the tier ("7 locations within 45 min" / "7 locations, any drive") and gains the non-zero band counts in bars mode; the tooltip keeps "within reach" | §1 #6, §2 #5–#6 — the grid is `aria-hidden`; a visible label and visible counts need an accessible equivalent, and "within reach" against a visible "any drive" is a contradiction (§6 Q1, decided). |
| 9 | Recreate in `HeatmapGrid.jsx` / `DailyBriefing.jsx` | `WindowFirstHeatStrip.jsx` | §1 #1 — the Plan card lives there; the named files are the popup's grid and a deleted component. |
| 10 | One side-by-side layout everywhere; worst case "iPad, `.pls` 177px" | A second, STACKED state (label line, then the histogram right-aligned on its own line) below a 170px card width, by container query | §1 #7 — this grid is 116–157px wide at six of its seven measured geometries; side by side the row needs 155px at one-character counts. A card-width rule, not a content wrap, so neighbours stay aligned. |

---

## §5 Decisions taken at plan time (defaults every phase builds on)

1. The hatched bar is a counted column (§4 #2). A reviewer who argues it should be countless is
   arguing with A27 — point them at the plan-matrix row.
2. `--cw` is an inline custom property, not a class per width: the value is continuous (one of
   ~nine) and the card already carries `--c`/`--r` the same way.
3. The spoken sentence names only non-zero bands (§2 #6). Five clauses per card across six cards
   would be thirty, most of them "0 at N stars".
4. The qualifier string is parenthesised in the markup, not by CSS `::before`, so a text-content
   assertion reads what a sighted reader sees — and so a contrast sweep that walks text nodes sees
   it too (`contrast-sweep-misses-pseudo-elements`).

---

## §6 Open owner questions — none blocks a phase

- **Q1 — DECIDED on the plan PR (2026-10-15).** The SPOKEN sentence names the tier's minutes
  ("7 locations within 45 min", "7 locations, any drive") because the visible label now does and
  the grid is `aria-hidden` (§2 #5); the TOOLTIP keeps "within reach", as the spec asks. The
  default had been "no"; a Codex review showed the Any-tier contradiction it would have shipped.
- **Q2.** The `.sm` 7.5px count type is below any size this stylesheet otherwise uses. Default:
  **ship it as specified** (the spec says so, and it fires only at a 4-character count — a
  catalogue of a thousand places within reach) and record the measured contrast in §7.
- **Q3.** Under an away origin the label copy is unchanged (spec) — the drive is from the base
  town, which is what the pool is gated on. Default: **unchanged**, as the spec says.
- **Q4.** The stacked-state threshold (170px, §2 #2) is sized for two-character counts. Between
  171 and ~195px a three-character count (a hundred-plus rated places in one band within reach)
  overflows side by side and is clipped by the value cell. Default: **accept and record** —
  today's catalogue is 253 places over the whole country, and the clip is the spec's own
  `overflow: hidden` safety net doing its job; raise the threshold, or drop the well's padding and
  the hatched gap under `.sm`, when the catalogue gets there.

---

## §7 Verification — the measurement S2 records here

Fixture, not assertion: a standalone page built from the REAL compiled `dist/assets/index-*.css`
(fresh `npm run build`) and the real IBM Plex Mono files, reproducing the card's DOM chain
(`.wf-hstrip > .wf-hc > .wf-hc-pls > .wf-hc-sprow`), opened in headless Chromium (Playwright, the
`browser-pane-headless-fallback` route) at 320, 375, 390, 640, 834 and 1280px. A live local stack
(`./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`, port 8083, `npm run dev`,
`admin`/`golden2026`) is the bonus check if its H2 holds ratings; a fresh file holds none, so bars
mode is a fixture question.

| viewport | card content width | stacked? (§2 #2) | histogram width at 1 / 2 / 4 chars | label width | overlap or clip? | count ink on well (AA) | zero-count ink | `.sm` ink | pick legend + hover paint as before? |
|---|---|---|---|---|---|---|---|---|---|
| 320 | | | | | | | | | |
| 360 | | | | | | | | | |
| 375 | | | | | | | | | |
| 390 | | | | | | | | | |
| 640 | | | | | | | | | |
| 768 | | | | | | | | | |
| 834 | | | | | | | | | |
| 1024 docked (Ask dock open) | | | | | | | | | |
| 1280 | | | | | | | | | |

Required cases on the NARROWEST card (320, stacked) AND on the narrowest side-by-side card:
single-digit counts; `999` in every column; `1.2k` in every column (the spec's own test); the
hatched bar at `1.2k` beside single-digit bands; the text state (`124/253 rated`) with the label
line above it; a mixed pool (one unknown drive — one line, "Spread" alone); "Any". Nothing may
draw over the label; the rating pill's column must measure the same with and without the
qualifier; and a `1.2k` card at 320px may clip only inside the value cell (record by how much, if
at all — §6 Q4).

---

## §8 Process

Each phase is a **fresh Sonnet session** in its own worktree, started by the planning session with
this plan's §3 phase text and the spec pasted in (the plan is on main once S0 merges; a session's
worktree is cut from `origin/main`, so a phase must not start until the previous phase's PR has
MERGED). The session: builds → runs the gate → runs its review lenses in the foreground → fixes →
measures (S2) → writes its changelog.d entry and its phase-log row → commits (conventional commit,
the attribution trailer) → pushes its branch → opens the PR (`gh pr create`, body ending with the
generated-with line) → watches CI (`gh pr checks --watch`) and Codex → fixes anything either
raises, pushing follow-up commits and asking `@codex review` after each → merges with
`gh pr merge --squash --match-head-commit "$(gh pr view <N> --json headRefOid -q .headRefOid)"`
only when CI is green AND Codex has answered for the HEAD sha. ⚠️ Codex clean is a 👍 reaction on
the PR body or the "Codex Review Summary" comment naming the head's short sha — 👀 is in progress,
never clean; a force-push does not re-trigger it. The planning session verifies each merge on
`origin/main` before launching the next phase. The owner is told only when a phase is genuinely
blocked (a Codex finding the session cannot resolve with cited evidence, a CI failure outside the
diff) — never for a decision §5 already takes.
