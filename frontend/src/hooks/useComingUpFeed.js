import { useCallback, useEffect, useRef, useState } from 'react';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The "Coming up" feed's fetch: eager (plan D13, P5), once per day, and held across tab switches.
 *
 * <h2>Eager now, and a recorded reversal (plan D13, P5)</h2>
 *
 * <p>This used to argue the opposite: "nothing above the tab bar reads the almanac, so fetching it
 * eagerly would make every reader of the Plan tab — which is every reader, since Plan is the
 * default tab — pay a request for a pane they may never open." The tab badge (plan D3/D4) breaks
 * that premise: it needs to know about arrivals whether or not the reader ever opens the pane, so
 * {@code WindowFirstShell} now calls this hook with {@code enabled} always true, firing the request
 * after first paint for every reader. The cost this used to refuse is now spent on purpose — a
 * count of dated arrivals is a decision-changing signal ("something new and rare is coming") where
 * a row count of what is already there was not (see {@code WindowFirstComingUp}'s own "still no
 * count on the tab" section, which draws exactly that line). The payload is a few KB and
 * ETag-revalidated, so the steady-state cost of paying it on every session is one small 304.
 *
 * <h2>Why it is not in {@code WindowFirstBriefingContext}</h2>
 *
 * <p>Eager is not the same question as "does this belong beside the briefing fetch". It does not,
 * for the reason below — unchanged by the badge, because the badge changed WHEN this fires, not
 * WHERE it is called from.
 *
 * <p>It is also a genuinely different contract, not another view of the briefing snapshot.
 * {@code docs/engineering/plan-panel-data-contracts.md} allows a panel its own endpoint when it
 * answers "a different question about differently owned data", and the second half of that test is
 * the one to be careful with: the almanac is <em>not</em> differently owned — like the briefing it
 * is shared, system-owned data with no notion of a caller. What separates them is that it is a
 * different <b>snapshot</b>: a 90-day horizon against the briefing's four dates, rebuilt daily
 * against the briefing's ~8-hourly cycle. The consistency argument that binds the Plan panes
 * together — two panels disagreeing about one location's rating — has nothing to bind here, because
 * this feed carries no locations and no ratings. So the usual reason to share a payload does not
 * apply, and the usual reason not to (personal data at rest in the browser's HTTP cache) does not
 * apply either: {@code /api/almanac} is already on {@code HttpCachingConfig}'s revalidation
 * whitelist precisely because it carries nothing per-user.
 *
 * <h2>Eager, and latched</h2>
 *
 * <p>{@code enabled} is kept as a parameter — the hook itself stays generic, the same shape
 * {@code useAuroraViewline} uses for a fetch gated on its surface being on screen — but
 * {@code WindowFirstShell} now passes {@code true} unconditionally rather than the tab's selected
 * state, per the reversal above. The latch is what makes an eager fetch cheap rather than merely
 * early: without it, a feed fetched once per mount would still be fine (this hook mounts once, with
 * the shell), but the latch is also what makes returning to a closed-then-reopened tab free, and
 * what {@code ManageView}'s remount-every-time behaviour would have cost here had this hook lived
 * inside the pane instead of the shell.
 *
 * <p><b>The latch key is the date, not a boolean.</b> A plain "have I fetched" flag would leave a
 * session open past midnight showing yesterday's feed with a row still reading "Today". Keying it
 * on {@code todayStr} means the date roll invalidates it and nothing else does.
 *
 * <p>{@code AlmanacService} builds the feed on the same calendar this latch keys on —
 * {@code Europe/London}, via {@code ForecastHorizon.today} — so there is no cross-calendar
 * disagreement to reconcile. London is still the right key regardless of which calendar the
 * backend happens to build on: it is the day the rows are described against, and the lead word is
 * the thing a reader checks.
 *
 * <p>The hook is called from the shell rather than from the pane, so switching tabs does not
 * unmount it. That removes the in-flight-unmount case entirely rather than defending against it,
 * and it is why there is no cancellation flag here.
 *
 * <p>No SWR cache. The endpoint is ETag-revalidated and the payload is kilobytes, so a repeat load
 * is already a 304 the browser answers below the XHR layer — a second copy in localStorage would
 * buy a pre-network paint on a surface that was not on screen a moment ago, at the cost of a third
 * namespace against iOS Safari's ~5 MB ceiling that {@code swrCache.js} already rations.
 *
 * @param {boolean} enabled  whether the fetch may run — {@code WindowFirstShell} passes `true`
 *                           unconditionally (plan D13); kept as a parameter for the hook's own
 *                           testability and because it stays a generally useful gate
 * @param {string}  todayStr the reader's today, `YYYY-MM-DD`; the latch key
 * @returns {{status: string, events: ?object, retry: function}} `status` is
 *          `idle` before the fetch has fired, then `loading`, then `ready` or `error`; `events` is
 *          the wrapped {@code ComingUpResponse} once it has arrived
 */
export default function useComingUpFeed(enabled, todayStr) {
  const [status, setStatus] = useState('idle');
  const [events, setEvents] = useState(null);
  const fetchedForRef = useRef(null);
  /** Monotonic id of the in-flight request, so a late response cannot overwrite a newer one. */
  const runIdRef = useRef(0);
  const [attempt, setAttempt] = useState(0);

  /** Clears the latch and re-runs the effect. The error state's only affordance. */
  const retry = useCallback(() => {
    fetchedForRef.current = null;
    setAttempt((n) => n + 1);
  }, []);

  useEffect(() => {
    if (!enabled) return;
    if (fetchedForRef.current === todayStr) return;
    fetchedForRef.current = todayStr;
    // Which request this is. Two can only ever overlap one way — the date rolls past midnight while
    // the tab is open and the latch reopens on a request that has not come back — and responses
    // carry no ordering guarantee, so the older one could land last and overwrite the newer feed
    // with yesterday's. Comparing the counter on the way out makes the last WRITE the last request
    // rather than the last response.
    //
    // A monotonic counter, NOT `attempt`: `attempt` only moves on a retry, so on a date roll both
    // requests would carry the same id and neither would ever look stale — a guard that cannot
    // fire in the one case it exists for.
    runIdRef.current += 1;
    const runId = runIdRef.current;
    const isStale = () => runIdRef.current !== runId;
    // The inline async IIFE is this codebase's answer to `react-hooks/set-state-in-effect`: the
    // state changes happen inside the async body rather than in the effect's own synchronous run.
    (async () => {
      setStatus('loading');
      try {
        const data = await getAlmanac();
        if (isStale()) return;
        // The wrapped ComingUpResponse, always — the endpoint has no 204 and no role variance. A
        // non-object would mean something upstream changed shape, and rendering an empty feed is
        // better than crashing the pane. `typeof [] === 'object'` in JS, so the array check is
        // its own clause — without it a reverted/mixed-version backend still serving the old bare
        // array would pass straight through as `events` instead of degrading.
        setEvents(data && typeof data === 'object' && !Array.isArray(data)
          ? data : { entries: [] });
        setStatus('ready');
      } catch {
        if (isStale()) return;
        // The latch stays SET on failure so a re-render does not retry in a loop; `retry` is the
        // only way back, and it is a button the reader presses.
        setStatus('error');
      }
    })();
  }, [enabled, todayStr, attempt]);

  return { status, events, retry };
}
