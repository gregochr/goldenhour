import {
  useCallback, useEffect, useMemo, useRef, useState,
} from 'react';
import { getAskSettings } from '../api/askApi.js';
import { ukDateStr } from '../utils/mapDates.js';

/**
 * The caller's typed-question allowance, read from `GET /api/user/settings/ask`.
 *
 * <p><b>Only the server's figures are ever shown.</b> The hook holds what the endpoint said, or what
 * a typed answer said ({@link applyServed}, fed the POST response's own {@code allowanceLeft} and
 * {@code allowanceLimit}); nothing here counts a question down. It is refetched on demand
 * ({@code refetch}): the provider calls it after a POST that could have moved the figure and carried
 * none (an answer without its figures, a lost connection, a failed engine, a refusal that used a
 * question, a response a newer ask overtook), and NOT after an answer that states its figures, which
 * is the server's word and is applied as it stands. (That leaves {@code typedAvailable}, which a POST
 * answer does not carry, as the last read had it until the next read — owner decision, 2026-10-08.)
 * A read in flight when a served figure lands is ignored when it resolves: it was taken before that
 * answer and would put the older count back over it. A read started AFTER it is applied as usual.
 * It is never cached: this endpoint sits under the personal-data prefix `HttpCachingConfig` never
 * filters, and the state lives in the component, not in `utils/swrCache.js`, so a logout cannot carry
 * one reader's allowance to the next.
 *
 * <p><b>The UK day turning over refetches it.</b> The allowance resets at UK midnight, and a tab left
 * open overnight (an installed PWA is the ordinary case) would otherwise go on saying "No own
 * questions left today" and keep typed questions off until a reload. Checked when the tab comes
 * back to the foreground, and only when the date has actually changed — the same shape as
 * `useTodaysLight`'s day check, and no polling.
 *
 * <p>Before the first answer the figures are {@code null} and {@code loaded} is false: a surface
 * must render no claim about the allowance then, not "0 left". A failed read leaves the last known
 * figures in place and sets {@code status: 'failed'}; a surface that wants to disable itself while
 * the backend is down reads that status.
 *
 * @returns {{status: 'loading'|'ready'|'failed', loaded: boolean, enabled: ?boolean, used: ?number,
 *   limit: ?number, left: ?number, typedAvailable: ?boolean, refetch: function(): void,
 *   applyServed: function({left: number, limit: number}): void}}
 */
export default function useAskAllowance() {
  const [state, setState] = useState({ status: 'loading', data: null });
  const [tick, setTick] = useState(0);
  // The UK civil date of the newest read that SUCCEEDED, started on that date. A ref: it is read
  // inside an event listener and must never itself cause a render. A read that fails leaves it where
  // it was, so a tab that comes back after midnight and fails to re-read tries again on the next
  // return instead of keeping yesterday's "none left" as today's answer.
  const readOn = useRef(null);
  // Orders what may write the state. Every read takes a ticket when it STARTS and {@code applyServed}
  // takes one too, so a read is applied only if no newer read has started and no served figure has
  // landed since: a settings snapshot taken before a POST answer must not overwrite that answer.
  // (`live` alone covers the newer-read case; it cannot see a served figure, which is not a read.)
  const ticket = useRef(0);

  useEffect(() => {
    let live = true;
    const startedOn = ukDateStr();
    ticket.current += 1;
    const mine = ticket.current;
    const current = () => live && mine === ticket.current;
    getAskSettings()
      .then((raw) => {
        if (!current()) return;
        const data = normalise(raw);
        if (data) readOn.current = startedOn;
        setState((prev) => (data
          ? { status: 'ready', data }
          : { status: 'failed', data: prev.data }));
      })
      // A superseded request's failure must not mark the newest request's answer as failed.
      .catch(() => {
        if (current()) setState((prev) => ({ status: 'failed', data: prev.data }));
      });
    return () => { live = false; };
  }, [tick]);

  const refetch = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    const refetchIfTheDayTurned = () => {
      if (document.visibilityState !== 'visible') return;
      if (readOn.current && ukDateStr() !== readOn.current) setTick((n) => n + 1);
    };
    // Both events, because they answer different returns: `visibilitychange` covers a tab switched
    // back to, `focus` a window raised with the tab already visible. The date guard makes the pair
    // idempotent.
    document.addEventListener('visibilitychange', refetchIfTheDayTurned);
    window.addEventListener('focus', refetchIfTheDayTurned);
    return () => {
      document.removeEventListener('visibilitychange', refetchIfTheDayTurned);
      window.removeEventListener('focus', refetchIfTheDayTurned);
    };
  }, []);

  /** Takes the server's own left/limit from a POST response; `used` follows from them. */
  const applyServed = useCallback(({ left, limit }) => {
    if (!isCount(left) || !isCount(limit)) return;
    ticket.current += 1; // every read started before this answer is now stale
    setState((prev) => ({
      status: prev.status === 'failed' ? 'ready' : prev.status,
      data: {
        enabled: true,
        typedAvailable: prev.data?.typedAvailable ?? true,
        used: Math.max(0, limit - left),
        limit,
        left,
      },
    }));
  }, []);

  const { status, data } = state;
  return useMemo(() => ({
    status,
    loaded: data !== null,
    enabled: data?.enabled ?? null,
    used: data?.used ?? null,
    limit: data?.limit ?? null,
    left: data?.left ?? null,
    typedAvailable: data?.typedAvailable ?? null,
    refetch,
    applyServed,
  }), [status, data, refetch, applyServed]);
}

/** A whole, non-negative number — the only shape a count can honestly take. */
function isCount(value) {
  return Number.isInteger(value) && value >= 0;
}

/** The endpoint's body as the hook holds it, or null for a body that is not one. */
function normalise(raw) {
  if (raw === null || typeof raw !== 'object') return null;
  if (!isCount(raw.used) || !isCount(raw.limit) || !isCount(raw.left)) return null;
  return {
    enabled: raw.enabled === true,
    used: raw.used,
    limit: raw.limit,
    left: raw.left,
    typedAvailable: raw.typedAvailable === true,
  };
}
