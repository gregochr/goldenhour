### Changed — Coming up drops the last "bits" leaks for plain-language copy

Phase L6 of `docs/engineering/lunar-eclipse-plan.md` (independent of the lunar eclipse work
itself): every user-facing surprisal-score string on the Coming up tab is now plain language,
backend and frontend.

`ComingUpAssembler.markScoreNotes`'s server-authored sentence now reads back the rarity component
as a calendar phrase (`SurpriseScore.gapWord`, e.g. "a fortnight", "a year", "every two to three
years") — `"It comes round about once a year, which is rare enough to flag on its own."` — or, when
magnitude carried the score, names the entry's own figure against the usual one from its history
(`"An unusually big one — 6.0 m against the usual 2.5 m."`, falling back to a figure-free sentence
under cold start). `mergeEntries`'s coincidence `joinNote` reads `"One perigee causes both. Counted
as one event, not two — the <title> carries it."` `ComingUpConditionsBuilder`'s standing-conditions
quant line leads with an idiomatic frequency phrase (`frequencyPhrase`, "about one a week", "most
mornings" for a genuinely near-daily gap) rather than an adjective bucketed straight from bits, and
a promoted tide-run occurrence's `reason` tag drops its "max w/ " prefix.

On the frontend, `WindowComingUpSinceLine`'s two banner shapes read `"◆ Rare — the <title> entered
the window, <date>. <scoreNote>"` and `"<N> announced — the <title> entered the window, <date>.
<scoreNote>"` — the interrupt headline is now the fixed word "Rare" rather than a figure bucketed
from `entry.bits`, since that band is binary (README §6: "above 9.5 bits there is only ever one
thing in play"). `bitsWord` (peak/occurrence rows) moves to the design's three-word scale —
`exceptional` (≥7) / `above usual` (≥4.5) / `typical` — and "scores are provisional" becomes
"figures are provisional" throughout.

Scoring logic is unchanged: every `bits` **value** assertion in `ComingUpAssemblerTest` and
`ComingUpAnnualBadgeCensusTest` stays green untouched (the census re-run confirms 11 badge
arrivals/year, 1 interrupt, unmoved) — only strings and their tests moved. `grep -rn "bits"` over
`frontend/src/components` and the two backend classes now finds only field names, PropTypes and
engineering comments, never a rendered sentence. The design's dust-row "heaviest of N since
&lt;month&gt;" fact is deliberately not built — it is a new comparison, not a copy change, and
dust has no chronology-entry shape to attach it to (plan §4 #16).
