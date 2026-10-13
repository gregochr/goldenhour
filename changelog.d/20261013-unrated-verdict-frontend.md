### Changed — "Too few rated" replaces a weather-only "Worth it" on every Plan and Map surface

The client now speaks the server's new `UNRATED` display verdict
(`docs/engineering/unrated-verdict-plan.md`, U2). At window and region level the word is **"Too few
rated"** — the Plan card, the popup badge and region rail, the Map's window pill, landing card,
drilldown rows and peek sheet, the regional-planner grid cell and its pill; at slot level (an Ask
pick) it is **"Not rated"**. It takes the neutral treatment a window with nothing to say already
wore: no verdict tint or bloom, grid tier 5, no ◎ before the word, no confidence decay or provisional
mark (the word already says it), and the Map's all-Poor fold counts `STAND_DOWN` only, so "neither is
worth the drive" is never said over a window nothing has called. The card prints the region's mean
beside the new word — "Too few rated 3.6★" is the honest card, since the gate withheld a verdict from
exactly those ratings — and stays silent beside a triage "Poor". Because "Too few rated" is 85.8px
and the mean 24px against a ~88px value column at 320px, the mean wraps onto its own line under the
word there (a CSS rule keyed on the row's verdict, measured in Chromium against the built stylesheet:
the row wraps below a 113.8px column and is unchanged above it; a "Worth it" row is untouched). The
map drilldown's note gets its own sentence for the state, regions sort an UNRATED region above a
stood-down one, and an UNRATED place stays in the drill-down's main list rather than under "poor".
Not measured in a browser: the whole Map window pill at 320px, where the new word is wider than any
before it and will ellipsise ("TOO FEW…") rather than push the day label.
