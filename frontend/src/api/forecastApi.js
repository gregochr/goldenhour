import apiClient from './axiosClient.js';

const BASE_URL = '/api';

/**
 * In-memory cache of fetched forecast-detail rows, keyed by evaluation id.
 *
 * The list endpoint now returns slim rows; the heavy popup-only detail is
 * fetched lazily per id and memoised here so re-opening a popup is instant.
 * A miss is cached as {@code null} so a broken/absent row degrades to a
 * header-only popup without refetching on every re-render.
 *
 * MUST be cleared on logout/session-expiry (via {@link clearForecastDetailCache})
 * so one account's role-gated detail is never shown to the next account on a
 * shared browser.
 *
 * @type {Map<number, object|null>}
 */
const forecastDetailCache = new Map();

/**
 * Synchronously reads the detail cache so a popup can paint instantly on re-open.
 *
 * @param {number} id - The forecast evaluation primary key.
 * @returns {{cached: boolean, detail: object|null}} Whether the id is cached and its value.
 */
export function peekForecastDetail(id) {
  return forecastDetailCache.has(id)
    ? { cached: true, detail: forecastDetailCache.get(id) }
    : { cached: false, detail: null };
}

/**
 * Stores a resolved detail row (or null for a miss) against its id.
 *
 * @param {number} id - The forecast evaluation primary key.
 * @param {object|null} detail - The full detail row, or null for a failed/absent fetch.
 */
export function cacheForecastDetail(id, detail) {
  forecastDetailCache.set(id, detail);
}

/**
 * Empties the forecast-detail cache. Call alongside {@code clearSwrCache()} on
 * logout and session-expiry so cached detail never leaks across accounts.
 */
export function clearForecastDetailCache() {
  forecastDetailCache.clear();
}

/**
 * Fetches the T through T+5 forecast week for all configured locations.
 *
 * @returns {Promise<Array<object>>} Array of forecast evaluations.
 */
export async function fetchForecasts() {
  const response = await apiClient.get(`${BASE_URL}/forecast`);
  return response.data;
}

/**
 * Fetches ONE stored past day — {@code GET /api/forecast?date=YYYY-MM-DD}
 * (outcome-rating-plan.md §2 #4, backend O2).
 *
 * <p>The answer is the list's own shape for a single day: every enabled place's passed SUNRISE and
 * SUNSET slot (served as of the event; a slot with nothing available by the event has no element)
 * plus that day's HOURLY rows. The array is NOT ordered by location — group by date and type. The
 * endpoint admits T-1 to T-4 and answers 400 for anything else, and it overlaps the list's own
 * past edge, so the Map tab's older-day fetch never asks for a day it already holds. A day can
 * legitimately come back with fewer rows than the list once did, or empty.
 *
 * @param {string} date - The past day, YYYY-MM-DD.
 * @returns {Promise<Array<object>>} The forecast rows for that day (possibly empty).
 */
export async function fetchForecastsForDate(date) {
  const response = await apiClient.get(`${BASE_URL}/forecast`, { params: { date } });
  return response.data;
}

/**
 * Fetches the full ("fat") forecast detail row for a single persisted evaluation.
 *
 * The list endpoint ({@link fetchForecasts}) returns slim rows; the heavy
 * popup-only fields (tide, surge, inversion, aerosol, golden/blue-hour times,
 * cloud layers, and the AI summary) live only here and are loaded lazily when a
 * marker popup opens. Only persisted rich rows (non-null id) have a detail row.
 *
 * @param {number} id - The forecast evaluation primary key.
 * @returns {Promise<object>} The full forecast evaluation DTO.
 */
export async function getForecastDetail(id) {
  const response = await apiClient.get(`${BASE_URL}/forecast/${id}`);
  return response.data;
}

/**
 * Triggers a manual refresh of tide extreme data for all coastal locations.
 *
 * @returns {Promise<{status: string}>} Status message.
 */
export async function refreshTideData() {
  const response = await apiClient.post(`${BASE_URL}/forecast/run/tide`);
  return response.data;
}

/**
 * Triggers a backfill of 12 months of historical tide data for all SEASCAPE locations.
 * Skips date ranges where data already exists.
 *
 * @returns {Promise<{status: string}>} Status message.
 */
export async function backfillTideData() {
  const response = await apiClient.post(`${BASE_URL}/forecast/run/tide/backfill`);
  return response.data;
}

/**
 * Fetches all persisted locations ordered alphabetically by name.
 *
 * @returns {Promise<Array<{id: number, name: string, lat: number, lon: number}>>} Location list.
 */
export async function fetchLocations() {
  const response = await apiClient.get(`${BASE_URL}/locations`);
  return response.data;
}

/**
 * Adds a new location to the persisted set.
 *
 * @param {object} data - Location data.
 * @param {string} data.name - Human-readable location identifier.
 * @param {number} data.lat  - Latitude in decimal degrees.
 * @param {number} data.lon  - Longitude in decimal degrees.
 * @param {string[]} [data.solarEventTypes] - Solar event types.
 * @param {string} [data.locationType] - Photography type.
 * @param {string} [data.tideType] - Tide preference.
 * @returns {Promise<object>} The saved location entity.
 */
export async function addLocation(data) {
  const response = await apiClient.post(`${BASE_URL}/locations`, data);
  return response.data;
}

/**
 * Updates metadata for an existing location.
 *
 * @param {number} id - Location primary key.
 * @param {object} data - Updated metadata (solarEventTypes, locationType, tideType).
 * @returns {Promise<object>} The updated location entity.
 */
export async function updateLocation(id, data) {
  const response = await apiClient.put(`${BASE_URL}/locations/${id}`, data);
  return response.data;
}

/**
 * Toggles the enabled state of a location.
 *
 * @param {number} id - Location primary key.
 * @param {boolean} enabled - Whether the location should be enabled.
 * @returns {Promise<object>} The updated location entity.
 */
export async function setLocationEnabled(id, enabled) {
  const response = await apiClient.put(`${BASE_URL}/locations/${id}/enabled`, { enabled });
  return response.data;
}

/**
 * Resets the consecutive-failure count for a location, re-enabling it after auto-disable.
 * ADMIN only.
 *
 * @param {string} locationName - The configured location name.
 * @returns {Promise<object>} the location as stored afterwards; `enabled` stays false when its region is off.
 */
export async function resetLocationFailures(locationName) {
  const { data } = await apiClient.put(`${BASE_URL}/locations/reset-failures`, null, {
    params: { name: locationName },
  });
  return data;
}

/**
 * Fetches auto-detected enrichment data (bortle, SQM, elevation, grid cell) for a location.
 *
 * @param {number} lat - Latitude in decimal degrees.
 * @param {number} lon - Longitude in decimal degrees.
 * @returns {Promise<{bortleClass: number|null, skyBrightnessSqm: number|null, elevationMetres: number|null, gridLat: number|null, gridLng: number|null}>}
 */
export async function enrichLocation(lat, lon) {
  const response = await apiClient.get(`${BASE_URL}/locations/enrich`, { params: { lat, lon } });
  return response.data;
}

/**
 * Suggests valley-floor points below a location (admin only): the lowest sampled point in each compass
 * sector within about 5 km, kept only when at least 30 m below the location's own DEM sample. An empty
 * list means none qualify; a failed lookup rejects (HTTP 502), so the two stay distinguishable.
 *
 * @param {number} lat - Latitude in decimal degrees (the editor's edited value).
 * @param {number} lon - Longitude in decimal degrees.
 * @returns {Promise<{siteSampleMetres: number, candidates: Array<{metres: number, lat: number, lng: number, distanceKm: number, bearing: string}>}>}
 */
export async function fetchValleyFloorCandidates(lat, lon) {
  const response = await apiClient.get(`${BASE_URL}/locations/valley-floor-candidates`, { params: { lat, lon } });
  return response.data;
}

/**
 * Looks up the Open-Meteo model cell a chosen valley-floor point resolves to when requested at that
 * height (admin only; shown, never stored).
 *
 * @param {number} lat - The floor point's latitude.
 * @param {number} lon - The floor point's longitude.
 * @param {number} elevation - The floor height in metres.
 * @returns {Promise<{cellLat: number, cellLng: number, cellElevationMetres: number, cellDistanceKm: number}>}
 */
export async function fetchValleyFloorCell(lat, lon, elevation) {
  const response = await apiClient.get(`${BASE_URL}/locations/valley-floor-cell`, { params: { lat, lon, elevation } });
  return response.data;
}

/**
 * Geocodes a place name via Nominatim (OpenStreetMap).
 * Uses plain fetch to avoid the JWT axios interceptor.
 *
 * @param {string} placeName - The place to search for.
 * @returns {Promise<{lat: number, lon: number, displayName: string}>} Resolved coordinates.
 * @throws {Error} If no results are found.
 */
export async function geocodePlace(placeName) {
  const url = `https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(placeName + ' UK')}&format=json&limit=1`;
  const response = await fetch(url, {
    headers: { 'Accept': 'application/json' },
  });
  if (!response.ok) {
    throw new Error('Geocoding request failed');
  }
  const results = await response.json();
  if (!results || results.length === 0) {
    throw new Error('No results found for "' + placeName + '"');
  }
  return {
    lat: parseFloat(results[0].lat),
    lon: parseFloat(results[0].lon),
    displayName: results[0].display_name,
  };
}

