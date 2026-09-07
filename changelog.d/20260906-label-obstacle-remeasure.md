### Docs — the Map tab's label obstacles, measured properly

`map-tab-v2-plan.md` §4 #31 licensed widening the Map tab's top-left chrome — a label-placement
obstacle — from a variable 187–300px to a constant 334px, and it licensed it by *measuring* that no
label moved. The control has since grown to 504px, and three more surfaces (the landing card and the
drilldown's two panels) joined the obstacle list. Neither change re-ran the measurement, so the
licence had been spent at sizes nobody had checked.

It has now been re-run against the real placer, with real label boxes measured in headless Chromium
off the built stylesheet. **The licence holds, and holds exactly where it was claimed**: at the tab's
own opening framing the widening changes nothing at all — the placed set and every label's position,
at every comparable state. Two qualifications the original entry did not carry are now recorded: it
is not one change (a `max-width` clamp makes it 334→504 on wide frames and 334→480 on a narrower
one, and below 640px there is no widening to license at all), and away from that framing it is cheap
but not free.

**The three drilldown panels are a different matter, and that is the finding.** Seeding them as
obstacles does not merely hide the labels beneath them: the greedy pass reshuffles around them and
drops labels that had clear air elsewhere on the map, real destinations among them. It worsens the
busier the map is. Two candidate cures are ruled out by measurement rather than argument — a retry
pass after the greedy one recovers nothing by construction, and not seeding the panels is worse
because it puts labels under an opaque plate. Recorded as a residual with the numbers attached.

⚠️ **The instrument is committed this time**, at `scripts/measurements/label-obstacle/`, which is the
actual fix for what created this residual: a licence granted by a measurement nobody could repeat.
Its README carries the traps it fell into. And the write-up leads with its findings rather than its
counts, deliberately — those counts moved on every one of seven review rounds, each time because the
instrument became more faithful and never because the app changed. Re-run it rather than cite them.

No behaviour change.
