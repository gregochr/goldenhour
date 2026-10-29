import apiClient from './axiosClient.js';
import { getMemoised } from '../utils/jsonMemo.js';

const BASE_URL = '/api/aurora';

/**
 * Fetches the current NOAA SWPC aurora alert status.
 *
 * Returns null if the request fails or returns 403 (free-tier user).
 *
 * @returns {Promise<object|null>} the status object or null
 */
export async function getAuroraStatus() {
  try {
    const response = await getMemoised(apiClient, `${BASE_URL}/status`);
    return response.data;
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      return null; // Free-tier user — don't show aurora UI
    }
    throw err;
  }
}

/**
 * Fetches the level-only aurora alert a LITE reader's banner is built from
 * (docs/engineering/aurora-lite-alert-plan.md §2.2). Open to every authenticated role, and the only
 * aurora status request a LITE reader makes. Carries the level, whether it is active, how it was
 * triggered, whether every dark-sky site is overcast and the current night's end: no Kp, count or site.
 *
 * <p>Resolves null on any error, so a failed read draws no banner rather than a broken one.
 *
 * @returns {Promise<?{level: string, active: boolean, triggerType: ?string, allOvercast: boolean,
 *   currentNightDate: ?string, currentNightEndsAt: ?string}>} the alert, or null
 */
export async function getAuroraAlert() {
  try {
    const response = await getMemoised(apiClient, `${BASE_URL}/alert`);
    return response.data ?? null;
  } catch {
    return null;
  }
}

/**
 * Fetches the nights a Pro reader's window control would offer an aurora row for right now
 * (docs/engineering/aurora-pro-door-plan.md §2.1). Open to every authenticated role: the Lite
 * reader's "Aurora tonight" Pro door is driven by it. Carries only dates, no rating or location.
 *
 * <p>Resolves an empty answer on any error, so a failed read draws no door rather than a broken one.
 *
 * @returns {Promise<{nights: string[], currentNightEndsAt: ?string}>} ISO dates ascending, and the
 *   instant the current night ends (or null)
 */
export async function getAuroraNights() {
  try {
    const response = await apiClient.get(`${BASE_URL}/nights`);
    const data = response.data;
    return {
      nights: Array.isArray(data?.nights) ? data.nights : [],
      currentNightEndsAt: data?.currentNightEndsAt ?? null,
    };
  } catch {
    return { nights: [], currentNightEndsAt: null };
  }
}

/**
 * Triggers a Bortle light-pollution enrichment run for all unenriched locations.
 * ADMIN only.
 *
 * @returns {Promise<object>} { status, runType, jobRunId }
 */
export async function enrichBortle() {
  const response = await apiClient.post(`${BASE_URL}/admin/enrich-bortle`);
  return response.data;
}

/**
 * Activates aurora simulation mode with the provided fake NOAA space weather values.
 * No Claude API calls are made.
 * ADMIN only.
 *
 * @param {object} request
 * @param {number} request.kp                 - simulated Kp index (0–9)
 * @param {number} request.ovationProbability - OVATION aurora probability at 55°N (0–100)
 * @param {number} request.bzNanoTesla        - solar wind Bz in nanoTesla
 * @param {string|null} request.gScale        - G-scale label ("G1"–"G5") or null
 * @returns {Promise<{level: string, message: string, eligibleLocations: number}>}
 */
export async function simulateAurora(request) {
  const response = await apiClient.post(`${BASE_URL}/admin/simulate`, request);
  return response.data;
}

/**
 * Clears the active aurora simulation, resetting the state machine to IDLE.
 * ADMIN only.
 *
 * @returns {Promise<object>} { status }
 */
export async function clearSimulation() {
  const response = await apiClient.post(`${BASE_URL}/admin/simulate/clear`);
  return response.data;
}

/**
 * Fetches scored aurora-eligible locations, filtered by Bortle class and star rating.
 *
 * @param {object} [params]
 * @param {number} [params.maxBortle=4] - maximum Bortle class to include
 * @param {number} [params.minStars=1]  - minimum star rating to include
 * @returns {Promise<Array>} list of scored locations
 */
export async function getAuroraLocations({ maxBortle = 4, minStars = 1 } = {}) {
  const response = await apiClient.get(`${BASE_URL}/locations`, {
    params: { maxBortle, minStars },
  });
  return response.data;
}

/**
 * Returns a 3-night Kp preview (tonight, T+1, T+2) for the night selector popup.
 * Reads cached NOAA data — no Claude cost.
 *
 * @returns {Promise<{nights: Array}>} preview with per-night Kp data
 */
export async function getAuroraForecastPreview() {
  const response = await apiClient.get(`${BASE_URL}/forecast/preview`);
  return response.data;
}

/**
 * Runs aurora forecasts for the selected nights and stores results to the database.
 * Each viable night triggers one Claude API call.
 *
 * @param {string[]} nights - ISO date strings (e.g. ['2026-03-21', '2026-03-22'])
 * @returns {Promise<{nights: Array, totalClaudeCalls: number, estimatedCost: string}>}
 */
export async function runAuroraForecast(nights) {
  const response = await apiClient.post(`${BASE_URL}/forecast/run`, { nights });
  return response.data;
}

/**
 * Fetches stored aurora forecast results for the map view for a given night.
 *
 * @param {string} date - ISO date string (e.g. '2026-03-21')
 * @returns {Promise<Array>} list of location results with stars, summary, and factor breakdown
 */
export async function getAuroraForecastResults(date) {
  const response = await apiClient.get(`${BASE_URL}/forecast/results`, { params: { date } });
  return response.data;
}

/**
 * Returns all ISO dates for which stored aurora forecast results exist.
 * Used to determine whether the Aurora toggle should appear on the map.
 *
 * @returns {Promise<string[]>} sorted list of ISO date strings
 */
export async function getAuroraForecastAvailableDates() {
  try {
    const response = await apiClient.get(`${BASE_URL}/forecast/results/available-dates`);
    return response.data;
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      return []; // Free-tier — no stored aurora results available
    }
    throw err;
  }
}

/**
 * Fetches the forecast aurora extent line: the camera-visibility rule drawn for the highest Kp NOAA
 * forecasts for the rest of tonight, read by the server on every request.
 * Returns null for free-tier users (403).
 *
 * @returns {Promise<object|null>} forecast viewline response or null
 */
export async function getAuroraForecastViewline() {
  try {
    const response = await getMemoised(apiClient, `${BASE_URL}/viewline/forecast`);
    return response.data;
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      return null;
    }
    throw err;
  }
}

/**
 * Fetches the aurora viewline — the southernmost visible aurora boundary for UK longitudes.
 * Returns null for free-tier users (403).
 *
 * @returns {Promise<object|null>} viewline response or null
 */
export async function getAuroraViewline() {
  try {
    const response = await getMemoised(apiClient, `${BASE_URL}/viewline`);
    return response.data;
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      return null;
    }
    throw err;
  }
}
