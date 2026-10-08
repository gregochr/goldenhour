### Refactor — Ask reads tide from the window's facts (window tide facts, P4)

`AskSnapshotBuilder` now joins each slot's `tideState`, `tideAligned` and `tideFitPhrase` from the summary's `window.tideFacts` (by location id, then name; the lookup is built once per window) instead of from `slot.tide()`, which it no longer reads. A slot with no fact reads as an inland one did before. No behaviour change while slots still carry tide; it makes Ask survive P5 stripping tide from API slots. See `docs/engineering/window-tide-facts-plan.md`.
