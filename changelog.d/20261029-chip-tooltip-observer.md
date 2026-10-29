### Changed — the map chip tooltip and the modal watcher stop doing work on every pointer move and tile

The map's chip hover tooltip is now positioned through a ref and a CSS transform, one write per animation frame, so moving the mouse over the map no longer re-renders the whole label layer. The Map tab's dialog watcher still sees every dialog open, close and stack, but ignores Leaflet tile and pane changes instead of re-querying the document for each one. On the first hover the tooltip now clamps against its real width rather than a 240 px estimate.
