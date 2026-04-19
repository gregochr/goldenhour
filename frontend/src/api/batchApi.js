import axios from 'axios';

/**
 * Batch admin API client — region listing and batch submission.
 */

/**
 * Fetch regions with enabled location counts.
 *
 * @returns {Promise<Array<{id: number, name: string, locationCount: number}>>}
 */
export const getRegions = () => axios.get('/api/admin/batches/regions').then((r) => r.data);

/**
 * Submit a scheduled batch (same triage gates as overnight job).
 *
 * @param {number[]|null} regionIds - Region IDs to include, or null for all
 * @returns {Promise<{batchId: string, requestCount: number}>}
 */
export const submitScheduledBatch = (regionIds) =>
  axios.post('/api/admin/batches/submit-scheduled', { regionIds }).then((r) => r.data);

/**
 * Submit a JFDI batch (no triage, all dates T+0 to T+3, both events).
 *
 * @param {number[]|null} regionIds - Region IDs to include, or null for all
 * @returns {Promise<{batchId: string, requestCount: number}>}
 */
export const submitJfdiBatch = (regionIds) =>
  axios.post('/api/admin/batches/submit-jfdi', { regionIds }).then((r) => r.data);
