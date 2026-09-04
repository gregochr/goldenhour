import React, { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import BrandLockup from './shared/BrandLockup.jsx';
import MastheadLight from './shared/MastheadLight.jsx';
import MastheadTickLine from './MastheadTickLine.jsx';
import WindowFirstLensBar from './WindowFirstLensBar.jsx';
import WindowFirstDoors from './WindowFirstDoors.jsx';
import WindowFirstComingUp from './WindowFirstComingUp.jsx';
import WindowPickDialog from './WindowPickDialog.jsx';
import WindowSpotSheet from './WindowSpotSheet.jsx';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { formatRelativeAge } from '../utils/relativeTime.js';
import { buildLocationTypeMap } from '../utils/locationTypes.js';
import { ANY_TIER_ID } from '../utils/reachLens.js';
import { sheetOffersMore } from '../utils/windowSpotBrowse.js';
import { originAction, scopeRegions } from '../utils/planOrigin.js';
import { buildTopicIndex, windowTopics } from '../utils/windowFirstTopics.js';
import { buildPlanConflict } from '../utils/planConflicts.js';
import { buildScoreIndex, buildSlotIndex, sheetSpotOf } from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/regionGloss.js';
import { deriveBadge } from '../utils/comingUpArrivals.js';
import { markComingUpSeen } from '../api/settingsApi.js';
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
 * removes the window on every route that exists: a chip, a spot card, a pick badge and {@code /}
 * are all reachable ONLY from an open popup, so the fetch has the reader's whole reading time to
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

/**
 * The point set a window with nothing scored gets — one frozen array rather than a fresh literal,
 * so a card whose window has no points does not get a new prop identity on every shell render.
 */
const EMPTY_POINTS = Object.freeze([]);

/** The design's frame: 1080px. */
const WRAP_MAX_WIDTH = '1080px';

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
 * <h2>Tab selection is deliberately not persisted</h2>
 *
 * <p>The arm persists two things — the reach lens and the rating floor — and both are settled
 * preferences. Which tab you last had open is not: the reader's question on opening the app is
 * almost always "what about tonight", and restoring a ninety-day almanac because they browsed it
 * yesterday answers a question they are not asking. It also spends the first paint on a fetch.
 * Plan resets on every visit, and the cost of being wrong is one click.
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
 * @param {?object} [props.homeCoords] {@code {lat, lon}}, or null with no postcode saved — reused
 *        by the heat strip's home marker and (at G3) the popup field's reach rings.
 */
export default function WindowFirstShell({
  onOpenSettings, onSignOut, contentDisabled, onShowOnMap, onEvaluationScoresChange,
  onSeasonalFeaturesChange, locations, mapPane, operationsPane, tabRequest, healthPill,
  light, onSetPostcode, mapColourScale = null, homeCoords = null, onTabChange = null,
  planLocationHandoff = null,
}) {
  const {
    heatStripCards, heatPointSets, heatSpots, reachById, regionSeries,
    windowCards, paneItems, loading, briefing, evaluationScores, scoresLoaded,
    scoreIndex, scoreRows, todayStr, reachLens, ratingLens, homePlace,
    origin, setOrigin, regions, effectiveReachById,
    comingUpLastSeenDate, setComingUpLastSeenAt,
  } = useWindowFirstBriefing();
  /**
   * The search dialog's open state, and the region it should be pre-filled with.
   *
   * <p>Two values in one, because "open with a query" and "open empty" are the same gesture from
   * two places: the chip and the {@code /} key open it empty, and the strip's beyond line opens it
   * on the first region beyond the planning area (the link P2 deferred to here). {@code null} is
   * closed; a string — possibly empty — is open.
   */
  const [searchSeed, setSearchSeed] = useState(null);
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
  const [activeTab, setActiveTab] = useState(TABS[0].id);
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
  const effectiveTab = tabs.some((t) => t.id === activeTab) ? activeTab : tabs[0].id;
  /**
   * The shell→App channel the full-frame Map tab needs (map-tab-v2-plan.md §3 P7's first owner).
   * `App` recasts its whole page as a flex column on the map tab (dropping `<main>`'s own padding
   * and giving every ancestor down to `.wf-body.wf-body--map` `flex: 1; min-height: 0` instead of
   * a computed height) — but `effectiveTab` is shell-internal state `App` has no other way to
   * read. Fired on mount too (not only on change), so `App` learns the OPENING tab rather than
   * starting from a guess and correcting one render late.
   */
  useEffect(() => { onTabChange?.(effectiveTab); }, [effectiveTab, onTabChange]);
  /** Panes mount on first selection and stay mounted; the panel ELEMENT is always present. */
  const [openedTabs, setOpenedTabs] = useState(() => new Set([TABS[0].id]));
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
   * session re-fetches settings to notice (the settings fetch is gated on
   * `homeSettingsVersion`, which only moves when the settings modal saves). Applying the echoed
   * value closes that gap the instant it would otherwise open. A FAILED write is left on the
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
   * <p>Every dialog this shell owns — the window popup, the drill-down sheet, the four-day sheet
   * and the pick — is rendered outside the pane and its state is independent of the tab, so without
   * this a reader who opened a window and then pressed Coming up would be left with a modal about a
   * Plan window floating over the almanac feed — and {@code useDialogFocus} is explicitly not a focus trap, so closing it would hand
   * focus back to a trigger that is no longer on screen. Arriving somewhere else ends the
   * browsing, which is the same rule the strip already applies to its peek before a map handoff.
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
   * The arm's root, and the element that hosts `--wf-lens-reserve` and `--wf-mast-h`.
   *
   * <p>Both are written imperatively by {@code useLensReserve} rather than through the `style` prop
   * below, and the two coexist because React updates a style object key by key: it never rewrites
   * `cssText`, so a custom property it does not know about survives every re-render. The
   * alternative — measuring into state and rendering it — puts a `setState` inside a
   * `ResizeObserver` callback for properties that affect no layout of their own.
   *
   * <p>The masthead's height comes BACK as a number as well, and that one is not a duplicate: the
   * stuck sentinel builds an {@code IntersectionObserver} {@code rootMargin} from it, which is a
   * JavaScript string rather than a stylesheet value. One measurement, two consumers.
   */
  const shellRef = useRef(null);
  const mastHeight = useLensReserve(shellRef);
  /**
   * Whether the lens bar has left its resting place, and the sentinel that answers it.
   *
   * <p>M3's chrome model: the masthead is sticky at the top and the bar sticks below it, so the bar
   * needs a treatment that says which of the two states it is in — without it, a bar overlapping
   * content it is scrolling over reads as part of that content. The design's own answer is a shadow
   * and a raised bottom border, and its own mechanism is a 1px sentinel above the bar.
   */
  const [lensSentinelRef, lensStuck] = useStuckSentinel(mastHeight);
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
   * The Map tab callout's "Open in Plan" handoff (map-tab-v2-plan.md §3 P9) — `openFullMapTab`'s
   * shape, in reverse, routed through {@code selectTab} rather than a bare {@code setActiveTab} for
   * the SAME reason every other entry into this dialog stack is: {@code selectTab} clears
   * {@code sheetSpot} (along with `openWindowKey`/`openPick`/`sheetKey`/`focusedRegion`) as part of
   * its own body, so the {@code setSheetSpot} call immediately below — in the SAME synchronous
   * effect, i.e. the SAME React batch — is the write that survives: two calls to one setter inside
   * one commit resolve to the LAST one, never the {@code null} `selectTab` made a line earlier. The
   * sheet therefore lands as the ONLY dialog layer, never stacked over a popup nobody asked to see.
   *
   * <p>Guarded by its own nonce and its own ref, mirroring the `tabRequest` effect immediately
   * above — the identical protection against acting on a STALE handoff twice, which here is the
   * §3 P9 test brief's own "hidden-pane" concern turned around: this shell's OWN Plan body is
   * `hidden` rather than unmounted while another tab is active (the same sticky-pane idiom the Map
   * pane itself relies on), so without a nonce guard a plain prop-identity check could refire on an
   * unrelated re-render while the Plan tab sits hidden behind whatever tab the reader is actually on.
   */
  const planHandoffNonce = planLocationHandoff?.nonce ?? null;
  const lastHandledPlanHandoff = useRef(planLocationHandoff?.nonce ?? null);
  useEffect(() => {
    if (planHandoffNonce == null || planHandoffNonce === lastHandledPlanHandoff.current) return;
    lastHandledPlanHandoff.current = planHandoffNonce;
    selectTab('plan');
    setSheetSpot({
      id: planLocationHandoff.id ?? null,
      name: planLocationHandoff.name ?? '',
      regionName: planLocationHandoff.regionName ?? null,
    });
    // The window the map was on, so the sheet opens ON it rather than on its own best — see
    // `MapView.handleOpenLocationInPlan`'s note for the defect this closes. Null for every other
    // entry point (search, a field chip, a spot card), which keeps their seeding exactly as it was.
    setSheetWindowKey(planLocationHandoff.date && planLocationHandoff.targetType
      ? `${planLocationHandoff.date}:${planLocationHandoff.targetType}`
      : null);
    // Same focus rule as the `tabRequest` effect above: an external ask arrives with focus wherever
    // the caller (a callout button that is about to be hidden along with its whole panel) left it.
    const domId = tabDomId('plan');
    requestAnimationFrame(() => document.getElementById(domId)?.focus());
    // `selectTab`/`setSheetSpot` deliberately absent from the dependency list — both are rebuilt
    // every render, and the nonce is the trigger already in it.
  }, [planHandoffNonce, planLocationHandoff]);

  // ⚠️ A key whose card has gone stops rendering but is not released, and the effect that would
  // release it is a `setState` inside `useEffect` that `react-hooks/set-state-in-effect` rejects.
  // The residual is that a window which disappears and returns would re-show the dialog — and it is
  // left undefended deliberately, because the only way a key leaves `windowCards` is the past-event
  // filter or the travel-day set, and `isEventPast` is monotonic in time: an event that has passed
  // does not come back. Fighting the linter for a state the clock cannot produce is the wrong trade.
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
   * layer is not on top declines the key, so Escape takes exactly one layer per press — search, then
   * a sheet stacked over the popup, then the popup itself (plan-matrix §6 M2.5, and the bundle
   * README's own ordering).
   *
   * <p><b>SEARCH's rung went live at M3, and it is the reason this whole ordering exists.</b> It
   * was dormant through M2 — {@code /} was refused while <em>any</em> dialog was open, so search
   * could never sit over anything and the three {@code escapeEnabled} props below guarded a case
   * that could not arise. M3 anchors search to the masthead, which the popup is drawn over rather
   * than in, so {@code /} is now permitted while the window popup is open and refused over
   * everything else. Escape then takes exactly one layer per press: search, then a sheet stacked
   * over the popup, then the popup itself.
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
   * {@code /} opens search — the design's own shortcut, and the one keyboard affordance the chip
   * cannot advertise.
   *
   * <p>Guarded four ways, because a bare global {@code /} listener is a well-known way to make a
   * page hostile: it is ignored while the reader is in a field (input, textarea, select, or
   * anything {@code contenteditable} — the almanac has none but the settings modal is a sibling in
   * {@code App}); when a modifier is held, so browser and OS shortcuts are untouched; while a
   * dialog this shell does not own is open; and while any of its own dialogs EXCEPT the window
   * popup is. That last exclusion is M3's, and the guard below spells out why the popup is the one
   * stack this arm supports. It is also Plan-only: on Coming up or the Map tab there is no window
   * list to search into, and a shortcut that opens a dialog about another tab is worse than none.
   */
  useEffect(() => {
    if (effectiveTab !== 'plan') return undefined;
    const onKeyDown = (event) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return;
      if (searchSeed != null) return;
      // ⚠️ The WINDOW POPUP is deliberately absent from this list, and everything else is in it.
      // Search is the topmost layer of the Escape order (M2.5) and M3 anchors it to the masthead —
      // a surface the popup is drawn over rather than inside — so `/` over an open popup is the
      // one stack this arm supports. Over a sheet, the pick dialog or the location sheet it stays
      // refused: those are already stacked on the popup, and a third layer has nowhere to go.
      if (stackedOverPopup) return;
      // ⚠️ And a dialog this shell does not own still refuses, which is a DIFFERENT question from
      // the flags above. `UserSettingsModal` is a SIBLING of the shell in `App` — `/` over an open
      // settings dialog stacked a second `aria-modal` overlay on it, with two document-level
      // Escape handlers and two interleaved focus restores. `Modal` renders in place rather than
      // through a portal, so every dialog this shell owns is a DESCENDANT of its root and every
      // one it does not is not: containment answers "is this mine" without naming any of them,
      // which is what lets the popup be excluded above without also excusing the settings modal.
      // It still covers the unclosable drive-times spinner, the worst one to land a dialog on.
      const root = shellRef.current;
      const foreign = Array.from(document.querySelectorAll('[role="dialog"]'))
        .some((node) => !root || !root.contains(node));
      if (foreign) return;
      // The shell is inert under a dead backend (`pointer-events: none`), so a keyboard shortcut
      // into it would be the one live control on a surface that says it is not.
      if (contentDisabled) return;
      const el = event.target;
      const tag = el?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return;
      event.preventDefault();
      setSearchSeed('');
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [effectiveTab, searchSeed, stackedOverPopup, contentDisabled]);

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
   * <p>Guarded the way {@code /} is, and for the same reasons plus one: a stacked sheet or search
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
      // `wf-shell` hosts `--wf-gutter`/`--wf-mast-h`/`--wf-lens-reserve` — the arm's shared
      // horizontal inset and the sticky-chrome measurements `useLensReserve` publishes. It carries
      // NO width constraint of its own (map-tab-v2-plan.md §3 P7's second full-frame owner): the
      // masthead and the tab bar stay wrapped at `WRAP_MAX_WIDTH` below on every tab, and — since
      // O-17 (bundle rev 2, owner decision 2026-09-03, reversing P7's width release) — the panel
      // region's own wrapper further down now applies that SAME `WRAP_MAX_WIDTH` on the Map tab
      // too, rather than releasing it.
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
      className={effectiveTab === 'map' ? 'wf-shell w-full flex-1 min-h-0 flex flex-col' : 'wf-shell w-full'}
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
                follow-on, not adopted). */}
            <button
              type="button"
              onClick={() => { openOverPopup(null); openWindow(null); onOpenSettings?.(); }}
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
          // ⚠️ The SAME guard the `/` shortcut carries, and M5 added it because the button did not.
          // Measured in a browser: from an open location sheet a keyboard reader reached this
          // control on the seventeenth Tab and opened search as a THIRD layer — and `Modal` gives
          // every dialog `fixed inset-0 z-50`, so with equal z-index paint order is DOM order and
          // the sheet, which renders after search, painted its scrim and its whole card OVER the
          // search panel. The reader typed into a box behind a dead, dimmed sheet. The shortcut's
          // own comment already settled the rule this restores — "those are already stacked on the
          // popup, and a third layer has nowhere to go" — so the button was simply bypassing it.
          onOpenSearch={() => { if (!stackedOverPopup) setSearchSeed(''); }}
          // ⚠️ Takes every dialog down first, since M4. The tick line keeps its tab stops while no
          // search panel is open, and `useDialogFocus` is not a trap — so a keyboard reader inside
          // an open location sheet can reach this button, and moving the origin under that sheet is
          // the case M4.3's close-then-move footer exists to rule out. One rule, every route.
          onGoHome={() => { openOverPopup(null); openWindow(null); setOrigin?.(null); }}
          onSetPostcode={onSetPostcode ?? onOpenSettings}
          // Out of the tab order for TWO reasons now, which is why the prop is no longer named for
          // one of them. The anchored search panel covers this row exactly (WCAG 2.4.11) — and a
          // layer stacked over the popup makes the search button refuse, so leaving it tabbable
          // would be a control with no visible effect, which plan §3 rule 14 bans outright.
          searchOpen={searchSeed != null || stackedOverPopup}
          // map-tab-v2-plan.md §3 P11: a per-tab STATE of the tick line, read off the SAME
          // `effectiveTab` every other map-only branch in this file already keys on (the full-frame
          // recast at `:1154`/`:1355`, the search/sheet gates below) — never a second "which tab"
          // test that could disagree with them.
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
        <div role="status" aria-live="polite" data-testid="window-first-conflict-slot">
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
            todayStr={todayStr}
            runAge={age}
            onOpenWindow={openWindow}
            origin={origin ?? null}
            homeCoords={homeCoords}
            onSearchRegion={(regionName) => { if (stackedOverPopup) return; setSearchSeed(regionName); }}
          />
        </Suspense>

        {!loading && paneItems.length === 0 && (
          <p
            data-testid="window-first-pane-empty"
            className="font-mono text-plex-text-muted"
            style={{ fontSize: '10.5px' }}
          >
            No forecast to show.
          </p>
        )}

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
          {openedTabs.has(tab.id) ? { mapPane, operationsPane }[tab.slot] : null}
        </div>
      ))}
      </div>

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
          // Declines Escape while search is over it — the same one-layer-per-press rule the popup
          // beneath it follows. Nothing else can stack on this one.
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
            // ⚠️ `openOverPopup(null)` as well, since M4. Search can now sit over a location sheet
            // that is itself over the popup, and moving the origin with that sheet still up is the
            // exact thing M4.3's close-then-move footer goes to trouble to prevent — the drive, the
            // base named beside it, the outside badge and every departure would all change under
            // the reader. One rule, every route.
            onPickRegion={(region) => {
              openOverPopup(null); openWindow(null); setOrigin?.(region);
            }}
            // ⚠️ And here, because otherwise the pick lands on a popup nobody can see: search
            // closes, `openWindowKey` moves, and the location sheet is still on top — so from the
            // reader's side choosing a window did nothing until the next Escape.
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
            // Closes FIRST, the rule the spot sheet already states: the map overlay is itself an
            // `aria-modal` dialog, and leaving this one mounted underneath puts two on the page
            // with two Escape listeners between them.
            // ⚠️ THE POPUP GOES TOO, and M4 is what made that necessary. Until this phase the sheet
            // could only be reached from search, which had already closed the popup; now it stacks
            // on a live one. `MapOverlay` is itself `aria-modal` with an unconditional document
            // Escape listener, and `stackedOverPopup` goes false the instant the sheet unmounts —
            // so a sheet-only close leaves the popup's own listener re-armed under the overlay and
            // one press closes two layers. Same rule, same line, as the drill-down sheet above.
            onShowOnMap={(date, targetType, name) => {
              openOverPopup(null);
              openWindow(null);
              onShowOnMap?.(date, targetType, name);
            }}
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
  /** The active scoreRamp mode, forwarded to the heat strip as its paint-repaint key. */
  mapColourScale: PropTypes.oneOf(['temp', 'verdict']),
  /**
   * The shell→App channel for the full-frame Map tab (map-tab-v2-plan.md §3 P7). Fired with the
   * effective tab id on mount and on every change — `App` cannot otherwise learn which tab is
   * active (`effectiveTab` is shell-internal), and it needs to know in order to drop `<main>`'s
   * own padding for the Map tab.
   */
  onTabChange: PropTypes.func,
  onOpenSettings: PropTypes.func.isRequired,
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
  /** The Map tab callout's "Open in Plan" handoff (map-tab-v2-plan.md §3 P9) — `App.jsx`'s
   * `openLocationInPlan`, `openFullMapTab`'s shape in reverse. */
  planLocationHandoff: PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    name: PropTypes.string,
    regionName: PropTypes.string,
    nonce: PropTypes.number,
  }),
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
  /** Opens settings on the postcode field for the band's nudge; falls back to onOpenSettings. */
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
};
