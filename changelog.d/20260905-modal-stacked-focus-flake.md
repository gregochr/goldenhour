### Fixed — a load-sensitive flake in `Modal`'s stacked-focus test

`Modal > stacked > does NOT yank focus back from wherever the reader has since moved to` failed
intermittently under CPU load — 1 of 10 runs of its own file under a 24-process load on an 8-core
Mac, 2 of 10 re-measuring the same way, and 2 of 6 concurrent full-suite runs — while passing every
idle run. The assertion found the dialog **root** (`<div role="dialog" data-testid="under">`) where
it expected the button outside it.

⚠️ Those counts are existence proofs, not a rate: an interleaved A/B under comparable load saw 0 of
12. Load is not the variable — **phase** against jsdom's `requestAnimationFrame` interval is. What
pins the mechanism is a delay sweep: busy-spin *N* ms between the render and the rest of the test
body, 8 reps per step, and the unfixed version is stolen 1/8 at 0 ms, 3/8 at 8 ms, 6/8 at 12 ms and
8/8 from 16 ms up — a ramp saturating at exactly the frame interval. The frame lands one interval
after mount at arbitrary phase, so a delay of *d* steals with probability ≈ min(1, *d*/16), and the
residue at *d* = 0 is the roughly-one-in-ten seen in the wild.

**No product defect caused it.** The thief was the dialog's own opening. `useDialogFocus` moves
focus to the dialog root on a `requestAnimationFrame` scheduled at MOUNT, and — measured by counting
frames — that is the only frame `Modal`'s own code schedules: the hook's effect depends on the
literal `true` that `Modal` passes, so neither stacking nor unstacking re-runs it. Uncovering a
stacked dialog is therefore not treated as an open **at this call site** — the hook itself re-opens
readily for the dynamic `active` that `BottomSheet` and `RegionsJump` hand it. The test simply never let that frame land. It
rendered the dialog and then immediately played a reader who had been inside it for a while, leaving
the open-focus frame in flight across every later statement; on a starved machine it fired at the
test's one yield point and took focus back to the root. The hook's own guard could not help — it
stands down only when focus is on a descendant — and this is the one test in the block that
deliberately ends with focus outside the dialog.

The fix settles the open-focus before the reader moves, which is also the real sequence. **The
settle belongs there and nowhere later**: this flake is shaped the other way up from the usual one,
passing while the frame had *not* yet fired, so settling it beside the assertion fails every time.

A second assertion now holds the result across a forced frame, and it is the half that keeps the
test honest rather than merely green. Settling at the top removes today's race but would also blind
the test to the race returning: flipping `useDialogFocus(true)` to `useDialogFocus(!stacked)` — the
"treat uncovering as an open" change — passes the first assertion 20 runs of 20 on an idle machine
and fails the second 20 of 20, because that mutant schedules a second frame at the uncover which
only a forced frame reveals.

Verified against a control arm rather than green runs alone: under identical load the unfixed test
failed 2 of 10 and the fixed one passed 26 of 26. Removing `Modal`'s orphan-focus guard still fails
this test and only this test, so it remains the sole cover for the behaviour it was written for.

⚠️ **One product question #776 raised is narrowed, not closed.** Uncovering is not an open, but the
restore still does nothing when `lastInside` is null — a reader who opened a dialog and never
touched a control inside it (or any Safari pointer route, where a click fires no focus event) can be
left on `<body>` beneath a live `aria-modal` layer, which is the defect the hook exists to prevent.
No test covers that cell and this change does not address it.
