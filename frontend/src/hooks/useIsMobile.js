import { useState, useEffect } from 'react';

const MOBILE_QUERY = '(max-width: 639px)';

/**
 * Detects whether the viewport is mobile-sized (≤639px, below Tailwind's sm breakpoint).
 * Listens for resize and orientation changes via MediaQueryList.
 *
 * @returns {boolean} True when the viewport width is 639px or narrower.
 */
export function useIsMobile() {
  const [isMobile, setIsMobile] = useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia(MOBILE_QUERY).matches;
  });

  useEffect(() => {
    const mql = window.matchMedia(MOBILE_QUERY);
    const handler = (e) => setIsMobile(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  return isMobile;
}
