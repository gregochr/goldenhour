import axios from 'axios';

/**
 * Model Test API client — A/B/C model comparison testing.
 * Uses axios with JWT interceptors (configured in forecastApi.js)
 */

/**
 * Triggers a model comparison test across all enabled regions.
 *
 * @returns {Promise} Response with the completed test run
 */
export const runModelTest = () => {
  return axios.post('/api/model-test/run');
};

/**
 * Fetch recent model test runs (last 20).
 *
 * @returns {Promise} Response with list of test runs
 */
export const getModelTestRuns = () => {
  return axios.get('/api/model-test/runs');
};

/**
 * Triggers a model comparison test for a single location.
 *
 * @param {number} locationId - Location ID to test
 * @returns {Promise} Response with the completed test run
 */
export const runModelTestForLocation = (locationId) => {
  return axios.post(`/api/model-test/run-location?locationId=${locationId}`);
};

/**
 * Re-runs a previous model test using the same locations but fresh data.
 *
 * @param {number} testRunId - Previous test run ID to re-run
 * @returns {Promise} Response with the completed new test run
 */
export const rerunModelTest = (testRunId) => {
  return axios.post(`/api/model-test/rerun?testRunId=${testRunId}`);
};

/**
 * Re-runs a previous model test using identical atmospheric data (determinism test).
 *
 * @param {number} testRunId - Previous test run ID to replay
 * @returns {Promise} Response with the completed new test run
 */
export const rerunModelTestDeterministic = (testRunId) => {
  return axios.post(`/api/model-test/rerun-determinism?testRunId=${testRunId}`);
};

/**
 * Fetch results for a specific model test run.
 *
 * @param {number} testRunId - Test run ID
 * @returns {Promise} Response with list of test results
 */
export const getModelTestResults = (testRunId) => {
  return axios.get(`/api/model-test/results?testRunId=${testRunId}`);
};
