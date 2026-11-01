import { useEffect, useState } from 'react';
import { fetchJobRun } from '../api/runsApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * One job run for the Runs panel, from {@code GET /api/admin/runs/job/{id}} (so a deep link and a cycle's batch
 * work). It reloads when the id changes, and silently refetches on {@code refreshKey} (the list's poll tick) without
 * blanking the panel, so a running run's figures stay current.
 *
 * @param {number|null} id the job run id, or null when no job-run panel is open
 * @param {number} [refreshKey] a number that changes on each list poll
 * @returns {{detail: object|null, loading: boolean, error: string|null, notFound: boolean}}
 */
export default function useJobRunDetail(id, refreshKey = 0) {
  const [state, setState] = useState({ id: null, detail: null, error: null, notFound: false });

  useEffect(() => {
    if (id == null) return undefined;
    let cancelled = false;
    fetchJobRun(id)
      .then((detail) => {
        if (!cancelled) setState({ id, detail, error: null, notFound: false });
      })
      .catch((err) => {
        if (cancelled) return;
        const notFound = err?.response?.status === 404;
        setState((prev) => ({
          id,
          // A failed refresh of the run already on screen leaves it there.
          detail: prev.id === id ? prev.detail : null,
          error: notFound ? null : apiErrorMessage(err, err?.message || 'Failed to load the job run'),
          notFound,
        }));
      });
    return () => { cancelled = true; };
  }, [id, refreshKey]);

  const settled = state.id === id;
  return {
    detail: settled ? state.detail : null,
    loading: id != null && !settled,
    error: settled ? state.error : null,
    notFound: settled ? state.notFound : false,
  };
}
