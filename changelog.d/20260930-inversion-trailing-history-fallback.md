### Fixed — the inversion condition's trailing history fell silent for every date before V158

A Codex review of PR #948 (against commit `3e688862`, the first V158 cut) found a P1: after V158
added `survivor_atmosphere.inversion_score`, every row written before that migration carries a null
value there forever, because a past date is never re-evaluated by a later pipeline cycle — there is
no later look that could ever fill it in. `ComingUpConditionsBuilder.buildInversion`'s trailing-
window occurrence list had moved to reading that column alone, so it reported "none in the last 60
days" even on dates where a strong `forecast_score` INVERSION row (Claude's echo of the identical
0–10 scale) genuinely existed. That false history would have stood until the entire 60-day window
aged past every pre-migration date — weeks of a silently wrong "Valley inversions" card.

**The fix splits the condition's two reads by what can still change.** The FORWARD peak cell stays
calculator-only: a forward slot is upserted every pipeline cycle, so `survivor_atmosphere
.inversion_score` is correct there from the very first cycle after deploy, and there is nothing to
fall back to. The TRAILING HISTORY occurrence list cannot make that assumption, because it can never
acquire a reading for a date already in the past — so `ComingUpConditionsBuilder
.trailingInversionScore` now falls back to `SurvivorSignals.Scores.inversion()` (Claude's
`forecast_score` echo of the same 0–10 scale and the same STRONG threshold) whenever a date's
calculator reading is null. When both are present, the reading wins — it remains the intended
primary source, not merely an equally-valid alternative.

**This fallback is permanent, not a transition hack that a later cleanup should remove.** Two real,
ongoing populations have only the echo, forever: every `survivor_atmosphere` row written before
V158 (the column did not exist yet, and nothing re-evaluates a past date), and a `forecast_score`
INVERSION row with no matching `survivor_atmosphere` row at all — a key whose weather was fetched
only through one of the pre-#947 code paths that predate "record conditions for every place"'s
writer being called at all. Neither population will ever be filled in by a later cycle, so the
fallback is the permanent shape of this condition's trailing read, not scaffolding for a rollout
window.

**No backfill migration, deliberately.** A backfill could write today's calculator score into old
`survivor_atmosphere` rows, but it cannot reach the second population above (a row that was never
written at all), so a read-time rule is needed regardless of whether a backfill also runs — and once
it exists, it already covers everything a backfill could have fixed. A backfill would also be
data-moving SQL that only CI can prove ever ran correctly, for no remaining benefit. The read-time
fallback is the whole fix.

**A consequence worth naming precisely**: `SurvivorSignals.Scores.inversion()`/`inversionBand()`
were described in the same-day Phase 2 changelog entry (`changelog.d
/20260930-inversion-score-every-place.md`) as unread by any production code and a candidate for
future removal. That was accurate for the commit it described, and is no longer accurate: this fix
gives `Scores.inversion()` a genuine, permanent production reader again (the trailing-history
fallback above), so it should not be removed as dead code. `SurvivorSignals.java`,
`SurvivorSignalReader.java` and `CLAUDE.md` are corrected to say so.

**In the hours immediately after this deploys**, until the next scheduled cycle upserts the forward
window, the inversion hot topic and the Coming up condition's forward-peak cell are silent for
inversion — this is unchanged from before V158, and is not a regression this fix introduces. The two
scheduled cycles that would end that silence are the nightly run (~01:00 UTC) and the intraday
refresh (~14:00 UTC); an operator watching for the feature to "come alive" should expect to wait for
whichever of those runs next, not assume something is broken before then. The trailing history's own
fallback has no such wait — it is already reading `forecast_score` rows that exist today.

Tests: `ComingUpConditionsBuilderTest` gains four cases pinning the split explicitly — a null
reading with a strong echo is listed (using the echo's score); a date with both present, disagreeing,
shows the reading's score; a null reading with an echo below the STRONG threshold is not listed; and
the forward-peak cell with a null reading and a strong echo shows no peak at all. The first of these
fails against commit `3e688862` (before this fix) with an empty occurrence list where one entry was
expected — the exact regression this fix closes.
