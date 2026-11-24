import { useCallback, useEffect, useRef, useState } from 'react';

/** The default address Operations opens on: the Runs landing. */
const DEFAULT_OPERATIONS_HASH = 'manage/runs';

/**
 * Whether a location hash is an Operations address: {@code #manage}, {@code #manage/...} or {@code #manage?...}, and
 * never another fragment that merely starts with the letters (a token, {@code #managers}).
 *
 * @param {string} hash a {@code location.hash} value
 * @returns {boolean}
 */
export function isManageHash(hash) {
  return /^#manage(?=$|[/?])/.test(hash ?? '');
}

/** The page's own path and query, without a fragment: the "bare" address Operations closes to. */
function barePath() {
  return `${window.location.pathname}${window.location.search}`;
}

/**
 * The Operations view's route (`docs/engineering/one-screen-plan.md` D9): the view is open exactly while the page's
 * address says {@code #manage...}, and only for a reader allowed it.
 *
 * <p><b>Not a Back layer, on purpose.</b> {@code ManageView}'s own tabs write {@code #manage/<tab>} entries, so Back
 * already steps through them and past the first one out of the view. Registering a {@code #manage} panel with
 * {@code dialogHistory} would make two owners of one entry (`docs/engineering/reference/plan-tab.md`, "Back"). The
 * hook only listens ({@code hashchange}, {@code popstate}) and writes its own two addresses.
 *
 * <p><b>A reader who may not</b> (Pro, Lite) gets {@code #manage...} cleared from the address with a
 * {@code replaceState} that copies {@code history.state}, and the view never opens, so the lazy Operations content is
 * never imported for them. The backend's 403 on {@code /api/admin/**} is the real enforcement.
 *
 * @param {{allowed: boolean}} options whether this reader may use Operations (an admin)
 * @returns {{isOpen: boolean, open: Function, close: Function}} {@code open(hash)} navigates to an Operations address
 *          (default the Runs landing); {@code close()} leaves for the bare path with one new history entry
 */
export default function useOperationsRoute({ allowed }) {
  const [onManageHash, setOnManageHash] = useState(() => isManageHash(window.location.hash));
  const allowedRef = useRef(allowed);
  useEffect(() => { allowedRef.current = allowed; });

  useEffect(() => {
    const sync = () => {
      const manage = isManageHash(window.location.hash);
      // A reader who may not has the address cleared at once, whatever the state last said (the state can already be
      // true from the load that was cleared, and setting it true again would run no effect).
      if (manage && !allowedRef.current) window.history.replaceState(window.history.state, '', barePath());
      setOnManageHash(manage && allowedRef.current);
    };
    window.addEventListener('hashchange', sync);
    window.addEventListener('popstate', sync);
    return () => {
      window.removeEventListener('hashchange', sync);
      window.removeEventListener('popstate', sync);
    };
  }, []);

  // A non-admin's `#manage` address goes at once, wherever it came from (a pasted link, a stale bookmark). The state
  // needs no write: `isOpen` below is already false for them, and the next address change re-reads it.
  useEffect(() => {
    if (allowed || !isManageHash(window.location.hash)) return;
    window.history.replaceState(window.history.state, '', barePath());
  }, [allowed, onManageHash]);

  const open = useCallback((hash = DEFAULT_OPERATIONS_HASH) => {
    if (!allowed) return;
    // Setting the hash is the navigation: it adds the history entry and fires `hashchange`, which `sync` reads.
    window.location.hash = hash;
  }, [allowed]);

  const close = useCallback(() => {
    // One new entry for the bare path, so Back from the screen returns into Operations (as Back past the first
    // `#manage` entry leaves it). `null` state: the entry belongs to no dialog layer. `pushState` fires no event.
    window.history.pushState(null, '', barePath());
    setOnManageHash(false);
  }, []);

  return { isOpen: allowed && onManageHash, open, close };
}
