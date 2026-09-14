### Docs — the frontend test standards say how to pin a late response

`docs/engineering/frontend-test-standards.md` gains a section, "A late response is only 'dropped'
once it has landed", recording five rules that four fetch-race test files on the map each learned
from a test that passed, or would have passed, with the behaviour it names broken: settle hand-held
requests inside an **awaited** `act`; route a positive control through the same settle helper; give a
`.catch` its own late-failure test; count markers rather than `markerLabelAndColour` calls; and, where
the claim is "never offered", log every render rather than reading only `result.current`.

The first had lived only in test-file comments and changelog entries — nowhere a new test author is
sent. ⚠️ **It was also stated wrongly.** The astro fix's own entry (#822) and its test's comment
blamed a "synchronous `act`", and so did this branch's first drafts; the variable that matters is
the `await`. Measured with each file's guard deleted: an un-awaited `act(() => …)` let the late test
pass in all four files, and an un-awaited async callback did too where that was tried, while
`await act(() => …)` — a plain callback, awaited — failed it in each of the three files where it was
tried. The astro test's comment is corrected here; #822's own entry file is another change's, and
`changelog.d/README.md` has those left as written.
