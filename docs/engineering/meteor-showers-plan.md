# Meteor showers: complete the table, per-year peaks, moon-up gate

Owner request, 2026-11-07: the Leonids (peak night 17 Nov 2026) were missing from Coming Up and the
Plan tab because `MeteorHotTopicStrategy.SHOWERS` held only five showers. Three phases, one PR each.

## Sources

Figures come from the IMO 2026 Meteor Shower Calendar (Rendtel, IMO INFO(3-25)) and the IMO working
list of visual showers. `imo.net` is not reachable from the cloud build container, so the values were
taken from indexed text of `cal2026.pdf` and cross-checked against RMG, AMS and EarthSky. Where a
figure could not be read from the IMO text directly it is marked *(working list)* below; the owner
should spot-check those against the PDF before Phase 2 pins per-year dates.

| Shower | IMO max (2026) | Peak night stored (evening date) | ZHR | Radiant | Notes |
|---|---|---|---|---|---|
| Eta Aquariids | 6 May (λ 45.5°) *(working list)* | 6 May | 50 | Dec ≈ −1°, rises ~02:30 BST, dawn ~04:45 | Radiant ≤ ~15° before dawn from 54°N: UK rate at most ~10/hr |
| S. Delta Aquariids | 30 Jul (λ 127°) | 30 Jul | 25 | Dec ≈ −16°, max altitude ~20° near 02:00 | UK rate ~8/hr at best |
| Draconids | 9 Oct 01h UT | 8 Oct | 10 *(working list; the 2026 text gives "5?")* | Draco, high in the NW at dusk | Evening shower |
| S. Taurids | 10 Oct (λ 197°) *(working list)* | 10 Oct | 5 | Broad radiant, S | Slow, fireball-rich |
| N. Taurids | 12 Nov (λ 230°) | 12 Nov | 5 | Broad radiant, S | Slow, fireball-rich |
| Leonids | 17 Nov 23h45 UT | 17 Nov | 15 | Leo, rises ~23:00, highest before dawn | |
| Ursids | 22 Dec 22h UT | 22 Dec | 10 | Near Kochab, circumpolar | |

"Peak night stored" follows the existing convention: `peak` is the evening date of the night of
maximum (`MeteorClarityService` samples `peak + 1` at 00:00).

## Phase 1 — complete the table (urgent: deploy before 17 Nov)

### Shape

`Shower` gains two fields:

- `boolean major` — **true** for Quadrantids, Lyrids, Perseids, Orionids, Leonids, Geminids;
  **false** for the six added minor showers (Eta Aquariids, S. Delta Aquariids, Draconids,
  S. Taurids, N. Taurids, Ursids).
- `Integer ukRate` — the realistic best hourly rate from the UK when the radiant never climbs high
  enough for the ZHR to be a fair guide; `null` otherwise. Eta Aquariids `10`, S. Delta Aquariids
  `8`, everyone else `null`.

### Which showers reach the hot-topic path (Plan tab pill)

Rule: **a shower reaches the hot-topic path only when a UK observer can expect about 10 or more
meteors an hour at its peak** (ZHR × sin(radiant altitude in the best hours), rounded). That is the
`major` flag.

- Leonids (15, radiant ~50–60° pre-dawn → ~12/hr): **hot topic.** The bug being fixed.
- Ursids (10, radiant ~55° pre-dawn → ~8/hr, and a near-full moon most years): almanac only.
- Eta Aquariids (50 → ≤10/hr), S. Delta Aquariids (25 → ~8/hr): almanac only. The headline ZHR is
  the misleading number the owner flagged; a Plan-tab pill saying "Eta Aquariids peak" invites a
  2 a.m. drive for a handful of meteors.
- Draconids (10, usually far less; IMO 2026 "5?"), Taurids (5 each): almanac only. They are worth
  knowing about (Taurid fireballs make a good Coming Up line) but a pill on the Plan tab, which
  answers "is tonight worth going out", over-sells them. Outburst years (Draconids 2011/2018-type)
  are the case this rule misses; that belongs to a per-year table (Phase 2) and is out of scope.

So the hot-topic set grows from five to six (Leonids), and `peakDatesWithin` reads the **major**
showers only: the overhead-clarity scan exists to feed the hot-topic "clear at X of Y" fact, so a
fetch on a minor shower's night would buy nothing.

### Almanac feed (Coming Up)

`MeteorAlmanacSource` keeps listing every shower (all twelve).

- Low-radiant showers (`ukRate != null`): the detail reads, e.g. *"Eta Aquariids peak. The
  catalogue rate is around 50 an hour, but the radiant stays low from the UK, so expect nearer 10
  an hour at best, radiating from the SE. Best watched 03:00–dawn."* Meta carries `ukRate` and
  `lowRadiant=true`. `ComingUpAssembler.enrichMeteor` serves the metric from `ukRate` when present
  (`~10/hr from UK`) instead of the ZHR, and adds an accent fact "radiant low from the UK —
  catalogue rate ~50/hr". Both keys are stripped from the served `meta`.
- Minor showers (`major=false`): meta `minor=true`. They **list but do not badge**: the
  assembler scores them on a separate rarity, `minorMeteorShowerMeanGapDays` (default 60.875 =
  365.25 / 6: six minor showers a year, and a reader is not surprised by *which* minor shower it
  is), so bits = log2(60.875) + 1.0 ≈ 6.93, under the 7.5 announce edge, the same rung as
  supermoons. `rarityIsStatable = false` for them, so no sentence states the figure.
- Coming Up badge census (`ComingUpAnnualBadgeCensusTest`): 11 → 12 badge arrivals a year, meteor
  5 → 6 (the Leonids). Interrupts are unchanged (the eclipse alone). This is a re-census and the
  test's comments say so.

### Tests

- `MeteorHotTopicStrategyTest` / `BranchTest`: Leonids fire in a 14–17 Nov window under a dark
  moon; a minor shower (e.g. Taurids 12 Nov, Eta Aquariids 6 May) never fires a hot topic;
  `peakDatesWithin` returns a major peak and not a minor one.
- `AlmanacEphemerisSourcesTest`: a 7–30 Nov 2026 range returns the Leonids (and N. Taurids,
  `minor=true`); Eta Aquariids detail names the UK rate and not the ZHR as the expected rate;
  existing range assertions updated for the added showers.
- `ComingUpAssembler` tests: metric from `ukRate`, the low-radiant fact, minor scoring below
  announce, keys stripped.
- `ComingUpAnnualBadgeCensusTest`: 12 / meteor 6; `HotTopicInputsEquivalenceTest` checked.

### Known limit found while planning

On 17 Nov 2026 the moon is 50.1% lit at noon UTC, so the **current** ≥50% gate drops the Leonids
from the hot-topic path even after Phase 1, and the almanac row carries the "washed out" caveat,
although the moon sets at ~23:35 and the Leonids are a post-midnight shower. Phase 3 fixes this; it
must also ship before 17 Nov for the Plan tab pill to appear.

### Plan review, round 1 (corrections that supersede the text above)

1. **Order.** The moon-up gate (Phase 3) ships *second*, before per-year dates, so the Leonids pill
   can appear on 17 Nov. Execution order: 1 → 3 → 2 → 4.
   (Values in Phase 1 were corrected on 2026-11-07 from the IMO 2026 Table 5 itself, once imo.net
   was reachable: Quadrantids ZHR 80, Eta Aquariids 5 May, Draconids ZHR 5, S. Taurids 5 Nov ZHR 7.)
2. **First-of-type prose.** `ComingUpAssembler.markFirstOfType` gives `prose` (and the client's
   feature card, `comingUpFeed.js` `isFeature`) to the first meteor entry. A minor shower must
   never take it: N. Taurids (12 Nov) would otherwise strip the Leonids' card. Minor meteors are
   skipped by first-of-type; a test puts N. Taurids and Leonids in one window.
3. **Final strings** (54.8°N, 2°W, local clock):

   | Shower | bestHours | radiant | ukRate | major |
   |---|---|---|---|---|
   | Eta Aquariids | 03:00–dawn | E | 5 | no |
   | S. Delta Aquariids | 01:00–04:00 | S | 8 | no |
   | Draconids | 20:00–23:00 | NW | – | no |
   | S. Taurids | 23:00–03:00 | S | – | no |
   | N. Taurids | 22:00–02:00 | S | – | no |
   | Leonids | 03:00–dawn | SE | – | yes |
   | Ursids | 00:00–dawn | N | – | no |

   Lyrids' radiant is corrected from NE to SE (ESE–SE at 50–65° in 02:00–04:00 BST).
4. **Metric.** Plain `~5/hr` from `ukRate` (no "from UK" suffix, which would imply the other rows
   are not UK rates); the catalogue rate goes in the accent fact "radiant low from the UK —
   catalogue rate ~50/hr".
5. **Minor rarity, stated honestly.** `minorMeteorShowerMeanGapDays` (60.875) is documented as a
   configured figure: "the six minor showers together peak about every two months; set so a minor
   shower sits on the supermoon rung and lists without badging". The existing property's
   "regardless of how many other showers" sentence is kept and scoped to named major showers.
   `application-example.yml`, the ladder Javadoc in the census test and
   `reference/configuration.md` are updated. Census asserts meteor arrivals 12, meteor badges 6.
6. `minor`, `ukRate`, `lowRadiant` are all stripped from served `meta`.
7. **Ask** caps `detail` at 200 characters (`AskTools`); the almanac detail, moonlit suffix
   included, must stay under 200 for every shower (a test asserts it).
8. A table test asserts every shower has a distinct peak `MonthDay` (the entry id is
   `meteor:date:date`).

## Phase 2 — per-year peak dates, stored in the database (detailed 2026-11-07)

The per-year peaks live in a table, not in code, so the Phase 4 job can write them (the owner's
decision: the refresh updates the table itself, like the WorldTides refresh).

### Schema (V222)

`meteor_shower_peak`:

| column | type | |
|---|---|---|
| `shower_code` | `VARCHAR(3) NOT NULL` | IAU code: QUA LYR ETA SDA PER DRA ORI STA NTA LEO GEM URS |
| `year` | `INTEGER NOT NULL` | the IMO calendar year the row came from |
| `peak_night` | `DATE NOT NULL` | evening date of the night of maximum (rule below) |
| `max_utc` | `TIMESTAMP WITH TIME ZONE NULL` | the maximum time when IMO gives one |
| `zhr` | `INTEGER NOT NULL` | IMO's figure for that year (`80+` → 80) |
| `source_url` | `VARCHAR(255) NOT NULL` | e.g. `https://www.imo.net/files/meteor-shower/cal2026.pdf` |
| `source_sha256` | `CHAR(64) NULL` | null for migration-seeded rows; Phase 4 writes it |
| `fetched_at` | `TIMESTAMP WITH TIME ZONE NOT NULL` | |

PK `(shower_code, year)`. Written for Postgres. Local H2 gets the table from `ddl-auto: update`
with no rows, so local runs use the code fallback. A Testcontainers migration test (under
`integration/`, run only in CI) asserts the 24 seeded rows.

### Seed (2026 and 2027, read from the IMO calendars on 2026-11-07)

Peak nights after the evening-date rule:

| code | 2026 night | 2026 ZHR | 2026 max | 2027 night | 2027 ZHR | 2027 max |
|---|---|---|---|---|---|---|
| QUA | 01-03 | 80 | 01-03 21:00Z | 01-03 | 80 | 01-04 03:25Z |
| LYR | 04-22 | 18 | 04-22 19:40Z | 04-22 | 18 | 04-23 01:40Z |
| ETA | 05-05 | 50 | – (May 06) | 05-05 | 50 | 05-06 09:00Z |
| SDA | 07-30 | 25 | – (Jul 31) | 07-30 | 25 | – (Jul 31) |
| PER | 08-12 | 100 | 08-13 03:00Z | 08-12 | 110 | 08-13 09:00Z |
| DRA | 10-08 | 5 | 10-09 01:00Z | 10-08 | 5 | 10-09 07:00Z |
| ORI | 10-20 | 20 | – (Oct 21) | 10-21 | 20 | – (Oct 22) |
| STA | 11-05 | 7 | – | 11-06 | 7 | – |
| NTA | 11-12 | 5 | – | 11-13 | 5 | – |
| LEO | 11-17 | 15 | 11-17 23:45Z | 11-17 | 15 | 11-18 06:00Z |
| GEM | 12-13 | 150 | 12-14 14:00Z | 12-14 | 150 | 12-14 20:00Z |
| URS | 12-22 | 10 | 12-22 22:00Z | 12-22 | 10 | 12-23 04:00Z |

A range ("02h to 04h UT") is stored as its midpoint.

### Evening-date rule (one function, `MeteorPeakNight.of(...)`, unit-tested)

- **With a maximum time:** the peak night is the evening before the UK local midnight nearest the
  maximum. A tie goes to the earlier night.
- **With a date only:** the peak night is the table date minus one day when the shower's best hours
  start between 00:00 and 06:00 (a pre-dawn shower). Otherwise it is the table date.

The seed table above is exactly this rule applied to the IMO text, and the unit tests pin each
row. **Code fallback correction:** `SHOWERS`' Orionids `MonthDay` moves from 10-21 to 10-20, the
rule applied to IMO's standard 21 Oct (AMS also gives "peak night 20–21 Oct").

### Code

- `Shower` gains `String code` (first field), and the codes are distinct (test).
- `MeteorPeakCalendar` (`@Component`) holds an immutable snapshot `Map<String, Map<Integer,
  Peak>>`, loaded from `MeteorShowerPeakRepository.findAll()` on `ApplicationReadyEvent`. It is
  replaceable with `reload()`, which Phase 4 calls. A failed load logs and keeps the code
  fallback; it never fails start-up.
  - `LocalDate peakNight(Shower s, int year)` returns the row, else `s.peak().atYear(year)`.
  - `int zhr(Shower s, int year)` returns the row, else `s.zhrPeak()`.
  - `List<LocalDate> majorPeakNightsWithin(List<LocalDate> nights)` checks each night's year and
    the year after (a Quadrantid night in early January belongs to that calendar year).
- `MeteorHotTopicStrategy.detect`, `MeteorAlmanacSource` and `MeteorClarityService` take the
  calendar and use `peakNight` / `zhr` / `majorPeakNightsWithin`. The static `peakDatesWithin`
  goes. The ZHR in the hot-topic fact, the almanac detail and `meta.zhr` is the year's figure.
- Tests that build these classes pass a calendar over a mocked repository. Mocking the
  repository is not mocking the rule. The census and `HotTopicInputsEquivalenceTest` use an empty
  repository (code fallback), so their counts must not change. One added test proves a row
  overrides the fallback, e.g. Orionids 2027 → 21 Oct, N. Taurids 2027 → 13 Nov.
- `docs/engineering/reference/database-migrations.md` gets a V222 line.

### Phase 2 plan review, round 1 (supersedes the text above where they differ)

1. **`source_sha256 VARCHAR(64)`**, not `CHAR(64)`. Postgres reports CHAR as `bpchar`, and
   Hibernate `validate` in prod rejects it against a `String`. Add `CHECK (source_sha256 IS NULL OR
   length(source_sha256) = 64)`.
2. **`calendar_year`**, not `year`. H2 rejects `year` as a column name, and `ddl-auto: update`
   would only log it, leaving the table missing locally.
3. **The evening-date rule changes to "a night runs 18:00 to 18:00 UK local time"**:
   `peak_night = localDate(max − 18 h)`.
   - The nearest-midnight rule put the Geminids 2026 maximum (14 Dec 14h UT) on the night of
     14/15. IMO's reliable maxima run from 13 Dec 21h to 14 Dec 18h UT, which covers the 13/14
     dark hours and not the 14/15 ones. AMS also gives 13–14 Dec.
   - The new rule gives **12-13** for the Geminids 2026 and leaves every other seeded row
     unchanged (all 22 times re-checked).
   - The date-only rule is unchanged.
4. **Two more seed times** from the IMO prose. Neither changes its night.
   - LYR 2027: `2027-04-23 01:40Z` ("expected on April 23, 01h 40m UT").
   - GEM 2027: `2027-12-14 20:00Z` ("centred around 20h UT").

   Phase 4's parser must read prose times as well as "Maximum:" lines, so that the seed and the
   parser agree. The Phase 4 tests assert exactly this seed table.
5. **Load order.**
   - `MeteorPeakCalendar` loads in `@PostConstruct`, inside a try. Flyway has run by then.
   - It keeps the snapshot in an `AtomicReference` of `Map.copyOf` maps, so the first briefing
     tail, the scheduler catch-up and the almanac warm-up all see the table.
   - `reload()` publishes a `MeteorPeakCalendarReloaded` application event, and `AlmanacService`
     listens and evicts its cache. An event, not a call, avoids a dependency cycle.
   - Tests:
     - a failed load keeps the code fallback;
     - reload swaps the snapshot and publishes the event;
     - a row overrides the fallback.
6. **One scan for all three classes.** `List<ShowerNight> peakNightsIn(LocalDate from, LocalDate
   to, boolean majorOnly)` scans calendar years `from.year − 1 … to.year + 1` and returns
   `(shower, peakNight, zhr)` whose night lies in `[from, to]`, sorted by night. `detect`,
   `MeteorAlmanacSource.events` and `MeteorClarityService` (via the nights' min/max) all use it.
7. **Ripple to update.**
   - Every `new Shower(...)` (e.g. `MeteorMoonGateTest:40`).
   - Every constructor caller:
     - `HotTopicInputsEquivalenceTest:167`
     - `ComingUpAnnualBadgeCensusTest:133`
     - `MeteorHotTopicStrategyTest:46`
     - `MeteorHotTopicStrategyBranchTest:45`
     - `AlmanacEphemerisSourcesTest` (all sites)
     - `MeteorClarityServiceTest:43`
     - `BriefingServiceTest:304`
   - The `peakDatesWithin` tests move to `MeteorPeakCalendarTest`.
   - Tests that hard-code the Orionids on 2026-10-21 move to 10-20 or use another shower:
     - `MeteorHotTopicStrategyTest:276`
     - `MeteorHotTopicStrategyBranchTest:32`
     - `AlmanacEphemerisSourcesTest:60,123,128`
     - `MeteorMoonGateTest:56` (re-check that verdict for the 20th with the real calculators)
   - The census is **re-run**, not assumed. If its counts change, stop and report.
8. **Coverage.** The id class uses Lombok `@EqualsAndHashCode` (`lombok.config` excludes generated
   code). `MeteorPeakNight.of` is tested on both branches plus the 18:00 boundary. A distinct-night
   test runs per year over the seed rows (the entry id is `type:start:end`).

## Phase 3 — moon-up gate (detailed 2026-11-07)

**Problem.** The hot-topic gate drops a shower when `LunarPhaseService.getIlluminationFraction(peak)`
(noon UTC at 0°, 0°) is ≥ 0.5, and the almanac's `washedOut` caveat uses the same figure. Neither asks
whether the moon is up while the shower is worth watching. 17 Nov 2026: 50.1% lit, sets ~23:35 at
54.8°N, while the Leonids are best 03:00–dawn, so they are wrongly dropped and wrongly caveated.

**Predicate (one place, `MeteorMoonGate`, a `@Component`).**
- `bestHours` is parsed into a window on the shower's night (`peak` is the evening date):
  `"HH:mm–HH:mm"` or `"HH:mm–dawn"`; an hour ≥ 12 falls on the evening date, < 12 on the next
  morning; `dawn` is `SolarCalculator.civilDawn` at the reference point on the morning date. Times
  are `Europe/London`. The display string stays as it is (it is served); a test parses all twelve.
- Reference point 54.8°N, 2.0°W (the region's centre; the moon's altitude differs by well under a
  degree across it). Constants, not config.
- Sample the window every 15 minutes, both ends inclusive, with the existing `LunarCalculator`
  bean: a sample is **bright-moon-up** when `illumination ≥ 0.5` and `altitude > 0`.
- **Washed out** iff bright-moon-up samples are more than half of all samples.
- The result record `MoonOverShower(int illuminationPct, boolean washedOut, boolean downThroughout)`
  where `illuminationPct` is the mean illumination over the window rounded to an integer (it is what
  the chip and the meta report, so the number shown matches the night, not noon), and
  `downThroughout` is true when every sample has `altitude ≤ 0`.

**Hot topic.** `detect` drops a shower iff `washedOut`. In `buildTopic`, `darkEnough` becomes
`illuminationPct < 25 || downThroughout`; when `downThroughout` and the moon is ≥ 25% lit, the chip
reads `"{pct}% · down in the best hours"` and the cue reads `"moon down, good viewing"`; otherwise the
existing copy. The `MAX_ILLUMINATION` and `MOON_DARK_PCT` Javadocs are rewritten to the new rule.

**Almanac.** `MeteorAlmanacSource` takes `MeteorMoonGate` (constructor change; update every
`new MeteorAlmanacSource(...)` in tests, the census included). `washedOut` and the moonlit suffix use
the gate; `moonIllumination` meta reports the gate's `illuminationPct`. When the moon is bright but
down throughout, no caveat. The `WASHED_OUT_ILLUMINATION_PCT` comment about rounding is rewritten:
the flag is now a moon-up decision, so a row can say "50% lit" with no caveat, and the detail must
say why: append `" The moon is %d%% lit but down during the best hours."` in that case (keep every
detail < 200 characters; the existing test covers it).

**Tests (real `LunarCalculator`, `SolarCalculator`, no mocks of astronomy):**
- Leonids 17 Nov 2026: not washed out, `downThroughout` true; the strategy fires a Leonids topic for
  a 14–17 Nov 2026 window with the "down in the best hours" chip; the almanac row has no
  `washedOut` and says the moon is down.
- Ursids 22 Dec 2026 (96%, up all night): washed out (they are minor, so almanac only).
- Geminids 14 Dec 2026 (24%): not washed out.
- A synthetic window where a bright moon is up for exactly half the samples: not washed out (the
  rule is "more than half").
- The window parser: every `SHOWERS` entry parses; `22:00–02:00` spans midnight; `dawn` resolves to
  civil dawn on the morning date; a BST night (Perseids, 12 Aug).
- Census re-run: bits are unaffected, so the counts must not change.
- `docs/engineering/reference/` mentions of the 50% gate are updated (grep "50%" and "moon gate").

### Phase 3 plan review, round 1 (supersedes the text above where they differ)

Verdicts computed with the real calculators for all 24 shower nights of 2026–2027: the rule flips
three nights from dropped to shown (Orionids 2026, moon sets 02:56; Leonids 2026, down throughout;
Perseids 2027, sets 01:01) and none the other way.

1. **Both constructors change.** `MeteorHotTopicStrategy` and `MeteorAlmanacSource` take
   `MeteorMoonGate` and drop `LunarPhaseService` where nothing else in the class uses it. Their unit
   tests mock the gate (mocking the gate is not mocking astronomy) and lose their illumination
   stubs (strict Mockito would flag them). `MeteorMoonGate` itself is tested with the real
   `LunarCalculator`, `SolarCalculator` and `MoonriseMoonsetCalculator`.
   `HotTopicInputsEquivalenceTest` and `ComingUpAnnualBadgeCensusTest` build a real gate.
2. **Moon up for part of the window.** `MoonOverShower` gains `Optional<LocalTime> moonset` and
   `Optional<LocalTime> moonrise`, set when the moon sets or rises inside the window (from
   `MoonriseMoonsetCalculator` on the evening and morning dates, UK local time, minutes truncated).
   When the shower is not washed out, the moon is ≥ 25% lit and it is up for part of the window:
   - chip `"{pct}% · sets HH:mm"` or `"{pct}% · rises HH:mm"`;
   - almanac suffix `" The moon is %d%% lit and sets around %s."` (or "rises around").
   Moon down throughout keeps "down in the best hours". Every detail stays under 200 characters,
   proved by a test over `SHOWERS` that renders each template with each suffix at 100%.
3. **Fail soft.** The window is parsed once per call inside a try. An unparseable `bestHours`, or a
   `dawn` end at or before the start, falls back to the old noon rule (illumination ≥ 0.5 at noon
   means washed out) and logs a warning, so one bad string can never empty the hot-topic list
   (`HotTopicAggregator` has no catch). Phase 4 will bring new strings through this path.
4. **Sampling.** Step the instant with `ZonedDateTime.plusMinutes(15)` from the start to the end,
   inclusive; build the end from the start plus the window's duration, so a clock-change night
   still samples the true elapsed time.
5. **Horizon.** `altitude > 0` is geometric altitude of the moon's centre. It is not refraction
   corrected, so it can disagree with `MoonriseMoonsetCalculator` by a minute or two at the edge
   (Perseids 2027: sets 01:01, the 01:00 sample reads −0.3°). That is accepted and documented in
   the Javadoc; the boundary sample counts as down.
6. **Reported figure.** The chip and the almanac now report the mean illumination over the window,
   not noon UTC (Leonids 2026 reads 57%, not 50%).
7. **Exact half** stays "not washed out". A package-private `evaluate(ZonedDateTime start,
   ZonedDateTime end)` lets the test build an exact-half window.
8. Rewrite the class and `detect` Javadocs of `MeteorHotTopicStrategy` to the new rule.

## Phase 4 — the IMO calendar refresh keeps the table current

The owner's decision: the job **updates the table itself**, like the WorldTides refresh, and an
email is only the exception path, as `sendTideSourceChangeAlert` is for tides.

**Source.** `https://www.imo.net/files/meteor-shower/cal{year}.pdf`, published about six months
ahead (on 2026-11-07, 2026 and 2027 return 200 and 2028 returns 404). Each calendar has the same
**Table 5, "Working List of Visual Meteor Showers"**, one row per shower keyed by IAU number and
code, for example:

```
 Leonids (013 LEO)          Nov 06–Nov 30   Nov 17     235 ◦. 27   152◦   +22◦   71     2.5    15
 Quadrantids (010 QUA)      Dec 28–Jan 12   Jan 04     283 ◦. 15   230◦   +49◦   41     2.1    80+
```

and a per-shower section whose first line carries the time, when IMO gives one:
`Leonids (013 LEO)` / `Active: November 6–30; Maximum: November 17, 23h 45m UT (…); ZHR ≈ 15`.

**Job.** `imo_calendar_refresh`, `DynamicSchedulerService` target, seeded by migration (`CRON`,
Mondays 03:17 UK, `ACTIVE`; weekly, because a revision ahead of an outburst can land mid-year).
For each year from this year to this year + 1:
1. Fetch the PDF (5 s connect / 30 s read, `application/pdf` only, at most 10 MB). A 404 is
   "not published yet": no write, no alert.
2. Skip it when the SHA-256 matches the stored `source_sha256` for that year.
3. Extract text with Apache PDFBox (new dependency, Apache 2.0) and parse Table 5 rows for the
   codes `SHOWERS` knows; take the maximum time from that code's section where present. Leading
   `+`/`≈` and trailing `+`/`(?)` on a ZHR are dropped; `Var` keeps the catalogue ZHR.
4. **Validate before writing anything for the year.** Every major shower (QUA, LYR, PER, ORI, LEO,
   GEM) must parse; every parsed date must fall within ±3 days of the shower's `MonthDay` (the
   Quadrantids across the year boundary); ZHR must be 1–500. If any check fails, write nothing for
   that year, keep the stored rows, and send one admin alert naming the year, the failing
   shower and the URL. That is the "IMO changed its layout" signal.
5. Upsert the year's rows in one transaction. Diff against the stored rows; when a peak night
   moves or a ZHR changes for a year already stored, send one admin alert listing before and
   after (IMO revised its calendar). A first fill for a new year is logged and reported in the run
   summary, not emailed.
6. Reload `MeteorPeakCalendar`. Report `SchedulerFireOutcome` like `tide_refresh` ("2027 stored ·
   2028 not published").

Showers the code does not know are ignored: which showers PhotoCast shows, and with what UK
judgement (major/minor, UK rate, compass, best hours), stays a reviewed code decision. A
fetch failure is logged; three consecutive failed weeks send one alert (a moved URL).

**Tests.** The 2026 and 2027 PDFs are committed as test resources (`src/test/resources/imo/`) and
the parser is tested against both: every expected `(code, peak_night, zhr)` for the twelve
showers, the Leonids' 23h45 time, the Quadrantids' `80+`, and the Draconids' `5 (?)`. A mutated
fixture (a row removed, a date moved by five days) proves validation refuses to write. The job
is tested with a mocked client and an injected `Clock`. Production must be able to reach
`www.imo.net` (add it to any egress allowlist on the host).

### Phase 4 detail (2026-11-07, after Phase 2 was built)

Phase 2 shipped `meteor_shower_peak` (`calendar_year`, `VARCHAR(64)` hash), `MeteorPeakNight`
(`of(Instant)` with the 18:00–18:00 night, `ofDateOnly(date, bestHours)`), and
`MeteorPeakCalendar.reload()`, which publishes the event that evicts the almanac cache. Phase 4
builds on all three and changes none of them.

**Parser (`ImoCalendarParser`, pure, no Spring).** It takes the extracted text and the calendar
year and returns `Map<String code, ParsedPeak(LocalDate imoDate, Instant maxUtc /*nullable*/,
int zhr)>`.

- **Table 5 rows.** Match a line containing `(NNN CODE)` followed by an activity range, a `Mon dd`
  maximum and the ZHR as the last token. Take the FIRST such row per code; the 2027 calendar has
  extra "trails & filament" rows for PER and LEO with a different shape.
- **ZHR.** Drop a trailing `+` and `(?)`. `Var` keeps the code's catalogue ZHR.
- **The year of the date.** The month and day come from the row, and the year is the calendar
  year (the Quadrantids "Jan 04" in `cal2027` is 2027-01-04).
- **Maximum time.** These sources apply in order, all scoped to the code:
  1. The per-shower section: the heading line ending in `(NNN CODE)`, then within 3 lines
     `Maximum: <Month> <d>, HHh [MMm] UT`. A range `HHh to HHh UT` takes its midpoint.
  2. The prose: a sentence naming `(NNN CODE)` with `<Month> <d>[,] [close to|shortly after|around|
     centred around|expected on …] HHh [MMm] UT` within 2 lines. Seen in 2026/27:
     - QUA 2026 "peak on January 3 close to 21h UT"
     - URS 2026 "reach their maximum on December 22, 22h UT"
     - LYR 2027 "expected on April 23, 01h 40m UT"
     - GEM 2027 "centred around 20h UT"
  3. None: date only.

  A time whose month and day differ from the Table 5 date by more than one day is ignored.
- **Peak night.** `MeteorPeakNight.of(maxUtc)` when there is a time, else
  `MeteorPeakNight.ofDateOnly(imoDate, shower.bestHours())`.
- **The decisive test.** It extracts both committed PDFs with PDFBox (not pdftotext; the layout
  differs) and asserts the parser returns exactly the V222 seed: the 24 `(code, night, zhr,
  max_utc)` rows, using `MeteorSeed` from the Phase 2 tests. If PDFBox's text breaks a pattern,
  fix the pattern, never the expectation.

**Validation (per year, before any write).**
- All six major codes are present.
- Every parsed night is within ±3 days of the code's fallback `MonthDay` in that year. For the
  Quadrantids, compare across the year boundary.
- ZHR is 1–500, and the nights are distinct within the year.
- A failure writes nothing for that year.

**State: `imo_calendar_fetch` (V223).** Columns: `id`, `calendar_year`, `attempted_at`,
`outcome`, `http_status`, `sha256`, `detail VARCHAR(500)`. The outcome is one of
`STORED | UNCHANGED | NOT_PUBLISHED | FAILED | INVALID`, stored as a string with a CHECK.

Use a plain `VARCHAR`, not a JPA enum, so `LocalH2EnumWidener` is not needed. If an
`@Enumerated(STRING)` field is used anyway, add the `TARGETS` line.

Alert decisions read this log, as tides read `tide_fetch`, so nothing lives in memory:
- **INVALID:** alert unless the previous row for the year was INVALID with the same sha.
- **FAILED:** alert only when this is the third FAILED in a row for the year.
- **Revision:** a STORED write that changes the night or ZHR of an existing row alerts once with
  before and after.
- **First fill:** a STORED write into a year with no rows is not emailed. It is reported in the
  fire outcome.

**Job.**
- `ImoCalendarRefreshService`, registered as `imo_calendar_refresh` with `DynamicSchedulerService`.
  Use the plain `Runnable` overload, and follow how `refresh_token_cleanup` is wired end to end:
  the `SchedulerJobCatalogue` entry with `usesClaude=false`, the V81-style seed row and
  `docs/engineering/reference/platform.md`.
- The V223 seed row is `CRON`, `0 17 3 * * MON`, in the zone the scheduler already uses for UK
  crons. Read `DynamicSchedulerService` near its zone constants.
- **Years:** today's UK year and the next, via the injected `Clock` and `ForecastHorizon`.
- **Fetch.** `ImoCalendarClient`, a `RestClient` with the shared timeout pattern
  (`EnvironmentAgencyGaugeClient`).
  - Base URL is `photocast.meteor.imo.base-url`, default `https://www.imo.net/files/meteor-shower/`.
    Put it in `application-example.yml` and `reference/configuration.md`.
  - Connect timeout 5 s, read timeout 30 s.
  - Refuse a body over 10 MB, and refuse a content type other than `application/pdf`.
  - A 404 means `NOT_PUBLISHED`.
- **Write.** Upsert the year's rows in one transaction, with `source_url`, `source_sha256` and
  `fetched_at`. Then call `MeteorPeakCalendar.reload()`.
- **Outcome.** Report a `SchedulerFireOutcome`: `SUCCEEDED` with "2026 unchanged · 2027 stored",
  `PARTIAL` when a year failed or was invalid. A failure never throws out of the job.
- **Alerts.** One new method, `AdminAlertService.sendMeteorCalendarAlert(String subject, String
  body)`, following `sendTideSourceChangeAlert`. A failed send is logged and never fails the job.

**Dependency.** Add `org.apache.pdfbox:pdfbox` at its current 3.x release, and check
`npm`-style audit equivalents: the OWASP dependency check runs in CI. Bound every load: use
`Loader.loadPDF(byte[])` under the 10 MB cap and stop after 60 pages.

**Fixtures.** `backend/src/test/resources/imo/cal2026.pdf` and `cal2027.pdf` (owner approved).
The planner downloaded them to the session scratchpad. The build agent copies them from
`/tmp/claude-0/-home-user-goldenhour/1e6a23e1-1607-533a-bbec-d917a647aca7/scratchpad/imo/`.

**Docs.**
- `reference/platform.md`: the job row in the scheduler table.
- `reference/conditions.md` or `hot-topics-tides-eclipse.md`, whichever already mentions meteors:
  a paragraph on the meteor calendar and its refresh.
- `database-migrations.md`: the V223 line.
- The changelog.

## Landing site copy (lands with Phase 1)

Plain, first-person-where-needed, short sentences, matching `landing/faq.html` and
`landing/guide/*.html` as they read today:

- `landing/faq.html`, data-sources table, after the NOAA SWPC row:
  `International Meteor Organization (IMO)` · `Meteor Shower Calendar: peak dates, peak rates (ZHR) and radiants` ·
  `Meteor shower dates in Coming up and on At a Glance, with a realistic UK rate for showers whose radiant stays low`.
- `landing/acknowledgements.html`, data providers table, after NLCNET:
  `International Meteor Organization (IMO)` · `The annual Meteor Shower Calendar: peak dates and rates for every shower PhotoCast lists` ·
  `Credited with thanks to the IMO (imo.net) and the observers who contribute to it`.
- `landing/guide/coming-up.html`: the Meteors `<dd>` becomes "The peak of a meteor shower, with
  dates from the International Meteor Organization's calendar. The date is fixed, but you need a
  clear, dark sky to see it." The key-figure paragraph (line 78) adds, after the catalogue-rate
  clause: "For showers whose radiant stays low from the UK, such as the Eta Aquariids, the figure
  is the rate you can realistically expect here instead."
- `landing/guide/night-sky.html`, the Meteor showers bullet: add "The smaller showers, such as the
  Taurids and the Draconids, are listed in Coming up but don't appear on At a Glance."
