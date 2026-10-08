import React, {
  lazy, Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import BrandLockup from './shared/BrandLockup.jsx';
import MastheadLight from './shared/MastheadLight.jsx';
import MastheadTickLine from './MastheadTickLine.jsx';
import WindowFirstLensBar from './WindowFirstLensBar.jsx';
import WindowFirstDoors from './WindowFirstDoors.jsx';
import WindowFirstComingUp from './WindowFirstComingUp.jsx';
import WindowPickDialog from './WindowPickDialog.jsx';
import WindowSpotSheet from './WindowSpotSheet.jsx';
import AskBar from './ask/AskBar.jsx';
import AskDock, { ASK_DOCK_ID } from './ask/AskDock.jsx';
import AskField from './ask/AskField.jsx';
import AskSheet, { ASK_SHEET_LABEL } from './ask/AskSheet.jsx';
import { useAsk } from '../context/AskContext.jsx';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { formatRelativeAge } from '../utils/relativeTime.js';
import { buildLocationTypeMap } from '../utils/locationTypes.js';
import { ANY_TIER_ID } from '../utils/reachLens.js';
import { sheetOffersMore } from '../utils/windowSpotBrowse.js';
import { originAction, scopeRegions } from '../utils/planOrigin.js';
import { buildTopicIndex, windowTopics } from '../utils/windowFirstTopics.js';
import { buildPlanConflict } from '../utils/planConflicts.js';
import {
  buildEclipseIndex, buildScoreIndex, buildSlotIndex, buildTideAlignmentIndex, sheetSpotOf,
} from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/regionGloss.js';
import { openMapDoor } from '../utils/mapDoors.js';
import { foreignDialogOpen } from '../utils/shellForeignDialog.js';
import { deriveBadge } from '../utils/comingUpArrivals.js';
import { markComingUpSeen } from '../api/settingsApi.js';
import useAskSurface from '../hooks/useAskSurface.js';
import useComingUpFeed from '../hooks/useComingUpFeed.js';
import useLensReserve from '../hooks/useLensReserve.js';
import useStuckSentinel from '../hooks/useStuckSentinel.js';

/**
 * The heat strip, behind a lazy boundary for chunk hygiene.
 *
 * <p>{@code App} imports this shell STATICALLY (unlike `MapView`, `WindowFirstMapPane`,
 * `MapOverlay` and `ManageView`, which are all `lazy()`), so a static import here would put the
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
 * The window popup, on the same terms — a dialog, not a first-paint element, and the boundary is
 * load-bearing for the same reason the strip's is.
 *
 * <p>It reaches the field map, and through it {@code heatField.js}'s {@code d3-geo} and
 * {@code topojson-client}. A static import here would put all of that in the entry chunk for every
 * reader — the exact measurement {@code WindowRowRegionLayer} recorded (+21.4 KB raw / +7.2 KB
 * gzip) before this phase deleted it. The lazy strip above already carries the chunk for a reader
 * who has seen the matrix, so opening a window is usually a cache hit rather than a fetch.
 */
const WindowSheetDialog = lazy(() => import('./WindowSheetDialog.jsx'));

/**
 * The four-day location sheet, on the same terms — a dialog, mounted only while open (P8).
 *
 * <p>Lazy for the reason search is, and for one more: it pulls in {@code locationSheet.js} and the
 * slot-time index, neither of which any other surface reads. A reader who never searches for a
 * place never downloads them.
 */
const LocationFourDaySheet = lazy(() => import('./LocationFourDaySheet.jsx'));

/**
 * Warms the three dialog chunks that can be STACKED, once the popup is open.
 *
 * <h2>⚠️ This is a correctness mitigation, not a speed one</h2>
 *
 * <p>Since M5 a covered layer is {@code inert} — and {@code stacked} is derived from the shell's
 * intent, which is synchronous, while the layer doing the covering arrives when its chunk does.
 * Measured in a browser with the sheet's chunk throttled to 2.5 s: for the whole fetch the page held
 * <b>one dialog and zero live layers</b> — the popup inert (every control, the backdrop and its
 * Escape all dead) and the sheet not yet mounted, with {@code fallback={null}} rendering nothing in
 * between. Before M5 the popup merely declined Escape and stayed clickable, so this is a regression
 * the {@code inert} work introduced rather than an inherited gap.
 *
 * <p><b>Why warming rather than a loading dialog or a mount signal.</b> A {@code Modal} fallback
 * would flash a second loading state on every cold open for a wait that is usually zero; deferring
 * {@code stacked} until the arriving layer reports its mount means a new prop on three components
 * and an extra commit on the hottest interaction on the page, at the settling commit. Warming
 * removes the window on every route that exists: a chip, a spot card, a pick badge and the masthead's
 * ⌕ (formerly {@code /} too) are all reachable ONLY from an open popup, so the fetch has the reader's whole reading time to
 * finish. It is idempotent (the module registry dedupes), it is fire-and-forget, and a failure is
 * the same failure the real import would have had.
 *
 * <p><b>Residual, stated rather than defended against:</b> a reader who opens a window and clicks a
 * chip inside the same few hundred milliseconds on a cold, slow connection can still reach the gap.
 * §11 records it, and the real fix — gate {@code stacked} on the covering layer having mounted — is
 * named there rather than smuggled in here.
 */
function warmStackedChunks() {
  // Only the two that are LAZY. `WindowSpotSheet` and `WindowPickDialog` are static imports and are
  // already in the entry graph, which is why they never show the gap.
  import('./LocationFourDaySheet.jsx').catch(() => {});
  import('./PlanSearch.jsx').catch(() => {});
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

/**
 * The point set a window with nothing scored gets — one frozen array rather than a fresh literal,
 * so a card whose window has no points does not get a new prop identity on every shell render.
 */
const EMPTY_POINTS = Object.freeze([]);

/** The design's frame: 1080px. */
const WRAP_MAX_WIDTH = '1080px';

/**
 * How long the opening tab PREFERENCE stands undisturbed before it commits on its own
 * (default-tab-by-device-plan.md §4.3) — a reader who reads without touching anything is still
 * reading, and a tab switching under them several seconds in is the same jolt as switching it
 * under an active interaction. A one-shot timer from mount; cleared on unmount, and a no-op once
 * the reader has made an explicit or interaction-driven choice.
 */
const PREFERRED_TAB_GRACE_MS = 1500;

/**
 * The tab bar's contents, in order.
 *
 * <p><b>A tab with a {@code slot} appears only when the shell is handed that pane.</b> That is the
 * rule this file has always stated — "a tab that renders nothing is a demo control and §6 bans
 * those, so each tab lands with its pane" — now enforced by construction rather than by keeping the
 * list short. It is also how the admin gate works: {@code App} holds {@code isAdmin} and simply
 * does not pass {@code operationsPane}. Nothing role-shaped crosses this boundary — no role, no
 * {@code isAdmin} boolean, no prop the arm would then have to explain — which is what plan §5c
 * exists to protect, and it is a stronger guarantee than a gate the shell could get wrong.
 *
 * <p>The glyph is decorative and {@code aria-hidden}, so the accessible name stays the bare word.
 * Coming up has none, matching the mock. <b>Operations has none either, and that is a collision
 * rather than a preference:</b> {@code ⚙} is already the masthead's settings control a few pixels
 * away, so using it here would put the same glyph on a modal and on a tab. It also costs 17.84px
 * of a bar that has to fit a phone (measured).
 */
const TABS = [
  { id: 'plan', label: 'Plan', glyph: '◉' },
  { id: 'coming-up', label: 'Coming up', glyph: null },
  { id: 'map', label: 'Map', glyph: '◍', slot: 'mapPane' },
  { id: 'operations', label: 'Operations', glyph: null, slot: 'operationsPane', gated: true },
];

/**
 * The bar's prompt, by tab (design README, "Where Ask lives"). The bar is drawn on Plan and Coming up
 * only, so those are the two keys.
 */
const ASK_BAR_PROMPT = {
  plan: 'Ask about this weekend…',
  'coming-up': 'Ask about rare events…',
};

/**
 * What the view chip says, and what the question is sent with, by tab. Map reads "all regions" here
 * because the shell cannot see the Map's own scope segment or window (they are `MapView` state): the
 * chip names what is SENT, and until F3/F4 carry the Map's scope to a surface, a question from the
 * sheet OR the dock is sent with no region and no window.
 */
const ASK_VIEW = {
  plan: { view: 'plan', label: 'Plan · all regions' },
  'coming-up': { view: 'coming-up', label: 'Coming up · all regions' },
  map: { view: 'map', label: 'Map · all regions' },
};

/** What the dock's header says it is beside, by tab (the mock's "· on Plan" / "· on Map"). */
const ASK_DOCK_CONTEXT = {
  plan: 'on Plan',
  'coming-up': 'on Coming up',
  map: 'on Map',
};

/**
 * The field's width by band: 340px with the `/` hint from 1180px, 260px from 1024 to 1179 where the
 * dock (360px) leaves the tab row too little room for more, and the tablet's 260px below it.
 */
const ASK_FIELD_WIDTH = { tablet: 260, desktop: 260, wide: 340 };

/** `window-first-tab-plan` — the id the panel points back at, and the existing test-id. */
const tabDomId = (id) => `window-first-tab-${id}`;

/** `window-first-panel-plan` — the id the tab's `aria-controls` points at. */
const panelDomId = (id) => `window-first-panel-${id}`;


/**
 * The window-first Plan tab's own shell — masthead, tab bar, and the frame both sit in.
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
 * still sees no pill and nothing role-shaped crosses this boundary (plan §5c). The unconditional
 * pill the design draws is still not what ships — the design has no roles in it.
 *
 * <h2>The tab bar carries Plan and Coming up, and still not Map or Manage</h2>
 *
 * <p>The design draws four tabs. Two of their panes do not exist yet: Map and Manage arrive when
 * this subtree takes over view state. A tab that renders nothing is a demo control, and §6 bans
 * those from the shipped build — so each tab lands with its pane, which is why P13 adds the second
 * one and no more.
 *
 * <p>{@code border-bottom-width: 0} on the tab, never {@code border-bottom: none} — the shorthand
 * would also clear the colour the active state needs.
 *
 * <h2>The tab bar became a real ARIA tab widget at P13, and it was not one before</h2>
 *
 * <p>Through P12 the bar had {@code role="tablist"} and one {@code role="tab"} with a hard-coded
 * {@code aria-selected="true"}, no {@code aria-controls}, no {@code id} pairing and no
 * {@code role="tabpanel"} anywhere in the repo. With one tab that is inert rather than wrong. With
 * two it is a promise the markup does not keep: a screen-reader user is told there is a tab list
 * and then given no way to know what either tab controls. So P13 completes the pattern —
 * {@code aria-selected} on both, {@code aria-controls}/{@code aria-labelledby} pairing each tab to
 * its panel, a roving {@code tabIndex} so the bar is one stop rather than two, and Left/Right/Home/
 * End moving between them.
 *
 * <p><b>Selection follows focus</b>, which is the authoring-practices default and is right here
 * because both panes are already-mounted state rather than a fetch — arrowing onto Coming up does
 * trigger its one lazy request, which is exactly what the reader asked for by arrowing onto it.
 *
 * <p>This is the first roving-tabindex implementation in the codebase; there was nothing to copy —
 * {@code ManageView}'s tabs carry no roles at all.
 *
 * <h2>Tab selection is deliberately not persisted — but the OPENING tab is now device-aware</h2>
 *
 * <p>The arm persists two things — the reach lens and the rating floor — and both are settled
 * preferences. Which tab you last had open is not: the reader's question on opening the app is
 * almost always "what about tonight", and restoring a ninety-day almanac because they browsed it
 * yesterday answers a question they are not asking. It also spends the first paint on a fetch. No
 * {@code localStorage}, and the default is recomputed on every visit — nothing here is
 * per-session memory.
 *
 * <p>What changed (default-tab-by-device-plan.md) is which tab that reset lands ON, not whether it
 * resets: the app opens on Plan on a phone and on Map on anything larger, decided once per visit by
 * {@code App} ({@code utils/initialTab.js}, a pure viewport read) and handed down as
 * {@code initialTab}. This component stays device-agnostic — it never reads a media query itself —
 * and models the opening tab as a PREFERENCE that stands until the reader makes an actual choice,
 * because the Map pane does not exist at first render (`App.jsx`'s `hasForecastData` gate) and
 * so cannot simply be selected up front. {@code activeTab} is {@code null} until then; see its own
 * Javadoc, {@code commitTabInForce} and {@code PREFERRED_TAB_GRACE_MS} for how the preference is
 * allowed to resolve to Map once, and never moves the tab under a reader who has done anything at
 * all with the page. The cost of an opening tab being wrong is still one click.
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
 * cells and one popup over them. So this component holds a single {@code openWindowKey} rather than
 * a per-card collapse map, and a single {@code focusedRegion} rather than one per row.
 *
 * <p>{@code buildPaneItems} survives the deletion and is still read — it is the empty-state line's
 * denominator, and the derivation that keeps away days accounted for. What went is the
 * <em>rendering</em> of it, and (at M5, with the promoted strip) its away payload: the block's
 * label, note and window count had no reader left once nothing rendered a row for it.
 *
 * @param {function} props.onSignOut ends the session — the masthead's route to it while the Plan
 *        is healthy; {@code PlanErrorBoundary} offers its own separate Sign-out if it is not.
 * @param {Array} [props.locations] enabled locations. The regional-planner door needs its id→name
 *        and name→type joins; the drill-down needs the same name→type join for its type control.
 *        Not fetched by this arm's provider: {@code App} already holds them, and a second request
 *        for a list the page has would be waste.
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
 *        by the heat strip's home marker and (at G3) the popup field's reach rings. `App` hands
 *        `undefined` while the home is not known, and the default folds that into null here, which
 *        is safe only because every Plan surface draws a home and none prompts for one — the map's
 *        ⌂ does, which is why `WindowFirstMapPane` and `MapView` take the prop bare.
 */
export default function WindowFirstShell({
  onOpenSettings, onSignOut, contentDisabled, onShowOnMap, onEvaluationScoresChange,
  onSeasonalFeaturesChange, locations, mapPane, operationsPane, tabRequest, healthPill,
  light, onSetPostcode, mapColourScale = null, homeCoords = null, onTabChange = null,
  locationSheetHandoff = null, onOpenMapTab = null, settingsOpen = false, initialTab = null,
  forecastDates = null,
}) {
  const {
    heatStripCards, heatPointSets, heatSpots, reachById, regionSeries,
    windowCards, paneItems, loading, briefing, evaluationScores, scoresLoaded,
    scoreIndex, scoreRows, todayStr, reachLens, ratingLens, homePlace,
    origin, setOrigin, regions, effectiveReachById,
    comingUpLastSeenDate, setComingUpLastSeenAt,
  } = useWindowFirstBriefing();
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
   * Whether Ask PhotoCast's sheet is open (plan §2.6: whether Ask is OPEN is SHELL state).
   *
   * <p>The shell's own {@code useState}, and it must stay one: {@code selectTab} clears it, and
   * {@code selectTab} runs during render on the settings edge, where it may call only this
   * component's own setters. A context value could not be cleared from there. Never set from
   * {@code AskContext}, which holds the conversation and nothing about a surface.
   */
  const [askSheetOpen, setAskSheetOpen] = useState(false);
  /**
   * Whether Ask PhotoCast's DOCK is open (F2) — its own state, deliberately not a value of
   * {@code askSheetOpen}/{@code askEntry}. {@code askSheetShown} drives the app container's {@code inert}, and
   * the dock is not modal: folding it in would make the dock inert itself and the page beside it dead.
   *
   * <p>Not on {@code selectTab}'s list either, and that is the point of a dock: it survives a switch
   * between Plan, Coming up and Map (the conversation chips and suggestions follow the tab). It goes
   * when nothing can draw it any more — Operations, a band below 1024px, Ask switched off — through the
   * same render-time release {@code askSheetOpen} uses.
   */
  const [askDockOpen, setAskDockOpen] = useState(false);
  /** The Ask bar or field that is on screen, where closing the sheet or the dock returns focus. */
  const askTriggerRef = useRef(null);
  /** The dock's question field, so `/` can focus it when the dock is already open. */
  const askDockInputRef = useRef(null);
  const ask = useAsk();
  const askSurface = useAskSurface();
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
   * The window whose popup is open, held by KEY — or null.
   *
   * <p>The whole of what M2 replaces: there is no per-card open state any more, because there are no
   * cards to open. Six accordion rows became one dialog, and a dialog is a single value.
   *
   * <p>By key rather than by the card object, for the reason the spot sheet's own key already gives:
   * the provider rebuilds every descriptor on the ten-minute poll, on the reach fetch, and on every
   * lens change, so holding the object would leave the dialog describing a window the page behind it
   * had already replaced. Holding the key means it always reads the live card — and a window that
   * passes simply closes it rather than becoming a dialog about a window that no longer exists.
   */
  const [openWindowKey, setOpenWindowKey] = useState(null);
  /**
   * The region focused INSIDE the open popup, or null.
   *
   * <p>One value rather than the map-per-card the rows needed: only one window is open at a time
   * now, so a map would be a store with one live entry and five stale ones. It is reset whenever the
   * open window changes, which is the design's own rule — a focus is a question about one window's
   * field, and carrying it into the next window would silently filter a list the reader has not
   * looked at yet.
   */
  const [focusedRegion, setFocusedRegion] = useState(null);
  const openWindow = useCallback((key) => {
    setOpenWindowKey(key);
    setFocusedRegion(null);
    // ⚠️ Warms the two lazy chunks that can be STACKED over this popup — see `warmStackedChunks`
    // for the measured reason. Fire-and-forget and idempotent; it is not on any render path.
    if (key != null) warmStackedChunks();
  }, []);
  /**
   * {@code null} means the reader has not chosen a tab yet THIS VISIT — the opening preference
   * (default-tab-by-device-plan.md §4.3) still applies, and a later pane arriving (the Map
   * pane, handed over only once `GET /api/forecast` has returned rows) can still move the tab. Any
   * tab selection, or any interaction with the shell at all, pins this non-null and the preference is
   * dead from then on — see `commitTabInForce` below.
   */
  const [activeTab, setActiveTab] = useState(null);
  /**
   * The tabs this shell actually has, which is a function of the panes it was handed.
   *
   * <p>Depends on whether each pane is PRESENT, not on the node itself, because a parent that
   * rebuilds its JSX every render hands over new node identities every render. The memo is the
   * right shape; what it buys is a stable array rather than a fresh one per render — it does NOT
   * prevent a remount, which an earlier version of this comment claimed. Nothing here feeds a
   * dependency array, and the buttons rebuild each render regardless, keyed by a stable id.
   */
  const hasMapPane = mapPane != null;
  const hasOperationsPane = operationsPane != null;
  const tabs = useMemo(
    () => TABS.filter((t) => (t.slot ? { mapPane: hasMapPane, operationsPane: hasOperationsPane }[t.slot] : true)),
    [hasMapPane, hasOperationsPane],
  );
  /**
   * The tab actually rendered, which is not always the one last selected.
   *
   * <p>Without this a selection can outlive its tab — a session that loses admin, or a stored id
   * from a build that had one more pane. The bar would then have no tab holding
   * {@code tabIndex={0}}, which is the whole keyboard entry point, and every panel would be hidden.
   * Falling back to the first tab is the only state that is always coherent.
   */
  /**
   * The tab the reader would see if nothing overrode it — the opening preference while
   * {@code activeTab} is still {@code null}, {@code activeTab} itself once a choice has been made.
   * Falls back to Plan for a caller that hands no {@code initialTab} at all (every existing shell
   * test), pinning the long-standing default.
   */
  const preferred = initialTab ?? TABS[0].id;
  const requestedTab = activeTab ?? preferred;
  /**
   * The tab actually rendered, which is not always the one requested.
   *
   * <p>Without this a selection — or a preference — can outlive its tab: a session that loses
   * admin, a stored id from a build that had one more pane, or (new here) a preference for Map
   * while the map pane has not arrived yet. The bar would then have no tab holding
   * {@code tabIndex={0}}, which is the whole keyboard entry point, and every panel would be hidden.
   * Falling back to the first tab is the only state that is always coherent — and on a desktop this
   * is exactly what gives the intended first-render Plan / later-render Map sequence (§4.3): the Map
   * pane is absent at first paint (`App.jsx`'s `hasForecastData` gate), so this fallback lands
   * on Plan until it arrives, then resolves to Map the moment it does — but only while
   * {@code activeTab} is still {@code null}.
   */
  const effectiveTab = tabs.some((t) => t.id === requestedTab) ? requestedTab : tabs[0].id;
  /**
   * The shell→App channel the full-frame Map tab needs (map-tab-v2-plan.md §3 P7's first owner).
   * `App` recasts its whole page as a flex column on the map tab (dropping `<main>`'s own padding
   * and giving every ancestor down to `.wf-body.wf-body--map` `flex: 1; min-height: 0` instead of
   * a computed height) — but `effectiveTab` is shell-internal state `App` has no other way to
   * read. Fired on mount too (not only on change), so `App` learns the OPENING tab rather than
   * starting from a guess and correcting one render late.
   */
  useEffect(() => { onTabChange?.(effectiveTab); }, [effectiveTab, onTabChange]);
  /**
   * While the reader has not chosen a tab, ANY interaction with the shell is a choice of the tab in
   * force: pin it, so a map pane arriving later cannot move the tab under someone mid-task
   * (default-tab-by-device-plan.md §4.3). Deliberately not a list of triggers — an earlier draft of
   * that plan enumerated the dialog states that should commit, and review (PR #904) found the lens
   * bar (`WindowFirstLensBar.jsx` calls `reachLens.selectTier`/`ratingLens.selectFloor` directly,
   * never `selectTab`) slipping straight past it; any enumeration rots the same way as controls are
   * added. So this commits on the EVENT, at the shell root, in the capture phase — wired onto
   * `onPointerDownCapture`/`onKeyDownCapture`/`onWheelCapture` below.
   *
   * <p>`setActiveTab`, never `selectTab` — the point is to pin the tab CURRENTLY in force without
   * touching anything else `selectTab` does (closing the popup, the sheet, search…), which would
   * itself be the jolt this exists to prevent. A plain event handler, not an effect, so there is no
   * set-state-in-effect suppression to write; capture phase so a child that stops propagation cannot
   * hide the interaction, and React's synthetic events travel through portals, so a dialog rendered
   * into `<body>` still reaches this root.
   */
  /**
   * The tab in force, mirrored into a ref that stays current across renders — read by
   * {@code commitTabInForce} at FIRE TIME rather than closed over at whatever render created the
   * caller. This is load-bearing for the grace timer below: the timer is armed once, at mount,
   * when {@code effectiveTab} is still Plan (the Map pane has not arrived yet — §4.3), and it must
   * NOT commit that stale value if the preference has since resolved to Map. Updated in an effect
   * with no dependency array, so it runs after every commit — mutating a ref directly in the render
   * body is the pattern that trips this file's own "no side effects during render" convention
   * elsewhere (the {@code settingsWasOpen}/{@code openedTabs} bailouts are `setState`, not a mutable
   * write), and the timer fires so much later (1500 ms) that the one-render lag an effect adds here
   * is immaterial.
   */
  const effectiveTabRef = useRef(effectiveTab);
  useEffect(() => { effectiveTabRef.current = effectiveTab; });
  const commitTabInForce = useCallback(() => {
    setActiveTab((prev) => (prev == null ? effectiveTabRef.current : prev));
  }, []);
  /**
   * The time bound on the opening preference (plan §4.3): a reader who reads without touching
   * anything is still reading, and a switch several seconds into that is the same jolt as one mid
   * interaction. A one-shot timer from mount, cleared on unmount, a no-op once `activeTab` is
   * already set — so the late switch to Map happens only when forecasts arrive quickly AND the
   * reader has not touched the page, i.e. when it reads as part of loading.
   *
   * <p>⚠️ Reads `effectiveTabRef`, not a closed-over `effectiveTab` — found in review. The timer is
   * armed once at MOUNT, when `effectiveTab` is necessarily still Plan (the Map pane has not
   * arrived — App.jsx's `hasForecastData` gate). A version that called a `commitTabInForce`
   * closed over that mount-render value would commit PLAN at 1500 ms even after the Map pane
   * arrived at, say, 300 ms and the preference had already resolved to Map — silently yanking the
   * reader back to Plan a second and a half into reading it. Reading the ref at fire time is what
   * makes the timer commit whatever is actually in force *then*, not whatever was in force when it
   * was armed.
   */
  useEffect(() => {
    const timer = setTimeout(() => { commitTabInForce(); }, PREFERRED_TAB_GRACE_MS);
    return () => clearTimeout(timer);
    // `commitTabInForce` deliberately absent: it is now referentially STABLE (`[]` deps, since it
    // reads the ref rather than closing over `effectiveTab`), so listing it changes nothing — but
    // the comment stays because the reason this effect itself still needs `[]` does not follow from
    // that alone. Re-running this effect on every render (had it depended on anything that changes)
    // would re-arm the timer every time, and a reader whose page keeps re-rendering (the ten-minute
    // poll, a lens change) would never reach the deadline. The timer is meant to fire
    // `PREFERRED_TAB_GRACE_MS` after MOUNT, once, full stop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  /**
   * Panes mount on first selection and stay mounted; the panel ELEMENT is always present.
   *
   * <p>Seeded with Plan alone, same as before — a preference for Map is not a selection, and the
   * mount check below (`openedTabs.has(tab.id) || tab.id === effectiveTab`) covers a Map pane shown
   * by preference without needing it seeded here. The effect beneath keeps the set STICKY once a
   * preference does resolve to Map: without it, a pane shown only via that mount-check's second arm
   * would vanish the instant `effectiveTab` moved on (a later `selectTab`, or the reader leaving and
   * returning), which is exactly the sticky-pane rule `WindowFirstShellSticky.test.jsx` pins.
   */
  const [openedTabs, setOpenedTabs] = useState(() => new Set([TABS[0].id]));
  // Adjusted DURING RENDER, not in an effect — the same bailout shape `settingsWasOpen` below
  // uses: the condition is false again the very next render (the set now has `effectiveTab`), so
  // this cannot cascade. An effect here would be one more commit before a Map pane shown only by
  // preference (never through `selectTab`, which already does this) picked up its sticky entry.
  if (!openedTabs.has(effectiveTab)) {
    setOpenedTabs((prev) => new Set(prev).add(effectiveTab));
  }
  /**
   * The tab buttons, so an arrow key can move focus as well as selection.
   *
   * <p>Focus has to be moved imperatively: selection follows focus, so the newly selected tab is
   * the only one with {@code tabIndex={0}} and a keyboard user who pressed Right would otherwise be
   * left with focus on an element that has just become unreachable.
   */
  const tabRefs = useRef([]);
  // `true`, unconditionally — the tab badge (plan D3/D4/D13) needs to know about arrivals whether
  // or not the reader ever opens this pane, so the fetch fires after first paint for every reader
  // rather than gating on the tab. See useComingUpFeed.js's own class doc for the reversal.
  const comingUp = useComingUpFeed(true, todayStr);
  /**
   * The tab badge's state (plan D3/D4/D12) — null (no element at all) in the overwhelmingly common
   * case. Read off the WHOLE served feed, never the chip-filtered subset the pane itself may be
   * showing: the badge answers "is anything new anywhere in the feed", a question the active filter
   * must not narrow.
   */
  const comingUpBadge = useMemo(
    () => deriveBadge(comingUp.events?.entries, comingUp.events?.bands, comingUpLastSeenDate),
    [comingUp.events, comingUpLastSeenDate],
  );
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
   * <p>Fires once, on the FIRST open of the Coming up tab while `comingUpLastSeenDate` is exactly
   * {@code null} (not `undefined`, which means "not answered yet" — see the context's own class
   * doc — and must not be mistaken for "never seen"). That first visit shows no badge and no NEW
   * flags, matching D3's chosen quiet bias: the write happens silently, not as a visible "welcome"
   * moment.
   *
   * <p>The guard ref is reset on failure, not left permanently spent — a dropped request must retry
   * on the NEXT visit rather than never again, but must not retry instantly either. Resetting the
   * ref alone cannot cause a tight loop: this effect only re-runs when one of its dependencies
   * actually changes value, and none of them change while the reader sits on an open tab — only
   * leaving and returning (which moves `effectiveTab` away and back) gives the reset ref another
   * chance, which is exactly "next visit, never loops".
   */
  const bootstrapFiredRef = useRef(false);
  useEffect(() => {
    if (effectiveTab !== 'coming-up') return;
    if (comingUpLastSeenDate !== null) return;
    if (bootstrapFiredRef.current) return;
    bootstrapFiredRef.current = true;
    markComingUpSeen()
      .then((settings) => setComingUpLastSeenAt(settings?.comingUpLastSeenDate ?? todayStr))
      .catch(() => { bootstrapFiredRef.current = false; });
  }, [effectiveTab, comingUpLastSeenDate, todayStr, setComingUpLastSeenAt]);
  /**
   * Selects a tab, and takes any dialog down with it.
   *
   * <p>Every dialog this shell owns — the window popup, the drill-down sheet, the four-day sheet,
   * the pick and search — is rendered outside the pane and its state is independent of the tab, so
   * without this a reader who opened a window and then pressed Coming up would be left with a modal
   * about a Plan window floating over the almanac feed — and {@code useDialogFocus} is explicitly not
   * a focus trap, so closing it would hand focus back to a trigger that is no longer on screen.
   * Arriving somewhere else ends the browsing, which is the same rule the strip already applies to
   * its peek before a map handoff.
   *
   * <p><b>Naming the tab already in force is the no-move form, and it is how every route that only
   * needs the dialogs gone closes them</b> — the map's peek handoff below, the ⚙ cog, the tick line's
   * postcode nudge and the settings edge. One list, so a dialog added later cannot reach one of those
   * routes and miss another.
   *
   * <p>⚠️ <b>Search is on the list since 2026-09-16, and was missing from it before.</b> A keyboard
   * reader could Tab out of the search box — it is not a trap, and only the tick line leaves the tab
   * order under it — onto the tab bar and arrow to another tab with search still open; over the map,
   * the peek then landed `inert` beneath it rather than as the only layer.
   *
   * <p>⚠️ <b>This runs DURING RENDER as well as in handlers and effects</b>: the settings edge below
   * calls it. So the body must stay this component's own setters and nothing else. A focus move, a
   * ref write, a parent callback or a request added here would run inside the shell's render on every
   * opening of settings — and only the ref write is something lint catches.
   */
  const selectTab = (id) => {
    setActiveTab(id);
    // Sticky: a pane that has been opened stays mounted, so its state and its fetches survive a
    // round trip through another tab. `ManageView` in particular reads the hash at mount only.
    setOpenedTabs((prev) => (prev.has(id) ? prev : new Set(prev).add(id)));
    setOpenPick(null);
    setSheetKey(null);
    // The window popup and the four-day sheet go with them. Every dialog this shell owns is about
    // the Plan tab, so arriving anywhere else ends the browsing — the same rule the strip already
    // applies to its peek before a map handoff. Without this a reader who opened a window and then
    // pressed Coming up was left with a dialog about a Plan window floating over the almanac feed.
    setOpenWindowKey(null);
    setFocusedRegion(null);
    setSheetSpot(null);
    setSheetWindowKey(null);
    setSearchSeed(null);
    // Ask's sheet is a layer like the rest: arriving anywhere ends it, and every route that only
    // wants the dialogs gone (the cog, the nudge, the settings edge) takes it down with them. The
    // CONVERSATION is not touched — it is context state, and this body may call only our own setters.
    setAskSheetOpen(false);
  };
  /**
   * The Coming up tab's handoff row, going the other way (plan P1/D14).
   *
   * <p>{@code selectTab} hides the panel the pressed row lives in immediately, so without an
   * imperative focus move afterwards, focus would fall to {@code <body>} — the same fall-to-body
   * failure {@code WindowFirstComingUp}'s own retry-focus effect already argues against.
   * {@code onGoToPlan} accepts a date already, though nothing reads it yet (§11.9): the handoff
   * row has no single date to carry, but P3b's per-entry "plan" action will, and Plan cannot yet
   * focus one, so the signature is settled now rather than grown again in that phase.
   */
  const goToPlan = (date) => {
    void date;
    selectTab('plan');
    tabRefs.current[tabs.findIndex((t) => t.id === 'plan')]?.focus();
  };
  /**
   * Left/Right/Home/End across the bar, wrapping at both ends.
   *
   * <p>Up/Down are deliberately not handled: this is a horizontal tab list, and binding the
   * vertical keys would take them from the page scroll for no gain.
   */
  const handleTabKey = (event, index) => {
    // A modified arrow or Home is somebody else's shortcut, and swallowing it is worse than not
    // handling it: Alt+Left and Cmd+Left are the browser's Back, and Ctrl/Cmd+Home is "top of
    // document". The bar's own bindings are the UNMODIFIED keys only.
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const last = tabs.length - 1;
    let next = null;
    if (event.key === 'ArrowRight') next = index === last ? 0 : index + 1;
    else if (event.key === 'ArrowLeft') next = index === 0 ? last : index - 1;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = last;
    if (next === null) return;
    // Home and End scroll the page by default, and Left/Right scroll a horizontally overflowing
    // one — either would move the view out from under the reader as they change tab.
    event.preventDefault();
    selectTab(tabs[next].id);
    tabRefs.current[next]?.focus();
    // Focus does NOT scroll a tab into view on its own — measured on the running app: `scrollLeft`
    // stayed 0 through `.focus()` and moved only under `scrollIntoView`. Without this, arrowing to
    // an off-screen tab at 320px focuses something the reader cannot see. `block: 'nearest'` is
    // what keeps the page itself still.
    // Optional CALL, not just optional chaining on the node: jsdom implements no layout and so
    // provides no `scrollIntoView`, and the unguarded form threw a TypeError on every arrow press
    // while the suite still reported green — seven unhandled errors and an exit code of 1 under a
    // "3035 passed" summary. Guarding it here rather than stubbing it in `setup.js` keeps the
    // absence honest: there is nothing to scroll in a document with no layout.
    tabRefs.current[next]?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
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
  const [openPick, setOpenPick] = useState(null);
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
   * Opens exactly ONE layer over the window popup, taking down whatever else was there.
   *
   * <h2>Why the three are mutually exclusive rather than a stack</h2>
   *
   * <p>Three dialogs can sit over the popup — the drill-down sheet ("See all N →"), the four-day
   * location sheet (M4's chips and spot cards) and the pick dialog — and all three carry the same
   * {@code escapeEnabled={searchSeed == null}}, because each was written as <em>the</em> stacked
   * layer. Any two of them open together therefore answer one Escape press twice, which is a direct
   * breach of the one-layer-per-press rule the popup beneath them relies on (plan-matrix §6 M2.5).
   *
   * <p>⚠️ <b>Reachable, and made reachable by M4.</b> {@code useDialogFocus} is deliberately not a
   * focus trap, so from an open location sheet a keyboard reader can Tab back onto the popup's own
   * pick badge behind the backdrop and press Enter. Before M4 the location sheet could not coexist
   * with the popup at all, so the collision had no route.
   *
   * <p>Making them exclusive rather than ordering them is the smaller change and the better one: an
   * ordering would need a fourth {@code aria-modal} layer's worth of guards, which is exactly what
   * this phase was told not to add. One layer over the popup, one press to take it off.
   */
  const openOverPopup = useCallback((next) => {
    setSheetSpot(next?.spot ?? null);
    // Every caller here is a PLAN surface, which carries no map window — and clearing rather than
    // leaving it is what stops one route's focused window riding onto another's sheet.
    setSheetWindowKey(null);
    setSheetKey(next?.sheetKey ?? null);
    setOpenPick(next?.pick ?? null);
  }, []);
  /**
   * Takes every dialog this shell owns down when `App`'s settings dialog opens — by whichever route.
   *
   * <p>⚠️ {@code UserSettingsModal} is a SIBLING of this shell in `App`: it is not a `Modal` rendered
   * here, {@code stackedOverPopup} cannot see it, and it takes no {@code stacked} opt-in. So it cannot
   * be ordered against a dialog of ours — it can only arrive with none of ours up, or two elements
   * claim {@code aria-modal="true"} and the lower one's Escape listener, still armed, closes the
   * dialog the reader cannot see. The cog and the tick line's nudge are this shell's own controls and
   * close first themselves. The Map tab's ⌂ is not: with no postcode saved it opens settings through
   * the map pane's own {@code onOpenSettings}, which `App` hands the pane directly — and it is
   * reachable while a dialog of ours is up, because the four-day sheet opens OVER the map (O-18) and
   * the map under it is a whole interactive pane a keyboard reader can Tab onto (O-20 arm A). So
   * `App` says when its dialog is open, and the rising edge takes ours down — through
   * {@code selectTab} naming the tab in force, so no tab moves: settings is not a destination, and
   * from the peek the reader must be left on the map they were back-tracking to.
   *
   * <p>During render rather than in an effect, which is what keeps it in the SAME commit as the
   * dialog it yields to — React re-renders this component before committing when it sets its own
   * state during render, so no commit holds both. An effect's close would land in a second commit:
   * the DOM would hold both claiming the modal in between, and the settings dialog would already
   * have recorded its return address, so this route would send focus back somewhere different from
   * the cog's and the nudge's, whose closes share a commit with the open. (Placed below every state
   * {@code selectTab} writes, which calling it during render requires.)
   *
   * <p>Keyed on the edge, not the level. A dialog of ours opened WHILE settings stands — by Tabbing
   * out of it onto the page — is the reverse route, of the same family as the Tab-out residual
   * plan-matrix §11c records, and is not answered here: closing on the level would make every
   * control behind the settings backdrop a dead one instead.
   */
  const [settingsWasOpen, setSettingsWasOpen] = useState(settingsOpen);
  if (settingsOpen !== settingsWasOpen) {
    setSettingsWasOpen(settingsOpen);
    if (settingsOpen) selectTab(effectiveTab);
  }
  /**
   * The shared close-then-move-and-merge entry every map door calls (doors D2,
   * `plan-to-map-doors-plan.md` §3 D2 task 1; D3 the sheet footer, D4 the popup field) — a thin
   * wrapper over {@link openMapDoor} (`utils/mapDoors.js`), supplying this shell's own closures.
   * The logic itself — close-then-move, and the live lens-value merge — lives in that PURE
   * function precisely so it is directly unit-tested (`test/mapDoors.test.js`) without a rendered
   * caller. D3 is its first caller (the location sheet's own `onShowOnMap` prop, below); D4 (the
   * popup field's new button) is the second, in its own later phase.
   *
   * <p>⚠️ Close-then-move matches the sheet's own `onShowOnMap` wiring below — but NOT
   * `WindowPickDialog`'s `onShowRegion`/`onShowLocation`, which call `onShowOnMap` FIRST and close
   * SECOND (an earlier draft of this comment claimed all three routes agreed; they do not — those
   * two routes are untouched by this plan and their own ordering is unaffected either way, since
   * React batches the state updates regardless of source order, but the claim itself was wrong and
   * is corrected here rather than left to mislead the next reader).
   */
  const openMapTab = useCallback((door) => openMapDoor({
    openOverPopup,
    openWindow,
    onOpenMapTab,
    ratingLens,
    reachLens,
    // ⚠️ `inPlace` — the one thing this wrapper adds that {@link openMapDoor} could not know. A
    // "door onto the map" pressed from a sheet that is ALREADY over the map is not a door: `App`'s
    // in-place branch explains what a full door payload would silently undo there (the map's own
    // rating floor, its reach tier, its scope and its camera). It is answered here rather than in
    // the pure function because only the shell knows which tab is in force.
    door: { ...door, inPlace: effectiveTab === 'map' },
  }), [openOverPopup, openWindow, onOpenMapTab, ratingLens, reachLens, effectiveTab]);
  /**
   * A tab asked for from OUTSIDE the bar — currently the map overlay's "open the full map" hatch.
   *
   * <p>Keyed on a NONCE rather than on the id, because the same destination can be asked for twice
   * running and the second ask must still land. That is the idiom {@code App} already uses for map
   * handoffs, for the same reason.
   *
   * <p>Goes through {@code selectTab} rather than {@code setActiveTab} so an arriving request gets
   * everything a click gets — the pane marked as opened, and any open dialog taken down. Selecting
   * a tab without mounting its pane would show an empty panel.
   *
   * <p><b>It sits here, below the dialog state, and not beside {@code selectTab} where it reads
   * more naturally.</b> {@code selectTab} clears {@code openPick} and {@code sheetKey}, which are
   * declared further down; calling it from an effect placed above them is a use-before-declaration
   * the linter catches. Runtime would have been fine — an effect runs after render — which is
   * exactly why this is worth a sentence rather than a silent move.
   */
  const requestedNonce = tabRequest?.nonce ?? null;
  const requestedId = tabRequest?.id ?? null;
  // Seeded with whatever nonce is already in flight at MOUNT, not with null. `App` holds
  // `tabRequest` and never clears it, and it outlives this component — so a null seed on any
  // remount would replay the last request rather than treating it as already handled, which
  // contradicts this file's own rule that tab selection is not persisted.
  const lastHandledRequest = useRef(tabRequest?.nonce ?? null);
  useEffect(() => {
    if (requestedNonce == null || requestedNonce === lastHandledRequest.current) return;
    lastHandledRequest.current = requestedNonce;
    // Ignored rather than obeyed: a request naming a tab this shell was handed no pane for would
    // select an id `effectiveTab` then has to fall back from, i.e. a silent jump to Plan.
    if (requestedId === 'map' && mapPane == null) return;
    if (requestedId === 'operations' && operationsPane == null) return;
    if (!TABS.some((t) => t.id === requestedId)) return;
    // Responding to a request that arrives from OUTSIDE this component is the effect's whole
    // purpose — the selected tab is not derivable from props — and the nonce guard means this runs
    // once per ask rather than on every render, so there is no cascade to trigger.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    selectTab(requestedId);
    // Focus follows the request, and only a request. A CLICK leaves focus on the tab the pointer or
    // the keyboard already put it on, so the bar needs nothing; an external ask arrives with focus
    // wherever the CALLER left it — and the caller here is a dialog that closes on the same press.
    // Measured on the running app: after the overlay's "open the full map" hatch, `activeElement`
    // was the document root, i.e. a keyboard reader was dropped at the top of the page having just
    // asked to be taken somewhere specific. Deferred a frame because the tab it names may be
    // rendering for the first time on this very commit.
    // Reached by id rather than through `tabRefs`, which is index-based: the index depends on which
    // panes were handed over, so it is the one thing that moves when a tab appears or disappears —
    // exactly the case this effect exists for. The id is the component's own and is already what
    // `aria-controls` resolves against.
    const domId = tabDomId(requestedId);
    requestAnimationFrame(() => document.getElementById(domId)?.focus());
    // `selectTab` is deliberately absent from the list. It is rebuilt every render, so listing it
    // would re-run this on every render with the nonce guard as the only thing stopping it. The
    // nonce IS the trigger, and it is in the list.
  }, [requestedNonce, requestedId, mapPane, operationsPane]);

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
   * <p>⚠️ <b>The peek route goes through {@code selectTab} too, naming the tab already in force.</b>
   * That is not a trick to read twice: {@code selectTab} is idempotent in everything except the
   * dialog clearing ({@code setActiveTab} to the current id changes nothing, and {@code setOpenedTabs}
   * returns {@code prev} for an id it already holds), and that clearing is the only thing making
   * this sheet the only layer. Routing both ways through it keeps exactly ONE definition of "take
   * every dialog this shell owns down", so a seventh dialog added later cannot reach one route and
   * miss the other. (A {@code closeOwnDialogs()} helper called by {@code selectTab} and by this
   * effect says the same thing and was tried first; it was dropped because splitting the body that
   * way makes {@code react-hooks/exhaustive-deps} begin demanding {@code selectTab} in two
   * unrelated effects' dependency lists — a real cost for no behavioural gain.)
   *
   * <p>⚠️ <b>The clearing and the {@code setSheetSpot} must stay in ONE synchronous body.</b>
   * {@code selectTab} clears {@code sheetSpot} (along with
   * `openWindowKey`/`openPick`/`sheetKey`/`focusedRegion`), so the {@code setSheetSpot} call below
   * survives only because it is in the SAME React batch: two calls to one setter inside one commit
   * resolve to the LAST one, never the {@code null} made a line earlier. That is also why the tab
   * move rides {@code inPlan} on this payload rather than a second {@code tabRequest} from `App` —
   * a second channel would put the two writes in two effects and make the outcome depend on their
   * declaration order.
   *
   * <p>Guarded by its own nonce and its own ref, mirroring the `tabRequest` effect immediately
   * above — the identical protection against acting on a STALE handoff twice, which here is the
   * §3 P9 test brief's own "hidden-pane" concern turned around: this shell's OWN Plan body is
   * `hidden` rather than unmounted while another tab is active (the same sticky-pane idiom the Map
   * pane itself relies on), so without a nonce guard a plain prop-identity check could refire on an
   * unrelated re-render while the Plan tab sits hidden behind whatever tab the reader is actually on.
   */
  const sheetHandoffNonce = locationSheetHandoff?.nonce ?? null;
  const lastHandledSheetHandoff = useRef(locationSheetHandoff?.nonce ?? null);
  useEffect(() => {
    if (sheetHandoffNonce == null || sheetHandoffNonce === lastHandledSheetHandoff.current) return;
    lastHandledSheetHandoff.current = sheetHandoffNonce;
    const toPlanTab = locationSheetHandoff.inPlan === true;
    selectTab(toPlanTab ? 'plan' : effectiveTab);
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
    // ⚠️ ONLY on the route that moves the tab. Same focus rule as the `tabRequest` effect above:
    // an external ask arrives with focus wherever the caller (a callout button that is about to be
    // hidden along with its whole panel) left it. On the peek route that panel is NOT hidden — the
    // button is still on screen behind the sheet — so moving focus to a tab the reader did not
    // press would both send them somewhere they did not ask to go and overwrite the very element
    // `useDialogFocus` is about to capture as the sheet's return address.
    if (toPlanTab) {
      const domId = tabDomId('plan');
      requestAnimationFrame(() => document.getElementById(domId)?.focus());
    }
    // `selectTab`/`setSheetSpot` deliberately absent from the dependency list — both are rebuilt
    // every render, and the nonce is the trigger already in it. `effectiveTab` IS in the list (the
    // linter asks for it and it is free): a tab change re-runs this, and the nonce guard on the
    // first line returns immediately, exactly as `mapPane`/`operationsPane` do in the `tabRequest`
    // effect above.
  }, [sheetHandoffNonce, locationSheetHandoff, effectiveTab]);

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
   * <p>{@code App} already holds {@code locations} — P9 drilled it here for the regional door — so
   * this costs no request. The join itself lives in {@code locationTypes.js}
   * because the regional planner builds the same one from the same prop, and two copies of a join
   * is how the five copies that module replaced started.
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
  const openCard = openWindowKey == null
    ? null
    : windowCards.find((card) => card.key === openWindowKey) || null;
  /** Where the open window sits among the openable ones, for the popup's `‹ n/6 ›` nav. */
  const openIndex = openCard ? windowCards.indexOf(openCard) : -1;
  /**
   * The popup's dawn-race source — each location's lunar eclipse sight per window (L4,
   * `docs/engineering/lunar-eclipse-plan.md` §2.7). Gated on the popup being open for the same
   * reason `slotIndex`/`sheetTideAlignmentIndex` below are: it walks every slot of every event
   * summary of every day, and nothing reads it while the popup is closed.
   */
  const eclipseIndex = useMemo(
    () => (openCard ? buildEclipseIndex(briefing?.days) : null),
    [openCard, briefing?.days],
  );
  /**
   * Each rendered window's event summary, keyed the way the pane addresses a window.
   *
   * <p>The open row's rail and band need the SERVED region records — {@code meanRating},
   * {@code bestRating}, {@code displayVerdict}, {@code summary} — and {@code buildWindowCards}
   * deliberately does not copy them onto its descriptor: they are a second population with its own
   * canopy rule (P1), and flattening them onto a card is how two levels of "best" end up as one
   * number. So the summaries are looked up here, by key, and read only by the surface that names
   * regions.
   */
  const eventSummariesByKey = useMemo(() => {
    const byKey = new Map();
    for (const day of briefing?.days || []) {
      for (const es of day?.eventSummaries || []) {
        if (!day.date || !es?.targetType) continue;
        byKey.set(`${day.date}:${es.targetType}`, es);
      }
    }
    return byKey;
  }, [briefing?.days]);

  /** The one key the matrix marks as open — a Set of at most one, which is the shape it takes. */
  const openWindowKeys = useMemo(
    () => new Set(openWindowKey == null ? [] : [openWindowKey]),
    [openWindowKey],
  );

  /**
   * The served topics, indexed once for the whole page.
   *
   * <p>The matrix builds its own for its cards; this one is the popup's, and both call
   * {@code buildTopicIndex} on the same field. That is the plan's A8 rule in force — one join, one
   * scope filter, in {@code windowFirstTopics.js} — and it is why the dialog takes an index rather
   * than a list of pre-joined rows: the rows are per window, and only the dialog knows which window
   * it is about.
   */
  const topicIndex = useMemo(() => buildTopicIndex(briefing?.hotTopics), [briefing?.hotTopics]);

  /**
   * The two lens axes in the shape the region layer words itself from — its own memo, so a region
   * selection cannot churn it and the card's row derivation stays stable across a click.
   */
  const fieldLens = useMemo(() => ({
    limitMinutes: reachLens?.tier?.limitMinutes ?? null,
    tierLabel: reachLens?.tier?.label ?? null,
    minRating: ratingLens?.minRating ?? null,
    ratingLabel: ratingLens?.floor?.label ?? null,
    // Optional all the way down, unlike the conditional reads further below. This memo runs on every
    // render rather than inside a branch, so it is the first thing in the shell to touch either lens
    // unconditionally — and a provider-less or partial context (which several shell suites hand over
    // deliberately, to keep their files about one seam) would otherwise throw here before rendering
    // anything at all.
  }), [reachLens?.tier, ratingLens?.minRating, ratingLens?.floor]);

  /**
   * The popup's inputs — built for the OPEN window only, and null while none is.
   *
   * <p>It was a map of six, one per accordion row, and the memo's own comment recorded the defect
   * that shape produced: an inline object literal repainted every open row's canvas on every shell
   * render, through a five-link chain ending in {@link useHeatCanvas}'s paint effect. With one
   * dialog there is at most one field on screen, so the map is a single object — which is both
   * cheaper and structurally immune to the same defect. The identity rule still holds and still
   * matters: every value inside stays referentially stable, so a region pick rebuilds this object
   * and the map's {@code paint} (which depends on {@code points}, {@code fitTo} and the focus, not
   * on the container) repaints for the focus alone.
   *
   * <p>⚠️ <b>Built whenever a window is open, even with no catalogue.</b> It used to be withheld
   * wholesale when {@code heatSpots} was empty — a scores fetch that failed, a session with no
   * roster, or simply the window before {@code /api/locations} resolves — and the dialog was gated
   * on it, so every matrix cell and every one of search's window rows set state and painted nothing
   * at all. A control with no visible effect is exactly what plan §3 rule
   * 14 bans, and the old card list rendered its non-field content without a catalogue. The
   * withholding belongs to the FIELD MAP alone, and the dialog does it: everything else in the
   * popup — the verdict, the prose, the topics, the tide, the ranked list — is briefing data and is
   * there either way.
   */
  const openField = useMemo(() => {
    if (!openCard) return null;
    return {
      eventSummary: eventSummariesByKey.get(openCard.key) ?? null,
      spots: heatSpots,
      points: heatPointSets.get(openCard.key) || EMPTY_POINTS,
      // The matrix's own descriptors, so the popup's null-prose line and the six cells behind it
      // name one set of windows in one order.
      windows: heatStripCards,
      series: regionSeries,
      reachById,
      lens: fieldLens,
      onSelectRegion: setFocusedRegion,
      // ⚠️ FORCED NULL under an away origin, and that is a defect fix rather than tidiness. The
      // focus is not cleared when the origin moves, and away the rail that would clear it is
      // withheld. Left live it filters an already-scoped strip to a region the reader has scoped
      // out and prints "Nothing in X for this window" under a chip naming somewhere else.
      selectedRegion: origin ? null : focusedRegion,
      // The origin has already answered "which region" for the whole page, so the popup's own rail
      // has nothing left to choose (plan §4.8).
      singleRegionScope: Boolean(origin),
      origin: origin ?? null,
      // Read by `WindowSheetDialog`, which converts it to `[lng, lat]` and hands it to
      // `WindowRowFieldMap` as `homePoint` — the popup field's reach rings and home marker
      // (field-geography plan §3). Plumbed alongside G2 (plan §2.1) so the two phases share one
      // prop path from `App` rather than two.
      homeCoords,
    };
  }, [openCard, heatSpots, heatPointSets, heatStripCards, regionSeries, reachById,
    eventSummariesByKey, fieldLens, focusedRegion, origin, homeCoords]);

  // Lifted to App for the map overlay. Without this a tile handed to the map opens an overlay with
  // no narrative over a map that has filtered out every unrated pin — see the provider's note on
  // why this arm fetches them at all.
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
   * Whether anything is stacked OVER the window popup — the whole of the Escape order.
   *
   * <p>{@code Modal} installs one document-level Escape listener per instance, so two open dialogs
   * both close on a single press. The remedy is not a shared stack but a guard per layer: whichever
   * layer is not on top declines the key, so Escape takes exactly one layer per press — the layer
   * over the popup, then the popup itself (plan-matrix §6 M2.5).
   *
   * <p><b>SEARCH's rung went live at M3, and it is the reason this whole ordering exists.</b> It
   * was dormant through M2 — {@code /} was refused while <em>any</em> dialog was open, so search
   * could never sit over anything and the three {@code escapeEnabled} props below guarded a case
   * that could not arise. M3 anchors search to the masthead, which the popup is drawn over rather
   * than in, so search is permitted while the window popup is open and refused over everything else.
   * ⚠️ Since F2 the way in is the masthead's ⌕ and the origin button ONLY — {@code /} belongs to Ask
   * (plan §6 Q1) and is refused over the popup. The rung is unchanged: the same controls open the
   * same search over the same popup, and the same guards refuse it over a layer already stacked.
   *
   * <p>⚠️ <b>The stack is TWO deep, never the bundle README's three.</b> The rung over the popup is
   * search <em>or</em> a stacked sheet, and since M5 never both — every route into search is
   * refused while this flag stands (plan-matrix §4 A22). So read the order as "the layer above,
   * then the popup", not as a three-rung sequence to walk down: the two upper rungs are
   * alternatives, and each {@code escapeEnabled} prop below is written for whichever one is up.
   */
  const stackedOverPopup = sheetCard != null || sheetSpot != null || openPick != null;
  /**
   * The same question with search folded in — live since M3; see the note above.
   *
   * <p>It is what suppresses the spot peek, and every dialog is an operand deliberately rather
   * than only the sheets. They are all {@code Modal}s, so all render inside Tailwind's
   * {@code z-50} while {@code .wf-peek} is portalled to the body at {@code z-index: 60}; and
   * {@code useDialogFocus} is explicitly not a focus trap, so from any of them a keyboard user can
   * Tab back onto a spot card behind the backdrop and paint a hover panel over the dialog. (This
   * replaced a broader `modalOpen` flag that also counted the popup itself. The popup must NOT
   * suppress the peek — it is the surface the peek is opened from — and once the `/` guard moved
   * onto `stackedOverPopup` at M3 that flag had no other reader.)
   */
  const modalOpenOverPopup = stackedOverPopup || searchSeed != null;

  /**
   * Ask PhotoCast's entry and sheet (plan §2.6, F1b).
   *
   * <h2>Where each entry shows</h2>
   * <p>Below 640px the 48px bar, on Plan and Coming up ONLY — the phone Map gets its entry in the peek
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
   * shell; and {@code selectTab} closes the sheet on any tab change, from any route.
   */
  const askEntry = (() => {
    if (ask.availability !== 'on' && ask.availability !== 'down') return null;
    const onSkyTab = effectiveTab === 'plan' || effectiveTab === 'coming-up';
    if (askSurface === 'phone') return onSkyTab ? 'bar' : null;
    if (askSurface === 'tablet') return onSkyTab || effectiveTab === 'map' ? 'field' : null;
    return null;
  })();
  // The docked surface's entry: the same tabs as the tablet's field, from 1024px. "Is there a dock to
  // open" — `askDockOpen` is "is it open".
  const askDocked = askSurface === 'desktop' || askSurface === 'wide';
  const askDockEntry = (ask.availability === 'on' || ask.availability === 'down')
    && askDocked && effectiveTab !== 'operations';
  // ⚠️ `askSheetOpen` would otherwise be held while NOTHING draws the sheet — the window crossing 1024px, a
  // phone Map, Ask switched off — and bring the sheet back by itself the next time an entry exists (an
  // iPad turned landscape and back reopened it, unasked, focus and all). So it is let go the render
  // the entry goes: during render and with this component's own setter, the shape the settings edge
  // above uses, so no commit holds both states. The conversation is untouched.
  if (askSheetOpen && askEntry === null) setAskSheetOpen(false);
  const askSheetShown = askSheetOpen && askEntry !== null;
  // The dock's twin: Operations, a window narrowed below 1024px and Ask switched off all take away what
  // the dock hangs from, and a state left true would bring it back unasked the next time one exists.
  // A tab switch between Plan, Coming up and Map is NOT one of them — see `askDockOpen`.
  if (askDockOpen && !askDockEntry) setAskDockOpen(false);
  const askDockShown = askDockOpen && askDockEntry;
  // `openCard`, not `openWindowKey`: the popup draws only for a live card, and a key whose card has
  // gone (its event passed) is deliberately never released — it would leave the entry disabled with
  // no dialog on screen and nothing to say why.
  const askDialogOpen = openCard != null || modalOpenOverPopup || settingsOpen;
  const askDisabled = ask.availability === 'down' || Boolean(contentDisabled) || askDialogOpen;
  const askViewSpec = ASK_VIEW[effectiveTab] ?? ASK_VIEW.plan;
  // The tablet's Operations tab draws no field, but the tab list is `flex: 1` with Operations pinned
  // to its right edge (`.wf-tab-gated`): without something holding the field's width, pressing
  // Operations would widen the list and slide the button out from under the pointer.
  // Held from 1024px too (F2), where Operations is the same admin-only tab pinned to the same edge.
  const askGhost = (askSurface === 'tablet' || askDocked) && effectiveTab === 'operations'
    && (ask.availability === 'on' || ask.availability === 'down');
  const askFieldWidth = ASK_FIELD_WIDTH[askSurface] ?? 260;
  /**
   * Opens the sheet. The entry is {@code disabled} (a real attribute, so no press reaches this) while
   * one of THIS shell's dialogs or settings stands; what that flag cannot see is a dialog this shell
   * does not own — the map overlay — so it is found the way {@code /} finds it (any
   * {@code role="dialog"} outside this root), at press time, and refused with nothing taken down.
   */
  const openAskSheet = () => {
    if (foreignDialogOpen(shellRef.current)) return;
    setAskSheetOpen(true);
  };
  /**
   * The ✕, the scrim and Escape: CLOSE, and keep the conversation (plan §2.6, §2.8 — the answer
   * survives a close, and a Plan-card highlight is "applied when the sheet closes"). Ending it is the
   * sheet's own "Clear answer" control. A scrim is a full-viewport target and Escape is global; either
   * discarding an answer the reader was charged a question for would be a trap.
   */
  const closeAskSheet = () => setAskSheetOpen(false);
  /**
   * "Show on map ›" on a pick: select that pick, leave the sheet, land on the Map tab, keep the
   * answer. {@code selectTab} is the route (it closes the sheet with the rest of the layers); the
   * numbered markers are F3's, so until then the Map shows nothing extra, and F3 reads the selected
   * pick this leaves. Focus goes to the Map tab button a frame later, the same idiom the
   * {@code tabRequest} effect uses, because the bar that opened the sheet is unmounted by this very
   * press and focus would otherwise fall to {@code <body>}.
   *
   * <p>From the DOCK the same route is right for the same reason and costs nothing extra: the dock is
   * NOT on {@code selectTab}'s list, so it stays open beside the map with the conversation in it, and
   * the pressed control — which is not offered on the Map — goes with the tab, so focus is moved
   * rather than left to fall.
   */
  const askShowOnMap = (card) => {
    ask.selectPick(card.rank);
    if (effectiveTab === 'map') {
      // The tablet's Ask sheet over the Map (F3): the map is already the tab, but a modal covers it.
      // Closing the sheet is the whole of "show": the map's camera was holding for exactly this
      // (`AskCameraController` waits while a modal stands over the pane) and fits the moment it goes.
      // Focus returns through the sheet's own restore, so nothing is moved here.
      closeAskSheet();
      return;
    }
    selectTab('map');
    requestAnimationFrame(() => document.getElementById(tabDomId('map'))?.focus());
  };
  // The controls on a pick card's own row, AFTER the "Plan this ›" every card carries (that one is a move
  // of the conversation and `AskConversation` draws it on every surface, the phone's peek included). A
  // function whether or not it has anything to draw. "Show on map ›" is offered only where it goes
  // somewhere: a Map pane exists, and the reader is not already on it.
  // ...and, since F3, on the tablet's Ask SHEET over the Map: the picks are numbered on a map the sheet
  // covers, and this is the press that uncovers it (the dock, which covers nothing, is not offered it).
  const askCanShowOnMap = mapPane != null && (effectiveTab !== 'map' || askSheetShown);
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
   * The tab in force, read from the DOM — the one lookup behind both focus fallbacks below (a ref to the
   * tab only catches up in a passive effect, and the close that needs it runs in its own cleanup).
   */
  const selectedTabNode = useCallback(
    () => shellRef.current?.querySelector('[role="tab"][aria-selected="true"]') ?? null,
    [],
  );
  /**
   * Where focus goes on close when the trigger cannot take it back (a tap never focused it, it was
   * unmounted by the press, or it is disabled): the trigger if it is still there, else the tab in
   * force — read from the DOM, since a ref to the tab only catches up in a passive effect and this
   * runs in the close's own cleanup, before it.
   */
  const askRestoreFallback = useCallback(() => {
    const trigger = askTriggerRef.current;
    if (trigger?.isConnected && !trigger.disabled) return trigger;
    return selectedTabNode();
  }, [selectedTabNode]);
  /**
   * The control in the DOCK that "Open in Plan ›" was pressed on, so closing the location sheet it opened
   * can put focus back there (null on every other host, where the pressed control went with its surface).
   */
  const askOpenerRef = useRef(null);
  /**
   * Whether the location sheet now up was opened from Ask's SHEET. Pressed there, the Ask sheet's own
   * close runs in the same commit as the location sheet's mount, with the Ask trigger already
   * {@code disabled} (a dialog is open) and a modal standing — so its restore declines, nothing holds
   * focus, and the location sheet captures {@code <body>} as its opener: closing it would leave the
   * reader nowhere. This is what makes the close land on the trigger (or the tab) instead, read at
   * CLOSE time by the sheet's {@code restoreFocusFallback} and put away when it is gone.
   */
  const askSheetReturnRef = useRef(false);
  const askSheetFallback = useCallback(
    () => (askSheetReturnRef.current ? askRestoreFallback() : null),
    [askRestoreFallback],
  );
  /**
   * "Open in Plan ›" on a pick's "Plan this" view (F5, plan §2.8): the Plan tab, the pick's own location
   * sheet, opened AT the pick's window.
   *
   * <p><b>The two-deep rule, route by route.</b> {@code selectTab('plan')} is the one list that takes
   * every dialog this shell owns down, and it is where the ASK SHEET goes (it sets {@code askSheetOpen} false),
   * so the location sheet is the single modal on the tablet and the phone. The DOCK is not a layer and is
   * not on that list: it stays open beside the plan the reader asked to see, and turns {@code inert}
   * for as long as the sheet is up ({@code askDialogOpen} reads {@code sheetSpot} through
   * {@code stackedOverPopup}). A dialog this shell does not own (the map overlay) refuses the press, as
   * {@code openAskSheet} does — a second {@code aria-modal} over it is the thing the rule is for.
   *
   * <p>The sheet is the Plan tab's own, with the window the handoff effect above gives the map's callout:
   * {@code sheetWindowKey} is {@code date:targetType}, so it opens on the pick's window rather than on its
   * own best. {@code selectTab} clears {@code sheetSpot}, and the setter after it wins only because both
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
    selectTab('plan');
    setSheetSpot({
      id: card.locationId ?? null,
      name: card.name,
      regionName: card.regionName ?? null,
    });
    setSheetWindowKey(card.windowKey);
  };
  useEffect(() => {
    if (sheetSpot != null) return;
    askSheetReturnRef.current = false;
    const opener = askOpenerRef.current;
    if (!opener) return;
    askOpenerRef.current = null;
    const focused = document.activeElement;
    const nowhere = !focused || focused === document.body || focused === document.documentElement;
    // Only a reader who was left where the sheet's own restore parks them — the PLAN tab, which the press
    // moved to and the dock's rescue chose — or on nowhere is sent back. Another tab is a choice (arrowing
    // along the tab bar closes the sheet too), and so is anywhere else they have Tabbed: the same rule
    // `useDialogFocus`'s own restore keeps.
    if ((nowhere || focused === document.getElementById(tabDomId('plan')))
      && opener.isConnected && !opener.closest('[inert]')) {
      opener.focus({ preventScroll: true });
    }
  }, [sheetSpot]);
  /**
   * The postcode nudge inside "Plan this": the tick line's own route (close what is open, then
   * settings on the postcode field), with the Ask return address as its restore target.
   */
  const askSetPostcode = () => {
    selectTab(effectiveTab);
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
    if (!askAvailable || effectiveTab !== 'plan' || askSheetShown || askActiveRank == null) return NO_HIGHLIGHT;
    const card = ask.pickCards.find((c) => c.rank === askActiveRank);
    return card ? new Map([[card.windowKey, card.rank]]) : NO_HIGHLIGHT;
  }, [askAvailable, effectiveTab, askSheetShown, askActiveRank, ask.pickCards]);
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
    if (foreignDialogOpen(shellRef.current)) return;
    if (askDockShown) askDockInputRef.current?.focus({ preventScroll: true });
    else setAskDockOpen(true);
  }, [askDockShown]);
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
   * above the shell, the footer, the masthead, the tabs and every pane all are.
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
   * slot of every event summary of every day — the roster times six windows — and
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
   * the SAME join the popup's {@code eclipseIndex} above builds for `DawnRace`, over the SAME
   * `briefing.days`, so a location's own moon geometry never disagrees between the popup that
   * opened this sheet and the sheet itself. Null-until-open, like {@code sheetTideAlignmentIndex}
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
  const detailScoreIndex = useMemo(
    // ⚠️ TWO readers since M3, and the gate widened with them: search's location rows print the
    // place's own best window from the same id-first index, so the box and the sheet it opens can
    // never disagree about what a place is rated. Still gated — a reader who opens neither pays
    // nothing, which is the whole point of building it here rather than in the provider.
    () => ((sheetSpot || searchSeed != null) ? buildScoreIndex(scoreRows) : null),
    [sheetSpot, searchSeed, scoreRows],
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
   * <p>No longer gated on a sheet being open, because it has a second reader: the popup's topic
   * scope filter (A8 rule 2) is the same question about the same scope, and the matrix already
   * makes this exact call on every render for the cards' own filter. One memo over three stable
   * inputs is cheaper than two calls and — the reason that matters — makes it impossible for the
   * cards and the popup they open to be filtered against two different scopes.
   */
  const planScopeNames = useMemo(
    () => scopeRegions(heatSpots, reachById, origin),
    [heatSpots, reachById, origin],
  );
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
  // The POSITIONAL form, which centres the map on one location — the same call the pick dialog's
  // "show location" already makes. The OBJECT form (`{region, date, eventType}`) opened a whole
  // region and was the retired rail's region chip; nothing on this pane names a region until P3's
  // rail lands, so it has no caller here.
  const handleSpot = (card, spot) => (
    onShowOnMap?.(card.date, card.targetType, spot.locationName)
  );
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
   * any of its own dialogs, the WINDOW POPUP included, search, settings, a dialog this shell does not
   * own, or a dead backend. {@code askDisabled} is every one of the shell's own, and is the same flag
   * that disables the field, so the key can never be live where the control beside it is not.
   *
   * <p>⚠️ <b>The window popup refuses it, where it used to be the one dialog search was allowed over.</b>
   * Search is anchored to the masthead, a surface the popup is drawn over, so the two stacked; the dock
   * is a column beside the page that the popup's scrim covers. Opening it under an {@code aria-modal}
   * dialog would put a live control where the popup says nothing is, and the popup stays.
   */
  useEffect(() => {
    if (!askDockEntry) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return;
      if (askDisabled) return;
      // ⚠️ A dialog this shell does not own still refuses, which is a DIFFERENT question from the flag
      // above: `UserSettingsModal` and the map overlay are siblings of the shell in `App`, invisible to
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
  }, [askDockEntry, askDisabled, openAskDock]);

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
   * <p><b>No focus move, and the difference from the card's ladder is real.</b> The per-card button
   * destroyed itself: it sat in the empty state it was replacing, so a keyboard reader was dropped
   * at {@code <body>} having just asked to be shown something. This message sits above the matrix
   * and every action here also unmounts it — so the same problem, one level up, and the same
   * remedy: focus goes to the first card of the sunrise row, which is what the reader has just been
   * shown (matrix-axis plan D20 — the row-major DOM the rails restructure introduced means the
   * first {@code button[data-testid="wf-heat-card"]} in document order is the first day's sunrise
   * card rather than the first day's own first window, but it is still the visually top-left card).
   */
  const applyConflictAction = (action) => {
    if (action?.kind === 'reach') reachLens?.selectTier(action.id);
    else if (action?.kind === 'rating') ratingLens?.selectFloor(action.id);
    // The one action that is not a lens: an away scope the reader chose. It refills the plan the
    // same way the other two do — the home pool is a superset of any one region's.
    else if (action?.kind === 'origin') setOrigin?.(null);
    else return;
    // ⚠️ `button[…]`, not `[…]`. An AWAY window keeps its matrix cell and is a `<div>` with no
    // tabindex (plan §3 rule 14 — a control with no visible effect is banned), and `querySelector`
    // returns DOM order — so on a plan whose first rendered day is a travel day, focusing the bare
    // selector is a no-op and the reader is dropped at `<body>`: the exact defect this move exists
    // to prevent. Deferred a frame because the matrix is re-rendering on this very commit, and
    // optional-CALLED because jsdom implements no layout.
    requestAnimationFrame(() => {
      document.querySelector('button[data-testid="wf-heat-card"]')?.focus?.();
    });
  };

  /**
   * The one message the whole plan may carry, and null when it carries none.
   *
   * <p>Above the matrix, because it is about the plan rather than one window (plan-matrix §6 M2.6).
   * Its per-window counterpart is the popup's quiet sentence, and the two land together: neither
   * alone covers what the deleted per-card ladder covered.
   */
  const conflict = useMemo(() => buildPlanConflict({
    cards: windowCards,
    origin: origin ?? null,
    homePlace: homePlace || null,
    tierId: reachLens?.tierId,
    limitMinutes: reachLens?.tier?.limitMinutes ?? null,
    floorId: ratingLens?.floorId,
    minRating: ratingLens?.minRating ?? null,
  }), [windowCards, origin, homePlace, reachLens?.tierId, reachLens?.tier,
    ratingLens?.floorId, ratingLens?.minRating]);

  /**
   * The safety warning a topic on this plan carries, and the window it is about.
   *
   * <p>⚠️ <b>Page-level because the surface that used to guarantee it is gone.</b>
   * {@code BriefingWindow.Badge.safetyNote} carries the "do not look at the sun without a filter"
   * class of warning, and the window card's own comment named that card as "the ONE surface
   * guaranteed to be on screen whenever a topic is" — the Hot Topics door is shut on a fresh
   * session, and the promoted strip that once carried a second copy is gone (M5, D-1). Deleting the
   * card list would have put the warning behind a click, which is not somewhere a hazard notice may
   * live. So it is stated once, above the matrix, naming its window; the popup's topic row states it
   * again for a reader who has opened that window, exactly as the door already does.
   *
   * <p>One line rather than one per badge: a warning is about the hazard, not about the chip, and
   * the card's own rule was already "whichever badge carries one".
   */
  const safety = useMemo(() => {
    // ⚠️ Through the SAME A8 filter the cards and the popup use, not over the raw badge list. A
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
   * `←`/`→` step the open window, and nothing else may be on top.
   *
   * <p>Guarded the way the {@code /} shortcut is (its refusals, now Ask's), and for the same reasons plus
   * one: a stacked sheet or search
   * has its own arrow behaviour (the search list's selection moves on Up/Down and its input takes
   * Left/Right to move the caret), so stepping the window underneath it would move a surface the
   * reader cannot see. Modified arrows are somebody else's shortcut — Alt+Left is the browser's
   * Back — and a text field's own caret keys are never taken.
   *
   * <p>It WRAPS, which the visible {@code ‹ n/6 ›} control also does: six windows on a ring is how
   * the design's own prototype steps them, and a disabled arrow at each end would be two controls
   * that do nothing on the two windows a reader is most often in.
   */
  useEffect(() => {
    if (openWindowKey == null || searchSeed != null || stackedOverPopup) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      const el = event.target;
      const tag = el?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return;
      const index = windowCards.findIndex((card) => card.key === openWindowKey);
      if (index < 0 || windowCards.length === 0) return;
      event.preventDefault();
      const step = event.key === 'ArrowRight' ? 1 : -1;
      const next = (index + step + windowCards.length) % windowCards.length;
      openWindow(windowCards[next].key);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [openWindowKey, searchSeed, stackedOverPopup, windowCards, openWindow]);
  return (
    <div
      ref={shellRef}
      data-testid="window-first-shell"
      // Capture phase, per `commitTabInForce`'s own note above: ANY interaction anywhere in this
      // subtree — a popup control, the lens bar, a card, a key, a scroll — pins the opening
      // preference before it can be moved out from under the reader. Pointer, keyboard and wheel
      // are the three input classes that can reach a control here; a plain `click` needs no
      // listener of its own because it is always preceded by a `pointerdown`.
      onPointerDownCapture={commitTabInForce}
      onKeyDownCapture={commitTabInForce}
      onWheelCapture={commitTabInForce}
      // `wf-shell` hosts `--wf-gutter`/`--wf-lens-reserve`/`--wf-lens-h` — the arm's shared
      // horizontal inset and the sticky-chrome measurements `useLensReserve` publishes. It carries
      // NO width constraint of its own (map-tab-v2-plan.md §3 P7's second full-frame owner): the
      // masthead and the tab bar stay wrapped at `WRAP_MAX_WIDTH` below on every tab, and — since
      // O-17 (bundle rev 2, owner decision 2026-09-03, reversing P7's width release) — the panel
      // region's own wrapper further down now applies that SAME `WRAP_MAX_WIDTH` on the Map tab
      // too, rather than releasing it. (A third carrier of the same constant exists while the Ask
      // dock is open: the COLUMN around both wrappers, which the dock sits beside. It is the same
      // value from the same constant, so the three cannot disagree.)
      //
      // ⚠️ That closes the gap at `sm` (640px) and up, but NOT below it. `App.jsx`'s `<main>`
      // carries `sm:px-4` on the Map tab — present at `sm`+, absent on the phone — because P12's
      // full-bleed phone chrome needs the genuine edge (adversarial review, real finding: an
      // earlier cut dropped `<main>`'s horizontal padding unconditionally, which left the masthead
      // 32px narrower on Map than on every other tab between 640px and ~1112px, the exact
      // disagreement O-17 exists to close). So below `sm` a real, deliberate residue survives: the
      // masthead/tick line/search anchor DO still shift by `<main>`'s own 32px of horizontal
      // padding on a Plan⇄Map switch on a phone. See `App.jsx`'s own comment on `<main>` for the
      // full account — this file's width story is complete only at `sm` and up.
      //
      // On the Map tab it is ALSO a flex column filling whatever height `App`'s own root gives it
      // (App.jsx's `isMapTabActive` recast) — `flex-1 min-h-0` so it actually receives that space
      // rather than sizing to its content, `flex flex-col` so its own children (the masthead+tabbar
      // wrap below, then the panel region) stack and share it the same way. Every other tab keeps
      // plain block flow (`w-full` alone), which is today's unchanged layout and scroll. O-17 is
      // WIDTH ONLY — this vertical/height chain is untouched.
      //
      // ⚠️ With the Ask dock open (F2) the root is a ROW — `[column][dock]`, centred as a pair — and
      // `wf-shell--docked` says so in the stylesheet; on the Map it is then `flex` without `flex-col`
      // (a utility and a rule of the same specificity would be an order-of-source bet). Everything
      // else about the chain below is unchanged: the column wrapper takes the root's old place in it.
      className={`${effectiveTab === 'map'
        ? `wf-shell w-full flex-1 min-h-0 flex${askDockShown ? '' : ' flex-col'}`
        : 'wf-shell w-full'}${askDockShown ? ' wf-shell--docked' : ''}${
        askEntry === 'bar' ? ' wf-ask-bar-on' : ''}`}
    >
      {/* THE SHELL COLUMN: the masthead, the tab row and the panel region, and nothing else. It exists
          so the Ask dock (this wrapper's next sibling in the shell root) can sit BESIDE all three at once — the
          masthead and the panel then narrow together and O-17's "the two columns never drift apart"
          holds, which a dock inside either wrapper could not promise. Undocked it is a plain block
          (a `flex-1 min-h-0` flex column on the Map, taking the root's old place in that chain), so
          nothing about the layout changes when Ask is off or closed. The two wrappers inside are
          deliberately NOT re-indented one level, for the reason the tab row's own wrapper gives. */}
      <div
        className={effectiveTab === 'map' ? 'wf-shell-col flex-1 min-h-0 flex flex-col' : 'wf-shell-col'}
        // Docked, the column is a flex item that takes what the dock leaves, up to the one width the
        // masthead and the panel inside it share. Undocked it is a block and the two wrappers cap
        // themselves, exactly as before.
        style={askDockShown ? { maxWidth: WRAP_MAX_WIDTH } : undefined}
        data-testid="window-first-shell-col"
      >
      {/* Masthead + tab bar + tab rule — wrapped at `WRAP_MAX_WIDTH` on EVERY tab, and since O-17
          the panel region below shares that same width on every tab too (there is no longer a tab
          whose wrap "releases"), at `sm` (640px) and up — below it `<main>`'s own padding still
          differs per tab; see the shell root's own comment above. On the Map tab this is also the
          flex column's first, natural-height item — `flex-shrink-0` so a tight column squeezes
          the panel below it, never this. */}
      <div
        className={effectiveTab === 'map' ? 'mx-auto w-full flex-shrink-0' : 'mx-auto w-full'}
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
        <div className="flex items-center gap-3">
          <BrandLockup variant="masthead" />
          <div className="ml-auto flex items-center gap-2">
            {/* Leftmost of the three — it is a reading, not a control the reader operates to get
                somewhere, so it sits before the pair rather than between them. Absent for everyone
                but an admin, and the gap collapses on its own. */}
            {healthPill}
            {/* ⚠️ TAKES EVERY DIALOG DOWN FIRST, and M5 added that after measuring the alternative.
                `UserSettingsModal` is a SIBLING of this shell in `App`, so it is outside every
                mechanism this arm has for ordering layers: it is not a `Modal` this shell renders,
                `stackedOverPopup` cannot see it, and it takes no `stacked` opt-in. With the window
                popup open a keyboard reader reached this cog on the forty-second Tab (measured) and
                got TWO `aria-modal="true"` elements with neither inert — and then one Escape press
                closed the POPUP underneath while the settings dialog stayed up, because the popup's
                own listener was still armed. Closing first is the rule every other route out of the
                plan already follows (`onGoHome`, the map handoffs), and it keeps the "exactly one
                modal" property a property of the page rather than of three of its dialogs.

                This is exactly the class of route the v1-retirement plan's §4.3 ruling means by
                "held route by route": `UserSettingsModal` sits outside `useDialogFocus`'s mechanism
                the same way it always has, v1 or no v1, and closing here is what keeps the property
                true without a shell-wide `inert` (the structural alternative, still a named
                follow-on, not adopted).

                ⚠️ M5's close missed SEARCH, which is `searchSeed` rather than the popup or a layer
                over it — and this cog is reachable from an open search box, since the tick line
                leaves the tab order under it and the cog does not. It now closes through
                `selectTab` naming the tab in force, the shell's one list, which carries search;
                the tick line's nudge below does the same, and `App`'s `settingsOpen` edge covers
                the map's ⌂. None of them reaches a dialog this shell does not own: `App` closes its
                map overlay on that edge itself, and an Operations-tab admin `Modal` is left open
                under settings, since what it holds (a generated password, say) a close would lose. */}
            <button
              type="button"
              onClick={() => { selectTab(effectiveTab); onOpenSettings?.(); }}
              data-testid="window-first-settings"
              aria-label="Settings"
              className="font-mono border border-plex-border text-plex-text-muted hover:text-plex-text hover:border-plex-border-light transition-colors"
              style={{ fontSize: '10.5px', borderRadius: '7px', padding: '5px 10px' }}
            >
              ⚙
            </button>
            <button
              type="button"
              onClick={onSignOut}
              data-testid="window-first-signout"
              className="font-mono border border-plex-border text-plex-text-muted hover:text-plex-text hover:border-plex-border-light transition-colors"
              style={{ fontSize: '10.5px', borderRadius: '7px', padding: '5px 10px' }}
            >
              Sign out
            </button>
          </div>
        </div>
        <MastheadLight light={light} />
        {/* The tick line — the plan's origin, the way to change it, and today's times, in the one
            row the design allows for all three. It is inside the band deliberately: the rail
            footer it replaces sat outside, and a reader who has just moved the origin should not
            have to look in two places to see where they moved it to. */}
        <MastheadTickLine
          light={light}
          origin={origin ?? null}
          homePlace={homePlace}
          // ⚠️ HANDED OVER ON THE PLAN TAB ONLY — the tab rule the old `/` search shortcut had, which
          // the tick line's two search buttons (the ⌕ and the origin button) did not follow until
          // 2026-09-16. They are search's ONLY way in since F2: `/` is Ask's.
          // Everything search finds is a Plan object, and every pick acts on the Plan: a window opens
          // the popup, a place opens the four-day sheet, a region moves the origin. On Coming up the
          // first two opened over the almanac feed with the tab unmoved (reproduced in jsdom through
          // both buttons) — the state `selectTab` exists to prevent — and Operations offered the
          // same two buttons.
          // WITHHELD rather than refused: with no handler the tick line draws the origin as a
          // statement and no ⌕, where a handler that did nothing would leave two controls with no
          // visible effect (plan-matrix §3 rule 14). The Map tab withheld both before this for its
          // own reason (P11: panning is the search there). The beyond line, the third trigger, sits
          // inside the Plan pane and is hidden with it.
          //
          // ⚠️ And the SAME stacking guard the old `/` search shortcut carried, which M5 added because
          // the button did not. Measured in a browser: from an open location sheet a keyboard reader
          // reached this control on the seventeenth Tab and opened search as a THIRD layer — and
          // `Modal` gives every dialog `fixed inset-0 z-50`, so with equal z-index paint order is DOM
          // order and the sheet, which renders after search, painted its scrim and its whole card
          // OVER the search panel. The reader typed into a box behind a dead, dimmed sheet. The
          // shortcut's own comment (since deleted with it) had settled the rule this restores — "those
          // are already stacked on the popup, and a third layer has nowhere to go" — so the button was
          // simply bypassing it.
          onOpenSearch={effectiveTab === 'plan'
            ? () => { if (!stackedOverPopup) setSearchSeed(''); }
            : undefined}
          // ⚠️ Takes every dialog down first — but at M5 the two calls stopped being on the same
          // footing, and the difference is worth stating so neither is later read as dead.
          //
          // `openWindow(null)` has a LIVE route. `useDialogFocus` is not a trap and nothing makes
          // the masthead inert, so a Tab walk out of an open dialog reaches this row — M5 measured
          // press 17. With ONLY the popup open `searchOpen` is false, so the row keeps its tab
          // stops and this button is reachable from inside the popup; moving the origin under it is
          // the case M4.3's close-then-move footer exists to rule out.
          //
          // `openOverPopup(null)` is a belt, and M5's own fix is why. `searchOpen` counts
          // `stackedOverPopup` as well as search (the prop below) and puts `tabIndex={-1}` on all
          // four controls in this row, so the moment a sheet or the pick dialog stands over the
          // popup this button leaves the tab order — it cannot be what moves the origin with a
          // sheet up. Kept because the invariant is stated once per route, not once per reachable
          // route. One rule, every route.
          //
          // Focus is the tick line's to settle, not this handler's: the press removes ⌂, and the
          // line hands a reader it held to the origin control. On the live route above that lands
          // before the popup's own restore, which would put them back on the card that opened it
          // — by design (`MastheadTickLine`'s class comment), so a focus move added here would be a
          // second answer to the same question.
          onGoHome={() => { openOverPopup(null); openWindow(null); setOrigin?.(null); }}
          // ⚠️ The cog's rule, which this route went without: `App` wired the nudge straight to its
          // own handler, so with a window popup open — where `searchOpen` is false and this row
          // keeps its tab stops — a reader who Tabbed out onto the nudge opened settings OVER the
          // popup, two `aria-modal` elements with the popup's Escape listener still armed beneath.
          // It closes through the same `selectTab(effectiveTab)` the cog does. The layers over the
          // popup and search are belts here, exactly as they are for `onGoHome`: under either this
          // row is out of the tab order.
          //
          // The arguments are FORWARDED, whatever they are: what the nudge hands its handler is the
          // tick line's business, and a wrapper here that swallowed them would change that contract
          // without either end noticing.
          onSetPostcode={(...args) => {
            selectTab(effectiveTab);
            (onSetPostcode ?? onOpenSettings)?.(...args);
          }}
          // Out of the tab order for TWO reasons now, which is why the prop is no longer named for
          // one of them. The anchored search panel covers this row exactly (WCAG 2.4.11) — and a
          // layer stacked over the popup makes the search button refuse, so leaving it tabbable
          // would be a control with no visible effect, which plan §3 rule 14 bans outright.
          searchOpen={searchSeed != null || stackedOverPopup}
          // map-tab-v2-plan.md §3 P11: a per-tab STATE of the tick line, read off the SAME
          // `effectiveTab` every other map-only branch in this file already keys on (the full-frame
          // recast at `:1154`/`:1355`, the search/sheet gates below) — never a second "which tab"
          // test that could disagree with them. ⚠️ Since 2026-09-16 the statement is not the Map's
          // alone: `onOpenSearch` above is withheld on every tab but Plan, which draws it there too.
          // This prop keeps the Map's own no-search rule and the caption only the Map draws.
          isMapTab={effectiveTab === 'map'}
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

      {/* The tab ROW: the tab list and, from 640 to 1023px, the Ask field BESIDE it (plan §2.6).
          Always rendered, with or without the field — wrapping conditionally would remount the tab
          buttons the moment Ask's availability resolved, taking focus from one a reader was on.
          (The tab list inside is deliberately NOT re-indented one level: that would turn a
          four-line wrapper into a reflow of eighty lines nobody has to review.) */}
      <div className="wf-tabrow" data-testid="window-first-tabrow" data-tab-count={tabs.length}>
      <div
        data-testid="window-first-tabs"
        role="tablist"
        aria-label="Plan sections"
        className="wf-tabs flex gap-1.5"
      >
        {tabs.map((tab, index) => {
          const selected = tab.id === effectiveTab;
          // Only the Coming up tab ever carries a badge (design §6: "forecast topics do not badge
          // on arrival" — no other tab has an equivalent signal at all).
          const badge = tab.id === 'coming-up' ? comingUpBadge : null;
          // Explicit only while a badge is showing — otherwise the accessible name computes from
          // content exactly as it always has, and there is nothing here to override. With a badge,
          // the badge SPAN below is `aria-hidden` and this is the only place its meaning reaches a
          // screen reader (design §6's two shapes, put into words: "1 new announced event" / "new
          // interrupt event" — the interrupt shape carries no number to read back, matching the
          // visual).
          // The two band names — `interrupt` and `announce` — are the surprise model's, not the
          // reader's. A screen-reader user hears only this string, so it carries the difference the
          // bands encode (one exceptional arrival versus several ordinary ones) without naming them.
          const badgeAriaLabel = badge
            ? `${tab.label}, ${badge.band === 'interrupt'
              ? 'one rare event added'
              : `${badge.count} new event${badge.count === 1 ? '' : 's'}`}`
            : undefined;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              id={tabDomId(tab.id)}
              aria-selected={selected}
              aria-controls={panelDomId(tab.id)}
              aria-label={badgeAriaLabel}
              // Roving: the bar is ONE tab stop, and the arrow keys move within it. Without this a
              // keyboard user tabs through every tab to reach the pane.
              tabIndex={selected ? 0 : -1}
              ref={(node) => { tabRefs.current[index] = node; }}
              onClick={() => selectTab(tab.id)}
              onKeyDown={(event) => handleTabKey(event, index)}
              data-testid={tabDomId(tab.id)}
              // Type, padding and the selected treatment all live in `.wf-tab` — the phone rule
              // changes two of them, and a media query cannot reach an inline style. The selected
              // state hangs off `aria-selected`, which the tab pattern already requires above, so
              // the whole style object migrates without inventing a state class or a prop. The
              // mock's own weights (500 resting, 600 active) and the gold top rule are in the
              // stylesheet beside the geometry they belong with.
              className={`wf-tab${tab.gated ? ' wf-tab-gated' : ''} font-sans whitespace-nowrap border border-plex-border transition-colors ${
                selected
                  ? 'bg-plex-surface text-plex-text'
                  : 'bg-plex-panel text-plex-text-secondary hover:text-plex-text'
              }`}
            >
              {tab.glyph && (
                <span aria-hidden="true" style={{ fontSize: '12px', opacity: 0.8 }}>
                  {`${tab.glyph} `}
                </span>
              )}
              {tab.label}
              {badge && (
                <span
                  aria-hidden="true"
                  data-testid="coming-up-tab-badge"
                  className={badge.band === 'interrupt' ? 'wf-tab-badge wf-tab-badge-rare' : 'wf-tab-badge'}
                >
                  {badge.band === 'interrupt' ? '◆' : badge.count}
                </span>
              )}
            </button>
          );
        })}
      </div>
      {askEntry === 'field' && (
        <AskField
          width={260}
          prompt="Ask about the forecasts…"
          disabled={askDisabled}
          expanded={askSheetShown}
          onOpen={openAskSheet}
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
          prompt="Ask about the forecasts…"
          disabled={askDisabled}
          expanded={askDockShown}
          controls={ASK_DOCK_ID}
          onOpen={openAskDock}
          buttonRef={askTriggerRef}
        />
      )}
      {askGhost && (
        <div
          className="wf-askf wf-askf-ghost"
          aria-hidden="true"
          data-width={askFieldWidth}
          data-testid="ask-field-ghost"
        />
      )}
      </div>
      <div data-testid="window-first-tabrule" className="h-px bg-plex-border" />
      </div>

      {/* The panel region — P7's width split, REVERSED (O-17, bundle rev 2, owner decision
          2026-09-03): the Map tab no longer goes full-width. Bundle rev 2's own case is
          structural, not a taste call — full-bleed reads as broken because the tab strip stops at
          the content column while a full-width panel carries on to the window edge, so the tabs
          look like they float above an unrelated surface, and full width adds sea and empty moor
          rather than information (at 2400px one screen spans ~150 miles and the window control and
          Filters end up a head-turn apart). So this wrapper now applies the SAME `WRAP_MAX_WIDTH`
          + centering on EVERY tab, Map included — one `style` object below, shared rather than two
          copies of the constant, so the masthead's column and the map panel's column can never
          drift apart the way the design's complaint describes. A width change here on tab switch
          was never actually a hazard either way (nothing sticky lives in this wrapper — the
          masthead and the tab bar, the two elements a width jump would actually disturb, are both
          in the wrapper above, which never changes) — but now THIS wrapper's own `maxWidth` never
          changes either, at any viewport. That is not quite the whole width story, though: below
          `sm` (640px) `<main>`'s own padding (`App.jsx`, outside this component) still differs
          per tab for P12's full-bleed phone chrome, so the masthead still shifts by 32px on a
          phone-width tab switch — see the shell root's own comment above for the full account.

          The vertical behaviour is UNTOUCHED by O-17. On the Map tab this is still the flex
          column's second item — `flex-1 min-h-0` so it takes every pixel the masthead+tab-bar item
          above did not, `flex flex-col` so its own visible child (the Map tab's own slotted-pane
          wrapper, `.wf-body.wf-body--map` — every OTHER child here is `hidden` while the Map tab
          is active, so it is the pane's only flex participant) can do the same. No height is
          computed anywhere in this chain; flexbox distributes it. `mx-auto` costs nothing on that
          chain — a horizontally centred flex column is still a flex column. */}
      <div
        className={effectiveTab === 'map' ? 'mx-auto w-full flex-1 min-h-0 flex flex-col' : 'mx-auto w-full'}
        style={{ maxWidth: WRAP_MAX_WIDTH }}
      >

      {/* Plan only, and that is the design's own heading for it ("Lens bar (Plan only)"). The bar
          filters SPOTS; the almanac feed has none, so on Coming up it would gate nothing — §6's
          "no control gates on data that does not exist", and its own footer would read "0 spots
          across 5 windows" over a pane containing neither. It is unmounted rather than hidden so
          the sticky bar cannot take a scroll position with it. */}
      {effectiveTab === 'plan' && reachLens && ratingLens && (
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
      {effectiveTab === 'plan' && reachLens && ratingLens && (
        <WindowFirstLensBar
          stuck={lensStuck}
          lens={reachLens}
          ratingLens={ratingLens}
          spotCount={windowCards.reduce((total, card) => total + card.spots.length, 0)}
          // The rating floor's own denominator — what reach left for it to choose from. Summed here
          // rather than derived in the bar for the reason `spotCount` already is: the counts have to
          // be the lengths of the arrays that were drawn, and only this component can see all six.
          reachedCount={windowCards.reduce((total, card) => total + (card.reachedTotal ?? 0), 0)}
          // ⚠️ Whether the reach axis COULD act — see `formatLensCount`. A reader with no home
          // postcode has no drive time anywhere, so `reachedCount` is simply everything and the
          // readout's "within reach" would name a gate that did nothing. This is the page's ONE
          // count statement (§4 A7), so it is the most load-bearing place that claim could be
          // wrong; an adversarial review of M5 found it still making it after the popup was fixed.
          // Asked of `allSpots` — the origin scope BEFORE the reach gate — for the reason
          // `WindowSheetDialog` records: the drawn set would make the wording flicker per window.
          reachMeasured={windowCards.some((card) => card.reachMeasured)}
          windowCount={windowCards.length}
          originBase={origin?.baseName ?? null}
        />
      )}

      {/* Mounted whichever tab is selected, and hidden when it is not — the same lifetime the Plan
          pane has, and for a reason beyond symmetry. Both tabs carry `aria-controls` pointing at
          their panel's id, and an `aria-controls` whose target is not in the document resolves to
          nothing: while this was mounted conditionally, the pairing the tab pattern requires was
          only ever half present, and the half that was missing was always the tab a reader had not
          reached yet. It costs a heading and a footer in the DOM; it does NOT cost a request, since
          the fetch is gated on the tab rather than on the mount. */}
      <WindowFirstComingUp
        id={panelDomId('coming-up')}
        labelledBy={tabDomId('coming-up')}
        hidden={effectiveTab !== 'coming-up'}
        status={comingUp.status}
        events={comingUp.events}
        hotTopics={briefing?.hotTopics}
        todayStr={todayStr}
        onRetry={comingUp.retry}
        onGoToPlan={goToPlan}
        onShowOnMap={onShowOnMap}
        comingUpLastSeenDate={comingUpLastSeenDate}
        forecastDates={forecastDates}
        onMarkSeen={markSeen}
      />

      {/* Hidden rather than unmounted: unmounting the pane on every tab change would discard the
          drill-down and the doors' open/closed state on every change back to Plan — which is
          exactly what `ManageView` does to its sub-views, and the behaviour not to copy. Keeping it
          mounted keeps whatever the reader had open open.

          BOTH the `hidden` attribute and a display class, and the reason is defence in depth rather
          than necessity — which is worth stating plainly, because two earlier versions of this
          comment got the mechanism wrong in opposite directions. Tailwind v4's preflight ships
          `[hidden]:where(:not([hidden='until-found'])) { display: none !important }`
          (`node_modules/tailwindcss/preflight.css:396`). That is an AUTHOR rule carrying
          `!important`, so it beats every normal author declaration whatever the specificity — the
          attribute alone does hide the pane, and `.flex` does not override it. Verified on the
          running app: a `<div class="flex" hidden>` computes to `display: none`. Equally, the
          class alone would be enough, since `display: none` is itself what removes an element from
          the accessibility tree (`WindowFirstDoors.jsx` says so already).

          So: the attribute is the semantic statement and the half jsdom can see; the class is
          carried so that a display utility added here later cannot quietly re-expose the panel. */}
      <div
        id={panelDomId('plan')}
        role="tabpanel"
        aria-labelledby={tabDomId('plan')}
        hidden={effectiveTab !== 'plan'}
        data-testid="window-first-pane"
        className={`wf-body ${effectiveTab === 'plan' ? 'flex' : 'hidden'} flex-col${dimmed}`}
      >
        {/* Page-level, above the pictures, because both messages are about the WHOLE plan — the
            design's own placement and its own reason. The per-window half of what the card ladder
            used to do is the popup's quiet sentence; the two replacements land in one phase. */}
        {/* ⚠️ The WRAPPER is always mounted and carries `role="status"`; the message inside is what
            comes and goes. A `role` added to a conditionally-mounted node is not announced — the
            drill-down's own count records that trap two files away — and this message appears in
            response to a lens change several elements up the page, so a screen-reader reader would
            otherwise watch their whole plan empty in silence. */}
        <div
          role="status"
          aria-live="polite"
          data-testid="window-first-conflict-slot"
          className="wf-conflict-slot"
        >
          {conflict && (
          <div data-testid="window-first-conflict" data-conflict={conflict.id} className="wf-clash">
            <b data-testid="window-first-conflict-head">{conflict.headline}</b>
            <span data-testid="window-first-conflict-body">{conflict.body}</span>
            {conflict.actions.length > 0 && (
              <span className="wf-clash-acts">
                {conflict.actions.map((action) => (
                  <button
                    key={`${action.kind}:${action.id}`}
                    type="button"
                    data-testid="window-first-conflict-act"
                    data-loosen={action.kind}
                    className="wf-clash-act"
                    onClick={() => applyConflictAction(action)}
                  >
                    {action.label}
                    <span aria-hidden="true"> →</span>
                  </button>
                ))}
              </span>
            )}
          </div>
          )}
        </div>

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
            spots={heatSpots}
            reachById={reachById}
            /* The served topics, for the matrix's scope filter (plan-matrix A8). Read straight off
               the briefing rather than through a derivation, because the join key is the topic's
               OWN `eventType` + `date` and any client re-shaping is a chance to lose the NIGHT
               bucketing. `WindowFirstDoors` reads the same field the same way. */
            hotTopics={briefing?.hotTopics}
            openKeys={openWindowKeys}
            highlightKeys={askHighlight}
            todayStr={todayStr}
            runAge={age}
            onOpenWindow={openWindow}
            origin={origin ?? null}
            homeCoords={homeCoords}
            onSearchRegion={(regionName) => { if (stackedOverPopup) return; setSearchSeed(regionName); }}
          />
        </Suspense>

        {/* ⚠️ The WRAPPER is always mounted and carries `role="status"`; the line inside is what
            comes and goes — the same shape `WindowFirstComingUp`'s own status wrapper uses, and
            for the identical reason (`docs/engineering/window-first-redesign-plan.md` §5f):
            a live region inserted in the same commit as the CONTENT IT THEN CHANGES TO is
            unreliably announced — the wrapper has to already be on screen before the pending→slow
            and pending→settled TRANSITIONS it exists to announce. Before this wrapper existed, the
            pane rendered NOTHING at all while `loading` was true and nothing had arrived yet — no
            matrix, no doors, no count line, no sentence here — which is what made the 2026-09-30
            slow-briefing incident (a `GET /api/briefing` request that never completed in
            production, fixed in #957) read as a blank pane rather than a pane still working.

            `wf-pane-status` cancels the `.wf-body` flex gap (index.css, beside the `.wf-body` rule)
            when this wrapper is empty — i.e. whenever there ARE cards, which is what every reader
            sees almost all the time — so the strip→doors spacing stays the 10px it was before this
            wrapper existed, rather than adding a second 10px gap for an always-mounted but usually
            empty node. */}
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

        {/* The two doors, at the foot of the pane where the design puts them and inside the greyed
            region: they open forecast content, which is exactly what that treatment marks. */}
        <WindowFirstDoors locations={locations} onShowOnMap={onShowOnMap} />
      </div>

      {/* The slotted panes. Each renders its panel ELEMENT unconditionally — `aria-controls` must
          name something that exists, and a tab pointing at nothing is half a relationship — but its
          CONTENTS wait for the tab to be selected once, and then stay.

          That split is this file's own idiom, not a new one: `useComingUpFeed` is already gated on
          the selected tab while its panel is always mounted. It matters more here. Mounting
          `ManageView` eagerly would pull 633 KB and fire its waitlist and user fetches on every
          Plan-tab first paint, for a pane most sessions never open. Never unmounting after that is
          equally deliberate — but NOT for the reason first written here. `ManageView` writes
          `#manage/<tab>` on every sub-tab click and parses that hash on mount, so the sub-view is
          the one thing that WOULD survive a remount. What a remount actually discards is the rest:
          the selected run, table filters, scroll position, and a re-fired waitlist fetch.
          ⚠️ The cost of never unmounting is real and measured: a Scheduler sub-view left open keeps
          its 30-second poll running for the rest of the session, invisibly, after the reader has
          gone back to Plan. Admin-only and one interval, not several — but if that is not wanted,
          release the pane here rather than deleting the comment. */}
      {tabs.filter((t) => t.slot).map((tab) => (
        <div
          key={tab.id}
          id={panelDomId(tab.id)}
          role="tabpanel"
          aria-labelledby={tabDomId(tab.id)}
          hidden={effectiveTab !== tab.id}
          data-testid={`window-first-panel-${tab.id}`}
          // `wf-body` on BOTH branches, exactly as the two panes above do it. Without it this panel
          // rendered flush to the frame while its siblings sat at the arm's inset — measured 100px
          // vs 118px at 1280 and 16px vs 30px at 390 — so the content edge jumped on every tab
          // change, and ManageView's own group bar landed on the tab rule reading as one two-row
          // control. That is precisely what this class was introduced to make structurally
          // impossible, in a comment this file already carries. `gap` is inert on a block panel.
          //
          // `wf-body--map` is the SELECTED Map tab's own addition (map-tab-v2-plan.md §3 P7's
          // third + fourth full-frame owners): it releases `wf-body`'s inset padding to zero (the
          // map is meant to bleed to the frame's edge — since O-17 that "frame" is this panel's own
          // width-capped column, not the browser window; see the panel-region wrapper's comment
          // above) and makes this panel a `flex:1; min-height:0`
          // flex child of the panel-region wrap above — no height is computed anywhere in the
          // chain (a `calc(100dvh - …)` version of this shipped once and was reverted: a live
          // measurement found 16px of inter-element spacing a `ResizeObserver` on element BOXES
          // cannot see, index.css's own comment on `.wf-body.wf-body--map` has the full account).
          // Flexbox distributing real, measured space is what lets `MapView`'s own `flex:1` map
          // container fill "the rest of the screen" with no page scroll. Never applied to
          // Operations, and never applied to the Map tab while it is merely mounted-but-hidden
          // (`hidden` wins the cascade regardless either way, but there is no reason to make an
          // invisible panel a flex child of anything).
          className={effectiveTab === tab.id
            ? (tab.id === 'map' ? 'wf-body wf-body--map' : 'wf-body')
            : 'wf-body hidden'}
        >
          {/* `|| tab.id === effectiveTab` — belt over the render-time `openedTabs` adjustment
              above: a Map pane shown only by the opening PREFERENCE (never through `selectTab`,
              which already seeds `openedTabs` itself) must mount on the very render it becomes
              effective, not one render late — a blank panel is the failure this guards. */}
          {(openedTabs.has(tab.id) || tab.id === effectiveTab)
            ? { mapPane, operationsPane }[tab.slot] : null}
        </div>
      ))}
      </div>
      </div>

      {/* Ask PhotoCast's DOCK — the second child of the root row, a sibling of the whole shell column
          (F2, plan §2.6/D-4). Mounted only while open and only from 1024px, on Plan, Coming up and the
          Map. NOT a dialog: the page beside it stays live, and it is `inert` (never the page) while
          a shell dialog, search or settings is open. Under a dead backend it is NOT inert — the field
          is disabled and `/` refused, but a dock already open must stay closable (an inert ✕ would
          pin it there, dimmed, for as long as the backend is down). On the Map the dock is the
          frame's own height and the map pane's `ResizeObserver` tells Leaflet about its new width;
          on Plan and Coming up it sticks to the viewport with the input row at its foot. */}
      {askDockShown && (
        <AskDock
          band={askSurface}
          sticky={effectiveTab !== 'map'}
          inert={askDialogOpen}
          view={askViewSpec.view}
          viewLabel={askViewSpec.label}
          contextLabel={ASK_DOCK_CONTEXT[effectiveTab] ?? ASK_DOCK_CONTEXT.plan}
          inputRef={askDockInputRef}
          onClose={closeAskDock}
          fallbackFocus={selectedTabNode}
          pickActions={askPickActions}
          planActions={askPlanActions}
        />
      )}

      {/* Ask PhotoCast. The phone's bar is `position: fixed` and sits BELOW every `Modal` (z-50) and
          the sheet's scrim; the sheet is a `BottomSheet`, portalled to <body>. Both are drawn only
          where `askEntry` says (plan §2.6). */}
      {askEntry === 'bar' && (
        <AskBar
          prompt={ASK_BAR_PROMPT[effectiveTab] ?? ASK_BAR_PROMPT.plan}
          disabled={askDisabled}
          expanded={askSheetShown}
          onOpen={openAskSheet}
          buttonRef={askTriggerRef}
        />
      )}
      {askEntry !== null && (
        <AskSheet
          open={askSheetShown}
          onClose={closeAskSheet}
          view={askViewSpec.view}
          viewLabel={askViewSpec.label}
          pickActions={askPickActions}
          planActions={askPlanActions}
          restoreFallback={askRestoreFallback}
        />
      )}

      {/* The window popup — the plan's drill-down, over the plan rather than inside it.
          Mounted only while open, and lazily, for the reasons its own boundary records. */}
      {openCard && openField && (
        <Suspense fallback={null}>
          <WindowSheetDialog
            // ⚠️ NOT keyed on the window, and that is a fix rather than an omission. A `key` here
            // remounted the dialog on every `‹ ›` step, and `useDialogFocus` restores focus to its
            // captured trigger on unmount — so a keyboard reader pressing `›` lost the button they
            // had just pressed, every time, and had to Tab back through the header to press it
            // again. The dialog's own body scroll is reset on a window change instead, which is the
            // only thing the remount was buying.
            card={openCard}
            index={openIndex}
            total={windowCards.length}
            field={openField}
            topicIndex={topicIndex}
            eclipseIndex={eclipseIndex}
            scopeNames={planScopeNames}
            todayStr={todayStr}
            // ⚠️ The Escape ORDER, and the whole of it: this layer declines the key while anything
            // sits over it, so a press takes exactly one layer. See `stackedOverPopup`.
            escapeEnabled={searchSeed == null && !stackedOverPopup}
            // The peek is suppressed by whatever is over the popup, never by the popup itself:
            // a hover panel is portalled above every `Modal`, so it may only ever be opened from
            // the topmost surface.
            peeksSuppressed={modalOpenOverPopup}
            onClose={() => openWindow(null)}
            onStep={(delta) => {
              const next = (openIndex + delta + windowCards.length) % windowCards.length;
              openWindow(windowCards[next].key);
            }}
            onOpenPick={(pick) => openOverPopup({ pick })}
            // ⚠️ M4 (D-3) RETARGETS both of these from the map to the location sheet, and the
            // popup deliberately stays open underneath. Until this phase a spot card opened the
            // map, and the rule at this seam was "closes FIRST" because `MapOverlay` is itself
            // `aria-modal` and the reader had arrived at a destination. A sheet is not a
            // destination: it is one place's own four days, opened from the window the reader is
            // still reading, and closing the popup would throw that window away to answer a
            // question about one of its rows. So this is the stack the Escape order was written
            // for — `stackedOverPopup` already counts `sheetSpot`, so the popup declines Escape
            // while the sheet is up and a press takes exactly one layer. The map is not lost: the
            // sheet's footer carries it, and closes both from there.
            //
            // `sheetSpotOf` is the ONE translation from the briefing's `locationId`/`locationName`
            // vocabulary to the sheet's identity, shared by both entries so a chip and the card
            // beneath it can never open two different pages for one place.
            onOpenSpot={(card, spot) => openOverPopup({ spot: sheetSpotOf(spot) })}
            onOpenLocation={(chip) => openOverPopup({ spot: sheetSpotOf(chip) })}
            // Door 1 (doors plan §3 D4) — withheld (undefined, not a no-op closure) exactly when
            // there is no map door at all, so `WindowRowFieldMap` never renders the button rather
            // than rendering one that closes the popup and lands nowhere. The region carried is the
            // popup's OWN focus (`openField.selectedRegion`, already forced null under an away
            // origin — see `openField`'s own note), never re-derived here.
            onOpenInMap={onOpenMapTab
              ? () => openMapTab({
                date: openCard.date,
                targetType: openCard.targetType,
                region: openField.selectedRegion ?? null,
              })
              : undefined}
            onSeeAllSpots={sheetOffersMore(openCard, typesByName)
              ? (card) => openOverPopup({ sheetKey: card.key })
              : undefined}
            scoreIndex={scoreIndex}
            colourMode={mapColourScale}
          />
        </Suspense>
      )}

      {/* Keyed on the window, so opening a different card's sheet mounts a fresh one rather than
          carrying the previous window's reach widening across. Both axes are inherited from the bar
          and local from there on, so neither carries across — the sheet reads no storage at all. */}
      {sheetCard && (
        <WindowSpotSheet
          key={sheetCard.key}
          card={sheetCard}
          barTierId={reachLens.tierId}
          // The bar's floor is the one the sheet opens on, exactly as its tier is. The sheet's own
          // change to either is local and dies with the dialog — see its class comment.
          barFloorId={ratingLens.floorId}
          // Widen REACH only for a window REACH emptied, which is what `reachedTotal === 0` says.
          //
          // This keyed on `spots.length === 0` until the rating floor arrived, when `spots` became
          // gateSpotsByRating(gateSpotsByReach(...)) and the test silently widened to "emptied by
          // either axis". A rating-emptied window then opened on ANY_TIER_ID: it threw away the
          // reader's chosen tier, printed "widened for browsing" over a widening that had gated
          // nothing, and — since the sheet inherits the floor that did the emptying — still opened
          // onto an empty list. A door onto a wall, which is the one thing this prop exists to
          // prevent. Reachable because the emptied card renders its own "See all" trigger.
          //
          // `reachedTotal` is the survivors of reach alone, added alongside the floor for exactly
          // this denominator. Widening reach can only help when reach is what removed them.
          openTierId={sheetCard.reachedTotal === 0 && sheetCard.reachTotal > 0
            ? ANY_TIER_ID
            : undefined}
          // A boolean, never the role — plan §5c's rule that `role` enters this arm at the provider
          // and stops there. The sheet's reach control is the same PRO control the bar is (§7), so
          // it takes the same lock; the rating floor and the type are not gated at all.
          reachLocked={reachLens.locked}
          typesByName={typesByName}
          // ⚠️ The same one-layer-per-press predicate its two sibling layers carry, and since M5 a
          // belt that can no longer go false: search is refused while anything is stacked over the
          // popup (`stackedOverPopup` counts this sheet), so the supported stack is two deep and
          // nothing can sit over this one. Kept in the shared shape because `WindowSpotSheet`
          // derives `stacked` from it, and those two must never come apart.
          escapeEnabled={searchSeed == null}
          onClose={() => openOverPopup(null)}
          // Closes FIRST, exactly as the strip dismisses its peek before the same handoff. The map
          // overlay is itself an `aria-modal` dialog: leaving the sheet mounted underneath puts two
          // on the page at once, gives Escape two listeners to satisfy, and leaves the reader's
          // place in a list they have navigated away from. The sheet is a browsing surface and the
          // map is a destination — arriving at the destination ends the browsing.
          // ⚠️ `openWindow(null)` as well as the sheet, and M4 is where that became load-bearing.
          // `MapOverlay` is itself an `aria-modal` dialog with its own unconditional document
          // Escape listener, and the window popup underneath re-arms its own the moment nothing is
          // stacked on it — so leaving it mounted puts two dialogs on the page and makes one press
          // close both, the very thing this arm's Escape order exists to prevent. The reader has
          // arrived at a destination, which ends the browsing.
          onOpenSpot={(spot) => { openOverPopup(null); openWindow(null); handleSpot(sheetCard, spot); }}
        />
      )}

      {/* Lazy, and mounted only while open — it is a dialog, so it is on no first-paint path.
          Keyed on the seed so opening it from the beyond line always mounts a fresh box with that
          region typed in, rather than reusing one that has already been edited. */}
      {searchSeed != null && (
        <Suspense fallback={null}>
          <PlanSearch
            key={searchSeed}
            initialQuery={searchSeed}
            windows={heatStripCards}
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
            // ⚠️ CLOSES THE POPUP IN THE SAME COMMIT, and that is the P8 invariant rather than
            // tidiness. (Not an ordering: React batches both setters out of one handler, so the
            // unmount and the origin change land together and there is no frame in which the popup
            // is rendered against the new origin — a stronger guarantee than "close, then move".)
            // Search can now sit OVER an open window popup (M3's whole point), so without this a
            // reader could move the origin while the popup watched: the reach default drops to 90,
            // `effectiveReachById` swaps, and the popup's spot strip, best-in-reach figure, spread
            // histogram, region rail and every leave-by re-derive underneath them. P8 refused to
            // build exactly that, and M4.3's `Plan from <region>` footer is specified as
            // close-then-move for the same reason — two contradictory semantics for one action on
            // one screen is what this avoids.
            // ⚠️ `openOverPopup(null)` as well — the SAME rule, applied at every route that moves
            // the origin rather than only at the reachable ones. Moving it with a location sheet up
            // is the exact thing M4.3's close-then-move footer goes to trouble to prevent: the
            // drive, the base named beside it, the outside badge and every departure would all
            // change under the reader.
            // ⚠️ It is NOT here because search can sit over that sheet — since M5 it cannot. The
            // third layer is refused outright: all three routes into search guard on
            // `stackedOverPopup`, which counts `sheetSpot`, so the supported stack is two deep —
            // search over the popup, or a sheet over the popup, never both (plan-matrix §4 A22).
            // So this arm cannot fire with a sheet up today. It stays because the invariant is
            // stated once per route — `onGoHome` above and the sheet's own footer state it too —
            // and a route added later inherits it instead of rediscovering why this one was
            // exempt. One rule, every route.
            onPickRegion={(region) => {
              openOverPopup(null); openWindow(null); setOrigin?.(region);
            }}
            // ⚠️ The same belt here, and for the reason above rather than a new one: nothing can
            // be over the popup when a search pick fires, because that layer is what refuses
            // search. Were one ever up, the pick would land on a popup nobody can see — search
            // closes, `openWindowKey` moves, and the layer is still on top, so from the reader's
            // side choosing a window did nothing until the next Escape.
            onPickWindow={(key) => { openOverPopup(null); openWindow(key); }}
            // P8: a location result opens that place's own six-window timeline rather than jumping
            // straight to the map. The map is not lost — the sheet's footer carries it and names
            // the window it opens.
            //
            // Closes the popup first, for the same reason `onPickRegion` above does. M4 DOES stack
            // this sheet over the popup — but from the popup's own chips and spot cards, where the
            // reader is already looking at that window. Arriving from search is a different
            // gesture, and it is the one the shell's own "closes FIRST" rule already governs
            // everywhere else.
            onPickLocation={(spot) => { openWindow(null); openOverPopup({ spot }); }}
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
            tideAlignmentIndex={sheetTideAlignmentIndex}
            // The eclipse spot line, per solar row (L7).
            eclipseIndex={sheetEclipseIndex}
            // Where the close lands when the sheet was opened from Ask's sheet (F5); null for every
            // other opening, which keeps its restore exactly as it was.
            restoreFocusFallback={askSheetFallback}
            escapeEnabled={searchSeed == null}
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
                openWindow(null);
                setOrigin?.(sheetPlanFrom.region);
                // ⚠️ The focus move, and the reason is the one `applyConflictAction` already
                // records: this commit unmounts BOTH dialogs, so `useDialogFocus`'s restore finds
                // its captured trigger — a chip or a card that lived inside the popup — detached,
                // declines to focus it, and the reader is dropped at `<body>` while the page
                // re-frames underneath them. `button[…]` rather than `[…]`, because an away window
                // keeps its matrix cell as a non-focusable `<div>` and `querySelector` returns DOM
                // order — which, since the rails restructure, lands on the first card of the
                // SUNRISE row rather than the first day's own first window (matrix-axis plan D20).
                // Deferred a frame because the matrix is re-rendering on this very commit, and
                // optional-CALLED because jsdom implements no layout.
                requestAnimationFrame(() => {
                  document.querySelector('button[data-testid="wf-heat-card"]')?.focus?.();
                });
              }
              : undefined}
            onClose={() => openOverPopup(null)}
            // Door 2 (doors D3, `plan-to-map-doors-plan.md` §3 D3 task 1) — re-pointed from the
            // frozen overlay to the Map tab. `openMapTab` (the `openMapDoor` wrapper above) does its
            // OWN close-then-move: `openOverPopup(null)` then `openWindow(null)` before it reads the
            // live lens and hands the door to `onOpenMapTab`, so nothing here repeats that ordering —
            // repeating it would just be two callers racing to close the same two pieces of state.
            // `region: null` because this door names a LOCATION, never a region (the popup's own
            // region routes — `WindowPickDialog`'s two actions below — are untouched: they stay on
            // the overlay, D3 re-points only the sheet footer, O-6 is not this phase).
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
              ? (date, targetType, name) => openMapTab({
                date, targetType, locationName: name, region: null,
              })
              : undefined}
          />
        </Suspense>
      )}

      {openPick?.pick && (
        <WindowPickDialog
          pick={openPick.pick}
          when={openPick.when}
          time={openPick.time}
          escapeEnabled={searchSeed == null}
          onClose={() => openOverPopup(null)}
          // ⚠️ `openWindow(null)` TOO, and this is the third of the three routes to the map — the
          // two sheets got it at M4 and this one was missed. `MapOverlay` is itself an `aria-modal`
          // dialog with an unconditional document Escape listener and it is NOT a `Modal`, so it
          // takes no `stacked` opt-in; and the instant `openPick` clears, `stackedOverPopup` goes
          // false and the popup re-arms its own listener and re-takes `aria-modal`. Leaving it
          // mounted therefore puts two modals on the page with the lower one fully tab-reachable
          // under the overlay, and makes one press close both — the whole of what M5's stacking
          // work exists to prevent, defeated on a pointer route. The reader has arrived at a
          // destination, which ends the browsing.
          onShowRegion={() => {
            onShowOnMap?.({
              region: openPick.pick.regionName, date: openPick.date, eventType: openPick.targetType,
            });
            openOverPopup(null);
            openWindow(null);
          }}
          onShowLocation={() => {
            onShowOnMap?.(openPick.date, openPick.targetType, openPick.pick.locationName);
            openOverPopup(null);
            openWindow(null);
          }}
        />
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
   * The shell→App channel for the full-frame Map tab (map-tab-v2-plan.md §3 P7). Fired with the
   * effective tab id on mount and on every change — `App` cannot otherwise learn which tab is
   * active (`effectiveTab` is shell-internal), and it needs to know in order to recast `<main>`'s
   * padding (no bottom padding, `sm:px-4`, the top padding kept) for the Map tab.
   */
  onTabChange: PropTypes.func,
  onOpenSettings: PropTypes.func.isRequired,
  /**
   * Whether `App`'s settings dialog is open. The shell does not render that dialog, so this is the
   * only way it can hear one has opened by a route that bypasses its own controls — the Map tab's
   * ⌂ — and take its own dialogs down in the same commit. See the `settingsWasOpen` edge.
   */
  settingsOpen: PropTypes.bool,
  onSignOut: PropTypes.func.isRequired,
  contentDisabled: PropTypes.bool,
  onShowOnMap: PropTypes.func,
  onEvaluationScoresChange: PropTypes.func,
  /** Lifts `briefing.seasonalFeatures` to App, which the map overlay reads. Optional-called, so
      every existing test renders without it. */
  onSeasonalFeaturesChange: PropTypes.func,
  locations: PropTypes.array,
  /** The Map pane. Absent means no Map tab — the tab and its content arrive together. */
  mapPane: PropTypes.node,
  /**
   * A tab selection asked for from outside the bar, as {@code {id, nonce}}. The nonce is what makes
   * it fire, so the same tab can be requested twice running. A request naming a tab this shell has
   * no pane for is ignored rather than obeyed.
   */
  tabRequest: PropTypes.shape({ id: PropTypes.string, nonce: PropTypes.number }),
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
   * `openMapTabFromPlan`, called by the shell's own `openMapTab(door)` wrapper (which closes the
   * popup/window sheet first and merges in the live lens values). Absent whenever there is nothing
   * to map (`App`'s own `hasForecastData` gate) — no door renders without it, mirroring
   * `onOpenFullMap`'s identical withholding rule on the overlay's own hatch.
   */
  onOpenMapTab: PropTypes.func,
  /**
   * The Operations pane. Absent means no Operations tab, and that is the admin gate in full: the
   * caller holds the role and withholds the pane, so nothing role-shaped reaches this component.
   */
  operationsPane: PropTypes.node,
  /**
   * The masthead's status pill, on the same terms as {@code operationsPane}: absent means no pill,
   * which is how the admin gate reaches this arm without a role crossing into it.
   */
  healthPill: PropTypes.node,
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
   * The tab this device opens on — {@code 'plan'} or {@code 'map'}, resolved once by
   * {@code App} via {@code utils/initialTab.js} (default-tab-by-device-plan.md §4.2). Absent means
   * Plan, the long-standing default every existing shell test still gets with no prop at all. A
   * STANDING PREFERENCE, not a one-time selection — see {@code activeTab}'s own Javadoc.
   */
  initialTab: PropTypes.oneOf(['plan', 'map']),
};
