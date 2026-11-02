import apiClient from './axiosClient.js';

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
