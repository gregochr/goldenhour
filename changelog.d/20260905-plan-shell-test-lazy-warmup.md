### Fixed — the Plan shell's test files no longer time out under a loaded machine

The first test in each of the thirteen files that render `WindowFirstShell` was silently paying a
per-**file** cost inside a per-**test** budget: the shell puts its matrix, its window popup, its
search panel and its location sheet behind `React.lazy`, and whichever test ran first found the
module registry cold. Measured under a 20-process CPU load, in `WindowFirstShellSheet.test.jsx`,
that test's first `findByTestId('wf-heat-strip')` took 1051ms and its first
`findByTestId('window-sheet')` 797ms, where every later call in the same file took 3.5ms and 50ms.
Nothing about the first test is different — it just gets there first.

That had been met once already: `setup.js` raised Testing Library's `asyncUtilTimeout` to 4000ms so
a cold boundary had room. But 4000ms was 80% of Vitest's then-5000ms per-test budget, and these
tests cross two boundaries in sequence, so the test died before either `findBy*` could reach its own
ceiling or say which wait was stuck — it failed as a bare `Test timed out in 5000ms` pointing at the
`it(` line. Running the full suite three times concurrently under a 16-process CPU load reproduced
it in 3 of 3 runs, on the first test of `WindowFirstShellSheet`, `WindowFirstShellDoor1` and
`locationSheetShell`, while the same files passed alone in six seconds.

Two changes, and neither is enough on its own. `src/test/warmPlanChunks.js` loads those chunks in a
`beforeAll`, where the cost is per-file and the budget is its own — the earlier note reasoned there
was "nothing to gate on", but a test can simply load the module itself. That cures the popup's wait
outright (797ms → 2.4ms) and halves the first test (2598ms → 1386ms). It does not cure the strip's,
which fell only to 722ms, and the residue is what `testTimeout: 20000` covers: post-fix under the
same load, `locationSheetShell`'s first test still measured 4903 / 5065 / 4939ms — one of those
already over the old budget. `hookTimeout` moves with it, because a blown test budget fails one test
where a blown hook budget fails the whole file, and the load-sensitive work now lives in a hook. The
frontend CI job gains a `timeout-minutes`, since a 4× per-test ceiling with no job ceiling left the
cost of a genuine hang capped only by GitHub's six-hour default.

⚠️ Two honest limits on all of that, both found by an adversarial review of the first cut. The
residue is **not** `React.lazy`'s payload resolution, as that cut claimed: both surfaces sit behind
`lazy()` and one of them warmed to 2.4ms, so a mechanism worth 2.4ms at one boundary cannot be worth
722ms at another. It is the strip's own first render — the heat-field asset load, decode and
Mercator fit — which no module warm-up reaches. And raising `testTimeout` costs more than "a hang
takes 20s to report": a tight budget is this suite's only performance tripwire, and this very
defect was discoverable *because* the budget was tight enough to fail on it.

No test changed what it asserts. Three claims were measured rather than assumed. The drill-down
helper's un-awaited click, which looks like the obvious culprit, is correct — `WindowSpotSheet` is a
static import, so it lands in the same commit as the click. Making the shell's `lazy()` boundaries
static as a probe left all 305 shell tests passing, so no assertion on an element that *should* have
rendered was resting on an unarrived chunk — though that probe says nothing about the separate class
of absence assertions guarding a shell gate, where the element never mounts either way. And the
membership of the thirteen was wrong at both ends in the first cut: `AppOpenMapTabFromPlan` mocks
the shell wholesale and crosses no boundary at all, while `App.test.jsx` mounts the real shell
through `App.jsx` and was missed — its first test runs 2580–2777ms under load against a ~700ms
median. `warmPlanChunks.test.js` now pins the warmed list to the shell's own `lazy()` calls, because
a renamed module fails loudly but a fifth boundary would not.

One flake that reproduction surfaced is NOT fixed here and is unrelated:
`src/test/shared/Modal.test.jsx`'s "does NOT yank focus back from wherever the reader has since
moved to" failed once in ten runs of that file alone under the same load with none of this loaded,
and in 2 of 6 concurrent suite runs — a rate those samples pin down no further. It races
`useDialogFocus`'s animation frame rather than a module boundary, and the shape is inverted (it
passes only while the frame has yet to fire), so it may be a question about whether uncovering a
stacked dialog should take focus at all rather than a test fix. It is now recorded at the test
itself, which is where the next person to hit it will be looking.
