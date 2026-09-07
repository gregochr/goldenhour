/**
 * Whether a dialog from OUTSIDE the map pane is currently over it — the four-day sheet the callout
 * opens, `UserSettingsModal`, a search overlay.
 *
 * <p>⚠️ **One predicate, because the Escape rules on this tab must never disagree.** There are four
 * of them now: `MapView`'s pane-level `handleMapPaneKeyDown`, the landing card's own document
 * listener, and the drilldown's two panels. This lived in `MapView`'s module scope while only the
 * first two consulted it, and the two that did not read it were the two that got it wrong — one
 * `Escape` reaching a panel operated it *behind* an open sheet (`map-landing-plan.md` §4 #37,
 * `map-tab-v2-plan.md` O-20). It moved here rather than being exported from `MapView`, which the
 * panels cannot import without a cycle.
 *
 * <p><b>Containment, not "is any modal open"</b>: a dialog the pane renders INLINE is its own
 * business. Today nothing inside the pane carries `aria-modal` at all — `FiltersPopover`, the
 * landing card and both drilldown panels each say in their own docs that they deliberately do not —
 * so the distinction is defensive rather than load-bearing, and it is kept because the day one of
 * them gains the attribute is not the day to rediscover this rule.
 */

/** The map TAB's own root — the node `MapView`'s `mapPaneRef` points at. Never the overlay. */
export const MAP_PANE_SELECTOR = '.wf-map-tab';

/**
 * @param {?Element} paneRoot the map pane's root node
 * @returns {boolean} true when a modal dialog outside that root is open
 */
export function foreignModalOver(paneRoot) {
  return Array.from(document.querySelectorAll('[role="dialog"][aria-modal="true"]'))
    .some((node) => !paneRoot || !paneRoot.contains(node));
}

/**
 * The same question asked from INSIDE the pane, for a component that has its own root but no
 * reference to the pane's.
 *
 * <p>⚠️ **This exists so there is no prop to forget.** The first cut of the §4 #37 fix threaded a
 * required `foreignModalOver` prop from `MapView` to both panels. Mutation testing killed it: with
 * either mount unwired, `foreignModalOver()` threw inside the event handler, the handler died
 * before acting, and the panel stayed open — so every wiring test still passed, for the wrong
 * reason. Vitest's own "unhandled errors … might cause false positive tests" was the tell. A
 * component that resolves its own pane has no such mode.
 *
 * <p>Falls back to standing down when the pane cannot be found: not locating your own container is
 * not evidence that nothing is over you.
 *
 * @param {?Element} node any node inside the map pane
 * @returns {boolean} true when a modal dialog outside the pane is open
 */
export function foreignModalOverPaneOf(node) {
  return foreignModalOver(node?.closest?.(MAP_PANE_SELECTOR) ?? null);
}
