### Tests — kill Stryker survivors in schedulerDay, schedulerDayOverlays and schedulerView

Three Scheduler view-model utils had surviving mutants in Stryker shard 9. New edge-case tests
(`schedulerDayEdges`, `schedulerDayOverlaysEdges`, `schedulerViewEdges`) pin the boundaries, tie-breaks,
fallbacks and degenerate inputs those mutants relied on. No source changed.

| File | Survived + no coverage before | After | Score before | Score after |
|---|---|---|---|---|
| `schedulerDay.js` | 46 + 5 | 11 | 414/465 (89.0%) | 454/465 (97.6%) |
| `schedulerDayOverlays.js` | 43 | 9 | 362/405 (89.4%) | 396/405 (97.8%) |
| `schedulerView.js` | 34 + 1 | 6 | 350/385 (90.9%) | 379/385 (98.4%) |

Scores count killed plus timed-out mutants over all non-ignored mutants. Every survivor left is an equivalent
mutant: `?? []` fallbacks that are filtered or iterated away before anything returns them, `Array.find` on a
type that always exists, and comparison or arithmetic changes that cannot alter a result.
