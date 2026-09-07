### Docs — the Map tab's label obstacles, measured properly

`map-tab-v2-plan.md` §4 #31 licensed widening the Map tab's top-left chrome — a label-placement
obstacle — from a variable 187–300px to a constant 334px, and it licensed it by *measuring* that no
label moved. The control has since grown to 504px, and three more surfaces (the landing card and the
drilldown's two panels) joined the obstacle list. Neither change re-ran the measurement, so the
licence had been spent at sizes nobody had checked.

It has now been re-run against the real placer, with the real label boxes measured in headless
Chromium off the built stylesheet, over every combination of camera, selection and frame height.
**The widening is the cheap change it was taken for** — going from 334px to 504px costs at worst
four labels that had clear air across 210 states, roughly a sixth of what the panels cost — but
it is not free, and the original entry's "identical" holds only at the framing the tab opens on. Two
of its qualifications do not survive and are now recorded: the phone was never a passing viewport,
because below 640px neither width renders; and "every label's position" overstates what is testable,
since a chip can step a nudge rung without anything being lost.

**The three drilldown panels are a different matter, and this is the new finding.** Seeding them as
obstacles drops labels that had clear air — up to twenty, twenty-five and twenty-three per 280 map
states, and twenty-nine for a window panel sized to a realistic region count, with real destinations
among them. It gets worse the busier the map is. That is a genuine cost rather than a defect with a
line to fix — not seeding them is worse, since it puts labels under an opaque panel — so it is
recorded as a residual with numbers attached rather than quietly absorbed.

The measurement instrument is committed this time, at `scripts/measurements/label-obstacle/`. That
is the actual fix for what created this residual: a licence granted by a measurement nobody could
repeat. Its README carries the two traps it fell into, both of which failed plausibly rather than
loudly.

No behaviour change. Method, figures and limitations are in `map-landing-plan.md` §4b.1.
