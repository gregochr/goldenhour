import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/scheduler';

/**
 * Fetches all scheduled job configs with status and next fire time.
 * ADMIN only.
 *
 * @returns {Promise<Array>} list of job config objects
 */
export async function fetchSchedulerJobs() {
  const response = await apiClient.get(`${BASE_URL}/jobs`);
  return response.data;
}

/**
 * Updates the schedule for a job (cron expression or fixed delay).
 * ADMIN only.
 *
 * @param {string} jobKey - the job key
 * @param {object} schedule - { cronExpression } or { fixedDelayMs }
 * @returns {Promise<object>} the updated job config
 */
export async function updateJobSchedule(jobKey, schedule) {
  const response = await apiClient.put(`${BASE_URL}/jobs/${jobKey}/schedule`, schedule);
  return response.data;
}

/**
 * Switches a forecast cycle job between a fixed cron time and a sun-relative schedule.
 * ADMIN only. Rejects (400, {@code {error}}) with nothing changed when the body is invalid.
 *
 * @param {string} jobKey - the job key (one of the two forecast cycle jobs)
 * @param {object} body - { scheduleType: 'CRON' } or
 *   { scheduleType: 'SOLAR_OFFSET', solarEvent, solarOffsetMinutes }
 * @returns {Promise<object>} the updated job config
 */
export async function switchScheduleType(jobKey, body) {
  const response = await apiClient.put(`${BASE_URL}/jobs/${jobKey}/schedule-type`, body);
  return response.data;
}

/**
 * Fetches the read-only preview of the two forecast cycles' solar rules.
 * ADMIN only.
 *
 * @returns {Promise<object>} { referencePoint, generatedAt, jobs }
 */
export async function fetchSolarPreview() {
  const response = await apiClient.get(`${BASE_URL}/solar-preview`);
  return response.data;
}

/**
 * Pauses a scheduled job.
 * ADMIN only.
 *
 * @param {string} jobKey - the job key
 * @returns {Promise<object>} the updated job config
 */
export async function pauseJob(jobKey) {
  const response = await apiClient.post(`${BASE_URL}/jobs/${jobKey}/pause`);
  return response.data;
}

/**
 * Resumes a paused job.
 * ADMIN only.
 *
 * @param {string} jobKey - the job key
 * @returns {Promise<object>} the updated job config
 */
export async function resumeJob(jobKey) {
  const response = await apiClient.post(`${BASE_URL}/jobs/${jobKey}/resume`);
  return response.data;
}

/**
 * Fetches a job's newest runs by the Runs list's rules (ADMIN only). Only the three forecast cycle jobs,
 * {@code tide_refresh} and {@code aurora_batch_evaluation} have attributable runs; any other job answers an empty
 * list, and an unknown job a 404.
 *
 * @param {string} jobKey - the job key
 * @param {number} [limit=3] - how many runs (the server clamps to 10)
 * @returns {Promise<Array>} list items, newest first
 */
export async function fetchJobRuns(jobKey, limit = 3) {
  const response = await apiClient.get(`${BASE_URL}/jobs/${jobKey}/runs`, { params: { limit } });
  return response.data;
}

/**
 * Triggers a job to run immediately.
 * ADMIN only.
 *
 * @param {string} jobKey - the job key
 * @returns {Promise<object>} { status, jobKey }
 */
export async function triggerJob(jobKey) {
  const response = await apiClient.post(`${BASE_URL}/jobs/${jobKey}/trigger`);
  return response.data;
}
