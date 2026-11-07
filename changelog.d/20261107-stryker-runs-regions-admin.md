### Tests — kill Stryker survivors in the runs utils, regionsJump, jobRunPanel and locationAdmin

Seven new `*Edges.test.js` files pin the boundaries Stryker found unpinned (survivors plus no-coverage mutants,
before to after): `runsCost.js` 20 to 1, `runsView.js` 17 to 2, `runsTiming.js` 15 to 1, `runsQuery.js` 15 to 4,
`regionsJump.js` 22 to 9, `jobRunPanel.js` 16 to 0, `locationAdmin.js` 22 to 5 (plus one mutant that crashes the
runner, which counts as detected). Every remaining survivor is an equivalent mutant (empty-array fallbacks,
redundant guards). No source file changed.
