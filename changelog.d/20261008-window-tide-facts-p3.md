### Changed — the Plan tab's pool and grid read tide from `window.tideFacts`

`buildWindowSpots` now copies each spot's `tideState`, `tideAligned` and `tideQuality` from the
window's served tide facts (by location id, then name) instead of from the slot; the spot population
still comes from slots, so every Plan card counts the same pool as before. `HeatmapGrid` builds the
tide index once and hands an `alignedOf(slot, date, targetType)` lookup to its cells and drill-down:
the cell's "N tide aligned" count, `computeCellTier(region, alignedOf)` and the drill-down's
`slotSortKey(slot, aligned)` / `sortedSlotsByTidePriority(slots, alignedOf)` ordering no longer read
`slot.tideAligned`. The lookup has no default, and a window or location with no fact is not aligned.
No visible change on a scored window. After this, no frontend code reads tide off a slot, which is
what lets the series stop serving it there.
