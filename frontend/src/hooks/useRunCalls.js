import { useCallback, useEffect, useState } from 'react';
import { fetchRunCalls } from '../api/runsApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * The external calls of one expanded Day row ({@code GET /api/admin/runs/calls}), fetched when the row opens.
 * A fetch failure is its own state ({@code error}), distinct from a run whose calls errored (that is data).
 *
 * @param {string|null} runId {@code cycle-N}, {@code job-N} or {@code eval-N}; null fetches nothing
 * @returns {{data: object|null, loading: boolean, error: string|null, retry: () => void}}
 */
export default function useRunCalls(runId) {
  const [state, setState] = useState({ runId: null, data: null, error: null });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (runId == null) return undefined;
    let cancelled = false;
    fetchRunCalls(runId)
      .then((data) => {
        if (!cancelled) setState({ runId, data, error: null });
      })
      .catch((err) => {
        if (!cancelled) {
          setState({ runId, data: null, error: apiErrorMessage(err, err?.message || 'Failed to load the calls') });
        }
      });
    return () => { cancelled = true; };
  }, [runId, attempt]);

  const retry = useCallback(() => {
    setState({ runId: null, data: null, error: null });
    setAttempt((n) => n + 1);
  }, []);

  const settled = runId != null && state.runId === runId;
  return {
    data: settled ? state.data : null,
    loading: runId != null && !settled,
    error: settled ? state.error : null,
    retry,
  };
}
