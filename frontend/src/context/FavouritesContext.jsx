import React, {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { fetchFavourites, setFavourite } from '../api/favouritesApi.js';
import { fetchAreaDirectory } from '../api/areasApi.js';
import useUndoToast from '../hooks/useUndoToast.js';
import UndoToast from '../components/shared/UndoToast.jsx';
import { apiErrorMessage } from '../utils/apiError.js';
import {
  applyStar, favouriteSets, FAVOURITE_FAILED, NO_FAVOURITES, starToastText,
} from '../utils/favourites.js';

/**
 * What every consumer reads outside a provider, and what a reader sees before the first answer. {@code status}:
 * <ul>
 *   <li>{@code 'off'} no provider (a test, a bare component): nothing favourite-shaped draws anywhere;</li>
 *   <li>{@code 'locked'} a Lite reader: the Pro doors draw, and the endpoint is never called;</li>
 *   <li>{@code 'loading'} / {@code 'error'} a Pro reader before the list lands, or after it failed: no toggle, no mark
 *       (a toggle with a wrong {@code aria-pressed} would be a false statement);</li>
 *   <li>{@code 'ready'} a Pro reader with a served list.</li>
 * </ul>
 */
export const OFF_FAVOURITES = Object.freeze({
  status: 'off',
  ...NO_FAVOURITES,
  starredAreas: Object.freeze([]),
  areasUnresolved: 0,
  isPlaceStarred: () => false,
  isAreaStarred: () => false,
  isDateWatched: () => false,
  setStar: () => Promise.resolve(false),
  toggle: () => Promise.resolve(false),
});

/** A failed first load or directory read is retried this many times, a little later each, then left for the next page load. */
export const MAX_LOAD_RETRIES = 3;
export const RETRY_MS = 30000;

export const FavouritesContext = createContext(OFF_FAVOURITES);

/**
 * Your places (find-and-favourites-plan.md F4): the reader's served favourites, shared so the sheets, the map, Find,
 * the pickers and the watchlist never each ask.
 *
 * <p><b>A Lite reader never calls the endpoint</b> ({@code pro} false: the provider fetches nothing and its status is
 * {@code 'locked'}); the server's 403 is the real enforcement, this is only the courtesy of not asking.
 *
 * <p>The served body is already merged (D2): {@code locationIds} is the stars plus the members of the starred areas,
 * so nothing here walks membership. The area directory is read only to NAME the starred areas for the pickers and
 * Find, and only when at least one area is starred.
 *
 * <p><b>Toggling is optimistic, with a revert.</b> The star moves at once; the server's whole merged list replaces it
 * when it lands, and only the NEWEST request's answer is applied (writes are ordered by submission, never arrival). A
 * failure puts back exactly that one change (never a snapshot, so a second change made meanwhile survives) and says
 * why in the toast: the server's own sentence for the cap (409, "You can keep up to 25 places."), a generic line
 * otherwise. A success shows a toast with Undo.
 *
 * @param {object} props
 * @param {boolean} props.pro true for PRO_USER and ADMIN (a boolean, never the role)
 * @param {React.ReactNode} props.children
 */
export function FavouritesProvider({ pro, children }) {
  const [body, setBodyState] = useState(null);
  const [failed, setFailed] = useState(false);
  const [directory, setDirectory] = useState(null);
  // How many times the first load / the directory has been retried after a failure (bounded, never a loop).
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [directoryAttempt, setDirectoryAttempt] = useState(0);
  const bodyRef = useRef(null);
  // Changes are SERIALISED: each waits for the one before it, so the server sees them in submission order and the
  // last answer is the truth about all of them. `pending` counts those not yet answered; `epoch` moves on every press.
  const chain = useRef(Promise.resolve());
  const pending = useRef(0);
  const epoch = useRef(0);
  // The newest setStar, so an Undo press made later calls the current one (a callback cannot name itself).
  const setStarRef = useRef(null);
  // One number per reader session: bumped when the provider unmounts or `pro` changes, so a queued write, a retry or a
  // re-read that outlives the session finds it stale and does nothing (no request, no state, no toast).
  const session = useRef(0);
  useEffect(() => {
    session.current += 1;
    return () => { session.current += 1; };
  }, [pro]);
  const toast = useUndoToast();
  const { show } = toast;

  const setBody = useCallback((next) => {
    bodyRef.current = next;
    setBodyState(next);
  }, []);

  useEffect(() => {
    if (!pro) return undefined;
    let live = true;
    let timer;
    fetchFavourites()
      .then((data) => { if (live && bodyRef.current == null) setBody(data); })
      .catch(() => {
        if (!live) return;
        setFailed(true);
        // Quietly try again a few times: a failed first load must not switch favourites off for the whole session.
        if (loadAttempt < MAX_LOAD_RETRIES) {
          timer = setTimeout(() => setLoadAttempt((n) => n + 1), RETRY_MS * (loadAttempt + 1));
        }
      });
    return () => { live = false; clearTimeout(timer); };
  }, [pro, loadAttempt, setBody]);

  const hasAreas = (body?.areas?.length ?? 0) > 0;
  useEffect(() => {
    if (!hasAreas || directory != null) return undefined;
    let live = true;
    let timer;
    fetchAreaDirectory()
      .then((rows) => { if (live && Array.isArray(rows)) setDirectory(rows); })
      .catch(() => {
        if (live && directoryAttempt < MAX_LOAD_RETRIES) {
          timer = setTimeout(() => setDirectoryAttempt((n) => n + 1), RETRY_MS * (directoryAttempt + 1));
        }
      });
    return () => { live = false; clearTimeout(timer); };
  }, [hasAreas, directory, directoryAttempt]);

  const sets = useMemo(() => (body ? favouriteSets(body) : NO_FAVOURITES), [body]);
  const starredAreas = useMemo(
    () => (directory ? directory.filter((entry) => sets.areaIds.has(entry.id)) : []),
    [directory, sets],
  );

  /** Re-reads the served list after a refused change, so state converges on the server's whatever was dropped. */
  const reconcile = useCallback(() => {
    const at = epoch.current;
    const mine = session.current;
    fetchFavourites()
      .then((data) => {
        if (mine === session.current && at === epoch.current && pending.current === 0) setBody(data);
      })
      .catch(() => { /* the optimistic revert stands; the next change or load reconciles */ });
  }, [setBody]);

  /**
   * Sets one place or area to a given state: optimistic, then the server's merged list, with the toast.
   *
   * @param {'place'|'area'|'date'} kind
   * @param {number|string} id the location or area id, or a watched date's key
   * @param {string} name what the toast calls it
   * @param {boolean} on the state to move to
   * @param {boolean} [undo] true for an Undo press: the toast then says "Undone." and offers no second Undo
   * @param {?Array<number|string>} [group] every id one press changes together (un-watching several matched dates), so the
   *        one Undo on the last answer's toast puts them ALL back; omitted, the Undo restores just {@code id}
   * @returns {Promise<boolean>} whether the server took it
   */
  const setStar = useCallback((kind, id, name, on, undo = false, group = null) => {
    if (bodyRef.current == null) return Promise.resolve(false);
    // What had focus at PRESS time, so the toast can hand focus back there however late the answer lands.
    const returnTo = document.activeElement;
    setBody(applyStar(bodyRef.current, kind, id, on));
    epoch.current += 1;
    pending.current += 1;
    const mySession = session.current;
    const run = chain.current.then(async () => {
      // The session this press belongs to has ended (sign-out, a different reader): the request must never go out,
      // because the shared HTTP client would send it with whoever is signed in NOW.
      if (mySession !== session.current) return false;
      let next = null;
      let error = null;
      try {
        next = await setFavourite(kind, id, on);
      } catch (err) {
        error = err;
      }
      pending.current -= 1;
      if (mySession !== session.current) return false;
      if (error == null) {
        // Only the LAST answer is applied or announced: it reflects every change before it, a dropped older one is
        // not shown as if it were current.
        if (pending.current === 0) {
          setBody(next);
          show(undo
            ? { message: 'Undone.', returnTo }
            : {
              message: starToastText(kind, name, on),
              returnTo,
              // Void: Undo is a press handler, nothing waits on the second request.
              onUndo: () => {
                (group ?? [id]).forEach((each) => { void setStarRef.current(kind, each, name, !on, true); });
              },
            });
        }
        return true;
      }
      // Only this change goes back: a later change the reader made stays; then re-read the server's truth.
      setBody(applyStar(bodyRef.current, kind, id, !on));
      show({
        message: error?.response?.status === 409 ? apiErrorMessage(error, FAVOURITE_FAILED) : FAVOURITE_FAILED,
        returnTo,
      });
      if (pending.current === 0) reconcile();
      return false;
    });
    chain.current = run;
    return run;
  }, [setBody, show, reconcile]);
  useEffect(() => { setStarRef.current = setStar; }, [setStar]);

  const value = useMemo(() => {
    let status = 'locked';
    if (pro) {
      if (body) status = 'ready';
      else status = failed ? 'error' : 'loading';
    }
    return {
      status,
      ...sets,
      starredAreas,
      // Starred areas the directory has not named (yet): the watchlist says so rather than hiding them.
      areasUnresolved: Math.max(0, sets.areaIds.size - starredAreas.length),
      isPlaceStarred: (id) => sets.placeIds.has(id),
      isAreaStarred: (id) => sets.areaIds.has(id),
      isDateWatched: (key) => sets.dateKeys.has(key),
      setStar,
      toggle: (kind, id, name) => setStar(
        kind, id, name, !({ area: sets.areaIds, date: sets.dateKeys }[kind] ?? sets.placeIds).has(id),
      ),
    };
  }, [pro, body, failed, sets, starredAreas, setStar]);

  return (
    <FavouritesContext.Provider value={value}>
      {children}
      {pro && (
        <UndoToast toast={toast.toast} onDismiss={toast.dismiss} onPause={toast.pause} onResume={toast.resume} />
      )}
    </FavouritesContext.Provider>
  );
}

FavouritesProvider.propTypes = {
  pro: PropTypes.bool.isRequired,
  children: PropTypes.node,
};

/**
 * The reader's favourites. Outside a provider this is {@link OFF_FAVOURITES}, so a component that reads it renders
 * exactly what it did before favourites existed.
 *
 * @returns {typeof OFF_FAVOURITES}
 */
export function useFavourites() {
  return useContext(FavouritesContext);
}
