import React from 'react';
import { vi } from 'vitest';
import { FavouritesContext } from '../context/FavouritesContext.jsx';

/**
 * A favourites context value for a component test (find-and-favourites-plan.md F4): the sets are what the lists read,
 * and {@code toggle} / {@code setStar} are spies. Pass {@code status: 'locked'} for a Lite reader.
 *
 * @param {object} [o]
 * @param {string} [o.status]
 * @param {number[]} [o.places] starred place ids
 * @param {number[]} [o.areas] starred area ids
 * @param {number[]} [o.locationIds] the served merged ids
 * @param {Array} [o.starredAreas] directory entries of the starred areas
 * @returns {object} a context value
 */
export function favouritesValue({
  status = 'ready', places = [], areas = [], locationIds = places, starredAreas = [],
} = {}) {
  const placeIds = new Set(places);
  const areaIds = new Set(areas);
  return {
    status,
    placeIds,
    areaIds,
    locationIds: new Set(locationIds),
    starredAreas,
    areasUnresolved: Math.max(0, areaIds.size - starredAreas.length),
    isPlaceStarred: (id) => placeIds.has(id),
    isAreaStarred: (id) => areaIds.has(id),
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
