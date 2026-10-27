# Supermoon on a tide run — Coming up merge shape (plan)

Status: **PLAN, reviewed** (2026-10-14; four adversarial lenses — backend correctness, frontend/a11y,
brief fidelity, phasing/testability — §8 records what each changed). Owner brief: the design handoff
"PhotoCast SuperMoon and Tides" (`design_handoff_supermoon_tide_run/README.md`, the as-built/proposed
mock, four screenshots). Built in phases S1–S4, one PR each, by Sonnet subagents; each PR merged on
green CI + a clean Codex review before the next starts. Parent plan: `docs/engineering/coming-up-plan.md`
(D10, §11.25–§11.26, §13).

## 1. The defect

`ComingUpAssembler.mergeCoincidences` (D10) pairs every spring/king tide run with an overlapping
supermoon and merges each pair under the **max rule**. `mergeEntries` returns the WINNER on bits
unchanged in shape, with the loser reduced to one `ComingUpCoincidenceLine(family, name,
factsLabel)` whose label is `<metric> · <peak date>` (`4.9 m · 27 Oct`). When the supermoon wins
(the live case, 26 Oct 2026):

- the rail reads `MON 26 OCT`: the run's span (25–30 Oct) is gone;
- `buildDayChips` walks only the entry's span, so one chip (26), not six;
- the door is `dark-sky-spots`, though the run is coastal and moonrise over the sea is the shot;
- the fact `peaks 26 Oct` is the moon's, beside a tide line reading `27 Oct` — it does not say whose peak;
- the join note `…the Supermoon carries it.` is engineering vocabulary.

## 2. The rule (owner's, general — never special-case types in the SHAPE logic)

**Bits decide ranking. Span decides shape.** The max rule still picks the score; it no longer picks
the dates, title, family, door or chip row.

| # | Rule |
|---|---|
| R1 | **Span = union** of every merged topic's `startDate`–`endDate` (`min`, `max`). |
| R2 | **Host = the topic with the longest span** (inclusive days); tie → the bits winner; bits tie → the existing `tide.bits >= supermoon.bits` tie-break (tide). Consequence, accepted (owner's rule): on a cold DB a 2-day fallback run and a 2-day supermoon tie on span and the supermoon (higher bits) hosts, with a dark-sky door. |
| R3 | The host supplies `type`, `family`, `kind`, `detail`, `meta`, `regions`, `metric`, `superlative`, `threshold`, `tide` (sparkline), `facts` and `action` (kind AND date). The other topic's facts are **not** carried (its line carries its dates); in particular, when a supermoon hosts the run's range/alignment facts are not on the card, and the supermoon's own `peaks …` fact is not served (`facts` may be empty). |
| R4 | **Title** = `<host title>, on a <other title with its first letter lower-cased>` — `Spring tide run, on a supermoon`, `King tide run, on a supermoon`, `Supermoon, on a spring tide run`. |
| R5 | **Every topic gets a coincidence line with its own dates, host first.** None is reduced to a bare facts label. |
| R6 | **Every topic marks its own day(s)** in a new served `dayMarks`. A date may carry several marks; order = by date, then host's marks before the other's. |
| R7 | **Join note** names each topic's own date(s); same-day wording when they coincide. |
| R8 | The generic `peaks <date>` fact never appears on a merged entry; when the RUN hosts its tide alignment fact gains ` on the <ordinal>` (from `alignmentDate`). **SUPERSEDED 2026-10-19 (tide-both-lights W1):** the single alignment fact is replaced by a sunrise and a sunset high-water row that each always carry their own date, merged or not; there is no ` on the <ordinal>` suffix and `tideFacts` has no `merged` parameter. |
| R9 | `id` = `<host type>:<union start>:<union end>` (`spring-tide:2026-10-25:2026-10-30`). |
| R10 | Score provenance stays with the bits WINNER, copied onto the host explicitly: `bits = max(both)`, `interim = winner.interim`, `enteredWindow = winner.enteredWindow` (so a run that wins and clears announce still arrives on the day IT entered the window, as today — never the union start, which would back-date the arrival and swallow the badge). `prose` follows `markFirstOfType`, which runs after the merge on the host's type: a run-hosted card keeps the run's first-of-type prose and the supermoon's prose moves to the next standalone supermoon. Accepted. |

Pairing itself is **unchanged**: D10 still pairs only tide runs with supermoons
(`findSupermoonPartner`). Lunation spacing makes two supermoons on one run, or one supermoon on two
runs, impossible (verified in review). R1–R10 are written over "the merged topics"; per-type
vocabulary (mark word, line facts, join note) lives in small per-type helpers, never in the shape logic.

### 2.0 Reading the source facts — ⚠️ from `event.meta()`, never `Staged.meta`

`enrichTide` strips `range`, `location`, `peakDate`, `figuresFrom` (and, since tide-both-lights W1, the eight `sunriseHw…`/`sunsetHw…` keys and `eventWaterPartial`; the old `alignment`/`alignmentDate`/`noAlignment` are no longer written at all) from `s.meta`, and
`enrichSupermoon` strips `peakDate`. Every merge helper reads **`staged.event.meta()`**. Definitions:

- **T** (tide true peak) = `event.meta().get("peakDate")` parsed defensively; **null = no true peak**.
  Never `peakDateOf()` — it falls back to `figuresFrom`, then `startDate`, and would put a false
  "peak" on a partial run. ⚠️ A run with exactly ONE derivable day never has a true peak
  (`TideRunBuilder.buildDay`: `peak = dayCount > 1 && …`), so it serves `figuresFrom` +
  `partialCoverage` (existing behaviour, unchanged).
- **F** (figures day) = `event.meta().get("figuresFrom")`, only when T is null.
- (Superseded by tide-both-lights W1: the coincidence clock now reads the aligned `sunsetHw` row, else `sunriseHw`, dated on the true peak.) **A** = `event.meta().get("alignmentDate")`, **HW** = the leading two tokens of
  `event.meta().get("alignment")` (`HW 15:48`), shape `TideRunBuilder` writes at ~:633–643.
- **M** (supermoon night) = `peakDateOf(supermoon.event)` — the span night nearest perigee.
- **Moon nights** = every date of the supermoon span.

### 2.1 Cases (each a backend test in S1)

| Merge | Span | Host | `dayMarks` |
|---|---|---|---|
| Spring run 25–30 Oct (T 27) + supermoon 26 Oct | 25–30 Oct | spring run | 26 `full moon` (sun-moon); 27 `peak` (coastal) |
| King run 23–28 Nov (T 26) + supermoon 24–25 Nov | 23–28 Nov | king run | 24, 25 `full moon`; 26 `peak` |
| Run + supermoon, T = moon night (26) | run span | run | 26: `peak`, then `full moon` (host first) |
| Supermoon wins on bits (row 1 shape) | run span | run (still) | as row 1; `bits`/`interim`/`enteredWindow` are the supermoon's |
| Run with ONE derivable day + supermoon same day | that day | bits winner | that day: `full moon` only (no true peak ⇒ no tide mark); join note uses the no-peak wording |
| Supermoon 24 Nov, run 25–29 Nov, run wins on bits | 24–29 Nov | run | 24 `full moon`; T `peak`; `enteredWindow` = the run's own |
| Merged run whose T is null (partial coverage, ≥ 2 days) | run span | run | moon nights only; no `peak` anywhere |

### 2.2 Per-type vocabulary

- **Mark word**: tide run → `peak` on T only (none when T is null). Supermoon → `full moon` on every
  moon night. Any other type (unreachable today) → `peak` on its `event.meta()` `peakDate` if present,
  else none. A mark's `family` is its own topic's family (`coastal`, `sun-moon`).
- **Span label** (`Locale.UK`, en dash with spaces): single day `EEE d MMM` (`Mon 26 Oct`); same month
  `EEE d – EEE d MMM` (`Sun 25 – Fri 30 Oct`); crossing months `EEE d MMM – EEE d MMM`
  (`Fri 30 Oct – Tue 3 Nov`).
- **Coincidence line facts** (`factsLabel`, still ONE plain string — no segments on the wire; the
  mock's bold dates are a deliberate divergence, §7):
  - tide run, T present: `<span label> · biggest <EEE d> · <metric>`, then ` · <HW>` **only when A ==
    T** (a clock is never printed beside a different day). `<metric>` is the run's `metric` (`4.9 m`),
    omitted with its separator if null. Month-crossing runs: `biggest Mon 2` carries no month — accepted.
  - tide run, T null, F present: `<span label> · <metric> on <EEE d of F>`.
  - tide run, no figures: `<span label>`.
  - supermoon: `<span label>` then, when moonrise is known, ` · moonrise HH:mm` for a single night or
    ` · moonrise HH:mm on the <M ord>` for a multi-night span. Moonrise is on M, Europe/London, at the
    merged run's **representative location** — the port the range figures and bars are drawn at — via
    the existing `MoonriseMoonsetCalculator` bean, exactly as `SupermoonHotTopicStrategy.buildFacts`
    (~:148–163): `calculate(M, lat, lon, Europe/London).moonrise()` then
    `rise.withZoneSameInstant(Europe/London).toLocalTime()` formatted `HH:mm` (never rely on the
    returned zone). Omitted when there is no representative or the moon does not rise. The hot topic
    samples `coastal.get(0)`, not the representative; the two surfaces may differ by a minute or two
    for one date and never show together — accepted.
- **Join note** (tide + supermoon, the only pair). Stem: `Same cause, two effects. The moon is closest
  to Earth, which both enlarges it and stretches the tide.` Then:
  - T null: stem + ` Counted as one event, not two.`
  - T is a moon night: stem + ` The moon is full and the tide peaks on the same day, the <T ord>.
    Counted as one event, not two.`
  - otherwise: stem + ` The moon is full on the <nights> and the tide peaks <rel>, on the <T ord>.
    Counted as one event, not two.` `<nights>` = `26th` / `24th and 25th` / `23rd, 24th and 25th`;
    `<rel>` is measured from the NEAREST moon night to T: `a day later` / `a day earlier` / `N days
    later` / `N days earlier`.
  - Ordinals: `1st 2nd 3rd 4th … 11th 12th 13th … 21st 22nd 23rd … 31st`. No month name.
  - "Full" names the qualifying full-moon nights (every supermoon night is within the full-moon window),
    which is what the chips mark — not the full-moon instant.

### 2.3 What a merged entry carries (Oct example)

```
startDate 2026-10-25, endDate 2026-10-30, type spring-tide, family coastal
id spring-tide:2026-10-25:2026-10-30
title "Spring tide run, on a supermoon"
action {label "Show coastal spots for 27 Oct →", kind coastal-spots, date 2026-10-27}
coincidence [
  {family coastal,  name "Spring tide run", factsLabel "Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m · HW 15:48"},
  {family sun-moon, name "Supermoon",       factsLabel "Mon 26 Oct · moonrise 17:22"}]
joinNote "Same cause, two effects. The moon is closest to Earth, which both enlarges it and stretches the tide. The moon is full on the 26th and the tide peaks a day later, on the 27th. Counted as one event, not two."
facts: [range 4.9 m at <location>], [tide HW 15:48 · 52m before sunset on the 27th]
dayMarks [{2026-10-26, "full moon", sun-moon}, {2026-10-27, "peak", coastal}]
dayRanges [{2026-10-25, 4.3, false}, …, {2026-10-27, 4.9, true}, …]      (S2)
bits/interim/enteredWindow = the bits winner's
```

The `range … at <location>` fact is **kept** (the mock drops it): it is the only place the card names
the port the range, bars and moonrise are measured at; `4.9 m` then appears twice — accepted. The
alignment fact keeps its existing `tide ` prefix (every tide card says `tide`; the mock and the
reference bundle say `alignment`) and appends ` on the <ordinal>` on a run-hosted merged entry only.
Both are recorded as deliberate divergences in coming-up-plan §11 (S1). **Superseded 2026-10-19 (tide-both-lights W1):** the `tide ` alignment fact and its ordinal suffix are gone, replaced by the two dated high-water rows.

## 3. Wire changes (`ComingUpEntry`, additive; no migration — the feed is built in memory)

- **S1** — `dayMarks`: `List<ComingUpDayMark>`, `@JsonInclude(NON_EMPTY)` (the same
  `com.fasterxml.jackson.annotation` form `facts`/`coincidence` use, which works under the Jackson 3
  web mapper), new record `ComingUpDayMark(LocalDate date, String label, String family)`. **Served only
  on a merged entry**; an unmerged entry serves none and its chips behave exactly as today.
- **S2** — `dayRanges`: `List<ComingUpDayRange>`, `@JsonInclude(NON_EMPTY)`, new record
  `ComingUpDayRange(LocalDate date, double rangeMetres, boolean peak)` (metres to 1 dp; `peak` = the
  date equals T, server-owned so the client never decides whose day is biggest). **Every tide-run
  entry** (merged or not) whose run has ≥ 2 derivable days; one element per derivable day within the
  entry's span, ascending; a day with no stored extremes is absent (never 0, never synthesised). On a
  merged entry the ranges are the run's, whichever topic hosts.
- Both normalised to `List.of()` in the compact constructor; `ComingUpEntry.from(...)` passes empty
  lists; Javadoc `@param` for each. ⚠️ S1 must NOT add `dayRanges` or `ComingUpDayRange` (an
  unconstructed record is 0% under JaCoCo's 80%-per-class rule) — S2 owns both.
- `coincidence` javadoc (and `ComingUpCoincidenceLine`'s record javadoc): "one line per merged topic,
  host first, each with its own dates".
- **Counts**: `countsOf` counts a merged entry under EVERY distinct family among its host family and its
  coincidence lines' families (S1), so the client's family filter (S3) and its served chip counts agree.
  `fixed`/`forecast` still count the entry once (`ComingUpCounts` has no `total`). ⚠️ The client's All
  chip today SUMS `byFamily` (`comingUpFeed.js` `chipCounts`), which would count a merged card twice:
  S1 changes All to `counts.fixed + counts.forecast` (a one-line frontend change, with a
  multi-family regression test) in the same PR as the backend count change. (Codex, plan PR #1159.)
- **Coincidence line `type`** (S1): `ComingUpCoincidenceLine` gains a `type` (the merged topic's own
  wire type, `spring-tide`/`king-tide`/`supermoon`), so a consumer can tell which topics a card holds
  without parsing names. Additive, `@JsonInclude(NON_NULL)`; the frontend ignores it.

## 4. Consumers that must move with the shape

1. **`ComingUpConditionsBuilder.matchingEntry` + `reason`** (~:287–305, ~:369–380). `reason` today is
   `matchedEntry.id().equals(ownId) ? coincidence().getFirst().name() : title()` — wrong under R5 (line 0
   is now the host) and R4 (composite title). Replace with: the first coincidence line whose `name` is
   not this run's own `event.title()`, lower-cased; null if none (line names equal
   `Spring tide run`/`King tide run`, TideAlmanacSource:77–78). The exact-id lookup now hits whenever
   the run hosts and the union equals its span; the overlap fallback stays (supermoon host, or a union
   wider than the run) with its `family == coastal` filter. Rewrite the class doc (~85–98) and
   `matchingEntry`'s invariant 2 ("never touches the winner's dates") to "the merged entry's span is a
   superset of each merged run's span; union growth of 1–2 days cannot reach a neighbouring run, which
   is separated by neaps".
2. **Ask** — `AskSnapshotBuilder.comingUp()` and `AskSnapshot.timeline` serve a merged entry as the
   host type with the union span and composite title; `AskReadyFreshness.liveEvent` matches an almanac
   event by type + `startDate`, so a stored Ready answer or thread event naming `supermoon` on 26 Oct is
   withheld whole after deploy until the next precompute — accepted (freshness doing its job). ⚠️ But
   `AskSnapshot.timeline` appends each live hot topic unless `listedBy` finds an almanac entry of the
   SAME type over that date, and `AskSnapshotBuilder.comingUp()` discards the coincidence lines: with
   the run hosting, a live `SUPERMOON` topic inside the Plan horizon would be listed a second time
   beside the merged `spring-tide` entry (Codex, plan PR #1159). S1 fixes it: `AskSnapshot.ComingUp`
   gains `List<String> coincidentTypes` (the coincidence lines' `type`s, empty when unmerged; every
   construction site updated), `comingUp()` fills it, and `listedBy` matches the entry's own type OR
   any coincident type (through `AskEventType.same`). Test: a merged run + supermoon entry and a live
   `SUPERMOON` topic on a date inside its span yield ONE timeline row. The timeline row itself still
   shows the host type/title — accepted; state it in S1's changelog.
3. **`WindowComingUpSinceLine`** prints `the ${entry.title} entered the window` + the join note → "the
   Spring tide run, on a supermoon entered the window…". Accepted; the comma reads as an appositive.
   The overlay label `${entry.title} · ${chip.dateLabel}` gets longer — accepted.
4. **Family filter** — `comingUpFeed.js` `matchesFilter` matched `entry.family` only; **done in S1** (moved from S3
   so the served counts and the list agree from the first release): it also matches any `coincidence[].family`, so a merged card is found under Sun & moon AND Coastal (counts: §3).
5. **`MAX_CHIP_DAYS` (10)** — the union can push a long run past 10 days. S3: an entry with `dayMarks`
   uses a cap of 14; unmerged entries keep 10.
6. **`WindowComingUpEntry` class doc** (coincidence section ~:218) and the CSS comment at `index.css`
   ~9201–9207 ("names the OTHER topic, never the card's own family") — update in S3.
7. **Pairing must see Plan-owned partners** (Codex, plan PR #1159 round 2). `AlmanacService.assemble`
   (~:300–307) filters raw events by `endDate > PlanHorizon.lastPlanDate(builtFor)` BEFORE the
   assembler runs, so a supermoon inside Plan's four days is dropped while its overlapping run (which
   ends beyond the horizon) survives — no merge, no marks, and Ask lists the run and the live
   supermoon separately. S1: `ComingUpAssembler.assemble(builtFor, eligible, partnersOnly)` — the
   service passes the eligible list as today PLUS the not-eligible events (`partnersOnly`). The
   assembler stages `partnersOnly` events but uses them ONLY as D10 merge partners: one that pairs with
   an eligible event is merged (R1–R10 apply unchanged — e.g. a Plan-window supermoon on an eligible
   run is marked on its day, the run hosts); one that pairs with nothing is dropped; two
   `partnersOnly` events never pair with each other (the union would end inside Plan's window).
   `markTideSuperlativesAndThresholds` still runs over the ELIGIBLE runs only (unchanged comparison
   set). A merged entry is always eligible (its union ends no earlier than its eligible member).
   Knock-on, accepted: `ComingUpConditionsBuilder` (which still receives the unfiltered `all`) may now
   match a run wholly inside Plan's window to a merged card through the overlap fallback and report
   it `promoted` rather than `insidePlan` — true, since the card now shows it. Tests: Plan-window
   supermoon + eligible run → one merged card with the `full moon` mark; Plan-window run + eligible
   supermoon → one merged card, run hosts (longer span); an unpaired Plan-window event is absent;
   superlatives unchanged by a Plan-window run; `AlmanacServiceTest` covers the service wiring.
   > **Superseded 2026-10-27 (`coming-up-near-term-plan.md`):** every event that has not ended is now
   > eligible, so production no longer passes `partnersOnly`; a near-term supermoon and run merge through the
   > ordinary eligible-pair path. The partner path is kept in the assembler, unused, pending removal.
8. Unchanged and still correct: `markScoreNotes` (skips an entry with a `joinNote`),
   `markTideSuperlativesAndThresholds` (runs before the merge; a hosting run keeps its own; a
   supermoon host does not carry the run's), the `assemble` sort (union start).

## 5. Phases

Each phase: a Sonnet subagent in its own worktree branched from **current `origin/main`** (the
previous phase merged first), conventional commit(s), one `changelog.d/YYYYMMDD-<slug>.md`, the gate
(§6), push, PR, CI green + Codex 👍 on the head sha, squash-merge. Subagents never push to `main`,
never tag, never release. ⚠️ **The owner should not release between S1 and S3**: S1 changes the visible
card (title, door, two lines, chips over the union span) but the chips carry no marks until S3. S1 alone
is safe on main (the frontend renders every served coincidence line generically) — say so in its PR,
and that it was not seen in a browser.

### S1 — backend merge shape (+ one CSS line)

- `Staged` gains `startDate`, `endDate`, `title`, `dayMarks` (defaults from `event`) and
  `representative` (`LocationEntity`, nullable — set in `enrichTide` from `peak.representative()`
  **before** the `getTideStats` branch, so moonrise does not depend on `TideStats`). `toEntry()` reads
  these, not `event.startDate()`/`event.endDate()`/`event.title()`.
- Rewrite `mergeEntries(tide, supermoon)` per R1–R10 and §2: choose host (R2) and winner (bits);
  host.startDate/endDate/title/id; host.bits = max, host.interim = winner.interim,
  host.enteredWindow = winner.enteredWindow; coincidence = [line(host), line(other)] via a per-type
  `coincidenceFacts(Staged, representative)`; dayMarks via a per-type `dayMarksOf(Staged)` merged in
  R6 order; joinNote via `perigeeJoinNote(supermoon, tide)`. Delete `loserFactsLabel`.
- (Superseded by tide-both-lights W1: `tideFacts(event)` takes no `merged` flag.) Facts by construction, not text surgery: `tideFacts(event, boolean merged)` appends ` on the <A ord>`
  to the alignment value when `merged` and A is present; the merge rebuilds the hosting run's facts with
  `merged = true`; a hosting supermoon's facts are rebuilt without the `peaks` fact (only the region
  scope fact, if any). Never match on segment text.
- `countsOf` per §3. `ComingUpDayMark` record + `dayMarks` field per §3.
- Inject `MoonriseMoonsetCalculator` into `ComingUpAssembler`. Constructions: `ComingUpAssemblerTest`
  (a mock stubbed with `new MoonriseMoonset(Optional.of(…), …)` — see `LunarEclipseCalculatorTest`
  ~:238 — for "rises" and `Optional.empty()` for "no rise"; never hard-code a real ephemeris time),
  `AlmanacServiceTest:39` and `ComingUpAnnualBadgeCensusTest:129` (pass `new MoonriseMoonsetCalculator()`,
  pure, as `AppConfig:216` does — a bare mock returns null and would NPE on a merge).
- Pair against Plan-owned partners per §4.7 (`assemble(builtFor, eligible, partnersOnly)` and its
  caller in `AlmanacService`).
- `ComingUpConditionsBuilder` per §4.1. Ask timeline dedupe per §4.2 (`coincidentTypes`). The All
  chip count per §3 (frontend `chipCounts`, + test).
- Frontend CSS, one rule: `.wf-cu-coin-facts` is `white-space: nowrap` in 10.5px mono
  (`index.css` ~9220–9226); the new tide line (~55 chars ≈ 345px) overflows the card at 390/320px.
  Make it `white-space: normal` (keep nowrap only from 640px if it is needed there), so main is never
  left with a horizontally overflowing card between S1 and S3.
- Docs: coming-up-plan.md gets a §11 entry (2026-10-14) recording that D10's winner-shape and
  winner-naming join note are superseded by the owner's brief (span = union, host = longest span,
  both lines, `dayMarks`), plus the two divergences of §2.3 and the plain-string factsLabel; update
  §13's `coincidence`/`joinNote` comments and add `dayMarks` to the §13 schema.
- **Tests to rewrite, not delete** — `ComingUpAssemblerTest`: `tideRunAndSupermoonMerge` (~695–716),
  `supermoonSortingBeforeItsTideRun_stillMergesToOneEntry` (~719–739),
  `tideRunOutscoresSupermoon_tideCarriesTheMerge` (~742–770), and
  `biggestRunMergedAway_stillSetsNeighboursComparisonSet` (~649–692 — keep its purpose: A and C still
  see B's 6.0; add that B's own superlative and threshold now appear on the merged card because B
  hosts). `ComingUpConditionsBuilderTest`: `reasonTagWhenSupermoonWonTheMerge` (~212–225),
  `reasonFiresEvenWhenThisRunsOwnScoreWon` (~245–256) and the `entry(...)` helper (~98–104, the only
  canonical-constructor caller) rebuilt in the two-line host-first shape.
- **New tests**: every row of §2.1; the join note's four wordings, multi-night `<nights>`, and ordinals
  (1st 2nd 3rd 4th 11th 12th 13th 21st 22nd 23rd 31st); span labels (single, same month, crossing);
  `HW` clock only when A == T; moonrise present / absent (no representative; no rise) / multi-night
  `on the <ord>`; R2 ties (span tie → bits winner; bits tie → tide); `id` from host type + union;
  `bits`/`interim`/`enteredWindow` from the winner when host ≠ winner; facts (`peaks` never on a merged
  entry; alignment ` on the 27th` only when the run hosts); counts by family; the reason from both the
  exact-id and the fallback match. Unmerged supermoon and unmerged run: field-level assertions of
  title, type, facts, action, coincidence (empty), `dayMarks` (empty), `id` — unchanged from main.
- **Serialisation** in `AlmanacControllerTest` (it already asserts absent fields through the real web
  mapper, ~87–88): a merged entry asserts `$.entries[0].dayMarks[0].label`; an unmerged
  `ComingUpEntry.from` entry asserts `dayMarks` doesNotExist.
- Changelog notes: §4.2 Ask behaviour; filter/counts change; door and title change on merged cards.

### S2 — backend `dayRanges` for every tide run

- `TideRunBuilder.dailyRangesAt(LocationEntity representative, List<LocalDate> dates)` →
  `Map<LocalDate, Double>` (LinkedHashMap, date order, derivable days only, empty on null/empty input):
  the `fetchExtremes(List.of(representative), ordered)` + `dayTides(extremes, date).range()` walk
  `peakRangeAt` already does (both private and reachable inside the class). Decided: **two reads**
  (`peakRange`'s fetch + this one) — folding the ranges into `RunPeak` would touch ~13 test
  constructions for one saved query on a day-cached build. `peakRangeAt` may be re-expressed over it
  only as a pure refactor. Never re-select a representative: it is `Staged.representative`.
- `enrichTide` stores `dayRanges` (1 dp; `peak` = date equals T from `event.meta()`) when ≥ 2 days are
  derivable, restricted to the entry span; the merge carries the run's `dayRanges` onto the merged
  entry whichever topic hosts.
- Tests: length equals the DERIVABLE days (the README's "length equals span days" read deliberately: a
  missing stored day is absent, never synthesised — equals span days in the ordinary case, asserted
  too); a missing middle day absent; < 2 derivable → empty; rounding; `peak` true on T only and false
  everywhere on a partial run; carried when the supermoon hosts; `dailyRangesAt` unit tests;
  `AlmanacControllerTest` present/absent pair; coming-up-plan §13 gains `dayRanges`.

### S3 — frontend: marks, coincidence lines, filter, merged-chip styling

- `comingUpFeed.js` `buildDayChips`: `marks` is decided ONCE per entry — `null` when
  `entry.dayMarks` is absent or empty (unmerged: the existing `chip.peak` → `peak` path, unchanged),
  else each chip's `entry.dayMarks.filter(m => m.date === date).map(({label, family}) => ({label,
  family}))` (possibly `[]`). On a merged entry the word "peak" and ", peak day" come ONLY from a served
  `peak` mark, never from `chip.peak` (a partial-coverage run's `action.date` is its `figuresFrom` day).
  `chip.peak` still drives the door; on a merged entry the peak STYLING (`data-peak`) follows the served `peak` mark and is coloured by that mark's family (`data-peak-family`). Cap: 14 when `dayMarks` is non-empty, else
  `MAX_CHIP_DAYS` (10). `matchesFilter` (**done in S1**, §4.4): `entry.family` OR any `coincidence[].family`.
- `WindowComingUpEntry.jsx` `DayChips`:
  - third line: `monthWord` (first chip of a new month, §11.25 — the mock omits `Oct` under SUN 25 and
    is wrong there) then each mark as its own block line `<span className="wf-cu-day-mark"
    data-family={family}>{label}</span>` (`display:block`, no visible ` · ` between marks); `null`
    marks → the existing `chip.peak ? 'peak'` text, byte-identical.
  - mark colour by family token (`.wf-cu-day-mark[data-family="coastal"] { color:
    var(--color-topic-coastal) }`, all seven families as at `index.css` ~9208), never by type name;
    marks keep their family colour on a gone/disabled chip, as the gold peak word does today (muting is
    by colour, never opacity). Marks must fit a 38–40px chip: no letter-spacing growth, allow the mark
    span to wrap inside the chip only by word; measure "FULL MOON" and a two-mark chip in a browser.
  - **Peak chip styling**: today's peak chip is GOLD (`--color-home` border/number, `rgba(201,162,75,.12)`
    fill, ~9407–9415). Unmerged rows keep it unchanged. On an entry with `dayMarks`, the group carries
    `data-marked="true"` and the chip holding the served `peak` mark is `data-peak` and takes that
    MARK's family colour (`data-peak-family`; coastal teal border, number and a ~14% `color-mix` fill) —
    not the host accent, which is gold under a supermoon host — so the tide's peak is never drawn in the
    moon's gold beside a gold `full moon` mark. Re-measure AA for normal, hover and disabled.
  - aria-label: with `marks === null`, unchanged (`, peak day` from `chip.peak`). Otherwise for each
    mark in order, `peak` → `, peak day`, other → `, <label>`: `Show coastal spots for Mon 26 Oct, full
    moon`, `Show coastal spots for Mon 26 Oct, peak day, full moon`; the gone / no-forecast forms keep
    the marks before ` — gone` / ` — no forecast yet`.
  - PropTypes: `marks: arrayOf(shape({label, family}))` (nullable) on the chip shape.
- `.wf-cu-coin-name { white-space: nowrap; }` — **done in S1**. Also done in S1: `coincidenceLineGlyph` prefers the line's wire `type`, and the facts labels use non-breaking spaces.
- Update the class doc's coincidence section, the CSS comment (§4.6), and the `comingUpFeed.js` comment
  at ~390.
- Tests: marks threaded per date incl. two on one date in served order; `marks === null` for an
  unmerged entry and its DOM unchanged; a merged partial-coverage entry shows no "peak" text or
  ", peak day" anywhere; aria-labels above; `data-family` on marks; `data-marked` only on merged
  groups; filter matches by coincidence family; the 14-day cap; merged fixtures updated to the two-line
  host-first shape; jsdom cascade checks that the mark rule resolves to the family token, the merged
  peak chip to the accent, the unmerged peak chip still to gold, and `.wf-cu-coin-name` is nowrap.
- Measured contrast of each mark colour on the chip ground, the merged peak tint and disabled.
- Adversarial UI review before commit (CLAUDE.md *UI Work — Review Cadence*); browser check if a local
  run is feasible, else state "tested, not seen".

### S4 — frontend range bars

- `buildDayChips`: each chip gains `rangeMetres` and `rangePeak` from the served `dayRanges` entry for
  that date (or `null`/`false`). `buildEntryView` adds `rangeScale: {min, max}` over the served values
  only when ≥ 2 exist, else `null` (geometry over served values — the licensed filter/map class).
- `DayChips`, when `rangeScale` is set: each chip sits in a column `.wf-cu-day-col` **exactly the chip's
  width** (40px; 38px below 640px — never the mock's 52px, which would wrap a six-day run at 390px).
  Inside the column, ABOVE the button, an `aria-hidden="true"` span holds the value label (mono 9px,
  one decimal, no unit) and a 26px-tall track with a bar ~10px wide (scaled from the mock's 16/52) of
  height `4 + (r − min)/(max − min) × 22` px (`max === min` → 15px). ⚠️ `aria-hidden` goes on that
  inner span ONLY — never on the column, which contains the button. The `rangePeak` column draws the
  bar solid `var(--color-topic-coastal)` and its value in `--color-plex-text-primary`-strength ink;
  others at `color-mix(in oklch, var(--color-topic-coastal) 45%, transparent)` with the value in the
  secondary ink token (never opacity). A chip with no value keeps an empty column of the same height.
  The 45% bar is ~2:1 against the card — acceptable only because the value is in visible text and in
  the button's name; say so in the CSS comment.
  - Bar height via a CSS custom property, the established form: `style={{ '--wf-cu-bar-h': `${h}px` }}`
    (precedent: `WindowComingUpConditions.jsx:80,142` `--wf-cond-accent`; `WindowFirstHeatStrip.jsx`
    ~1566 `--c`/`--r`).
- aria-label gains `, <r> metre range` after the marks: `Show coastal spots for Tue 27 Oct, peak day,
  4.9 metre range` ("metre", not "m", which screen readers voice as "m" or "minutes").
- PropTypes: `rangeMetres`, `rangePeak` on the chip shape; `rangeScale` on the entry.
- Mobile: verify 320 and 390px in a browser — a six-day run's six columns plus the lead fit as today.
- Tests: bar heights for min / max / middle / equal; solid bar on `rangePeak`, NOT `chip.peak` (a
  supermoon-hosted fixture where they differ); missing middle value → empty column; no `dayRanges`, and
  an unmerged tide run with < 2 values → no columns (DOM identical to S3); every chip button still found
  by `getByRole('button', {name})` with columns present; aria range clause.
- CLAUDE.md: one short line where *Coming up* is described: D10's shape rule (span = union, host =
  longest span, both lines, `dayMarks`/`dayRanges`) and that the bar geometry is the licensed
  filter/map class. Adversarial UI review before commit.

## 6. Gates (every phase)

- Backend (JDK 23 on this Mac — no JDK 25 here; CI runs 25): `cd backend && export
  JAVA_HOME=/Users/chrisgregory/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home
  MAVEN_OPTS=--enable-preview && ./mvnw clean verify --batch-mode --no-transfer-progress
  -Djava.version=23 -Dmaven.compiler.enablePreview=true
  -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1;
  echo "exit: $?"` — gate on the exit code; JaCoCo 80% per class applies to the new records.
- Frontend: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`.

## 7. Not in scope / deliberate divergences

- Pairing beyond tide + supermoon; ranking, bits, bands and the badge logic.
- An unmerged supermoon (single dark-sky chip) and an unmerged tide run's facts, title and door — only
  S2/S4's bars reach unmerged tide cards.
- The alignment date on UNMERGED tide cards (same ambiguity; follow-up if wanted — done by tide-both-lights W1, whose rows are always dated). Unmerged
  partial-coverage runs still print "peak" on their `figuresFrom` chip today — a pre-existing
  falsehood, named here as a follow-up, not fixed.
- Bold dates in the coincidence lines and join note (plain strings on the wire); `tide` not
  `alignment`; the range fact kept — recorded in coming-up-plan §11.
- Ask's view of a merged entry (§4.2).

## 8. Review record (2026-10-14)

Four read-only lenses. Changes made: the partial-coverage "peak" leak (per-entry fallback) — found by
three lenses; the `aria-hidden` column that would have hidden the button; 52px columns that would wrap
a six-day run at 390px; `.wf-cu-coin-facts` nowrap overflow moved into S1; the gold peak chip
misdescribed as accent, now gated by `data-marked`; marks as block lines (FULL MOON does not fit
inline); `Staged.meta` already stripped of the keys the merge needs (§2.0); a one-day run has no true
peak (row 5 corrected); `enteredWindow` from the bits winner (union start would swallow a badge);
family filter + counts; the 14-day cap; the solid bar keyed to a served `peak` flag on `dayRanges`,
not `action.date`; host takes the winner's `bits`/`interim` explicitly; the reason rule and
`matchingEntry` invariant 2; multi-night join-note wording; mark order fixed host-first; S1 not adding
`dayRanges`; named tests to rewrite; serialisation test home; moonrise test mocks; "metre range";
coming-up-plan §11/§13 updates; Ask/SinceLine consumers recorded. Codex on #1159 added: the All
chip's double count, Ask's timeline listing a live supermoon twice beside a run-hosted card, and
(round 2) pairing before the Plan-horizon eligibility filter drops a Plan-window partner. Decisions taken as defaults (no
owner question needed): every item above, plus R2's cold-DB tie consequence.
