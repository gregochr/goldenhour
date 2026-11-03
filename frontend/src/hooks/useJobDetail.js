import { useEffect, useState } from 'react';
import { fetchJobDetail } from '../api/schedulerApi.js';

/**
 * One job's expensive facts for the Scheduler panel (typical duration, Claude spend over 30 days), from
 * {@code GET /api/admin/scheduler/jobs/{key}/detail}. Fetched once when the panel opens for a job and never polled; a
 * response for a job the reader has since left never lands.
 *
 * @param {string|null} jobKey the open job, or null when no panel is open
 * @returns {{detail: object|null, loading: boolean, error: boolean}} {@code detail} is null until the answer
 */
export default function useJobDetail(jobKey) {
  const [state, setState] = useState({ jobKey: null, detail: null, error: false });

  useEffect(() => {
    if (jobKey == null) return undefined;
    let cancelled = false;
    Promise.resolve()
      .then(() => fetchJobDetail(jobKey))
      .then((detail) => {
        if (!cancelled) setState({ jobKey, detail: detail ?? null, error: false });
      })
      .catch(() => {
        if (!cancelled) setState({ jobKey, detail: null, error: true });
      });
    return () => { cancelled = true; };
  }, [jobKey]);

  const current = state.jobKey === jobKey;
  return {
    detail: current ? state.detail : null,
    loading: jobKey != null && !current,
    error: current && state.error,
  };
}
