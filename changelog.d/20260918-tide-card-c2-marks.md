### Added — two tide marks on the Plan tab window card (C2)

`WindowFirstHeatStrip.jsx`'s card gains the two marks `docs/engineering/tide-plan-card-plan.md`
§3 C2 specifies, both reusing C0/C1's served/derived data — nothing is re-scored, re-coloured, or
computed here beyond formatting.

A wave glyph (`components/map/TideWave.jsx`, no `shortfall`) sits before the best-reachable spot's
name whenever `card.bestReach.tideAligned === true` — the preference axis, never the timing phrase
"on the light". Its claim reaches a screen reader only through a new clause on
`bestReachLine`'s `spoken` string ("the tide is right here"), since the card's whole value grid is
`aria-hidden`; a miss or an inland spot draws and says nothing.

A `N on tide` topic chip is the first element of the topics line whenever `card.tideFit.live`,
carrying a tooltip naming the matched/coastal count (qualified "in reach" only when
`card.reachMeasured`), the served window tide's state and direction, and — only when more than one
window in the strip is live (`utils/windowFirstTideRun.js#tideRun`, C1) — a run-size clause and,
on the ranked best, `best of N` in a heavier, pilled emphasis on the same `--color-badge-tide` ink
(never a third tide hue). The chip's own accessible-name clause mirrors the served topic badges'
own construction.

CSS: four new rules beside the existing `.wf-hc-tw[data-channel="tide"]` block — `.wf-hc-tide`,
`.wf-hc-tide[data-best]`, `.wf-hc-tide-best`, `.wf-hc-best-tw` — all plain rgba tints of the
existing `--color-tide`/`--color-badge-tide` tokens. `.wf-hc-best-tw` carries `display:
inline-block`, found missing by adversarial review and matching the established `.wf-reg-tide`
precedent: without it, Tailwind's `svg { display: block }` preflight drops the glyph onto its own
line instead of sitting before the name.

Browser-verified against a live local stack (four seeded coastal locations, a served
`tideAlignmentQuality` pattern reproducing a live/live/miss/single-match run across four sunsets):
the glyph renders inline at 1280/834/390px; the emphasised chip measures 7.90:1 ink-on-pill and the
plain chip 10.01:1 against the card, both clearing AA; no chip's visible box exceeds its card's
edge at any of the three widths (a benign ~2px "logical" overflow of `.wf-hc-tps`'s own content box
— the pill's own literal `margin: -1px -2px` — never breaches the card, which has `overflow:
visible` and 6px of padding to spare); the accessible name carries both new clauses exactly as
specified and neither on a miss.
