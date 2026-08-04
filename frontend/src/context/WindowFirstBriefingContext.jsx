import React, {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import { cacheGeneration, readSwrCache, writeSwrCache } from '../utils/swrCache.js';
import { isTravelDate } from '../utils/conversions.js';
import { isEventPast } from '../utils/briefingDisplay.js';
import { buildRailTiles } from '../utils/windowFirstRail.js';
import { buildWindowCards } from '../utils/windowFirstCards.js';

/** Matched to v1's. The payload regenerates every ~8–10h; polling faster only adds revalidations. */
const POLL_INTERVAL_MS = 10 * 60 * 1000;

/** How stale a cached briefing may be and still paint instantly on mount. Matched to v1's. */
const BRIEFING_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000;

/** The grid's cap on solar events, and therefore the outer bound on the rail's days. */
const MAX_VISIBLE_EVENTS = 6;

/** Shared empty map, so the context default and the pre-fetch state are identity-stable. */
const EMPTY_SCORES = new Map();

const WindowFirstBriefingContext = createContext({
  briefing: null,
  loading: false,
  railTiles: [],
  windowCards: [],
  evaluationScores: EMPTY_SCORES,
  todayStr: '',
  tomorrowStr: '',
});

/** Today's ISO date in the forecast's own timezone. */
function londonDate(offsetDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
}

/** Up to 6 upcoming (non-past) solar events [{date, targetType}], in payload order. */
function selectUpcomingEvents(briefingDays) {
  const events = [];
  for (const day of briefingDays || []) {
    for (const es of day.eventSummaries || []) {
      if (!isEventPast(es)) {
        events.push({ date: day.date, targetType: es.targetType });
        if (events.length === MAX_VISIBLE_EVENTS) return events;
      }
    }
  }
  return events;
}

/**
 * The window-first subtree's single briefing fetch, and the derivations its rail needs.
 *
 * <h2>Why the v2 arm owns its own fetch</h2>
 *
 * <p>Plan §4: the flag branches at App level and the v2 subtree owns its shell, its tab state and
 * one {@code /api/briefing} fetch, so {@code ViewToggle} and {@code DailyBriefing} are never
 * touched while both layouts are alive. {@code AuroraStatusContext} is the shape this follows —
 * with four differences it does not cover, each of them load-bearing:
 *
 * <ul>
 *   <li><b>SWR hydrate.</b> The briefing is the page; a cold mount that waits on the network shows
 *       an empty rail for as long as the round-trip takes. Seeded from the cache, capped at 12h.</li>
 *   <li><b>The cache key is role-scoped and deliberately the SAME key v1 uses.</b> A new namespace
 *       would be a third multi-megabyte entry against iOS Safari's ~5MB ceiling, which
 *       {@code swrCache.js} already says briefing and forecasts do not both fit under — and the
 *       two arms are a hard either/or at {@code App.jsx}, so they never write it at once. Sharing
 *       also means flipping the flag keeps the instant paint.</li>
 *   <li><b>{@code cacheGeneration()} is captured BEFORE the await, never after.</b>
 *       {@code AuthContext.logout} awaits a network round-trip, clears the cache, and only then
 *       nulls the token; a revalidation resolving inside that window would otherwise re-plant the
 *       previous account's briefing under its role key. An unmount-cancel flag does not close it.</li>
 *   <li><b>A null response is ignored rather than stored.</b> {@code /api/briefing} answers 204
 *       when nothing is cached server-side; treating that as data would blank a good rail and
 *       poison the SWR entry.</li>
 * </ul>
 *
 * <h2>What it deliberately does not fetch</h2>
 *
 * <p>The briefing, the travel-day ranges, and the batch evaluation scores. v1's cold mount fires
 * five requests plus one per day for astro; close-to-home, drive times and astro stay behind
 * because they feed the heatmap, the hover preview and the local block — none of which this arm
 * has yet. The evaluation scores are the exception, and the reason is on their own effect below:
 * they are not read by anything the rail draws, but everything downstream of the map handoff this
 * arm now wires needs them. Per-user reach arrives at P8 through its own endpoint and must never
 * join this payload: {@code /api/briefing} is ETag-revalidated, which persists the body to a
 * browser HTTP cache JavaScript cannot evict on logout.
 *
 * <p>Travel days are not optional garnish: without them an away day rolls up as "All poor", which
 * says the forecast was bad when in fact none was run.
 *
 * @param {{children: React.ReactNode}} props
 */
export function WindowFirstBriefingProvider({ children }) {
  const { role } = useAuth();
  const briefingCacheKey = `briefing:${role || 'anon'}`;

  // Lazy initialiser, not a call in the render body. React evaluates a plain `useState(x)` argument
  // on EVERY render and throws it away from the second one on — and this argument is a synchronous
  // localStorage read plus a JSON.parse of a payload `swrCache` documents at ~1.3 MB. The provider
  // re-renders often (a health SSE event every 30s, every poll, every focus, every map handoff),
  // so that is tens of milliseconds of main thread, repeatedly, for a value that is discarded.
  // Both other SWR consumers already use the lazy form.
  const [briefing, setBriefing] = useState(
    () => readSwrCache(briefingCacheKey, BRIEFING_CACHE_MAX_AGE_MS),
  );
  const [loading, setLoading] = useState(briefing === null);
  const [travelRanges, setTravelRanges] = useState([]);
  const [evaluationScores, setEvaluationScores] = useState(EMPTY_SCORES);
  const intervalRef = useRef(null);

  const fetchBriefing = useCallback(async () => {
    const gen = cacheGeneration(); // BEFORE the await — see the logout race in the class comment
    try {
      const data = await getDailyBriefing();
      if (data) {
        setBriefing(data);
        writeSwrCache(briefingCacheKey, data, gen);
      }
    } catch {
      // Transient — keep whatever is already on screen
    } finally {
      setLoading(false);
    }
  }, [briefingCacheKey]);

  useEffect(() => {
    (async () => { await fetchBriefing(); })();
    intervalRef.current = setInterval(fetchBriefing, POLL_INTERVAL_MS);
    function handleFocus() { fetchBriefing(); }
    window.addEventListener('focus', handleFocus);
    return () => {
      clearInterval(intervalRef.current);
      window.removeEventListener('focus', handleFocus);
    };
  }, [fetchBriefing]);

  useEffect(() => {
    fetchTravelDayRanges().then(setTravelRanges).catch(() => {});
  }, []);

  /**
   * Batch-scored ratings, keyed `regionName|date|targetType|locationName`.
   *
   * <p><b>Not for this arm's own rendering — for the map it hands off to.</b> P4c wires
   * `onShowOnMap` into the v2 arm for the first time, and everything downstream of that handoff
   * reads these scores: `buildMapOverlay` takes the overlay's narrative from the top location's
   * summary, and `MapView` decides which pins are even *visible* from the rating (`resolveStandDown`
   * treats a location with no rating and a triage row as stood down, and `forecast_evaluation` is
   * in practice a triage table — three quarters of its rows carry a null rating). Without them a
   * user clicking a tile that says "Worth it" gets an overlay with no prose over a map with almost
   * no pins.
   *
   * <p>So this is the one thing the "we only fetch the briefing and the travel days" rule gives up,
   * and it gives it up because this same change created the need. The key format is load-bearing:
   * `buildBriefingScoreIndex` splits on the last three `|` fields.
   */
  useEffect(() => {
    getAllEvaluationScores()
      .then((views) => {
        if (!views || views.length === 0) return;
        const next = new Map();
        for (const v of views) {
          if (!v.regionName || !v.locationName) continue;
          next.set(`${v.regionName}|${v.date}|${v.targetType}|${v.locationName}`, {
            locationName: v.locationName,
            rating: v.rating,
            fierySkyPotential: v.fierySkyPotential,
            goldenHourPotential: v.goldenHourPotential,
            summary: v.summary,
            triageReason: v.triageReason,
            triageMessage: v.triageMessage,
          });
        }
        setEvaluationScores(next);
      })
      .catch(() => {});
  }, []);

  const todayStr = londonDate(0);
  const tomorrowStr = londonDate(1);

  // Keyed on the briefing OBJECT, not on generatedAt. Keying on the timestamp looks like the
  // cheaper choice — a poll returning a byte-identical payload still hands back a fresh object —
  // but generatedAt is the BUILD time, and this payload is re-derived at SERVE time: the window
  // projection, the picks and each region's confidence are all computed per request. So two
  // responses can carry the same generatedAt and different content, and the memo would hold the
  // stale one. What it bought was not re-running a fold over four days and a handful of regions,
  // which is nothing; it gates no fetch. Correctness at no measurable cost.
  // One evaluation of the event window, shared by the rail and the cards. The past-event rule and
  // the six-event cap have to be applied ONCE: if the rail and the pane each ran their own, a
  // change to either could leave a card describing a day the rail does not draw, or the reverse —
  // and the pick badge and the rail's pick flag would then point at different sets.
  const { upcomingEvents, travelDayDates } = useMemo(() => {
    if (!briefing) return { upcomingEvents: [], travelDayDates: new Set() };
    const upcoming = selectUpcomingEvents(briefing.days);
    const dates = [...new Set(upcoming.map((e) => e.date))];
    return {
      upcomingEvents: upcoming,
      travelDayDates: new Set(dates.filter((d) => isTravelDate(d, travelRanges))),
    };
  }, [briefing, travelRanges]);

  const railTiles = useMemo(
    () => (briefing
      ? buildRailTiles(upcomingEvents, briefing.days, todayStr, tomorrowStr, travelDayDates)
      : []),
    [briefing, upcomingEvents, travelDayDates, todayStr, tomorrowStr],
  );

  const windowCards = useMemo(
    () => (briefing
      ? buildWindowCards(upcomingEvents, briefing.days, todayStr, tomorrowStr, travelDayDates)
      : []),
    [briefing, upcomingEvents, travelDayDates, todayStr, tomorrowStr],
  );

  const value = useMemo(
    () => ({ briefing, loading, railTiles, windowCards, evaluationScores, todayStr, tomorrowStr }),
    [briefing, loading, railTiles, windowCards, evaluationScores, todayStr, tomorrowStr],
  );

  return (
    <WindowFirstBriefingContext.Provider value={value}>
      {children}
    </WindowFirstBriefingContext.Provider>
  );
}

WindowFirstBriefingProvider.propTypes = {
  children: PropTypes.node,
};

/**
 * The window-first briefing, its loading flag and the day rail's tiles.
 *
 * @returns {{briefing: ?object, loading: boolean, railTiles: Array,
 *           todayStr: string, tomorrowStr: string}}
 */
export function useWindowFirstBriefing() {
  return useContext(WindowFirstBriefingContext);
}
