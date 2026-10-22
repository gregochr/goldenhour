import {
  useCallback, useEffect, useMemo, useRef, useState,
} from 'react';
import { fetchAllRatingsForSlot } from '../api/outcomeRatingApi.js';
import { rowKey } from './useOutcomeRatings.js';

const IDLE = Object.freeze({ key: null, status: 'idle', data: null });

/**
 * One slot's ratings from EVERY user — the Admin's "All ratings" box under the rating block
 * (`docs/engineering/outcome-rating-plan.md` §2 #7, §3 O5a). A hook of its own beside
 * `useOutcomeRatings` rather than a method on it: that hook is a range read of the caller's own rows
 * that the whole Map tab shares, and this is a per-slot read that exists only while one admin's callout
 * is open on one slot, so neither belongs in the other's state or its dependency lists.
 *
 * <p>Mounted by `MapCallout`, not by the block: the card places itself from its own props and state, and
 * the rows and {@code loading} change the box's height, so they have to be visible there (plan §1 #3).
 *
 * <h2>When it reads</h2>
 * <ul>
 *   <li><b>On first expand</b> — {@code open} for a slot nothing has been fetched for. Never with the
 *       caller's own ratings read, never while collapsed, never when {@code enabled} is false (the
 *       callout passes false for every role but ADMIN, so PRO and LITE accounts never make the request),
 *       never twice for one slot while the first answered.</li>
 *   <li><b>After the admin's own save lands</b> — {@link refetch}, called by the callout with the slot
 *       the save was for. It acts only when that is the slot this hook currently holds a fetch for: a
 *       save that finishes after the admin moved on, or for a slot never opened, asks for nothing (the
 *       first expand will).</li>
 *   <li><b>On re-open after a failure</b> — the box stays openable, and opening it again tries again
 *       (closing it forgets the failure). A failed RE-fetch keeps the rows already held.</li>
 * </ul>
 *
 * <h2>What it holds</h2>
 * <p>One slot's answer, keyed. A change of slot drops it (and abandons a request in flight), so the
 * rows can never be another slot's and a slot revisited reads fresh. The rows are as the server sent
 * them — newest first, {@code mine} marked; hoisting the caller's own row is the list's job.
 *
 * @param {object} args
 * @param {boolean} args.enabled whether this reader may and should have the list (ADMIN, with the rating
 *        block mounted for this slot)
 * @param {?number} args.locationId
 * @param {?string} args.date `YYYY-MM-DD`
 * @param {?string} args.eventType `'SUNRISE' | 'SUNSET'`
 * @param {boolean} args.open whether the box is expanded
 * @returns {{
 *   rows: ?Array<{username: string, rating: ?number, updatedAt: ?string, mine: boolean,
 *     seen: ?string}>,
 *   eventTime: ?string,
 *   forecastRunAt: ?string,
 *   loading: boolean,
 *   failed: boolean,
 *   refetch: function(string): void
 * }} {@code rows} is null until a fetch has answered; {@code loading} is true only with nothing to show
 *   yet (a re-fetch keeps the old rows on screen); {@code failed} says the latest fetch did (rows already held stay)
 */
export function useSlotRatings({
  enabled, locationId, date, eventType, open,
}) {
  const slotKey = enabled && locationId != null && date && eventType
    ? rowKey(locationId, date, eventType) : null;
  const [state, setState] = useState(IDLE);
  const [prevKey, setPrevKey] = useState(slotKey);
  // A different slot starts empty: held rows are never carried to another slot, and a slot revisited
  // is fetched afresh on its next expand.
  if (slotKey !== prevKey) {
    setPrevKey(slotKey);
    setState(IDLE);
  }

  const stateRef = useRef(state);
  const slotRef = useRef({
    key: slotKey, locationId, date, eventType,
  });
  const seqRef = useRef(0);
  const aliveRef = useRef(true);

  useEffect(() => {
    stateRef.current = state;
    slotRef.current = {
      key: slotKey, locationId, date, eventType,
    };
  }, [state, slotKey, locationId, date, eventType]);
  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; };
  }, []);
  // Leaving a slot abandons whatever was still on its way for it.
  useEffect(() => () => { seqRef.current += 1; }, [slotKey]);

  const run = useCallback((slot) => {
    seqRef.current += 1;
    const token = seqRef.current;
    setState((prev) => ({
      key: slot.key, status: 'loading', data: prev.key === slot.key ? prev.data : null,
    }));
    const landed = (next) => {
      if (token !== seqRef.current || !aliveRef.current) return;
      setState(next);
    };
    fetchAllRatingsForSlot(slot.locationId, slot.date, slot.eventType)
      .then((data) => landed(() => ({ key: slot.key, status: 'ready', data })))
      // A failed RE-fetch keeps the rows already held (stale, not unreadable) beside the failure line.
      .catch(() => landed((prev) => ({
        key: slot.key, status: 'failed', data: prev.key === slot.key ? prev.data : null,
      })));
  }, []);

  // Closing a box that is showing a failure forgets it, so the next open starts at "Loading …" rather
  // than painting the old failure for a frame before the retry begins.
  useEffect(() => {
    if (!open && stateRef.current.status === 'failed') setState(IDLE);
  }, [open]);

  // First expand (or a re-open after a failure). Not keyed on `state`: a failed answer must not retry
  // by itself.
  useEffect(() => {
    if (!slotKey || !open) return;
    const held = stateRef.current;
    if (held.key === slotKey && held.status !== 'failed') return;
    run({
      key: slotKey, locationId, date, eventType,
    });
  }, [slotKey, open, locationId, date, eventType, run]);

  const refetch = useCallback((key) => {
    const slot = slotRef.current;
    if (!aliveRef.current || !key || key !== slot.key || stateRef.current.key !== key) return;
    run(slot);
  }, [run]);

  const held = slotKey && state.key === slotKey ? state : null;
  const data = held?.data ?? null;
  const rows = useMemo(() => {
    if (!data) return null;
    return (Array.isArray(data.ratings) ? data.ratings : []).map((r) => ({
      username: r.username,
      rating: r.rating ?? null,
      updatedAt: r.updatedAt ?? null,
      mine: Boolean(r.mine),
      // The "what did you see" tag (valley-mist-plan M3). Dropping it here left the list's tag line
      // permanently empty: the list reads rows as THIS hook shapes them, not as the server sent them.
      seen: r.seen ?? null,
    }));
  }, [data]);
  const loading = Boolean(slotKey && open && (!held || (held.status === 'loading' && !held.data)));
  const failed = held?.status === 'failed';

  return useMemo(() => ({
    rows,
    eventTime: data?.eventTime ?? null,
    forecastRunAt: data?.forecastRunAt ?? null,
    loading,
    failed,
    refetch,
  }), [rows, data, loading, failed, refetch]);
}
