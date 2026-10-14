# Tide-run range bars — rank against the year, keep metres (plan)

Status: **PLAN, reviewed** (2026-10-14; three read-only lenses — backend/data, frontend layout/a11y,
brief fidelity/scope — §6 records what they changed). Owner brief: the handoff "PhotoCast High Tide
Coming Up Height Changes", `handoff/PROMPT-tide-run-range-bars.md` + `design-mockups/tide-run-range-bars.png`
(the PNG is the only geometry source: the brief's HTML "Proposed panel" is not in the zip). Follows
`docs/engineering/supermoon-tide-run-plan.md` (S1–S4, #1159–#1163, released v2.27.2): S2 served
`dayRanges`, S4 drew the bars. Two phases, R1 (backend) and R2 (frontend), one PR each, Sonnet
subagents; each merged on green CI + a clean Codex review.

**Data precondition — confirmed by the owner (2026-10-14): production's tide history is back-dated**
(the admin 12-month backfill has been run), so the representative port holds a full year of
`tide_extreme`. The sufficiency gate below stays as a safety net for a port that does not.

## 0. Scope — the rest of the zip was already built

The zip re-ships the **July 2026 handoff**, every item of which was implemented in #194 ("Plan-screen UX
polish + hot-topic events and aurora G-scale", 2026-07-03), and has since moved on:

| Bundle item | Status |
|---|---|
| CHANGES §1 chronological Hot Topics, §4 / `PROMPT-multi-day-cards.md` / BACKEND-CONTRACT §2 (`eventType`, `eventTime`, per-date topics) | Built in #194; `HotTopic.eventType`/`eventTime` are served by `HotTopicEventEnricher`. `HotTopicStrip` (and so the Option B timing lead) was retired in the Coming up redesign's P6 (`coming-up-plan.md` D7) — that lead has no live surface today. |
| CHANGES §2 calendar-chip header (`HeatmapGrid.jsx`) | Built in #194 (`getCalDow`/`getCalDayNum`). The bundle's file predates #1071 and #1140; dropping it in would regress them. |
| CHANGES §3 / BACKEND-CONTRACT §1 aurora banner + `gScale` | Built in #194 (`AuroraStatusResponse.gScale`), then redesigned in B1–B4 (2026-10-13). The bundle's banner keys accent on `status.hexColour`, which the current banner deliberately does not, and lacks the field thumbnail; dropping it in would regress B1–B4. |
| "Simulate mode should echo plausible values" | Coming up has no simulate mode (`AlmanacService` deliberately does not honour the hot-topic simulation). Test fixtures cover the shapes. |
| "Update BACKEND-CONTRACT.md / CHANGES.md" | Bundle files; the repo's equivalents are `coming-up-plan.md` §11/§13 and `changelog.d/`. |

Deliberate **addition** beyond the brief: **one average per card** (§2) — an unmerged tide card already
shows the sparkline's `+N vs avg`, which must agree with the dashed line's average.

## 1. What changes for the reader

Per day of a tide run (merged or not): **`top N%`** (how that day's range ranks among the past year's
daily ranges at the same port) above the bar; the bar (height compares the days' ranges, with a dashed
**average** segment across every bar); the day chip (unchanged); **`4.9 m`** under the chip. From 640px a
leading axis labels the dashed line `AVG` and the metres row `range`. A key sentence under the row
explains both. On a merged card the tide coincidence line reads `… · biggest Tue 27 · top 2% of the year ·
HW 15:48` instead of `… · 4.9 m · …`. The unmerged card's header metric (`4.9 m`) and range fact are
intentionally unchanged.

## 2. Definitions (server-owned; the client never ranks)

- **Names**: the brief's `rangeM` is the existing `ComingUpDayRange.rangeMetres` (kept); its `avgRangeM`
  is **`rangeAverageMetres`** everywhere (wire, `Staged`, client) — NOT `avgRangeMetres`, which is
  `TideStats.avgRangeMetres`, a different statistic (avgHigh − avgLow over all history).
- **Port**: the run's representative (`Staged.representative`) — the bars', range fact's and moonrise's port.
- **Daily range**: exactly `dayRanges`' definition — `TideRunBuilder.dailyRangesAt` (`dayTides(...).range()`,
  highest high − lowest low in a Europe/London civil day; a day counts only with both a high and a low;
  datum-independent, so backfilled and forward-fetched rows mix safely).
- **Year sample**: `dailyRangesAt(port, [builtFor − 365 … builtFor − 1])` — its size is n (derivable days).
- **Sufficiency**: n ≥ 300, else NO percentile and NO average for that run (never from a thin sample).
  Percentile is all-or-none per run (one sample serves every day).
- **`rangePercentile`** (Integer 1–100), ranked in DISPLAY space with the existing helper, no epsilon:
  `count = |{s : metresToOneDp(s) >= dayRange.rangeMetres()}|`, `p = max(1, ceil(100 × count / n))`.
  (`r − 0.05` was wrong: `4.9 − 0.05 = 4.8500000000000005` in Java, so a 4.85 sample — displayed 4.9 —
  would miss.) Ties count, so a run day that falls inside the sample (a run that started before
  `builtFor`) counts itself — correct. Lower = bigger; above every sample → `top 1%`.
- **`rangeAverageMetres`**: `metresToOneDp(mean(sample))` (same helper).
- **One average per card**: when `rangeAverageMetres` is served, `ComingUpTide.delta =
  metresToOneDp(shownRange − rangeAverageMetres)`; otherwise today's `TideStats` figure. This is a
  DIFFERENT statistic, not a fresher window of the same one — the mean daily (highest high − lowest
  low) is larger than avgHigh − avgLow at a semidiurnal port, so deltas shrink and the sparkline flattens
  (`comingUpSparkline.js`, `3 + delta·3.5`). Accepted for consistency; update `ComingUpTide`'s javadoc
  ("exactly rangeAnomaly…") and coming-up-plan §11.6.

## 3. Wire (additive; no migration)

- `ComingUpDayRange` gains `Integer rangePercentile` (`@JsonInclude(NON_NULL)`).
- `ComingUpEntry` gains `Double rangeAverageMetres` (`@JsonInclude(NON_NULL)`), served only with
  `dayRanges` AND a sufficient sample.
- Update every constructor site (7 `new ComingUpDayRange(`, 5 `new ComingUpEntry(`, the
  `new ComingUpAssembler(` sites in AlmanacServiceTest, ComingUpAssemblerTest,
  ComingUpAnnualBadgeCensusTest, ComingUpDayRangesAgreementTest) — no convenience constructors.
- coming-up-plan §13 schema; a §11 entry recording this change, the §0 scope note, the delta change,
  and the owner's backfill confirmation.

## 4. Phases

### R1 — backend: rank and average

- `DailyRangeHistory` (`service/comingup/`), modelled on `TideRunPeakHistory`: a `DayScopedMemo` whose
  DAY is `LocalDate.now(ZoneOffset.UTC)` (exactly as `TideRunPeakHistory.java:~121` — never `builtFor`,
  or a rewound build would roll the memo) and whose KEY is `(representative id, builtFor)`; value a
  record `(n, mean, sorted displayed values)` (the memo rejects null — use an empty/insufficient
  marker, not null). Subscribe to the same eviction (`tideSizeIndex.onEvicted`) so a tide ingest
  refreshes it. **Do not memoise failures**: let the repository exception escape the loader and catch it
  around `memo.get(...)` in the assembler (WARN, no percentile/average, feed served). Skip the read
  entirely when the run has no `dayRanges`.
- `enrichTide`: set each day's `rangePercentile`, `Staged.rangeAverageMetres`, and `delta` per §2.
- Merge: an explicit `host.rangeAverageMetres = tide.rangeAverageMetres` beside the existing
  `host.dayRanges = tide.dayRanges` (~`ComingUpAssembler.java:861`).
- Coincidence tide line: only on the true-peak branch (`truePeak != null`), and only when the TIDE
  Staged's own `dayRanges` has the `peak == true` day with a percentile, replace the `<metric>` segment
  with ONE non-breaking segment `nb("top 2% of the year")` (breaks only at ` · `, like every other
  segment ~`:925`). Read the tide Staged (the line is built before `host.dayRanges` is assigned). The
  `figuresFrom` partial-coverage branch keeps its metres unchanged.
- Tests: display-space boundary (a 4.85 sample from `6.05 − 1.20` counts as ≥ a 4.9 day); largest →
  `top 1%`; a mid value; n = 299 → absent / n = 300 → present; average rounding; delta uses the
  12-month average when served and TideStats otherwise (a fixture where the two averages differ);
  memo: hit, eviction, UTC-day keying (a rewound `builtFor` does not roll it), a failed read is NOT
  cached (second call reads again); merged entry carries both fields whichever hosts; coincidence line
  with/without a percentile and on the partial branch; AlmanacControllerTest present/absent through the
  real web mapper. (`ComingUpAnnualBadgeCensusTest` mocks the tide path — it proves nothing here; don't
  cite it.)

### R2 — frontend: the ranked bars

Replaces S4's value-above-bar column (S4's 4/26/15px tests are REWRITTEN, not kept).

- **Data**: `buildDayChips` threads `rangePercentile`; `buildEntryView` serves `rangeAverage`
  (`entry.rangeAverageMetres ?? null`) and `rangeScale` (≥ 2 values, as today) with `hasPercentiles`
  (all-or-none) — the `rangePercentile <= 5` "big" threshold is a client constant over a served value
  (licensed filter/map class; name it in CLAUDE.md).
- **Scale** (client geometry over served values): track 32px; `lo0 = min(runMin, avg ?? runMin)`,
  `hi = max(runMax, avg ?? runMax)`; `h(x) = round(13 + (x − lo0)/(hi − lo0) × 19)` → bars and the average
  line live in 13..32px; `hi === lo0` → 18px for every bar and the line. Fitted to the PNG (avg 3.4,
  run 4.1–4.9: peak 32, Fri ≈ 23, line 13). Not proportional to metres (a non-zero baseline, as S4
  was); the brief's key wording stays — the metres are printed under every bar.
- **Column** (width = chip width, 40px / 38px < 640px — never wider: at 390 the group is exactly
  6×38 + 5×6 = 258px and any growth wraps 6 → 5+1), top to bottom, every row FIXED height so tracks share
  a baseline and chips stay equal height (S4's `.wf-cu-day-col > .wf-cu-day { flex: 1 0 auto }` stands):
  1. **Label row** — only when `hasPercentiles`; ~13px tall (holds the pill: 9px × 1.2 + 2px). Text
     `top N%`, IBM Plex Mono 9px, `white-space: nowrap`, centred, allowed to overhang the column into the
     6px gap (no clipping; the card's own `overflow:hidden` is the bound). A day with no range keeps an
     empty slot.
  2. **Track** — column-width, 32px, `position: relative`. The bar centred in it.
  3. The chip button (unchanged).
  4. **Metres row** — fixed height, `4.9 m` (NBSP), Plex Mono 9px; empty slot for a day with no range.
- **Tiers** (served facts only): **peak** = the `dayRanges` `peak` flag → solid
  `var(--color-topic-coastal)` bar 22px wide (others 16px), glow
  `box-shadow: 0 0 10px color-mix(in oklch, var(--color-topic-coastal) 55%, transparent)`, label as a
  filled pill (bg coastal, text `#0e0b09`, 700, radius 4px, padding **`1px 3px`** — `1px 5px` makes
  `top 12%` 47.8px), metres in `--color-plex-text`. **Big** (`rangePercentile <= 5`, not peak) → bar at
  coastal 80%, label `--color-topic-coastal` (measure at 9px). **Rest** → bar at 45%, label and metres
  `--color-plex-text-secondary`. No percentiles → no label row, no big tier, peak bar styling kept.
- **Tokens**: the brief's `--ink-3`/"dim" → `--color-plex-text-secondary` everywhere (the file maps design
  `--ink-3` to secondary eight times; muted 0.42 ≈ 3.5:1 fails AA); `--ink-2` → secondary; `--tide` →
  `--color-topic-coastal` (same #6FA8B0 as `--color-tide`, S4's choice). Never opacity.
- **Gone / no-forecast columns**: label and metres stay secondary (a no-op for rest columns); a peak
  pill on such a day becomes an OUTLINE pill (1px `--color-plex-text-secondary` border, secondary text,
  no fill, no glow); bars unchanged.
- **Average line — drawn PER COLUMN** (one line cannot follow a row that wraps 4+2 with different row
  heights): set `--wf-cu-avg-h` once on `.wf-cu-days` via `style` (the sanctioned custom-property form,
  like `--wf-cu-bar-h`); each track draws `::before`/a span with `position:absolute; bottom:
  var(--wf-cu-avg-h); left:0; right:-6px; border-top: 1px dashed rgba(242,231,211,.28)` (so segments meet
  across the 6px gap; a 6px overshoot after each row's last column is accepted), drawn UNDER the bar.
  Absent when `rangeAverage` is null. Forced colours: `border-color: GrayText` (never
  `forced-color-adjust: none` on it — the rgba vanishes on a light Canvas).
- **Axis** (≥ 640px only, `display:none` below): the group's FIRST child, `aria-hidden`, so it can never
  wrap away from the bars (640–730px is a wrap band): `AVG` at the line's height (`top = label-h + gap +
  32 − avg-h`), `range` at the metres row; Plex Mono 9px, 600, `.1em`, secondary. `AVG` absent with the
  line.
- **Key** — a SIBLING after `.wf-cu-days` (a fragment, so the ≥ 640 stretch rules can't catch it), Plex
  Mono 10px, secondary, full width: "Bars show each day's tidal range in metres, printed under each day.
  <strong>Top N%</strong> means the day ranks among the biggest N% of the year's ranges. Dashed line is
  the average, 3.4 m." (literal "Top N%", NBSP in "3.4 m"); drop the Top-N sentence without percentiles
  and the dashed-line sentence without an average. Only when columns render.
- **Lead/note centring**: superseded, see the measured deviation below (no centring padding is built).
- **Measured deviation (R2 review): the ranged lead is a heading above the row**, full width at every
  width, not beside the chips as in the mockup, and the dimmed note sits under the row. A beside-the-row
  lead has to centre on the chip row, which depends on whether the row wraps, and CSS cannot detect that;
  the centring padding above was removed.
- **Accessibility**: aria-label clause `, 4.9 metre range, top 2 percent of the year` (percent part only
  with a percentile; in every chip state, before ` — gone`/` — no forecast yet`). `title="4.9 m range ·
  1.4× average"` per the brief (multiple only with an average) — note it becomes the accessible
  description, so the range is voiced twice; accepted as the brief asks. `aria-hidden` on inner spans
  (label, track, metres, axis) only — never on a wrapper containing the button. The key is visible text.
  Re-word S4's CSS justification for the sub-3:1 45% bar: the metres row under the chip plus the
  accessible name carry the value. Forced colours: the pill gets `border: 1px solid CanvasText` so the
  tier survives.
- **Two "peaks" on an unmerged run**: the gold `PEAK` chip is the door date (`chip.peak`), the pill the
  served `dayRanges` peak. For a true-peak run they are the same day; they differ only on a
  partial-coverage run, where `dayRanges` flags no peak at all (no pill). State this in the CSS/class doc.
- **Tests**: label `top N%` and metres under the chip; tiers (peak/big/rest; big never on the peak);
  the line position with avg INSIDE the run (e.g. 4.5 → ~22px) and avg ABOVE runMax — the brief's
  avg-below-all fixture alone can't kill a hard-coded line; line, `AVG` and the key's dashed sentence
  all absent when avg is null; no label row without percentiles; empty slots for a missing day; gone
  peak → outline pill; aria + title; no-`dayRanges` entries render no `.wf-cu-day-col`, no `data-ranged`,
  no key, no axis (and a one-value run likewise).
- CLAUDE.md: amend the S4 line by one clause — percentile and average are served; the client only
  scales and applies the served-value `<= 5` tier.
- **Review before merge**: an adversarial code/a11y lens AND a MEASURED headless-Chromium layout lens at
  **320, 360, 390, 640, 768, 1280** with a worst-case fixture (peak `top 12%` beside `top 10%`/`top 12%`),
  the glow, the per-column line across a wrapped row, equal chip heights, lead/note centring, no
  overflow, and contrast of every label/pill state including hover.

## 5. Gates
As `supermoon-tide-run-plan.md` §6 (JDK 23 backend gate with the integration and `EvaluationModelTest`
exclusions; frontend lint/test/audit/build), gate on exit codes.

## 6. Review record (2026-10-14)
Backend: the `r − 0.05` floating-point bug (rank in display space instead); the 300-day risk (resolved
by the owner's backfill confirmation); don't memoise failures; UTC-day memo keying; delta is a different
statistic (javadoc + §11.6); coincidence-line branch and merge wiring; constructor sites; the census test
proves nothing here. Frontend: `--ink-3` fails AA (map to secondary; peak metres to primary); the pill
didn't fit (padding 1px 3px, overhang allowed, never widen the column); one line can't span a wrapped
row (per-column segments); scale clamps and the 13..32 floor; label/metres rows fixed-height and
all-or-none; muted peak pill; axis inside the group; lead/note centring formula; forced colours; title
voicing; key as a sibling; test fixtures that kill hard-coded lines; 360/640 in the measured lens.
Fidelity: §0 rewritten (the zip's other items shipped in #194); one field name; PNG is the only
geometry source; delta change labelled as an addition; NBSP as one segment; unmerged header unchanged.
