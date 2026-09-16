### Fixed — two App tests that could pass or fail on when they happened to run

`App.test.jsx`'s "the date handed to the Map pane" block (six tests, on top of
`fix/app-test-aurora-overlay-order`'s reordering fix) had two wall-clock dependences an adversarial
review found rather than a failing run — neither has been observed to fail here. Test-only: no
product code changes.

**A fixture computed once could go stale mid-run.** `TOMORROW` and `YESTERDAY` were computed by
calling `ukDateStrOffset` when the file loaded, but `App.jsx` reads `ukDateStr()` fresh on every
render. A run whose module load and whose execution of this block straddle a UK midnight leaves the
fixture's `YESTERDAY` a day behind `App`'s own live "today" — and `mapDates.isNightOver` then
measures a night that is still genuinely in progress against a `todayStr` that has moved on,
refusing it. Reproduced directly against the real `isNightOver`/`ukDateStr`/`ukDateStrOffset`: a
`YESTERDAY` fixed at 23:50 BST on the 13th, checked against a live `ukDateStr()` read at 00:10 BST
on the 14th (the same 20-minute crossing a slow suite could straddle for real), comes back
`isNightOver === true` — wrongly over. The same check with both reads pinned to one frozen instant
comes back `false`.

**A control that read the clock immediately was paired with a wait that didn't.** The last test's
control assertion — selectedDate still honours the night immediately after a status with
`currentNightEndsAt: now + 1.5s` lands — is genuinely safe, since nothing can make a 1.5s
real `setTimeout` fire in the same microtask turn. The `waitFor` two lines later that then waits for
the night to clear is not: `AuroraStatusContext`'s re-check loop schedules a real 1.5s timer, and
under CPU contention a real timer can fire late enough to miss Testing Library's 4s ceiling.
Reproduced by exaggerating the same shape: a required real delay of 4.5s against the OLD
`waitFor(..., { timeout: 4000 })` pattern threw after genuinely spending 4188ms finding out, while
the fixed pattern's deterministic clock jump handled the identical 4.5s requirement in 12ms.

**The fix.** The whole block now freezes `Date` (`vi.useFakeTimers({ shouldAdvanceTime: true })` +
`vi.setSystemTime`, matching `WindowFirstBriefingContext.test.jsx`'s established shape) at
`mapDates.js`'s own documented example — 00:30 BST on 2026-08-14, where the UK and UTC calendars
already disagree — so every read of "now", fixture and app-side alike, answers from one instant
regardless of when the suite runs; `YESTERDAY`/`TODAY`/`LATER` are literals derived from it rather
than a second `ukDateStrOffset` call, and the local `pastAndFutureForecasts` no longer reaches for
the file's module-level `FORECASTS`/`TOMORROW`, which are still real-wall-clock-derived and would
otherwise mix a real calendar date into a block pinned to a fictional one. The last test switches to
a fully fake, manually driven clock (`shouldAdvanceTime: false`) once its setup no longer needs
`findBy*` to resolve, and replaces the `waitFor` with one deliberate `vi.advanceTimersByTimeAsync`
jump past the end — the night-in-progress case itself stays real, in the small hours, as asked.

**Proved.** Default order and ten shuffle seeds (1, 2, 3, 7, 8, 9, 42, 123456, 424242, 999999) each
pass all 45 tests. The simulated-crossing and forced-delay reproductions above each fail on the OLD
pattern and pass on the fixed one. Mutation-checked: dropping the aurora banner route's night
provenance in `App.jsx` (`{ isNight: trigger.kind === 'aurora' }` → `{ isNight: false }`) fails
"honours the NIGHT in progress" and "clears the night licence" on the date
(`expected '2026-08-21' to be '2026-08-13'`), leaving the other five green; disabling
`AuroraStatusContext`'s re-render-at-end (`setNightEnded`) fails "moves the map off the night" on
its own assertion, confirming the rewritten wait still exercises the real mechanism rather than
passing by construction.
