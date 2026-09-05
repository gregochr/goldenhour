### Fixed — the highlighted cards had no highlight, and six accessibility defects

An adversarial accessibility review of the revamped landing site found seven issues.

**The two highlighted cards were losing their surface.** `background` is a shorthand, so
`.plat.live` ("Browser · Very close") and `.price.hi` ("Pro · recommended") reset
`background-color` to transparent and their 7%/6% brand wash fell onto the page ground
instead of onto `--card`. Measured, the Pro card's top edge rendered `rgb(35,30,20)`
against its plain sibling's `rgb(36,31,23)` — **one unit apart**, so the emphasis marking
the recommended tier was invisible. Naming `--card` as the final background layer restores
it to `rgb(49,41,28)`. This is the third instance of the same cascade family as the
masthead CTA and `.lab` defects.

**Data tables were keyboard-unreachable on a phone** (WCAG 2.1.1, Level A). At 375px a
600px table leaves 44.5% off-screen, and neither Chrome nor Safari makes an
`overflow-x:auto` div focusable — so the third column could not be reached at all, which
on `privacy.html` is the *Purpose* column, the substantive GDPR disclosure. Each scroll
container is now `tabindex="0"` with `role="region"` and a name, plus a focus ring, since
a thing that takes focus must show it.

Also: every page gains a `<main>` landmark (previously all content sat outside every
landmark, leaving a screen-reader user cycling landmarks with only banner and contentinfo);
`index.html`'s FAQ teaser questions drop to `<h3>` so they no longer rank equal to the
`<h2>` introducing them; `acknowledgements.html`'s Backend/Frontend/Infrastructure become
real `<h3>`s rather than styled `<div>`s, so 23 list items are no longer one undifferentiated
run; `terms.html`'s safety callout is a `role="note"` labelled by its own *Safety* heading;
`scroll-behavior:smooth` is guarded behind `prefers-reduced-motion` (the `#pricing` anchor
animates 5,631px, the canonical vestibular trigger); the home link regained hover feedback,
lost when `.mark .wm` (0,2,0) began out-specifying `a:hover` (0,1,1); A bare text node was also added between the
wordmark and kicker; that one is belt-and-braces rather than a fix, because `.mark` is
`display:flex` and every real engine already spaces the contributions of blockified
children — the run-together accessible name it guards against occurs only for genuinely
inline children, and in jsdom.

The review separately verified clean, and these are recorded so they are not re-litigated:
41 contrast pairs with none below threshold (tightest 4.86), no focus indicator removed
anywhere, both footer social links correctly labelled, and no further unintended specificity
collisions across a 143-rule sweep.
