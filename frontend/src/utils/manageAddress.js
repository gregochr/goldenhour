/**
 * Takes the Operations address (`#manage/...`) off the page once the reader has left Operations.
 *
 * <p>Operations writes `#manage/<tab>` on every sub-tab press and never clears it, so the address outlived the tab: an
 * admin who went Operations, then This week, then pressed a card saw the map view open under
 * `#manage/runs?view=cost`. The dialog-history engine writes a layer's own address only over an empty hash or its own
 * `#map`, so the view's `#map/...` address was never written. Called by `App` on the move off Operations.
 *
 * <p>Replaces the current entry in place (no new entry, no `hashchange`), copying `history.state` so the engine's
 * depth marker survives. Anything that is not an Operations address is left alone.
 *
 * @returns {boolean} whether an address was removed
 */
export function clearStaleManageAddress() {
  try {
    const { location, history } = window;
    if (!location.hash.startsWith('#manage')) return false;
    history.replaceState(history.state, '', `${location.pathname}${location.search}`);
    return true;
  } catch {
    // The address is a nicety; nothing else depends on it being cleared.
    return false;
  }
}
