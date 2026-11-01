import { useCallback, useEffect, useRef, useState } from 'react';
import { pauseJob, resumeJob, triggerJob } from '../api/schedulerApi.js';

/** How long Run now says "Started" before it is ready again. */
export const TRIGGER_CONFIRM_MS = 2000;

/** What a job's actions look like at rest. */
export const IDLE_ACTIONS = Object.freeze({ pending: false, triggered: false, busy: false, triggeredAt: 0, error: null });

/**
 * The Scheduler's Run now, Pause and Resume, with each job's in-flight, confirmed and failed state held <em>per job
 * key</em> in one place (the way {@code RunsView} holds {@code useStartRunState}), so leaving a job's panel mid-call
 * and coming back finds its button still guarded, and a failure that lands late is shown in the job it belongs to.
 *
 * <p>⚠️ Run now's guard is held from the click until the call settles, then until the "Started" confirmation clears,
 * and a second press in that time never reaches {@code POST .../trigger}: {@code triggerNow} queues an immediate run
 * for every call, so one double-click used to run a briefing or a tide refresh twice. The guard is a ref set in the
 * click itself, so it holds before React has re-rendered. Keep the {@code pending} update ordinary (not in a
 * transition).
 *
 * <p>⚠️ The confirmation's dismiss is an effect keyed on which jobs are confirmed, not a timer armed after the await:
 * {@code ManageView} renders this screen behind {@code activeTab === 'scheduler'}, so switching tab mid-request
 * unmounts it before any timer exists, and a timer armed afterwards would belong to nothing. An effect never runs after
 * unmount, and its cleanup clears the timers.
 *
 * @param {() => Promise<void>} reload re-reads the jobs after a pause or resume
 * @returns {{stateOf: (jobKey: string) => object, runNow: (job: object) => Promise<void>,
 *   pause: (job: object) => Promise<void>, resume: (job: object) => Promise<void>}}
 */
export default function useSchedulerActions(reload) {
  const [state, setState] = useState({});
  const stateRef = useRef(state);
  const guards = useRef(new Set());

  useEffect(() => {
    stateRef.current = state;
  });

  const patch = useCallback((jobKey, changes) => {
    setState((prev) => ({ ...prev, [jobKey]: { ...(prev[jobKey] ?? IDLE_ACTIONS), ...changes } }));
  }, []);

  const triggeredKeys = Object.keys(state).filter((key) => state[key].triggered).sort().join('|');
  useEffect(() => {
    if (!triggeredKeys) return undefined;
    const timers = triggeredKeys.split('|').map((jobKey) => {
      const left = Math.max(0, TRIGGER_CONFIRM_MS - (Date.now() - stateRef.current[jobKey].triggeredAt));
      return setTimeout(() => {
        guards.current.delete(`run:${jobKey}`);
        patch(jobKey, { triggered: false });
      }, left);
    });
    return () => timers.forEach(clearTimeout);
  }, [triggeredKeys, patch]);

  const runNow = useCallback(async (job) => {
    const guard = `run:${job.jobKey}`;
    if (guards.current.has(guard)) return;
    guards.current.add(guard);
    patch(job.jobKey, { pending: true, error: null });
    try {
      await triggerJob(job.jobKey);
      patch(job.jobKey, { pending: false, triggered: true, triggeredAt: Date.now() });
    } catch {
      guards.current.delete(guard);
      patch(job.jobKey, { pending: false, error: `Couldn't start ${job.displayName}.` });
    }
  }, [patch]);

  const change = useCallback(async (job, call, failure) => {
    const guard = `change:${job.jobKey}`;
    if (guards.current.has(guard)) return;
    guards.current.add(guard);
    patch(job.jobKey, { busy: true, error: null });
    try {
      await call(job.jobKey);
      await reload();
    } catch {
      patch(job.jobKey, { error: failure });
    } finally {
      guards.current.delete(guard);
      patch(job.jobKey, { busy: false });
    }
  }, [patch, reload]);

  const pause = useCallback(
    (job) => change(job, pauseJob, `Couldn't pause ${job.displayName}.`),
    [change],
  );
  const resume = useCallback(
    (job) => change(job, resumeJob, `Couldn't resume ${job.displayName}.`),
    [change],
  );
  const stateOf = useCallback((jobKey) => state[jobKey] ?? IDLE_ACTIONS, [state]);

  return { stateOf, runNow, pause, resume };
}
