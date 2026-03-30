import createEventSource from '../utils/createEventSource.js';

const BASE_URL = '/api';
const TOKEN_KEY = 'goldenhour_token';

/**
 * Subscribes to briefing evaluation SSE for a region/date/targetType.
 * Streams per-location Claude scores as they complete.
 *
 * @param {string}   regionName       - The region to evaluate.
 * @param {string}   date             - YYYY-MM-DD date string.
 * @param {string}   targetType       - 'SUNRISE' or 'SUNSET'.
 * @param {function} onLocationScored - Called with { locationName, rating, fierySkyPotential, goldenHourPotential, summary }.
 * @param {function} onProgress       - Called with { completed, total, failed }.
 * @param {function} onComplete       - Called with { completed, total, failed, regionName, date, targetType }.
 * @param {function} onLocationError  - Called with { locationName, error }.
 * @param {function} onError          - Called on connection error.
 * @returns {function} Cleanup function to close the EventSource.
 */
export function subscribeToBriefingEvaluation(
  regionName, date, targetType,
  onLocationScored, onProgress, onComplete, onLocationError, onError,
) {
  return createEventSource(
    `${BASE_URL}/briefing/evaluate`,
    { regionName, date, targetType },
    {
      'location-scored': onLocationScored,
      'progress': onProgress,
      'evaluation-complete': onComplete,
      'evaluation-error': onLocationError,
    },
    { onError, closeOn: 'evaluation-complete' },
  );
}

/**
 * Fetches cached evaluation scores for a region/date/targetType.
 *
 * @param {string} regionName  - The region name.
 * @param {string} date        - YYYY-MM-DD date string.
 * @param {string} targetType  - 'SUNRISE' or 'SUNSET'.
 * @returns {Promise<Object>} Map of locationName → { rating, fierySkyPotential, goldenHourPotential, summary }.
 */
export async function getCachedEvaluationScores(regionName, date, targetType) {
  const token = localStorage.getItem(TOKEN_KEY);
  const params = new URLSearchParams({ regionName, date, targetType });
  const response = await fetch(`${BASE_URL}/briefing/evaluate/cache?${params.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    throw new Error('Failed to fetch cached scores');
  }
  return response.json();
}
