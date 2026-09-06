import { useEffect, useRef } from 'react';

/**
 * The map chrome's shared outside-click dismissal — one rule, SIX panels
 * (`docs/engineering/map-landing-plan.md` §3 L3/L5/L6, `docs/design/map-landing/README.md` §5).
 *
 * <p>⚠️ <b>It said "four" until L7's completeness sweep, in nine places in this file alone.</b>
 * L3 wrote the rule for the week menu, the Regions list, Filters and the Legend; L5 and L6 then
 * added the drilldown's two levels ({@code MapWindowPanel}, {@code MapRegionPanel}) as consumers
 * and left every count behind. The number is load-bearing in the argument below — "editing three
 * of the four" is the whole case for a shared hook — so a stale one weakens exactly the reasoning
 * a future caller is meant to read.
 *
 * <h2>Nothing closes because you looked at the map</h2>
 *
 * <p>The week menu, the Regions list, Filters and the Legend are all <em>about</em> the map, so
 * reading "Thursday is Poor" and then panning to see where is one action, not two — and losing the
 * list halfway through it made the panel feel like something to be got rid of. A press that lands
 * anywhere inside the map frame therefore dismisses nothing. They close on their own chip, on their
 * close control, or on `Escape`.
 *
 * <p>A press <b>outside</b> the frame still dismisses: the masthead, the tab bar, the page around
 * it. Nothing in the design asks otherwise, and a panel that survives navigating away from the map
 * entirely would be a different (worse) surprise.
 *
 * <p>⚠️ <b>Desktop and tablet only, and the phone genuinely behaves differently.</b> Filters and
 * the Regions list render as a {@code BottomSheet} at ≤639px and pass {@code enabled: false} here,
 * because that sheet is portalled outside {@code rootRef} and this listener would close it on the
 * first tap inside it. But its backdrop is {@code fixed inset-0} with {@code onClick={onClose}} and
 * it locks body scroll — so on a phone a tap on the map DOES dismiss those two, and the map cannot
 * be panned at all while one is open. That is the opposite of the rule above. It is pre-existing
 * (the sheet's own behaviour, unchanged by L3) and is recorded rather than fixed here, because
 * changing it means changing {@code BottomSheet}'s dismiss surface — see map-landing-plan.md §6 Q9.
 *
 * <p>⚠️ <b>Not every dismissible thing on this tab wants this rule.</b> The landing card L4 adds
 * must NOT use this hook: its spec is that it dismisses on its close control, Escape or a row
 * selection and on <em>nothing else</em> — not an outside tap either. Do not adopt this by pattern
 * match.
 *
 * <h2>⚠️ Why this is a shared hook rather than six edits</h2>
 *
 * <p>The rule reads like one change to one handler and is in fact seven call sites: these six
 * `document`-level listeners plus `MapView`'s own Leaflet ground-click controller. The six
 * listeners fire — and commit — <em>before</em> the `click` that controller answers, which is a
 * timeline `MapBackgroundClickController`'s class doc already records having been caught by, in the
 * browser rather than by any test. Editing the controller alone changes nothing observable, and
 * editing five of the six leaves a panel that behaves differently from its neighbours for no
 * reason a reader could infer. One hook makes the six incapable of drifting — which is what let
 * L5 and L6 adopt it in one line each and inherit the whole rule.
 *
 * <h2>The selector, not a ref</h2>
 *
 * <p>The frame is found by climbing from the event target rather than by threading a ref down from
 * `MapView` through six unrelated components, every one of which is rendered inside it.
 *
 * <p>⚠️ <b>This selector is NOT how the rest of the map finds its own frame</b>, and an earlier
 * draft of this comment claimed it was. `MapLabels.jsx` and `PinsLayer.jsx` reach the frame through
 * Leaflet — `map.getContainer().parentElement` — and their `OBSTACLE_SELECTOR` lists *chrome*
 * test-ids, never `map-container`. That route is unavailable here: these panels hold no map
 * instance, and threading one through six components to answer "was this press on the map" would
 * be worse. So the test-id is a deliberate second handle on the same node, not an existing idiom.
 *
 * <p>The trade is that renaming or dropping that attribute silently restores the old behaviour.
 * `MapViewBackgroundClick.test.jsx` guards it by asserting the node COUNT and the nesting rather
 * than taking `[0]` on trust — without that, its own `react-leaflet` mock supplies a second node
 * with the same id and a full revert reads as green. ⚠️ And "exactly one node in the app" is false:
 * `MapView` renders this frame on the Plan-tab overlay too, so two can coexist in the document. It
 * is harmless (the panels are tab-only), but do not write a selector that assumes uniqueness.
 */

/** The map frame. A press inside it dismisses nothing. */
export const MAP_FRAME_SELECTOR = '[data-testid="map-container"]';

/**
 * Whether a press on {@code target} should leave the map's panels open.
 *
 * <p>Exported so a caller (and a test) can ask the question without mounting a listener. Anything
 * that is not an {@link Element} — a press that reached `document` itself, which jsdom can produce —
 * is treated as outside the frame, because it certainly is not inside one.
 *
 * @param {EventTarget|null} target
 * @returns {boolean}
 */
export function isInsideMapFrame(target) {
  return target instanceof Element && Boolean(target.closest(MAP_FRAME_SELECTOR));
}

/**
 * Dismisses a map-chrome panel on a press outside it, unless that press was on the map.
 *
 * @param {object} args
 * @param {boolean} args.open whether the panel is open; no listener is mounted while it is not
 * @param {{current: ?Element}} args.rootRef the panel's own root — a press inside it never dismisses
 * @param {() => void} args.onDismiss called for a qualifying press
 * @param {boolean} [args.enabled] false to withhold the listener entirely. The phone surfaces pass
 *        `!isMobile`: there the panel is a `BottomSheet` portalled OUTSIDE `rootRef`, whose own
 *        backdrop is the dismiss surface, so this listener would close it on the first tap inside it
 */
export function useOutsideDismiss({ open, rootRef, onDismiss, enabled = true }) {
  // Held in a ref so a caller may pass a fresh arrow every render without re-subscribing, and
  // without the `exhaustive-deps` disable the call sites each carried separately.
  const onDismissRef = useRef(onDismiss);
  useEffect(() => { onDismissRef.current = onDismiss; }, [onDismiss]);

  useEffect(() => {
    if (!open || !enabled) return undefined;
    function onDocMouseDown(e) {
      if (!rootRef.current || rootRef.current.contains(e.target)) return;
      if (isInsideMapFrame(e.target)) return;
      onDismissRef.current();
    }
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [open, enabled, rootRef]);
}
