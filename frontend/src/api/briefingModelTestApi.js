import apiClient from './axiosClient.js';

/**
 * Briefing Model Test API client — compares Haiku/Sonnet/Opus on briefing picks.
 * Uses the shared apiClient with JWT interceptors (configured in axiosClient.js)
 */

/**
 * Triggers a briefing model comparison test (3 API calls).
 *
 * @returns {Promise} Response with the completed test run
 */
export const runBriefingModelTest = () => {
  return apiClient.post('/api/briefing/compare-models');
};

/**
 * Fetch recent briefing model test runs (last 20).
 *
 * @returns {Promise} Response with list of test runs
 */
export const getBriefingModelTestRuns = () => {
  return apiClient.get('/api/briefing/compare-models/runs');
};

/**
 * Fetch results for a specific briefing model test run.
 *
 * @param {number} runId - Test run ID
 * @returns {Promise} Response with list of test results
 */
export const getBriefingModelTestResults = (runId) => {
  return apiClient.get(`/api/briefing/compare-models/results?runId=${runId}`);
};
