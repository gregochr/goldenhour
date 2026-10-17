import { useEffect, useState } from 'react';
import { useIsMobile } from './useIsMobile.js';

const TABLET_QUERY = '(max-width: 1023px)';
const WIDE_QUERY = '(min-width: 1180px)';

/** Whether {@code query} holds now, kept current as the viewport crosses it. */
function useMatches(query) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mql = window.matchMedia(query);
    const handler = (event) => setMatches(event.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, [query]);
  return matches;
}

/**
 * Which of Ask PhotoCast's four width bands the viewport is in (plan §2.6's surface table):
 * {@code 'phone'} below 640px — exactly {@link useIsMobile}'s own query, so the two can never
 * disagree about where the phone ends — {@code 'tablet'} from 640 to 1023px, {@code 'desktop'} from
 * 1024 to 1179px (a 360px dock beside a 260px field) and {@code 'wide'} from 1180px (a 380px dock
 * beside a 340px field with the {@code /} key hint).
 *
 * <p>One hook for the band rather than a query per component, so the bar, the field, the sheet and
 * the dock all read one answer. The first two bands draw the phone's bar and the sheet-opening field
 * and are unchanged by F2; the last two are the docked column's, and what separates them is only the
 * widths — the dock is the same surface from 1024px up.
 *
 * <p>Needs {@code matchMedia}, as {@link useIsMobile} does (which this calls first): jsdom has none, and
 * `test/setup.js` supplies a default that matches nothing — the {@code 'desktop'} band, the narrower of
 * the two docked ones.
 *
 * @returns {'phone'|'tablet'|'desktop'|'wide'}
 */
export default function useAskSurface() {
  const phone = useIsMobile();
  const tablet = useMatches(TABLET_QUERY);
  const wide = useMatches(WIDE_QUERY);
  if (phone) return 'phone';
  if (tablet) return 'tablet';
  return wide ? 'wide' : 'desktop';
}
