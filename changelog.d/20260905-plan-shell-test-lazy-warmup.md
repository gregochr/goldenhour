### Fixed — the Plan shell's test files no longer time out under a loaded machine

The first test in each of the thirteen files that mount `WindowFirstShell` was silently paying a
per-**file** cost inside a per-**test** budget: the shell puts its matrix, its window popup, its
search panel and its location sheet behind `React.lazy`, and whichever test ran first found the
module registry cold. Measured under a 20-process CPU load, in `WindowFirstShellSheet.test.jsx`,
that test spent 1051ms waiting on the matrix and a further 797ms on the popup, where every later
test in the same file spent 3.5ms and 50ms. Nothing about the first test is different — it just
gets there first.

That had been met once already: `setup.js` raised Testing Library's `asyncUtilTimeout` to 4000ms so
a cold boundary had room. But 4000ms is 80% of Vitest's 5000ms per-test budget, and these tests
cross two boundaries in sequence, so the test died before either `findBy*` could reach its own
ceiling or say which wait was stuck — it failed as a bare `Test timed out in 5000ms` pointing at the
`it(` line. Running the full suite three times concurrently under a 16-process CPU load reproduced
it in 3 of 3 runs, on the first test of `WindowFirstShellSheet`, `WindowFirstShellDoor1` and
`locationSheetShell`, while the same files passed alone in six seconds.

Two changes, and neither is enough on its own. `src/test/warmPlanChunks.js` loads those four chunks
in a `beforeAll`, where the cost is per-file and the budget is its own — the earlier note reasoned
there was "nothing to gate on", but a test can simply load the module itself. That cures the popup's
boundary outright (797ms → 2.4ms) and halves the first test (2598ms → 1386ms). What it cannot reach
is `React.lazy`'s own payload, which is cached on the lazy object rather than in the module registry
and resolves on first *render*; so `testTimeout` moves to 20000ms, which is what makes the existing
4000ms async ceiling reachable at all. The warm-up alone was measured and was not enough — it took
the reproduction from 9 failures to 4. With both, nine further concurrent full-suite runs under the
same load produced none.

One flake that reproduction surfaced is NOT fixed here and is unrelated: `Modal.test.jsx`'s "does
NOT yank focus back from wherever the reader has since moved to" fails about 1 run in 10 under the
same load, in that file alone, with none of the above loaded. It races `useDialogFocus`'s
animation frame rather than a module boundary, and the shape is inverted — the test passes only
while the frame has yet to fire — so it may be a question about whether uncovering a stacked dialog
should take focus at all, rather than a test fix. It is recorded rather than folded in here.

No test changed what it asserts. The suite's timing was audited rather than assumed while fixing
this: the drill-down helper's un-awaited click, which looks like the obvious culprit, was measured
over 30 invocations idle and under load and is correct — `WindowSpotSheet` is a static import, so it
lands in the same commit as the click, and the helper now says so. Making every one of the shell's
lazy boundaries static, as a probe, left all 305 shell tests passing, so none of them was quietly
resting on a chunk that had yet to arrive.
