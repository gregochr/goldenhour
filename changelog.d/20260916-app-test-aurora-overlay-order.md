### Fixed — the App tests that press the aurora banner no longer depend on the tests before them

`App.test.jsx`'s "honours the NIGHT in progress when the aurora banner asks for it" failed under
`--sequence.shuffle.tests --sequence.seed=424242` at 48e8aa2a — `No "getAuroraLocations" export is
defined on the "../api/auroraApi.js" mock`, thrown from `MapView`'s aurora-scores effect — and
passed in the default order. Test-only: no product code changes.

**What an earlier test left behind was a loaded chunk, not a mock.** The banner opens the Plan-tab
map overlay — `MapOverlay` framing a real `MapView`, both behind `React.lazy` in `App.jsx` — and a
lazy chunk stays loaded for the rest of the file once any test has loaded it, which no `beforeEach`,
`mockReset` or `vi.restoreAllMocks` can undo. Neither banner test waited for the overlay it opened,
and `MapView`'s chunk is requested only when `MapOverlay` renders, so the real map could mount
inside a press only if an earlier banner test had still been mounted when `MapOverlay` loaded, and
`MapView` had loaded since. Instrumented under that seed, that is what happened: "clears the night
licence" ran first, and `MapOverlay` loaded 5 ms before it ended and asked for `MapView` 3 ms
before; `MapView` finished loading during the next test, the one that waits out a 1.5 s night end;
and "honours the NIGHT" then mounted the real map inside its press, where the first of the map's
reads the file had never mocked threw. In the default order neither test ever mounted the map.
Importing the two modules in a throwaway `beforeAll` failed BOTH banner tests in the default order,
3 runs of 3.

**It is a race as well as an order.** By 04386b5c — #842 had added 23 tests and changed `App.jsx`
and `MapView.jsx`, and #850 had changed `MapView.jsx` — the failure was no longer repeatable:
seed 10 ran the same order twice and failed once. A 31-seed sweep left running overnight also failed
eight other tests, in runs spread over hours while the machine slept, and each of those seeds passed
when run awake, with this fix and without it. Named in case one of them turns out to be flaky after
all: "picks up a change on every opening of the dialog, not just the first", "does NOT let a stale
solar pick borrow the night licence (Codex, #803)", "lets the mount read land after the dialog has
answered without changing anything", "reads them once and hands one home to both the provider and
the Map pane", "does not take one drive-time stamp, read back at the database's precision, for a
change", "does not take a recalculation for a move — a save that lands under its spinner stands",
"moves the map off the night when its end passes with no new status — the provider re-renders App"
and "accepts a night the pane asks for with provenance, whatever asked for it".

**The fix: wait for the overlay, and stub the map inside it.** Both tests press the banner through
one helper, `pressAuroraBanner`, which waits for the overlay's dialog and for the map stub inside
it, so every order runs the same code. `MapView` is stubbed rather than kept real with its reads
mocked: no assertion in the file reads that map, `MapView`'s own suites mount it with Leaflet
stubbed, and keeping it real meant mocking the four reads it makes on this route
(`getAuroraLocations`, `getAuroraForecastResults`, `getAuroraForecastAvailableDates`,
`getAstroAvailableDates`) and fixturing its aurora rules — a status without `active: true` dropped
it out of aurora mode as it mounted. The first cut did keep it real, and under the load reproduction
in `frontend-test-standards.md` (the whole suite three times at once under 16 busy loops) the cold
overlay wait — both chunks, Leaflet included, and the first render — took 2.8–3.8 s against Testing
Library's 4 s ceiling. A harsher variant, twelve copies of this file at once under the same busy
loops, failed 17 tests, every one of them at that wait; the same variant with the stub failed none,
and neither did 24 copies. `frontend-test-standards.md` gains the rule (**Do not open a lazy subtree
and leave before it has mounted, unless mounting it is inert**), says when a stub like this one is
not the child-mocking it bans, and no longer says a seed always reproduces an order dependency.

**Proved.** The default order and 42 seeds (1–40, 424242, 7777777) each pass all 45 tests, with no
console output beyond the provider-crash test's own `Error: boom`, and so does the `beforeAll`
variant in the default order and three seeds. Mutation-checked: removing the `MapView` stub fails
both banner tests in the default order and three seeds, naming `getAuroraLocations`, so the real map
cannot come back unnoticed or in only some orders; dropping the banner route's night provenance in
`App.jsx` fails both on the date (`expected '2026-09-17' to be '2026-09-15'`); dropping the overlay
fails both at the wait. Removing the wait alone fails nothing, and the helper's comment says why it
stays. The whole suite, three runs at once under 16 busy loops, passed all 242 files and 6,004 tests
in each run with this change. Two of those runs, with the load average peaking at 713, also caught
one unhandled error each from `createEventSource.test.js`: a real 5 s reconnect timer that one of
its tests leaves armed. That leak predates this change, which does not touch it.
