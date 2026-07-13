import apiClient from './axiosClient.js';

/**
 * Metrics API client — job run tracking and API call logging.
 * Uses the shared apiClient with JWT interceptors (configured in axiosClient.js)
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
  return apiClient.get(`/api/metrics/job-runs?${params.toString()}`);
};

/**
 * Fetch all API calls for a specific job run.
 *
 * @param {number} jobRunId - Job run ID
 * @returns {Promise} List of API call logs
 */
export const getApiCalls = (jobRunId) => {
  return apiClient.get(`/api/metrics/api-calls?jobRunId=${jobRunId}`);
};

/**
 * Fetch batch token/cost summary for a batch job run.
 *
 * @param {number} jobRunId - Job run ID linked to a forecast batch
 * @returns {Promise} Batch summary with token counts, cost, and status
 */
export const getBatchSummary = (jobRunId) => {
  return apiClient.get(`/api/metrics/batch-summary?jobRunId=${jobRunId}`);
};

/**
 * Fetch the application build and deploy metadata.
 *
 * @returns {Promise<{version: string, deployedAt: string}>} version and ISO deploy timestamp
 */
export const getBuildInfo = () => {
  return apiClient.get('/api/admin/build-info');
};

/**
 * Fetch per-candidate disposition breakdown for a job run (V101).
 *
 * Backs the Job Run detail "Disposition Breakdown" section — totals, per-category
 * counts (EVALUATED + SKIPPED_*), and every entry (with location, date, event,
 * detail). Returns an empty-but-well-formed response for non-batch runs and for
 * the cycle's 2nd/3rd/4th bucket job runs (dispositions live only on the first
 * job run created in a cycle).
 *
 * @param {number} jobRunId - Job run ID to fetch dispositions for
 * @returns {Promise} Breakdown response with totalCount, countsByDisposition, entries
 */
export const getDispositionBreakdown = (jobRunId) => {
  return apiClient.get(`/api/metrics/disposition-breakdown?jobRunId=${jobRunId}`);
};
