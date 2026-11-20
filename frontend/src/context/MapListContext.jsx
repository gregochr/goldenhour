import { createContext, useContext } from 'react';

/**
 * The one door from the Map pane to the shell's ranked spot list (glance-to-map-plan.md G1).
 *
 * <p>The list is the shell's own {@code Modal} ({@code WindowSpotSheet}, held there by {@code sheetKey}), not a
 * child of the Map, so it is a dialog OUTSIDE {@code .wf-map-tab} and every Map Escape and outside-press rule stands
 * down for it through {@code mapForeignModal.foreignModalOver}. The Map pane is a node {@code App} hands the shell, so
 * the shell cannot pass it props; it provides this value around the pane instead. The default is null: with no shell
 * (a standalone {@code MapView}) the Map draws no List control at all.
 *
 * <p>The value is {@code {openList}}, where {@code openList(request)} takes {@code {key, spotKeys, floorId, tierId,
 * onPick}}: the Map's selected window key, the set of {@code heatSpotKey}s the Map holds in scope (so the list's
 * counts match the Map's), the Map's own rating floor and drive tier ids, and the callback that selects a place on
 * the Map in place. It returns true when the list opened and false when the shell refused it (another layer stands).
 *
 * @type {import('react').Context<?{openList: function(object): boolean}>}
 */
export const MapListContext = createContext(null);

/**
 * @returns {?{openList: function(object): boolean}} the shell's list door, or null outside the shell
 */
export function useMapList() {
  return useContext(MapListContext);
}
