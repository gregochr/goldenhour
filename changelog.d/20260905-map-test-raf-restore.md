### Tests — three map test files now restore the rAF they replace

`MapCallout`, `MapLabels` and `PinsLayer` install a manual frame queue over
`global.requestAnimationFrame` in `beforeEach` and never put the real one back;
`MapHeatLayer.test.jsx` already saved and restored. All four now agree.

Stated honestly: **this fixes nothing today.** `isolate: true` gives every test file its own
process, so the replacement cannot reach another file, and `beforeEach` reinstalls the queue for
every test within the file. It is symmetry, so that a reader comparing the four finds them agreeing
rather than wondering which is right.

A second charge from the same review — that `WindowFirstShellLocationSheetHandoff.test.jsx`'s bare
trailing `raf.mockRestore()` leaks a synchronous-rAF mock into later tests when an assertion throws
first — was **refuted and no change made**: that file carries a top-level
`afterEach(() => vi.restoreAllMocks())`, so the spy cannot survive the already-failed test. The
`try/finally` in `WindowFirstShell.test.jsx` is belt-and-braces over the identical net, not the thing
that makes it safe.
