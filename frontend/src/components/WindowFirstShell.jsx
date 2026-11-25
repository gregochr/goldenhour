import React, {
  lazy, Suspense, useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { ASK_PROMPT } from '../utils/askModel.js';
import BrandLockup from './shared/BrandLockup.jsx';
import MastheadLight from './shared/MastheadLight.jsx';
import MastheadTickLine, { MastheadFind } from './MastheadTickLine.jsx';
import MastheadMenu from './MastheadMenu.jsx';
import OperationsScreen from './OperationsScreen.jsx';
import { useIsMobile } from '../hooks/useIsMobile.js';
import WindowFirstLensBar from './WindowFirstLensBar.jsx';
import FurtherAhead from './FurtherAhead.jsx';
import {
  FURTHER_AHEAD_HEADING_ID, FURTHER_AHEAD_ID, THIS_WEEK_HEADING, THIS_WEEK_HEADING_ID, THIS_WEEK_ID,
  focusSectionHeading,
} from '../utils/screenSections.js';
import WindowSpotSheet from './WindowSpotSheet.jsx';
import { MapListContext } from '../context/MapListContext.jsx';
import { heatSpotKey } from '../utils/heatSpots.js';
import AskBar from './ask/AskBar.jsx';
import { ASK_DOCK_ID, ASK_SHEET_LABEL } from './ask/askIds.js';
import ChunkErrorBoundary from './shared/ChunkErrorBoundary.jsx';
import { warmSettingsModal } from '../utils/settingsModalChunk.js';
import AskField from './ask/AskField.jsx';
import AskUpsellPopover from './ask/AskUpsell.jsx';
import { useAsk } from '../context/AskContext.jsx';
import { useGuide } from '../context/GuideContext.jsx';
import GuideDock from './guide/GuideDock.jsx';
import { SCREEN_GUIDE } from '../utils/guide.js';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { useSubRegionStatus } from '../context/SubRegionStatusContext.jsx';
import { useWarnings } from '../context/WarningsContext.jsx';
import { warningsKey } from '../utils/comingUpWarnings.js';
import { isOutsidePicks } from '../utils/pickScope.js';
import { placesOf } from '../utils/windowFirstSpots.js';
import { formatRelativeAge } from '../utils/relativeTime.js';
import { buildLocationTypeMap } from '../utils/locationTypes.js';
import { originAction, scopeRegions } from '../utils/planOrigin.js';
import { buildTopicIndex, windowTopics } from '../utils/windowFirstTopics.js';
import { buildPlanConflict } from '../utils/planConflicts.js';
import {
  buildEclipseIndex, buildScoreIndex, buildSlotIndex, buildTideAlignmentIndex,
} from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/regionGloss.js';
import { openMapDoor } from '../utils/mapDoors.js';
import MapScreen from './MapScreen.jsx';
import useDockEdge from '../hooks/useDockEdge.js';
import { formatMapRoute, routeWindowStatus } from '../utils/mapRoute.js';
import { areaMapWindow } from '../utils/areaFind.js';
import { normalizeMapTrigger, outlierKeysOf } from '../utils/mapDoor.js';
import { foreignDialogOpen } from '../utils/shellForeignDialog.js';
import { isFindKey, isTypingTarget } from '../utils/findKey.js';
import useListedFeed from '../hooks/useListedFeed.js';
import { markComingUpSeen } from '../api/settingsApi.js';
import useAskSurface from '../hooks/useAskSurface.js';
import useAreaFind from '../hooks/useAreaFind.js';
import { useFavourites } from '../context/FavouritesContext.jsx';
import YourPlacesRow from './YourPlacesRow.jsx';
import GuideLink from './guide/GuideLink.jsx';
import { weekBestOf } from '../utils/weekBest.js';
import { NO_QUIET_OPEN } from '../utils/quietDays.js';
import BookmarkMark from './shared/BookmarkMark.jsx';
import StarFigure from './shared/StarFigure.jsx';
import { watchedStillToCome } from '../utils/watchedDates.js';
import useBackLayer from '../hooks/useBackLayer.js';
import useComingUpFeed from '../hooks/useComingUpFeed.js';
import useLensReserve from '../hooks/useLensReserve.js';
import useStuckSentinel from '../hooks/useStuckSentinel.js';

/**
 * The Coming up pane's `onShowOnMap` when the shell was handed no Map door (no forecast to map). The
 * pane REQUIRES a function, so this fills the prop; it is never called, because the shell then hands
 * the pane `forecastDates={[]}` ("known empty"), which withholds every map door the pane would draw
 * (a card becomes a plain card, a chip is dimmed) - a control that opens nothing is banned.
 */
const NO_MAP_DOOR = () => {};
/** The camera a window door asks of the map: frame the reader's home and the window's best place together. */
const HOME_AND_PLACE = 'home-and-place';
/** "Known empty": the Coming up pane's way of withholding every map door. */
const NO_FORECAST_DATES = Object.freeze([]);

/**
 * The heat strip, behind a lazy boundary for chunk hygiene.
 *
 * <p>{@code App} imports this shell STATICALLY (unlike `MapView`, `WindowFirstMapPane`,
 * and `ManageView`, which are all `lazy()`), so a static import here would put the
 * strip — and through it `heatField.js`'s `d3-geo` and `topojson-client` — in the entry chunk for
 * every reader. Measured: a 24.14 KB / 9.19 KB-gzip `geo` chunk, fetched render-blocking on first
 * paint, for a strip that always renders below the fold. Lazy keeps that chunk out of the entry.
 *
 * <p>The fallback is {@code null} rather than a skeleton: the strip's own canvases paint
 * asynchronously anyway (they wait on the vendored topology), so a placeholder would be a second
 * loading state for the same wait. The window rows below are unaffected either way.
 */
const WindowFirstHeatStrip = lazy(() => import('./WindowFirstHeatStrip.jsx'));

/**
 * Search, lazily — it is a dialog, so it is not on any first-paint path, and it drags in nothing
 * the shell already has (the matching lives in {@code planSearch.js}).
 */
const PlanSearch = lazy(() => import('./PlanSearch.jsx'));

/**
 * An area's spots (find-and-favourites-plan.md F2): a dialog reached only from an Areas row in Find, so it is lazy
 * for the same reason search is.
 */
const AreaSheet = lazy(() => import('./AreaSheet.jsx'));

/**
 * The four-day location sheet, on the same terms — a dialog, mounted only while open (P8).
 *
 * <p>Lazy for the reason search is, and for one more: it pulls in {@code locationSheet.js} and the
 * slot-time index, neither of which any other surface reads. A reader who never searches for a
 * place never downloads them.
 */
const LocationFourDaySheet = lazy(() => import('./LocationFourDaySheet.jsx'));

/**
 * Ask PhotoCast's dock and sheet, lazily (performance plan P2d). Both draw only once a reader opens
 * them, and they are what pull in the whole conversation (`AskConversation` and its cards), so a
 * reader who never asks never downloads it. The shell reads their identifiers from {@code askIds.js}
 * so those two constants do not drag the components in. {@code AskContext}, the entry fields, the bar
 * and the upsell popover stay eager: they are the way in, and small.
 */
const AskDock = lazy(() => import('./ask/AskDock.jsx'));
const AskSheet = lazy(() => import('./ask/AskSheet.jsx'));

/**
 * The dock's stand-in while its chunk loads: the same `aside` box (same band width, same sticky
 * or stretch rule), empty, inert and hidden from assistive tech, so the page beside it does not jump
 * when the real dock replaces it.
 *
 * @param {object} props
 * @param {'desktop'|'wide'} props.band
 * @param {boolean} props.sticky
 */
function AskDockFallback({ band, sticky }) {
  return (
    <aside
      id={ASK_DOCK_ID}
      aria-hidden="true"
      inert
      data-testid="ask-dock-fallback"
      data-band={band}
      data-sticky={sticky ? '' : undefined}
      className="wf-ask-dock"
    />
  );
}

AskDockFallback.propTypes = {
  band: PropTypes.oneOf(['desktop', 'wide']).isRequired,
  sticky: PropTypes.bool.isRequired,
};

/**
 * Warms the three lazy dialog chunks that can be STACKED over another layer.
 *
 * <h2>⚠️ This is a correctness mitigation, not a speed one</h2>
 *
 * <p>Since M5 a covered layer is {@code inert} — and {@code stacked} is derived from the shell's
 * intent, which is synchronous, while the layer doing the covering arrives when its chunk does.
 * Measured in a browser with the sheet's chunk throttled to 2.5 s: for the whole fetch the page held
 * <b>one dialog and zero live layers</b> — the lower layer inert (every control, the backdrop and its
 * Escape all dead) and the sheet not yet mounted, with {@code fallback={null}} rendering nothing in
 * between. Before M5 the lower layer merely declined Escape and stayed clickable, so this is a regression
 * the {@code inert} work introduced rather than an inherited gap.
 *
 * <p><b>Why warming rather than a loading dialog or a mount signal.</b> A {@code Modal} fallback
 * would flash a second loading state on every cold open for a wait that is usually zero; deferring
 * {@code stacked} until the arriving layer reports its mount means a new prop on three components
 * and an extra commit on the hottest interaction on the page, at the settling commit. Warming
 * removes the window on every route that exists: a spot row on the area sheet, a map callout's
 * {@code Four days here ›} and the masthead's ⌕ are each reachable only after the reader has already read the layer
 * they come from, so the fetch has that reading time to finish. It is idempotent (the module registry dedupes), it is fire-and-forget, and a failure is
 * the same failure the real import would have had.
 *
 * <p><b>Residual, stated rather than defended against:</b> a reader who opens a sheet and clicks a
 * row inside the same few hundred milliseconds on a cold, slow connection can still reach the gap.
 * §11 records it, and the real fix — gate {@code stacked} on the covering layer having mounted — is
 * named there rather than smuggled in here.
 */
function warmStackedChunks() {
  // Only the lazy ones. `WindowSpotSheet` is a static import and is already in the entry graph, which is why it
  // never shows the gap.
  import('./LocationFourDaySheet.jsx').catch(() => {});
  import('./PlanSearch.jsx').catch(() => {});
  import('./AreaSheet.jsx').catch(() => {});
}

/** The matrix's Ask highlight when there is none to show: one shared, empty map, so nothing re-renders for it. */
const NO_HIGHLIGHT = new Map();

/**
 * How long the first fetch has to run before the Plan pane's pending line admits it is taking a
 * while (2026-10-01, the 2026-09-30 incident — a `GET /api/briefing` request that never completed
 * in production, timing out against Cloudflare's 100s limit (36s was a lab measurement on
 * production-sized data, not a production observation); fixed in #957. The Plan pane below the
 * lens bar rendered nothing at all for the whole of that wait: no matrix, no doors, no count line, no
 * empty-state sentence, because the pane had no pending state of its own). Far above a healthy
 * round-trip and short enough that a reader staring at a frozen pane is not left guessing for a
 * minute.
 */
const PENDING_SLOW_AFTER_MS = 10_000;

/** The design's frame: 1080px. */
const WRAP_MAX_WIDTH = '1080px';

/** No lane carries an almanac entry: the starting value of the graduated set, shared so it costs no allocation. */
const NO_ENTRY_IDS = new Set();

/**
 * What the view chip says, and what the question is sent with, by tab. Map reads "all regions" here
 * because the shell cannot see the Map's own scope segment or window (they are `MapView` state): the
 * chip names what is SENT, and until F3/F4 carry the Map's scope to a surface, a question from the
 * sheet OR the dock is sent with no region and no window.
 */
const ASK_VIEW = {
  plan: { view: 'plan', label: 'This week · all regions' },
  map: { view: 'map', label: 'Map · all regions' },
};
// (One-screen-plan.md D8: the view is 'map' while the map VIEW is open, otherwise the screen's own. Further ahead is
// part of the screen now, so it asks as 'plan' and the Ready questions that were Coming up's are offered there.)

/** What the dock's header says it is beside: the screen, or the map view. */
const ASK_DOCK_CONTEXT = {
  plan: 'on This week',
  map: 'on Map',
};

/**
 * The field's width by band: 340px with the `/` hint from 1180px, 260px from 1024 to 1179 where the
 * dock (360px) leaves the masthead too little room for more, and the tablet's 260px below it.
 */
const ASK_FIELD_WIDTH = { tablet: 260, desktop: 260, wide: 340 };

/** The word a load-time route's event type is spoken as in "That sunrise has passed". */
/** The served `targetType` a night trigger's `kind` stands for (a seed for the layer's address until the pane's own). */
const NIGHT_KIND_TYPE = { aurora: 'AURORA', astro: 'ASTRO' };
const LOAD_WORD = { SUNRISE: 'sunrise', SUNSET: 'sunset', ASTRO: 'night', AURORA: 'aurora' };


/**
 * The one screen's shell — the masthead and the frame the screen sits in.
 *
 * <h2>It renders its own masthead because it is the app's only header</h2>
 *
 * <p>{@code App} renders no {@code <header>} of its own — this shell is it — so the wordmark, the
 * settings cog and Sign out all live here or they are simply gone. The two buttons take the same
 * handlers, lifted rather than duplicated — this component owns no auth or modal state of its own.
 *
 * <p><b>Not the design's masthead brand, deliberately.</b> The mock draws a conic-gradient disc and
 * a 20px sans wordmark. This app's identity is {@link BrandLockup} — a film-perforation spine and a
 * serif wordmark — and that component's own Javadoc records why the previous {@code logo.png} went:
 * it "belonged to no part of the Kodachrome Field Guide system the rest of the app uses". Drawing
 * the disc would reintroduce exactly that, as the only mark of its kind in the product. The
 * {@code compact} variant exists for this masthead's height budget. Recorded in plan §7.
 *
 * <h2>The status pill, and why it is a slot rather than a component</h2>
 *
 * <p>The design shows {@code ● UP v2.17.7} unconditionally, and this arm shipped without it: build
 * version and service health are not a pilot user's business (plan §7). That reasoning was right
 * about the <em>pilot user</em> and wrong about the admin, who had the control in the app's old
 * header and would otherwise have lost it entirely — the first thing anyone running the app would
 * notice is being unable to see whether the backend is up.
 *
 * <p>So it returns as {@code healthPill}, a NODE the caller supplies, on the same idiom as
 * {@code operationsPane}: {@code App} holds {@code isAdmin} and withholds the node, so a pilot user
 * still sees no pill and no role and no isAdmin crosses this boundary; derived booleans such as reachLocked and isPro are allowed (plan §5c). The unconditional
 * pill the design draws is still not what ships — the design has no roles in it.
 *
 * <h2>One screen, no tabs (`docs/engineering/one-screen-plan.md`)</h2>
 *
 * <p>The screen is the masthead and one scrolling column: This week, then Further ahead. The map is a VIEW over it
 * ({@code openMap}, drawn by {@code MapScreen}) and Operations is an admin-only full-screen view ({@code operationsOpen},
 * driven by the page's {@code #manage} address, drawn here around the node {@code App} hands over). There is no tab
 * list, no roving tab index and no opening-tab preference: every device opens on This week.
 *
 * <h2>The day rail is GONE, and its replacement is a Plan-pane element</h2>
 *
 * <p>Through P14 four day tiles sat above the tab bar, and this file argued at length that they
 * belonged there: the rail was "the whole screen's date context rather than one pane's content",
 * since Coming up and Map ask questions about the same days. That decision is <b>reversed</b> at
 * P2 of the heat-field plan, on the owner's confirmation (2026-08-18), and the reversal is
 * recorded rather than silently overwritten — see §1.1 of {@code heat-field-plan.md} for the
 * job-by-job relocation table and the two rejected alternatives.
 *
 * <p>The reason it is acceptable NOW and was not before: each tab has since grown its own date
 * context. The Map pane's window control ({@code components/map/WindowControl.jsx}, fed by
 * {@code utils/mapEvents.js}'s D-13 rows — map-tab-v2-plan.md §3 P6, which retired the pane's
 * earlier {@code DateStrip} mount) browses the full forecast horizon, and every Coming-up row
 * carries its dates. What replaces the rail is {@link WindowFirstHeatStrip},
 * six window thumbnails under the lens bar — the rail's job done at the window list's own grain,
 * with space shown inside each window instead of named beside it. Stacking both would be two
 * summaries of one forecast at two grains, costing roughly two screens of chrome before the first
 * window row.
 *
 * <p>The strip takes the {@code contentDisabled} greying because it is forecast data and data from
 * a DOWN backend is exactly what that treatment exists to mark — it is inside the pane, which
 * already carries it. The tab bar does not: it is navigation, and so is the masthead.
 *
 * <h2>The rail footer is gone, and the tick line is where three of its four jobs went</h2>
 *
 * <p>It outlived the rail it was named for and did not outlive M3. The masthead's tick line
 * ({@link MastheadTickLine}) is now, in the design's words, "the ONLY statement of where the plan
 * is computed from; there is no separate origin chip or breadcrumb anywhere in the tab", so the
 * origin control moved into it and the "Home not set" line became its empty state — the same
 * three-way rule, unchanged: {@code Home · <place>} when one is known, the prompt when the settings
 * response says there is none, and <b>nothing at all</b> while that is still unknown, because
 * telling a user who has a home that they have not set one, on the strength of a dropped request,
 * is worse than silence. "Edit reach" opened the same modal the ⚙ two rows up opens, so only the
 * duplicate control went.
 *
 * <p>The age is the one that changed more than its address, and the deletion note beside the
 * masthead below records the trade in full. In short: {@code generatedAt} is still formatted on the
 * client (§2.8 — a server-rendered relative string would mutate the ETagged body on every request)
 * through the shared {@code formatRelativeAge}, which already knows the instant is UTC; the design's
 * {@code by Sonnet} and its "· reach set per day" stay dropped for the reasons §7 and §2.7 give.
 *
 * <h2>The lens bar sits between the tab rule and the pane, and is never dimmed</h2>
 *
 * <p>Where the design puts it, and outside the {@code contentDisabled} treatment on purpose. The
 * lens is a pure client-side filter over data already in memory, so it keeps working when the
 * backend does not — and {@code pointer-events: none} on a sticky bar would make a live control
 * look broken to say nothing true. The tab bar and the masthead are excluded for the same reason.
 *
 * @param {object}   props
 * @param {function} props.onOpenSettings opens the shared settings modal.
 * <h2>The pane is the matrix and one dialog (M2)</h2>
 *
 * <p>The six-row card list is gone. What the strip used to index — a row per window, opened in
 * place, with the reader's position in the page shifting under them — is now the matrix's own six
 * cells, and (since G2b) a window opens on the Map rather than in a dialog here.
 *
 * <p>{@code buildPaneItems} survives the deletion and is still read — it is the empty-state line's
 * denominator, and the derivation that keeps away days accounted for. What went is the
 * <em>rendering</em> of it, and (at M5, with the promoted strip) its away payload: the block's
 * label, note and window count had no reader left once nothing rendered a row for it.
 *
 * @param {function} props.onSignOut ends the session — the masthead's route to it while the Plan
 *        is healthy; {@code PlanErrorBoundary} offers its own separate Sign-out if it is not.
 * @param {Array} [props.locations] enabled locations. The shell needs the name→type join for the spot sheet's
 *        type control and the id/name lookup for the sheet's spot.
 *        Not fetched by this arm's provider: {@code App} already holds them, and a second request
 *        for a list the page has would be waste.
 * @param {?Function} [props.onFindOnMap] hands the Map a Find pick that is not a window:
 *        {@code {kind: 'location', id, name}} (fly and open the callout) or {@code {kind: 'region', name}}
 *        (pan and frame, never the origin). {@code App} nonces it and forwards it to the Map pane. Absent
 *        means no Map pane: Find then offers the Map no pick.
 * @param {?Function} [props.onFindOpenChange] told {@code true} while Find is open and {@code false} when it
 *        closes, so the Map pane can close its own menus (they are not dialogs, and Find is a modal over them)
 * @param {?Array<string>} [props.forecastDates] the dates (`YYYY-MM-DD`) the reader holds a colour
 *        forecast for — {@code App}'s {@code allDates}. Forwarded untouched to the Coming up tab,
 *        which withholds a map door whose date is not in it. Three-valued: null (the default) is
 *        "not known yet" and withholds nothing; an array, even empty, is known.
 * @param {boolean}  [props.contentDisabled] greys the pane when the backend is DOWN.
 *
 *        <p><b>The pane, never the chrome.</b> The masthead is inside the shell, so gating the
 *        whole subtree would take the cog and Sign out with it — leaving a user staring at a
 *        greyed page with no route anywhere, at exactly the moment they most need one.
 * @param {object|null} [props.light] today's light at the reader's home, for the masthead's light
 *        rule. Resolved by {@code App}, not here, so the shell stays a render layer and every test
 *        can put the band in any of its three states. {@code undefined} is "not yet answered" and
 *        {@code null} is "answered, no home saved" — see {@link MastheadLight}.
 * @param {function} [props.onSetPostcode] opens settings on the home-postcode field, for the
 *        band's nudge. Defaults to {@code onOpenSettings}, so the nudge can never be a dead end.
 *        Called with the tick line's origin-slot resolver, passed through untouched — see
 *        {@link MastheadTickLine}'s {@code onSetPostcode}.
 * @param {?object} [props.homeCoords] {@code {lat, lon}}, or null with no postcode saved — reused
 *        by the heat strip's home marker and (at G3) the deleted window popup field's reach rings. `App` hands
 *        `undefined` while the home is not known, and the default folds that into null here, which
 *        is safe only because every Plan surface draws a home and none prompts for one — the map's
 *        ⌂ does, which is why `WindowFirstMapPane` and `MapView` take the prop bare.
 */
export default function WindowFirstShell({
  onOpenSettings, onSignOut, contentDisabled, onEvaluationScoresChange,
  onSeasonalFeaturesChange, locations, mapPane, operationsPane, operationsOpen = false, onOpenOperations = null,
  onCloseOperations = null, healthPill,
  healthDot = null, healthStatusLine = null, light, onSetPostcode, mapColourScale = null, homeCoords = null, onScreenChange = null,
  locationSheetHandoff = null, onOpenMapTab = null, settingsOpen = false,
  forecastDates = null, registerCloseDialogs = null, onFindOnMap = null, onFindOpenChange = null,
  registerOpenMap = null, loadMapRoute = null, onLoadMapRouteHandled = null,
  placesHintDue = false, onDismissPlacesHint = null, onSettlePlacesHint = null,
}) {
  const {
    heatStripCards, heatPointSets, heatUnratedSets, heatStoodSets, heatGroundSets,
    heatSpots, reachById,
    windowCards, paneItems, loading, briefing, evaluationScores, scoresLoaded,
    scoreRows, todayStr, reachLens, ratingLens, homePlace,
    origin, setOrigin, regions, effectiveReachById, reachMeasured,
    comingUpLastSeenDate, setComingUpLastSeenAt, isPro, scoresLocked, picksLocked,
    allowedLocationIds,
  } = useWindowFirstBriefing();
  const { openSubRegions } = useSubRegionStatus();
  // What the strip's key and note need on a body shaped by the reader's sub-region picks (S5b-1): the places the picks
  // cover (the served list) and the roster. Null for every other reader. Memoised so the strip's props are stable.
  const picksFacts = useMemo(
    () => (Array.isArray(allowedLocationIds)
      ? { allowed: allowedLocationIds.length, total: heatSpots.length } : null),
    [allowedLocationIds, heatSpots.length],
  );
  /**
   * Whether the first fetch has been running long enough that the pending line should admit it is
   * taking a while, rather than quietly repeating "Loading the forecast…" with nothing to show
   * for it. Armed on a plain `setTimeout`, not a derivation of `loading` alone, because `loading`
   * carries no timestamp — only whether a fetch is in flight right now.
   *
   * <p>No reset branch for `loading` going true→false: `WindowFirstBriefingContext`'s only
   * `setLoading(false)` site is the one fetch's own `finally`, so `loading` goes true→false at
   * most once per provider mount — there is no "next loading session" in which a stale
   * `pendingSlow=true` could wrongly resurface. And even within this one mount, the text below
   * renders only while `loading` is still true, so a `pendingSlow` left at `true` after loading
   * ends is simply never read. The same arm-and-clear shape as the `PREFERRED_TAB_GRACE_MS` timer
   * below (which arms once at mount; this one re-arms per `loading` transition, but both clear on
   * unmount/dep-change with no reset branch) rather than fighting `react-hooks/set-state-in-effect`
   * for a state the clock cannot produce — the same trade this file's `windowCards` key-release
   * note, below, already argues.
   */
  const [pendingSlow, setPendingSlow] = useState(false);
  useEffect(() => {
    if (!loading) return undefined;
    const timer = setTimeout(() => setPendingSlow(true), PENDING_SLOW_AFTER_MS);
    return () => clearTimeout(timer);
  }, [loading]);
  /**
   * The search dialog's open state, and the region it should be pre-filled with.
   *
   * <p>Two values in one, because "open with a query" and "open empty" are the same gesture from
   * two places: the ⌕ and the origin button open it empty (the {@code /} key did too until F2 gave it to
   * Ask), and the strip's beyond line opens it
   * on the first region beyond the planning area (the link P2 deferred to here). {@code null} is
   * closed; a string — possibly empty — is open.
   */
  const [searchSeed, setSearchSeed] = useState(null);
  /**
   * The area whose sheet is open (a directory entry), or null (find-and-favourites-plan.md F2). Reached only from an
   * Areas row in Find on Plan or Coming up; the place sheet may stack over it, so it is never more than two deep
   * (Find having closed on the pick).
   */
  const [areaSheet, setAreaSheet] = useState(null);
  // The area directory and the reader's outlook, fetched lazily: nothing is requested until Find first opens.
  const areaFind = useAreaFind(searchSeed != null || areaSheet != null);
  // The masthead's ☰ menu (one-screen-plan.md D9, D14), at every width: whether it is open, and its button, which is
  // also the focus fallback for the tick line when its origin slot is empty and where Operations returns focus.
  const isMobile = useIsMobile();
  const [mastMenuOpen, setMastMenuOpen] = useState(false);
  const menuBtnRef = useRef(null);
  /**
   * Whether Ask PhotoCast's sheet is open (plan §2.6: whether Ask is OPEN is SHELL state).
   *
   * <p>The shell's own {@code useState}, and it must stay one: {@link closeShellLayers} clears it, and
   * {@link closeShellLayers} runs during render on the settings edge, where it may call only this
   * component's own setters. A context value could not be cleared from there. Never set from
   * {@code AskContext}, which holds the conversation and nothing about a surface.
   */
  const [askSheetOpen, setAskSheetOpen] = useState(false);
  /** Whether the sheet has been opened since its entry last appeared: gates mounting the lazy chunk. */
  const [askSheetLoaded, setAskSheetLoaded] = useState(false);
  /**
   * Whether Ask PhotoCast's DOCK is open (F2) — its own state, deliberately not a value of
   * {@code askSheetOpen}/{@code askEntry}. {@code askSheetShown} drives the app container's {@code inert}, and
   * the dock is not modal: folding it in would make the dock inert itself and the page beside it dead.
   *
   * <p>Not on {@link closeShellLayers}' list either, and that is the point of a dock: it survives a move
   * between the screen and the map view (the conversation chips and suggestions follow the view). It goes
   * when nothing can draw it any more — Operations, a band below 1024px, Ask switched off — through the
   * same render-time release {@code askSheetOpen} uses.
   */
  const [askDockOpen, setAskDockOpen] = useState(false);
  /** The Ask bar or field that is on screen, where closing the sheet or the dock returns focus. */
  const askTriggerRef = useRef(null);
  /**
   * The kind of Ask entry that opened the Ask UPSELL (a LITE reader's worded door, docs/engineering/
   * ask-upsell-plan.md §3.2): {@code 'bar'} (the phone's), {@code 'field'} (the tablet's) or
   * {@code 'dock-field'} (the field from 1024px), or null when it is closed. The KIND is recorded, not a
   * bare boolean, because a breakpoint change swaps one entry for another and a state that only asked
   * "is there an entry" would carry the popover across to the wrong one. Let go during render below, and
   * cleared by {@link closeShellLayers}.
   */
  const [askUpsellFor, setAskUpsellFor] = useState(null);
  const askUpsellId = useId();
  const askUpsellRootRef = useRef(null);
  /** The dock's question field, so `/` can focus it when the dock is already open. */
  const askDockInputRef = useRef(null);
  const ask = useAsk();
  const askSurface = useAskSurface();
  /** The in-app guide (G2): its door here is the ☰ menu's Guide row. */
  const guide = useGuide();
  const { guideModal } = guide;
  /**
   * The location whose four-day sheet is open, or null (P8).
   *
   * <p>Only the spot's identity is held. Every figure the sheet prints — the rating, the prose, the
   * drive, the departure — is looked up live from the payloads on each render, so a sheet left open
   * across a poll shows the new forecast rather than a snapshot of the old one. Holding the derived
   * sheet here instead would be the same freeze, one level up.
   */
  const [sheetSpot, setSheetSpot] = useState(null);
  /**
   * The window the open sheet should FOCUS — `date:targetType`, or null.
   *
   * <p>Only the map's callout route sets it (increment §1): that route promises "the rest of THIS
   * narrative", so the sheet must open on the window whose prose was clicked, not on its own best.
   * Every other entry point leaves it null and keeps `buildLocationSheet`'s own seeding.
   *
   * <p>Held beside {@code sheetSpot} rather than on it, because it is not part of the location's
   * identity — the sheet is keyed on `sheetSpot.id ?? sheetSpot.name`, and folding a window into
   * that key would remount the whole dialog whenever the map's window changed underneath it.
   */
  const [sheetWindowKey, setSheetWindowKey] = useState(null);

  /**
   * The map VIEW (`docs/engineering/one-screen-plan.md` D1, D2, D3): {@code null} while closed, otherwise
   * {@code {loadTime}}, where {@code loadTime} says the open came from the page's own address
   * rather than a press (its Back layer is then activation-gated, so the first gesture pushes the
   * entry). A view that is open while the shell has no map pane is let go during render, below.
   */
  const [mapView, setMapView] = useState(null);
  /** Latched the first time the view opens: the pane mounts then, visible from its first commit (D10). */
  const [mapMounted, setMapMounted] = useState(false);
  /** The pane's current `{date, eventType, locationId}`, published through `MapScreenContext`; the layer's url. */
  const [mapRoute, setMapRoute] = useState(null);
  /** A one-line note under the view's header (a load-time window that has passed), or null. */
  const [mapNote, setMapNote] = useState(null);
  /** The element focus returns to when the view closes (a card, a button), kept out of state: nothing renders it. */
  const mapFromRef = useRef(null);
  /** 'skip' when the closer places focus itself (a route into a sheet on the screen); otherwise 'restore'. */
  const mapFocusReturnRef = useRef('restore');
  /** The view's title: initial focus on open, and the focus fallback for every surface that names the map. */
  const mapTitleRef = useRef(null);
  const hasMapPane = mapPane != null;
  /** Whether Operations is showing: the page's address says so and `App` handed over the pane (admin only). */
  const operationsShown = operationsOpen && operationsPane != null;
  /** Whether the map view is showing. A view left open when its pane is withdrawn (an aurora alert clearing) goes. */
  const mapOpen = mapView != null && hasMapPane && !operationsShown;
  if (mapView != null && (!hasMapPane || operationsShown)) setMapView(null);
  if (mapOpen && !mapMounted) setMapMounted(true);
  /**
   * The shell→App channel for the guide and the aurora door: {@code 'operations'} while Operations is open,
   * {@code 'map'} while the map view is, otherwise {@code 'plan'}. Fired on mount too, so `App` learns the opening
   * state rather than starting from a guess.
   */
  const reportedScreen = operationsShown ? 'operations' : (mapOpen ? 'map' : 'plan');
  useEffect(() => { onScreenChange?.(reportedScreen); }, [reportedScreen, onScreenChange]);
  // Gone (a crash under `PlanErrorBoundary`, a sign-out): the page behind must not stay inert or pinned for a view that
  // no longer exists, so the shell reports no screen at all.
  const onScreenChangeRef = useRef(onScreenChange);
  useEffect(() => { onScreenChangeRef.current = onScreenChange; });
  useEffect(() => () => { onScreenChangeRef.current?.(null); }, []);
  // `true`, unconditionally — the "Further ahead" jump's badge (plan D3/D4/D13) needs to know about arrivals
  // whether or not the reader ever scrolls there, so the fetch fires after first paint for every reader.
  // See useComingUpFeed.js's own class doc for the reversal.
  // The polled warning set keys a silent refetch: the server merges the Met Office warning entries per request, and the
  // feed is otherwise fetched once a day, so without this a new or withdrawn warning could stay unseen for hours.
  const polledWarnings = useWarnings();
  const warningSetKey = useMemo(() => warningsKey(polledWarnings), [polledWarnings]);
  const comingUp = useComingUpFeed(true, todayStr, warningSetKey);
  // A pending "focus Further ahead's heading" request (see `openFurtherAhead`).
  const [comingUpFocus, setComingUpFocus] = useState(null);
  const comingUpSeq = useRef(0);
  // The almanac entries This week already draws as lanes, reported by the heat strip (one-screen-plan.md D5). Further
  // ahead lists everything else. The strip reports only when the SET changes (it keys on the sorted ids), so a
  // re-render that draws the same lanes writes nothing; the setter is stable, so it never re-fires the strip's effect.
  const [graduatedIds, setGraduatedIds] = useState(NO_ENTRY_IDS);
  /**
   * The quiet days the reader has opened this session (screen-spec.md D5), by ISO date. The shell's own state, not the
   * strip's: the strip is a lazy pane that remounts on a viewport flip, and an opened day stays opened for the session.
   * A fold is a presentation choice, never persisted.
   */
  const [openQuietDays, setOpenQuietDays] = useState(NO_QUIET_OPEN);
  const toggleQuietDay = useCallback((date) => {
    setOpenQuietDays((prev) => {
      const next = new Set(prev);
      if (next.has(date)) next.delete(date);
      else next.add(date);
      return next;
    });
  }, []);
  // The ids Coming up lists under its All filter — every served entry, which is what the pills' jump may target
  // (glance-topic-strip-plan.md R1/R4). Empty unless the feed is READY: a stale `events` while a retry loads, or
  // after a failure, names rows that are not on screen.
  const comingUpEntryIds = useMemo(
    () => (comingUp.status === 'ready'
      ? new Set((comingUp.events?.entries ?? []).map((entry) => entry.id))
      : new Set()),
    [comingUp.status, comingUp.events],
  );
  /**
   * The tab badge's state (plan D3/D4/D12) — null (no element at all) in the overwhelmingly common
   * case. Read off the WHOLE served feed, never the chip-filtered subset the pane itself may be
   * showing: the badge answers "is anything new anywhere in the feed", a question the active filter
   * must not narrow.
   */
  const { badge: comingUpBadge } = useListedFeed(comingUp.events, graduatedIds, comingUpLastSeenDate);
  /**
   * The "Further ahead ↓" jump's accessible name (D6). With a badge it carries the badge's meaning, which the
   * `aria-hidden` badge span cannot; without one the name computes from the button's own text. The two band names
   * ({@code interrupt}, {@code announce}) are the surprise model's, not the reader's, so a screen-reader user hears
   * only the difference they encode: one exceptional arrival versus several ordinary ones.
   */
  const favouritesState = useFavourites();
  // The watched dates not yet past (screen-spec.md D2), drawn beside the jump. Only for a reader whose list has landed,
  // and only above zero: a Lite reader or the error state never shows a "0".
  const watchingCount = favouritesState.status === 'ready'
    ? watchedStillToCome({ dates: favouritesState.dates, entries: comingUp.events?.entries, todayStr })
    : 0;
  const furtherAheadJumpLabel = (comingUpBadge || watchingCount > 0)
    ? [
      'Further ahead',
      comingUpBadge && (comingUpBadge.band === 'interrupt'
        ? 'one rare date added'
        : `${comingUpBadge.count} new date${comingUpBadge.count === 1 ? '' : 's'}`),
      watchingCount > 0 && `${watchingCount} watching`,
    ].filter(Boolean).join(', ')
    : undefined;
  /**
   * Marks the feed seen — both `Mark seen`'s own press and the quiet bootstrap write below share
   * this one function, because the service does not distinguish the two callers (plan D3).
   *
   * <p>Optimistic AND reconciled: the local date moves to `todayStr` immediately, before the
   * request settles, clearing the badge and every NEW flag without waiting on a round trip — but
   * a SUCCESSFUL response's own `comingUpLastSeenDate` then overwrites that guess, the same
   * reconciliation the bootstrap write below already does. `todayStr` is the reader's own London
   * civil date and will usually already match what the server's clock resolves "now" to, but
   * "usually" is not "always" — a skewed client clock, or a press within the last moments before
   * the UK civil day rolls over, can disagree with the server by a day, and nothing else in this
   * session re-fetches settings to notice (`App`'s `useReaderSettings` reads them once, on mount,
   * and the settings dialog's own read never overwrites a known date). Applying the echoed value
   * closes that gap the instant it would otherwise open. A FAILED write is left on the
   * optimistic guess rather than rolled back: the design's own bias throughout is that silence is
   * the safe failure, and reverting to "still new" on a dropped response would flash the badge
   * back on for no reason a reader could see.
   */
  const markSeen = useCallback(() => {
    setComingUpLastSeenAt(todayStr);
    markComingUpSeen()
      .then((settings) => {
        if (settings?.comingUpLastSeenDate) setComingUpLastSeenAt(settings.comingUpLastSeenDate);
      })
      .catch(() => {});
  }, [setComingUpLastSeenAt, todayStr]);
  /**
   * The bootstrap write (plan D3, the round-3 external-review fix for a deadlock that otherwise
   * disables the badge for every account forever): a null `comingUpLastSeenDate` renders as
   * "nothing new", `Mark seen` is the only other write, and the since-line hosting it only renders
   * when something IS new — so without this, an account that has never opened the tab can never
   * reach the one control that would set the timestamp, and stays null permanently.
   *
   * <p>Fires once, the FIRST time Further ahead's heading reaches the viewport while `comingUpLastSeenDate` is
   * exactly {@code null} (not `undefined`, which means "not answered yet" — see the context's own class
   * doc — and must not be mistaken for "never seen"). It was "the first open of the Coming up tab"; the heading
   * intersecting is the same moment on a screen with no tab (one-screen-plan.md D6). That first visit shows no badge
   * and no NEW flags, matching D3's chosen quiet bias: the write happens silently, not as a visible "welcome"
   * moment. ⚠️ Scrolling PAST the section does not clear the badge (owner, 2026-11-23); only "Mark seen" does.
   *
   * <p>The guard ref is reset on failure, not left permanently spent — a dropped request must retry
   * the NEXT time the heading reaches the viewport rather than never again, but must not retry
   * instantly either: {@code FurtherAhead} calls this on each intersection, never on a timer, so a
   * reader sitting still gives it no second chance and a scroll away and back gives it exactly one.
   */
  const bootstrapFiredRef = useRef(false);
  const handleFurtherAheadSeen = useCallback(() => {
    if (comingUpLastSeenDate !== null) return;
    if (bootstrapFiredRef.current) return;
    bootstrapFiredRef.current = true;
    markComingUpSeen()
      .then((settings) => setComingUpLastSeenAt(settings?.comingUpLastSeenDate ?? todayStr))
      .catch(() => { bootstrapFiredRef.current = false; });
  }, [comingUpLastSeenDate, todayStr, setComingUpLastSeenAt]);
  /**
   * Takes every dialog this shell owns down: the Map's spot list, the four-day sheet, the area sheet, search, Ask's
   * sheet and upsell, and the ☰ menu. The shell's ONE list of what a dialog is
   * (`docs/engineering/one-screen-plan.md` D17, which replaced the old tab selector): every caller — {@code openMap}, the menu's rows, the tick
   * line's nudge, the settings edge, the aurora banner's closer — calls this and never inlines the list, so a dialog
   * added later cannot reach one route and miss another. It does NOT close the map view: the view is the base layer
   * the dialogs stack over, and a route that wants it gone says so ({@code closeMap}).
   *
   * <p>Every dialog this shell owns is rendered outside the pane and its state is independent of the tab, so without
   * this a reader who opened a sheet and then moved on would be left with a modal about a place floating over the next
   * thing — and {@code useDialogFocus} is explicitly not a focus trap, so closing it would hand focus back to a
   * trigger that is no longer on screen. Arriving somewhere else ends the browsing.
   *
   * <p>⚠️ <b>Search is on the list since 2026-09-16, and was missing from it before.</b> A keyboard reader could Tab
   * out of the search box (it is not a trap) and arrow to another tab with search still open.
   *
   * <p>⚠️ <b>This runs DURING RENDER as well as in handlers and effects</b>: the settings edge below calls it. So the
   * body must stay this component's own setters and nothing else. A focus move, a ref write, a parent callback or a
   * request added here would run inside the shell's render on every opening of settings — and only the ref write is
   * something lint catches.
   */
  const closeShellLayers = () => {
    setSheetKey(null);
    setSheetScope(null);
    // The four-day sheet goes with them.
    setSheetSpot(null);
    setSheetWindowKey(null);
    setSearchSeed(null);
    // The area sheet is a dialog like the rest.
    setAreaSheet(null);
    // Ask's sheet is a layer like the rest: arriving anywhere ends it, and every route that only
    // wants the dialogs gone (the cog, the nudge, the settings edge) takes it down with them. The
    // CONVERSATION is not touched — it is context state, and this body may call only our own setters.
    setAskSheetOpen(false);
    // ...and so is the LITE reader's Ask upsell: from 640px its field is drawn on every screen, so a route
    // would otherwise leave the popover open over the next one.
    setAskUpsellFor(null);
    // The phone's ⋯ menu is a layer too (compact-chrome plan R4): every route out of the masthead
    // closes it, so settings never opens over it. Our own setter, as this body requires.
    setMastMenuOpen(false);
  };
  /**
   * A Further ahead entry's "plan" action: back up to This week. It is the same screen, so there is nothing to select;
   * scrolling to and focusing This week's heading is what keeps focus from falling to {@code <body>} when the
   * pressed card is no longer where the reader was (the failure {@code FurtherAhead}'s own retry-focus effect argues
   * against). {@code onGoToPlan} accepts a date, which nothing reads yet (§11.9).
   */
  const goToPlan = (date) => {
    void date;
    closeShellLayers();
    focusSectionHeading(THIS_WEEK_HEADING_ID);
  };
  /**
   * Opens Further ahead (one-screen-plan.md O2): the "Further ahead ↓" jump. Stores a request {@code {seq}} and takes
   * the dialogs down. The request lives in this shell's state, so it survives the commit that closes them;
   * {@code FurtherAhead} answers it through {@link handleComingUpFocusHandled}. {@code seq} makes a second press a new
   * request. (It once named an entry to open; nothing does any more, since a lane's entry is not listed and Your
   * places lost its date chips, screen-spec.md D2.)
   */
  const openFurtherAhead = () => {
    comingUpSeq.current += 1;
    setComingUpFocus({ seq: comingUpSeq.current });
    closeShellLayers();
  };
  /**
   * Further ahead has answered the request: focus goes to the section's heading, scrolled into view, so it never falls
   * to {@code <body>}.
   */
  const handleComingUpFocusHandled = () => {
    setComingUpFocus(null);
    focusSectionHeading(FURTHER_AHEAD_HEADING_ID);
  };
  /**
   * The arm's root, and the element that hosts `--wf-lens-reserve` and `--wf-lens-h`.
   *
   * <p>Both are written imperatively by {@code useLensReserve} rather than through the `style` prop
   * below, and the two coexist because React updates a style object key by key: it never rewrites
   * `cssText`, so a custom property it does not know about survives every re-render. The
   * alternative — measuring into state and rendering it — puts a `setState` inside a
   * `ResizeObserver` callback for properties that affect no layout of their own.
   */
  const shellRef = useRef(null);
  useLensReserve(shellRef);
  /**
   * Whether the lens bar has left its resting place, and the sentinel that answers it.
   *
   * <p>The bar is the pane's only sticky element, so it needs a treatment that says which of its two
   * states it is in — without it, a bar overlapping content it is scrolling over reads as part of
   * that content. The design's own answer is a shadow and a raised bottom border, and its own
   * mechanism is a 1px sentinel above the bar.
   *
   * <p>⚠️ <b>No offset, and that is a measured value rather than a default.</b> The sentinel's
   * {@code rootMargin} insets by the line the bar rests at, which M3 made the masthead's measured
   * height because the bar was believed to stick below a pinned masthead. It never was pinned
   * (`index.css`'s `.wf-mast`), and since the anchoring fix the bar rests at the viewport's own top
   * edge, so the line is zero. A phase that pins the masthead for real has to hand the height back
   * in here, or the shadow arrives a masthead's worth of scroll late.
   */
  const [lensSentinelRef, lensStuck] = useStuckSentinel();
  /**
   * The This week section, handed to the lens bar (screen-spec.md D6), which watches it to collapse to one line on a
   * phone once the section has scrolled past the lens (`useCollapsingLens`).
   *
   * <p>⚠️ The watched element is the SECTION, not a 1px sentinel at its end, and that is deliberate. An IntersectionObserver
   * reports only a change of "intersecting", so a 1px marker that starts below the viewport is missed whenever the page jumps
   * over it in one frame (a fast fling, the Further ahead jump, scroll restoration): below and above are both "not
   * intersecting". The section is taller than the viewport and intersects at the top of the page, so every jump from the top
   * to past its end flips the state and is reported. The line it is measured against is the lens's own bottom edge (plus a
   * little), not the viewport top: the Further ahead jump parks the next heading just under the lens, which is the case the
   * viewport-top line missed. A state, not a ref: the bar's observer is built when the node arrives.
   */
  const [weekNode, setWeekNode] = useState(null);
  /**
   * The drill-down's window, held by KEY rather than by the card object.
   *
   * <p>A card is a derived snapshot: the provider rebuilds every one of them on the ten-minute
   * poll, when the reach fetch lands, and whenever the lens tier moves. Holding the object would
   * leave the sheet describing a list the page behind it had already replaced — and the reach
   * control inside it filtering an array nothing else on screen still uses. Holding the key means
   * the sheet always reads the live card, and a window that has passed simply closes it rather than
   * becoming a dialog about a window that no longer exists.
   */
  const [sheetKey, setSheetKey] = useState(null);
  const sheetCard = sheetKey == null ? null : windowCards.find((c) => c.key === sheetKey) || null;
  /**
   * The Map's snapshot at the press that opened the list (glance-to-map-plan.md G1), so the list says what the Map
   * says. {@code {spotKeys, floorId, tierId, onPick}}: the {@code heatSpotKey}s the Map held in scope (the list's
   * spots are intersected with them, so its counts match the Map's), the Map's own rating floor and drive tier ids
   * (what the sheet opens on), and the callback that selects a place on the Map in place. The list opens only from
   * the Map, so this is set and cleared together with {@code sheetKey} in {@code openSheetLayer}: a stale snapshot
   * can never ride onto another opening.
   */
  const [sheetScope, setSheetScope] = useState(null);
  /** The card the list shows: the live card, its spots narrowed to the Map's in-scope set. */
  const sheetCardShown = sheetCard != null
    ? {
      ...sheetCard,
      allSpots: (sheetCard.allSpots || []).filter((spot) => sheetScope?.spotKeys?.has(
        heatSpotKey({ id: spot.locationId, name: spot.locationName }),
      ) ?? true),
    }
    : null;

  /**
   * Opens exactly ONE sheet layer (the list, the four-day location sheet or a spot sheet), taking down whatever else
   * was there.
   *
   * <p>Two of them open together would answer one Escape press twice, which breaches the one-layer-per-press rule, and
   * {@code useDialogFocus} is deliberately not a focus trap, so a keyboard reader can Tab back onto a layer under a
   * backdrop. Making them exclusive rather than ordering them is the smaller change and the better one: one layer, one
   * press to take it off.
   */
  const openSheetLayer = useCallback((next) => {
    setSheetSpot(next?.spot ?? null);
    // Every caller here is a PLAN surface, which carries no map window — and clearing rather than
    // leaving it is what stops one route's focused window riding onto another's sheet.
    setSheetWindowKey(null);
    setSheetKey(next?.sheetKey ?? null);
    setSheetScope(next?.sheetScope ?? null);
  }, []);
  /**
   * Takes every dialog this shell owns down when `App`'s settings dialog opens — by whichever route.
   *
   * <p>⚠️ {@code UserSettingsModal} is a SIBLING of this shell in `App`: it is not a `Modal` rendered
   * here, {@code stackedOverLayer} cannot see it, and it takes no {@code stacked} opt-in. So it cannot
   * be ordered against a dialog of ours — it can only arrive with none of ours up, or two elements
   * claim {@code aria-modal="true"} and the lower one's Escape listener, still armed, closes the
   * dialog the reader cannot see. The cog and the tick line's nudge are this shell's own controls and
   * close first themselves. The Map tab's ⌂ is not: with no postcode saved it opens settings through
   * the map pane's own {@code onOpenSettings}, which `App` hands the pane directly — and it is
   * reachable while a dialog of ours is up, because the four-day sheet opens OVER the map (O-18) and
   * the map under it is a whole interactive pane a keyboard reader can Tab onto (O-20 arm A). So
   * `App` says when its dialog is open, and the rising edge takes ours down — through
   * {@link closeShellLayers}, which moves nothing: settings is not a destination, and
   * from the peek the reader must be left on the map they were back-tracking to.
   *
   * <p>During render rather than in an effect, which is what keeps it in the SAME commit as the
   * dialog it yields to — React re-renders this component before committing when it sets its own
   * state during render, so no commit holds both. An effect's close would land in a second commit:
   * the DOM would hold both claiming the modal in between, and the settings dialog would already
   * have recorded its return address, so this route would send focus back somewhere different from
   * the menu's and the nudge's, whose closes share a commit with the open. (Placed below every state
   * {@link closeShellLayers} writes, which calling it during render requires.)
   *
   * <p>Keyed on the edge, not the level. A dialog of ours opened WHILE settings stands — by Tabbing
   * out of it onto the page — is the reverse route, of the same family as the Tab-out residual
   * plan-matrix §11c records, and is not answered here: closing on the level would make every
   * control behind the settings backdrop a dead one instead.
   */
  const [settingsWasOpen, setSettingsWasOpen] = useState(settingsOpen);
  if (settingsOpen !== settingsWasOpen) {
    setSettingsWasOpen(settingsOpen);
    if (settingsOpen) closeShellLayers();
  }
  // Operations opening by ANY route (the menu, the aurora banner, a typed or pasted `#manage` address, Forward) takes
  // every dialog of the screen it covers down in the same commit (one-screen-plan.md D9). Same during-render edge.
  const [operationsWasShown, setOperationsWasShown] = useState(operationsShown);
  if (operationsShown !== operationsWasShown) {
    setOperationsWasShown(operationsShown);
    if (operationsShown) closeShellLayers();
  }
  /**
   * The map's address facts and load-time answers, memoised once: the served briefing's neighbour-outlier marks, for
   * `resolveDoor` (App is outside the provider and the scores endpoint carries no flag). Rides every trigger to `App`,
   * which moves it into the resolution context; `doorFromTrigger` never copies it into the door.
   */
  const outlierKeys = useMemo(() => outlierKeysOf({ days: briefing?.days }), [briefing?.days]);
  /** Whether the map view is open, readable from a callback that was made on an earlier render. */
  const mapOpenRef = useRef(false);
  // A layout effect, so a close's own layout effect (below) and any cleanup that runs after it read the new value.
  useLayoutEffect(() => { mapOpenRef.current = mapOpen; });
  /**
   * The element a door was pressed on, for focus to return to when the view closes. Safari and Firefox on macOS do
   * not focus a button on a click, so a door passes its own node when it has one; otherwise the focused element.
   */
  const resolveFrom = (from) => {
    if (from instanceof HTMLElement) return from;
    const active = document.activeElement;
    return active instanceof HTMLElement && active !== document.body && active !== document.documentElement
      ? active : null;
  };
  /**
   * Opens the map view, or re-targets it when it is already open: the ONE function behind every door onto the map
   * (`docs/engineering/one-screen-plan.md` D2) — a Glance card or lane, a Coming up card or chip, a Find pick, a spot
   * sheet row, the location sheet's footer, the area sheet's foot, Ask's "Show on map", the aurora banner, the
   * and the page's own address at load.
   *
   * <p>Underneath it keeps today's handoff (`mapTabHandoff` → `planHandoff` → `MapView`'s `planDoor`), so the landing,
   * lens, scope and camera are exactly what a door has always done. {@code window} and {@code focus} describe the
   * request in the plan's terms; {@code trigger} is the older normalised trigger (`utils/mapDoor.js`), which the
   * existing producers still hand over and which wins when both are given.
   *
   * <p>⚠️ <b>Takes the layers down first</b> ({@link closeShellLayers}): a sheet or Find unwinds and its Back entry
   * goes before the view's is taken, so the same commit swaps one for the other (the engine's same-depth swap).
   * Already open is {@code inPlace}: the window moves and the lens, scope and camera stay (`App`'s `source: 'map'`
   * branch), and no layer is added, so no history entry is pushed.
   *
   * @param {object} [request]
   * @param {?{date: string, eventType: string}} [request.window] null opens on the map's own window
   * @param {?object} [request.focus] `{kind: 'location', id|name}`, `{kind: 'region', name}`,
   *        `{kind: 'names', focusNames}`, `{kind: 'aurora'}` or null
   * @param {?Element} [request.from] the element focus returns to; the focused element when omitted
   * @param {?object} [request.trigger] a normalised trigger from `normalizeMapTrigger`
   * @param {boolean} [request.loadTime] the open came from the page's own address, not a press
   * @param {?string} [request.note] a line the view shows under its header
   * @param {?string} [request.frame] `'home-and-place'` for a window door: the camera frames home and its best place
   */
  const openMap = (request = {}) => {
    const {
      window: win = null, focus = null, from = null, trigger = null, loadTime = false, note = null, frame = null,
    } = request;
    const inPlace = mapOpen;
    let door = trigger;
    if (!door && win?.date) {
      const place = focus?.kind === 'location'
        ? (focus.name ?? (Array.isArray(locations) ? locations.find((l) => l?.id === focus.id)?.name : null) ?? null)
        : null;
      if (focus?.kind === 'names') {
        door = {
          date: win.date,
          targetType: win.eventType,
          region: null,
          locationName: null,
          focusNames: focus.focusNames,
        };
      } else if (focus?.kind === 'aurora') {
        door = normalizeMapTrigger({ kind: 'aurora', date: win.date });
      } else if (focus?.kind === 'astro') {
        door = normalizeMapTrigger({ kind: 'astro', date: win.date });
      } else if (focus?.kind === 'region') {
        door = normalizeMapTrigger({ date: win.date, eventType: win.eventType, region: focus.name });
      } else if (place) {
        door = normalizeMapTrigger(win.date, win.eventType, place);
      } else {
        door = normalizeMapTrigger({ kind: 'event', date: win.date, eventType: win.eventType });
      }
    }
    // A WINDOW door's camera (`'home-and-place'`), set only by the callers that open a window (a Glance card, a lane,
    // a Your places chip); a door about a place (the location sheet, Find, Ask, the area sheet) never passes one.
    if (door) door = { ...door, outlierKeys, ...(frame ? { frame } : {}) };
    if (door && onOpenMapTab) {
      openMapDoor({
        // Every map door takes the layers under it down too, the area sheet included (it can sit under a place sheet).
        openSheetLayer: () => closeShellLayers(),
        onOpenMapTab,
        ratingLens,
        reachLens,
        // ⚠️ `inPlace`: a door pressed while the view is ALREADY open is not a door. `App`'s in-place branch explains
        // what a full door payload would silently undo there (the map's own rating floor, its reach tier, its scope
        // and its camera).
        door: { ...door, inPlace },
      });
    } else {
      closeShellLayers();
    }
    // With no map pane (nothing to map) the door's handoff still goes out and the layers still close, but there is no
    // view to show: a control onto nothing is withheld upstream, so this is only reached by a caller that had none.
    if (!inPlace && hasMapPane) {
      setMapView({ loadTime, from: resolveFrom(from) });
      // The layer's address, seeded from the request so it is right from the first commit; the pane publishes the
      // live route from then on (`MapScreenContext.publishRoute`).
      const placeName = focus?.kind === 'location' ? focus.name : (door?.kind === 'location' ? door.locationName : null);
      const placeId = focus?.kind === 'location' && focus.id != null
        ? focus.id
        : (placeName && Array.isArray(locations) ? locations.find((l) => l?.name === placeName)?.id ?? null : null);
      const routeDate = win?.date ?? door?.date ?? null;
      setMapRoute(routeDate
        ? {
          date: routeDate,
          eventType: win?.eventType ?? NIGHT_KIND_TYPE[door?.kind] ?? door?.eventType ?? door?.targetType ?? null,
          locationId: placeId,
        }
        : null);
      warmStackedChunks();
    }
    setMapNote(note);
  };
  /**
   * Closes the map view. {@code restoreFocus: false} is for a route that places focus itself (the "Show in This week"
   * routes, which open a sheet on the screen and return focus there when it closes). Scroll is untouched: the screen
   * never unmounts under the view, it only goes inert, and the view locks the body's scroll while it stands (D11).
   */
  const closeMap = ({ restoreFocus = true } = {}) => {
    mapFocusReturnRef.current = restoreFocus ? 'restore' : 'skip';
    setMapView(null);
    setMapNote(null);
  };
  /** The latest {@link openMap} and {@link closeMap}, for the registration made once on mount. */
  const mapDoorsRef = useRef(null);
  useEffect(() => { mapDoorsRef.current = { open: openMap, close: closeMap }; });
  /**
   * The screen's focus target, for every focus fallback: the map view's title while the view is open, otherwise This
   * week's heading (which carries {@code tabIndex=-1}). Read from the DOM and a ref, because it runs in a close's own
   * cleanup, before a ref would have caught up.
   */
  const screenHeadingNode = useCallback(() => document.getElementById(THIS_WEEK_HEADING_ID), []);
  const screenFocusNode = useCallback(() => {
    const title = mapTitleRef.current;
    // Only a title that is still on screen: a view that is closing has `hidden` set in the same commit.
    const titleShown = mapOpenRef.current && title?.isConnected && !title.closest('[hidden]');
    return titleShown ? title : screenHeadingNode();
  }, [screenHeadingNode]);
  /**
   * Focus, when the view closes: back to the element that opened it if it is still on screen, otherwise the screen's
   * target. A layout effect, so it runs in the commit that removes `inert` and sets `hidden`, before anything paints.
   */
  // The element the open was pressed on, kept by an effect (the close that needs it runs after the state is gone).
  useEffect(() => { if (mapView) mapFromRef.current = mapView.from; }, [mapView]);
  const mapWasOpen = useRef(false);
  useLayoutEffect(() => {
    if (mapWasOpen.current && !mapOpen) {
      const mode = mapFocusReturnRef.current;
      mapFocusReturnRef.current = 'restore';
      if (mode !== 'skip') {
        const from = mapFromRef.current;
        const usable = from?.isConnected && !from.closest('[inert]') && !from.closest('[hidden]');
        // This week's heading, never the view's own (now hidden) title: this runs in the commit that hides it.
        (usable ? from : screenHeadingNode())?.focus({ preventScroll: true });
      }
      mapFromRef.current = null;
    }
    mapWasOpen.current = mapOpen;
  }, [mapOpen, screenHeadingNode]);
  /**
   * The Plan tab's "show this on the map" producers' ONE entry — the spot sheet's rows and the Coming up cards and
   * day chips: {@link normalizeMapTrigger} turns whatever the producer sends into the one trigger shape and
   * {@link openMap} does the rest. `undefined` when `App` offers no Map door (no forecast), so each producer's own
   * `onShowOnMap && ...` gate withholds its control.
   *
   * @param {*}       dateOrHandoff either a plain date string or a handoff object naming its `kind`
   * @param {?string} eventType     SUNRISE/SUNSET for the plain-date call shapes
   * @param {?string} locationName  a specific location, for the location call shape
   */
  // Stable across renders: `openMap` is rebuilt every render, so the latest one is reached through the doors ref. Built
  // once in a lazy state initialiser (not `useCallback`): the React Compiler lint rejects a callback that reads a ref
  // when it is handed to JSX, and the initialiser's closure is the one place that read is invisible to it.
  const [showOnMapStable] = useState(() => (dateOrHandoff, eventType, locationName = null) => mapDoorsRef.current?.open({
    trigger: normalizeMapTrigger(dateOrHandoff, eventType, locationName),
  }));
  const showOnMap = onOpenMapTab ? showOnMapStable : undefined;
  /**
   * Operations' focus (one-screen-plan.md D9). Closing returns focus to the ☰ menu button (the door it was opened
   * through, in the commit that shows the screen again, whichever way it closed: "‹ PhotoCast", Back or Forward).
   * Opening needs nothing here: {@code OperationsScreen} focuses its own heading on mount.
   */
  const operationsWasUp = useRef(false);
  useLayoutEffect(() => {
    if (operationsWasUp.current && !operationsShown) {
      (menuBtnRef.current ?? screenHeadingNode())?.focus({ preventScroll: true });
    }
    operationsWasUp.current = operationsShown;
  }, [operationsShown, screenHeadingNode]);

  /**
   * Registers a "close the dialogs this shell owns" callback with `App` (overlay convergence C4).
   * The aurora banner sits OUTSIDE the shell and, pressed with the map view open, moves nothing - so nothing
   * would take a Map-hosted four-day sheet down before the window changed under it. The closer is
   * {@link closeShellLayers}, the shell's one list of what a dialog is. Kept in a
   * ref refreshed every commit (it closes over this render's state), and registered once.
   */
  const closeOwnDialogs = useRef(null);
  useEffect(() => {
    closeOwnDialogs.current = () => closeShellLayers();
  });
  useEffect(() => {
    registerCloseDialogs?.(() => closeOwnDialogs.current?.());
    return () => registerCloseDialogs?.(null);
  }, [registerCloseDialogs]);
  /**
   * Registers {@code openMap} with `App` (one-screen-plan.md D2): the aurora banner sits OUTSIDE the shell, hands the
   * map its own handoff, and then asks the shell to show the view. Through the ref, so the registration is made once
   * and always reaches this render's closures.
   */
  useEffect(() => {
    registerOpenMap?.({
      open: (request) => mapDoorsRef.current?.open(request),
      close: (options) => mapDoorsRef.current?.close(options),
    });
    return () => registerOpenMap?.(null);
  }, [registerOpenMap]);

  /**
   * The Map tab callout's four-day-sheet handoff (map-tab-v2-plan.md §3 P9) — `openFullMapTab`'s
   * shape, in reverse.
   *
   * <p><b>The sheet lands as the ONLY dialog layer on either route, and the two routes differ only
   * in whether the tab moves.</b> {@code inPlan} is true for the callout's `Open in Plan` button,
   * whose label names the Plan tab, and false for the clamped prose's `Four days here ›`, which is
   * a peek: the map stays on screen behind the sheet and the callout stays mounted, so dismissing
   * the sheet returns the reader to the selection they opened it from (an owner ask — "I'd like it
   * to stay with the map behind, then I can back track on my user journey").
   *
   * <p>⚠️ <b>Both routes go through {@link closeShellLayers}.</b> It is the shell's one list of "take every dialog
   * this shell owns down", so a dialog added later cannot reach one route and miss the other.
   *
   * <p>⚠️ <b>The clearing and the {@code setSheetSpot} must stay in ONE synchronous body.</b>
   * {@link closeShellLayers} clears {@code sheetSpot} (along with the Map list's `sheetKey`), so the
   * {@code setSheetSpot} call below survives only because it is in the SAME React batch: two calls to one setter inside
   * one commit resolve to the LAST one, never the {@code null} made a line earlier.
   *
   * <p>Guarded by its own nonce and its own ref, so a stale handoff is never acted on twice.
   */
  // Declared before the handoff effect that sets them (`react-hooks/immutability`).
  // True while the location sheet up was opened from Ask's SHEET (set by `askOpenInPlan`).
  const askSheetReturnRef = useRef(false);
  // True while the sheet up came from an `inPlan` handoff (set by the handoff effect).
  const sheetTabReturnRef = useRef(false);
  // True while the location sheet up was opened by a Find pick: its close returns focus to the Find button.
  const sheetFromFindRef = useRef(false);
  // True while the area sheet up was opened by a Find pick: its close returns focus to the Find button.
  const areaFromFindRef = useRef(false);
  const sheetHandoffNonce = locationSheetHandoff?.nonce ?? null;
  const lastHandledSheetHandoff = useRef(locationSheetHandoff?.nonce ?? null);
  useEffect(() => {
    if (sheetHandoffNonce == null || sheetHandoffNonce === lastHandledSheetHandoff.current) return;
    lastHandledSheetHandoff.current = sheetHandoffNonce;
    const toScreen = locationSheetHandoff.inPlan === true;
    // `inPlan`: close the map view and open the sheet on the screen (D12); focus returns to the screen when the sheet
    // closes. Otherwise the sheet is layer 1 over the view, which stays.
    if (toScreen) {
      // A handoff arriving from OUTSIDE this component: responding to it is the effect's whole purpose.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      if (mapOpen) closeMap({ restoreFocus: false });
    }
    closeShellLayers();
    setSheetSpot({
      id: locationSheetHandoff.id ?? null,
      name: locationSheetHandoff.name ?? '',
      regionName: locationSheetHandoff.regionName ?? null,
    });
    // The window the map was on, so the sheet opens ON it rather than on its own best — see
    // `MapView.handleOpenLocationSheet`'s note for the defect this closes. Null for every other
    // entry point (search, a field chip, a spot card), which keeps their seeding exactly as it was.
    setSheetWindowKey(locationSheetHandoff.date && locationSheetHandoff.targetType
      ? `${locationSheetHandoff.date}:${locationSheetHandoff.targetType}`
      : null);
    // ⚠️ ONLY on the route that leaves the map view: an external ask arrives with focus wherever the caller (a callout
    // button that is about to be hidden along with its whole view) left it. On the peek route the view is NOT hidden —
    // the button is still on screen behind the sheet — so moving focus to the heading would both send the reader
    // somewhere they did not ask to go and overwrite the very element `useDialogFocus` is about to capture as the
    // sheet's return address.
    if (toScreen) {
      // The opener (on the map view) goes hidden and a browser refuses focus there: close returns to the screen.
      sheetTabReturnRef.current = true;
      requestAnimationFrame(() => screenHeadingNode()?.focus());
    }
    // `setSheetSpot` is deliberately absent from the dependency list — it is rebuilt every render, and the nonce is
    // the trigger already in it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sheetHandoffNonce, locationSheetHandoff]);

  // ⚠️ A key whose card has gone stops rendering but is not released, and the effect that would
  // release it is a `setState` inside `useEffect` that `react-hooks/set-state-in-effect` rejects.
  // The residual is that a window which disappears and returns would re-show the dialog — and it is
  // left undefended deliberately, because the only way a key leaves `windowCards` is the past-event
  // filter or the travel-day set, and `isEventPast` is monotonic in time: an event that has passed
  // does not come back. Fighting the linter for a state the clock cannot produce is the wrong trade.
  // (An admin's rewind — `utils/rewind.js` — is the one thing that moves the clock backwards, and
  // it remounts the whole app rather than relying on this: see `RewindGate` in `App.jsx`.)
  /**
   * Location name → its {@code locationType} array, for the sheet's type control.
   *
   * <p>Passed as a lookup rather than folded into every spot descriptor, for the reason the card
   * already gives for {@code scoreIndex}: {@code buildWindowSpots}' join is documented as briefing
   * plus reach, and folding a third source in would rebuild every window's spot array whenever the
   * roster arrived. Only the sheet reads it, and only while a type control is on screen.
   *
   * <p>{@code App} already holds {@code locations} — so this costs no request. The join
   * itself lives in {@code locationTypes.js}, because two copies of a join is how the five copies
   * that module replaced started.
   */
  const typesByName = useMemo(() => buildLocationTypeMap(locations), [locations]);

  /**
   * The roster record behind the open sheet — its subject tags, its Bortle class and its tide
   * preferences (increment §2's meta row, §3's per-row tide sentence).
   *
   * <p>Joined ID-first and NAME-second, the same rule {@code heatSpots}/{@code lookupForWindow}
   * apply throughout, and for the same reason: a location the briefing rates but
   * {@code GET /api/locations} has not published yet (a fresh entry, a poll landing between the two
   * fetches) resolves to nothing — and null is the honest answer there, since the sheet omits the
   * row rather than rendering blanks.
   */
  /** The callout's own prose fallback, built only while a sheet is open. */
  const sheetGlossIndex = useMemo(
    () => (sheetSpot ? buildRegionGlossIndex(briefing?.days) : null),
    [sheetSpot, briefing?.days],
  );

  const sheetLocation = useMemo(() => {
    if (!sheetSpot || !Array.isArray(locations)) return null;
    return locations.find((l) => (sheetSpot.id != null && l?.id === sheetSpot.id))
      || locations.find((l) => l?.name === sheetSpot.name)
      || null;
  }, [sheetSpot, locations]);
  // Back closes the Plan dialogs (back-closes-dialogs-plan.md, PR 2b). Each registers from the shell's DERIVED open
  // state, outside every `Suspense`, never from the lazy dialog's mount: a cold chunk neither delays the entry nor
  // turns a swap into a pop and a push, and a card that leaves `windowCards` on a data refresh unregisters (the engine
  // traverses). `onBack` is the close Escape runs. Always registered while open, never mirrored from `escapeEnabled` or
  // `stacked`: the store's order picks the top layer. An `openSheetLayer` swap A -> B keeps the same layer open, so it is
  // the same depth: no `go`, no extra push.
  useBackLayer({ open: sheetCard != null, onBack: () => openSheetLayer(null) });
  useBackLayer({ open: sheetSpot != null, onBack: () => openSheetLayer(null) });
  useBackLayer({ open: areaSheet != null, onBack: () => setAreaSheet(null) });
  // The map view is the BASE layer (one-screen-plan.md D3): it owns one history entry whose address is the layer's
  // url (`#map/<date>/<event>[/<id>]`, written by the engine, D4), and a sheet, Find or the List stacks over it. Back
  // closes the top layer first and the view last. A view opened from the page's own address is
  // activation-gated: the first gesture pushes its entry, until then Back is the browser's.
  const mapUrl = mapOpen ? formatMapRoute(mapRoute) : undefined;
  useBackLayer({
    open: mapOpen,
    mode: 'close',
    url: mapUrl,
    activationGated: mapView?.loadTime === true,
    onBack: () => closeMap(),
  });
  useBackLayer({ open: searchSeed != null, onBack: () => setSearchSeed(null) });
  /**
   * The served topics, indexed once for the whole page.
   *
   * <p>The matrix builds its own for its cards; this one feeds the banner's window topics, and both call
   * {@code buildTopicIndex} on the same field. That is the plan's A8 rule in force — one join, one
   * scope filter, in {@code windowFirstTopics.js} — and it is why the consumers take an index rather than a list of
   * pre-joined rows: the rows are per window.
   */
  const topicIndex = useMemo(() => buildTopicIndex(briefing?.hotTopics), [briefing?.hotTopics]);

  // Lifted to App for the Map tab's doors. Without this a tile handed to the map lands on a map that
  // has filtered out every unrated pin - see the provider's note on why this arm fetches them at all.
  useEffect(() => {
    onEvaluationScoresChange?.(evaluationScores);
  }, [evaluationScores, onEvaluationScoresChange]);

  // The same lift for the seasonal features. `briefing?.seasonalFeatures` rather than `briefing`:
  // the provider replaces that object on every poll and every window focus, and depending on the
  // parent would re-fire this on each one.
  useEffect(() => {
    onSeasonalFeaturesChange?.(briefing?.seasonalFeatures ?? []);
  }, [briefing?.seasonalFeatures, onSeasonalFeaturesChange]);

  /**
   * Whether a sheet layer (the Map's spot list, a four-day sheet or the area sheet) is up — the whole of the Escape order.
   *
   * <p>{@code Modal} installs one document-level Escape listener per instance, so two open dialogs
   * both close on a single press. The remedy is not a shared stack but a guard per layer: whichever
   * layer is not on top declines the key, so Escape takes exactly one layer per press (plan-matrix §6 M2.5).
   *
   * <p>⚠️ <b>The stack is TWO deep, never the bundle README's three.</b> Search over a sheet is refused: every route
   * into search is refused while this flag stands (plan-matrix §4 A22), so a sheet and search are alternatives, and each
   * {@code escapeEnabled} prop below is written for whichever one is up. (The window popup this order was written
   * around was deleted in glance-to-map-plan.md G2b; the area sheet under a place sheet is the stack that remains.)
   */
  const stackedOverLayer = sheetCard != null || sheetSpot != null || areaSheet != null;
  /**
   * The same question with search and the guide folded in: whether any modal layer of the shell is up, which is what
   * disables Ask's entry points while one stands.
   */
  const modalOpenOverLayer = stackedOverLayer || searchSeed != null || guideModal;

  /**
   * Ask PhotoCast's entry and sheet (plan §2.6, F1b).
   *
   * <h2>Where each entry shows</h2>
   * <p>Below 640px the 48px bar, on the screen ONLY — the phone Map gets its entry in the peek
   * sheet (F4) and Operations gets none. From 640 to 1023px the 260px field beside the tab list on
   * those two tabs and on Map (on a tablet the Map's controls are not a peek sheet, so the sheet is
   * the surface). From 1024px the same field (340px with the {@code /} hint from 1180px) opens the
   * DOCK on Plan, Coming up and Map — {@code askDockEntry} below, which is deliberately not a value of
   * {@code askEntry}: {@code askEntry} says "a modal sheet can be drawn" and drives the app container's
   * {@code inert}, and the dock is not modal.
   * {@code availability} decides whether Ask exists at all: {@code pending} and {@code off} draw
   * nothing (a flash on a server with the flag off is the failure), {@code down} draws the entry
   * disabled. Under a rewind there is no provider, so the context's default {@code off} applies and
   * no surface exists — that rule is {@code App}'s, not a test made here.
   *
   * <h2>One modal, held by this component</h2>
   * <p>The sheet claims {@code aria-modal}, so while it is up nothing else may be a dialog. That is held
   * four ways, because {@code useDialogFocus} is deliberately not a focus trap: the entry is DISABLED
   * while any shell dialog or settings is open (opening Ask never closes one — a refusal, not a
   * take-down), and {@code openAskSheet} refuses over a dialog this shell does not own; the whole React app
   * container is {@code inert} while the sheet is open (the layout effect below), so a keyboard
   * reader who Tabs out of the sheet reaches neither a card behind the scrim nor a banner above the
   * shell; and {@link closeShellLayers} closes the sheet on any route off the screen.
   */
  // A reader who is not PRO or ADMIN sees every entry LOCKED: a live trigger with a "Pro" pill inside it that
  // opens the worded upsell (docs/engineering/ask-upsell-plan.md). `locked` draws wherever `on` and `down` do;
  // the sheet and the dock openers and the `/` key still refuse it, so nothing of Ask itself is reachable.
  const askLocked = ask.availability === 'locked';
  const askEntry = (() => {
    if (ask.availability !== 'on' && ask.availability !== 'down' && !askLocked) return null;
    // Operations has no Ask entry at all. The phone's map view has its own entry in the peek; the bar belongs to the
    // screen under it.
    if (askSurface === 'phone') return !operationsShown && !mapOpen ? 'bar' : null;
    if (askSurface === 'tablet') return !operationsShown ? 'field' : null;
    return null;
  })();
  // The docked surface's entry: the same tabs as the tablet's field, from 1024px. "Is there a dock to
  // open" — `askDockOpen` is "is it open".
  const askDocked = askSurface === 'desktop' || askSurface === 'wide';
  const askDockEntry = (ask.availability === 'on' || ask.availability === 'down' || askLocked)
    && askDocked && !operationsShown;
  // ⚠️ `askSheetOpen` would otherwise be held while NOTHING draws the sheet — the window crossing 1024px, a
  // phone Map, Ask switched off — and bring the sheet back by itself the next time an entry exists (an
  // iPad turned landscape and back reopened it, unasked, focus and all). So it is let go the render
  // the entry goes: during render and with this component's own setter, the shape the settings edge
  // above uses, so no commit holds both states. The conversation is untouched.
  if (askSheetOpen && askEntry === null) setAskSheetOpen(false);
  const askSheetShown = askSheetOpen && askEntry !== null;
  // Latched the render the sheet first shows (the same during-render shape as the two lines above), so
  // the lazy sheet stays mounted once fetched and a close is not an unmount.
  if (askSheetShown && !askSheetLoaded) setAskSheetLoaded(true);
  if (askEntry === null && askSheetLoaded) setAskSheetLoaded(false);
  // The dock's twin: Operations, a window narrowed below 1024px and Ask switched off all take away what
  // the dock hangs from, and a state left true would bring it back unasked the next time one exists.
  // A tab switch between Plan, Coming up and Map is NOT one of them — see `askDockOpen`.
  if (askDockOpen && !askDockEntry) setAskDockOpen(false);
  /**
   * The docked guide (G2, presentation 1) takes the Ask dock's slot, so the two are exclusive both
   * ways: while the guide is docked the dock is HIDDEN, not closed ({@code askDockOpen} is the
   * remembered {@code dockWasOpen}), and closing the guide brings it back. Opening the dock closes the
   * guide ({@code openAskDock}). On Operations there is no docked row, so the guide is not shown there
   * (GuideProvider closes it on entering the tab).
   */
  const guideDockShown = guide.open && guide.presentation === 'docked' && askDocked && !operationsShown;
  const askDockShown = askDockOpen && askDockEntry && !guideDockShown;
  // The dock returns, after the docked guide closed, WITHOUT stealing focus from the guide's door: the
  // reader asked to leave the guide, not to type. (A door that lived in the dock is gone with it; that
  // case, and the dock-field press that closed the guide, keep the dock's usual focus on its field.)
  // During render, with this component's own setters, like the releases above.
  const [seenGuideDock, setSeenGuideDock] = useState(false);
  const [dockQuiet, setDockQuiet] = useState(false);
  if (guideDockShown !== seenGuideDock) {
    setSeenGuideDock(guideDockShown);
    setDockQuiet(!guideDockShown && askDockOpen && askDockEntry
      && guide.lastClose.restoreFocus && !guide.lastClose.doorInDock);
  } else if (dockQuiet && !askDockShown) {
    setDockQuiet(false);
  }
  /** One flag for the layout: either docked occupant makes the shell root a row. */
  const dockedRow = askDockShown || guideDockShown;
  /** Where the dock column begins, so the map view ends there and leaves the dock beside it, interactive (D12). */
  const dockEdge = useDockEdge(shellRef, mapOpen && dockedRow);
  /** The pane's live `{date, eventType, locationId}`, kept as the same object while nothing changed. */
  const handleMapRoute = useCallback((route) => {
    setMapRoute((prev) => (prev && route
      && prev.date === route.date && prev.eventType === route.eventType && prev.locationId === route.locationId
      ? prev : route));
  }, []);
  /** The guide page the menu's Guide row opens for the screen in force; none on Operations. */
  const guideScreenPage = operationsShown ? null : (SCREEN_GUIDE[mapOpen ? 'map' : 'plan'] ?? null);
  /** The menu's Guide row: open the screen's page, or close the guide if it is already up. */
  const toggleGuide = () => {
    if (guide.open) guide.closeGuide();
    // Safari does not focus a button on click, so there may be no focused door to return to: name the menu's.
    else if (guideScreenPage) guide.openGuide(guideScreenPage, '', { returnFocus: () => menuBtnRef.current });
  };
  const askDialogOpen = modalOpenOverLayer || settingsOpen;
  // Two questions the old `askDisabled` answered at once. `askBlocked` is whether the CONTROL is really
  // `disabled`: the backend is down, the content is disabled, or a shell dialog or settings stands. A locked
  // reader's trigger is a live door, so the lock is not in it (`down` and `locked` cannot both hold). The
  // `/` key is refused for the lock as well, BEFORE its `preventDefault` (`askKeyRefused`), so the key is
  // never live where the control beside it is not, and a LITE reader's `/` still reaches the browser.
  const askBlocked = ask.availability === 'down' || Boolean(contentDisabled) || askDialogOpen;
  const askKeyRefused = askBlocked || askLocked;
  // The entry the upsell would hang from now, and whether it is open: for THAT kind of entry, while the
  // reader is still locked and nothing blocks the control. Anything else lets the state go during render
  // (the `askSheetOpen` shape): a breakpoint change that removes or swaps the entry, Operations, a shell
  // dialog or settings opening, or the role turning PRO. A state left set would bring the popover back
  // unasked, or hold it on an entry that is not the one that was pressed.
  const askEntryKind = askEntry ?? (askDockEntry ? 'dock-field' : null);
  const askUpsellOpen = askUpsellFor !== null && askUpsellFor === askEntryKind && askLocked && !askBlocked;
  if (askUpsellFor !== null && !askUpsellOpen) setAskUpsellFor(null);
  const askViewSpec = mapOpen ? ASK_VIEW.map : ASK_VIEW.plan;
  const askFieldWidth = ASK_FIELD_WIDTH[askSurface] ?? 260;
  /**
   * Opens the sheet. The entry is {@code disabled} (a real attribute, so no press reaches this) while
   * one of THIS shell's dialogs or settings stands; what that flag cannot see is a dialog this shell
   * does not own (the settings dialog, the postcode prompt) — so it is found the way {@code /} finds it (any
   * {@code role="dialog"} outside this root), at press time, and refused with nothing taken down.
   */
  const openAskSheet = () => {
    if (askLocked) return;
    if (foreignDialogOpen(shellRef.current)) return;
    setAskSheetOpen(true);
  };
  /**
   * The ✕, the scrim and Escape: CLOSE, and keep the conversation (plan §2.6, §2.8 — the answer
   * survives a close, and a Plan-card highlight is "applied when the sheet closes"). Ending it is the
   * sheet's own "New conversation" control. A scrim is a full-viewport target and Escape is global; either
   * discarding an answer the reader was charged a question for would be a trap.
   */
  const closeAskSheet = () => setAskSheetOpen(false);
  // Back closes the sheet like the ✕ (the conversation is kept). Registered here, from the open INTENT and outside
  // the lazy `Suspense`, so a cold chunk neither delays the entry nor turns a swap into a pop and a push.
  useBackLayer({ open: askSheetShown, onBack: closeAskSheet });
  /**
   * The Map's List door (glance-to-map-plan.md G1): opens the ranked spot list over the Map for the window the Map has
   * selected, as the shell's own dialog ({@code sheetKey}), so it is a layer OUTSIDE {@code .wf-map-tab} and every Map
   * Escape and outside-press rule stands down for it through {@code foreignModalOver}.
   *
   * <p>Refused (returns false, nothing taken down) while any other layer stands: Ask's sheet, search, the guide, a
   * sheet layer, settings, or any dialog this shell does not own. That is the depth cap
   * ({@code MAX_LAYER_DEPTH}) held in the one place that can see every layer, and it is the same refusal the other
   * way: with the list open, {@code askDialogOpen} counts it, so Ask's entry is disabled. A plain function behind a
   * ref so the door handed to the Map is stable (the pane is {@code React.memo}'d) yet always reads this render.
   */
  const openMapListRef = useRef(null);
  const tryOpenMapList = (request) => {
    if (askSheetShown || stackedOverLayer || searchSeed != null || guideModal || settingsOpen
      || foreignDialogOpen(shellRef.current)) return false;
    if (!request?.key || !(request.spotKeys instanceof Set)) return false;
    if (!windowCards.some((card) => card.key === request.key)) return false;
    openSheetLayer({
      sheetKey: request.key,
      sheetScope: {
        spotKeys: request.spotKeys,
        floorId: request.floorId,
        tierId: request.tierId,
        onPick: request.onPick ?? null,
      },
    });
    return true;
  };
  useEffect(() => { openMapListRef.current = tryOpenMapList; });
  const openMapList = useCallback((request) => openMapListRef.current(request), []);
  const mapListDoor = useMemo(() => ({ openList: openMapList }), [openMapList]);
  /**
   * "Show on map ›" on a pick: select that pick, leave the sheet, land on the Map tab, keep the
   * answer. {@link openMap} is the route (it closes the sheet with the rest of the layers); the
   * numbered markers are F3's, so until then the Map shows nothing extra, and F3 reads the selected
   * pick this leaves. The view's own title takes focus on open, because the bar that opened the sheet is unmounted by
   * this very press and focus would otherwise fall to {@code <body>}.
   *
   * <p>From the DOCK the same route is right for the same reason and costs nothing extra: the dock is
   * NOT on {@link closeShellLayers}'s list, so it stays open beside the map with the conversation in it, and
   * the pressed control — which is not offered on the Map — goes with the tab, so focus is moved
   * rather than left to fall.
   */
  const askShowOnMap = (card) => {
    ask.selectPick(card.rank);
    if (mapOpen) {
      // The tablet's Ask sheet over the map view (F3): the view is already open, but a modal covers it.
      // Closing the sheet is the whole of "show": the map's camera was holding for exactly this
      // (`AskCameraController` waits while a modal stands over the pane) and fits the moment it goes.
      // Focus returns through the sheet's own restore, so nothing is moved here.
      closeAskSheet();
      return;
    }
    // The pressed control is not on the view, and the view's own title takes initial focus on open; the dock (which is
    // not a layer) stays open beside the view with the conversation in it.
    openMap({ from: null });
  };
  // The controls on a pick card's own row, AFTER the "Plan this ›" every card carries (that one is a move
  // of the conversation and `AskConversation` draws it on every surface, the phone's peek included). A
  // function whether or not it has anything to draw. "Show on map ›" is offered only where it goes
  // somewhere: a Map pane exists, and the reader is not already on it.
  // ...and, since F3, on the tablet's Ask SHEET over the Map: the picks are numbered on a map the sheet
  // covers, and this is the press that uncovers it (the dock, which covers nothing, is not offered it).
  const askCanShowOnMap = mapPane != null && (!mapOpen || askSheetShown);
  const askPickActions = (card) => (askCanShowOnMap ? (
    <button
      type="button"
      className="wf-ask-act"
      data-testid={`ask-show-on-map-${card.rank}`}
      // The visible words lead the name (WCAG 2.5.3), and the place follows so a list of these
      // buttons is not N identical "Show on map"s.
      aria-label={`Show on map — ${card.name}`}
      onClick={() => askShowOnMap(card)}
    >
      Show on map
      <span aria-hidden="true"> ›</span>
    </button>
  ) : null);
  /**
   * Where focus goes on close when the trigger cannot take it back (a tap never focused it, it was
   * unmounted by the press, or it is disabled): the trigger if it is still there, else the tab in
   * force — read from the DOM, since a ref only catches up in a passive effect and this
   * runs in the close's own cleanup, before it.
   */
  const askRestoreFallback = useCallback(() => {
    const trigger = askTriggerRef.current;
    if (trigger?.isConnected && !trigger.disabled) return trigger;
    return screenFocusNode();
  }, [screenFocusNode]);
  /**
   * Closes the Ask upsell. {@code restoreFocus} sends focus back to the trigger FIRST (the popover, or
   * the trigger itself, may be what held it and a focused node that unmounts takes focus to
   * {@code <body>}); the outside press and focus leaving pass false, because focus is already where the
   * reader put it.
   */
  const closeAskUpsell = ({ restoreFocus }) => {
    if (restoreFocus) askRestoreFallback()?.focus({ preventScroll: true });
    setAskUpsellFor(null);
  };
  /**
   * The locked entry's press. Closing always works; OPENING is refused over a dialog this shell does not
   * own (the same refusal the sheet and the dock make), and only the open is guarded, or the trigger could
   * not close a popover that a foreign dialog arrived over.
   */
  const toggleAskUpsell = () => {
    if (askUpsellOpen) {
      closeAskUpsell({ restoreFocus: true });
      return;
    }
    if (foreignDialogOpen(shellRef.current)) return;
    setAskUpsellFor(askEntryKind);
  };
  const isForeignDialogOpen = () => foreignDialogOpen(shellRef.current);
  /**
   * Escape while focus is on the TRIGGER with the upsell open (Shift+Tab from the heading lands there and
   * the popover stays open, so its own handler never sees the key). Stopped for the same reason as the
   * popover's: a `document`-level Escape listener elsewhere on the page (a `Modal`'s) must not take this Escape too.
   * Focus is already on the trigger, so it does not move.
   */
  const onAskTriggerKeyDown = (event) => {
    if (event.key !== 'Escape') return;
    if (foreignDialogOpen(shellRef.current)) return;
    event.stopPropagation();
    event.preventDefault();
    closeAskUpsell({ restoreFocus: false });
  };
  // An outside press closes the upsell. A `document` listener in the CAPTURE phase so nothing on the page
  // (Leaflet stops some of its own pointer events) can hide the press; it swallows nothing, so a Plan card
  // still opens its popup on the same click. ⚠️ Not `useOutsideDismiss`: that ignores presses inside the map
  // frame, and a press on the map must close this popover, which belongs to the masthead and not the map.
  // It stands down over a dialog this shell does not own (the postcode prompt, the four-day sheet), where a
  // press would otherwise close the upsell invisibly behind it. The trigger's own press toggles, so it is
  // excluded here.
  useEffect(() => {
    if (!askUpsellOpen) return undefined;
    const onPointerDown = (event) => {
      if (foreignDialogOpen(shellRef.current)) return;
      const target = event.target;
      if (askUpsellRootRef.current?.contains(target) || askTriggerRef.current?.contains(target)) return;
      setAskUpsellFor(null);
    };
    document.addEventListener('pointerdown', onPointerDown, true);
    return () => document.removeEventListener('pointerdown', onPointerDown, true);
  }, [askUpsellOpen]);
  // Focus leaving through the TRIGGER (Shift+Tab from the heading lands on it, the popover stays open, and a
  // second Shift+Tab goes on into the tab list or the page) never fires the popover's own `onBlur`, which only
  // sees blurs inside the popover. So, while open, any focus arriving outside both the popover and the trigger
  // closes it WITHOUT moving focus. Focus events with a null target are not focus arriving anywhere.
  useEffect(() => {
    if (!askUpsellOpen) return undefined;
    const onFocusIn = (event) => {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (askUpsellRootRef.current?.contains(target) || askTriggerRef.current?.contains(target)) return;
      setAskUpsellFor(null);
    };
    document.addEventListener('focusin', onFocusIn);
    return () => document.removeEventListener('focusin', onFocusIn);
  }, [askUpsellOpen]);
  // A swap strands focus: the trigger (or the popover that held it) unmounts when the entry changes kind
  // or the lock goes, and focus cannot be moved during the render that lets the state go. So, once the
  // upsell has been dropped for THAT reason (not by a close, which put focus back itself, and not by a
  // dialog opening, which owns focus now) and focus is on <body> or detached, send it to the new trigger,
  // else to the tab in force.
  const askUpsellWasOpenFor = useRef(null);
  useLayoutEffect(() => {
    const was = askUpsellWasOpenFor.current;
    askUpsellWasOpenFor.current = askUpsellOpen ? askUpsellFor : null;
    if (was === null || askUpsellOpen) return;
    // A plain close put focus back itself, and a dialog opening owns it now. A block that is NOT a dialog (the
    // backend going down disables the trigger) has no owner, so it falls through to the repair.
    const blockedWithoutDialog = askBlocked && !askDialogOpen;
    if (was === askEntryKind && askLocked && !blockedWithoutDialog) return;
    const active = document.activeElement;
    if (active && active !== document.body && active.isConnected) return;
    const trigger = askTriggerRef.current;
    const target = trigger?.isConnected && !trigger.disabled ? trigger : screenFocusNode();
    target?.focus({ preventScroll: true });
  }, [askUpsellOpen, askUpsellFor, askEntryKind, askLocked, askBlocked, askDialogOpen, screenFocusNode]);
  /**
   * The control in the DOCK that "Open in Plan ›" was pressed on, so closing the location sheet it opened
   * can put focus back there (null on every other host, where the pressed control went with its surface).
   */
  const askOpenerRef = useRef(null);
  /**
   * The location sheet's focus-return address when its opener cannot take focus back: the Ask trigger (or the
   * tab) for a sheet opened from Ask's sheet, whose own close leaves nothing holding focus; the tab in force for
   * an {@code inPlan} handoff, whose opener goes hidden with the Map pane; otherwise null, so
   * {@code useDialogFocus}'s own restore stands. Read at CLOSE time and reset when the sheet is gone.
   */
  const askSheetFallback = useCallback(
    () => {
      if (askSheetReturnRef.current) return askRestoreFallback();
      // A sheet a Find pick opened goes back to the Find button, the door the reader came through (Safari and
      // Firefox on macOS do not focus a button on a click, and a ⌘K open has no focused trigger at all).
      if (sheetFromFindRef.current) {
        const find = shellRef.current?.querySelector('[data-testid="window-first-search"]');
        if (find?.isConnected) return find;
      }
      return sheetTabReturnRef.current ? screenFocusNode() : null;
    },
    [askRestoreFallback, screenFocusNode],
  );
  /**
   * "Open in Plan ›" on a pick's "Plan this" view (F5, plan §2.8): the Plan tab, the pick's own location
   * sheet, opened AT the pick's window.
   *
   * <p><b>The two-deep rule, route by route.</b> {@link closeShellLayers} is the one list that takes
   * every dialog this shell owns down, and it is where the ASK SHEET goes (it sets {@code askSheetOpen} false),
   * so the location sheet is the single modal on the tablet and the phone. The DOCK is not a layer and is
   * not on that list: it stays open beside the plan the reader asked to see, and turns {@code inert}
   * for as long as the sheet is up ({@code askDialogOpen} reads {@code sheetSpot} through
   * {@code stackedOverLayer}). A dialog this shell does not own (settings, the postcode prompt) refuses the press, as
   * {@code openAskSheet} does — a second {@code aria-modal} over it is the thing the rule is for.
   *
   * <p>The sheet is the Plan tab's own, with the window the handoff effect above gives the map's callout:
   * {@code sheetWindowKey} is {@code date:targetType}, so it opens on the pick's window rather than on its
   * own best. {@link closeShellLayers} clears {@code sheetSpot}, and the setter after it wins only because both
   * are in ONE batch — the reason that effect's own note records, and why this is a handler and not
   * an effect.
   *
   * <p><b>Focus, out.</b> From the dock the pressed button stays on screen but goes {@code inert}, and
   * {@code AskDock}'s own rescue hands focus to the tab in force in that commit — which is where the
   * sheet's restore sends it on close. The reader pressed a control in the dock, so when the sheet has
   * gone and focus was left on the tab (or nowhere) it is returned to that control. On the sheet
   * surfaces the pressed control went with the Ask sheet and its own restore has already done its work.
   */
  const askOpenInPlan = (card, pressed = null) => {
    // Ask's own sheet is a dialog outside this root, and this very press closes it: it is not a second
    // modal to refuse for. Every other dialog this shell does not own still is.
    const closesWithThisPress = (node) => askSheetShown && node.getAttribute('aria-label') === ASK_SHEET_LABEL;
    if (foreignDialogOpen(shellRef.current, closesWithThisPress)) return;
    // The control that was pressed — passed by the view, because Safari and Firefox on macOS do not focus a
    // button on a mouse press, so `activeElement` would be <body> there and there would be nothing to return to.
    const opener = pressed instanceof HTMLElement ? pressed : document.activeElement;
    askOpenerRef.current = askDockShown && opener instanceof HTMLElement ? opener : null;
    askSheetReturnRef.current = askSheetShown;
    warmStackedChunks();
    // The sheet opens on the screen: the view (if it was open) goes, and focus returns to the screen on close.
    if (mapOpen) closeMap({ restoreFocus: false });
    closeShellLayers();
    setSheetSpot({
      id: card.locationId ?? null,
      name: card.name,
      regionName: card.regionName ?? null,
    });
    setSheetWindowKey(card.windowKey);
  };
  // The area sheet's focus-return flag is spent once it is gone (the dialog's own restore has read it by then: a
  // child's cleanup runs before this effect).
  useEffect(() => {
    if (areaSheet == null) areaFromFindRef.current = false;
  }, [areaSheet]);
  useEffect(() => {
    if (sheetSpot != null) return;
    askSheetReturnRef.current = false;
    sheetTabReturnRef.current = false;
    sheetFromFindRef.current = false;
    const opener = askOpenerRef.current;
    if (!opener) return;
    askOpenerRef.current = null;
    const focused = document.activeElement;
    const nowhere = !focused || focused === document.body || focused === document.documentElement;
    // Only a reader who was left where the sheet's own restore parks them — the screen's heading, which the
    // dock's rescue chose — or on nowhere is sent back. Anywhere else they have Tabbed is a choice: the same rule
    // `useDialogFocus`'s own restore keeps.
    if ((nowhere || focused === screenHeadingNode())
      && opener.isConnected && !opener.closest('[inert]')) {
      opener.focus({ preventScroll: true });
    }
  }, [sheetSpot, screenHeadingNode]);
  /**
   * The postcode nudge inside "Plan this": the tick line's own route (close what is open, then
   * settings on the postcode field), with the Ask return address as its restore target.
   */
  const askSetPostcode = () => {
    closeShellLayers();
    (onSetPostcode ?? onOpenSettings)?.(askRestoreFallback);
  };
  const askPlanActions = { openInPlan: askOpenInPlan, setPostcode: askSetPostcode };
  /**
   * The Plan card the selected pick lands on, for the matrix's highlight (F5, plan §2.8): a map of
   * {@code date:targetType} to the pick's rank, empty unless it can be SEEN — the Plan tab is the one in
   * force and Ask's SHEET is not covering it (on a phone or tablet the sheet is a modal over the pane, and
   * the highlight, and the scroll it brings, are "applied when the sheet closes"). The pick the plan view is
   * about IS the selected one ({@code openPlan} selects it, and choosing another leaves the plan).
   * Filter/map/select over the conversation's own joined card.
   */
  const askActiveRank = ask.selectedPick;
  const askAvailable = ask.availability === 'on' || ask.availability === 'down';
  const askHighlight = useMemo(() => {
    // Off the moment Ask is: a server that answered 404 hides every surface but keeps the conversation, and a
    // ring with no surface left to clear it would be stuck on the card for the session.
    if (!askAvailable || operationsShown || mapOpen || askSheetShown || askActiveRank == null) return NO_HIGHLIGHT;
    const card = ask.pickCards.find((c) => c.rank === askActiveRank);
    // ⚠️ A NIGHT pick highlights no card: the matrix is a grid of solar windows, and no card of it is an
    // astro or aurora night (`docs/engineering/ask-night-plan.md` §2.6). Its `date:ASTRO` key would match
    // nothing and harm nothing — it is left out so that "nothing" is said here and not by a lookup that fails.
    return card && !card.night ? new Map([[card.windowKey, card.rank]]) : NO_HIGHLIGHT;
  }, [askAvailable, operationsShown, mapOpen, askSheetShown, askActiveRank, ask.pickCards]);
  /**
   * Where the dock's focus goes if the dock stops being somewhere focus can be while it holds it
   * (it turns {@code inert} under a dialog or a dead backend, or is released because the window
   * narrowed past 1024px): the tab in force. Never the field, which is disabled in the first case
   * and about to be replaced by another in the second. See {@code AskDock}'s {@code useKeepFocusAlive}.
   */
  /**
   * The field's press from 1024px, and the whole of what {@code /} does once it is past its
   * refusals: open the dock, or — when it is already open — put the cursor back in its question field
   * (the field is "on" and a second press must not close what the reader just asked for). Refused
   * over a dialog this shell does not own, exactly as {@code openAskSheet} is; the field is
   * {@code disabled} while one of its own stands. Opening focuses the field from the dock's own mount.
   */
  const openAskDock = useCallback(() => {
    if (askLocked) return;
    if (foreignDialogOpen(shellRef.current)) return;
    // The docked guide holds the dock's slot: opening the dock ends it (focus is not sent back to the
    // guide's door, since the reader is going to the dock's field).
    if (guideDockShown) {
      guide.closeGuide({ restoreFocus: false });
      setAskDockOpen(true);
      return;
    }
    if (askDockShown) askDockInputRef.current?.focus({ preventScroll: true });
    else setAskDockOpen(true);
  }, [askDockShown, askLocked, guideDockShown, guide]);
  /**
   * The ✕ and Escape inside the dock: CLOSE, keep the conversation, and put focus on the field.
   *
   * <p>Focus moves BEFORE the state changes. The ✕ — or whatever inside the dock held focus — goes with
   * the dock, and a focused node that unmounts takes focus to {@code <body>} with it, after which no
   * Escape rule that runs "while focus is inside a surface" is ever reached again (the defect the Map
   * tab's panels had five times). {@code askRestoreFallback} is the sheet's own answer to a field that
   * cannot take it: the tab in force.
   */
  const closeAskDock = () => {
    askRestoreFallback()?.focus({ preventScroll: true });
    setAskDockOpen(false);
  };
  /**
   * The page behind the sheet, dead while it is open: {@code inert} on the app's own container (the
   * ancestor of this shell that sits directly under {@code <body>} — {@code #root} in production).
   * The sheet is portalled to a different child of {@code <body>}, so it is not covered; the banners
   * above the shell, the footer, the masthead and the screen all are.
   *
   * <p>⚠️ <b>A layout effect, and an attribute set by hand, on purpose.</b> {@code useDialogFocus}
   * restores focus to the opener in a PASSIVE cleanup when the sheet closes, and a node inside an
   * {@code inert} subtree cannot take focus. This effect's cleanup runs earlier in the SAME commit, so
   * {@code inert} is already gone when the restore runs. The same attribute driven by a React state in
   * an ancestor would come off one commit later, after the restore had failed. The reverse is handled
   * too: setting it blurs the opener before the sheet reads it as its return address, which is why the
   * sheet takes {@code restoreFallback}. jsdom has no {@code inert}; tests assert the attribute.
   */
  useLayoutEffect(() => {
    if (!askSheetShown) return undefined;
    let container = shellRef.current;
    while (container?.parentElement && container.parentElement !== document.body) {
      container = container.parentElement;
    }
    // Never <body> or <html> themselves: a shell mounted straight into <body> has no container below it.
    if (!container || container === document.body || container === document.documentElement) {
      return undefined;
    }
    // Not ours to set, nor to clear, if something else already holds it.
    if (container.hasAttribute('inert')) return undefined;
    container.setAttribute('inert', '');
    return () => container.removeAttribute('inert');
  }, [askSheetShown]);
  const dimmed = contentDisabled ? ' opacity-50 pointer-events-none' : '';
  // The shared tiers, not a local copy: `generatedAt` is a zone-less UTC instant, and the one
  // formatter that already knows that is the one that appends the Z. Hand-rolling it here read an
  // hour young in BST — parsing bare takes the string as local, so a 34-minute-old forecast said
  // "1h ago". Caught by looking at the running app, not by a test.
  const age = formatRelativeAge(briefing?.generatedAt);
  /**
   * The four-day sheet's two derived inputs, both gated on the sheet being open (P8).
   *
   * <p>Neither is cheap enough to build unconditionally. {@code buildSlotTimeIndex} walks every
   * slot of every event summary of every day — the roster times the rendered windows — and
   * {@code scopeRegions} walks the whole heat catalogue against the reach map. Both rebuild on
   * every poll, and no other surface reads either, so the null-until-open guard is what keeps a
   * reader who never searches for a place from paying for a dialog they never opened.
   *
   * <p>They live here rather than inside the sheet because the sheet is lazy: putting them behind
   * the {@code Suspense} boundary would mean the dialog's first paint waits on a chunk fetch AND
   * then does the walk, which is the one frame a reader is watching.
   */
  const slotIndex = useMemo(
    () => (sheetSpot ? buildSlotIndex(briefing?.days) : null),
    [sheetSpot, briefing?.days],
  );
  /**
   * The tide-fit block's source on this sheet (T5, `docs/engineering/tide-window-plan.md`) — the
   * SAME join `WindowFirstMapPane.jsx`'s own `tideAlignmentIndex` builds for the Map tab's chip and
   * callout, over the SAME `briefing.days`, so a location's tide fact never disagrees between the
   * card that opened this sheet and the sheet itself. Null-until-open, like `slotIndex` beside it:
   * nothing reads this while the sheet is closed, so building it unconditionally would cost every
   * poll a walk of the whole roster for a dialog most polls never open.
   */
  const sheetTideAlignmentIndex = useMemo(
    () => (sheetSpot ? buildTideAlignmentIndex(briefing?.days) : null),
    [sheetSpot, briefing?.days],
  );
  /**
   * The eclipse spot line's source on this sheet (L7, `docs/engineering/lunar-eclipse-plan.md`) —
   * `buildEclipseIndex(briefing.days)`, which the sheet hands to its `DawnRace`, so a location's own moon
   * geometry is read from the served briefing days. Null-until-open, like {@code sheetTideAlignmentIndex}
   * beside it.
   */
  const sheetEclipseIndex = useMemo(
    () => (sheetSpot ? buildEclipseIndex(briefing?.days) : null),
    [sheetSpot, briefing?.days],
  );
  /**
   * The detail surfaces' ratings, built from the RAW rows rather than taken from {@code scoreIndex}.
   *
   * <p>The provider's index is keyed on {@code date|targetType|locationName} alone; this one joins
   * id-first, like every other join in the arm and like {@code buildSlotIndex} beside it. The
   * provider's own note asked for exactly this and named P8 while doing so — the first cut ignored
   * it and an adversarial review caught the consequence: a renamed location timed correctly and
   * rated as unscored, under a heat field that still painted its star.
   */
  // Your places (F4): whether the row at the top of This week holds anything to rate (a Pro reader's list has landed
  // and is not empty; a Lite reader's teaser needs no scores).
  const watchlistShown = favouritesState.status === 'ready'
    && (favouritesState.placeIds.size > 0 || favouritesState.areaIds.size > 0);
  const detailScoreIndex = useMemo(
    // ⚠️ TWO readers since M3, and the gate widened with them: search's location rows print the
    // place's own best window from the same id-first index, so the box and the sheet it opens can
    // never disagree about what a place is rated. Still gated — a reader who opens neither pays
    // nothing, which is the whole point of building it here rather than in the provider.
    // ⚠️ A THIRD reader since F2: the area sheet prints each of its spots' own best window from this index. It stays
    // open after Find has closed (`searchSeed` is null by then), so without it every row read "Not rated".
    // ⚠️ A FOURTH reader since F4: the Coming up watchlist prints each bookmarked place's own best window from it.
    () => ((sheetSpot || searchSeed != null || areaSheet != null || watchlistShown) ? buildScoreIndex(scoreRows) : null),
    [sheetSpot, searchSeed, areaSheet, watchlistShown, scoreRows],
  );
  /**
   * The region names the page is planning over — the planning area at home, the origin's own region
   * away. It is the SCOPE, never the reach lens: the sheet's "outside your plan" badge reports that
   * a place is not in the plan the reader framed, and a spot three hours out is still somewhere
   * they could go.
   *
   * <p>⚠️ Built from {@code reachById}, the HOME map, not {@code effectiveReachById}. The planning
   * area is a statement about home — the provider publishes both side by side for exactly this
   * reason — and the away arm ignores the map entirely.
   *
   * <p>No longer gated on a sheet being open, because it has a second reader: the deleted window popup's topic
   * scope filter (A8 rule 2) is the same question about the same scope, and the matrix already
   * makes this exact call on every render for the cards' own filter. One memo over three stable
   * inputs is cheaper than two calls and — the reason that matters — makes it impossible for the
   * cards and the deleted window popup they open to be filtered against two different scopes.
   */
  const planScopeNames = useMemo(
    () => scopeRegions(heatSpots, reachById, origin),
    [heatSpots, reachById, origin],
  );
  /**
   * The keys of the windows Plan renders a card for — the only windows a Coming up lane can open
   * (Plan shows six; passed and travel-day windows are dropped), so the lanes' tap target is chosen
   * from this set and falls back to plain {@code goToPlan} when none of a segment's windows is in it.
   */
  const planWindowKeys = useMemo(
    () => new Set(windowCards.map((card) => card.key)),
    [windowCards],
  );
  /**
   * The map view at load (`docs/engineering/one-screen-plan.md` D4, D10): the page's own `#map/...` address (read
   * once by `App`, which already rewrote the address bar to the bare path). Every device opens on This week; the view
   * opens at load only for an address. Waits for the map pane and the briefing, so the window can be judged. A route
   * names a window and optionally a place: a past or unforecast window opens the view on the map's own window with a
   * one-line note, an unknown or disabled place opens with no focus (Lite is focused like anyone else: the roster is
   * role-free). An address is a request and is always honoured. Its Back layer is activation-gated, so the first
   * gesture pushes the entry.
   */
  const loadRouteHandledRef = useRef(false);
  useEffect(() => {
    if (!loadMapRoute || loadRouteHandledRef.current || !hasMapPane || loading) return;
    if (loadMapRoute.date && forecastDates == null) return;
    loadRouteHandledRef.current = true;
    const { date, eventType, locationId } = loadMapRoute;
    const status = routeWindowStatus({ date, eventType }, { todayStr, forecastDates, windowKeys: planWindowKeys });
    const win = date && status === 'ok' ? { date, eventType } : null;
    const place = win && locationId != null && Array.isArray(locations)
      ? locations.find((l) => l?.id === locationId) : null;
    let note = null;
    if (status === 'past') note = `That ${LOAD_WORD[eventType] ?? 'window'} has passed`;
    else if (status === 'unforecast') note = 'Not forecast yet';
    // Responding to an address that arrives from OUTSIDE this component is the effect's whole purpose, once.
    // A night's address names the night, not a place: the aurora door and the astro night door reopen it.
    let focus = place ? { kind: 'location', id: place.id, name: place.name } : null;
    if (win?.eventType === 'AURORA') focus = { kind: 'aurora' };
    else if (win?.eventType === 'ASTRO') focus = { kind: 'astro' };
    // eslint-disable-next-line react-hooks/set-state-in-effect
    openMap({
      window: win,
      focus,
      loadTime: true,
      note,
    });
    onLoadMapRouteHandled?.();
    // `openMap` is rebuilt every render; the handled ref is the once-only guard.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadMapRoute, hasMapPane, loading, forecastDates, todayStr, planWindowKeys, locations]);
  /**
   * A window opened on the Map (G2a, glance-to-map-plan.md): a Glance card, a lane's tap-through and a warning lane
   * all land here. The window selects on the Map through the one {@code kind: 'event'} door ({@link showOnMap}, which
   * closes the Plan dialogs first and merges the live lens), and the tapped topic, when there is one, rides it so the
   * Map selects that chip.
   *
   * <p>The view's own Back layer is the way back (D3): Back, Escape and ✕ return the reader to the card they pressed.
   * Pressed while the view is already open (a Find pick) it re-targets the window and pushes nothing. With no Map door
   * (no forecast) the press does nothing, as the Map has nothing to show.
   *
   * @param {string}  key       the `date:TYPE` window key
   * @param {?string} topicType the tapped lane's HotTopic type, or null
   */
  const openWindowOnMap = (key, topicType = null) => {
    if (!showOnMap) return;
    const [date, eventType] = String(key).split(':');
    if (!date || !eventType) return;
    showOnMap({
      kind: 'event', date, eventType, frame: HOME_AND_PLACE,
      ...(topicType ? { topicType: String(topicType).toUpperCase() } : {}),
    });
  };
  /**
   * The sheet footer's origin action — this place's own region, and whether it may be planned from.
   *
   * <p>Matched on region NAME, byte-identically and never normalised, because that is the only key
   * the locations payload and the regions payload share ({@code heatSpots.js} records why, and
   * {@code scopeSpots} matches the same way one module over).
   *
   * <p><b>Null when no record is found</b>, which is not the same as "cannot be an origin".
   * {@code originAction}'s three reasons are all statements about a region record — switched off,
   * no base town, already the origin — and offering one for a region the shell has never seen would
   * be a guess printed as a fact. The footer then simply carries no origin action, which is the
   * degrade-is-silence rule the whole arm runs on.
   *
   * <p>Carries the RECORD, not an origin descriptor: {@code setOrigin} takes a region record and
   * folds it through {@code toOrigin} itself, so converting here would be a second conversion able
   * to disagree with the one the provider does.
   */
  const sheetPlanFrom = useMemo(() => {
    const name = sheetSpot?.regionName || null;
    if (!name) return null;
    const record = (regions || []).find((r) => r?.name === name) || null;
    if (!record) return null;
    const { can, off, based, current } = originAction(record, origin?.id ?? null);
    // ⚠️ THE SHEET'S OWN WORDS, naming the region — never the search dropdown's. That box's subject
    // is a region, so "you are already planning from here" is unambiguous there; this dialog's
    // subject is a PLACE, and the same sentence under a heading reading "Bamburgh" claims the
    // origin is Bamburgh. It is also the commonest of the three here, since every local place a
    // reader opens after moving the origin hits it. Precedence, not subsumption: a region can be
    // off, baseless and current at once, and this order is the one `originAction` documents.
    let reason = null;
    if (off) reason = `${name} is switched off`;
    else if (!based) reason = `${name} has no base town to plan from`;
    else if (current) reason = `Already planning from ${name}`;
    return { name, reason, region: can ? record : null };
  }, [sheetSpot, regions, origin]);
  /**
   * Find — the masthead's labelled button, ⌘K / Ctrl K, and what a pick does on the screen and on the map view
   * (find-and-favourites-plan.md F1).
   *
   * <h2>Where it is offered</h2>
   * <p>The screen and the map view: each gives a pick its own meaning (the table in the plan), and a pick that
   * needs the screen closes the layers first, the way {@code askOpenInPlan} does (decision D6). Operations has none,
   * and Find is not offered there (the masthead is hidden under it).
   *
   * <h2>The guards</h2>
   * <p>The button refuses while a sheet layer (the Map's spot list, the next-days sheet or the area sheet) or the
   * guide's modal is up (the two-deep rule: Find is never a third layer). The key is NOT a copy of {@code /}: it takes the
   * button's guard, any dialog this shell does not own ({@code foreignDialogOpen}, which covers settings and
   * Ask's sheet), and a field the reader is typing in, and it does NOT read {@code askKeyRefused}, which
   * would take ⌘K from a LITE reader, from a dead Ask backend, from a window narrower than 1024px and from
   * a reader with a sheet open. A second press while Find is open closes it, ahead of every
   * refusal (the focus is in Find's own field by then). {@code preventDefault} only when the key acts, so
   * a refused ⌘K still reaches the browser. The listener is mounted at every width and for every role.
   */
  const findOffered = !operationsShown;
  // The Find button the reader came through: the view's own while it is open (the masthead's is inert behind it).
  const findNode = () => shellRef.current?.querySelector(
    mapOpen ? '[data-testid="map-screen-find"]' : '[data-testid="window-first-search"]',
  ) ?? null;
  const openFind = () => {
    if (!findOffered || stackedOverLayer || guideModal) return;
    setSearchSeed('');
  };
  useEffect(() => {
    const onKeyDown = (event) => {
      if (!isFindKey(event)) return;
      // Held keys repeat: a long press must not open Find and close it again, or reopen it on a held chord.
      if (event.repeat) return;
      if (searchSeed != null) {
        // The guide's modal stacked over Find owns the key: closing the dialog under it would strand the guide.
        if (guideModal) return;
        event.preventDefault();
        setSearchSeed(null);
        return;
      }
      if (!findOffered || stackedOverLayer || guideModal) return;
      if (foreignDialogOpen(shellRef.current)) return;
      if (isTypingTarget(event.target)) return;
      event.preventDefault();
      setSearchSeed('');
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [searchSeed, findOffered, stackedOverLayer, guideModal]);
  // The Map pane closes its own menus while Find is up: the Filters and Regions popovers and the phone's peek
  // sections are not dialogs, so Find's modal does not take them down, and one left open would sit under the
  // callout a pick is about to open.
  useEffect(() => { onFindOpenChange?.(searchSeed != null); }, [searchSeed, onFindOpenChange]);
  // Focus, when Find closes with nobody holding it: a ⌘K open has no focused trigger (and Safari and Firefox on
  // macOS do not focus a button on a click), so `Modal`'s own restore has nowhere to go. It goes back to the Find
  // button, unless a pick left a dialog standing, which holds focus itself.
  const findWasOpen = useRef(false);
  useEffect(() => {
    const was = findWasOpen.current;
    findWasOpen.current = searchSeed != null;
    if (!was || searchSeed != null) return;
    if (sheetSpot != null || sheetKey != null || areaSheet != null) return;
    const focused = document.activeElement;
    if (focused && focused !== document.body && focused !== document.documentElement) return;
    findNode()?.focus({ preventScroll: true });
    // Closed-over `findNode` reads the ref only; the open flag is the trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchSeed]);
  /** The windows Find offers on the Map: only those the map's own window control can reach. */
  const mapFindWindows = useMemo(
    () => (Array.isArray(forecastDates)
      ? heatStripCards.filter((card) => forecastDates.includes(card.date))
      : heatStripCards),
    [heatStripCards, forecastDates],
  );
  /**
   * A place picked in Find, by context. The screen: the next-days sheet, as ever. The map view: fly there and open
   * its callout, through the Map pane's own channel.
   */
  const pickFindLocation = (spot) => {
    if (mapOpen) {
      onFindOnMap?.({ kind: 'location', id: spot.id ?? null, name: spot.name });
      return;
    }
    sheetFromFindRef.current = true;
    openSheetLayer({ spot });
  };
  /**
   * A window picked in Find (G2a): the Map with that window selected, from any tab. On the Map it is selected in place;
   * from the screen it goes through the Map door and records the arrival, so Back returns to the tab it left.
   */
  const pickFindWindow = (key) => {
    openWindowOnMap(key);
  };
  /**
   * A region picked in Find. The screen plans from it (the origin moves, as ever); the Map only pans and
   * frames it, and never calls {@code setOrigin}, because an origin move reaches the rings and every drive time.
   */
  const pickFindRegion = (region) => {
    if (mapOpen) {
      onFindOnMap?.({ kind: 'region', name: region.name });
      return;
    }
    openSheetLayer(null);
    setOrigin?.(region);
  };
  /**
   * An area picked in Find. The screen: its sheet. The map view: fly
   * to it and apply the named-spot lens, through the Map pane's own channel; the pick carries member ids, and the map
   * resolves them to names against its own roster.
   */
  const pickFindArea = (area) => {
    if (mapOpen) {
      onFindOnMap?.({ kind: 'area', id: area.id, name: area.name, ids: area.locationIds });
      return;
    }
    areaFromFindRef.current = true;
    openSheetLayer(null);
    setAreaSheet(area);
  };
  /**
   * A Your places chip with a window to open (D15): the map view on that window, focused on the place, or on the
   * area's spots as the named-spot lens (the same lens the area sheet's foot uses).
   *
   * @param {{window: {date: string, eventType: string}, focus: object, from: ?Element}} request
   */
  const openYourPlace = ({ window: win, focus, from }) => {
    if (focus?.kind === 'area') {
      const members = new Set(focus.area.locationIds);
      const names = heatSpots.filter((spot) => members.has(spot.id)).map((spot) => spot.name);
      openMap({ window: win, focus: { kind: 'names', focusNames: names }, from });
      return;
    }
    openMap({ window: win, focus, from, frame: HOME_AND_PLACE });
  };
  /**
   * The week's best, named in This week's bar (screen-spec.md D3): the card whose served pick is the Best bet, within
   * the days this screen shows. Withheld with no map door (the press goes to the map) and, by the card's own pick,
   * for a Lite reader or an origin that has scoped the pick away. Never ranked here (`utils/weekBest.js`).
   */
  const weekBest = useMemo(
    () => (showOnMap ? weekBestOf(heatStripCards, forecastDates) : null),
    [showOnMap, heatStripCards, forecastDates],
  );
  /** The bar's best button: the map view on that window, framing home and the pick's place, focus returning to it. */
  const openWeekBest = (event) => {
    if (!weekBest) return;
    openMap({
      window: { date: weekBest.date, eventType: weekBest.eventType },
      focus: weekBest.locationId != null
        ? { kind: 'location', id: weekBest.locationId, name: weekBest.place }
        : null,
      from: event.currentTarget,
      frame: HOME_AND_PLACE,
    });
  };
  /** The area sheet's foot: the Map on the area's best window, with the area's spots as the named-spot lens. */
  const showAreaOnMap = (area) => {
    const target = areaMapWindow(heatStripCards, areaFind.outlook?.get(area.id));
    if (!target) return;
    const members = new Set(area.locationIds);
    const names = heatSpots.filter((spot) => members.has(spot.id)).map((spot) => spot.name);
    setAreaSheet(null);
    openMap({
      window: { date: target.date, eventType: target.targetType },
      focus: { kind: 'names', focusNames: names },
    });
  };
  /**
   * {@code /} moves to Ask — at 1024px and up, on Plan, Coming up and the Map (plan §6 Q1, decided).
   * It opens the dock when it is closed and puts the cursor in its question field when it is open.
   * It used to open Plan search; search is now reached through the masthead's ⌕ and the origin button
   * ONLY, and below 1024px {@code /} does nothing (the sheet is the surface there, and it has no key).
   *
   * <p>Guarded the way the search shortcut was, because the reasons did not change: a bare global
   * {@code /} listener is a well-known way to make a page hostile. It is ignored while the reader is in
   * a field (input, textarea, select, or anything {@code contenteditable} — the settings modal is a
   * sibling in {@code App}); when a modifier is held, so browser and OS shortcuts are untouched (Shift
   * is NOT refused: on a German layout {@code /} is Shift+7); and while anything is over the shell —
   * any of its own dialogs, the sheets included, search, settings, a dialog this shell does not
   * own, or a dead backend. {@code askKeyRefused} is every one of the shell's own PLUS the lock (a LITE reader's
   * field is a live door to the upsell, and the key must not open a sales popover or swallow the browser's
   * quick-find), and it contains the flag that disables the field ({@code askBlocked}), so the key can never
   * be live where the control beside it is not.
   *
   * <p>⚠️ <b>A sheet refuses it.</b> Search is anchored to the masthead, a surface a sheet is drawn over, and the dock
   * is a column beside the page that a sheet's scrim covers. Opening it under an {@code aria-modal}
   * dialog would put a live control where the sheet says nothing is, so the sheet stays.
   */
  useEffect(() => {
    if (!askDockEntry) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return;
      if (askKeyRefused) return;
      // ⚠️ A dialog this shell does not own still refuses, which is a DIFFERENT question from the flag
      // above: `UserSettingsModal` and the postcode prompt are siblings of the shell in `App`, invisible to
      // its state. Containment answers it — see `foreignDialogOpen`.
      if (foreignDialogOpen(shellRef.current)) return;
      const el = event.target;
      const tag = el?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return;
      event.preventDefault();
      // Past every refusal, `/` means exactly what the field's press means.
      openAskDock();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [askDockEntry, askKeyRefused, openAskDock]);

  /**
   * The plan's way out of a lens that has shut it — the bar's controls, reached from the message.
   *
   * <p>The action is a descriptor {@code planConflicts.js} builds and this renders, rather than a
   * pair of callbacks per axis, so a third axis would not widen anything here. It moves the
   * <b>page-wide</b> lens, which is exactly what the reader asked for.
   *
   * <p>Nothing here is gated. A reach action only exists when a wider tier would put something on
   * screen, and a LITE reader is pinned to "Any" — so {@code buildPlanConflict} never offers them
   * one, with no role anywhere in the path.
   *
   * <p><b>A focus move, because the pressed button unmounts.</b> The verdict's button sits in the
   * line it is replacing, so a keyboard reader would be dropped at {@code <body>} having just asked
   * to be shown something. Focus goes to the first card of the sunrise row, which is what the
   * reader has just been shown (matrix-axis plan D20 — the row-major DOM the rails restructure
   * introduced means the first {@code [data-testid="wf-heat-card-button"]} in document order is the
   * first day's sunrise card rather than the first day's own first window, but it is still the
   * visually top-left card). When the next state is still empty (a rating fix that reveals a reach
   * verdict) there is no card, so focus falls back to the new verdict's button, then the readout.
   */
  const applyConflictAction = (action) => {
    if (action?.kind === 'reach') reachLens?.selectTier(action.id);
    else if (action?.kind === 'rating') ratingLens?.selectFloor(action.id);
    else return;
    // ⚠️ The card's inner BUTTON (`wf-heat-card-button`), not the card: a control card is a `<div>` now
    // (a11y-heat-card-plan.md), so the card itself takes no focus. An AWAY window keeps its matrix cell
    // as a `<div>` with no button inside (plan §3 rule 14 — a control with no visible effect is
    // banned), and `querySelector` returns DOM order — so on a plan whose first rendered day is a
    // travel day, focusing the card selector would be a no-op and the reader dropped at `<body>`: the
    // exact defect this move exists to prevent. Deferred a frame because the matrix is re-rendering on this very commit, and
    // optional-CALLED because jsdom implements no layout.
    requestAnimationFrame(() => {
      const target = document.querySelector('[data-testid="wf-heat-card-button"]')
        ?? document.querySelector('[data-testid="window-first-lens-verdict-act"]')
        ?? document.querySelector('[data-testid="window-first-lens-readout"]');
      target?.focus?.();
    });
  };

  /**
   * The one message the whole plan may carry, and null when it carries none.
   *
   * <p>The lens bar's verdict line, because it is about the plan rather than one window (plan-matrix §6 M2.6;
   * lens-verdict-line-plan.md moved it off a card above the matrix).
   * Its per-window counterpart is the deleted window popup's quiet sentence, and the two land together: neither
   * alone covers what the deleted per-card ladder covered.
   */
  const conflict = useMemo(() => buildPlanConflict({
    cards: windowCards,
    origin: origin ?? null,
    tierId: reachLens?.tierId,
    limitMinutes: reachLens?.tier?.limitMinutes ?? null,
    floorId: ratingLens?.floorId,
    minRating: ratingLens?.minRating ?? null,
    reachMeasured,
  }), [windowCards, origin, reachLens?.tierId, reachLens?.tier,
    ratingLens?.floorId, ratingLens?.minRating, reachMeasured]);

  /**
   * The safety warning a topic on this plan carries, and the window it is about.
   *
   * <p>⚠️ <b>Page-level because the surface that used to guarantee it is gone.</b>
   * {@code BriefingWindow.Badge.safetyNote} carries the "do not look at the sun without a filter"
   * class of warning, and the window card's own comment named that card as "the ONE surface
   * guaranteed to be on screen whenever a topic is" — the Hot Topics door is shut on a fresh
   * session, and the promoted strip that once carried a second copy is gone (M5, D-1). Deleting the
   * card list would have put the warning behind a click, which is not somewhere a hazard notice may
   * live. So it is stated once, above the matrix, naming its window; the deleted window popup's topic row states it
   * again for a reader who has opened that window, exactly as the door already does.
   *
   * <p>One line rather than one per badge: a warning is about the hazard, not about the chip, and
   * the card's own rule was already "whichever badge carries one".
   */
  const safety = useMemo(() => {
    // ⚠️ Through the SAME A8 filter the cards and the deleted window popup use, not over the raw badge list. A
    // region-scoped hazard the scope drops shows on no card and in no popup, so a banner naming its
    // window would point at a window that says nothing about it when opened.
    const hit = windowCards
      .flatMap((card) => windowTopics(card.key, card.allBadges, topicIndex, planScopeNames)
        .map((row) => ({ card, badge: row.badge })))
      .find((pair) => pair.badge?.safetyNote);
    return hit == null ? null : {
      note: hit.badge.safetyNote,
      window: [hit.card.kicker, hit.card.when].filter(Boolean).join(' '),
    };
  }, [windowCards, topicIndex, planScopeNames]);

  /**
   * The Ask entry fields and the Lite upsell popover: one set, drawn in the masthead while the screen is showing and in
   * the map view's header while the view is open (the masthead is inert behind it). Never both.
   */
  const askFields = (
    <>
      {askEntry === 'field' && (
        <AskField
          width={260}
          prompt={ASK_PROMPT}
          disabled={askBlocked}
          locked={askLocked}
          expanded={askSheetShown}
          upsellOpen={askUpsellOpen}
          upsellId={askUpsellId}
          onKeyDown={askUpsellOpen ? onAskTriggerKeyDown : undefined}
          onOpen={askLocked ? toggleAskUpsell : openAskSheet}
          buttonRef={askTriggerRef}
        />
      )}
      {/* From 1024px the same field opens the DOCK, and says so: no popup-dialog claim, a pointer at the
          dock while it is open, and the `/` key cap only where there is room for it (the 340px form,
          from 1180px). `controls` is what takes the dialog claim off (see `AskField`). */}
      {askDockEntry && (
        <AskField
          width={askFieldWidth}
          showKeyHint={askSurface === 'wide'}
          keyShortcut
          prompt={ASK_PROMPT}
          disabled={askBlocked}
          locked={askLocked}
          expanded={askDockShown}
          controls={ASK_DOCK_ID}
          upsellOpen={askUpsellOpen}
          upsellId={askUpsellId}
          onKeyDown={askUpsellOpen ? onAskTriggerKeyDown : undefined}
          onOpen={askLocked ? toggleAskUpsell : openAskDock}
          buttonRef={askTriggerRef}
        />
      )}
      {askUpsellOpen && askEntryKind !== 'bar' && (
        <AskUpsellPopover
          id={askUpsellId}
          rootRef={askUpsellRootRef}
          triggerRef={askTriggerRef}
          placement="field"
          onClose={() => closeAskUpsell({ restoreFocus: true })}
          onFocusAway={() => closeAskUpsell({ restoreFocus: false })}
          foreignOpen={isForeignDialogOpen}
        />
      )}
    </>
  );

  /**
   * The ☰ menu (one-screen-plan.md D9, D14), one mount at every width: Settings, Change home postcode, Guide,
   * Operations (an admin's only: `App` hands over {@code onOpenOperations} for no one else, so Pro and Lite get no row
   * at all) and Sign out. Every row takes the dialogs down first ({@link closeShellLayers}, the settings dialog is a
   * sibling of this shell) and the menu hands focus to its button before the handler runs, so what the row opens
   * records the button as its opener.
   */
  const mastMenu = (
    <MastheadMenu
      open={mastMenuOpen}
      onOpenChange={setMastMenuOpen}
      buttonRef={menuBtnRef}
      // The phone has no inline pill: the admin's status reading lives in the menu there. Wider screens draw the pill.
      statusLine={isMobile ? healthStatusLine : null}
      onSettings={() => { closeShellLayers(); onOpenSettings?.(); }}
      onChangePostcode={() => {
        closeShellLayers();
        (onSetPostcode ?? onOpenSettings)?.(() => menuBtnRef.current);
      }}
      onSignOut={onSignOut}
      onWarmSettings={warmSettingsModal}
      // The phone's masthead has no origin chip, so while away the menu carries the way back home (P4).
      onGoHome={isMobile && origin ? () => { closeShellLayers(); setOrigin?.(null); } : undefined}
      awayFrom={isMobile && origin ? (origin.baseName ?? null) : null}
      onGuide={guideScreenPage ? toggleGuide : undefined}
      guideOpen={guide.open}
      guideControls={guideDockShown ? 'wf-guide-dock' : undefined}
      onOperations={onOpenOperations && operationsPane != null
        ? () => { closeShellLayers(); onOpenOperations(); }
        : undefined}
    />
  );

  return (
    <div
      ref={shellRef}
      data-testid="window-first-shell"
      // `wf-shell` hosts `--wf-gutter`/`--wf-lens-reserve`/`--wf-lens-h` — the arm's shared horizontal inset and the
      // sticky-chrome measurements `useLensReserve` publishes. It carries NO width constraint of its own: the masthead
      // stays wrapped at `WRAP_MAX_WIDTH` below. Plain block flow: the map does not recast the page as a
      // viewport-height flex column (one-screen-plan.md D11 deleted that layout switch);
      // it is a fixed view over this screen (`MapScreen`, below), so the screen never re-lays-out under it.
      //
      // ⚠️ With the Ask dock open (F2) the root is a ROW — `[column][dock]`, centred as a pair — and
      // `wf-shell--docked` says so in the stylesheet. The map view is `position: fixed` and so takes no part in it.
      className={`wf-shell w-full${dockedRow ? ' wf-shell--docked' : ''}${askEntry === 'bar' ? ' wf-ask-bar-on' : ''}`}
    >
      {/* THE SHELL COLUMN: the masthead and the screen's column, and nothing else. It exists so the Ask dock (this
          wrapper's next sibling in the shell root) can sit BESIDE both at once, so the masthead and the screen narrow
          together and the two columns never drift apart, which a dock inside either wrapper could not promise. Undocked it
          is a plain block, so nothing about the layout changes when Ask is off or closed. */}
      <div
        className="wf-shell-col"
        // While the map view is open the screen and the masthead behind it are INERT (one-screen-plan.md D1): the same
        // isolation an `aria-modal` would give, without the three things that need the view NOT to be a modal
        // (`MapScreen`'s doc). Never the dock column, the AskBar or the `<body>` portals.
        inert={mapOpen}
        // Operations covers the whole screen (D9): the masthead and the screen are `hidden`, not just inert, so nothing
        // behind it is reachable by Tab or a screen reader. They stay mounted, so the screen keeps its scroll.
        hidden={operationsShown}
        // Docked, the column is a flex item that takes what the dock leaves, up to the one width the
        // masthead and the panel inside it share. Undocked it is a block and the two wrappers cap
        // themselves, exactly as before.
        style={dockedRow ? { maxWidth: WRAP_MAX_WIDTH } : undefined}
        data-testid="window-first-shell-col"
      >
      {/* The masthead, wrapped at `WRAP_MAX_WIDTH`. The screen's column below shares that same width, so the two never
          drift apart; below `sm` (640px) `<main>`'s own padding is the only difference (`App.test.jsx` covers it). */}
      <div
        className="mx-auto w-full"
        style={{ maxWidth: WRAP_MAX_WIDTH }}
      >
      {/* The lit band. Three lines in a column, not one row: the lockup and its controls, then
          today's light as a gradient, then the row that labels it. The band's own surface and its
          zero bottom padding live on `.wf-mast` in index.css — the time row supplies the bottom
          space, and a media query is a selector, so no inline style can reach it. */}
      <div
        data-testid="window-first-masthead"
        className="wf-mast border-b border-plex-border"
      >
        {isMobile ? (
          // The phone's one line (screen-spec.md section 4, P4): the compact wordmark, then ⌕ Find, the admin's status dot
          // and the ☰ menu (Settings, Change home postcode, Plan from home again while away, Guide, Sign out). There are no
          // light times on a phone (the map view's header and the desktop masthead carry them), and the origin chip lives
          // in the lens caption and the menu. Every width above 639px takes the arm below, unchanged.
          <div className="wf-mast-row" data-testid="window-first-mast-row">
            <BrandLockup variant="compact" />
            <div className="wf-mast-right">
              {findOffered && (
                <MastheadFind onOpen={openFind} searchOpen={searchSeed != null || stackedOverLayer} />
              )}
              {healthDot}
              {mastMenu}
            </div>
          </div>
        ) : (
        <div className="flex items-center gap-3" data-testid="window-first-mast-row-wide">
          <BrandLockup variant="masthead" />
          {/* Brand, then (right) the admin's health pill, the Ask field and the ☰ menu (D14). The origin chip and Find
              are the tick line's, below. Settings, the guide and Sign out are the menu's rows now. The Ask field is
              drawn here while the screen shows and in the map view's header while the view is open. */}
          <div className="wf-mast-tools flex flex-1 min-w-0 items-center justify-end gap-2">
            {healthPill}
            <div className="wf-mast-askwrap" data-testid="masthead-ask-room">{!mapOpen && askFields}</div>
            {mastMenu}
          </div>
        </div>
        )}
        <MastheadLight light={light} compact={isMobile} />
        {/* The tick line — the plan's origin, the way to change it, and today's times, in the one
            row the design allows for all three. It is inside the band deliberately: the rail
            footer it replaces sat outside, and a reader who has just moved the origin should not
            have to look in two places to see where they moved it to. */}
        <MastheadTickLine
          light={light}
          origin={origin ?? null}
          homePlace={homePlace}
          // ⚠️ HANDED OVER ON PLAN, COMING UP AND MAP (find-and-favourites-plan.md F1). It was the Plan tab
          // alone, because every pick acted on the Plan: on Coming up a window or a place opened a Plan
          // dialog over the almanac feed — the state `closeShellLayers` exists to prevent.
          // Each tab now gives a pick its own meaning (`pickFindLocation`, `pickFindWindow`,
          // `pickFindRegion`), so both doors are offered on all three. WITHHELD rather than refused on
          // Operations, which has no meaning for a pick: with no handler the tick line draws the origin as
          // a statement and no Find button, where a handler that did nothing would leave two controls with
          // no visible effect (plan-matrix §3 rule 14). The beyond line, the third trigger, sits inside
          // the Plan pane and is hidden with it.
          //
          // ⚠️ And the SAME stacking guard the old `/` search shortcut carried, which M5 added because
          // the button did not. Measured in a browser: from an open location sheet a keyboard reader
          // reached this control on the seventeenth Tab and opened search as a THIRD layer — and
          // `Modal` gives every dialog `fixed inset-0 z-50`, so with equal z-index paint order is DOM
          // order and the sheet, which renders after search, painted its scrim and its whole card
          // OVER the search panel. The reader typed into a box behind a dead, dimmed sheet. The
          // shortcut's own comment (since deleted with it) had settled the rule this restores — "those
          // are already stacked, and a third layer has nowhere to go" — so the button was
          // simply bypassing it.
          onOpenSearch={findOffered ? openFind : undefined}
          // Takes the sheet layer down first: moving the origin under an open sheet is the case the close-then-move
          // footer exists to rule out. Focus is the tick line's to settle, not this handler's.
          onGoHome={() => { openSheetLayer(null); setOrigin?.(null); }}
          // ⚠️ The cog's rule, which this route went without: `App` wired the nudge straight to its
          // own handler, so with a sheet open — where `searchOpen` is false and this row
          // keeps its tab stops — a reader who Tabbed out onto the nudge opened settings OVER the
          // sheet, two `aria-modal` elements with the sheet's Escape listener still armed beneath.
          // It closes through the same `closeShellLayers()` the menu's Settings row does. Search is a belt here, exactly as
          // it is for `onGoHome`: under it this row is out of the tab order.
          //
          // The arguments are FORWARDED, whatever they are: what the nudge hands its handler is the
          // tick line's business, and a wrapper here that swallowed them would change that contract
          // without either end noticing.
          onSetPostcode={(...args) => {
            closeShellLayers();
            (onSetPostcode ?? onOpenSettings)?.(...args);
          }}
          // Out of the tab order for TWO reasons now, which is why the prop is no longer named for
          // one of them. The anchored search panel covers this row exactly (WCAG 2.4.11) — and a
          // sheet layer makes the search button refuse, so leaving it tabbable
          // would be a control with no visible effect, which plan §3 rule 14 bans outright.
          searchOpen={searchSeed != null || stackedOverLayer}
          // Phone: the row is only the unset-postcode nudge (nothing otherwise); Find is the brand row's own button.
          // Desktop and tablet keep the whole row, with today's times labelled "today".
          phone={isMobile}
          // Once a postcode is saved the phone's row is gone, and the nudge with it: focus lands on ☰, never <body>.
          fallbackFocus={isMobile ? () => menuBtnRef.current : undefined}
        />
      </div>

      {/* ⚠️ THE RAIL FOOTER IS GONE, and three of its four elements moved rather than died
          (plan-matrix §6 M3.5, deletion ledger M3). `PlanOriginChip` and the "Home not set"
          line are now the tick line's origin button and its empty state; "Edit reach" is the ⚙
          it already opened, which is one route to the same modal rather than two side by side.

          ⚠️ THE AGE IS THE ONE THAT COST SOMETHING, and the price is stated here rather than left
          to be rediscovered. It moved beside the strip's change line — the plan's own placement —
          so the page states ONE age (Rule 7) where the footer and that line had both been printing
          the same `generatedAt`. Three things follow, and the third is a real loss:
            · it is Plan-only now, where the footer sat above the tab bar and showed on every tab;
            · it takes the pane's `contentDisabled` greying, where the footer sat outside it — still
              drawn and readable at `opacity-50`, not removed;
            · and it VANISHES when `WindowFirstHeatStrip` withdraws (`cards.length === 0 ||
              spots.length === 0` — a failed `/api/locations`, a session with no roster), where the
              footer printed it unconditionally.
          The defence for the third is that the strip withdraws precisely when there is no forecast
          on screen to be old, so the age would be qualifying nothing; the deleted row's own comment
          ("the forecast's AGE is the one fact that becomes more useful when the backend is down")
          argued the other way, and a reader who wants it back should move the age into the tick
          line AND strip `runAge` from the change line — never add a second copy. */}

      </div>

      {/* The screen's column: the SAME `WRAP_MAX_WIDTH` and centring as the masthead (O-17, owner decision 2026-09-03:
          full-bleed read as broken because the masthead stopped at the content column while a panel carried on to the
          window edge). One width, so the masthead and the screen can never drift apart. The map is not in this column:
          it is the fixed view `MapScreen`, drawn after it. No height is computed anywhere in this chain. */}
      <div
        className="mx-auto w-full"
        style={{ maxWidth: WRAP_MAX_WIDTH }}
      >

      {/* Plan only, and that is the design's own heading for it ("Lens bar (Plan only)"). The bar
          filters SPOTS; the almanac feed has none, so on Coming up it would gate nothing — §6's
          "no control gates on data that does not exist", and its own footer would read "0 spots
          across 5 windows" over a pane containing neither. It is unmounted rather than hidden so
          the sticky bar cannot take a scroll position with it. */}
      {!operationsShown && reachLens && ratingLens && (
        <div
          ref={lensSentinelRef}
          data-testid="window-first-lens-sentinel"
          aria-hidden="true"
          // 1px and empty. It exists to scroll away where the bar above it does not — see
          // `useStuckSentinel`. Rendered inside the same conditional as the bar so the observer's
          // target and its subject come and go together; a sentinel that outlived the bar would
          // report a stick for an element that is not on the page.
          className="wf-lens-sentinel"
        />
      )}
      {!operationsShown && reachLens && ratingLens && (
        <WindowFirstLensBar
          stuck={lensStuck}
          weekNode={weekNode}
          lens={reachLens}
          ratingLens={ratingLens}
          spotCount={windowCards.reduce((total, card) => total + placesOf(card.spots).length, 0)}
          // The rating floor's own denominator — what reach left for it to choose from. Summed here
          // rather than derived in the bar for the reason `spotCount` already is: the counts have to
          // be the lengths of the arrays that were drawn, and only this component can see all six.
          reachedCount={windowCards.reduce((total, card) => total + (card.reachedTotal ?? 0), 0)}
          // ⚠️ Whether the reach axis COULD act, see `formatLensCount`. A reader with no home postcode has no drive
          // time anywhere, so `reachedCount` is simply everything and "within reach" would name a gate that did nothing.
          reachMeasured={windowCards.some((card) => card.reachMeasured)}
          // A reader with no pick has no spots to count: "0 spots across 4 windows" would be false of the picks (S5b-1).
          windowCount={picksFacts?.allowed === 0 ? 0 : windowCards.length}
          originBase={origin?.baseName ?? null}
          // The plan-level conflict, as the readout's verdict line (it was a card above the matrix).
          verdict={conflict?.line ?? null}
          onVerdictAction={applyConflictAction}
        />
      )}

      {/* The screen's column: This week, then Further ahead. There is no tab panel and no hidden state any more: the
          screen is always the page, and the map view and Operations cover it (inert, or `hidden` on the column). */}
      <div
        data-testid="window-first-pane"
        className={`wf-body flex flex-col${dimmed}`}
      >
      {/* THIS WEEK (one-screen-plan.md O2): the forecast, under a sticky heading that carries the "Further ahead ↓" jump
          and its NEW badge. Its own `<section>` so the heading sticks for exactly as long as the section is on screen and
          is then replaced by Further ahead's, which is a sibling section below. The jump's accessible name carries the
          badge's meaning (the badge span is `aria-hidden`), so the heading's own name stays "This week" (the week's best is a sibling button, never inside it). */}
      <section
        id={THIS_WEEK_ID}
        ref={setWeekNode}
        aria-labelledby={THIS_WEEK_HEADING_ID}
        className="wf-sec wf-this-week"
        data-testid="this-week"
      >
        <div className="wf-sec-bar wf-sec-bar-week" data-testid="this-week-bar">
          <h2
            id={THIS_WEEK_HEADING_ID}
            tabIndex={-1}
            className="wf-sec-h"
            data-testid="this-week-heading"
          >
            {THIS_WEEK_HEADING}
          </h2>
          {/* The help `?` for the fixed/could-still-move words, moved here from the strip's retired heading. */}
          <GuideLink section="fixed" label="What fixed and could still move mean" />
          {/* The week's best (screen-spec.md D3): the served Best bet, a sibling button (never inside the heading, so the
              heading's name stays "This week"). Absent for a Lite reader, an away-scoped origin and a forecast with no
              Best bet. The glyphs are decoration; the name says it in words. */}
          {weekBest && (
            <button
              type="button"
              className="wf-sec-best"
              data-testid="this-week-best"
              onClick={openWeekBest}
            >
              {/* No aria-label: the accessible name STARTS with the visible words (WCAG 2.5.3), the stars spoken as
                  words by StarFigure, then a screen-reader-only tail naming what the button is and does. */}
              <span className="wf-sec-best-t">
                {`best ${weekBest.dow} ${weekBest.event}, ${weekBest.place}`}
                {weekBest.rating != null && (
                  <>
                    {' '}
                    <StarFigure n={weekBest.rating} />
                  </>
                )}
              </span>
              <span aria-hidden="true">{' →'}</span>
              <span className="sr-only">, the week&apos;s best, opens the map</span>
            </button>
          )}
          <button
            type="button"
            className="wf-sec-jump"
            data-testid="further-ahead-jump"
            aria-label={furtherAheadJumpLabel}
            onClick={openFurtherAhead}
          >
            Further ahead
            <span aria-hidden="true">{' ↓'}</span>
            {watchingCount > 0 && (
              <span aria-hidden="true" data-testid="further-ahead-jump-watching" className="wf-jump-watching">
                <BookmarkMark className="wf-yp-mark" />
                {watchingCount}
              </span>
            )}
            {comingUpBadge && (
              <span
                aria-hidden="true"
                data-testid="further-ahead-jump-badge"
                className={comingUpBadge.band === 'interrupt' ? 'wf-jump-badge wf-jump-badge-rare' : 'wf-jump-badge'}
              >
                {comingUpBadge.band === 'interrupt' ? '◆' : comingUpBadge.count}
              </span>
            )}
          </button>
        </div>

        {/* Your places (F4, D15): the reader's own bookmarked places and areas as one row of chips at the top of the
            forecast, or the one-time hint that teaches bookmarking (a Lite reader's carries the Pro pill). Renders nothing for
            anyone else. */}
        <YourPlacesRow
          hintDue={placesHintDue}
          onDismissHint={onDismissPlacesHint}
          onSettleHint={onSettlePlacesHint}
          spots={heatSpots}
          windows={heatStripCards}
          scoreIndex={detailScoreIndex}
          reachById={effectiveReachById}
          allowedLocationIds={allowedLocationIds}
          onOpenWindow={openYourPlace}
          onOpenPlace={(spot) => {
            warmStackedChunks();
            openSheetLayer({ spot });
          }}
          onOpenArea={(area) => {
            warmStackedChunks();
            setAreaSheet(area);
          }}
        />

        {/* The hazard notice, page-level for the reason the memo records: the card list that used
            to guarantee it is on screen whenever a topic is has been deleted. */}
        {safety && (
          <p data-testid="window-first-safety" className="wf-plan-safety font-mono">
            <span aria-hidden="true">⚠ </span>
            {safety.window ? `${safety.window} — ${safety.note}` : safety.note}
          </p>
        )}

        {/* THE PLAN. Six pictures in a day × event grid, each one the control that opens its own
            window's popup — the strip stopped being an index into a list at M1 and the list itself
            goes at M2. Inside the greyed region, because it is forecast content. */}
        <Suspense fallback={null}>
          <WindowFirstHeatStrip
            colourMode={mapColourScale}
            cards={heatStripCards}
            pointSets={heatPointSets}
            unratedSets={heatUnratedSets}
            stoodSets={heatStoodSets}
            // Outside the reader's sub-regions (S5b-1): a body shaped by their picks hands the strip each window's
            // two grounds and the facts for its key and note. Both are null/empty for every other reader.
            groundSets={heatGroundSets}
            picks={picksFacts}
            spots={heatSpots}
            reachById={reachById}
            fieldReachById={effectiveReachById}
            /* The served topics, for the matrix's scope filter (plan-matrix A8). Read straight off
               the briefing rather than through a derivation, because the join key is the topic's
               OWN `eventType` + `date` and any client re-shaping is a chance to lose the NIGHT
               bucketing. */
            hotTopics={briefing?.hotTopics}
            // The day header's topic pills (glance-topic-strip-plan.md): the same Map door, forecast dates and
            // window keys the Coming up block they replaced was handed, so a pill's routes are unchanged.
            comingUpEntries={comingUp.events?.entries}
            renderedEntryIds={comingUpEntryIds}
            openableKeys={planWindowKeys}
            forecastDates={showOnMap ? forecastDates : NO_FORECAST_DATES}
            onLaneEntryIds={setGraduatedIds}
            quietOpen={openQuietDays}
            onQuietToggle={toggleQuietDay}
            onShowOnMap={showOnMap ?? NO_MAP_DOOR}
            onOpenLaneOnMap={openWindowOnMap}
            onOpenWatched={openYourPlace}
            highlightKeys={askHighlight}
            todayStr={todayStr}
            runAge={age}
            onOpenWindow={openWindowOnMap}
            origin={origin ?? null}
            homeCoords={homeCoords}
            picksLocked={picksLocked}
            onSearchRegion={(regionName) => { if (stackedOverLayer || guideModal) return; setSearchSeed(regionName); }}
          />
        </Suspense>

        {/* ⚠️ The WRAPPER is always mounted and carries `role="status"`; the line inside is what
            comes and goes — the same shape `WindowFirstComingUp`'s own status wrapper uses, and
            for the identical reason (`docs/engineering/window-first-redesign-plan.md` §5f):
            a live region inserted in the same commit as the CONTENT IT THEN CHANGES TO is
            unreliably announced — the wrapper has to already be on screen before the pending→slow
            and pending→settled TRANSITIONS it exists to announce. Before this wrapper existed, the
            pane rendered NOTHING at all while `loading` was true and nothing had arrived yet — no
            matrix, no count line, no sentence here — which is what made the 2026-09-30
            slow-briefing incident (a `GET /api/briefing` request that never completed in
            production, fixed in #957) read as a blank pane rather than a pane still working.

            `wf-pane-status` cancels the `.wf-body` flex gap (index.css, beside the `.wf-body` rule)
            when this wrapper is empty — i.e. whenever there ARE cards, which is what every reader
            sees almost all the time — so the matrix is not followed by a stray 10px gap from an
            always-mounted but usually empty node. */}
        <div role="status" data-testid="window-first-pane-status" className="wf-pane-status">
          {loading && paneItems.length === 0 && (
            <p
              data-testid="window-first-pane-pending"
              className="font-mono text-plex-text-secondary"
              style={{ fontSize: '10.5px' }}
            >
              {pendingSlow
                ? 'Still loading the forecast — taking longer than usual.'
                : 'Loading the forecast…'}
            </p>
          )}

          {!loading && paneItems.length === 0 && (
            <p
              data-testid="window-first-pane-empty"
              className="font-mono text-plex-text-secondary"
              style={{ fontSize: '10.5px' }}
            >
              No forecast to show.
            </p>
          )}
        </div>
      </section>

      {/* FURTHER AHEAD (D5): Coming up's almanac, as the bottom section of the screen. It lists what This week does not
          already draw as a lane (`graduatedIds`, reported by the heat strip), and the one-off NEW-badge bootstrap fires
          when its heading first reaches the viewport (D6). */}
      <FurtherAhead
        id={FURTHER_AHEAD_ID}
        headingId={FURTHER_AHEAD_HEADING_ID}
        graduatedIds={graduatedIds}
        onHeadingSeen={handleFurtherAheadSeen}
        status={comingUp.status}
        events={comingUp.events}
        todayStr={todayStr}
        onRetry={comingUp.retry}
        onGoToPlan={goToPlan}
        onShowOnMap={showOnMap ?? NO_MAP_DOOR}
        focusRequest={comingUpFocus}
        onFocusHandled={handleComingUpFocusHandled}
        comingUpLastSeenDate={comingUpLastSeenDate}
        forecastDates={showOnMap ? forecastDates : NO_FORECAST_DATES}
        onMarkSeen={markSeen}
        locations={locations}
      />
      </div>

      </div>
      </div>

      {/* OPERATIONS (one-screen-plan.md D9): an admin-only full-screen view, open exactly while the page's address says
          `#manage...` (`App` reads it and hands {@code operationsOpen}). The column above is `hidden` while it stands, so
          nothing behind it is reachable. Mounted only while open: the pane reads the hash when it mounts, and releasing
          it stops its list polls. Never a Back layer: its own hashes already give Back, and registering a `#manage`
          panel with the dialog history is banned (`plan-tab.md`, 'Back'). */}
      {operationsShown && (
        <OperationsScreen onClose={onCloseOperations}>{operationsPane}</OperationsScreen>
      )}

      {/* THE MAP VIEW (one-screen-plan.md D1, D10, D12). A labelled region, `position: fixed` over the inert screen,
          rendered INSIDE the shell root and never portalled (so `foreignDialogOpen` keeps seeing the dialogs outside
          the shell, and Ask, `/` and ⌘K keep working). The pane mounts on the FIRST open, visible from its first commit,
          and stays mounted behind the `hidden` attribute when closed. It sits above the Ask bar and below `Modal`; with
          the Ask dock or the docked guide open it ends at the dock column's edge, and the dock stays beside it. */}
      {mapPane != null && mapMounted && (
        <MapScreen
          open={mapOpen}
          onClose={() => closeMap()}
          // The scrim closes the view only when nothing is stacked over it: a press that lands there while a sheet or
          // Find stands belongs to that layer's own backdrop.
          onScrimPress={() => {
            if (stackedOverLayer || searchSeed != null || guideModal || askSheetShown) return;
            closeMap();
          }}
          onRoute={handleMapRoute}
          titleRef={mapTitleRef}
          right={dockEdge}
          note={mapNote}
          onDismissNote={() => setMapNote(null)}
          find={(
            <button
              type="button"
              data-testid="map-screen-find"
              className="wf-map-screen-btn font-mono"
              onClick={openFind}
              aria-keyshortcuts="Meta+K Control+K"
            >
              <span aria-hidden="true">⌕ </span>
              Find
            </button>
          )}
          origin={(origin?.baseName || homePlace) ? (
            <>
              <span data-testid="map-screen-origin" className="wf-map-screen-btn wf-map-screen-origin font-mono">
                {origin?.baseName ? `From ${origin.baseName}` : `Home · ${homePlace}`}
                {/* True on the map, where every drive time and leave-by is measured from this place. */}
                <span className="hidden md:inline">{' · drive times from here'}</span>
              </span>
              {/* The masthead's ⌂ is inert behind the view, so an away origin is undone from here. Its press removes
                  it, so focus goes to the title first (never <body>). */}
              {origin?.baseName && (
                <button
                  type="button"
                  data-testid="map-screen-origin-home"
                  className="wf-map-screen-btn font-mono"
                  aria-label="Plan from home again"
                  onClick={() => {
                    mapTitleRef.current?.focus({ preventScroll: true });
                    setOrigin?.(null);
                  }}
                >
                  <span aria-hidden="true">⌂</span>
                </button>
              )}
            </>
          ) : null}
          ask={mapOpen ? askFields : null}
        >
          <MapListContext.Provider value={mapListDoor}>{mapPane}</MapListContext.Provider>
        </MapScreen>
      )}

      {/* Ask PhotoCast's DOCK — the second child of the root row, a sibling of the whole shell column
          (F2, plan §2.6/D-4). Mounted only while open and only from 1024px, on Plan, Coming up and the
          Map. NOT a dialog: the page beside it stays live, and it is `inert` (never the page) while
          a shell dialog, search or settings is open. Under a dead backend it is NOT inert — the field
          is disabled and `/` refused, but a dock already open must stay closable (an inert ✕ would
          pin it there, dimmed, for as long as the backend is down). On the Map the dock is the
          frame's own height and the map pane's `ResizeObserver` tells Leaflet about its new width;
          on Plan and Coming up it sticks to the viewport with the input row at its foot. */}
      {askDockShown && (
        <ChunkErrorBoundary variant="inline" label="Ask PhotoCast" id={ASK_DOCK_ID} onClose={closeAskDock}>
        <Suspense fallback={<AskDockFallback band={askSurface} sticky />}>
        <AskDock
          band={askSurface}
          sticky
          inert={askDialogOpen}
          view={askViewSpec.view}
          viewLabel={askViewSpec.label}
          contextLabel={mapOpen ? ASK_DOCK_CONTEXT.map : ASK_DOCK_CONTEXT.plan}
          inputRef={askDockInputRef}
          onClose={closeAskDock}
          fallbackFocus={screenFocusNode}
          pickActions={askPickActions}
          planActions={askPlanActions}
          focusOnMount={!dockQuiet}
        />
        </Suspense>
        </ChunkErrorBoundary>
      )}

      {/* The guide's docked column (G2, presentation 1): the Ask dock's slot, never both. Not a dialog,
          so it claims no modality; it is `inert` (never the page) under a shell dialog, search or
          settings, as the dock is. Closed on entering Operations and followed across tabs by
          `GuideProvider`. */}
      {guideDockShown && (
        <GuideDock
          band={askSurface}
          sticky
          inert={askDialogOpen}
        />
      )}

      {/* Ask PhotoCast. The phone's bar is `position: fixed` and sits BELOW every `Modal` (z-50) and
          the sheet's scrim; the sheet is a `BottomSheet`, portalled to <body>. Both are drawn only
          where `askEntry` says (plan §2.6). */}
      {askEntry === 'bar' && (
        <AskBar
          prompt={ASK_PROMPT}
          disabled={askBlocked}
          locked={askLocked}
          expanded={askSheetShown}
          upsellOpen={askUpsellOpen}
          upsellId={askUpsellId}
          onKeyDown={askUpsellOpen ? onAskTriggerKeyDown : undefined}
          onOpen={askLocked ? toggleAskUpsell : openAskSheet}
          buttonRef={askTriggerRef}
        />
      )}
      {/* ...and the bar's upsell, a fixed popover above it (same stacking tier as the bar, below `Modal`). */}
      {askUpsellOpen && askEntryKind === 'bar' && (
        <AskUpsellPopover
          id={askUpsellId}
          rootRef={askUpsellRootRef}
          triggerRef={askTriggerRef}
          placement="bar"
          onClose={() => closeAskUpsell({ restoreFocus: true })}
          onFocusAway={() => closeAskUpsell({ restoreFocus: false })}
          foreignOpen={isForeignDialogOpen}
        />
      )}
      {/* The sheet's chunk is fetched the first time it opens; after that it stays mounted (closed) for as
          long as an entry exists, as it always has, so closing restores focus through the same instance.
          Its fallback is null: the sheet is an overlay, so nothing in the page moves while it loads. */}
      {askEntry !== null && (askSheetShown || askSheetLoaded) && (
        <ChunkErrorBoundary variant="overlay" label="Ask PhotoCast" open={askSheetShown} onClose={closeAskSheet} backLayer={false}>
        <Suspense fallback={null}>
          <AskSheet
            open={askSheetShown}
            onClose={closeAskSheet}
            view={askViewSpec.view}
            viewLabel={askViewSpec.label}
            pickActions={askPickActions}
            planActions={askPlanActions}
            restoreFallback={askRestoreFallback}
          />
        </Suspense>
        </ChunkErrorBoundary>
      )}

      {/* The Map's spot list (glance-to-map-plan.md G1). Keyed on the window, so opening a different card's list mounts
          a fresh one rather than carrying the previous window's widening across. Both axes start from the Map's own
          tier and floor and are local from there on, so neither carries across: the sheet reads no storage at all. */}
      {sheetCard && (
        <WindowSpotSheet
          key={sheetCard.key}
          card={sheetCardShown}
          // The sheet inherits the MAP's tier and rating floor, and on a phone it is a bottom sheet (the Map's phone
          // dialog shape, not the centred modal). Its own changes to either are local and die with the dialog.
          presentation={isMobile ? 'sheet' : 'modal'}
          barTierId={sheetScope.tierId}
          barFloorId={sheetScope.floorId}
          // A boolean, never the role — plan §5c's rule that `role` enters this arm at the provider
          // and stops there. The sheet's reach control is the same PRO control the bar is (§7), so
          // it takes the same lock; the rating floor and the type are not gated at all.
          reachLocked={reachLens.locked}
          typesByName={typesByName}
          // ⚠️ The same one-layer-per-press predicate its sibling layers carry. Search is refused while anything is
          // stacked (`stackedOverLayer` counts this sheet), so nothing can sit over this one but the guide, and with
          // two layers standing the guide opens its full page in a new tab instead of a third layer. Kept in the
          // shared shape because `WindowSpotSheet` derives `stacked` from it, and those two must never come apart.
          escapeEnabled={searchSeed == null && !guideModal}
          onClose={() => openSheetLayer(null)}
          // A row selects the place IN PLACE on the Map it came from and closes the list: not `openMapTab`, which
          // would re-door the Map (and rewrite its lens). The callout is no Back layer, so it stacks freely over
          // what is left.
          onOpenSpot={(spot) => { const pick = sheetScope.onPick; openSheetLayer(null); pick?.(spot); }}
        />
      )}

      {/* Lazy, and mounted only while open — it is a dialog, so it is on no first-paint path.
          Keyed on the seed so opening it from the beyond line always mounts a fresh box with that
          region typed in, rather than reusing one that has already been edited. */}
      {searchSeed != null && (
        <Suspense fallback={null}>
          <PlanSearch
            key={searchSeed}
            stacked={guideModal}
            initialQuery={searchSeed}
            context={mapOpen ? 'map' : 'screen'}
            windows={mapOpen ? mapFindWindows : heatStripCards}
            regions={regions}
            locations={heatSpots}
            originId={origin?.id ?? null}
            // The figure and sub-line columns (M3.4). Every one is a value some other surface on
            // this page already draws — the sheet's id-first ratings, the page's reach map and its
            // planning area — handed over rather than re-derived, so the box cannot state a
            // different answer from the thing it opens. (The window figure needs no prop at all:
            // `heatStripCards` already carries each window's own `bestReach`.)
            reachById={effectiveReachById}
            scoreIndex={detailScoreIndex}
            scopeRegionNames={planScopeNames}
            origin={origin ?? null}
            onClose={() => setSearchSeed(null)}
            // ⚠️ CLOSES ANY SHEET LAYER IN THE SAME COMMIT, and that is the P8 invariant rather than
            // tidiness. (Not an ordering: React batches both setters out of one handler, so the
            // unmount and the origin change land together and there is no frame in which a sheet
            // is rendered against the new origin — a stronger guarantee than "close, then move".)
            // Moving the origin with a sheet up would swap `effectiveReachById` underneath it: the drive, the
            // base named beside it, the outside badge and every leave-by would re-derive while the reader looks at
            // them. P8 refused to build exactly that, and M4.3's `Plan from <region>` footer is specified as
            // close-then-move for the same reason — two contradictory semantics for one action on
            // one screen is what this avoids.
            // ⚠️ `openSheetLayer(null)` as well — the SAME rule, applied at every route that moves
            // the origin rather than only at the reachable ones. Moving it with a location sheet up
            // is the exact thing M4.3's close-then-move footer goes to trouble to prevent: the
            // drive, the base named beside it, the outside badge and every departure would all
            // change under the reader.
            // ⚠️ It is NOT here because search can sit over that sheet — it cannot. The third layer is refused
            // outright: all three routes into search guard on `stackedOverLayer`, which counts `sheetSpot`, so the
            // supported stack is two deep — an area sheet under a place sheet, or search alone (plan-matrix §4 A22).
            // So this arm cannot fire with a sheet up today. It stays because the invariant is
            // stated once per route — `onGoHome` above and the sheet's own footer state it too —
            // and a route added later inherits it instead of rediscovering why this one was
            // exempt. One rule, every route.
            // Per tab since F1: Plan and Coming up plan from the region (the line above is why any sheet
            // closes in the same commit); the Map only pans and frames it — see `pickFindRegion`.
            onPickRegion={pickFindRegion}
            // ⚠️ The same belt here, and for the reason above rather than a new one: nothing can be over search
            // when a pick fires, because a sheet layer is what refuses search. A pick on a window selects it on the
            // Map (`pickFindWindow`), which closes any sheet through `closeShellLayers` first.
            onPickWindow={pickFindWindow}
            // P8: a location result opens that place's own six-window timeline rather than jumping
            // straight to the map. The map is not lost — the sheet's footer carries it and names
            // the window it opens.
            //
            // Closes any sheet first, for the same reason `onPickRegion` above does: arriving from search is a
            // different gesture from reading a sheet, and it is the one the shell's own "closes FIRST" rule already
            // governs everywhere else.
            onPickLocation={pickFindLocation}
            areas={areaFind.directory}
            areaOutlook={areaFind.outlook}
            onPickArea={pickFindArea}
          />
        </Suspense>
      )}

      {/* The area sheet (F2): under the place sheet when a row of it is opened (two deep, Find having closed on the
          pick), so it is inert and withholds Escape while that sheet stands. Focus goes back to the Find button, the
          door the reader came through. */}
      {areaSheet && (
        <Suspense fallback={null}>
          <AreaSheet
            key={areaSheet.id}
            area={areaSheet}
            spots={heatSpots}
            windows={heatStripCards}
            scoreIndex={detailScoreIndex}
            reachById={effectiveReachById}
            allowedLocationIds={allowedLocationIds}
            stacked={sheetSpot != null || guideModal}
            restoreFocusFallback={() => {
              const find = shellRef.current?.querySelector('[data-testid="window-first-search"]');
              return areaFromFindRef.current && find?.isConnected ? find : null;
            }}
            onClose={() => setAreaSheet(null)}
            onOpenSpot={(spot) => openSheetLayer({ spot })}
            onShowOnMap={onOpenMapTab ? showAreaOnMap : null}
          />
        </Suspense>
      )}

      {/* Lazy and mounted only while open, exactly as search is. Keyed on the spot's identity so
          picking a second place from search mounts a fresh sheet — the expanded-row seed is chosen
          once per mount, and reusing the instance would carry one location's open rows onto
          another's. */}
      {sheetSpot && (
        <Suspense fallback={null}>
          <LocationFourDaySheet
            key={sheetSpot.id ?? sheetSpot.name}
            spot={sheetSpot}
            windows={heatStripCards}
            scoreIndex={detailScoreIndex}
            slotIndex={slotIndex}
            // A failed or in-flight ratings fetch is not evidence that nothing was rated, so the
            // sheet's "Not scored yet" is gated on the same flag the strip's unscored mark is.
            scoresKnown={scoresLoaded}
            // The map the PAGE plans from, so the sheet and the cards behind it describe one
            // journey. At home it is the per-user map unchanged; away it is the shared matrix.
            reachById={effectiveReachById}
            scopeRegionNames={planScopeNames}
            origin={origin}
            // "home" rather than nothing when the settings fetch has not named the place: the drive
            // figure needs an origin on it to be placeable, and "from home" is true either way.
            originLabel={origin ? origin.baseName : (homePlace || 'home')}
            todayStr={todayStr}
            // The roster record behind the open sheet, for its meta row (increment §2) and its
            // per-window tide sentence (§3).
            location={sheetLocation}
            // The window the map's callout was on — see `sheetWindowKey`. Null from every other
            // entry point, which keeps their seeding unchanged.
            focusWindowKey={sheetWindowKey}
            // The prose FALLBACK, so this sheet can never show less than the callout that routes
            // into it — the callout has had a region gloss behind its summary since P9.
            regionGlossIndex={sheetGlossIndex}
            // The tide-fit block, per solar row (T5).
            // The per-location tide match is Pro (tide-pro-gate-plan.md, Q2): a locked reader's sheet gets no index,
            // so no TideFitBlock and no next-fit line.
            tideAlignmentIndex={isPro ? sheetTideAlignmentIndex : null}
            // The eclipse spot line, per solar row (L7).
            eclipseIndex={sheetEclipseIndex}
            // The C2 forecast-detail block's tier gating (a boolean, never the role — plan §5c).
            isPro={isPro}
            // Scores and the written why are Pro: the sheet drops its "nothing written" line and draws the door.
            scoresLocked={scoresLocked}
            // Outside the reader's sub-regions (S5a-3): the served list leaves this place out. Its own state on the
            // sheet; the block's button closes this sheet before Settings opens.
            outsidePicks={isOutsidePicks(allowedLocationIds, sheetSpot.id ?? sheetLocation?.id ?? null)}
            onOpenSubRegions={openSubRegions
              ? () => { openSheetLayer(null); openSubRegions(screenFocusNode); }
              : null}
            // Where the close lands when the sheet was opened from Ask's sheet (F5); null for every
            // other opening, which keeps its restore exactly as it was.
            restoreFocusFallback={askSheetFallback}
            escapeEnabled={searchSeed == null && !guideModal}
            // The footer's origin action (M4.3, D-4). `planFrom` is null when the shell holds no
            // record for the place's region, which is the honest answer rather than a guessed
            // reason — `originAction`'s three are all statements ABOUT a record.
            planFrom={sheetPlanFrom}
            // ⚠️ Present only when the region may actually be an origin, and that presence is what
            // the sheet reads to decide between a control and a stated reason. Undefined is
            // deliberate rather than a no-op function: a button that does nothing is plan §3
            // rule 14's ban.
            onPlanFrom={sheetPlanFrom?.region
              ? () => {
                // An area sheet under this place sheet goes with it: the origin must not move under an open surface.
                setAreaSheet(null);
                setOrigin?.(sheetPlanFrom.region);
                // ⚠️ The focus move, and the reason is the one `applyConflictAction` already
                // records: this commit unmounts BOTH dialogs, so `useDialogFocus`'s restore finds
                // its captured trigger — a chip or a card that lived inside the deleted window popup — detached,
                // declines to focus it, and the reader is dropped at `<body>` while the page
                // re-frames underneath them. The card's inner button (`wf-heat-card-button`), because
                // the card is a `<div>` and an away window has no button: `querySelector` returns DOM
                // order — which, since the rails restructure, lands on the first card of the
                // SUNRISE row rather than the first day's own first window (matrix-axis plan D20).
                // Deferred a frame because the matrix is re-rendering on this very commit, and
                // optional-CALLED because jsdom implements no layout.
                requestAnimationFrame(() => {
                  document.querySelector('[data-testid="wf-heat-card-button"]')?.focus?.();
                });
              }
              : undefined}
            onClose={() => openSheetLayer(null)}
            // Door 2 (doors D3, `plan-to-map-doors-plan.md` §3 D3 task 1). `openMapTab` (the
            // `openMapDoor` wrapper above) does its OWN close-then-move: `openSheetLayer(null)` (since G2a
            // it no longer calls `openWindow(null)`) before it reads the live lens and hands the door to `onOpenMapTab`, so
            // nothing here repeats that ordering — repeating it would just be two callers racing to
            // close the same two pieces of state. `region: null` because this door names a LOCATION,
            // never a region (the deleted pick dialog's two actions below take the same door since C4).
            //
            // ⚠️ WITHHELD, not just unwired, when there is no map door: `App` hands this shell
            // `undefined` for `onOpenMapTab` whenever there is nothing to map
            // (no `hasForecastData`; this component's own default parameter is `null`, but no
            // production caller ever omits the prop, so `App`'s value is the one that reaches here),
            // and passing a function regardless would leave the sheet's `handoff && onShowOnMap`
            // gate rendering a button that calls nothing — the dead-control ban the sheet's own
            // footer comment names for `onPlanFrom`. `undefined`, never a no-op arrow, so
            // `LocationFourDaySheet` renders `location-sheet-nomap`'s sentence in its place.
            onShowOnMap={onOpenMapTab
              ? (date, targetType, name) => {
                openMap({ window: { date, eventType: targetType }, focus: { kind: 'location', name } });
              }
              : undefined}
          />
        </Suspense>
      )}
    </div>
  );
}

WindowFirstShell.propTypes = {
  /**
   * The dates the reader holds a colour forecast for (`App`'s `allDates`), handed to the Coming up
   * tab so it can withhold a map door onto a date with nothing to draw. Null or absent means the
   * forecast is not known yet and nothing is withheld; an empty array is known-empty.
   */
  forecastDates: PropTypes.arrayOf(PropTypes.string),
  /** The active scoreRamp mode, forwarded to the heat strip as its paint-repaint key. */
  mapColourScale: PropTypes.oneOf(['temp', 'verdict']),
  /**
   * The shell→App channel for the screen in force: {@code 'plan'} (the screen), {@code 'map'} (the map view is open) or
   * {@code 'operations'}, fired on mount and on every change, and {@code null} on unmount. `App` cannot otherwise learn
   * it (the view's state is shell-internal), and it needs it to lift the session notice over the map view, to inert
   * the page's own banners behind it and to tell the guide which page to follow.
   */
  onScreenChange: PropTypes.func,
  onOpenSettings: PropTypes.func.isRequired,
  /**
   * Whether `App`'s settings dialog is open. The shell does not render that dialog, so this is the
   * only way it can hear one has opened by a route that bypasses its own controls — the Map tab's
   * ⌂ — and take its own dialogs down in the same commit. See the `settingsWasOpen` edge.
   */
  settingsOpen: PropTypes.bool,
  onSignOut: PropTypes.func.isRequired,
  contentDisabled: PropTypes.bool,
  onEvaluationScoresChange: PropTypes.func,
  /** Lifts `briefing.seasonalFeatures` to App. Optional-called, so
      every existing test renders without it. */
  onSeasonalFeaturesChange: PropTypes.func,
  locations: PropTypes.array,
  /** The Map pane. Absent means no map view and no door onto one. */
  mapPane: PropTypes.node,
  /**
   * Registers a "close my dialogs" callback with `App` (called with the closer, and with null on
   * unmount) - the aurora banner sits outside the shell and must be able to take a Map-hosted
   * four-day sheet down before it changes the window under it.
   */
  registerCloseDialogs: PropTypes.func,
  /**
   * Hands the Map a Find pick that is not a window (find-and-favourites-plan.md F1): `{kind: 'location', id,
   * name}` or `{kind: 'region', name}`. Absent when there is no Map pane to receive it.
   */
  onFindOnMap: PropTypes.func,
  /** Told whether Find is open, so the Map pane can close its menus while it is. */
  onFindOpenChange: PropTypes.func,
  /** The Map tab callout's four-day-sheet handoff (map-tab-v2-plan.md §3 P9) — `App.jsx`'s
   * `openLocationSheet`, `openFullMapTab`'s shape in reverse. `inPlan` is the destination: true
   * moves to the Plan tab (the callout's `Open in Plan` button), false/absent leaves the reader on
   * whichever tab asked, with the sheet over it (the prose's `Four days here ›`). `date`/
   * `targetType` seed the sheet's open window — see the handoff effect's own note. */
  locationSheetHandoff: PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    name: PropTypes.string,
    regionName: PropTypes.string,
    inPlan: PropTypes.bool,
    date: PropTypes.string,
    targetType: PropTypes.string,
    nonce: PropTypes.number,
  }),
  /**
   * The map doors' shared entry (doors D2, `plan-to-map-doors-plan.md` §3) — `App.jsx`'s
   * `openMapTabFromPlan`, called by the shell's own `openMap` (which closes the layers first and merges in the live
   * lens values). Absent whenever there is nothing
   * to map (`App`'s own `hasForecastData` gate) — no door renders without it: a control that opens
   * nothing is banned, so every producer's control is withheld with it.
   */
  onOpenMapTab: PropTypes.func,
  /**
   * The Operations pane. Absent means no Operations view and no ☰ menu row for it, and that is the admin gate in full:
   * the caller holds the role and withholds the pane, so nothing role-shaped reaches this component.
   */
  operationsPane: PropTypes.node,
  /** Whether the page's address says Operations is open (`#manage...`, read by `App`); shown only with the pane. */
  operationsOpen: PropTypes.bool,
  /** Opens Operations from the ☰ menu's row. Absent means the menu has no Operations row. */
  onOpenOperations: PropTypes.func,
  /** The "‹ PhotoCast" button's handler: leaves Operations for the screen. */
  onCloseOperations: PropTypes.func,
  /**
   * The masthead's status pill, on the same terms as {@code operationsPane}: absent means no pill,
   * which is how the admin gate reaches this arm without a role crossing into it.
   */
  healthPill: PropTypes.node,
  /**
   * The phone masthead's status dot and the ⋯ menu's status line (compact-chrome plan R1): the same
   * health reading as {@code healthPill}, on the same terms — absent means none, which is how the admin
   * gate reaches them without a role crossing in. The pill is not drawn on the phone; these are.
   */
  healthDot: PropTypes.node,
  healthStatusLine: PropTypes.node,
  /**
   * Today's light for the masthead's rule. Three states in one value — undefined while the answer
   * is outstanding, null once it has arrived with no home saved, the day otherwise. Deliberately
   * not shape-checked here: {@link MastheadLight} owns that contract, and restating it would give
   * one payload two definitions that can drift.
   */
  light: PropTypes.object,
  /**
   * Opens settings on the postcode field for the band's nudge; falls back to onOpenSettings. Called
   * with a function returning the tick line's origin-slot element, for the dialog's close.
   */
  onSetPostcode: PropTypes.func,
  /**
   * The user's saved geocode, or null with no postcode saved — the same value {@code App} already
   * hands the Map pane, reused so the Plan surfaces' home marker (and, at G3, its reach rings) can
   * never name a different point (field-geography plan §2.1). Never a constant.
   */
  homeCoords: PropTypes.shape({
    lat: PropTypes.number,
    lon: PropTypes.number,
  }),
  /**
   * Registers {@code openMap} with `App` (called with an opener, and with null on unmount): the aurora banner is outside
   * the shell and asks it to show the map view after handing the map its own handoff.
   */
  registerOpenMap: PropTypes.func,
  /**
   * Whether the one-time "Bookmark places and watch dates" hint is still due (screen-spec.md D8): the server's own
   * {@code placesHintDue}, read with the reader's settings. False while unknown. Handed to Your places.
   */
  placesHintDue: PropTypes.bool,
  /** Records the hint's dismissal (hides it at once and writes the flag). Absent in tests that do not render the hint. */
  onDismissPlacesHint: PropTypes.func,
  /** Told once the reader has saved a favourite while the hint was due, so the page's own flag drops with no write. */
  onSettlePlacesHint: PropTypes.func,
  /**
   * The map view to open at load (one-screen-plan.md D4): `{date, eventType, locationId}` from the page's own `#map/...`
   * address. Null means open nothing: every device opens on This week.
   */
  loadMapRoute: PropTypes.shape({
    date: PropTypes.string,
    eventType: PropTypes.string,
    locationId: PropTypes.number,
  }),
  /** Told once the shell has acted on (or dropped) {@code loadMapRoute}, so `App` can spend it. */
  onLoadMapRouteHandled: PropTypes.func,
};
