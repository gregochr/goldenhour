import axios from 'axios';

const BASE_URL = '/api';

/**
 * Fetches the T through T+7 forecast week for all configured locations.
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
