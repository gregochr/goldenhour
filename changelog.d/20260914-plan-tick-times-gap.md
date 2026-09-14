### Fixed — the masthead's light-times gap is 16px on desktop again, and a test holds every tier

The plan-matrix prototype spaces the tick line's light times at 16px on desktop, 12px on iPad and
9px on a phone. The base `.wf-tick-times` rule had drifted to 12px — the iPad value — so desktop was
drawing iPad spacing. It is 16px again.

⚠️ **That was not a one-line change, and the reason is the useful part.** The base rule is the one
every width inherits unless overridden, and the iPad band had no rule of its own: it was right only
by inheriting the drifted 12px. Raising the base to 16px alone would have silently taken every iPad
up to the desktop gap. So the iPad block now states 12px explicitly, and the phone rule keeps its
9px — which already matched the prototype exactly, text size and tracking included.

**Measured in real headless Chromium against the compiled stylesheet:** 9px at 390px, 12px across
the whole iPad band (640, 767, 834, 1023), 16px from 1024 up. And the wider desktop gap moves
nothing else: with a deliberately long away label (`The Lake District · from Keswick`) the times
stay on the origin's line at every width from 834 up, before and after, and the pre-existing wrap at
390 and 640px is unchanged. ⚠️ The first same-line check compared the two groups' `top` edges and
read "wrapped" at every width including 1280 with 460px to spare — under `align-items: center` two
items of different heights on one line have different tops. It was replaced by a vertical-overlap
test, which reports a wrap where there genuinely is one and none where there is not.

**A cascade test now pins all three tiers** (`tickTimesGapCascade.test.jsx`). For each width it
keeps only the rules whose media condition holds there, injects them in source order, and reads what
the cascade resolves — both edges of every band, so a breakpoint moved by a pixel or an override
that stops winning fails the case that names it. Mutation-tested three ways, each caught: deleting
the iPad override (5 failures), the desktop base back at 12px (3), and **the iPad override moved
ahead of the base in source order (4)** — the exact way this drifted. ⚠️ It reads `gap`, not
`columnGap`: jsdom does not expand the `gap` shorthand, so a rule declaring `gap: 16px` reports
`columnGap: 'normal'` — measured, and a test reading it would see `'normal'` at every tier.

**Which prototype, stated.** Every design bundle carries a copy of the Plan-tab prototype (16/12/9),
but the tabs' own pages — `Map Tab v2.html`, `Map Landing.html`, `Coming Up.html` — specify 15px,
and the map pages an 8px phone gap. The masthead is one component on all four tabs
(`map-tab-v2-plan.md`: "a per-tab state of `MastheadTickLine`, not a fork"), so one value serves
every tab; 16px is the Plan tab's, 1px from the others' 15px where 12px was 3px away. An adversarial
review caught the first draft's comment calling it simply "the prototype" — the unqualified-bundle
claim this series has now made three times.

This also closes what #821's iPad entry recorded as "the tick-times gap is right for iPad; the
desktop drifted onto it", which is no longer true. Not seen in the populated masthead: it renders
only behind sign-in, which carries a Cloudflare Turnstile challenge that was not worked around.
