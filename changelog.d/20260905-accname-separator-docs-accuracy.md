### Fixed — two components claimed an accessibility defect that no browser has

`WindowFirstComingUpHandoff` and `WindowComingUpEntry` each documented their bare `{' '}` text-node
separators as fixing a real, user-facing run-together accessible name — the handoff row's doc going
as far as "it bit this row for real until a screen-reader-name test caught it". No browser
measurement supports that, and the claim had already begun to spread: it was cited as precedent for
adding the same separators to a third component, where they would likewise have done nothing.

Every engine inserts a space between **block-level** accessible-name contributions, and a flex or
grid item is blockified. Both components blockify everything that carries text — `.wf-cu-handoff`,
`.wf-cu-handoff-summary`, `.wf-cu-ttl` and `.wf-facts` are `flex`; `.wf-facts > span` and
`.wf-cu-coin-line` are `inline-flex`; `.wf-cu-prose` is `block` — so there is no genuinely inline
sibling pair in either one.

Measured by removing each separator individually from the components' real rendered DOM, against
the real stylesheet: **5 sites in the handoff row and 17 in the entry card, none load-bearing, in
Chromium, WebKit and Firefox.** Each run carried a planted inline pair in the same DOM as a
positive control, and Chromium's native accessibility tree (CDP) was used to confirm the result
independently of Playwright's own name computation.

⚠️ `inline-flex` is not the exception it looks like — it is inline-*level*, but its contents are
blockified and the engines space it like a block. The run-together defect needs a genuinely inline
box with inline content.

**The separators stay.** They cost nothing, they state the intent in the DOM rather than leaving it
to CSS, and they become load-bearing the moment one of those containers stops being flex. Only the
claims about them changed.

The instrument is the real lesson, and it is now named at each site: jsdom's
`dom-accessibility-api` — what Vitest and Testing Library's `name` option compute with — glues
*any* adjacent elements, so a test asserting a spaced name is asserting the polyfill's rule rather
than a browser's. The test that pins the handoff row now says so, and says that a failure there
means "the separators went away", not "accessibility broke in production".
