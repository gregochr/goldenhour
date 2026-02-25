import axios from 'axios';

/**
 * Metrics API client — job run tracking and API call logging.
 * Uses axios with JWT interceptors (configured in forecastApi.js)
 */

/**
 * Fetch recent job runs, optionally filtered by job name.
 *
 * @param {string} jobName - Optional job name filter (SONNET, HAIKU, WILDLIFE, TIDE)
 * @param {number} page - Page number (0-indexed, default 0)
 * @param {number} size - Page size (default 20)
 * @returns {Promise} Response with job runs and pagination info
 */
export const getJobRuns = (jobName = undefined, page = 0, size = 20) => {
  const params = new URLSearchParams();
  if (jobName) params.append('jobName', jobName);
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
 * Fetch service health metrics (average response times, error rates, etc.).
 *
 * @returns {Promise} Service health statistics
 */
export const getServiceHealth = () => {
  return axios.get('/api/metrics/service-health');
};
