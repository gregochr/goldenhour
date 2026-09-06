### Docs — the label-placement obstacle re-measured at 504px, and with the drilldown panels

`map-tab-v2-plan.md` §4 #31 licensed widening the Map tab's top-left chrome — a label-placement
obstacle — from a variable 187–300px to a constant 334px, and it licensed it by *measuring* that no
label moved. The control has since grown to 504px, and three more surfaces (the landing card and the
drilldown's two panels) joined the obstacle list. Neither change re-ran the measurement, so the
licence had been spent at sizes nobody had checked.

It has now been re-run: the real placer and the real candidate builders, against label boxes measured
in headless Chromium off the built stylesheet with the components mounted in their real ancestor
chain, over 560 map-state pairs. **At the tab's own opening framing the widening is still exactly
identical on all four viewports** — the licence holds where a reader actually lands. Over a wider
sweep it costs one label that had clear air, in one state of 140. The three panels drop a great deal
more (up to 64% of the placed set on a phone) and **none of it is collateral**: every label they drop
is one the panel covers, which is what seeding it as an obstacle is for.

The general rule that fell out, now recorded where obstacles are declared: the nudge ladder reaches
±38px and its horizontal fallback is anchor-relative, so an obstacle that swallows a label's own
anchor cannot be escaped on any rung. **Obstacle height is what spends the placement budget, not
width** — which is why a 36px band's licence does not cover a several-hundred-pixel panel.

No behaviour change. Method, figures and stated limitations are in `map-landing-plan.md` §4b.1.
