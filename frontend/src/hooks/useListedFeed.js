import { useMemo } from 'react';
import { useWarnings } from '../context/WarningsContext.jsx';
import { reconcileFeed } from '../utils/comingUpWarnings.js';
import { deriveBadge, selectSinceEntry } from '../utils/comingUpArrivals.js';
import { appNow } from '../utils/rewind.js';

/**
 * What Further ahead lists, and what is new in it: the ONE place both are derived (one-screen-plan.md O2, D5, D6).
 *
 * <p>The wire feed is first reconciled against the polled Met Office warnings (a withdrawn warning is not an entry),
 * then the entries This week already draws as lanes ({@code graduatedIds}) are taken out. The NEW badge and the
 * since-line read that LISTED set, ignoring the chip filter ("what is new anywhere in what is listed", never "in the
 * selected family"). The "Further ahead ↓" jump and the section's heading both read this result, so they cannot
 * disagree and neither counts an entry the section does not show.
 *
 * @param {?object} wireEvents the served feed ({@code entries}, {@code counts}, {@code bands})
 * @param {?Set<string>} graduatedIds ids of the entries a lane carries
 * @param {?string} lastSeenDate the reader's {@code comingUpLastSeenDate}
 * @returns {{events: ?object, listedEntries: (Array|undefined), badge: ?object, sinceEntry: ?object}}
 */
export default function useListedFeed(wireEvents, graduatedIds, lastSeenDate) {
  const served = useWarnings();
  const events = useMemo(
    () => reconcileFeed(wireEvents, served, appNow().getTime()),
    [wireEvents, served],
  );
  const listedEntries = useMemo(() => {
    const all = events?.entries;
    if (!Array.isArray(all) || !graduatedIds || graduatedIds.size === 0) return all;
    return all.filter((entry) => !graduatedIds.has(entry.id));
  }, [events, graduatedIds]);
  const badge = useMemo(
    () => deriveBadge(listedEntries, events?.bands, lastSeenDate),
    [listedEntries, events, lastSeenDate],
  );
  const sinceEntry = useMemo(
    () => selectSinceEntry(listedEntries, events?.bands, lastSeenDate),
    [listedEntries, events, lastSeenDate],
  );
  return { events, listedEntries, badge, sinceEntry };
}
