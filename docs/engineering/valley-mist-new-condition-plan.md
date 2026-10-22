# Valley mist: say "new", not "recurrent", until it has a record

Owner report, 2026-10-22 (v2.30.0, Coming up tab): the Valley mist row reads
`Valley mist RECURRENT · none in the last 60 days · about one a week · counts as a standout above 8/10`.
A feature that shipped on 2026-10-21 and has never recorded a likely day cannot be called recurrent,
and "about one a week" is not a measurement either.

## What is wrong, and why

`ComingUpConditionsBuilder.buildMist` takes both claims from configuration, not from data:

- **The cadence word** is `ComingUpScoringProperties.Cadence.mist` = `"recurrent"`, authored, never derived.
- **"about one a week"** is `frequencyPhrase(rarity(fallbackMeanGapDays = 6.0))`: the config's first
  guess, pinned until the `MIST_CALC` log matures (valley-mist-plan M1b). Printed as a frequency, it
  reads as an observed rate.

The other three rows are different: tides are on an ephemeris, and dust and inversions have months
of trailing history behind their words (2 plumes, 18 strong mornings). Leave them alone.

## The change (backend only, one class plus its test)

1. **Cadence word.** In `buildMist`, use the configured word only when the trailing window holds at
   least **2** likely days (a second occurrence is the least that "recurs" can mean; a named constant,
   `MIST_RECURRENT_MIN_DAYS = 2`). Below that the cadence is **`"new"`**. The frontend prints
   `condition.cadence` verbatim (`WindowComingUpConditions.jsx`), so no client change.
2. **Frequency phrase.** While the rarity is still the config fallback (always, for mist, until the
   `MIST_CALC` switch-over named in the plan), the quant line must not state a frequency. It reads
   `no frequency measured yet · counts as a standout above 8/10 (early threshold)`. The rarity bits
   still feed the scores and the badge exactly as now; only the words change.
3. **Unchanged:** `rateLabel` (`none in the last 60 days` / `N likely days since …` is already
   honest), the occurrences, the peak, `interim`, the config key (kept as the word used once the
   record exists), dust/inversion/tides.

## Tests (`ComingUpConditionsBuilderTest`)

- No likely day → cadence `"new"`, quant starts `"no frequency measured yet"` and does not contain
  `"about one"`.
- Exactly one likely day → still `"new"`.
- Two likely days → the configured `"recurrent"` (the existing `buildMist_trailingHistoryCountsLikelyDaysOnly`
  already has two; keep its assertion).
- A failed trailing read → `"new"` (nothing observed is not a record).
- The other three conditions' cadence and quant lines unchanged (an existing assertion or one new one).

## Docs

- CLAUDE.md's valley mist bullet (M1b paragraph, the "Coming up gained a fourth condition" sentence):
  the cadence is `new` until two likely days are recorded, then the configured `recurrent`; the quant
  line states no frequency while rarity is pinned.
- `application-example.yml` cadence comment: `mist` is used only once two likely days are recorded.
- One `changelog.d/` entry (Fixed).

## Verify

Local gate with the integration exclusion; exit code, not grep. No migration, no UI build needed
(frontend untouched). Seen in a browser: not possible here (sign-in); the row's text is pinned by test.

## Dust and inversions: the same fault, and the rule that replaced "not measured" (owner, 2026-10-22)

The quant line's `frequencyPhrase(rarityBits, ...)` printed the config guess whenever rarity was the
fallback, contradicting the observed history beside it. A first cut said "no frequency measured yet";
the owner corrected that: dust and inversions have been tracked for months (dust was frequent in
summer, rarer now) and the trailing window IS a measurement of that. Final rule, all three history rows
(dust, inversion, mist):

1. The frequency is the OBSERVED rate over the trailing window, on the rateLabel: with at least 2
   occurrences, append ` · lately about <describeGap(windowDays / count)>` ("2 plumes in the last 60
   days · lately about one a month"); with 0 or 1, no phrase.
2. The quant line states no frequency: the threshold text alone ("counts as a plume above a haze
   reading of 0.50 (early threshold)"). Coastal tides are untouched (ephemeris).
3. `describeGap` buckets: <=1.5 daily; <=2.5 every other day; <=4.5 twice a week; <=9 one a week;
   <=20 one a fortnight; <=45 one a month; else one every N days.
4. Unchanged: rarity bits, scores, badges, mist "new" cadence below two likely days, dust "recurrent",
   inversion "persistent".

## "since <date>" becomes "in the last N days"

The first occurrence's date is often just the trailing window's own start, so "since 23 Aug" overstated
recency. Every history rateLabel now reads `... in the last <windowDays> days` (configured window, never
a literal): dust `2 plumes in the last 60 days` and, measured, `5 plumes in the last 60 days · about
every N days`; inversion `18 strong mornings in the last 60 days`; mist `2 likely days in the last 60
days`. The frontend prints `rateLabel` verbatim and nothing in `frontend/src` parses "since".
