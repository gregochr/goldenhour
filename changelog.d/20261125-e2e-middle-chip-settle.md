### Fixed — E2E smoke: the middle-chip click no longer lands on bare map

Test infrastructure only. `clickMiddleChip` now waits for the Map label layer to settle, clicks the chosen chip by its accessible name instead of its index, and asserts the callout opened, so a repaint right after a window change can no longer redirect the click (the intermittent `roles.spec.js` "none of the LITE locks appear" failure).
