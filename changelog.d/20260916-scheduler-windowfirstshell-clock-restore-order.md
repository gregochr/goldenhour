### Fixed — three tests that could leave a fake clock running for whatever test came after them

Three tests across two files restored the clock on their own last line rather than in `afterEach` or
a `finally`, so a failing assertion above that line skipped the restore and left the clock installed
for later tests in the same file — which then failed for the wrong reason, or passed against a frozen
date, pointing at the wrong test.

`SchedulerView.test.jsx`'s "polls for job updates at the configured interval" called
`vi.useFakeTimers({ shouldAdvanceTime: true })` and called `vi.useRealTimers()` only after its final
`vi.waitFor`; its `describe` has no hook that restores timers. `WindowFirstShell.test.jsx`'s "states
the forecast's age, and never the model that produced it" and "reads the age as UTC, which is how the
backend writes it" both called `vi.setSystemTime(...)` with no prior `vi.useFakeTimers()` — which
installs a clock faking only `Date`/`Temporal` — and each called `vi.useRealTimers()` on its own last
line; the file's `afterEach(() => vi.restoreAllMocks())` clears mock call state, not a clock.

All three now restore the clock in a `try`/`finally` around the test body, matching the convention
`SchedulerView.test.jsx`'s own "Run Now confirmation" tests already use. No behaviour changes when a
test passes — only what happens after one throws.

Proved with scratch copies of both files (built, exercised, then deleted — never committed). Each
copy captured a real-time or real-timer reference before any test ran, broke one assertion inside the
affected test so it threw before reaching its own restore line, and added a probe test immediately
after it: for `WindowFirstShell`, one asserting `Date.now()` stays near that captured reference; for
`SchedulerView`, one asserting `vi.isFakeTimers()` is `false` (its test installs full fake timers, so
`isFakeTimers()` reflects the leak directly; `setSystemTime` alone does not flip that flag even while
`Date` stays frozen — the drift check is what catches that case). Against the unmodified files, both
probes failed alongside the deliberately-broken test, on a fake clock frozen at `2026-08-04`. With the
`try`/`finally` in place and the same deliberate failure still forced, the broken test still failed
(as intended) but every probe passed.

This is the same defect class documented in `docs/engineering/frontend-test-standards.md`'s "What NOT
to do" (landing with `fix/create-event-source-timer-leak`): a real resource armed by a test outlives
that test whenever its release sits on a line an earlier throw can skip.
