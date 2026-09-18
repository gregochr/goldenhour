### Fixed — Map tab phone: the counts footer stays in the accessibility tree while the tide strip is up

T7 (#882) hid the Map tab's bottom-centre count line (`.wf-map-counts-footer`) with
`display: none` on the phone whenever the tide strip is showing, per the design's own reasoning
that stacking both lines pushes the map to nothing. `display: none` also pulls an element out of
the accessibility tree, which the design's screen-space reasoning never considered — a
screen-reader user on the phone lost the count/rated/filtered figures for as long as the strip was
on, with no equivalent anywhere else on the tab (flagged as an owner item, §6 Q8, in T7's own
adversarial review).

Resolves Q8 with option 2: the footer is now visually hidden with an `sr-only`-shaped clip
(`position: absolute; width/height: 1px; overflow: hidden; clip-path: inset(50%)`) instead of
`display: none`, written out because the rule lives inside a media query rather than on a class.
A sighted reader sees exactly the same layout as before; a screen-reader user can still reach the
count. No visual or layout change — confirmed against `mapPhoneChromeCascade.test.jsx`'s
arithmetic that the rest of the phone's lifted chrome stack is untouched, and the footer already
carries `pointer-events: none` so the clipped box can never intercept a tap.

Adversarial review (CSS-cascade lens) caught a real second-order effect: `MapCallout.jsx`'s
placement-band logic (`utils/mapCallout.js#calloutBand`) skips a bar with a zero-size rect, which
is what had excluded the counts footer while it was a genuine `display: none` `0×0`. The new `1×1`
clipped box clears that bare `> 0` test, so — combined with the footer's pre-existing `always: true`
width-test opt-out — it would have started counting as a real, invisible floor/ceiling bar for the
callout's placement band on the phone. Currently harmless by coincidence of the two bars' `bottom`
values, not by any enforced invariant, so fixed alongside: `calloutBand`'s zero-size skip now reads
`> 1` rather than `> 0` (any real chrome bar is always many pixels in both dimensions; only a
clipped, invisible element is ever `1×1`), with a new test (`mapCallout.test.js`) and a corrected
`MapCallout.jsx` doc comment that had attributed the footer's zero-size rect to `display: none`
specifically.

See `docs/engineering/tide-window-plan.md` §6 Q8 and §4 #19.
