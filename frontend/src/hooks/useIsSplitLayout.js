import { useEffect, useState } from 'react';

const SPLIT_QUERY = '(min-width: 1000px)';

/**
 * Whether the Data tabs' list and panel sit side by side (1000px and up) rather than the panel overlaying the list.
 * The split and overlay layouts are CSS breakpoints; this is the same breakpoint in JS, for the one thing CSS
 * cannot do: make the list {@code inert} while an overlay or full-page panel is over it.
 *
 * @returns {boolean} True at 1000px and wider.
 */
export function useIsSplitLayout() {
  const [split, setSplit] = useState(() => (
    typeof window !== 'undefined' && window.matchMedia(SPLIT_QUERY).matches
  ));

  useEffect(() => {
    const mql = window.matchMedia(SPLIT_QUERY);
    const handler = (e) => setSplit(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  return split;
}
