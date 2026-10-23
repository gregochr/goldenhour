import { usePublishedHeight } from './useChromeTlHeight.js';

/**
 * The CSS custom property this hook keeps current: the height of the bottom-left chrome stack
 * ({@code .wf-map-chrome-bl}: legend chip, viewline upsell, the four-day strip and the tide strip),
 * set on the stack's parent (the map frame), where the bottom-centre counts footer can read it
 * (hot-topics-by-day-plan §3 M2, integration point 2).
 */
export const CHROME_BL_HEIGHT_VAR = '--wf-chrome-bl-h';

/**
 * Publishes the bottom-left chrome stack's height as {@link CHROME_BL_HEIGHT_VAR} and returns a
 * nonce that moves whenever it changes: the same pattern as {@code useChromeTlHeight}, whose
 * mechanism it shares. The nonce is the label layers' repaint key (the stack is an obstacle they
 * read when they place), so a strip that opens, collapses or changes height reaches them as a prop.
 *
 * <p>Returns {@code [ref, nonce]}: a CALLBACK ref for {@code .wf-map-chrome-bl}, which mounts and
 * unmounts with its conditions, so the variable is removed the moment the stack is gone.
 *
 * @returns {[function(?Element): (void|function()), number]}
 */
export function useChromeBlHeight() {
  return usePublishedHeight(CHROME_BL_HEIGHT_VAR);
}
