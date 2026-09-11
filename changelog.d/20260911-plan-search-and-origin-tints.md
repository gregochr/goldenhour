### Fixed — the Plan search row's selected fill now takes the design's gold

Closes the last two values the plan-matrix pixel audit left open. One changed; one was a deliberate
departure the audit had misread; and one change the spec asks for is refused on measurement.

**Changed: the search row's selected fill.** `.wf-search-row.on` was a one-off
`rgba(230,180,90,.10)` matching no token. It is now the bundle's `rgba(201,162,75,.1)` — which is
`--color-home`, the app's "selection, yours" colour, and so the semantically right one for a
selected row.

**Refused: the spec's border on that row.** The bundle pairs the fill with a 1px border at
`rgba(201,162,75,.32)`, which measures **1.88:1** — under 1.4.11's 3:1, on the one channel a reader
has when the cursor moves without focus moving. The row keeps its 2px inset rule at **13.98:1**
instead. That rule reads `var(--color-plex-gold)`, and ⚠️ that token is **bone** (`#F2E7D3`), not
gold, despite its name.

⚠️ **Every figure above was first measured against the wrong ground and corrected by review.** They
were computed against `--color-plex-panel`; `.wf-search-panel` paints `--color-plex-surface`, and
that is what the row sits on. No conclusion flipped — the rule was 14.45:1 against the panel and is
13.98:1 on the real ground — but the wrong label had gone into both `index.css` and the spec. The
same review found a *pre-existing* claim two lines up, "a gold wash at 0.10 measures 2.6:1", that
reproduces on no ground here: 0.10 is ~1.2:1, and 2.6:1 is what a wash near 0.40 measures. It had
become visible only because the corrected figure now sits beneath it, so it is annotated rather
than left to argue with the new one.

**Not changed, and not drift: the origin chip's away tint.** The spec asks for `--tide`
(`rgba(111,168,176,.09)` / `.4`); the code uses `#8FB6D9` blue, and `index.css` has said why since
P7 — tide is this app's channel for an objective tide fact, and an origin names a place, not a
quality, so reusing it would make a planning control read as a tide claim. Re-measured on the
masthead's own ground at 8.54:1 for the text and 4.77:1 for the border. The pixel audit had counted
this as an unimplemented value because it searched for the literal instead of reading the rule.
The spec now records the departure and its reason.

Verified in real headless Chromium: the selected row computes to `rgba(201, 162, 75, 0.1)` with the
bone inset rule unchanged.
