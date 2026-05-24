const BASE_URL = '/api';
const TOKEN_KEY = 'goldenhour_token';

/**
 * Fetches all evaluation scores (merged from cached_evaluation + forecast_evaluation)
 * for the standard forecast horizon. Used to pre-populate the Plan and Map tabs with
 * batch-scored locations on initial page load.
 *
 * @returns {Promise<Array<{locationName, date, targetType, source, rating, summary,
 *   fierySkyPotential, goldenHourPotential, triageReason, triageMessage, evaluatedAt}>>}
 */
export async function getAllEvaluationScores() {
  const token = localStorage.getItem(TOKEN_KEY);
  const response = await fetch(`${BASE_URL}/briefing/evaluate/scores`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    throw new Error('Failed to fetch evaluation scores');
  }
  return response.json();
}
