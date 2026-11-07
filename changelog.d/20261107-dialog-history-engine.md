### Added — a history engine so Back can close dialogs (no consumers yet)

`utils/dialogHistory.js` is the store behind the plan in `docs/engineering/back-closes-dialogs-plan.md`: a
module singleton that gives a dialog one browser-history entry (marker `{session, depth}`, pushed only under user
activation, otherwise the layer is entry-less), closes the top layers on Back, bounces Forward, absorbs Back on a
blocking layer, traverses at most one `go` at a time and only when the top entry is ours, and sets
`history.scrollRestoration` to `'manual'` only while an entry of ours is open. `hooks/useBackLayer.js` and
`registerBackLayer` are the entry points, and `Modal` and `BottomSheet` take an opt-in
`back="close" | "cancel" | "block"` prop. Nothing passes `back` yet, so every dialog behaves as before (zero
history calls). A single constant, `BACK_CLOSES_DIALOGS_ENABLED`, turns the whole engine off.

`BottomSheet`'s body scroll lock is now reference-counted, so two sheets closing in one commit no longer leave
the page `overflow: hidden`.
