import { useEffect, useState } from 'react';
import { useIsMobile } from './useIsMobile.js';

const TABLET_QUERY = '(max-width: 1023px)';

/**
 * Which of Ask PhotoCast's three width bands the viewport is in (plan §2.6's surface table):
 * {@code 'phone'} below 640px — exactly {@link useIsMobile}'s own query, so the two can never
 * disagree about where the phone ends — {@code 'tablet'} from 640 to 1023px, {@code 'desktop'} from
 * 1024px up.
 *
 * <p>One hook for the band rather than a query per component, so the bar, the field, the sheet and
 * (F2) the dock all read one answer. F1b acts on the first two bands; the third is F2's, and this
 * returns it now so F2 adds no second width test beside this one.
 *
 * <p>Needs {@code matchMedia}, as {@link useIsMobile} does (which this calls first): jsdom has none, and
 * `test/setup.js` supplies a default that matches nothing — the {@code 'desktop'} band, the one that
 * draws nothing.
 *
 * @returns {'phone'|'tablet'|'desktop'}
 */
export default function useAskSurface() {
  const phone = useIsMobile();
  const [tablet, setTablet] = useState(() => window.matchMedia(TABLET_QUERY).matches);
  useEffect(() => {
    const mql = window.matchMedia(TABLET_QUERY);
    const handler = (event) => setTablet(event.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);
  if (phone) return 'phone';
  return tablet ? 'tablet' : 'desktop';
}
