### Fixed — the masthead no longer jumps 24px when switching to or from the Map tab

On the Map tab `<main>` dropped all of its vertical padding, so the wordmark, the gradient band, the tick line and the tab row sat 24px higher there than on Plan and Coming up, and moved every time you switched. The Map tab now keeps the same 24px top padding as every other tab; the map pane gives up that 24px from its own flex-distributed height (the root is `overflow-hidden`, so page scroll is not reopened), and its bottom padding stays zero so the map still bleeds to the bottom edge.
