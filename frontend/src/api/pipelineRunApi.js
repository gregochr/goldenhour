import axios from 'axios';

const BASE_URL = '/api/admin/pipeline-runs';

/**
 * Fetches the most recent pipeline runs for the Pipeline Runs list view.
 * ADMIN only.
 *
 * @returns {Promise<Array>} most recent runs (up to 50) — newest first
 */
export async function fetchPipelineRuns() {
  const response = await axios.get(BASE_URL);
  return response.data;
}

/**
 * Fetches one pipeline run's full detail: summary + phase timeline + batch list.
 * The batch list links each row to its job_run id so the user can drill into
 * the existing disposition breakdown without leaving the browser.
 * ADMIN only.
 *
 * @param {number} id - pipeline run id
 * @returns {Promise<object>} { run, phases, batches }
 */
export async function fetchPipelineRunDetail(id) {
  const response = await axios.get(`${BASE_URL}/${id}`);
  return response.data;
}
