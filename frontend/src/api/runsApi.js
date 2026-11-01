import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/runs';

/**
 * Fetches one page of the merged Runs list (pipeline cycles and top-level job runs) with its range-scoped aggregates.
 * ADMIN only. The server groups, names and statuses every row; the client only asks.
 *
 * @param {object} [params]
 * @param {'TODAY'|'WEEK'|'MONTH'} [params.range] the UK-day range (the server defaults to WEEK)
 * @param {string} [params.kind] ALL, CYCLES, BATCHES, TIDE, BRIEFING, AURORA or OTHER
 * @param {boolean} [params.attention] true to keep only Degraded, Partial and Failed rows
 * @param {string|null} [params.before] the previous page's {@code nextCursor}
 * @param {number} [params.size] the page size (the server defaults to 20)
 * @returns {Promise<object>} {items, nextCursor, total, summary, kindCounts, attentionCount, runningCount,
 *   liveCycleId, nextOvernightAt, truncated}
 */
export async function fetchRuns({
  range = undefined, kind = undefined, attention = false, before = null, size = undefined,
} = {}) {
  const params = {};
  if (range) params.range = range;
  if (kind) params.kind = kind;
  if (attention) params.attention = true;
  if (before) params.before = before;
  if (size) params.size = size;
  const response = await apiClient.get(BASE_URL, { params });
  return response.data;
}

/**
 * Fetches one job run in full: the run, its served status, group, name and note, and its Anthropic batch row. Works
 * for any job run, including a cycle's batch, which never appears at the top level. ADMIN only.
 *
 * @param {number} id the job run id
 * @returns {Promise<object>} {run, status, group, name, note, noteTone, triggeredManually, batch}
 */
export async function fetchJobRun(id) {
  const response = await apiClient.get(`${BASE_URL}/job/${id}`);
  return response.data;
}
