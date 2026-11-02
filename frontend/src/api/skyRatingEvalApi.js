import apiClient from './axiosClient.js';

/**
 * Triggers a sky-rating eval run. Returns 202 with the in-progress run; the scoring
 * happens in the background — poll {@link getSkyRatingEvalRun} for completion.
 *
 * @param {string} model - HAIKU | HAIKU_55 | SONNET | SONNET_55 | OPUS
 * @param {number} [runsPerFixture] - runs per fixture (defaults server-side to 8)
 */
export const runSkyRatingEval = (model, runsPerFixture) =>
  apiClient.post(
    `/api/admin/sky-rating-eval/run?model=${model}` +
      (runsPerFixture ? `&runsPerFixture=${runsPerFixture}` : ''),
  );

/** Recent runs, newest first. */
export const getSkyRatingEvalRuns = () => apiClient.get('/api/admin/sky-rating-eval/runs');

/** A single run, for progress polling. */
export const getSkyRatingEvalRun = (id) => apiClient.get(`/api/admin/sky-rating-eval/runs/${id}`);

/** The calibration-drift series — one aggregate point per (completed run × fixture). */
export const getSkyRatingEvalTrend = () => apiClient.get('/api/admin/sky-rating-eval/trend');

/**
 * Everything the Sky Eval page renders, in one read: head version, schedule, lanes, sessions, per-model stats,
 * verdict, version comparisons and the Run a check defaults. All figures are derived on the server.
 */
export const getSkyEvalOverview = () => apiClient.get('/api/admin/sky-rating-eval/overview');

/**
 * Starts a quick check: one MANUAL run per model on the current build, scored in parallel. Resolves 202 with
 * `{runIds}`, in model order. This bills the Anthropic key.
 *
 * @param {{models: string[], runsPerFixture: 1|3|8, fixtureNames: string[]|null}} body the check to run
 */
export const startSkyEvalCheck = (body) => apiClient.post('/api/admin/sky-rating-eval/check', body);

/** The weekly run's model list: one `{model, scheduled}` row per selectable model. */
export const getSkyRatingEvalSchedule = () => apiClient.get('/api/admin/sky-rating-eval/schedule');

/**
 * Switches one model on or off for the weekly run. Resolves to only that model's `{model, scheduled}` row.
 *
 * @param {string} model - EvaluationModel enum name
 * @param {boolean} scheduled - true to include the model in the weekly run
 */
export const setSkyRatingEvalScheduled = (model, scheduled) =>
  apiClient.put(`/api/admin/sky-rating-eval/schedule/${model}`, { scheduled });
