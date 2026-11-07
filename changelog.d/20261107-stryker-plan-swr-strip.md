### Tests — kill Stryker survivors in the plan utils, swrCache, the window-first strip and topics, and four more

Ten new `*Edges.test.js` files in `frontend/src/test/` pin the behaviour behind the baseline mutation survivors in `planSearch`, `planOrigin`, `planningArea`, `subRegionCopy`, `swrCache`, `windowFirstTopics`, `windowFirstStrip`, `windowSpotBrowse`, `confidenceUtils` and `briefingDisplay`. No source changed: tests only.

Survivors plus no-coverage mutants, before to after (the remainder are equivalent mutants):

| File | Before | After |
|---|---|---|
| planSearch | 19 | 11 |
| planOrigin | 14 | 7 |
| planningArea | 10 | 4 |
| subRegionCopy | 13 | 1 |
| swrCache | 18 | 3 |
| windowFirstTopics | 12 | 6 |
| windowFirstStrip | 10 | 3 |
| windowSpotBrowse | 10 | 2 |
| confidenceUtils | 10 | 1 |
| briefingDisplay | 10 | 6 |
