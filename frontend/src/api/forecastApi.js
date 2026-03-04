import axios from 'axios';
import { refreshAccessToken } from './authApi.js';

const BASE_URL = '/api';

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';

// Attach the JWT access token to every outgoing request.
axios.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers = config.headers ?? {};
    config.headers['Authorization'] = `Bearer ${token}`;
  }
  return config;
});

// On 401, attempt a single token refresh and queue concurrent requests.
let refreshPromise = null;

axios.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;
    const storedRefresh = localStorage.getItem(REFRESH_KEY);

    if (error.response?.status !== 401 || original._retried || !storedRefresh) {
      return Promise.reject(error);
    }

    original._retried = true;

    // If a refresh is already in flight, wait for it then retry with the new token.
    if (refreshPromise) {
      await refreshPromise;
      original.headers['Authorization'] = `Bearer ${localStorage.getItem(TOKEN_KEY)}`;
      return axios(original);
    }

    // First 401 triggers the refresh; concurrent 401s await the same promise.
    refreshPromise = refreshAccessToken(storedRefresh)
      .then((data) => {
        localStorage.setItem(TOKEN_KEY, data.accessToken);
        if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
      })
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(REFRESH_KEY);
        localStorage.removeItem('goldenhour_role');
        window.dispatchEvent(new Event('goldenhour:session-expired'));
      })
      .finally(() => {
        refreshPromise = null;
      });

    await refreshPromise;
    original.headers['Authorization'] = `Bearer ${localStorage.getItem(TOKEN_KEY)}`;
    return axios(original);
  }
);

/**
 * Fetches the T through T+5 forecast week for all configured locations.
 *
 * @returns {Promise<Array<object>>} Array of forecast evaluations.
 */
export async function fetchForecasts() {
  const response = await axios.get(`${BASE_URL}/forecast`);
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
  const response = await axios.get(`${BASE_URL}/outcome`, {
    params: { lat, lon, from, to },
  });
  return response.data;
}

/**
 * Triggers an on-demand forecast re-run for a specific date, location, and target type.
 *
 * @param {string} date - Target date (YYYY-MM-DD).
 * @param {string} location - Configured location name.
 * @param {string} targetType - SUNRISE or SUNSET.
 * @returns {Promise<{status: string, runType: string}>} Accepted status message.
 */
export async function runForecast(date, location, targetType) {
  const response = await axios.post(`${BASE_URL}/forecast/run`, { dates: [date], location, targetType });
  return response.data;
}

/**
 * Triggers an on-demand run of very-short-term forecasts (today, T+1).
 * Uses the model configured under VERY_SHORT_TERM.
 *
 * @returns {Promise<{status: string, runType: string}>} Accepted status message.
 */
export async function runVeryShortTermForecast() {
  const response = await axios.post(`${BASE_URL}/forecast/run/very-short-term`);
  return response.data;
}

/**
 * Triggers an on-demand run of near-term forecasts (today, T+1, T+2).
 * Uses the model configured under SHORT_TERM.
 *
 * @returns {Promise<{status: string, runType: string}>} Accepted status message.
 */
export async function runShortTermForecast(dryRun = false) {
  const response = await axios.post(`${BASE_URL}/forecast/run/short-term?dryRun=${dryRun}`);
  return response.data;
}

/**
 * Triggers an on-demand run of distant forecasts (T+3 through T+5).
 * Uses the model configured under LONG_TERM.
 *
 * @returns {Promise<{status: string, runType: string}>} Accepted status message.
 */
export async function runLongTermForecast() {
  const response = await axios.post(`${BASE_URL}/forecast/run/long-term`);
  return response.data;
}

/**
 * Triggers a manual refresh of tide extreme data for all coastal locations.
 *
 * @returns {Promise<{status: string}>} Status message.
 */
export async function refreshTideData() {
  const response = await axios.post(`${BASE_URL}/forecast/run/tide`);
  return response.data;
}

/**
 * Fetches all persisted locations ordered alphabetically by name.
 *
 * @returns {Promise<Array<{id: number, name: string, lat: number, lon: number}>>} Location list.
 */
export async function fetchLocations() {
  const response = await axios.get(`${BASE_URL}/locations`);
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
  const response = await axios.post(`${BASE_URL}/locations`, data);
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
  const response = await axios.put(`${BASE_URL}/locations/${id}`, data);
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
  const response = await axios.put(`${BASE_URL}/locations/${id}/enabled`, { enabled });
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
  const response = await axios.get(`${BASE_URL}/tides`, {
    params: { locationName, date },
  });
  return response.data;
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
  const response = await axios.post(`${BASE_URL}/outcome`, outcome);
  return response.data;
}
