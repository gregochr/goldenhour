/**
 * The Map tab's foreign-modal predicate — "is a dialog from OUTSIDE this pane currently over it":
 * the four-day sheet the callout opens, `UserSettingsModal`, a search overlay.
 *
 * <p>⚠️ **One predicate, because the dismissal rules on this tab must never disagree.** There are
 * **eight** Escape rules across six components — `MapView`'s pane-level `handleMapPaneKeyDown` and
 * the landing card's document listener, the drilldown's two panels, and `WindowControl`,
 * `FiltersPopover`, `RegionsJump` and `MapLegendPanel` — plus the POINTER channel in
 * `useOutsideDismiss`. All of them read this.
 *
 * <p>⚠️ **The first cut of this fix said "four" and corrected only the two panels.** Two review
 * lenses counted eight independently, and one pointed out that `WindowControl` is on the very route
 * by which the panels are reachable behind a sheet: a keyboard reader gets to the drilldown by
 * Tabbing out of the non-trapping sheet onto the pill, so the pill's own dropdown answered the same
 * press first. Fixing two of eight would have left the defect live one control earlier
 * (`map-landing-plan.md` §4 #37, `map-tab-v2-plan.md` O-20).
 *
 * <p>It lives here rather than in `MapView`'s module scope, where it began: the components that
 * need it cannot import from `MapView` without a cycle.
 *
 * <p><b>Containment, not "is any modal open"</b>: a dialog the pane renders INLINE is its own
 * business. Today nothing inside the pane carries `aria-modal` at all — verified by grep, not by
 * citation: only `Modal`, `BottomSheet` (which omits it under `modal={false}`) and `MapOverlay` ever
 * emit it, and the pane renders none of them inline. ⚠️ An earlier wording cited "both drilldown
 * panels' own docs" as saying so; they say no such thing, and a review lens caught it — it is
 * `map-tab-v2-plan.md` O-20 that records the choice. The distinction is defensive rather than
 * load-bearing today, kept because the day one of them gains the attribute is not the day to
 * rediscover this rule, and pinned by `mapForeignModal.test.js` so it cannot be deleted as dead.
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
