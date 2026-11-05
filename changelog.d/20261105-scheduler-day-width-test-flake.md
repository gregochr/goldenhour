### Fixed — the Scheduler Day picture's width tests no longer fail intermittently in CI

"follows the container when it is wider" in `SchedulerDayTab.test.jsx` sometimes failed with
`expected 360 to be 912`. The Day picture sizes its SVG with a `ResizeObserver` that `useElementWidth`
constructs in a passive effect, and the test fired its fake resize as soon as the SVG was on screen, which
can be before that effect has run, so the resize reached nothing. Both width tests now wait until the plot's
own observer is watching the plot before they resize it; the second one could previously pass without
testing anything. Test-only change.
