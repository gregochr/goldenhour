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
 * Fetches recorded actual outcomes for a date range.
 *
 * @param {number} lat - Latitude.
 * @param {number} lon - Longitude.
 * @param {string} from - Start date (YYYY-MM-DD).
 * @param {string} to - End date (YYYY-MM-DD).
 * @returns {Promise<Array<object>>} Array of actual outcome records.
 */
export async function fetchOutcomes(lat, lon, from, to) {
  const response = await apiClient.get(`${BASE_URL}/outcome`, {
    params: { lat, lon, from, to },
  });
  return response.data;
}

/**
 * Fetches recorded actual outcomes for every location in one request.
 *
 * Each returned outcome carries a {@code locationName} for client-side grouping,
 * replacing the previous one-request-per-location fan-out.
 *
 * @param {string} from - Start date (YYYY-MM-DD).
 * @param {string} to - End date (YYYY-MM-DD).
 * @returns {Promise<Array<object>>} Array of actual outcome records across all locations.
 */
export async function fetchAllOutcomes(from, to) {
  const response = await apiClient.get(`${BASE_URL}/outcome/all`, {
    params: { from, to },
  });
  return response.data;
}

/**
 * Triggers an on-demand forecast re-run for a specific date, location, and target type.
 *
 * @param {string} date - Target date (YYYY-MM-DD).
 * @param {string} location - Configured location name.
 * @param {string} targetType - SUNRISE or SUNSET.
 * @returns {Promise<{status: string, runType: string, jobRunId: number}>} Accepted status message.
 */
export async function runForecast(date, location, targetType) {
  const response = await apiClient.post(`${BASE_URL}/forecast/run`, { dates: [date], location, targetType });
  return response.data;
}

/**
 * Triggers an on-demand run of very-short-term forecasts (today, T+1).
 * Uses the model configured under VERY_SHORT_TERM.
 *
 * @param {Array<{date: string, targetType: string}>} excludedSlots     slots to skip (default: none)
 * @param {string[]}                                  excludedLocations  location names to skip (default: none)
 * @returns {Promise<{status: string, runType: string, jobRunId: number}>} Accepted status message.
 */
export async function runVeryShortTermForecast(excludedSlots = [], excludedLocations = []) {
  const hasSlots = excludedSlots.length > 0;
  const hasLocations = excludedLocations.length > 0;
  const body = hasSlots || hasLocations
    ? { ...(hasSlots && { excludedSlots }), ...(hasLocations && { excludedLocations }) }
    : undefined;
  const response = await apiClient.post(`${BASE_URL}/forecast/run/very-short-term`, body);
  return response.data;
}

/**
 * Triggers an on-demand run of near-term forecasts (today, T+1, T+2).
 * Uses the model configured under SHORT_TERM.
 *
 * @param {Array<{date: string, targetType: string}>} excludedSlots     slots to skip (default: none)
 * @param {string[]}                                  excludedLocations  location names to skip (default: none)
 * @returns {Promise<{status: string, runType: string, jobRunId: number}>} Accepted status message.
 */
export async function runShortTermForecast(excludedSlots = [], excludedLocations = []) {
  const hasSlots = excludedSlots.length > 0;
  const hasLocations = excludedLocations.length > 0;
  const body = hasSlots || hasLocations
    ? { ...(hasSlots && { excludedSlots }), ...(hasLocations && { excludedLocations }) }
    : undefined;
  const response = await apiClient.post(`${BASE_URL}/forecast/run/short-term`, body);
  return response.data;
}

/**
 * Triggers an on-demand run of distant forecasts (T+3 through T+5).
 * Uses the model configured under LONG_TERM.
 *
 * @returns {Promise<{status: string, runType: string, jobRunId: number}>} Accepted status message.
 */
export async function runLongTermForecast() {
  const response = await apiClient.post(`${BASE_URL}/forecast/run/long-term`);
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
 * @returns {Promise<void>}
 */
export async function resetLocationFailures(locationName) {
  await apiClient.put(`${BASE_URL}/locations/reset-failures`, null, {
    params: { name: locationName },
  });
}

/**
 * Refreshes drive-time estimates from a given source position to all locations via ORS.
 * Persists drive duration minutes on each location entity. ADMIN only.
 *
 * @param {number} lat - Source latitude (from browser geolocation).
 * @param {number} lon - Source longitude.
 * @returns {Promise<{[locationName: string]: number|null}>} Map of location name to minutes (null = unreachable).
 */
export async function refreshDriveTimes(lat, lon) {
  const response = await apiClient.post(`${BASE_URL}/locations/drive-times`, { lat, lon });
  return response.data;
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

/**
 * Fetches all tide extremes for a location on a given UTC calendar day.
 *
 * @param {string} locationName - The configured location name.
 * @param {string} date - Target date in ISO format (YYYY-MM-DD).
 * @returns {Promise<Array<{id: number, type: string, eventTime: string, heightMetres: string}>>}
 */
export async function fetchTidesForDate(locationName, date) {
  const response = await apiClient.get(`${BASE_URL}/tides`, {
    params: { locationName, date },
  });
  return response.data;
}

/**
 * Fetches aggregate tide height statistics for a location from historical data.
 *
 * @param {string} locationName - The configured location name.
 * @returns {Promise<{avgHighMetres: number, maxHighMetres: number, avgLowMetres: number, minLowMetres: number, dataPoints: number}|null>}
 */
export async function fetchTideStats(locationName) {
  const response = await apiClient.get(`${BASE_URL}/tides/stats`, {
    params: { locationName },
  });
  return response.status === 204 ? null : response.data;
}

/**
 * Records an actual observed outcome for a given date and type.
 *
 * @param {object} outcome - Outcome payload.
 * @param {number} outcome.locationLat - Latitude.
 * @param {number} outcome.locationLon - Longitude.
 * @param {string} outcome.locationName - Human-readable location name.
 * @param {string} outcome.date - Date (YYYY-MM-DD).
 * @param {string} outcome.type - SUNRISE or SUNSET.
 * @param {boolean} outcome.wentOut - Whether the photographer went out.
 * @param {number|null} outcome.actualRating - Photographer's 1-5 rating.
 * @param {string} outcome.notes - Free text observations.
 * @returns {Promise<object>} Created outcome record.
 */
export async function recordOutcome(outcome) {
  const response = await apiClient.post(`${BASE_URL}/outcome`, outcome);
  return response.data;
}
