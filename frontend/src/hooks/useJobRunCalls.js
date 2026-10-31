import { useEffect, useState } from 'react';
import { getApiCalls, getBatchSummary } from '../api/metricsApi';
import { apiErrorMessage } from '../utils/apiError.js';
import { isBatchRunType } from '../utils/jobRunPanel.js';

/**
 * The per-call figures behind a job run's panel: its {@code api_call_log} rows and, for an Anthropic batch run,
 * the batch's token and cost summary. The first load for a run shows "loading"; a later reload (the run's counts
 * moved, so its calls may have) happens silently and leaves what is on screen in place.
 *
 * @param {number|null} id the job run id, or null when no panel is open
 * @param {string|null} runType the run's type; a batch type also asks for the batch summary
 * @param {string} reloadKey changes when the run's own figures move, to reload quietly
 * @returns {{loading: boolean, error: string|null, apiCalls: Array<object>, batchSummary: object|null}}
 */
export default function useJobRunCalls(id, runType, reloadKey) {
  const [state, setState] = useState({ id: null, apiCalls: [], batchSummary: null, error: null });

  useEffect(() => {
    if (id == null) return undefined;
    let cancelled = false;
    (async () => {
      try {
        let batchSummary = null;
        if (isBatchRunType(runType)) {
          const resp = await getBatchSummary(id).catch(() => null);
          batchSummary = resp?.data ?? null;
        }
        const calls = await getApiCalls(id);
        if (!cancelled) setState({ id, apiCalls: calls.data || [], batchSummary, error: null });
      } catch (err) {
        if (!cancelled) {
          setState({ id, apiCalls: [], batchSummary: null, error: apiErrorMessage(err, err?.message || 'Failed to load API calls') });
        }
      }
    })();
    return () => { cancelled = true; };
  }, [id, runType, reloadKey]);

  const settled = state.id === id;
  return {
    loading: id != null && !settled,
    error: settled ? state.error : null,
    apiCalls: settled ? state.apiCalls : [],
    batchSummary: settled ? state.batchSummary : null,
  };
}
