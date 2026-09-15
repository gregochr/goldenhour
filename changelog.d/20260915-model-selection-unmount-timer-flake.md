### Fixed — the Models screen's unmount-timer test no longer fails under load

`ModelSelectionView.test.jsx`'s "cancels its pending dismiss timer when it unmounts" failed
intermittently in full-suite runs on a heavily loaded machine — `expected 0 to be greater than 0` —
and passed when its file ran alone. Test-only: the component is untouched.

**The wait was satisfied before the thing it counted existed.** The test waited for the banner with
`findByText`, which resolves on the commit that renders it, but the dismiss timer the next line
counts is armed by a passive effect. Outside `act`, the commit a resolved request makes leaves its
passive effects to a later scheduler task, and React always yields between the two, because every
commit requests a paint. Testing Library resumes the test on a real `setTimeout(0)`, which races
that next `setImmediate` slice: if Node's millisecond clock ticks over before the event loop comes
back round — heavy load makes that likely — the timer wins and the test counts before the effect
has armed anything. The test did wait for what the response renders, as `frontend-test-standards.md`
asks; what it then counted is not rendered at all. The doc now says so (**And a commit is not its
effects**), including that a `findBy*` on a frozen fake clock hangs rather than races.

**The fix** puts the test on the fake clock and settles it with the file's own `pump()` — an awaited
`act` — as its three sibling fake-clock banner tests already do. An awaited `act` keeps flushing
until React's queue is empty, passive effects included, so the count no longer depends on which
macrotask wins, and on a frozen clock the 3 s dismiss cannot fire before the unmount however slow
the run. The original assertions are unchanged: a 3000 ms timer must be pending, and the unmount
must clear the last one scheduled. One is added, which the frozen clock makes free: nothing may be
left pending after the unmount (`vi.getTimerCount()` is zero). The dismiss is the only timer this
tree arms, and a cleanup that cleared it but armed a replacement at any other delay passed the
original test, because its `ms === 3000` filter cannot see one. The spies now wrap the fake clock's
functions and come off *before* the clock is uninstalled — restored after it, they put the fake
`setTimeout` back on the global (measured).

**Proved before and after, under load.** The original and the fixed test bodies, each repeated in a
scratch file and both run in the same Vitest invocation so they shared the load (16–24 busy-loop
processes on 8 logical CPUs — 4 physical cores — at load averages of 42–223): the original failed
41 of 2,700 repetitions, every one with `expected 0 to be greater than 0`; the fixed body failed 0
of 2,700, the last 1,350 of them with the added count in place. With the race forced — a 2 ms spin
right after Testing Library schedules its drain timer — the original failed 20 of 20.
Mutation-checked against the component: deleting the effect's cleanup, or moving the timer back
into the handler (the pre-#809 shape), fails the fixed test at its unmount assertion, as it failed
the original; a component with no dismiss timer fails at the precondition; a cleanup that arms a
2999 ms replacement fails only the new count, and passes the original; the idle null→null timer an
earlier version over-rejected still passes; and a handler-armed timer with an unmount cleanup still
passes here and fails the sibling "does not arm a dismiss timer when the request settles after it
unmounts" test, whose job that is.
