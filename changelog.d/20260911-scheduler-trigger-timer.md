### Fixed — the Scheduler screen's "Triggered ✓" confirmation no longer arms a timer nobody owns

`SchedulerView`'s Run Now handler armed its dismiss timer *after* awaiting `triggerJob`, and its
unmount cleanup cleared only the timers that already existed. `ManageView` renders the screen behind
`activeTab === 'scheduler'`, so switching tab while the call was in flight unmounted it before any
timer existed. The cleanup had nothing to cancel, and the continuation then armed a timer with no
owner. This is the arm-after-await hole #809 closed in `ModelSelectionView`. #809 found this second
instance too and left it for its own change. In jsdom such a callback can fire after teardown, and
vitest fails the run on that unhandled error while still reporting every test as passing.

A second leak lived in the same slot. The button is disabled only once a call resolves, so a
double-click sends two calls. Each response armed a timer into the one
`timerRefs.current[jobKey]` slot without clearing the previous id, and the cost was visible. The
first response's timer ended the confirmation at 2 s, which re-enabled the button. The second
response's timer was still pending, due at 2.5 s. A fresh Run Now pressed in that half-second had
its confirmation wiped by it, well short of its own two seconds. The first timer, meanwhile, was out
of the unmount cleanup's reach.

**The fix is the repo's existing idiom, adapted per job.** Each job's button is now a small
`RunNowButton` that owns its own `triggered` state and dismisses it from an effect keyed on that
state — the shape of #809's success banner and `RegisterPage`'s cooldown. An effect never
runs after unmount, so the arm-after-await window is gone and no `isMounted` guard is needed. #809's
review rejected that guard as a pattern React discourages. A second response while the confirmation
is showing sets `triggered` to the value it already holds. The effect's deps then compare equal, so
it does not re-run, and there is only ever one timer per button. The `timerRefs` and
`triggeredJobs` maps are both gone.

**Seven tests.** Three fail against `main`'s component, each for the reason it names:

- a timer still pending after an unmount mid-call
- an orphan still pending after a double-click
- a fresh confirmation wiped at 2.5 s

The other four pin behaviour `main` already had right, so the new structure cannot regress it:

- the delay: still showing at 1999 ms, gone at 2000 ms
- the unmount cancelling a dismiss that is really pending
- the error path
- the confirmation staying on the job that was triggered

**Mutated eleven ways, and ten are killed.**

- Dropping the effect's cleanup.
- A shorter delay and a longer one.
- Empty deps.
- An effect that never arms the dismiss.
- Moving the timer back into the handler with no cleanup: four tests reject it. `main`'s own shape, which adds the unmount cleanup, fails the three above.
- Dropping the disable-while-triggered.
- Swallowing the error.
- Dropping the poll interval's `clearInterval`, which the "nothing left on the clock" assertions also count.
- Disabling the button while a call is in flight. This is a plausible follow-up rather than a bug, and it fails only the two double-click tests, whose premise it removes.

The eleventh, dropping the effect's `!triggered` guard, **survives, correctly**. It only arms an
idle timer that sets `false` to `false`, which the unmount then cancels, so it is an equivalent
mutant.

The timer tests run on a frozen fake clock rather than `shouldAdvanceTime`, which would charge real
elapsed time against the window under test. Review found that a queued `mockReturnValueOnce`
survives `vi.clearAllMocks()`, so a test that failed before consuming its deferred call handed that
never-settling promise to the next test's click. The in-flight-guard mutant showed it: the
unrelated error test failed. The block now resets the mock in `beforeEach` and releases any
outstanding call in `afterEach`.

**Tested, not seen.** No browser check was made for this change. Everything above is tested. The
`RunNowButton` markup is unchanged from `main`'s button: the same classes, `data-testid`, text and
`disabled` rule.
