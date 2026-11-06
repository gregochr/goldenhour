### Tests — Stryker survivors killed in locationSheet, travelDays and mapVerdict

Three new edge suites (`locationSheetEdges.test.js`, `travelDaysEdges.test.js`,
`mapVerdictEdges.test.js`) pin answers the happy-path tests never asked for: malformed members of
the served trees, first-wins and sort tie-breaks, the location sheet's handoff and lead-line rules,
the Travel days cell class cascade and server-error placement, and the Map verdict's shaped and
served-order picks. Tests only; nothing under `src/utils` changed.

Mutation score (killed ÷ killed + survived + no coverage), baseline from the first full weekly run:

- `locationSheet.js`: 89.5% (418/467) → 97.2% (454/467)
- `travelDays.js`: 85.9% (262/305) → 99.0% (302/305)
- `mapVerdict.js`: 82.0% (191/233) → 94.4% (220/233)

Every mutant left is equivalent. Most are `[]` fallbacks whose replacement element is skipped by
the loop's first guard and never returned, or optional chains on a value an earlier check has
already proven non-null. mapVerdict stays just under 95% because its 13 remaining mutants are all
of that kind.
