### Fixed — the Plan arm's gutter had been the old bundle's 18px since window-first

The pixel half of the plan-matrix spec audit (the behavioural half was #804). `--wf-gutter` was
18px; the plan-matrix bundle asks for 22px, and so do the four bundles after it. Counted across
`docs/design/` by gutter-SHAPED padding rather than raw occurrence: no `<n>px 22px` padding appears
anywhere in window-first, and no `<n>px 18px` one appears in plan-matrix, field-geography,
coming-up, matrix-axis or map-tab-v2. So 22 is not one bundle's preference against this arm's habit
— it is the design's current gutter, and the arm was never moved when the design was.

⚠️ Quote that claim in its gutter-shaped form only. An adversarial fact-check killed the raw-count
version this entry first carried ("18px seventeen times, 22px never"): window-first does contain
`22px` — in a margin, a gap and a button height — and a naive substring count also scores `318px`
as a hit. The qualitative claim survived scrutiny; the tidy numbers did not.

Changed with it, all from the same spec: the masthead's vertical padding 16px → 14px, the body's
14px → 13px, and the lens bar's 11px → 10px. The lens bar also carried a hard-coded `18px` while
its own phone override already read `var(--wf-gutter)`, so the desktop bar and the rest of the row
were one edit from disagreeing — the exact "move half the chrome and leave the rest" failure the
token's declaration warns about, already half-happened. It now reads the token. The Plan lens bar's
active segment goes from `rgba(201,162,75,.18)` to the bundle's `.15`; the Map tab's filter chips
keep `.18`, because they answer to `map-tab-v2`, not to this spec.

⚠️ **The phone is untouched.** The 639px media query sets the token to 14px, which every bundle
still agrees on, and that path was measured unchanged.

**Verified in a browser, because `css: false` means the suite cannot see any of this.** Computed at
1280×860: gutter 22px, masthead `14px 22px 0px`, lens `10px 22px` (sticky, `z-index: 20`), body
`13px 22px 20px`, active segment `rgba(201, 162, 75, 0.15)`. Zero horizontal overflow at 1440, 1280,
1100, 900, 760 and 390. At 390 the gutter falls back to 14px as designed. `--wf-lens-reserve` (58px)
still clears the bar, and with more slack than before — the bar lost 2px, so the margin went from
4.5px to 6.5px.

**One test was passing for the wrong reason and is fixed here too.**
`mapFullFrameCascade.test.jsx` substitutes `var(--wf-gutter)` for a literal before handing the real
rules to jsdom (whose `cssstyle` cannot parse a `var()` inside a shorthand `padding` at all). That
literal was hard-coded `'18px'` and documented as "`.wf-shell`'s own declaration" — so once the
declaration moved, the test went on proving its specificity contest against a value the real
cascade can no longer produce, and stayed green because it is internally self-consistent. It now
reads the declared value out of the same stylesheet, so the substitution tracks the stylesheet
instead of a frozen copy and the comment is true again.

⚠️ **That fix is narrower than it first looked, and mutation testing is what said so.** Forcing the
derivation back to the stale `'18px'` still passes 4 of 4: the substituted value and the assertions
both come from the one constant, so they move together by construction. The test does not pin the
gutter and never did — its subject is the specificity contest, which is value-agnostic on purpose.
The comment now says that outright, so the next reader does not mistake it for a guard on the
number. A real guard would need its own assertion against `.wf-shell`.

⚠️ **What the browser could NOT show**: the local H2 database has no evaluation data, so no matrix
cards, popup or location sheet rendered. The chrome this change touches was verified; the surfaces
it sits around were not.
