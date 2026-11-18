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
  isPlaceStarred: () => false,
  isAreaStarred: () => false,
  setStar: () => Promise.resolve(false),
  toggle: () => Promise.resolve(false),
});

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
  const bodyRef = useRef(null);
  const requestSeq = useRef(0);
  // The newest setStar, so an Undo press made later calls the current one (a callback cannot name itself).
  const setStarRef = useRef(null);
  const toast = useUndoToast();
  const { show } = toast;

  const setBody = useCallback((next) => {
    bodyRef.current = next;
    setBodyState(next);
  }, []);

  useEffect(() => {
    if (!pro) return undefined;
    let live = true;
    fetchFavourites()
      .then((data) => { if (live && bodyRef.current == null) setBody(data); })
      .catch(() => { if (live) setFailed(true); });
    return () => { live = false; };
  }, [pro, setBody]);

  const hasAreas = (body?.areas?.length ?? 0) > 0;
  useEffect(() => {
    if (!hasAreas || directory != null) return undefined;
    let live = true;
    fetchAreaDirectory()
      .then((rows) => { if (live && Array.isArray(rows)) setDirectory(rows); })
      .catch(() => { /* the pickers simply have no starred-area entries until a later load */ });
    return () => { live = false; };
  }, [hasAreas, directory]);

  const sets = useMemo(() => (body ? favouriteSets(body) : NO_FAVOURITES), [body]);
  const starredAreas = useMemo(
    () => (directory ? directory.filter((entry) => sets.areaIds.has(entry.id)) : []),
    [directory, sets],
  );

  /**
   * Sets one place or area to a given state: optimistic, then the server's list, with the toast.
   *
   * @param {'place'|'area'} kind
   * @param {number} id
   * @param {string} name what the toast calls it
   * @param {boolean} on the state to move to
   * @param {boolean} [undo] true for an Undo press: the toast then says "Undone." and offers no second Undo
   * @returns {Promise<boolean>} whether the server took it
   */
  const setStar = useCallback(async (kind, id, name, on, undo = false) => {
    if (bodyRef.current == null) return false;
    setBody(applyStar(bodyRef.current, kind, id, on));
    requestSeq.current += 1;
    const mine = requestSeq.current;
    try {
      const next = await setFavourite(kind, id, on);
      if (mine === requestSeq.current) setBody(next);
      show(undo
        ? { message: 'Undone.' }
        : {
          message: starToastText(kind, name, on),
          // Void: Undo is a press handler, nothing waits on the second request.
          onUndo: () => { void setStarRef.current(kind, id, name, !on, true); },
        });
      return true;
    } catch (err) {
      // Only this change goes back: a second change made while this was in flight is the reader's and stays.
      setBody(applyStar(bodyRef.current, kind, id, !on));
      show({ message: err?.response?.status === 409 ? apiErrorMessage(err, FAVOURITE_FAILED) : FAVOURITE_FAILED });
      return false;
    }
  }, [setBody, show]);
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
      isPlaceStarred: (id) => sets.placeIds.has(id),
      isAreaStarred: (id) => sets.areaIds.has(id),
      setStar,
      toggle: (kind, id, name) => setStar(kind, id, name, !(kind === 'area' ? sets.areaIds : sets.placeIds).has(id)),
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
