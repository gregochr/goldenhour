### Docs — Q5 decided: the forecast's two picks may name the same region

The Map tab's design spec wanted BEST BET and ALSO GOOD to differ in both window *and* region. The
app's picks come from `PlanWindowProjector.selectPicks`, which ranks windows, so they always differ
in window but can name the same place on two nights. Whether to force them apart was an open owner
question (`map-landing-plan.md` §6 Q5), and it has now been **decided on a production measurement**
rather than on argument.

It is common: over 39 builds, 21 carried two picks and **10 of those named one region twice**. But
forcing them apart would have **removed** the second pick in 8 of those 10 rather than naming a
second place — the best *different* region was below the 3.0 floor, a full star behind, or absent
altogether. So the spec's rule would have cost ALSO GOOD in about 40% of the builds that had one, to
avoid repeating a region's name. The two picks are left as they are.

The spec's own rationale also turned out to argue less than it seemed: *"two picks on the same night
are one pick"* is a case about the window, which ranking windows already rules out. A reader who
wants a different place still has the drilldown.

The measurement is committed at `scripts/measurements/pick-region-repeat/` — the query exactly as it
ran on production, validated against the real projector — and a new test,
`picksMayNameTheSameRegion`, pins the decision so the code cannot quietly be "fixed" back to the
spec. It is the only test that fails if that rule is ever added.

No behaviour change.
