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

/**
 * Fetches one UK civil day of the Runs screen: its rows in time order with their types, statuses, costs and facts,
 * the summary strip, the per-chip counts and, for today, what is still to run. ADMIN only. A date that is malformed,
 * in the future or before the earliest day is a 400.
 *
 * @param {string|null} [date] the day as {@code YYYY-MM-DD}; today (UK) when absent
 * @returns {Promise<object>} {date, isToday, earliestDay, sunrise, sunset, sunriseAt, sunsetAt, dayStartUtc,
 *   dayEndUtc, now, runs, summary, filterCounts}
 */
export async function fetchRunDay(date = null) {
  const params = {};
  if (date) params.date = date;
  const response = await apiClient.get(`${BASE_URL}/day`, { params });
  return response.data;
}

/**
 * Fetches one month for the Day view's calendar: per UK day its run count, spend and attention count. ADMIN only.
 *
 * @param {string} month the month as {@code YYYY-MM}
 * @returns {Promise<object>} {month, days, maxSpendGbp, earliestDay}
 */
export async function fetchRunCalendar(month) {
  const response = await apiClient.get(`${BASE_URL}/calendar`, { params: { month } });
  return response.data;
}

/**
 * Fetches the external calls of one Day-view run, aggregated per service. ADMIN only.
 *
 * @param {string} runId {@code cycle-N}, {@code job-N} or {@code eval-N}
 * @returns {Promise<object>} {runId, logged, services}
 */
export async function fetchRunCalls(runId) {
  const response = await apiClient.get(`${BASE_URL}/calls`, { params: { runId } });
  return response.data;
}

/**
 * Fetches the Cost trends view: spend by day and by run type for the last {@code days} full UK days and the same
 * number before them, per-type changes and the verdict as structured fields. ADMIN only. Any other period is a 400.
 *
 * @param {number|'all'} [days] 7, 14, 30, 60, 90 or {@code 'all'} (the server defaults to 30)
 * @returns {Promise<object>} {rangeDays, firstDay, lastDay, allTime, days, types, totals, verdict}
 */
export async function fetchRunCost(days = undefined) {
  const params = {};
  if (days) params.days = days;
  const response = await apiClient.get(`${BASE_URL}/cost`, { params });
  return response.data;
}

/**
 * Fetches the Timing view for one run type: every measured run's duration, the statistics, every type's median, and
 * the planner or ready-by facts. ADMIN only. An unknown type, range or measure is a 400.
 *
 * @param {object} params
 * @param {string} params.type OVERNIGHT, AFTERNOON, EVENING, ASK_READY, BRIEFING or SKY_EVAL
 * @param {number} [params.days] 30, 60 or 120 (the server defaults to 30)
 * @param {'cycle'|'ready'} [params.measure] {@code ready} applies to the three cycle types only
 * @returns {Promise<object>} {type, typeLabel, rangeDays, measure, runs, stats, typeMedians, withoutAnswersLink,
 *   trigger, todaySunset, startsAt, typicalReadyAt, p90ReadyAt, worstReadyAt, readyBasis, sunriseToday,
 *   slackAtP90Minutes}
 */
export async function fetchRunTiming({ type, days = undefined, measure = undefined }) {
  const params = { type };
  if (days) params.days = days;
  if (measure) params.measure = measure;
  const response = await apiClient.get(`${BASE_URL}/timing`, { params });
  return response.data;
}
