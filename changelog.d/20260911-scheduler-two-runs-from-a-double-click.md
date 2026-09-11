### Fixed — a double-click on the Scheduler's Run Now queues one run, not two

`SchedulerView`'s `RunNowButton` disabled itself only once `triggerJob` had *resolved*
(`disabled={disabled || triggered}`), and `main`'s button had the same rule. So the second press of
a double-click landed on a button that was still enabled and sent a second
`POST /api/admin/scheduler/jobs/{jobKey}/trigger`. `DynamicSchedulerService.triggerNow` queues an
immediate run for every call it receives, and `wrapTarget` has no overlap guard. The daily briefing
and the tide refresh have none of their own either, so one double-click ran them twice, possibly at
the same moment on the five-thread scheduler pool. The batch pipeline jobs refuse a concurrent
submission through a shared lock. There the second run could lose that lock and be recorded as a
failed pipeline run for nothing.

This supersedes two passages in the entry just below it, *the Scheduler screen's "Triggered ✓"
confirmation no longer arms a timer nobody owns*:

- its present-tense "the button is disabled only once a call resolves, so a double-click sends two
  calls";
- its bullet calling the in-flight guard "a plausible follow-up rather than a bug".

It was a bug: two runs of a job from one intended press.

**The button is now disabled from the click until the call settles.** A `pending` state is set
before the await and cleared in a `finally`, and `disabled` reads `disabled || pending || triggered`.
React flushes a click's state update before the browser dispatches the next input event, and a
browser dispatches no click to a disabled button. So a press while the call is in flight never
reaches the handler. The label stays "Run Now" while the call is in flight, because "Triggered ✓"
would claim a run the server has not yet accepted. The `finally` hands the button back after a
failure too, so the retry the error banner asks for goes through.

The dismiss timer stays in the effect keyed on `triggered`, where the earlier entry put it. It has
not moved back into the handler, and there is no `isMounted` ref.

**This guards the button's own round trip and nothing more.** `triggerNow` returns as soon as the
run is queued, so the button is free again two seconds after the confirmation appears, while the job
may still be running. A second run can still be queued by any of these:

- a press after the confirmation clears;
- a second browser tab;
- switching tab and back while the call is in flight;
- a retry after a failed response that the server had in fact acted on.

Some targets already refuse an overlapping run: the batch submissions (`forecastBatchRunning`,
`auroraBatchRunning`) and the cloud-verification backfill. Giving the briefing and the tide refresh
the same guard is an owner decision, and it is not built here.

**Two costs this accepts.**

- **Keyboard focus.** A keyboard user who presses Run Now loses focus to `<body>` when the button
  disables. It already did that on success, when `triggered` disabled it. On a failed call it is
  new: focus used to stay on the button. This is the same pattern as the app's other in-flight
  buttons.
- **No timeout.** The API client sets no timeout, so a stalled request leaves the button disabled
  until the connection gives up. The alternative was a duplicate run.

**Tests.** The earlier entry's two double-click tests are replaced. They sent two calls on purpose,
and the in-flight guard makes that impossible. Measured against the new component:

- the orphan test fails at `toHaveBeenCalledTimes(2)`, receiving 1;
- the fresh-window test fails at `toHaveBeenCalledTimes(3)`, receiving 2.

Two tests take their place:

- **A second press while the call is in flight sends no second call.** The button is found by role
  and name, asserted disabled while the call is pending, and confirms once when it settles.
- **The retry after a failed call actually sends.** The existing error test pins only that the
  button is enabled after a failure. On its own that would pass a guard that latched once per
  mount.

The unmount-mid-call timer test is kept unchanged.

**Mutated five ways, all killed.**

- Dropping `pending` from `disabled` fails the in-flight test.
- Never setting `pending` fails the in-flight test.
- Clearing `pending` only on success fails the retry test and the error test.
- Never clearing `pending` fails those two and the confirmation-window test.
- A ref that latches on the first press, beside `pending`, fails only the retry test.

**Tested, not seen.** No browser check was made. The Scheduler screen is behind the admin sign-in.
