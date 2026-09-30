### Fixed — the inversion signal needed one shared fallback rule, not a forward/trailing split, and then a way to tell an authoritative null from an absent one

**Round 2** — A Codex review of PR #948 (against commit `3e688862`, the first V158 cut) found a P1:
after V158 added `survivor_atmosphere.inversion_score`, every row written before that migration
carries a null value there forever, because a past date is never re-evaluated by a later pipeline
cycle — there is no later look that could ever fill it in. `ComingUpConditionsBuilder.buildInversion`'s
trailing-window occurrence list had moved to reading that column alone, so it reported "none in the
last 60 days" even on dates where a strong `forecast_score` INVERSION row (Claude's echo of the
identical 0–10 scale) genuinely existed. That false history would have stood until the entire 60-day
window aged past every pre-migration date — weeks of a silently wrong "Valley inversions" card. The
round-2 fix split the condition's two reads by window direction: the forward peak stayed
calculator-only (reasoning that a forward slot is upserted every cycle, so it is always current),
while the trailing history alone fell back to Claude's echo via a new
`ComingUpConditionsBuilder.trailingInversionScore` helper.

**Round 3 (this fix) found the round-2 split itself wrong, not just incomplete.** A second Codex
review of PR #948 established that a forward slot is NOT guaranteed to carry a fresh calculator
reading every cycle: `BriefingCandidateCollector` skips a region with a fresh `cached_evaluation`
entry — `SKIPPED_CACHED`, around lines 204–227 — *before* `fetchWeatherAndTriage` (and therefore the
calculator) ever runs for it, and `FreshnessProperties.settledHours` defaults to 36 with no horizon
cap at T+2 and beyond (`FreshnessResolver.horizonCap` returns null there), so a SETTLED forward slot
can go up to 36 hours with a null `survivor_atmosphere.inversion_score` while Claude's own
`forecast_score` echo for that exact slot already exists. Under the round-2 design, both the
inversion hot topic and the Coming up condition's forward-peak cell would wrongly suppress an
imminent, already-known inversion for up to a day and a half.

**The fix drops the forward/trailing distinction entirely.** There is now ONE rule, used everywhere
the inversion signal is read: `SurvivorSignals.effectiveInversionScore()` — the calculator's
`Readings.inversionScore()` when present, else Claude's `Scores.inversion()` echo (the identical
0–10 scale, the identical STRONG cut of 9). `InversionHotTopicStrategy.detect`/`attachFacts` and
BOTH of `ComingUpConditionsBuilder.buildInversion`'s reads (trailing history and forward peak alike)
call this one shared helper; `ComingUpConditionsBuilder.trailingInversionScore` (round 2's
now-superseded helper) is deleted.

**This is not a retreat from the owner's "the calculator decides" rule — it is the same rule, made
consistent.** Where the calculator HAS scored a slot, the calculator decides, full stop: the reading
always wins when both are present, even when it disagrees with (including when it is LOWER than) the
echo. The echo is never anything more than a stand-in for a slot the calculator has not reached yet,
on the identical scale and threshold, so this can never make the topic fire on a score the calculator
itself would have refused — it only ever fills a gap the calculator has not had the chance to fill.

**The fallback remains permanent, not a transition hack**, and now covers a third population beyond
the two round 2 named: every pre-V158 `survivor_atmosphere` row (a past date's null reading is never
retroactively filled in), any `forecast_score` INVERSION row with no matching `survivor_atmosphere`
row at all (a key fetched only by a pre-#947 code path that predates the writer being called), and —
newly — any forward slot a `SKIPPED_CACHED` gate has not yet let the calculator re-score this cycle.
None of the three will ever be filled in by waiting; a read-time rule is the only design that covers
all three, since the third population proves even "wait for the next cycle" is not a reliable fix for
a forward slot. **Still no backfill migration** — a backfill could write today's calculator score
into old rows, but it cannot reach the second or third population above (no row was ever written, or
the write simply has not happened yet this cycle), so the read-time rule is needed regardless, and
once it exists it already covers everything a backfill could have fixed.

**A consequence worth naming precisely, corrected from round 2's own claim**: `SurvivorSignals
.Scores.inversion()`/`inversionBand()` were described in the same-day Phase 2 changelog entry as
unread by any production code and a candidate for future removal. That was already wrong by the end
of round 2 (which gave the trailing history a genuine reader) and remains wrong now: `effectiveInversionScore()`
is a permanent, live production reader of both fields, for both windows. `SurvivorSignals.java`,
`SurvivorSignalReader.java`, `InversionHotTopicStrategy.java`, `ComingUpConditionsBuilder.java` and
`CLAUDE.md` all name the single shared helper now.

**Round 2's "silent until the next cycle" claim is also corrected — it is no longer true.** Because
the echo now stands in for BOTH windows, a slot with a `forecast_score` echo and no calculator
reading fires from the very first request after this deploys, not only after the next scheduled
cycle. Only a slot with genuinely no evidence on EITHER surface — no calculator reading and no
Claude echo — stays silent, and that silence ends whenever either surface first gets a value, not on
a fixed clock. The two scheduled cycles that write fresh calculator readings remain the nightly run
(~01:00 UTC) and the intraday refresh (~14:00 UTC), for reference, but they are no longer the only
thing that can end the silence for a slot Claude has already evaluated.

Tests: `SurvivorSignalsTest` (new) pins `effectiveInversionScore()` directly at the helper level —
reading-only, echo-only, both-null, agreeing, and both present with the echo higher OR lower than
the reading (the reading always wins either way) — the one place this decision is made, so the
strategy and the builder can never disagree about it. `InversionHotTopicStrategyTest` REVERSES its
own round-2 test (`detect_silentWhenReadingNull_evenIfScoresInversionIsTen` renamed and flipped to
`detect_nullReadingWithStrongEcho_fires`) and adds: a lower reading beating a higher echo stays
silent; a reading with no echo still fires (named explicitly); both null stays silent.
`ComingUpConditionsBuilderTest` REVERSES its own round-2 forward-peak test
(`buildInversion_forwardPeak_nullReadingWithStrongEcho_notShown` renamed and flipped to
`buildInversion_forwardPeak_nullReadingWithStrongEcho_isShown`) and adds a forward-peak
reading-wins-over-disagreeing-echo case, mirroring the trailing-history one already there;
`inversionRarityNeverUpgrades` and the round-2 trailing-history tests are unaffected and still pass.

**Round 4 — a further Codex P1 found `effectiveInversionScore()` itself still wrong: it treated
EVERY null `Readings.inversionScore()` as "the calculator has not reached this slot yet", but a
null reading is also exactly what a FRESH write produces.** Two causes, both legitimate: (1)
`InversionScoreCalculator.calculate` returns null for an otherwise-ELIGIBLE location when required
weather inputs (a null dew point or surface temperature) are missing; (2) `ForecastDataAugmentor
.augmentWithInversionScore` returns the base `AtmosphericData` unchanged — the score staying null —
for an INELIGIBLE location. Both leave `survivor_atmosphere.inversion_score` null on a row written
THIS cycle, indistinguishable from a row the calculator simply has not reached at all. Since
`ForecastScoreWriter.write` only upserts the `forecast_score` INVERSION component when
`eval.inversionScore() != null`, any earlier component for that slot is left in place indefinitely
whenever a later evaluation scores nothing — so round 3's blanket "null reading → fall back to the
echo" rule could revive a STRONG rating from a stale evaluation days after the calculator itself
had legitimately found nothing to report, on the exact slot the deterministic scoring was meant to
correct.

**The fix distinguishes an authoritative null from an absent one with data, not with timestamps.**
`V158__add_survivor_inversion_score.sql` (amended in place — this PR was never merged, confirmed by
an empty `git log origin/main -- backend/src/main/resources/db/migration/V158*`) gains a second
column, `inversion_scored BOOLEAN NOT NULL DEFAULT FALSE`. `SurvivorAtmosphereWriter.write` now sets
`inversionScored = true` on every write it makes — with a real score or with a null one alike —
because a fresh write always ran the calculator's own eligibility check this cycle, whatever it
found. `SurvivorSignals.Readings` gains the thirteenth component `inversionScored` (`false` in
`EMPTY`), mapped by `SurvivorSignalReader` straight off the entity's own flag.
`effectiveInversionScore()` now checks `readings.inversionScored()` FIRST: when `true`, it returns
`readings.inversionScore()` exactly as stored, null included, and never consults the echo at all;
only when `false` — a row written before this column existed — does it fall back to Claude's echo,
or null if neither surface has anything to say. The calculator still decides whenever it has
scored a slot, full stop; this round narrows *what counts as* the calculator having scored a slot,
it does not touch the precedence rule itself.

**The permanent fallback population shrinks by exactly the case this round fixes.** Round 3 named
three populations that only ever have the echo; the "any forward slot a `SKIPPED_CACHED` gate has
not yet let the calculator re-score this cycle" population is now split in two by this round: a
slot with NO `survivor_atmosphere` row at all for that key still falls back (nothing to mark
scored), but a slot the calculator DID score this cycle — even to a null result — no longer does.
The two populations that remain permanent are unchanged in kind: every pre-round-4
`survivor_atmosphere` row (`inversion_scored = false` by the migration's default, and a past date is
never re-evaluated so the flag is never retroactively set) and any `forecast_score` INVERSION row
with no matching `survivor_atmosphere` row at all. **Still no backfill migration** — the read-time
flag already tells the two cases apart; a backfill could not populate `inversion_scored` for a
historical row with any confidence it reflects that row's own cycle.

Tests: `SurvivorSignalsTest.effectiveInversionScore` gains the scored/unscored cross product —
scored=true with a null reading and a strong echo (10) now returns null (silent), reversing round
3's own `detect_nullReadingWithStrongEcho_fires`-shaped expectation at the helper level; scored=true
with a real reading (9) returns 9 regardless of the echo; scored=false with an echo (10) returns 10
(the pre-column shape, unchanged); scored=false with no echo returns null.
`SurvivorAtmosphereWriterTest` gains two cases: a write with a calculator score sets
`inversionScored = true`, and a write with a null score sets it `true` too.
`SurvivorSignalReaderTest` gains a mapping test (scored=true, with and without a reading survives
the join) and confirms `Readings.EMPTY.inversionScored()` is `false`. `InversionHotTopicStrategyTest`
renames `detect_nullReadingWithStrongEcho_fires` to `detect_preColumnRowWithStrongEcho_fires`
(unchanged assertion — a pre-column row still fires) and adds
`detect_freshNullWithStrongEcho_silent`, its direct reversal for a scored row. Every remaining
signal-carrying fixture across `DustFactsBuilderTest`, `DustHotTopicStrategyTest`,
`SnowFreshHotTopicStrategyTest`, `StormSurgeFactsBuilderTest`, `StormSurgeHotTopicStrategyTest`,
`SnowTopsHotTopicStrategyTest`, `HotTopicAggregatorTest` and
`RecordConditionsForEveryPlaceIntegrationTest` was audited and given an explicit `scored` value
(`false` where the fixture is unrelated to inversion; `true` where an existing fixture asserts on a
real inversion reading, since leaving it at the record's default `false` would have silently
switched that fixture onto the echo path instead of the reading it was written to test).
`ComingUpConditionsBuilderTest` renames its own `buildInversion_nullReadingWithStrongEcho
_isListedWithEchoScore`/`buildInversion_forwardPeak_nullReadingWithStrongEcho_isShown` to
`_preColumnRowWithStrongEcho_*` (unchanged assertions) and adds their direct reversals,
`buildInversion_freshNullReadingWithStrongEcho_notListed` and
`buildInversion_forwardPeak_freshNullReadingWithStrongEcho_noPeak`, using two new helpers
(`scoredNullReading`, `preColumnReading`) that make each fixture's intent explicit rather than
relying on a bare `new SurvivorAtmosphereEntity()`'s default field values.
