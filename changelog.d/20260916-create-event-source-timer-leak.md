### Fixed — the event-source tests no longer fail a run in which every test passed

`createEventSource.test.js` could make the whole Vitest run exit 1 with nothing failing: `Vitest
caught 1 unhandled error`, a `TypeError: EventSource is not a constructor` thrown from a timer in
`createEventSource.js`. Seen on 2026-09-16 in two of three full-suite runs made concurrently under a
16-process CPU load, each passing 242 of 242 files and 6,004 of 6,004 tests. The utility's code is
untouched; one stale line of its JSDoc is corrected.

**A test left a real timer armed, and its globals were put back underneath it.** "calls onError even
when readyState is CLOSED" ran on real timers and drove the CLOSED branch, which arms a 5 s
reconnect, but never called the cleanup `createEventSource` returns. `afterEach` then restored
`EventSource` to jsdom's, which has none. If the file's worker was still alive five seconds later,
the retry fired, `connect()` ran `new EventSource(url)`, and Vitest could attribute the throw only
to the last test that had run. The rest of the file takes milliseconds, so this was only ever seen
under load.

The test is older than the retry. `45f00dcd` wrote it as "skips onError when readyState is CLOSED",
when the handler returned early for a CLOSED source. `1d6f7f0a` removed that guard, so `onError`
fired whatever the state, and gave the test its current name. `13e270c7` then added a CLOSED branch
with a real 5 s timer and tested the retry on fake timers, leaving the older test on real ones.

**The fix has three parts: every connection is released after every test, the whole file runs on
the fake clock, and the hook checks for both kinds of leak.**

- **Release.** Each test opens its connections through a small wrapper that records the cleanup
  `createEventSource` returns. `afterEach` calls every one, whether the test passed or threw. The
  cleanup is the only handle on a connection's retry timer and its visibility listeners; closing
  the mock source reaches neither. The import is renamed, so the natural call in a test is the
  tracked one.
- **Fake clock.** `beforeEach` installs the fake clock for every test, so no retry a test arms can
  fire after it, and `afterEach` restores the real one. Five tests used to restore the real clock
  on their last line, which a failing assertion never reached.
- **Check.** The hook fails the test on either of two leaks, and each check sees one the other
  cannot:
  - a mock source still open (`connections left open`, with its URL) is a connection nothing
    released, with or without a retry;
  - a fake timer still pending (`retries left armed`) is a retry nothing cancelled, even behind a
    source the test closed itself.

One test is added: cleanup cancels a retry that is already pending. Deleting the `clearTimeout` from
the cleanup failed nothing before, measured against two suites:

- the old suite, where no test called cleanup while a retry was pending;
- this fix on the real clock without the new test, where the hook did call it, but `closed` turned
  that retry into a no-op.

The new test counts pending timers and fails that deletion; the hook's own count now fails the
CLOSED test too.

The JSDoc correction: `createEventSource` still documented `onError` as called only "when readyState
!== CLOSED", which has not been true since `1d6f7f0a`. The production build is byte-identical across
the edit.

**How the design got here.** This fix first kept the file on the real clock and checked only for
open sources. An adversarial review then found a test that check passes: it bypasses the wrapper and
reaches the CLOSED branch through the mock's own `close()`, which leaves a live 5 s retry behind a
source marked closed.

- Under that version, the test passed on a quiet run and brought the original TypeError back under
  the probe below.
- Under this one, the count fails it on every run. With the count deleted it passes, and the fake
  clock still stops it firing.

The same review's first proposal, the fake clock with only the count, misses the opposite case: a
test that opens a connection outside the wrapper and arms no retry passes under it, while the
open-source check fails that test. Moving only the leaking test onto fake timers would have fixed
that one test and none written later.

**Proved with a probe that makes the original failure deterministic.** A scratch copy of the file
had one test appended at top level that outlives the retry:
`await new Promise((r) => { setTimeout(r, 5600); })`, with a 10 s budget. Results with the probe:

- The original exits 1 with the TypeError, and all 20 of its tests pass, the probe included.
- Filtered to the CLOSED test plus the probe, the original still exits 1.
- Filtered to the not-CLOSED test plus the probe (the control), it exits 0.
- The fixed file exits 0 under all three.

Mutation-checked, each on a scratch copy, on a quiet run without the probe unless stated:

- Deleting the hook's cleanup call, or dropping the wrapper's record, fails 15 tests with
  `connections left open`. On the fake clock the leftover retry cannot fire, so the probe shows no
  unhandled error. On the real-clock version of this fix, the same deletion brought the TypeError
  back.
- Calling the untracked import from the CLOSED test, or from "constructs URL with base, path,
  params, and token", which arms no retry, fails exactly that test with `connections left open`.
  From a test that calls the cleanup itself, as "returns cleanup function that closes source" does,
  it leaves nothing behind, and passes.
- The review's bypass through the mock's `close()` fails that test with `retries left armed`.
- Deleting the fake clock from `beforeEach` fails all 20 tests: the timer APIs are not mocked.
- Deleting the hook's `vi.useRealTimers()` passes, but the fake clock outlives the file's last test,
  so the probe times out.
- A deliberately wrong assertion in "cancels the pending throttled retry and reconnects at once on
  visibility" is that test's only failure.

Against a copy of the utility:

- A cleanup without `clearTimeout` fails the new test, and the CLOSED test on the hook's count.
- A cleanup without `closed = true` fails only "does not reconnect after cleanup is called".
- A cleanup that never closes its source fails 19 tests.

The concurrent load reproduction was not re-run, because the probe produces its condition, a worker
alive past the retry, on every run.
