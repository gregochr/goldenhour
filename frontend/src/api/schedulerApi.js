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
 * @param {object} schedule - { cronExpression } or { fixedDelayMs }; a cron may add {@code timeZone}
 *   ({@code "Europe/London"} or {@code "UTC"}); omit the key to keep the job's current zone
 * @returns {Promise<object>} the updated job config
 */
export async function updateJobSchedule(jobKey, schedule) {
  const response = await apiClient.put(`${BASE_URL}/jobs/${jobKey}/schedule`, schedule);
  return response.data;
}

/**
 * Dry-runs a proposed schedule: validates it exactly as a save would, saves nothing. ADMIN only. A proposal a save
 * would refuse answers 200 with {@code valid: false} and the message; only a failed request rejects.
 *
 * @param {string} jobKey - the job key
 * @param {object} body - {@code {scheduleType, solarEvent?, solarOffsetMinutes?, times?, daysOfWeek?, timeZone?,
 *   fixedDelayMs?}}
 * @param {{signal?: AbortSignal}} [options] - an abort signal for a request that has been superseded
 * @returns {Promise<object>} {@code {valid, error, cronExpression, timeZone, nextFires, yearRange, neighbours}}
 */
export async function previewSchedule(jobKey, body, { signal } = {}) {
  const response = await apiClient.post(`${BASE_URL}/jobs/${jobKey}/schedule-preview`, body, { signal });
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
 * Fetches the page-level summary: the job total, counts by status and schedule type, how many jobs use Claude, how
 * many last failed, and the soonest non-repeating fire. ADMIN only.
 *
 * @returns {Promise<object>} {@code {total, byStatus, byType, usesClaude, lastFireFailed, nextScheduled}}
 */
export async function fetchSchedulerSummary() {
  const response = await apiClient.get(`${BASE_URL}/jobs/summary`);
  return response.data;
}

/**
 * Fetches the expensive facts of one job for its panel (never polled): its typical duration and its Claude spend over
 * the last 30 days. ADMIN only.
 *
 * @param {string} jobKey - the job key
 * @returns {Promise<object>} {@code {jobKey, typicalDurationSeconds, typicalDurationBasis, claudeCost30d}}
 */
export async function fetchJobDetail(jobKey) {
  const response = await apiClient.get(`${BASE_URL}/jobs/${jobKey}/detail`);
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
