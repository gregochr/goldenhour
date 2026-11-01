import { useCallback, useEffect, useId, useRef, useSyncExternalStore } from 'react';
import { fetchPipelineRunDetail } from '../api/pipelineRunApi.js';
import { apiErrorMessage } from '../utils/apiError.js';
import { useVisiblePoll } from './useVisiblePoll.js';

/** How often a RUNNING cycle is re-read: fast enough to see {@code waitingOn} move. */
export const CYCLE_POLL_MS = 15 * 1000;

const EMPTY = Object.freeze({
  detail: null, error: null, notFound: false, loaded: false, fetchedAt: null, leader: null,
});

/** One entry per cycle id that has a reader: its snapshot, its listeners and its readers in the order they arrived. */
const entries = new Map();

function notify(entry) {
  entry.listeners.forEach((listener) => listener());
}

function setSnap(entry, patch) {
  entry.snap = { ...entry.snap, ...patch };
  notify(entry);
}

/**
 * Reads the cycle once, sharing the request: a second caller while one is in flight gets the same promise. A failed
 * refresh of a cycle already on screen leaves it there and records the error.
 */
function load(id) {
  const entry = entries.get(id);
  if (!entry) return Promise.resolve();
  if (entry.inflight) return entry.inflight;
  entry.inflight = fetchPipelineRunDetail(id)
    .then((detail) => {
      if (entries.get(id) === entry) {
        setSnap(entry, { detail, error: null, notFound: false, loaded: true, fetchedAt: Date.now() });
      }
    })
    .catch((err) => {
      if (entries.get(id) !== entry) return;
      const notFound = err?.response?.status === 404;
      setSnap(entry, {
        error: notFound ? null : apiErrorMessage(err, err?.message || 'Failed to load the cycle'),
        notFound,
        loaded: true,
      });
    })
    .finally(() => { entry.inflight = null; });
  return entry.inflight;
}

function subscribeTo(id, token, listener) {
  let entry = entries.get(id);
  if (!entry) {
    entry = { snap: EMPTY, listeners: new Set(), readers: [], inflight: null };
    entries.set(id, entry);
  }
  entry.listeners.add(listener);
  entry.readers.push(token);
  // The first reader polls for all of them, so the live strip and the open panel never double-poll.
  if (entry.snap.leader == null) setSnap(entry, { leader: token });
  return () => {
    entry.listeners.delete(listener);
    entry.readers = entry.readers.filter((t) => t !== token);
    if (entry.readers.length === 0) {
      if (entries.get(id) === entry) entries.delete(id);
    } else if (entry.snap.leader === token) {
      setSnap(entry, { leader: entry.readers[0] });
    }
  };
}

/**
 * One pipeline run in full ({@code GET /api/admin/pipeline-runs/{id}}), shared by every reader of that id: the live
 * strip and the open cycle panel are two readers of one request and one 15 second poll (plan §5.2). The first reader
 * polls; the poll runs only while the cycle is RUNNING (a finished cycle is read once) and, through
 * {@link useVisiblePoll}, stops while the browser tab is hidden. It also stands down while {@code isPaused()} says the
 * Operations pane is hidden.
 *
 * @param {number|null} id the cycle id, or null for none
 * @param {{isPaused?: () => boolean}} [options] a poll tick is skipped while it returns true
 * @returns {{detail: object|null, loading: boolean, error: string|null, notFound: boolean, fetchedAt: number|null,
 *   refresh: () => Promise<void>}}
 */
export default function useCycleDetail(id, { isPaused = undefined } = {}) {
  const token = useId();
  const subscribe = useCallback((listener) => (id == null ? () => {} : subscribeTo(id, token, listener)), [id, token]);
  const getSnapshot = useCallback(() => (id == null ? EMPTY : (entries.get(id)?.snap ?? EMPTY)), [id]);
  const snap = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  const pausedRef = useRef(isPaused);
  useEffect(() => {
    pausedRef.current = isPaused;
  });
  const tick = useCallback(() => {
    if (id == null) return;
    const loaded = entries.get(id)?.snap.loaded;
    if (loaded && pausedRef.current?.()) return;
    load(id);
  }, [id]);

  const running = snap.detail?.run?.status === 'RUNNING';
  useVisiblePoll(tick, CYCLE_POLL_MS, { enabled: id != null && snap.leader === token && (!snap.loaded || running) });

  const refresh = useCallback(() => (id == null ? Promise.resolve() : load(id)), [id]);
  return {
    detail: snap.detail,
    loading: id != null && !snap.loaded,
    error: snap.error,
    notFound: snap.notFound,
    fetchedAt: snap.fetchedAt,
    refresh,
  };
}
