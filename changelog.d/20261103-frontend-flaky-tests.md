### Fixed — flaky frontend tests wait for what they assert

Six Vitest tests that failed intermittently in CI now wait for the thing they assert instead of
reading it once: the Scheduler day tab's "takes today from the served clock" (the label is on screen,
dated from this machine's clock, before the served day lands), the Runs day strip's measured-width
test (the captured ResizeObserver may not be constructed yet), the sub-region picker's scroll into
view, the region form's focus moves, and the Plan sheet's focus after closing. The location sheet's
rating-badge test asserts `backgroundColor` rather than the `background` shorthand, which jsdom 30.1.3
computes as a longer value. Test-only; no product change.
