### Fixed — the Scheduler Day tab tests no longer fail intermittently in CI

Two tests in `SchedulerDayTab.test.jsx` were racing the screen. "follows the container when it is wider"
sometimes failed with `expected 360 to be 912`: the Day picture sizes its SVG with a `ResizeObserver` that
`useElementWidth` constructs in a passive effect, and the test fired its fake resize as soon as the SVG was
on screen, which can be before that effect has run, so the resize reached nothing. Both width tests now wait
until the plot's own observer is watching the plot before they resize it; "is never narrower than the minimum" could previously
pass without testing anything. "is not moved by Rewind" waited for the day label, which is on screen from
the first paint, and then looked for the Now line before the served day had landed; it now waits for the
Now line first. Test-only change.
