import { useCallback, useEffect, useRef, useState } from 'react';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The "Coming up" feed's fetch: lazy, once per day, and held across tab switches.
 *
 * <h2>Why it is not in {@code WindowFirstBriefingContext}</h2>
 *
 * <p>The provider's own class comment is an explicit inventory of what the arm does and does not
 * fetch on mount, and the rule it states is that a request joins it only when something the arm
 * always draws needs the answer. Nothing above the tab bar reads the almanac. Putting it there
 * would make every reader of the Plan tab — which is every reader, since Plan is the default tab —
 * pay a request for a pane they may never open.
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
 * <h2>Lazy, and latched</h2>
 *
 * <p>{@code enabled} is the tab being open — the shape {@code useAuroraViewline} already uses for a
 * fetch that must not run until its surface is on screen. The latch on top of it is new to this
 * codebase and is what makes returning to the tab free: {@code ManageView} re-mounts its sub-views
 * on every tab change and so refetches every time, which is the behaviour to avoid on a feed that
 * the backend itself rebuilds only once a UTC day.
 *
 * <p><b>The latch key is the date, not a boolean.</b> A plain "have I fetched" flag would leave a
 * session open past midnight showing yesterday's feed with a row still reading "Today". Keying it
 * on {@code todayStr} means the date roll invalidates it and nothing else does.
 *
 * <p>⚠️ <b>That date is Europe/London and the backend builds the feed on a UTC date, and the two
 * are deliberately not reconciled here.</b> {@code AlmanacService} uses
 * {@code LocalDate.now(ZoneOffset.UTC)}, so during BST they disagree between midnight and 01:00
 * London. Two bounded consequences, both accepted. The latch reopens at the London roll and the
 * refetch returns the same UTC-day feed, costing one request; and a session left open across that
 * hour holds a feed built for the previous UTC day until the next London roll, whose only visible
 * effect is that an entry which ended on the UTC day can still be listed — where it correctly reads
 * "Passed", because by the reader's own clock it has. London is the right key regardless: it is the
 * day the rows are described against, and the lead word is the thing a reader checks.
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
 * @param {boolean} enabled  whether the Coming up tab is the selected one
 * @param {string}  todayStr the reader's today, `YYYY-MM-DD`; the latch key
 * @returns {{status: string, events: ?Array, retry: function}} `status` is
 *          `idle` before the first open, then `loading`, then `ready` or `error`
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
        // An array, always — the endpoint has no 204 and no role variance. A non-array would mean
        // something upstream changed shape, and rendering `[]` is better than crashing the pane.
        setEvents(Array.isArray(data) ? data : []);
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
