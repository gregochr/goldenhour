import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/pipeline-runs';

/**
 * Fetches one pipeline run's full detail: summary + phase timeline + batch list + the intraday-vs-nightly comparison.
 * The Runs cycle panel reads it ({@code useCycleDetail}); each batch carries its {@code jobRunId}, which opens the
 * batch's job-run panel. The Runs list itself is {@code runsApi.fetchRuns}, which merges cycles and job runs.
 * ADMIN only.
 *
 * @param {number} id - pipeline run id
 * @returns {Promise<object>} { run, phases, batches, comparison }
 */
export async function fetchPipelineRunDetail(id) {
  const response = await apiClient.get(`${BASE_URL}/${id}`);
  return response.data;
}
