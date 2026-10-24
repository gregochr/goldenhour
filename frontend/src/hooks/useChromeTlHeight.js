import { useCallback, useRef, useState } from 'react';

/**
 * The CSS custom property this hook keeps current: the height of the window-control group
 * ({@code .wf-map-chrome-tl}), set on the group's parent (the map frame), where the frame-level
 * blocks that hang under the control ({@code .wf-win-panel}, {@code .wf-land}, the phone legend
 * toast) and Ask's camera ({@code askCamera.chromeHeightOf}) can read it. Their offsets fall back
 * to the 36/40px control constants when it is unset.
 */
export const CHROME_TL_HEIGHT_VAR = '--wf-chrome-tl-h';

/**
 * Publishes the top-left chrome group's height as {@link CHROME_TL_HEIGHT_VAR} on its parent
 * (hot-topics-by-day-plan §1.12), and returns a nonce that moves whenever that height changes.
 *
 * <p>It moved here from {@code BigSeasLine}, where it ran only while the big-seas line showed. The
 * topic chips row grows the group on EVERY day, so the variable must be current on every day: an
 * observer that stood down on a day with no big seas left the landing card, the window panel, the
 * phone toast and Ask's fit sitting over the chips.
 *
 * <p>The nonce is the label layers' repaint key ({@code MapLabels}'s
 * {@code chromeKey}): they repaint only on map move, zoom and resize or on a prop change, and read
 * the group's rect as an obstacle when they place, so a change in the group's height has to reach
 * them as a prop. Driving it from the observer (rather than from a hand-built key over what the
 * group contains) means ANY cause of a height change repaints, with no list of causes to keep in
 * step. It does not move for the first publish (the layers place after mount anyway) or for a
 * resize that leaves the height as it was.
 *
 * <p>Returns {@code [ref, nonce]}: a CALLBACK ref to attach to the group, not an effect over a ref
 * object, because the group is mounted after {@code MapView}'s early return, so an effect keyed on
 * mount would run while the ref was still null and never again. The callback runs when the node
 * attaches (the first measure lands before paint) and its returned cleanup disconnects the observer
 * and removes the variable.
 *
 * <p>The hidden-pane rule is kept: a pane under {@code display: none} measures 0, and publishing a
 * height of zero would be wrong, so nothing is published (the old constants stand) until the pane
 * is visible and the observer fires again.
 *
 * @returns {[function(?Element): (void|function()), number]} the ref callback for
 *          {@code .wf-map-chrome-tl}, and the repaint nonce
 */
export function useChromeTlHeight() {
  return usePublishedHeight(CHROME_TL_HEIGHT_VAR);
}

/**
 * The shared mechanism behind {@link useChromeTlHeight} and {@code useChromeBlHeight}: a callback
 * ref that publishes the node's height as the custom property {@code varName} on its parent and
 * returns a nonce that moves whenever that height does. See {@link useChromeTlHeight} for the rules
 * (no publish at 0, no nonce for the first publish, cleanup removes the variable).
 *
 * @param {string} varName the custom property to publish, e.g. {@code --wf-chrome-tl-h}
 * @returns {[function(?Element): (void|function()), number]} the ref callback, and the repaint nonce
 */
export function usePublishedHeight(varName) {
  const [nonce, setNonce] = useState(0);
  const lastRef = useRef(undefined);
  const ref = useCallback((chrome) => {
    const frame = chrome?.parentElement;
    if (!chrome || !frame) return undefined;
    lastRef.current = undefined;
    const publish = () => {
      const h = chrome.offsetHeight;
      if (h > 0) frame.style.setProperty(varName, `${h}px`);
      else frame.style.removeProperty(varName);
      const published = h > 0 ? h : null;
      if (lastRef.current !== undefined && lastRef.current !== published) setNonce((n) => n + 1);
      lastRef.current = published;
    };
    publish();
    let observer = null;
    if (typeof ResizeObserver === 'function') {
      observer = new ResizeObserver(publish);
      observer.observe(chrome);
    }
    return () => {
      observer?.disconnect();
      frame.style.removeProperty(varName);
    };
  }, [varName]);
  return [ref, nonce];
}
