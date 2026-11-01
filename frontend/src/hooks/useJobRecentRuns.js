import { useEffect, useState } from 'react';
import { fetchJobRuns } from '../api/schedulerApi.js';

/** How many recent runs the Scheduler panel shows. */
export const RECENT_RUNS_LIMIT = 3;

/**
 * A job's newest runs for the Scheduler panel, from {@code GET /api/admin/scheduler/jobs/{key}/runs}. It reloads when
 * the job changes and silently refetches on {@code refreshKey} (the jobs poll's tick) without blanking the section, so
 * a running cycle's row keeps up. A response for a job the reader has since left never lands.
 *
 * @param {string|null} jobKey the open job, or null when no panel is open
 * @param {boolean} enabled whether the job has attributable runs at all (the served {@code runGroup})
 * @param {number} [refreshKey] a number that changes on each jobs poll
 * @returns {{runs: Array<object>|null, loading: boolean, error: boolean}} {@code runs} is null until the first answer
 */
export default function useJobRecentRuns(jobKey, enabled, refreshKey = 0) {
  const [state, setState] = useState({ jobKey: null, runs: null, error: false });
  const active = jobKey != null && enabled;

  useEffect(() => {
    if (!active) return undefined;
    let cancelled = false;
    fetchJobRuns(jobKey, RECENT_RUNS_LIMIT)
      .then((runs) => {
        if (!cancelled) setState({ jobKey, runs, error: false });
      })
      .catch(() => {
        // A failed refresh of the runs already on screen leaves them there.
        if (!cancelled) setState((prev) => ({ jobKey, runs: prev.jobKey === jobKey ? prev.runs : null, error: true }));
      });
    return () => { cancelled = true; };
  }, [active, jobKey, refreshKey]);

  const settled = active && state.jobKey === jobKey;
  return {
    runs: settled ? state.runs : null,
    loading: active && !settled,
    error: settled ? state.error : false,
  };
}
