### Fixed — the Map tab keeps its coast, tide strip and tide cues on windows nothing scored

The Map tab's per-location tide index (`buildTideAlignmentIndex`) now reads `window.tideFacts`
instead of walking slots. A window the honesty filter blanked (T+3 except SETTLED, all of T+4, travel
days) has no slots but still has its facts, so coastal locations pass the rating floor through their
tide fact, the tide strip shows with its dimmed count, chips carry their tier and the callout and
four-day sheet show the tide block, on a coast-only, unrated map ("1 of N shown · 0 rated"). A payload
with no `tideFacts` (a pre-deploy cache) gives an empty index; there is no fallback to slots. The
index's `skyRating` and `gated` fields are joined from that window's slot when one exists.

A tide miss on a window the light was not assessed for now reads "Tide misses the light here"
instead of "Wrong water, not wrong light" (a match is unchanged).

### Changed — one window walk and one key helper per format on the client

`locationSheet.js` gains `windowsOf` and `indexByWindow`, and its slot, evaluation-gate, eclipse and
tide indexes are built over them with unchanged output. `mapEvents.solarWindowKey` and
`heatSpots.windowKey` (both `date:targetType`) are one `windowKey`, and `locationSheet.tailOf` and
`solarEventTimes.keyFor` (both `date|targetType`) are one `windowTail`, both in the new
`utils/windowKeys.js`. The two formats are deliberately not merged. A stale "the tide gate" comment
is corrected.
