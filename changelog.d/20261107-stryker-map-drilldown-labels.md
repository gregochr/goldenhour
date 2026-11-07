### Tests — kill Stryker survivors in mapDrilldown, mapLabels, mapCallout, mapLanding, mapTopicDays and mapScopedTime

Batch 9 of the frontend mutation clean-up. Six new behaviour suites, `mapDrilldownEdges.test.js`, `mapLabelsEdges.test.js`, `mapCalloutEdges.test.js`, `mapLandingEdges.test.js`, `mapTopicDaysEdges.test.js` and `mapScopedTimeEdges.test.js`, pin the drilldown's malformed-input and partial-picks rows, the shaped note's article handling, the region panel's day word, tide state and drive-less rows, the hottest-region mean and tie rules, the ring-label frame margins, the chip ordering tie-breaks and the selected-pick and stood-place budgets, the callout band's bar-size and card-span tests and the anchor's exact-fit and flip boundaries, the landing header's missing-row fallbacks, the topic-day dots' targets and sunset wording, and the scoped time's same-array answers and equal-instant rule. Mutation results (detected over detected plus survived plus uncovered):

- `mapDrilldown.js`: 86.6% (214/247) → 95.1% (235/247)
- `mapLabels.js`: 89.5% (263/294) → 99.0% (291/294)
- `mapCallout.js`: 89.9% (124/138) → 99.3% (137/138)
- `mapLanding.js`: 87.8% (72/82) → 96.3% (79/82)
- `mapTopicDays.js`: 88.5% (108/122) → 97.5% (119/122)
- `mapScopedTime.js`: 86.7% (72/83) → 94.0% (78/83)

No source changed. Every remaining mutant is equivalent: `[]` fallbacks whose string stand-in has no fields, `typeof` and `!= null` guards implied by `Number.isFinite` or by a later lookup guard, skips whose only effect is an entry in a map that is only read for in-scope names, `formatDriveDuration(null)` already answering null, and a comparison made unobservable by a loop bound or stable sort.
