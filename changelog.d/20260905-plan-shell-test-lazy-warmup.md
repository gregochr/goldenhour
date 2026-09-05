### Fixed — the Plan shell's test files no longer time out under a loaded machine

The first test in each file that renders `WindowFirstShell` was silently paying a per-**file** cost
inside a per-**test** budget: the shell puts its matrix, its window popup, its search panel and its
location sheet behind `React.lazy`, and whichever test ran first found the module registry cold.
Measured under a 20-process CPU load, in `WindowFirstShellSheet.test.jsx`, that test's first
`findByTestId('wf-heat-strip')` took 1051ms and its first `findByTestId('window-sheet')` 797ms,
where every later call in the same file took 3.5ms and 50ms. Nothing about the first test is
different — it just gets there first.

That had been met once already: `setup.js` raised Testing Library's `asyncUtilTimeout` to 4000ms so
a cold boundary had room. But Vitest's per-test budget stayed at 5000ms, and a test crossing two of
these boundaries in sequence cannot fit two 4000ms waits into 5000ms — so it died before either
`findBy*` could reach its own ceiling or say which wait was stuck, as a bare `Test timed out in
5000ms` pointing at the `it(` line. Running the full suite three times concurrently under a
16-process CPU load reproduced it in 3 of 3 runs, on the first test of `WindowFirstShellSheet`,
`WindowFirstShellDoor1` and `locationSheetShell`, while the same files passed alone in six seconds.

The fix is `testTimeout: 20000`, which is what makes the existing 4000ms ceiling reachable at all.
The frontend CI job gains a `timeout-minutes` alongside it, because a 4× per-test ceiling with no
job ceiling left the cost of a genuine hang capped only by GitHub's six-hour default.

⚠️ Two things are worth knowing, because both were built or believed and then measured away. A
per-file `beforeAll` warm-up that loaded those four chunks — the obvious structural fix, and the one
this change was originally built around — **works and is not needed**: with it neutralised and the
new ceiling in place the same reproduction is green 3 of 3, worst first test 5744ms against 5065ms
with it — a 12% tail reduction on a budget with 2.7× headroom at the worst first test seen
anywhere (7412ms). It was deleted rather than shipped, and the standards doc records why, so it
is not rebuilt. And raising the ceiling is not free: a tight
per-test budget is this suite's only performance-regression detector, and this very defect was
discoverable *because* the budget was tight enough to fail on it.

No test changed what it asserts. One measurement is worth keeping: the drill-down helper's
un-awaited `fireEvent.click`, which looks like the obvious culprit and was the first hypothesis, is
correct — `WindowSpotSheet` is a static import, so it lands in the same commit as the click, present
synchronously in 30 of 30 invocations idle and under load. The helper now says so, since it reads
like an omission and has been refiled as one.

One flake that reproduction surfaced is NOT fixed here and is unrelated:
`src/test/shared/Modal.test.jsx`'s "does NOT yank focus back from wherever the reader has since
moved to" failed once in ten runs of that file alone under the same load, and in 4 of 24 concurrent
suite runs — a rate those samples pin down no further. It races `useDialogFocus`'s animation frame
rather than a module boundary, and the shape is inverted (it passes only while the frame has yet to
fire), so it may be a question about whether uncovering a stacked dialog should take focus at all
rather than a test fix. It is now recorded at the test itself, which is where the next person to hit
it will be looking.
