### Fixed — the Models screen's success banner no longer outlives the screen

`ModelSelectionView`'s success banner cleared itself with bare `setTimeout(() => setSuccess(null),
3000)` calls placed after each handler's network await, so the callback ran whether or not the
component was still mounted. The dismiss is now an effect keyed on `success` — the idiom
`App.jsx`'s run banner and `RegisterPage`'s cooldown already use.

**Found by measurement, not by reading.** The vitest 4 → 5 bump (#799) merged with every check
green. A paired flakiness check afterwards — six full-suite runs on each major, same machine, same
load, back to back — turned up one vitest 5 run that exited **1 while reporting `5533 passed,
0 failed`**. The cause was `ModelSelectionView.jsx:233`: the dismiss timer firing after its test's
jsdom was gone, so React's `dispatchSetState` read a `window` that no longer existed. Vitest fails a
run with unhandled errors (`_checkUnhandledErrors` sets `process.exitCode` unless
`dangerouslyIgnoreUnhandledErrors`).

⚠️ **Stated as frequency, not as a capability difference.** The error did not reproduce in 6 vitest
4 runs and appeared in 1 of 6 vitest 5 runs. That gate is long-standing rather than new in 5, and
1-in-6 against 0-in-6 is not a significant difference, so the honest claim is that a **latent bug
here was observed once** — not that vitest 5 is more failure-prone.

⚠️ **The exit code and the summary disagreed**, which is the part worth remembering: anything
reading "0 failed" out of the output rather than `$?` would have called that run green. This repo
already learned that on Maven; it now applies to the frontend job.

**Why an effect, and not a timer in the handler with a cleanup — which is what the first cut did,
and it still leaked.** The handlers arm nothing until after their network await, and `ManageView`
renders this screen behind `activeTab === 'models'`, so switching tab mid-request unmounts it before
any timer exists: an unmount cleanup has nothing to cancel, and the handler then arms a timer with
no owner. Review caught that, and the first repair — an `isMounted` ref guard — was itself caught
as introducing a pattern React's docs discourage and that no other file in this repo uses. An effect
has no window to guard: it never runs after unmount, and `setSuccess` on an unmounted component is a
no-op.

**User-visible fix.** Three quick toggles used to leave three independent timers, so the first to
fire wiped the newest message early. Each new message now re-runs the effect, whose cleanup cancels
the previous timer, so every message gets its full three seconds.

**Scope — two components examined and deliberately left alone, one found and deferred:**

- `OutcomeModal`'s `setTimeout(onSaved, 1500)` is **unchanged**. An earlier cut of this PR "fixed"
  it and was wrong to: nothing renders `OutcomeModal` (outcome recording has been API-only since
  2026-02-27), `onSaved` is a parent callback and the parent outlives the modal, and firing it
  honours the prop's own contract — "called after a successful save". Cancelling it on unmount
  silently decided a product question for a component with no caller.
- `PlanErrorBoundary`'s only `setTimeout` is a word in a doc comment.
- ⚠️ `SchedulerView.handleTrigger` **does** have this defect — it arms `timerRefs.current[jobKey]`
  after `await triggerJob(...)`, its cleanup clears only timers that already exist, and `ManageView`
  renders it behind `activeTab === 'scheduler'`. It is left for its own change. The survey that
  first listed suspects compared `setTimeout` and `clearTimeout` counts per file, and this file has
  a cleanup, so it passed that test while carrying the same hole — a count is not evidence a timer
  is owned.
- `WindowComingUpConditions.scrollToEntry`'s timer removes a CSS class from a captured node,
  touches no React state, and has no component lifecycle to clean up from.

**Four tests, and the implementation was mutated five ways to prove they bite**: dropping the
cleanup, shortening the delay, emptying the deps, dropping the null guard, and — the one that
matters — moving the timer back into the handlers, which three separate tests reject. They run on a
frozen fake clock rather than `shouldAdvanceTime`, which charges real `waitFor` time against the
very window under test.
