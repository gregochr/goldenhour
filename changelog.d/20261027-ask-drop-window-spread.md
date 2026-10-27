### Removed — Ask answer card no longer draws the per-window spread lines

The mono lines under an Ask answer's summary (`Thu sunrise · 154 rated across every region · 88 at
4★+`) repeated the summary and pushed the picks down on a phone, so the owner asked for them gone.
`AskWindowSpread`, `askSpread.js` and their styles and tests are deleted, and the client no longer
keeps `windows` on a normalised answer. The server still serves `windows`, and `AskReadyFreshness`
still uses it.
