import { createContext, useContext } from 'react';

/**
 * What the map view (`components/MapScreen.jsx`) offers the pane rendered inside it
 * (`docs/engineering/one-screen-plan.md` D1, D12).
 *
 * <p>Null outside the view: a `MapView` rendered on its own (every existing test) draws exactly what it always did.
 * Inside it the value is `{windowSlot, publishHeader, publishRoute, publishEscape}`:
 * <ul>
 *   <li>{@code windowSlot}, the header's DOM node, into which `MapView` portals its ONE `WindowControl`;</li>
 *   <li>{@code publishHeader({title, verdictLabel, bestRating, confidence, light})}, the served fields the header names
 *       ({@code light} is {@code {place, windows: [{label, range}]}}, the window's blue and golden hour at the place on
 *       screen, or null);</li>
 *   <li>{@code publishRoute({date, eventType, locationId})}, which the shell turns into the layer's `#map/...` url;</li>
 *   <li>{@code publishEscape(close)}, the pane's Escape ladder: closes its innermost layer and answers whether it
 *       closed one, so an Escape pressed on the view's own header closes the callout before it closes the view.</li>
 *   <li>{@code publishStep(step)}, the pane's window stepper ({@code step(-1|1)}), for the arrow keys on the header;</li>
 *   <li>{@code focusTitle()}, which moves focus to the view's title (a control that unmounts under focus calls it).</li>
 * </ul>
 */
export const MapScreenContext = createContext(null);

/** @returns {?object} the view's offer, or null outside the view */
export function useMapScreen() {
  return useContext(MapScreenContext);
}
