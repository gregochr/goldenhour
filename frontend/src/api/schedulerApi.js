import axios from 'axios';

const BASE_URL = '/api/admin/scheduler';

/**
 * Fetches all scheduled job configs with status and next fire time.
 * ADMIN only.
 *
 * @returns {Promise<Array>} list of job config objects
 */
export async function fetchSchedulerJobs() {
  const response = await axios.get(`${BASE_URL}/jobs`);
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
  const response = await axios.put(`${BASE_URL}/jobs/${jobKey}/schedule`, schedule);
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
  const response = await axios.post(`${BASE_URL}/jobs/${jobKey}/pause`);
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
  const response = await axios.post(`${BASE_URL}/jobs/${jobKey}/resume`);
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
  const response = await axios.post(`${BASE_URL}/jobs/${jobKey}/trigger`);
  return response.data;
}
