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

## Phase 2 — per-year peak dates

`MonthDay peak` stays as the fallback; a `Map<Integer, LocalDate>` per shower (2026–2028, IMO
calendar) overrides it. One static lookup, `peakIn(int year)`, used by `detect`, `peakDatesWithin`,
`MeteorClarityService` (through `peakDatesWithin`) and `MeteorAlmanacSource`. Detailed when Phase 1
has merged.

## Phase 3 — moon-up gate

Replace "illumination ≥ 50% at noon on the peak date" with "a moon ≥ 50% lit is above the horizon
for most of the shower's `bestHours`", sampled hourly with `LunarCalculator.calculate(time, lat,
lon).altitude()` at a UK reference point; `bestHours` becomes structured (start hour, end hour or
dawn). The almanac `washedOut` caveat uses the same predicate. Detailed when Phase 2 has merged.

## Phase 4 — weekly IMO calendar watch (owner request, 2026-11-07)

The per-year table (Phase 2) runs out. Nobody will remember to look for the 2029 calendar, so a
weekly job looks for it and alerts the admins. **It alerts; it never edits the table.** The shower
table is code reviewed in a PR, the IMO calendar is a typeset PDF whose layout changes, and a
parser that silently wrote wrong peak dates would be worse than the gap it closes.

- Job `imo_calendar_check`, registered with `DynamicSchedulerService.registerJobTarget` and seeded
  by a migration in the `V81__refresh_token_cleanup_scheduler.sql` shape: `CRON`, weekly
  (Monday 06:17 UK, off the hour), `ACTIVE`. No Claude spend.
- **Coverage check (needs no network).** Read the last year in the per-year peak table. When today
  is within 13 months of that year's end and the following year has no row, send one admin alert
  ("the meteor peak table ends with 2028; add 2029 from the IMO calendar"). This is the safety net
  that works even if imo.net changes its URLs.
- **Publication check.** For each year from this year to this year + 2 that the table does not
  cover, `GET https://www.imo.net/files/meteor-shower/cal{year}.pdf` (5 s connect / 20 s read
  timeout, `Content-Type` must be `application/pdf`). A 200 is "published": one admin alert
  naming the year and the URL. For a year the table does cover, store the PDF's SHA-256 the first
  time it is seen and alert once when it changes (IMO revises a calendar mid-year when an outburst
  is predicted).
- State in a small table `imo_calendar_check (year PK, first_seen_at, sha256, alerted_at)` so each
  finding alerts **once**, not every Monday. A failed fetch is logged and retried next week; it
  never alerts on its own (three consecutive failures do, so a moved URL is noticed).
- Client: an `@HttpExchange` interface like the others in `client/`, with tests on a mock server;
  the job's tests mock the client and the clock. The cloud build container cannot reach imo.net;
  production must (add it to any egress allowlist there).
- `docs/engineering/reference/platform.md` (scheduler/admin) and `configuration.md` gain a line.
- Lands after Phase 2, since it reads Phase 2's table.

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
