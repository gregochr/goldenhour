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

## Phase 2 — per-year peak dates, stored in the database

Rewritten 2026-11-07 after the owner asked that the table update itself (Phase 4), as the
fortnightly WorldTides refresh does for tides. The per-year peaks therefore live in a table, not in
code, so a job can write them.

- `meteor_shower_peak (shower_code, year, peak_night, max_utc NULL, zhr, source_url, source_sha256,
  fetched_at)`, PK `(shower_code, year)`. `shower_code` is the IAU three-letter code (`LEO`,
  `GEM`, …), which `Shower` gains as a field. Seeded by migration for 2026 and 2027 from the IMO
  calendars (both are already published; 2028 is not, so it falls back).
- **Evening-date rule** (one function, unit-tested): with a maximum time, `peak_night` is the
  evening before the UK local midnight nearest the maximum (Leonids 17 Nov 23h45 UT → 17 Nov;
  Draconids 9 Oct 01h UT → 8 Oct; Geminids 14 Dec 14h UT → 14 Dec). With a date only, it is the
  table date minus one day when the shower's best hours start between 00:00 and 06:00 (a pre-dawn
  shower: Eta Aquariids 6 May → 5 May), else the table date.
- `MeteorPeakCalendar` (bean): `peakNight(shower, year)` and `zhr(shower, year)` read an in-memory
  snapshot of the table, reloaded at start-up and after each refresh; a missing row falls back to
  the code's `MonthDay` and catalogue ZHR. `detect`, `peakDatesWithin` (so `MeteorClarityService`)
  and `MeteorAlmanacSource` all read it, so the clarity scan, the Plan tab and Coming up agree.
  `peakDatesWithin` stops being static; the strategy and the clarity service get the bean.

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
