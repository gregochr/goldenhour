import { useMemo, useSyncExternalStore } from 'react';

/**
 * The visual viewport — the part of the page the reader can actually see — as the Ask sheet needs it.
 *
 * <p><b>Why a sheet needs this and {@code 100dvh} is not enough.</b> On iOS Safari the on-screen
 * keyboard does not resize the layout viewport: {@code position: fixed; bottom: 0} stays glued to a
 * bottom edge that is now UNDER the keyboard, and {@code dvh} units do not change either. Only
 * {@code window.visualViewport} reports what is left. A sheet whose input sits at its foot therefore
 * sizes itself from {@code height} and lifts itself by {@code bottomInset}, so the input stays above
 * the keyboard (plan §2.6, F1b).
 *
 * <p>{@code bottomInset} is the gap between the bottom of the layout viewport and the bottom of the
 * visual one — {@code innerHeight - height - offsetTop} — clamped to zero: a pinch-zoomed or
 * over-scrolled page can report a negative figure that must never push a sheet off the screen.
 *
 * <p><b>Falls back to {@code innerHeight}</b> (inset zero) where {@code visualViewport} is absent —
 * jsdom, and any engine without the API — which is the layout viewport and the right answer for a
 * device with no on-screen keyboard to dodge.
 *
 * <p><b>Opt-in, and silent when off.</b> With {@code enabled} false nothing is subscribed (it reads
 * the layout viewport on each render and is never told of a change), so a component that calls this
 * unconditionally (a hook cannot be conditional) costs every caller that does not want it exactly
 * nothing: {@code BottomSheet} passes its own {@code size === 'tall'}, which is why every existing
 * sheet is unaffected.
 *
 * <p>A {@code useSyncExternalStore} over a string key of three rounded integers: the snapshot must
 * be a stable value between changes, and a string compares by value where an object would not.
 *
 * @param {boolean} [enabled=true] whether to follow the viewport
 * @returns {{height: number, bottomInset: number}} CSS pixels: the visible height, and how far the
 *          bottom of the visible area sits above the bottom of the layout viewport
 */
export default function useVisualViewportHeight(enabled = true) {
  const key = useSyncExternalStore(
    enabled ? subscribe : noSubscribe,
    enabled ? readKey : readLayoutKey,
    readLayoutKey,
  );
  return useMemo(() => {
    const [height, offsetTop, inner] = key.split('|').map(Number);
    return { height, bottomInset: Math.max(0, inner - height - offsetTop) };
  }, [key]);
}

/** The layout viewport alone — what a disabled hook, and an engine with no visualViewport, reports. */
function readLayoutKey() {
  const inner = typeof window === 'undefined' ? 0 : Math.round(window.innerHeight);
  return `${inner}|0|${inner}`;
}

/** `height|offsetTop|innerHeight`, each rounded: sub-pixel jitter must not re-render a sheet. */
function readKey() {
  if (typeof window === 'undefined' || !window.visualViewport) return readLayoutKey();
  const { height, offsetTop } = window.visualViewport;
  return `${Math.round(height)}|${Math.round(offsetTop)}|${Math.round(window.innerHeight)}`;
}

function noSubscribe() {
  return () => {};
}

/** `resize` for the keyboard and pinch-zoom, `scroll` for the page panning inside it. */
function subscribe(callback) {
  if (typeof window === 'undefined') return noSubscribe();
  const vv = window.visualViewport;
  window.addEventListener('resize', callback);
  vv?.addEventListener('resize', callback);
  vv?.addEventListener('scroll', callback);
  return () => {
    window.removeEventListener('resize', callback);
    vv?.removeEventListener('resize', callback);
    vv?.removeEventListener('scroll', callback);
  };
}
