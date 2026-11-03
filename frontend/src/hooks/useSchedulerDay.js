import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchSchedulerDay } from '../api/schedulerApi.js';
import { useVisiblePoll } from './useVisiblePoll.js';
import { ukDateStr, shiftDateStr } from '../utils/mapDates.js';
import { DAY_RANGE_DAYS, dayInRange } from '../utils/schedulerDay.js';
import { dropInvalidDate, isSchedulerHash, readSchedulerQuery, writeSchedulerQuery } from '../utils/schedulerQuery.js';

/** The poll period for today's picture, and the tick that advances the Now line and notices midnight. */
export const DAY_POLL_MS = 30_000;

/**
 * The Day's jobs tab's data and clock (scheduler-redesign-plan.md D13).
 *
 * <p>"Today", the navigation's bounds and the Now line come from the served {@code date}, {@code isToday} and
 * {@code now}, advanced by {@code Date.now()} between reads (the served instant fixes the offset from this machine's
 * clock), and fall back to the browser's UK date before the first answer. ⚠️ Never {@code appNow()}: Operations does
 * not follow Rewind and {@code /api/admin} is never rewound.
 *
 * <p>The day is polled every 30 seconds <em>only when it is today</em>, skipping a tick while the Operations pane is
 * hidden (the pane stays mounted when the reader is on another tab). A reader following today (no {@code ?date=}) rolls
 * over to the new day at midnight. A {@code ?date=} that is invalid, today or out of range is replaced by today; a 400
 * for a date resets to today too.
 *
 * @param {{current: HTMLElement|null}} rootRef the tab's root, for the hidden-pane check
 * @returns {object} {@code {date, today, isToday, nowMs, data, loading, error, canPrev, canNext, goPrev, goNext,
 *   goToday, reload}}; {@code data} is null until the answer for the viewed day has arrived
 */
export default function useSchedulerDay(rootRef) {
  const [chosen, setDate] = useState(() => readSchedulerQuery().date);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [offsetMs, setOffsetMs] = useState(0);
  const [tick, setTick] = useState(() => Date.now());
  const request = useRef(0);
  const loadedOnce = useRef(false);

  const clockMs = tick + offsetMs;
  const today = ukDateStr(new Date(clockMs));
  // An explicit date that is today (it is written out of the address), or outside the navigation's reach, is today.
  const date = chosen != null && chosen !== today && dayInRange(chosen, today) ? chosen : null;
  const following = date == null;
  const viewDate = date ?? today;

  // The tick moves the Now line and lets a midnight be noticed even when no poll is running.
  useEffect(() => {
    const timer = setInterval(() => setTick(Date.now()), DAY_POLL_MS);
    return () => clearInterval(timer);
  }, []);

  // Back, Forward or a pasted link can change the date in the address; a malformed one is replaced by today.
  useEffect(() => {
    const onHashChange = () => {
      if (!isSchedulerHash()) return;
      dropInvalidDate();
      setDate(readSchedulerQuery().date);
    };
    if (isSchedulerHash()) dropInvalidDate();
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const resetToToday = useCallback(() => {
    writeSchedulerQuery({ date: null });
    setDate(null);
  }, []);

  // The address follows what is shown: a date that read as today is removed from it.
  useEffect(() => {
    if (chosen == null || date != null) return;
    writeSchedulerQuery({ date: null });
    // Forgotten too, so a date that read as today cannot come back as another day after midnight.
    (async () => setDate(null))();
  }, [chosen, date]);

  const load = useCallback(async (requested) => {
    const id = ++request.current;
    try {
      const served = await fetchSchedulerDay(requested);
      if (id !== request.current) return;
      const servedNow = Date.parse(served?.now);
      setOffsetMs(Number.isNaN(servedNow) ? 0 : servedNow - Date.now());
      setTick(Date.now());
      setData(served ?? null);
      setError(null);
      loadedOnce.current = true;
    } catch (failure) {
      if (id !== request.current) return;
      // A date the server refuses (400) is not a place to stay: go to today, which reloads.
      if (requested != null && failure?.response?.status === 400) {
        resetToToday();
        return;
      }
      setError('Failed to load the day');
    }
  }, [resetToToday]);

  // The poll tick and the first load: skipped only for a tick on a screen already loaded, while the pane is hidden.
  const refresh = useCallback(async () => {
    if (loadedOnce.current && rootRef.current?.closest('[hidden]') != null) return;
    await load(null);
  }, [load, rootRef]);
  useVisiblePoll(refresh, DAY_POLL_MS, { enabled: following });

  // Any other day is read once, when it is chosen (the past is fixed and the future is only the schedule).
  useEffect(() => {
    // An inline async wrapper: the load sets state after its await, which is not a cascading render.
    (async () => {
      if (!following) await load(date);
    })();
  }, [following, date, load]);

  // Midnight: a reader following today moves on to the new day once the served day has ended.
  const dayEndMs = Date.parse(data?.dayEnd);
  const rolledOver = following && data?.isToday === true && !Number.isNaN(dayEndMs) && clockMs >= dayEndMs;
  useEffect(() => {
    if (rolledOver) refresh();
  }, [rolledOver, tick, refresh]);

  const goTo = useCallback((target) => {
    const next = target === today ? null : target;
    writeSchedulerQuery({ date: next });
    setDate(next);
  }, [today]);

  const fresh = data != null && (following ? data.isToday === true : data.date === date);
  const canPrev = viewDate > shiftDateStr(today, -DAY_RANGE_DAYS);
  const canNext = viewDate < shiftDateStr(today, DAY_RANGE_DAYS);

  return {
    date: viewDate,
    today,
    isToday: viewDate === today,
    nowMs: clockMs,
    data: fresh ? data : null,
    loading: !fresh && error == null,
    error,
    canPrev,
    canNext,
    goPrev: () => canPrev && goTo(shiftDateStr(viewDate, -1)),
    goNext: () => canNext && goTo(shiftDateStr(viewDate, 1)),
    goToday: () => goTo(today),
    reload: () => (following ? refresh() : load(date)),
  };
}
