### Docs — waiting for a deferred effect is now a named test class

`docs/engineering/frontend-test-standards.md` gains **Waiting for a deferred effect (a frame, not a
tick)**. Several behaviours here are deliberately deferred by one animation frame — `useDialogFocus`
moves focus on a frame so Safari cannot silently drop it — and how a test waits for that frame
decides what its failures look like. The section records the three forms already live in the suite:
`await waitFor` under real timers (`BottomSheet`, `Modal`), `act(() => vi.advanceTimersByTime(n))`
under fake ones (`WindowFirstShellSheet`'s `settle()`, itself found by a mutation sweep), and the
deliberate synchronous-rAF substitution used where an *ordering* claim is the point and no wait can
express it. It also records the forced double-frame variant, which is what lets `Modal`'s
stacked-focus test see a second frame a `waitFor` would have been satisfied by. A matching
"What NOT to do" bullet states the rule about asserting inside the callback directly.

That Vitest's fake timers fake `requestAnimationFrame` was verified by probe rather than assumed.

Three claims in the first draft were caught by adversarial review and corrected rather than shipped:
that `WindowFirstShell`'s handoffs defer "on the same reasoning" as `useDialogFocus` (they defer for
their own, different reason — the element may be rendering for the first time on that commit), a
substitution-site list that missed `locationSheetShell.test.jsx` because its spy wraps across two
lines, and two figures for one experiment.
