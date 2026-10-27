import { DARK_SKY_THRESHOLD } from './darkSky.js';

/**
 * The two location facts the Map's subject filters and Coming up's outside-your-sub-regions line both ask, kept in
 * one place so the line and the map can never disagree about what a coastal or dark-sky place is
 * (sub-regions-plan §11 S5b-2, item 10). Facts of the served {@code /api/locations} record only: never
 * {@code tideType}, never a client rule of its own.
 */

/**
 * Coastal: the place's {@code locationType} contains {@code SEASCAPE}, the type the Coming up chips send as their
 * {@code filterAction} and the Map's type filter matches.
 *
 * @param {?{locationType: ?string[]}} location a served location record
 * @returns {boolean}
 */
export function isCoastalLocation(location) {
  return Array.isArray(location?.locationType) && location.locationType.includes('SEASCAPE');
}

/**
 * Dark sky: Bortle class {@link DARK_SKY_THRESHOLD} or darker. A place with no Bortle class is not dark.
 *
 * @param {?{bortleClass: ?number}} place a served location record, or a heat spot carrying {@code bortleClass}
 * @returns {boolean}
 */
export function isDarkSkyLocation(place) {
  return place?.bortleClass != null && place.bortleClass <= DARK_SKY_THRESHOLD;
}
