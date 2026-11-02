import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchRuns } from '../api/runsApi.js';
import { apiErrorMessage } from '../utils/apiError.js';
import { useVisiblePoll } from './useVisiblePoll.js';

/** How often the live head is re-fetched: every minute, every 15 seconds while anything is running. */
export const RUNS_POLL_MS = 60 * 1000;
export const RUNS_LIVE_POLL_MS = 15 * 1000;

/**
 * The Runs view's live state (runs-deep-dive-plan.md F6): one {@code GET /api/admin/runs?size=1}, whose
 * {@code runningCount} and {@code liveCycleId} drive the running-cycle banner and the live count beside Runs in
 * ManageView's navigation. It replaces {@code useRunsList}'s paging, merging and filters, and keeps its cadence: a
 * poll every minute, every 15 seconds while a run is running, standing down while the browser tab is hidden
 * ({@link useVisiblePoll}) and while {@code isPaused()} says so (the Operations pane stays mounted, hidden, on the Map
 * and Plan tabs). {@code pollCount} ticks after each successful fetch, so the open job-run panel and the live count
 * ride the same beat.
 *
 * <p>An older answer never lands over a newer one. A failed poll keeps what is on screen and reports the error.
 *
 * @param {{isPaused?: () => boolean}} [options] {@code isPaused}: a poll tick is skipped while it returns true
 * @returns {{meta: object|null, error: string|null, updatedAt: Date|null, pollCount: number, refresh: () => void}}
 */
export default function useRunsLive({ isPaused = undefined } = {}) {
  const [meta, setMeta] = useState(null);
  const [error, setError] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [pollCount, setPollCount] = useState(0);
  const loaded = useRef(false);
  const requestedSeq = useRef(0);
  const appliedSeq = useRef(0);
  const pausedRef = useRef(isPaused);

  useEffect(() => {
    pausedRef.current = isPaused;
  });

  const load = useCallback(async () => {
    const seq = requestedSeq.current + 1;
    requestedSeq.current = seq;
    try {
      const head = await fetchRuns({ size: 1 });
      if (seq < appliedSeq.current) return;
      appliedSeq.current = seq;
      loaded.current = true;
      setMeta(head);
      setError(null);
      setUpdatedAt(new Date());
      setPollCount((count) => count + 1);
    } catch (err) {
      setError(apiErrorMessage(err, err?.message || 'Failed to load runs'));
    }
  }, []);

  // The poll tick: skipped only once something has loaded, while paused (the first load always goes).
  const tick = useCallback(() => {
    if (loaded.current && pausedRef.current?.()) return;
    load();
  }, [load]);

  const runningCount = meta?.runningCount ?? 0;
  useVisiblePoll(tick, runningCount > 0 ? RUNS_LIVE_POLL_MS : RUNS_POLL_MS);

  return { meta, error, updatedAt, pollCount, refresh: load };
}
