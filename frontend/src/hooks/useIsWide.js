import { useState, useEffect } from 'react';

const WIDE_QUERY = '(min-width: 1280px)';

/**
 * Detects whether the viewport is wide enough for the Operations sidebar (1280px and up, Tailwind's
 * xl breakpoint). Listens for resize and orientation changes via MediaQueryList.
 *
 * @returns {boolean} True when the viewport width is 1280px or wider.
 */
export function useIsWide() {
  const [isWide, setIsWide] = useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia(WIDE_QUERY).matches;
  });

  useEffect(() => {
    const mql = window.matchMedia(WIDE_QUERY);
    const handler = (e) => setIsWide(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  return isWide;
}
