import axios from 'axios';

/**
 * Triggers a sky-rating eval run. Returns 202 with the in-progress run; the scoring
 * happens in the background — poll {@link getSkyRatingEvalRun} for completion.
 *
 * @param {string} model - HAIKU | SONNET | OPUS
 * @param {number} [runsPerFixture] - runs per fixture (defaults server-side to 8)
 */
export const runSkyRatingEval = (model, runsPerFixture) =>
  axios.post(
    `/api/admin/sky-rating-eval/run?model=${model}` +
      (runsPerFixture ? `&runsPerFixture=${runsPerFixture}` : ''),
  );

/** Recent runs, newest first. */
export const getSkyRatingEvalRuns = () => axios.get('/api/admin/sky-rating-eval/runs');

/** A single run, for progress polling. */
export const getSkyRatingEvalRun = (id) => axios.get(`/api/admin/sky-rating-eval/runs/${id}`);

/** The per-(fixture × run-index) results for one run. */
export const getSkyRatingEvalResults = (id) =>
  axios.get(`/api/admin/sky-rating-eval/runs/${id}/results`);

/** The calibration-drift series — one aggregate point per (completed run × fixture). */
export const getSkyRatingEvalTrend = () => axios.get('/api/admin/sky-rating-eval/trend');
