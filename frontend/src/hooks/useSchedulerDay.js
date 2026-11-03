import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchSchedulerDay } from '../api/schedulerApi.js';
import { useVisiblePoll } from './useVisiblePoll.js';
import { ukDateStr, shiftDateStr } from '../utils/mapDates.js';
import { DAY_RANGE_DAYS, dayInRange } from '../utils/schedulerDay.js';
import { dropInvalidDate, isSchedulerHash, readSchedulerQuery, writeSchedulerQuery } from '../utils/schedulerQuery.js';

/** The note shown when midnight moves the oldest day out of the window and the reader is moved on. */
export const OUTSIDE_WINDOW_NOTE = 'That day is now outside the 7-day window. Showing the oldest day.';

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
 * @returns {object} {@code {date, today, isToday, nowMs, data, loading, error, notice, canPrev, canNext, goPrev, goNext,
 *   goToday, reload}}; {@code data} is null until the answer for the viewed day has arrived
 */
export default function useSchedulerDay(rootRef) {
  const [chosen, setDate] = useState(() => readSchedulerQuery().date);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [offsetMs, setOffsetMs] = useState(0);
  const [tick, setTick] = useState(() => Date.now());
  const [notice, setNotice] = useState(null);
  const request = useRef(0);
  const loadedOnce = useRef(false);

  const clockMs = tick + offsetMs;
  const today = ukDateStr(new Date(clockMs));

  // Midnight (state adjusted while rendering, the pattern React documents for state that follows a value): the window
  // moves a day. A reader on what was the oldest day is not silently sent to today: they move to the new oldest day,
  // with a note. A stale error belongs to the old day, so the loading text can show for the new one.
  const [seenToday, setSeenToday] = useState(today);
  if (seenToday !== today) {
    setSeenToday(today);
    setError(null);
    const oldest = shiftDateStr(today, -DAY_RANGE_DAYS);
    if (chosen != null && chosen < oldest && dayInRange(chosen, seenToday)) {
      setDate(oldest);
      setNotice(OUTSIDE_WINDOW_NOTE);
    }
  }
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
      // One read of the clock, so the offset and the tick agree to the millisecond.
      const readAt = Date.now();
      setOffsetMs(Number.isNaN(servedNow) ? 0 : servedNow - readAt);
      setTick(readAt);
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

  // The address follows a move made at midnight.
  useEffect(() => {
    if (notice != null && date != null) writeSchedulerQuery({ date });
  }, [notice, date]);

  const goTo = useCallback((target) => {
    const next = target === today ? null : target;
    writeSchedulerQuery({ date: next });
    setNotice(null);
    // The old day's error is not this day's: let its loading text show.
    setError(null);
    setDate(next);
  }, [today]);

  // A reader following today needs the answer for the UK day it is now, not the one that has just ended.
  const fresh = data != null && (following ? data.isToday === true && data.date === today : data.date === date);
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
    notice,
    canPrev,
    canNext,
    goPrev: () => canPrev && goTo(shiftDateStr(viewDate, -1)),
    goNext: () => canNext && goTo(shiftDateStr(viewDate, 1)),
    goToday: () => goTo(today),
    reload: () => load(date),
  };
}
