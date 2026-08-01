import { useState, useEffect } from 'react';

const COARSE_QUERY = '(pointer: coarse)';

/**
 * True when the primary input is a coarse pointer — a finger or a stylus rather than a mouse.
 *
 * <p>Companion to {@link useIsMobile}, not a replacement for it: that hook asks how *wide* the
 * viewport is, and this one asks what is *pointing at it*. A hover-only affordance needs both
 * answers, because the two disagree on exactly the devices that break it — a touchscreen laptop or
 * a landscape tablet is far wider than the mobile breakpoint, yet a "hover" there is a tap that has
 * already committed to something.
 *
 * <p>Deliberately phrased as "is coarse" rather than "can hover": an environment that does not
 * implement the media query at all (JSDOM, older engines) reports no match, which this hook reads
 * as *not coarse* and therefore leaves hover behaviour switched on. The inverse phrasing would
 * silently disable it everywhere the query is unknown.
 *
 * @returns {boolean} true when the pointer is coarse
 */
export function useIsCoarsePointer() {
  const [coarse, setCoarse] = useState(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return false;
    return window.matchMedia(COARSE_QUERY).matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return undefined;
    const mql = window.matchMedia(COARSE_QUERY);
    const handler = (e) => setCoarse(e.matches);
    mql.addEventListener?.('change', handler);
    return () => mql.removeEventListener?.('change', handler);
  }, []);

  return coarse;
}

export default useIsCoarsePointer;
