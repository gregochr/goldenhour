### Fixed — the "Last updated" label rendered as a headline, and FAQ answers changed colour by page

Two more cascade defects of the kind that produced the masthead CTA one.

`.phead p` (specificity 0,1,1) out-specified the `.lab` component (0,1,0), so the quiet
metadata line on `privacy.html` and `terms.html` kept `.lab`'s mono family and .16em
tracking while taking the container's 17px and `--ink-soft`. *Last updated: September
2026* was rendering at 17px tracked out to 2.72px — a headline where a caption was
intended. Scoping the container rule `:not(.lab)`, the same idiom the masthead fix used,
restores 11.5px at 1.84px in `--ink-dim`.

`.qa` is mounted inside `.doc` on `faq.html` but outside it on `index.html`'s teaser, and
took its paragraph colour from `.doc p`. The same component therefore rendered
`--ink-soft` on one page and `--ink` on the other; it now states its own colour.
