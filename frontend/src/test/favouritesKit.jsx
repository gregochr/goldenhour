import React from 'react';
import { vi } from 'vitest';
import { FavouritesContext } from '../context/FavouritesContext.jsx';

/**
 * A favourites context value for a component test (find-and-favourites-plan.md F4): the sets are what the lists read,
 * and {@code toggle} / {@code setStar} are spies. Pass {@code status: 'locked'} for a Lite reader.
 *
 * @param {object} [o]
 * @param {string} [o.status]
 * @param {number[]} [o.places] bookmarked place ids
 * @param {number[]} [o.areas] bookmarked area ids
 * @param {number[]} [o.locationIds] the served merged ids
 * @param {Array} [o.bookmarkedAreas] directory entries of the bookmarked areas
 * @param {string[]} [o.dates] watched almanac date keys (one-screen-plan.md D16)
 * @returns {object} a context value
 */
export function favouritesValue({
  status = 'ready', places = [], areas = [], locationIds = places, bookmarkedAreas = [], dates = [],
} = {}) {
  const placeIds = new Set(places);
  const areaIds = new Set(areas);
  const dateKeys = new Set(dates);
  return {
    status,
    placeIds,
    areaIds,
    locationIds: new Set(locationIds),
    dateKeys,
    dates: dates.map((key) => ({ key, createdAt: null })),
    bookmarkedAreas,
    areasUnresolved: Math.max(0, areaIds.size - bookmarkedAreas.length),
    isPlaceStarred: (id) => placeIds.has(id),
    isAreaStarred: (id) => areaIds.has(id),
    isDateWatched: (key) => dateKeys.has(key),
    registerWatchHome: vi.fn(() => () => {}),
    setStar: vi.fn(() => Promise.resolve(true)),
    toggle: vi.fn(() => Promise.resolve(true)),
  };
}

/**
 * Wraps a node in a favourites context.
 *
 * @param {React.ReactNode} ui
 * @param {object} [options] {@link favouritesValue}'s options
 * @returns {React.ReactElement}
 */
export function withFavourites(ui, options = {}) {
  return <FavouritesContext.Provider value={favouritesValue(options)}>{ui}</FavouritesContext.Provider>;
}
