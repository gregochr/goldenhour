import axios from 'axios';

/**
 * Metrics API client — job run tracking and API call logging.
 * Uses axios with JWT interceptors (configured in forecastApi.js)
 */

/**
 * Fetch recent job runs, optionally filtered by run type.
 *
 * @param {string} runType - Optional run type filter (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE)
 * @param {number} page - Page number (0-indexed, default 0)
 * @param {number} size - Page size (default 20)
 * @returns {Promise} Response with job runs and pagination info
 */
export const getJobRuns = (runType = undefined, page = 0, size = 20) => {
  const params = new URLSearchParams();
  if (runType) params.append('runType', runType);
  params.append('page', page);
  params.append('size', size);
  return axios.get(`/api/metrics/job-runs?${params.toString()}`);
};

/**
 * Fetch all API calls for a specific job run.
 *
 * @param {number} jobRunId - Job run ID
 * @returns {Promise} List of API call logs
 */
export const getApiCalls = (jobRunId) => {
  return axios.get(`/api/metrics/api-calls?jobRunId=${jobRunId}`);
};

/**
 * Fetch the application build and deploy metadata.
 *
 * @returns {Promise<{version: string, deployedAt: string}>} version and ISO deploy timestamp
 */
export const getBuildInfo = () => {
  return axios.get('/api/admin/build-info');
};
