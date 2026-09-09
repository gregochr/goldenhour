### Fixed — two auto-dismiss timers that outlived the components that scheduled them

`ModelSelectionView`'s success banner and `OutcomeModal`'s save hand-off were both bare
`setTimeout` calls with no cleanup, so the callback ran whether or not the component was still
mounted. Both now own their timer in a ref, cancel it on unmount, and — the half that matters —
refuse to arm one at all once unmounted.

**Found by measurement, not by reading.** The vitest 4 → 5 bump (#799) merged with every check
green. A paired flakiness check afterwards — six full-suite runs on each major, same machine, same
load, back to back — turned up one vitest 5 run that exited **1 while reporting `5533 passed,
0 failed`**. The cause was `ModelSelectionView.jsx:233`: `setTimeout(() => setSuccess(null), 3000)`
firing after its test's jsdom was gone, so React's `dispatchSetState` read a `window` that no longer
existed. Vitest fails a run with unhandled errors (`_checkUnhandledErrors` sets `process.exitCode`
unless `dangerouslyIgnoreUnhandledErrors`).

⚠️ **Stated as frequency, not as a capability difference.** The error did not reproduce in 6 vitest
4 runs and appeared in 1 of 6 vitest 5 runs. That gate is long-standing rather than new in 5, and
1-in-6 against 0-in-6 is not a significant difference, so the honest claim is that a **latent bug
here was observed once** — not that vitest 5 is more failure-prone.

⚠️ **The exit code and the summary disagreed**, which is the part worth remembering: anything
reading "0 failed" out of the output rather than `$?` would have called that run green. This repo
already learned that on Maven; it now applies to the frontend job.

**An unmount cleanup alone did not fix it — the first cut still leaked, and review caught it.**
Both components arm their timer *after* awaiting a network call, so unmounting during that await
runs the cleanup while the ref is still null (cancelling nothing) and the resolved handler then arms
a timer nothing owns. `ManageView` renders `ModelSelectionView` behind `activeTab === 'models'`, so
changing tab mid-request is the ordinary way to reach it. A `mounted` ref guard closes it.

**The other user-visible bug**, in the one component that is actually mounted: `ModelSelectionView`
scheduled a fresh 3s timer per toggle without cancelling the previous one, so three quick toggles
let the first timer wipe the third message early. `flashSuccess` now clears any pending timer first.

⚠️ **`OutcomeModal` has no production render site**, so its defect is latent. Outcome recording has
been API-only since 2026-02-27 (`v1-retirement-plan.md` §8 keeps the component deliberately as an
owner question), and the only `onSaved` that has ever run is a test double — which also means this
file never produced the unhandled error described above, since a mock reads no `window`. Whether a
manual Close inside the 1.5s window should still notify the opener is an open product question for
whoever gives the component a caller; it is deliberately not decided here.

**Scope, stated because two neighbours look like the same defect and are not.** `PlanErrorBoundary`
was on the first list of suspects and is untouched: its only `setTimeout` is a word in a doc comment,
which the survey grep counted as code. `WindowComingUpConditions.scrollToEntry` is left as it is —
its 1.6s timer removes a CSS class from a captured node, touches no React state, never appeared in a
failing log, and sits in a module-level helper with no lifecycle to clean up from.

**Six tests added, and every guard was mutated out to prove they fail without it** — the two
cleanups, the two `mounted` guards, and both delay constants (a `1500 → 500` that would snatch the
confirmation away early used to survive the suite untouched). The tests run on a frozen fake clock
rather than `shouldAdvanceTime`, because that option charges real `waitFor` time against the very
window under test — measured here as a failing assertion, not a hypothetical.
