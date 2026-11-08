import { useEffect, useRef } from 'react';
import { registerBackLayer } from '../utils/dialogHistory.js';

/**
 * Registers a dialog with the Back-closes-dialogs engine while `open` is true.
 *
 * <p>Registration follows `open` (mount / unmount for a component that is only rendered while open),
 * never `onClose`, so a data-driven close, a tab switch that closes two layers and a remount all
 * unregister the same way. `onBack` and `mode` are kept in a ref and pushed into the live registration,
 * so changing them never unregisters the layer or touches the history.
 *
 * <p>See `utils/dialogHistory.js` and `docs/engineering/back-closes-dialogs-plan.md`. A class
 * component uses {@link registerBackLayer} directly.
 *
 * @param {object} options
 * @param {boolean} options.open - Whether the dialog is on screen.
 * @param {Function} [options.onBack] - What Back does for this layer (normally its close or cancel).
 * @param {'close'|'cancel'|'block'} [options.mode] - `block` absorbs Back and closes nothing.
 * @param {boolean} [options.activationGated] - First-run prompts: while `open`, register only once the page has
 *     a live user activation (design item 10, back-closes-dialogs-plan.md). Without one Chromium would mark the
 *     entry skippable and Back would leave the app anyway, so nothing is pushed. If `navigator.userActivation`
 *     is live at open the layer registers at once; otherwise a capture-phase `click` / `keyup` listener (never
 *     `pointerdown`, which is not activation-triggering on touch) registers it on the first such gesture that
 *     finds `isActive` true, and then removes itself. Closing first removes the listener and pushes nothing.
 *     No `beforeunload`. On a browser with no `navigator.userActivation` at all the gesture registers the layer, but
 *     the engine's own push also needs `isActive === true`, so the layer has NO history entry: no guard, and Back
 *     behaves as it did before this feature. That is safe.
 * @param {{current: (Element|null)}} [options.ref] - With `activationGated`, the element the gesture listener
 *     attaches to (the prompt's own node); `document` when omitted or not yet mounted.
 */
export default function useBackLayer({
  open, onBack, mode = 'close', activationGated = false, ref = undefined,
}) {
  const onBackRef = useRef(onBack);
  const modeRef = useRef(mode);
  const layerRef = useRef(null);

  // No deps: runs every commit, before the registration effect below on a first mount.
  useEffect(() => {
    onBackRef.current = onBack;
    modeRef.current = mode;
    layerRef.current?.update({ mode });
  });

  useEffect(() => {
    if (!open) return undefined;
    let layer = null;
    const register = () => {
      layer = registerBackLayer({ onBack: () => onBackRef.current?.(), mode: modeRef.current });
      layerRef.current = layer;
    };
    let detach = () => {};
    if (!activationGated || hasLiveActivation(true)) {
      register();
    } else {
      const target = ref?.current ?? document;
      const onGesture = () => {
        if (!hasLiveActivation(false)) return;
        detach();
        register();
      };
      detach = () => {
        target.removeEventListener('click', onGesture, true);
        target.removeEventListener('keyup', onGesture, true);
        detach = () => {};
      };
      target.addEventListener('click', onGesture, true);
      target.addEventListener('keyup', onGesture, true);
    }
    return () => {
      detach();
      layer?.release();
      layerRef.current = null;
    };
    // `ref` is a stable ref object and `activationGated` never changes for a mounted prompt.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
}

/**
 * Whether the page has a live user activation. With no `navigator.userActivation` API the answer is false at
 * open (nothing proves a gesture yet) and true on a gesture (the event is itself the activation); the engine
 * then declines to push, so the layer is registered without an entry.
 */
function hasLiveActivation(atOpen) {
  const activation = globalThis.navigator?.userActivation;
  if (!activation) return !atOpen;
  return activation.isActive === true;
}
