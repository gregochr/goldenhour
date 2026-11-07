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
 */
export default function useBackLayer({ open, onBack, mode = 'close' }) {
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
    const layer = registerBackLayer({ onBack: () => onBackRef.current?.(), mode: modeRef.current });
    layerRef.current = layer;
    return () => {
      layer.release();
      layerRef.current = null;
    };
  }, [open]);
}
