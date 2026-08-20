import React, {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegionDriveTimes, fetchRegions } from '../api/regionApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import usePlanOrder from '../hooks/usePlanOrder.js';
import useRatingLens from '../hooks/useRatingLens.js';
import useReachLens from '../hooks/useReachLens.js';
import { cacheGeneration, readSwrCache, writeSwrCache } from '../utils/swrCache.js';
import { isTravelDate } from '../utils/conversions.js';
import { isEventPast } from '../utils/briefingDisplay.js';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';
import { buildPaneItems } from '../utils/windowFirstAway.js';
import { buildPromotedStrip } from '../utils/windowFirstPromoted.js';
import { buildBriefingScoreIndex } from '../utils/briefingScoreIndex.js';
import { buildHeatPointSets, buildHeatSpots } from '../utils/heatSpots.js';
import { buildRegionSeries } from '../utils/windowFirstRegions.js';
import { AWAY_TIER_ID, originReachMap, toOrigin } from '../utils/planOrigin.js';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';

/** Matched to v1's. The payload regenerates every ~8–10h; polling faster only adds revalidations. */
const POLL_INTERVAL_MS = 10 * 60 * 1000;

/** How stale a cached briefing may be and still paint instantly on mount. Matched to v1's. */
const BRIEFING_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000;

/** Shared empty map, so the context default and the pre-fetch state are identity-stable. */
const EMPTY_SCORES = new Map();

/** Same, for reach — an identity-stable empty means the card memo does not re-run on mount. */
const EMPTY_REACH = new Map();

/**
 * Same again. It matters for {@code locations}, which is a default PARAMETER and so is
 * re-evaluated on every render — a literal {@code []} there would hand the heat join a new
 * identity on every health SSE tick and rebuild the whole catalogue. {@code scoreRows} shares it
 * for consistency rather than necessity: a {@code useState} initialiser is evaluated once and
 * its identity is stable either way.
 */
const EMPTY_ARRAY = [];

/**
 * The heat point sets' empty value, which is a MAP — the shape `buildHeatPointSets` returns and the
 * shape `WindowFirstHeatStrip`'s PropTypes require. It was `EMPTY_ARRAY`, which every consumer
 * survived only because they reach for it as `pointSets?.get?.(key)`; a consumer that iterated it,
 * or a PropTypes check on a provider-less render, would have found an array under a Map's name.
 */
const EMPTY_POINT_SETS = new Map();

/**
 * The region series' empty value — its own constant rather than a share of the one above, because
 * the two are different shapes ({@code Map<string, Array>} against
 * {@code Map<string, Map<string, ?number>>}) and a shared empty is exactly how two shapes end up
 * documented as one.
 */
const EMPTY_REGION_SERIES = new Map();

/**
 * The region list and the shared drive-time matrix before their own fetches land.
 *
 * <p>Their own constants rather than a share of {@code EMPTY_ARRAY}: the matrix is a plain object
 * keyed by region id, and a shared empty is exactly how two shapes end up documented as one.
 */
const EMPTY_REGIONS = [];
const EMPTY_MATRIX = {};

const WindowFirstBriefingContext = createContext({
  briefing: null,
  loading: false,
  heatStripCards: [],
  windowCards: [],
  paneItems: [],
  promotedStrip: null,
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: EMPTY_SCORES,
  heatSpots: EMPTY_ARRAY,
  heatPointSets: EMPTY_POINT_SETS,
  regionSeries: EMPTY_REGION_SERIES,
  reachById: EMPTY_REACH,
  effectiveReachById: EMPTY_REACH,
  origin: null,
  setOrigin: () => {},
  regions: EMPTY_REGIONS,
  todayStr: '',
  tomorrowStr: '',
  isPro: false,
  isLiteUser: false,
});

/**
 * The events this arm renders: the backend's own ordered, capped list, minus any that have
 * elapsed since the payload was built.
 *
 * <p><b>The list is the backend's answer and this function does not second-guess it.</b> Ordering
 * and the six-event cap left the client at Phase 3 (`PlanRenderLimits.MAX_VISIBLE_EVENTS`), because
 * the backend scopes the BEST/ALSO picks to exactly this set — and while the two were derived
 * independently a pick could name a window with no card. Re-applying a cap here would be a second
 * opinion about the same question, which is the defect, not the fix.
 *
 * <p><b>What remains is a data-freshness defence, not an aggregation.</b> An SWR-cached payload
 * paints from up to 12h old, over which an event named in it will have passed; the filter drops
 * those. It is deliberately the same `isEventPast` the rest of the app uses, so a stale-cache
 * decision cannot diverge from the rest of the client's idea of "past". Against a fresh payload it
 * is a no-op — the backend applied its own pastness, against a UTC instant, moments earlier.
 *
 * <p><b>The degrade is a walk of the days, uncapped.</b> `renderedEvents` is absent from a payload
 * cached before the field existed and from one served by an older backend mid-deploy; returning
 * nothing there would blank the pane. Uncapped on purpose — the cap is the backend's to own, and
 * restoring a client-side copy for the degrade would reintroduce exactly the constant this phase
 * deleted. The next fetch fixes it within seconds. Absent and empty are different answers, and the
 * payload contract keeps them apart: empty means the projector ran and found no live window.
 *
 * @param {?object} briefing the briefing payload
 * @returns {Array} [{date, targetType}], in render order
 */
function selectUpcomingEvents(briefing) {
  const days = briefing?.days || [];
  const summaryFor = (date, targetType) => days
    .find((d) => d.date === date)?.eventSummaries
    ?.find((es) => es.targetType === targetType);
  const listed = briefing?.renderedEvents
    ?? days.flatMap((d) => (d.eventSummaries || [])
      .map((es) => ({ date: d.date, targetType: es.targetType })));

  return listed.filter(({ date, targetType }) => {
    const es = summaryFor(date, targetType);
    // A named event with no summary is a payload that contradicts itself. Drop it rather than let
    // a card, a tile or a pick be built from `undefined` further down.
    // `date` is passed for the same reason as v1's copy in DailyBriefing.jsx: a day whose slots the
    // coverage filter withdrew carries no time inside its regions, and the date is then the only
    // evidence the event has happened.
    return es != null && !isEventPast(es, date);
  });
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
 * @param {object} props
 * @param {React.ReactNode} props.children the v2 subtree
 * @param {number} [props.homeSettingsVersion] bumped by {@code App} whenever the user saves a home
 *        postcode, a radius or a drive-time recalculation. It is the reach fetch's only
 *        invalidation signal — see the effect below.
 */
export function WindowFirstBriefingProvider({
  children, homeSettingsVersion, locations = EMPTY_ARRAY,
}) {
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
  // The same response, kept unreduced. See the fetch effect below for why both shapes exist.
  // Deliberately NOT on the context value: nothing outside this file needs a row yet. P5's
  // leave-by and P8's four-day sheet do — their "why" prose is `LocationEvaluationView.summary`
  // — and they should export THIS rather than read `scoreIndex`, which is name-keyed and drops
  // every row missing a region or location name, i.e. a different population from the one the
  // heat join saw.
  const [scoreRows, setScoreRows] = useState(EMPTY_ARRAY);

  const [reachById, setReachById] = useState(EMPTY_REACH);
  const [regions, setRegions] = useState(EMPTY_REGIONS);
  const [regionMatrix, setRegionMatrix] = useState(EMPTY_MATRIX);
  /**
   * The frame of reference the whole Plan tab is written from — null is home (plan §4.8).
   *
   * <p><b>In memory only, deliberately.</b> An origin is a question a reader is asking right now
   * ("what is it doing near Keswick this week"), not a preference like the rating floor — and the
   * reach tier it drops is stamped with today's date, so a persisted origin would restore a frame
   * without restoring the lens that framed it. A reload returns home, which is the state every
   * other surface in the app already assumes.
   */
  const [origin, setOriginState] = useState(null);
  // Three states, not two: `undefined` is "not answered yet or the request failed", and it renders
  // NOTHING. Collapsing it onto null would tell a user who has a home postcode that they have not
  // set one, on nothing more than a dropped request — plan §2.5 forbids a second source of truth
  // for exactly this reason, and a wrong answer is worse than silence.
  const [homePlace, setHomePlace] = useState(undefined);
  const intervalRef = useRef(null);

  /**
   * The batch ratings, refreshed on the SAME beat as the briefing.
   *
   * <p>⚠️ <b>This used to be a mount-only {@code useEffect(..., [])}, and that froze the heat
   * field for the life of the tab.</b> The briefing polls every ten minutes and again on focus, so
   * verdicts, {@code bestRating}, the planner grid and the hot topics all kept moving while the
   * rows the FIELD is drawn from stayed at whatever was true when the tab was opened. Leave the
   * Plan tab open overnight and the overnight batch writes T+2/T+3 ratings that this session never
   * sees: the cards say {@code best spot 5★} and the thumbnails stay blank, for hours, with a hard
   * reload as the only cure.
   *
   * <p>Observed in production on 2026-08-20 and confirmed by elimination — the endpoint returned
   * 163 rated rows for the Saturday sunrise the strip was drawing empty, the window keys matched,
   * and replaying the join against the live payloads produced 151 points. Nothing was wrong with
   * the data or the join; the session was simply holding yesterday's copy of it. A hard reload
   * fixed it instantly, which is the tell.
   *
   * <p>Refreshed through the same {@code refresh} as the briefing rather than through a poll of
   * its own, so the two payloads a single screen joins can never be more than one cycle apart.
   * Both are ETag-revalidated, so an unchanged cycle costs a 304 and no body.
   *
   * <p>An empty or failed response leaves the previous rows in place — same rule as
   * {@code fetchBriefing}'s catch: a dropped request is not evidence that the ratings went away,
   * and blanking the field on one would be a worse lie than a slightly stale one.
   */
  const fetchScores = useCallback(() => {
    getAllEvaluationScores()
      .then((views) => {
        if (!views || views.length === 0) return;
        setScoreRows(views);
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
    (async () => { await fetchBriefing(); await fetchScores(); })();
    function refresh() { fetchBriefing(); fetchScores(); }
    intervalRef.current = setInterval(refresh, POLL_INTERVAL_MS);
    window.addEventListener('focus', refresh);
    return () => {
      clearInterval(intervalRef.current);
      window.removeEventListener('focus', refresh);
    };
  }, [fetchBriefing, fetchScores]);

  useEffect(() => {
    fetchTravelDayRanges().then(setTravelRanges).catch(() => {});
  }, []);

  /**
   * Per-user reach, keyed by location id — the other half of the spot strip's two-contract join.
   *
   * <p><b>Never written to the SWR cache, and that is the whole point of the separate endpoint.</b>
   * Plan §2.2 keeps drive times off `/api/briefing` because that path is ETag-revalidated, which
   * persists the body to a browser HTTP cache JavaScript cannot evict on logout. Parking the same
   * data in localStorage instead would re-create the problem in a store this app CAN clear but
   * currently would not: `swrCache` is keyed by role, not by user, so two accounts on one device
   * share a key. It is a small payload on a page that already fetches four things; fetch it.
   *
   * <p>A rejection is swallowed, leaving the map empty. That is the same state as a user with no
   * home postcode, which is the normal first run — so the failure mode is a strip with no reach
   * lines rather than a strip with none, and the footer's own sentence stops naming drive time.
   *
   * <p><b>{@code homeSettingsVersion}, not a bare {@code []}.</b> This app has already paid for that
   * mistake once: {@code DailyBriefing}'s close-to-home fetch shipped with an empty dep list and
   * "a user who widened their radius saw the block keep its old contents until a full reload — the
   * setting appeared to do nothing". The shape here is worse, because the provider is mounted for
   * the whole life of the v2 arm and {@code UserSettingsModal} is its SIBLING in {@code App} — so
   * saving a postcode re-renders but never remounts, and the first-run user who sets one would
   * watch every reach line stay absent indefinitely. The counter {@code App} already keeps for
   * exactly this is the signal; it also gives a boot-time failure a way back, which the swallowed
   * rejection above otherwise makes permanent for the session.
   */
  useEffect(() => {
    getReach()
      .then((entries) => {
        if (!entries || entries.length === 0) return;
        const next = new Map();
        for (const entry of entries) {
          if (entry?.locationId == null) continue;
          next.set(entry.locationId, {
            driveMinutes: entry.driveMinutes ?? null,
            distanceMiles: entry.distanceMiles ?? null,
          });
        }
        setReachById(next);
      })
      .catch(() => {});
  }, [homeSettingsVersion]);

  /**
   * The regions and the shared region-base drive-time matrix — the origin's two inputs.
   *
   * <p><b>Both are shared, user-independent data</b>, which is the whole reason the origin can
   * exist at all: "how far is this from Keswick" is the same answer for every reader, so it may
   * ride a cacheable endpoint, where the reach map above may not. They are kept apart on this side
   * of the wire too — see {@code planOrigin.js}.
   *
   * <p>Fetched once per mount rather than on the poll: a region's base is admin-managed and the
   * matrix is rewritten by a nightly job, so neither can move inside a session in a way the reader
   * would notice. A rejection leaves both empty, which reads as "no region has a base": search
   * still finds nothing to plan from, the chip stays on home, and nothing claims a drive it does
   * not have.
   */
  useEffect(() => {
    fetchRegions().then((rows) => setRegions(rows || EMPTY_REGIONS)).catch(() => {});
    fetchRegionDriveTimes().then((rows) => setRegionMatrix(rows || EMPTY_MATRIX)).catch(() => {});
  }, []);

  /**
   * The user's home, for the rail footer's prompt.
   *
   * <p>Plan §2.5 is explicit that this must not be a new flag or a 204 on the reach endpoint:
   * {@code GET /api/user/settings} already carries {@code homePostcode} and
   * {@code driveTimesCalculatedAt}, and a second source of truth can disagree with the first. So
   * the same response the settings modal reads answers it, and the place name is preferred over the
   * postcode because the design's slot reads {@code Home · <place>}.
   *
   * <p>Same invalidation as the reach fetch above, and for the same reason — saving a postcode
   * re-renders this provider without remounting it, so an empty dep list would leave a first-run
   * user reading "Home not set" for the rest of the session immediately after setting one.
   */
  useEffect(() => {
    getSettings()
      .then((settings) => setHomePlace(settings?.homePlaceName || settings?.homePostcode || null))
      .catch(() => setHomePlace(undefined));
  }, [homeSettingsVersion]);

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
   *
   * <p><b>The raw rows are kept as well, and the two shapes are not redundant.</b> The map above is
   * keyed by location NAME and drops every row whose region or name is missing — fine for the map
   * handoff, which looks a marker up by the name it is drawing. The heat join cannot use it: a name
   * is a display string an admin can edit, so the join is `locationId`-first with the name only as
   * a fallback (`heatSpots.js`), and the id is exactly what this key format discards. Keeping the
   * response rather than adding a second id-keyed index means one contract, one fetch, and no
   * chance of the two indexes disagreeing about which rows the response contained.
   */


  // UK calendar, not the browser's — every date on the wire is keyed to the backend's
  // `ForecastHorizon.today`. `ukDateStrOffset` steps the UK date STRING; the local copy this
  // replaced stepped the browser's calendar and only then formatted in London, so its offset was a
  // hybrid of two calendars that collapsed onto today across an autumn DST boundary.
  const todayStr = ukDateStr();
  const tomorrowStr = ukDateStrOffset(1);

  // The lens lives here rather than in the shell because the cards are built here: the gate has to
  // run inside the same memo that derives them, or the shell would filter a list the provider had
  // already counted. `role` is already in this component for the SWR cache key, so gating costs no
  // new dependency — and P7's rule that no `role` reaches the card subtree is untouched, because
  // what the cards receive is a threshold, not a role.
  // The origin supplies the DEFAULT tier rather than selecting one — see `useReachLens` for the two
  // review findings that rules out (a localStorage write that outlives the in-memory origin, and a
  // far mark measuring against a number the bar's own reset button contradicts).
  const reachLens = useReachLens(
    todayStr, role === 'LITE_USER', origin ? AWAY_TIER_ID : null,
  );
  const defaultLimitMinutes = reachLens.defaultTier.limitMinutes;
  // The bar's second axis, and it lives beside the first for the same reason: the cards are built
  // here, so both gates have to run inside the memo that derives them or the shell would filter a
  // list the provider had already counted. It takes no role — the floor is not a PRO control (see
  // the hook), so nothing new crosses plan §5c's line.
  const ratingLens = useRatingLens();

  // The pane's third axis. It lives here beside the other two because the lens bar renders all
  // three and the shell orders the pane by this one — a value read in two places is derived in one.
  // Unlike reach and rating it gates nothing: `paneItems` below stays chronological, and the
  // ordering is applied by the shell at render (see `orderPaneItems`), so `buildPromotedStrip`
  // keeps reading the date order its "is the promoted card the very next item" test depends on.
  const orderLens = usePlanOrder();

  /**
   * The reach map the page actually plans from — <b>overwritten</b> when the origin moves, never
   * extended.
   *
   * <p>P5 recorded this as a requirement: {@code spot.driveMinutes} has exactly one producer, so
   * an away figure has to replace the home one rather than sit beside it as a second field. Both
   * the reach line and the leave-by line read that one field, so a second producer would let one
   * card describe two different journeys with no test able to fail.
   *
   * <p>Null origin gives back the per-user map untouched, so the home path is byte-identical.
   */
  const effectiveReachById = useMemo(
    () => originReachMap(origin, regionMatrix) ?? reachById,
    [origin, regionMatrix, reachById],
  );

  /**
   * Moves the origin.
   *
   * <p><b>It touches the reach lens through nothing but the default it supplies</b> (see the
   * {@code useReachLens} call above). Two consequences, both deliberate: nothing is persisted, so a
   * reload — which returns the origin home — returns the lens with it; and the far mark, the reset
   * button and the readout all name one number, because the origin moved the default rather than
   * reaching over to move the selection.
   *
   * <p>A LITE reader is pinned to "Any" and the default gates nothing for them either way, so the
   * move changes their scope and nothing else — which is correct.
   *
   * <p>Takes a region RECORD, not a descriptor, so the caller cannot construct an origin out of a
   * baseless region: {@code toOrigin} returns null for one and the call becomes a return home.
   */
  const setOrigin = useCallback((region) => {
    setOriginState(region ? toOrigin(region) : null);
  }, []);

  // Keyed on the briefing OBJECT, not on generatedAt. Keying on the timestamp looks like the
  // cheaper choice — a poll returning a byte-identical payload still hands back a fresh object —
  // but generatedAt is the BUILD time, and this payload is re-derived at SERVE time: the window
  // projection, the picks and each region's confidence are all computed per request. So two
  // responses can carry the same generatedAt and different content, and the memo would hold the
  // stale one. What it bought was not re-running a fold over four days and a handful of regions,
  // which is nothing; it gates no fetch. Correctness at no measurable cost.
  // One evaluation of the event window, shared by the rail and the cards. The stale-cache pastness
  // guard has to be applied ONCE: if the rail and the pane each ran their own, a change to either
  // could leave a card describing a day the rail does not draw, or the reverse — and the pick badge
  // and the rail's pick flag would then point at different sets. The ordering and the cap are no
  // longer applied here at all; they arrive on `briefing.renderedEvents`.
  const { upcomingEvents, travelDayDates } = useMemo(() => {
    if (!briefing) return { upcomingEvents: [], travelDayDates: new Set() };
    const upcoming = selectUpcomingEvents(briefing);
    const dates = [...new Set(upcoming.map((e) => e.date))];
    return {
      upcomingEvents: upcoming,
      travelDayDates: new Set(dates.filter((d) => isTravelDate(d, travelRanges))),
    };
  }, [briefing, travelRanges]);

  const windowCards = useMemo(
    () => (briefing
      ? buildWindowCards(
        upcomingEvents, briefing.days, todayStr, tomorrowStr, travelDayDates, effectiveReachById,
        {
          limitMinutes: reachLens.tier.limitMinutes,
          // The lens's own default, which the origin has already moved — so the gate's starting
          // point and the far mark's meaning stay one judgement (`reachLens.js`'s module rule).
          defaultLimitMinutes,
          tierId: reachLens.tierId,
          minRating: ratingLens.minRating,
          floorId: ratingLens.floorId,
          // The origin's scope, applied before either gate: away, a window is about ONE region,
          // and the lens then chooses within it. Null is home and changes nothing.
          origin,
        },
      )
      : []),
    [briefing, upcomingEvents, travelDayDates, todayStr, tomorrowStr, effectiveReachById,
      reachLens.tier.limitMinutes, reachLens.tierId, defaultLimitMinutes,
      ratingLens.minRating, ratingLens.floorId, origin],
  );

  /**
   * The heat strip's six thumbnails, chronological and travel days included.
   *
   * <p>Derived after the cards and FROM them, because every word a thumbnail prints about a window
   * is already on that window's card — deriving them independently would give the strip and the
   * row it opens two chances to disagree about one night. Away days have no card and keep their
   * slot here, which is why the event list is the spine rather than the card list.
   *
   * <p>It replaces {@code railTiles} outright (plan D1, owner-confirmed 2026-08-18): the strip is
   * the rail's job done at the window list's own grain.
   */
  const heatStripCards = useMemo(
    () => (briefing
      ? buildHeatStripCards(
        upcomingEvents, windowCards, travelDayDates, briefing.days, todayStr, tomorrowStr,
      )
      : []),
    [briefing, upcomingEvents, windowCards, travelDayDates, todayStr, tomorrowStr],
  );

  // The pane's ordered contents — the cards with the away days folded back in where they fall. It
  // is derived HERE rather than in the shell for the same reason the cards are: the away rows and
  // the cards are two views of one event array, and a second evaluation of the travel filter is a
  // second chance for them to disagree about which days exist.
  const paneItems = useMemo(
    () => buildPaneItems(upcomingEvents, windowCards, travelDayDates, travelRanges),
    [upcomingEvents, windowCards, travelDayDates, travelRanges],
  );

  // The page's ONE promoted strip, derived here rather than in the shell because "at most one"
  // (§2.6) is a statement about the whole pane, and because the window card is explicitly forbidden
  // from promoting anything into a strip. Deriving it from `paneItems` rather than from
  // `windowCards` gives it the pane's own date order for the rarity tie-break, and lets it see
  // whether the promoted card is the very next item — the one case where its route into the list
  // would be a control with no visible effect.
  const promotedStrip = useMemo(() => buildPromotedStrip(paneItems), [paneItems]);

  // Booleans, never the role. Plan §5c: `role` enters this arm at the provider and stops there, and
  // what anything below receives is a decision already made — a threshold for the lens, a boolean
  // for the two components behind the doors that were built for the v1 arm and take one.
  const isPro = role === 'PRO_USER' || role === 'ADMIN';
  const isLiteUser = role === 'LITE_USER';

  // Built once for the page rather than per strip: the spot peek looks a slot up by
  // (locationName, date, targetType), and `evaluationScores` is keyed with the region name in
  // front, so every lookup would otherwise be a linear scan of the whole map. Six window cards ×
  // up to seven spots each is enough for that to matter, and the index is what
  // `buildBriefingScoreIndex` exists for.
  const scoreIndex = useMemo(() => buildBriefingScoreIndex(evaluationScores), [evaluationScores]);

  /**
   * The heat field's catalogue: one spot per joinable location, carrying one score per rendered
   * window. Derived HERE for the same reason the cards are — the strip, the row maps and the Map
   * tab are three views of one join, and three copies of it are three chances to disagree about
   * which locations exist.
   *
   * <p>Memoised on its real inputs (plan §5.4). The provider re-renders on every health SSE tick,
   * every poll and every focus event, and this walks the whole roster against the whole score
   * response; the prototype's unmemoised equivalent cost seconds per repaint.
   */
  const heatSpotList = useMemo(
    () => buildHeatSpots(locations, scoreRows, upcomingEvents),
    [locations, scoreRows, upcomingEvents],
  );

  /**
   * The kernel-ready points, keyed {@code date:targetType} — the same windows, each with its
   * unscored locations withdrawn. Separate from the catalogue because they are different shapes
   * for different consumers (P3's labels and P4's dark-sky filter want every spot; only the
   * kernel wants points), not because they change on different beats — they recompute together.
   */
  const heatPointSets = useMemo(
    () => buildHeatPointSets(heatSpotList, upcomingEvents),
    [heatSpotList, upcomingEvents],
  );

  /**
   * Each region's served mean across every rendered window, for the open row's six-dot strip.
   *
   * <p>Built here rather than per card because it is a CROSS-window quantity: a card holds one
   * event summary and the band's dots are about all six. Deriving it in the card would mean handing
   * every card the whole day list so that one open row could walk it — six walks for one answer,
   * rebuilt on every poll.
   *
   * <p>Off {@code briefing.days} and {@code upcomingEvents}, which is exactly the pair the window
   * cards are built from, so the dots can never name a window the pane does not render.
   */
  const regionSeries = useMemo(
    () => buildRegionSeries(upcomingEvents, briefing?.days),
    [upcomingEvents, briefing?.days],
  );

  const value = useMemo(
    () => ({
      briefing, loading, heatStripCards, windowCards, paneItems, promotedStrip, upcomingEvents,
      travelDayDates, evaluationScores, scoreIndex, heatSpots: heatSpotList, heatPointSets,
      regionSeries,
      reachById, todayStr, tomorrowStr, reachLens,
      ratingLens, orderLens, homePlace, isPro, isLiteUser,
      // The origin and its two inputs. `effectiveReachById` is published beside `reachById` rather
      // than in place of it, because the two answer different questions and one consumer still
      // wants the home one: the planning area and the beyond line are statements about HOME, and
      // framing them from an away base would make "beyond 3h from home" a claim about Keswick.
      origin, setOrigin, regions, effectiveReachById,
    }),
    [briefing, loading, heatStripCards, windowCards, paneItems, promotedStrip, upcomingEvents,
      travelDayDates, evaluationScores, scoreIndex, heatSpotList, heatPointSets, regionSeries,
      reachById, todayStr, tomorrowStr, reachLens,
      ratingLens, orderLens, homePlace, isPro, isLiteUser,
      origin, setOrigin, regions, effectiveReachById],
  );

  return (
    <WindowFirstBriefingContext.Provider value={value}>
      {children}
    </WindowFirstBriefingContext.Provider>
  );
}

WindowFirstBriefingProvider.propTypes = {
  children: PropTypes.node,
  homeSettingsVersion: PropTypes.number,
  /**
   * The enabled roster — the ONLY payload carrying coordinates (plan §3), and the reason this
   * provider needs a prop at all. Optional: the arm renders without it, minus the heat field.
   */
  locations: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    name: PropTypes.string,
    lat: PropTypes.number,
    lon: PropTypes.number,
    regionName: PropTypes.string,
    bortleClass: PropTypes.number,
  })),
};

/**
 * The window-first briefing, its loading flag, the heat strip's thumbnails, the pane's ordered
 * contents and the three lens axes.
 *
 * @returns {{briefing: ?object, loading: boolean, heatStripCards: Array, windowCards: Array,
 *           paneItems: Array, promotedStrip: ?object, upcomingEvents: Array,
 *           travelDayDates: Set, heatSpots: Array, heatPointSets: Map, regionSeries: Map,
 *           reachById: Map,
 *           reachLens: object, ratingLens: object, orderLens: object, homePlace: ?string,
 *           todayStr: string,
 *           tomorrowStr: string, isPro: boolean, isLiteUser: boolean}}
 */
export function useWindowFirstBriefing() {
  return useContext(WindowFirstBriefingContext);
}
