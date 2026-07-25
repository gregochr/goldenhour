import apiClient from './axiosClient.js';

const BASE_URL = '/api';

/**
 * Fetches all evaluation scores (merged from cached_evaluation + forecast_evaluation)
 * for the standard forecast horizon. Used to pre-populate the Plan and Map tabs with
 * batch-scored locations on initial page load.
 *
 * <p>Routed through the shared apiClient so it participates in the single-flight 401→refresh
 * retry — a token-boundary refresh no longer drops these scores from the instant-paint Plan.
 *
 * @returns {Promise<Array<{locationName, date, targetType, source, rating, summary,
 *   fierySkyPotential, goldenHourPotential, triageReason, triageMessage, evaluatedAt}>>}
 */
export async function getAllEvaluationScores() {
  const response = await apiClient.get(`${BASE_URL}/briefing/evaluate/scores`);
  return response.data;
}
