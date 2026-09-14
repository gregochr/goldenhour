### Fixed — the codebase's accessible-name comments now say what browsers actually do

An audit of the frontend's `{' '}` separators found the comments explaining them mostly wrong, in
three distinct ways — and found my own v2.20.4 correction wrong about its mechanism. Comments only:
the production bundle is byte-identical to `main`'s.

**The rule**, measured in Chromium, WebKit and Firefox at 1280px and 375px against the real
stylesheet, by removing separators one at a time from DOMs captured from the components' own
tests, with positive controls that had to glue and no disagreement between engines or widths: a
separator matters only between plain `display: inline` content — inline elements or text runs.
There it changes the accessible name, or the text a screen reader reads aloud where the element
has no name. Next to a block, a flex or grid item, an atomic inline (`inline-block`/`-flex`/
`-grid`) or an absolutely positioned element such as `sr-only`, the engine supplies the space
itself. Text, an `aria-hidden` element, then text, inside a block parent, also glues.

**Limits, stated because both bit.** A separator in a state no test renders was not measured, and
nor was any separator followed directly by text — the capture could not isolate one, because the
two merge when a DOM is re-parsed. Review found a site of the second kind: `MapBreadcrumb`'s
window label ("Tonightsunset"), which the rule predicts and this change now marks load-bearing.

**Three false beliefs, corrected where they were stated:**

- *"Load-bearing"* on separators the engines space anyway — between flex, grid or `inline-flex`
  items in `WindowControl` (twice, and its test), `MapBreadcrumb` and `WindowComingUpConditions`'
  cells, and beside an absolutely positioned `sr-only` span in `MapCallout`.
  `WindowControl`'s worry that breakpoint rules make these volatile was checked: three rules do
  hide or reposition parts of the control, but none changes `.wf-win-pill`'s own `display: flex`.
- *"Accname trims each element's contribution"* — true of jsdom's `dom-accessibility-api`, false of
  every browser: `<span>Plan </span><span>Map</span>` reads "Plan Map". Corrected in
  `WindowControl`, `MapCallout`, `MapBreadcrumb`, `MapLandingCard`, `LocationFourDaySheet`,
  `WindowRowFieldMap` and the handoff row.
- *"A pseudo-element is not in the accessibility tree"* (`MapCallout`, `MapBreadcrumb`) — generated
  content is in the name; even `content: " "` separates words.

`WindowFirstHeatStrip` held the inverse error — that name-from-contents over its flex and grid
spans would glue in a browser. Its visible spans carry no separators and read "SUNSET 21:11 Worth
it Spread Best nothing in reach" in all three engines. Its `sr-only` sentence is still right, and
its comment now gives the real reason: a day word, pauses, and no stray chart label. A test in
`jobRunSlotDatesAbroad` called jsdom's "🌅 Sunrise(past)" how the label "really reads"; a browser
reads "🌅 Sunrise (past)".

**Why the suite disagrees with browsers — corrected for the second time.** The polyfill applies the
browsers' own `display` rule, but reads `display` from jsdom, which has no layout engine: it never
blockifies a flex or grid item or an absolutely positioned element, so those read as `inline` even
with a stylesheet loaded, and this suite loads none (`css: false`). v2.20.4 said the polyfill
glues regardless of layout, which is wrong — and the difference is not simply the missing
stylesheet either, since loading one would not blockify a single flex item. v2.20.4 also said the
Coming up card blockifies everything that carries text — false whenever a tide chart renders.

**The load-bearing separators were the uncommented ones.** `WindowComingUpConditions`' name and
cadence ("Coastal tidesdeterministic"), the tide chart label ("5.2 m+1.9 vs avg"), `RegisterPage`'s
terms checkbox ("…Conditions andPrivacy Policy") and `MapBreadcrumb`'s window label carried no note,
while the warnings sat on inert separators — so the comments pointed the wrong way for anyone
deciding which ones matter. Each now says so, and `WindowFirstComingUpHandoff`'s class doc carries
the rule in full. No separator was removed.
