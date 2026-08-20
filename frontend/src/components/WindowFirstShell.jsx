import React, { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import BrandLockup from './shared/BrandLockup.jsx';
import MastheadLight from './shared/MastheadLight.jsx';
import PlanOriginChip from './PlanOriginChip.jsx';
import WindowFirstLensBar from './WindowFirstLensBar.jsx';
import WindowFirstWindowCard from './WindowFirstWindowCard.jsx';
import WindowFirstPromotedStrip from './WindowFirstPromotedStrip.jsx';
import WindowFirstDoors from './WindowFirstDoors.jsx';
import WindowFirstComingUp from './WindowFirstComingUp.jsx';
import WindowAwayRow from './WindowAwayRow.jsx';
import WindowPickDialog from './WindowPickDialog.jsx';
import WindowSpotSheet from './WindowSpotSheet.jsx';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { formatRelativeAge } from '../utils/relativeTime.js';
import { buildLocationTypeMap } from '../utils/locationTypes.js';
import { windowCardDomId } from '../utils/windowFirstCards.js';
import { ANY_TIER_ID } from '../utils/reachLens.js';
import { orderPaneItems, PLAN_ORDER_BEST } from '../utils/windowFirstOrder.js';
import { sheetOffersMore } from '../utils/windowSpotBrowse.js';
import { scopeRegions } from '../utils/planOrigin.js';
import { buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';
import useComingUpFeed from '../hooks/useComingUpFeed.js';
import useLensReserve from '../hooks/useLensReserve.js';

/**
 * The heat strip, behind a lazy boundary — and the boundary is about the OTHER arm.
 *
 * <p>{@code App} imports this shell STATICALLY (unlike `MapView`, `WindowFirstMapPane`,
 * `MapOverlay` and `ManageView`, which are all `lazy()`), and `usePlanLayout` still defaults to
 * `v1`. So a static import here puts the strip — and through it `heatField.js`'s `d3-geo` and
 * `topojson-client` — in the entry chunk for <b>every user</b>, including the 100% who are on the
 * v1 arm and will never see it. Measured: a 24.14 KB / 9.19 KB-gzip `geo` chunk, fetched
 * render-blocking on first paint, for an arm nobody currently opens. The plan's scope guard says
 * v2 must not change v1, and a first-paint fetch is a change to v1.
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
 * The four-day location sheet, on the same terms — a dialog, mounted only while open (P8).
 *
 * <p>Lazy for the reason search is, and for one more: it pulls in {@code locationSheet.js} and the
 * slot-time index, neither of which any other surface reads. A reader who never searches for a
 * place never downloads them.
 */
const LocationFourDaySheet = lazy(() => import('./LocationFourDaySheet.jsx'));

/**
 * The point set a window with nothing scored gets — one frozen array rather than a fresh literal,
 * so a card whose window has no points does not get a new prop identity on every shell render.
 */
const EMPTY_POINTS = Object.freeze([]);

/** The design's frame: 1080px, against the v1 arm's 896px `max-w-4xl`. */
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
 * <h2>It renders its own masthead because it replaces the app's</h2>
 *
 * <p>{@code App} suppresses its {@code <header>} for this arm, so everything that header carried
 * has to exist here or it is simply gone: the wordmark, the settings cog and Sign out. The two
 * buttons take the same handlers, lifted rather than duplicated — this component owns no auth or
 * modal state of its own.
 *
 * <p><b>Not the design's masthead brand, deliberately.</b> The mock draws a conic-gradient disc and
 * a 20px sans wordmark. This app's identity is {@link BrandLockup} — a film-perforation spine and a
 * serif wordmark — and that component's own Javadoc records why the previous {@code logo.png} went:
 * it "belonged to no part of the Kodachrome Field Guide system the rest of the app uses". Drawing
 * the disc would reintroduce exactly that, as the only mark of its kind in the product. Worse, the
 * flag runs both layouts at once so they can be judged against the same night's data (plan §4), and
 * a different wordmark in one arm makes every comparison a brand comparison too. The {@code compact}
 * variant exists for this masthead's height budget. Recorded in plan §7.
 *
 * <h2>The status pill, and why it is a slot rather than a component</h2>
 *
 * <p>The design shows {@code ● UP v2.17.7} unconditionally, and this arm shipped without it: build
 * version and service health are not a pilot user's business (plan §7). That reasoning was right
 * about the <em>pilot user</em> and wrong about the admin, who had the control in the v1 header and
 * simply lost it on switching arms — the first thing anyone running the app notices is that they can
 * no longer see whether the backend is up.
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
 * <p>This is the first roving-tabindex implementation in the codebase; there was nothing to copy.
 * {@code ViewToggle} is a segmented control of plain buttons using {@code aria-current}, and
 * {@code ManageView}'s tabs carry no roles at all.
 *
 * <h2>Tab selection is deliberately not persisted</h2>
 *
 * <p>The arm persists two things — the layout flag and the rating floor — and both are settled
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
 * context. The Map pane renders its own {@code DateStrip} over the full forecast horizon, and
 * every Coming-up row carries its dates. What replaces the rail is {@link WindowFirstHeatStrip},
 * six window thumbnails under the lens bar — the rail's job done at the window list's own grain,
 * with space shown inside each window instead of named beside it. Stacking both would be two
 * summaries of one forecast at two grains, costing roughly two screens of chrome before the first
 * window row.
 *
 * <p>The strip takes the {@code contentDisabled} greying because it is forecast data and data from
 * a DOWN backend is exactly what that treatment exists to mark — it is inside the pane, which
 * already carries it. The tab bar does not: it is navigation, and so is the masthead.
 *
 * <h2>The rail footer's two halves, both of them now — and it OUTLIVED the rail</h2>
 *
 * <p>The left one is the reach lens's prompt, and it lands here rather than on the bar itself for
 * the reason plan §2.5 gives: the bar is {@code position: sticky} and must not be suppressed for a
 * user with no home, so the thing that <em>varies</em> per user goes in the slot the design already
 * reserves for it. {@code Home · <place>} when one is set, "Home not set" when the settings response
 * says there is none, and <b>nothing at all</b> while that is still unknown — telling a user who has
 * a home that they have not set one, on the strength of a dropped request, is worse than silence.
 * "Edit reach" opens the same settings modal the cog does, which is where a postcode is entered.
 *
 * <p>The right half is {@code generatedAt} formatted on the client (§2.8 — a server-rendered
 * relative string would mutate the ETagged body on every request) through the shared
 * {@code formatRelativeAge}, which already knows the instant is UTC. The design's {@code by Sonnet}
 * is dropped: the model name is admin-only today and is not a pilot user's business (§7). Its
 * "· reach set per day" is dropped too — the bar's own "today only" pill and its named reset state
 * that policy exactly when it applies, and §2.7's rule against marking one fact twice holds here as
 * well as it does for confidence.
 *
 * <p>The footer's ink moved from muted to secondary in the same change. Measured on the running
 * app: at 10px on {@code --color-plex-bg} muted is <b>3.55:1</b> and fails AA, secondary is
 * <b>7.09:1</b>. This is the fifth time the redesign has had to make that correction; leaving one
 * span of the row on the old tone to keep the diff smaller would have put two greys in one line
 * for no reason.
 *
 * <h2>The lens bar sits between the tab rule and the pane, and is never dimmed</h2>
 *
 * <p>Where the design puts it, and outside the {@code contentDisabled} treatment on purpose. The
 * lens is a pure client-side filter over data already in memory, so it keeps working when the
 * backend does not — and {@code pointer-events: none} on a sticky bar would make a live control
 * look broken to say nothing true. The tab bar and the masthead are excluded for the same reason.
 *
 * @param {object}   props
 * @param {function} props.onExit restores the v1 layout. It does not change the selected tab, so a
 *        user who switched into v2 from the Map tab returns to the Map tab — hence the label says
 *        "Plan", the layout, and not "Plan tab".
 * @param {function} props.onOpenSettings opens the shared settings modal, which owns the flag
 *        toggle — so this is the route back that survives once the temporary exit button goes.
 * <h2>The pane renders items, not cards</h2>
 *
 * <p>An away day's windows are not drawn — the pipeline skips evaluation on them, so a card would
 * read "Poor" under a rail tile reading "Not forecast" — but they still spend one of the six event
 * slots, so simply omitting them left the pane's date order skipping a day with no account of it.
 * {@code buildPaneItems} folds the two back into one ordered list; this component only chooses which
 * component draws which item.
 *
 * <h2>Collapse state lives here because the default is a fact about the list</h2>
 *
 * <p>"The first card is open" is not something a card can evaluate about itself. What is stored is
 * only what the reader has <b>changed</b>, so the rule keeps applying as the list moves under it —
 * see {@code isCardOpen}.
 *
 * @param {function} props.onSignOut ends the session; the same handler the v1 header uses.
 * @param {Array} [props.locations] enabled locations. The regional-planner door needs its id→name
 *        and name→type joins; the drill-down needs the same name→type join for its type control.
 *        Not fetched by this arm's provider: {@code App} already holds them for both arms, and a
 *        second request for a list the page has would be waste.
 * @param {boolean}  [props.contentDisabled] greys the pane when the backend is DOWN.
 *
 *        <p><b>The pane, never the chrome.</b> In the v1 arm the header sits OUTSIDE the element
 *        carrying that treatment, so a DOWN backend has never been able to disable Settings or
 *        Sign out. Here the masthead is inside the shell, so gating the whole thing would take the
 *        cog, Sign out and the exit hatch with it — leaving a user staring at a greyed page with
 *        no route anywhere, at exactly the moment they most need one.
 * @param {object|null} [props.light] today's light at the reader's home, for the masthead's light
 *        rule. Resolved by {@code App}, not here, so the shell stays a render layer and every test
 *        can put the band in any of its three states. {@code undefined} is "not yet answered" and
 *        {@code null} is "answered, no home saved" — see {@link MastheadLight}.
 * @param {function} [props.onSetPostcode] opens settings on the home-postcode field, for the
 *        band's nudge. Defaults to {@code onOpenSettings}, so the nudge can never be a dead end.
 */
export default function WindowFirstShell({
  onExit, onOpenSettings, onSignOut, contentDisabled, onShowOnMap, onEvaluationScoresChange,
  onSeasonalFeaturesChange, locations, mapPane, operationsPane, tabRequest, healthPill,
  light, onSetPostcode,
}) {
  const {
    heatStripCards, heatPointSets, heatSpots, reachById, regionSeries,
    windowCards, paneItems, promotedStrip, loading, briefing, evaluationScores, scoresLoaded,
    scoreIndex, scoreRows, todayStr, reachLens, ratingLens, orderLens, homePlace,
    origin, setOrigin, regions, effectiveReachById,
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
  const comingUp = useComingUpFeed(effectiveTab === 'coming-up', todayStr);
  /**
   * Selects a tab, and takes any dialog down with it.
   *
   * <p>The drill-down sheet and the pick dialog are rendered outside the pane and their state is
   * independent of the tab, so without this a reader who opened a window's spot list and then
   * pressed Coming up would be left with a modal about a Plan window floating over the almanac
   * feed — and {@code useDialogFocus} is explicitly not a focus trap, so closing it would hand
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
   * The arm's root, and the element that hosts `--wf-lens-reserve`.
   *
   * <p>The variable is written imperatively by {@code useLensReserve} rather than through the
   * `style` prop below, and the two coexist because React updates a style object key by key: it
   * never rewrites `cssText`, so a custom property it does not know about survives every re-render.
   * The alternative — measuring into state and rendering it — puts a `setState` inside a
   * `ResizeObserver` callback for a property that affects no layout, which is a loop waiting for
   * someone to add a second use.
   */
  const shellRef = useRef(null);
  useLensReserve(shellRef);
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
  // `tabRequest` and never clears it, and it outlives this component — so with a null seed, leaving
  // the arm and coming back replayed the last request and landed the reader on the Map tab when
  // they had asked for the Plan layout. That flip is the pilot's core comparison gesture, and it
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
   * <p>{@code App} already holds {@code locations} for both arms — P9 drilled it here for the
   * regional door — so this costs no request. The join itself lives in {@code locationTypes.js}
   * because the regional planner builds the same one from the same prop, and two copies of a join
   * is how the five copies that module replaced started.
   */
  const typesByName = useMemo(() => buildLocationTypeMap(locations), [locations]);
  // Only the cards the reader has TOGGLED. The default is not seeded into this map, so it stays a
  // rule rather than a snapshot: a poll that adds tomorrow's sunrise, or an away day that removes a
  // card, re-evaluates "the first card is open" without disturbing anything the reader chose. A
  // seeded set would freeze yesterday's answer and would have to be reconciled on every poll.
  const [cardOverrides, setCardOverrides] = useState(() => new Map());
  // The toggle is written against the EFFECTIVE state, not against the map's own default. Flipping
  // `map.get(key) ?? false` would make the first click on the open lead card set it to open —
  // a control that does nothing the one time it is most likely to be pressed.
  /**
   * The region each open row is drilled into — the {@code cardOverrides} pattern, one map along.
   *
   * <p>Per card rather than page-wide, and that is the design's own call: the lens axes are durable
   * settings a reader carries between windows, while "show me Northumberland in tomorrow's sunrise"
   * is a question about one window and answering it for all six would silently narrow five cards a
   * reader never touched. Cleared on collapse, in {@code toggleCard} below.
   *
   * <p><b>Nothing prunes it when the window list changes, and nothing needs to.</b> A poll can add
   * tomorrow's sunrise or retire a window that has passed, leaving an entry whose card is gone — but
   * the key is {@code date:targetType} with an absolute date, so it can only ever be read again by
   * the same window on the same day, which is the row that set it. The map is bounded by the windows
   * one session renders, which is a handful. A reconciling effect here would be a {@code setState}
   * inside {@code useEffect} buying nothing but a lint suppression.
   */
  const [regionByCard, setRegionByCard] = useState(() => new Map());
  const selectRegion = useCallback((key, regionName) => setRegionByCard((prev) => {
    if ((prev.get(key) ?? null) === (regionName ?? null)) return prev;
    const next = new Map(prev);
    if (regionName) next.set(key, regionName);
    else next.delete(key);
    return next;
  }), []);

  const toggleCard = (key, currentlyOpen) => {
    setCardOverrides((prev) => {
      const next = new Map(prev);
      next.set(key, !currentlyOpen);
      return next;
    });
    // Collapsing takes the drill-down with it: the band and the rail are inside the row, so a
    // selection surviving a collapse would silently gate the strip of a row the reader reopens
    // later with no visible cause — the filter's own UI having gone with the collapse.
    if (currentlyOpen) selectRegion(key, null);
  };
  /**
   * Lead-open, rest-collapsed — plan §5a, settled there on measured heights rather than taste.
   *
   * <p>The predicate is <b>the first card</b>, not {@code card.lead}, and the difference is the
   * whole of its value. {@code lead} is `index === 0 && date === todayStr`, so after today's last
   * window has passed there is no lead card at all — and a rule keyed on it would leave six
   * collapsed headers with nothing open, every evening, which is the state a reader checking
   * tomorrow's dawn is most often in. Where a lead card exists the two rules agree by construction,
   * because a lead card is index 0.
   */
  const defaultOpenKey = windowCards[0]?.key ?? null;
  const isCardOpen = (card) => cardOverrides.get(card.key) ?? (card.key === defaultOpenKey);

  /**
   * The pane's items in the order the bar asks for.
   *
   * <p>Applied HERE rather than in the provider, and the reason is one derivation that must keep
   * seeing date order: {@code buildPromotedStrip} reads {@code paneItems} to decide whether the
   * window it promotes is the very next item on the pane — the one case where its "Go to" control
   * would scroll to the element immediately beneath it and so is withheld. Ranking the list first
   * would make that test answer a question about a ranking rather than about adjacency.
   *
   * <p>Ordering is presentation of a list already built, which is why it is allowed to be a client
   * rule at all: nothing here derives a forecast quantity, it sorts on {@code topMeanRating}, a
   * field the backend served.
   */
  const orderedPaneItems = useMemo(
    () => orderPaneItems(paneItems, orderLens?.orderId),
    [paneItems, orderLens?.orderId],
  );
  /**
   * The promoted strip, with its adjacency answered against the order actually RENDERED.
   *
   * <p>{@code buildPromotedStrip} sets {@code adjacent} from the date-ordered list, and the strip
   * withholds its "Go to" control when it is true — because the control would scroll to the element
   * directly beneath it, which §6 bans. Under {@code Order · Best} the pane no longer renders that
   * order, so the flag inverts in both directions: a promoted window that is chronologically first
   * but ranks sixth loses its only route into the list, and one that is chronologically fourth but
   * ranks first gets a button that scrolls one element down. Recomputed here rather than in the
   * provider, because the provider is where the DATE order has to survive — that is what
   * {@code buildPromotedStrip}'s own rarity tie-break reads.
   */
  const renderedStrip = useMemo(() => {
    if (!promotedStrip) return null;
    const adjacent = orderedPaneItems[0]?.card?.key === promotedStrip.windowKey;
    return adjacent === promotedStrip.adjacent ? promotedStrip : { ...promotedStrip, adjacent };
  }, [promotedStrip, orderedPaneItems]);
  /** Whether the pane has an away row at all — the note's second clause is about those rows. */
  const hasAwayRow = orderedPaneItems.some((item) => item.kind === 'away');
  /**
   * Which windows have their card open, for the strip's own selected treatment.
   *
   * <p>Derived from the SAME predicate the cards are drawn with rather than from
   * {@code cardOverrides}, which holds only what the reader has changed — a strip keyed on the
   * overrides alone would leave the lead card's thumbnail unmarked on first paint, which is the
   * one card that is always open.
   */
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

  const openWindowKeys = useMemo(
    () => new Set(windowCards.filter((card) => isCardOpen(card)).map((card) => card.key)),
    // `isCardOpen` is rebuilt every render and closes over both of these; listing it instead would
    // re-run this on every render with nothing gained.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [windowCards, cardOverrides, defaultOpenKey],
  );

  /**
   * The promoted strip's route into the list: open the window it names, and put the reader on it.
   *
   * <p>It writes the override to `true` rather than toggling, because the strip's control says "Go
   * to" and never "close" — a toggle would collapse the card for a reader who pressed it twice, or
   * who pressed it on a card the lead-open default had already opened.
   *
   * <p>Synchronous rather than deferred a frame, and that is deliberate: `block: 'start'` scrolls to
   * the card's TOP edge, which does not move when the card below it expands, so there is nothing to
   * wait for. Focus lands on that card's own expander — the control the reader would reach for next,
   * and the one whose accessible name repeats the window they just asked for.
   *
   * <p>Both DOM calls are optional-CALLED, not merely optional-chained: jsdom implements no layout
   * and provides no `scrollIntoView`, and the unguarded form throws on every press while the suite
   * still reports green — the exact trap the tab bar's own arrow handling documents a few lines up.
   */
  const revealWindow = useCallback((key) => {
    setCardOverrides((prev) => {
      const next = new Map(prev);
      next.set(key, true);
      return next;
    });
    const node = document.getElementById(windowCardDomId(key));
    node?.scrollIntoView?.({ block: 'start' });
    node?.querySelector('[data-testid="window-card-expander"]')?.focus?.();
  }, []);

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
   * The region layer's inputs, built once per card and STABLE while nothing they depend on moves.
   *
   * <p>⚠️ <b>An inline object literal here repaints every open row's canvas on every shell
   * render.</b> The chain is five links long and each one is load-bearing: a fresh {@code field}
   * makes the card's {@code regionRows} memo a no-op, which makes {@code regionNames} a fresh array,
   * which makes the map's {@code paint} callback a fresh function, which is a dependency of
   * {@link useHeatCanvas}'s paint effect — so a coastline fill, a kernel field at grid 6, a blurred
   * composite and a second path stroke all re-run. The shell re-renders on every card toggle, every
   * region selection, every sheet or dialog open, every lens change and every poll, so <em>opening
   * one row repainted every other open row's map</em>. §5 invariant 4 is the written rule
   * ("nothing derived inside a render loop"), and {@link useHeatCanvas}'s own parameter contract
   * says the callback must be memoised — which it cannot be while its inputs churn.
   *
   * <p><b>The invariant is that every value INSIDE the object is referentially stable</b>, not that
   * the object itself never changes. A region selection does rebuild this map — six small object
   * literals — but {@code spots}, {@code points} (read straight out of {@code heatPointSets}),
   * {@code windows}, {@code series}, {@code reachById} and {@link fieldLens} all keep their
   * identity, and the consumers depend on those rather than on the container: the card's row memo
   * lists the fields, {@code WindowRowRegionLayer} memoises {@code regionNames}, and the map's
   * {@code paint} lists {@code points}/{@code fitTo}/{@code focusRegion}. So selecting a region in
   * one row repaints that row's canvas and leaves the other five alone.
   */
  const fieldByKey = useMemo(() => {
    const byKey = new Map();
    if (heatSpots.length === 0) return byKey;
    for (const card of windowCards) {
      byKey.set(card.key, {
        eventSummary: eventSummariesByKey.get(card.key) ?? null,
        spots: heatSpots,
        points: heatPointSets.get(card.key) || EMPTY_POINTS,
        // The strip's own descriptors, so the band's six dots and the six thumbnails above name one
        // set of windows in one order — chronological, under both Order settings.
        windows: heatStripCards,
        series: regionSeries,
        reachById,
        lens: fieldLens,
        onSelectRegion: (name) => selectRegion(card.key, name),
        // The strip's own route into a row, reused verbatim: opening and scrolling is one behaviour
        // and a second copy of it would drift.
        onJumpWindow: revealWindow,
        // ⚠️ FORCED NULL under an away origin, and that is a defect fix rather than tidiness. The
        // per-card selection is not cleared when the origin moves (deliberately — it is restored
        // on the way home), and away the rail that would clear it is withheld. Left live it filters
        // an already-scoped strip to a region the reader has scoped out, prints "Nothing in X for
        // this window" under a chip naming somewhere else, and leaves the band's `Show all regions
        // ×` as the only control that could undo it — a control whose handler focuses the rail cell
        // this scope has just unmounted, dropping focus to `<body>`.
        selectedRegion: origin ? null : (regionByCard.get(card.key) ?? null),
        // The origin has already answered "which region" for the whole page, so the row's own rail
        // has nothing left to choose (plan §4.8). Carried on the field object rather than as a
        // second prop for the reason every other field on it is: the layer takes one object, and a
        // parallel prop would be a second thing to keep in step.
        singleRegionScope: Boolean(origin),
        origin: origin ?? null,
      });
    }
    return byKey;
  }, [windowCards, heatSpots, heatPointSets, heatStripCards, regionSeries, reachById,
    eventSummariesByKey, fieldLens, regionByCard, selectRegion, revealWindow, origin]);

  // Lifted to App for the map overlay, exactly as DailyBriefing does it in the v1 arm. Without this
  // a tile handed to the map opens an overlay with no narrative over a map that has filtered out
  // every unrated pin — see the provider's note on why this arm fetches them at all.
  useEffect(() => {
    onEvaluationScoresChange?.(evaluationScores);
  }, [evaluationScores, onEvaluationScoresChange]);

  // The same lift for the seasonal features, and for a reason the sibling above does not have: this
  // one was written by the v1 arm ONLY, so the value in App depended on whether the session had ever
  // rendered v1. The overlay map's Bluebell chip is gated on it, so the same night's data drew two
  // different maps depending on flip history — which is the class of thing that produces a bug
  // report nobody can reproduce. `briefing?.seasonalFeatures` rather than `briefing`: the provider
  // replaces that object on every poll and every window focus, and depending on the parent would
  // re-fire this on each one.
  useEffect(() => {
    onSeasonalFeaturesChange?.(briefing?.seasonalFeatures ?? []);
  }, [briefing?.seasonalFeatures, onSeasonalFeaturesChange]);

  /**
   * True while a dialog is over the pane — the spot sheet, the pick, search or the location sheet.
   *
   * <p>It suppresses the spot peek, and the pick dialog is included deliberately rather than only
   * the sheet this phase adds. Both are {@code Modal}s, so both render inside Tailwind's
   * {@code z-50} while {@code .wf-peek} is portalled to the body at {@code z-index: 60}; and
   * {@code useDialogFocus} is explicitly not a focus trap, so from either one a keyboard user can
   * Tab back onto a spot card behind the backdrop and paint a hover panel over the dialog. Fixing
   * one and not the other would leave the same defect on the surface that has had it longer.
   */
  const modalOpen = sheetCard != null || openPick != null || searchSeed != null
    || sheetSpot != null;
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
   * The sheet's ratings, built from the RAW rows rather than taken from {@code scoreIndex}.
   *
   * <p>The provider's index is keyed on {@code date|targetType|locationName} alone; this one joins
   * id-first, like every other join in the arm and like {@code buildSlotIndex} beside it. The
   * provider's own note asked for exactly this and named P8 while doing so — the first cut ignored
   * it and an adversarial review caught the consequence: a renamed location timed correctly and
   * rated as unscored, under a heat field that still painted its star.
   */
  const sheetScoreIndex = useMemo(
    () => (sheetSpot ? buildScoreIndex(scoreRows) : null),
    [sheetSpot, scoreRows],
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
   */
  const scopedRegionNames = useMemo(
    () => (sheetSpot ? scopeRegions(heatSpots, reachById, origin) : null),
    [sheetSpot, heatSpots, reachById, origin],
  );
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
   * <p>Guarded three ways, because a bare global {@code /} listener is a well-known way to make a
   * page hostile: it is ignored while any dialog is open, while the reader is in a field (input,
   * textarea, select, or anything {@code contenteditable} — the almanac has none but the settings
   * modal is a sibling in {@code App}), and when a modifier is held, so browser and OS shortcuts
   * are untouched. It is also Plan-only: on Coming up or the Map tab there is no window list to
   * search into, and a shortcut that opens a dialog about another tab is worse than none.
   */
  useEffect(() => {
    if (effectiveTab !== 'plan') return undefined;
    const onKeyDown = (event) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return;
      if (searchSeed != null || modalOpen) return;
      // ⚠️ Not `modalOpen` alone. That flag knows only this shell's three dialogs, and
      // `UserSettingsModal` is a SIBLING of the shell in `App` — so `/` over an open settings
      // dialog stacked a second `aria-modal` overlay on it, with two document-level Escape
      // handlers and two interleaved focus restores. Asking the document is the only test that
      // covers a dialog this component cannot see. It also covers the unclosable drive-times
      // spinner, which is the worst one to land a dialog on top of.
      if (document.querySelector('[role="dialog"]')) return;
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
  }, [effectiveTab, searchSeed, modalOpen, contentDisabled]);

  /**
   * A window's own way out of an empty strip — the bar's controls, reached from the card.
   *
   * <p>The action is a descriptor the card renders and hands back rather than a pair of callbacks
   * per axis, so a third axis would not widen this component's prop surface. It moves the
   * <b>page-wide</b> lens, which is exactly what the reader asked for: the alternative is a
   * per-window override, and a filter that means something different on each of six cards is a
   * filter nobody can read off a sticky bar.
   *
   * <p>Nothing here is gated. A reach action only exists when a wider tier would fill the card, and
   * a LITE reader is pinned to "Any" — so {@code buildLensEmptyState} never offers them one, with no
   * role anywhere in the path.
   */
  const handleLoosen = (action, cardKey) => {
    if (action?.kind === 'reach') reachLens?.selectTier(action.id);
    else if (action?.kind === 'rating') ratingLens?.selectFloor(action.id);
    // The third way out of an empty card, and the only one that is not a lens: an away scope the
    // reader chose. It refills the card the same way the other two do — the home pool is a
    // superset of any one region's — so the focus move below applies unchanged.
    else if (action?.kind === 'origin') setOrigin?.(null);
    else return;
    // The button that was just pressed no longer exists — every offered action is one that refills
    // the card, so the empty state it sat in is replaced by a spot strip on this commit. Without
    // this a keyboard reader is dropped at `<body>` having just asked to be shown something, which
    // is the defect `WindowFirstComingUp` already documents one component away. The expander is
    // where the strip's own reveal puts focus too, and its accessible name repeats the window.
    // Deferred a frame because the strip is rendering for the first time on this very commit, and
    // optional-CALLED because jsdom implements no layout and provides no `scrollIntoView`.
    if (cardKey == null) return;
    const domId = windowCardDomId(cardKey);
    requestAnimationFrame(() => {
      document.getElementById(domId)
        ?.querySelector('[data-testid="window-card-expander"]')?.focus?.();
    });
  };
  return (
    <div
      ref={shellRef}
      data-testid="window-first-shell"
      // `wf-shell` hosts `--wf-gutter`, the arm's horizontal inset. Seven elements below shared the
      // literal 18px and each would have needed its own phone override; declaring it once here
      // makes the phone gutter a single declaration and makes a partial migration — the failure
      // where half the chrome shifts and half does not — impossible rather than merely unlikely.
      // The max width stays inline: it is a JS constant and no media query touches it.
      className="wf-shell mx-auto w-full"
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
            {/* Leftmost of the three, which is where the v1 header put it relative to the same two
                buttons — and it is a reading, not a control the reader operates to get somewhere,
                so it sits before the pair rather than between them. Absent for everyone but an
                admin, and the gap collapses on its own. */}
            {healthPill}
            <button
              type="button"
              onClick={onOpenSettings}
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
        <MastheadLight light={light} onSetPostcode={onSetPostcode ?? onOpenSettings} />
      </div>

      {/* The rail footer, which OUTLIVES the rail (plan §1.1: "the rail *footer* is a separate
          element and stays — only the tile row goes"). It sits outside any greyed region, and that
          is a fix rather than a placement preference.
          Everything in this row survives a dead backend: the home is a per-user setting, "Edit
          reach" is the only route to fixing an empty lens — the same trap P4a fixed for the
          masthead and this file fixed again for the exit button — and the forecast's AGE is the
          one fact that becomes more useful when the backend is down, not less. Nothing here is
          forecast content, so nothing here takes the treatment that marks it. */}
      <div
        data-testid="window-first-railfoot"
        className="wf-railfoot flex items-center font-mono text-plex-text-secondary"
      >
        {/* The origin chip REPLACES the `Home · <place>` line this slot used to hold (plan §4.8):
            it states the same fact and can be acted on, where the line could only be read. The
            home-not-set prompt stays beside it rather than inside it — the chip is about the frame
            of reference and the prompt is about a missing setting, and folding the second into the
            first would make the control that moves the origin also the one that nags. */}
        <PlanOriginChip
          origin={origin ?? null}
          homePlace={homePlace || undefined}
          onOpenSearch={() => setSearchSeed('')}
          onGoHome={() => setOrigin?.(null)}
        />
        {/* Undefined is "we do not know yet", and it renders nothing. Only a settings response
            that came back without a home says so out loud — and only at home, because an away
            reader is not planning from a postcode and the prompt would be about nothing they can
            see. */}
        {!origin && homePlace === null && (
          <span data-testid="window-first-home">Home not set</span>
        )}
        <button
          type="button"
          onClick={onOpenSettings}
          data-testid="window-first-edit-reach"
          className="ml-auto hover:text-plex-text transition-colors"
          style={{ textDecoration: 'underline', textUnderlineOffset: '2px' }}
        >
          Edit reach
        </button>
        {age && <span data-testid="window-first-age">forecast {age}</span>}
      </div>

      <div
        data-testid="window-first-tabs"
        role="tablist"
        aria-label="Plan sections"
        className="wf-tabs flex gap-1.5"
      >
        {tabs.map((tab, index) => {
          const selected = tab.id === effectiveTab;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              id={tabDomId(tab.id)}
              aria-selected={selected}
              aria-controls={panelDomId(tab.id)}
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
            </button>
          );
        })}
      </div>
      <div data-testid="window-first-tabrule" className="h-px bg-plex-border" />

      {/* Plan only, and that is the design's own heading for it ("Lens bar (Plan only)"). The bar
          filters SPOTS; the almanac feed has none, so on Coming up it would gate nothing — §6's
          "no control gates on data that does not exist", and its own footer would read "0 spots
          across 5 windows" over a pane containing neither. It is unmounted rather than hidden so
          the sticky bar cannot take a scroll position with it. */}
      {effectiveTab === 'plan' && reachLens && ratingLens && orderLens && (
        <WindowFirstLensBar
          lens={reachLens}
          ratingLens={ratingLens}
          orderLens={orderLens}
          spotCount={windowCards.reduce((total, card) => total + card.spots.length, 0)}
          // The rating floor's own denominator — what reach left for it to choose from. Summed here
          // rather than derived in the bar for the reason `spotCount` already is: the counts have to
          // be the lengths of the arrays that were drawn, and only this component can see all six.
          reachedCount={windowCards.reduce((total, card) => total + (card.reachedTotal ?? 0), 0)}
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
        todayStr={todayStr}
        onRetry={comingUp.retry}
      />

      {/* Hidden rather than unmounted, and the reason is a request storm rather than tidiness:
          `WindowFirstDoors` mounts `WindowFirstRegionalPanel`, which fires an astro request per
          visible date on mount. Unmounting the pane on every tab change would re-fire all of them
          on every change back — which is exactly what `ManageView` does to its sub-views, and the
          behaviour not to copy. Keeping it mounted also keeps whatever the reader had open open.

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
        {/* Above every item, and the only element on this pane that is not in date order. That is
            tolerable because it is an INDEX into the list rather than an item in it: it names its
            window and carries the control that opens it. `buildPromotedStrip` returns one descriptor
            or null, so the "at most one" cap is arithmetic here rather than a rule this file has to
            keep. Inside the greyed region — it is forecast content, which is what that treatment
            marks — and inside `.wf-body`, so it takes the arm's gutter and the pane's 10px gap
            without a margin of its own. */}
        {/* Directly under the lens bar and ABOVE the promoted strip — two different things that
            both exist (plan §4.3). This one is the index into the window list; the promoted strip
            names one coincidence and keeps its place at the head of the pane items. Inside the
            greyed region because it is forecast content, which is what that treatment marks. */}
        <Suspense fallback={null}>
          <WindowFirstHeatStrip
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
            onOpenWindow={revealWindow}
            origin={origin ?? null}
            onSearchRegion={(regionName) => setSearchSeed(regionName)}
          />
        </Suspense>

        {renderedStrip && (
          <WindowFirstPromotedStrip strip={renderedStrip} onOpenWindow={revealWindow} />
        )}

        {/* The open row's region layer, assembled once and handed to every card — each takes its own
            window's slice by key. Withheld wholesale when the catalogue is empty (a scores fetch
            that failed, a session with no roster), for the same reason the strip withdraws: a field
            map of nothing is a picture claiming there is nothing there. The rows, the tide row and
            the spot strip are unaffected either way. */}
        {orderedPaneItems.map((item) => (item.kind === 'away' ? (
          <WindowAwayRow
            key={item.key}
            label={item.label}
            note={item.note}
            windowCount={item.windowCount}
          />
        ) : (
          <WindowFirstWindowCard
            key={item.key}
            card={item.card}
            rank={item.rank}
            todayStr={todayStr}
            onLoosenLens={handleLoosen}
            open={isCardOpen(item.card)}
            onToggle={() => toggleCard(item.card.key, isCardOpen(item.card))}
            onOpenPick={setOpenPick}
            onOpenSpot={handleSpot}
            onSeeAllSpots={sheetOffersMore(item.card, typesByName)
              ? (card) => setSheetKey(card.key)
              : undefined}
            peeksSuppressed={modalOpen}
            scoreIndex={scoreIndex}
            field={fieldByKey.get(item.card.key)}
          />
        )))}
        {/* Under `Best` the list is no longer in date order, and the ordinals alone do not say what
            it IS in — so the note is unconditional under that order rather than tied to the away
            rows. Its second clause is: an away day has no rank (a day with no forecast has no place
            in a ranking of forecasts), so those rows sink below the ranked cards, and a silent move
            reads as a bug. At the foot, which is where §4.3 puts it. */}
        {orderLens?.orderId === PLAN_ORDER_BEST && orderedPaneItems.length > 0 && (
          <p
            data-testid="window-first-order-note"
            className="wf-order-note font-mono text-plex-text-secondary"
          >
            {hasAwayRow
              ? 'Ranked by the best region in each window · days you are away follow, in date order.'
              : 'Ranked by the best region in each window.'}
          </p>
        )}

        {!loading && paneItems.length === 0 && (
          <p
            data-testid="window-first-pane-empty"
            className="font-mono text-plex-text-muted"
            style={{ fontSize: '10.5px' }}
          >
            No windows to show.
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
          gone back to Plan. v1 does not do this, because v1 unmounts. Admin-only and one interval,
          not several — but if that is not wanted, release the pane here rather than deleting the
          comment. */}
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
          className={effectiveTab === tab.id ? 'wf-body' : 'wf-body hidden'}
        >
          {openedTabs.has(tab.id) ? { mapPane, operationsPane }[tab.slot] : null}
        </div>
      ))}

      {/* OUTSIDE the pane, and that is a fix rather than a placement preference. The DOWN treatment
          is `pointer-events: none`, so while the exit button lived inside the pane a dead backend
          made the visible way back inert — the same trap P4a fixed for the masthead, re-created one
          level down. The cog still opens the settings modal that owns the durable toggle, but the
          button that names the route must work too. */}
      <div className="wf-exit-foot">
        <button
          type="button"
          onClick={onExit}
          data-testid="window-first-exit"
          className="font-mono border border-plex-border text-plex-text-secondary hover:text-plex-text hover:border-plex-border-light transition-colors"
          style={{ fontSize: '10.5px', borderRadius: '7px', padding: '6px 11px' }}
        >
          ← Back to the current Plan
        </button>
      </div>

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
          onClose={() => setSheetKey(null)}
          // Closes FIRST, exactly as the strip dismisses its peek before the same handoff. The map
          // overlay is itself an `aria-modal` dialog: leaving the sheet mounted underneath puts two
          // on the page at once, gives Escape two listeners to satisfy, and leaves the reader's
          // place in a list they have navigated away from. The sheet is a browsing surface and the
          // map is a destination — arriving at the destination ends the browsing.
          onOpenSpot={(spot) => { setSheetKey(null); handleSpot(sheetCard, spot); }}
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
            onClose={() => setSearchSeed(null)}
            onPickWindow={revealWindow}
            onPickRegion={(region) => setOrigin?.(region)}
            // P8: a location result opens that place's own six-window timeline rather than jumping
            // straight to the map. The map is not lost — the sheet's footer carries it and names
            // the window it opens — and this is the ONLY entry point (§9.9, resolved by the owner
            // 2026-08-20): a spot card's click and the peek's keep today's behaviour byte-for-byte.
            onPickLocation={(spot) => setSheetSpot(spot)}
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
            scoreIndex={sheetScoreIndex}
            slotIndex={slotIndex}
            // A failed or in-flight ratings fetch is not evidence that nothing was rated, so the
            // sheet's "Not scored yet" is gated on the same flag the strip's unscored mark is.
            scoresKnown={scoresLoaded}
            // The map the PAGE plans from, so the sheet and the cards behind it describe one
            // journey. At home it is the per-user map unchanged; away it is the shared matrix.
            reachById={effectiveReachById}
            scopeRegionNames={scopedRegionNames}
            origin={origin}
            // "home" rather than nothing when the settings fetch has not named the place: the drive
            // figure needs an origin on it to be placeable, and "from home" is true either way.
            originLabel={origin ? origin.baseName : (homePlace || 'home')}
            todayStr={todayStr}
            onClose={() => setSheetSpot(null)}
            // Closes FIRST, the rule the spot sheet already states: the map overlay is itself an
            // `aria-modal` dialog, and leaving this one mounted underneath puts two on the page
            // with two Escape listeners between them.
            onShowOnMap={(date, targetType, name) => {
              setSheetSpot(null);
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
          onClose={() => setOpenPick(null)}
          onShowRegion={() => {
            onShowOnMap?.({
              region: openPick.pick.regionName, date: openPick.date, eventType: openPick.targetType,
            });
            setOpenPick(null);
          }}
          onShowLocation={() => {
            onShowOnMap?.(openPick.date, openPick.targetType, openPick.pick.locationName);
            setOpenPick(null);
          }}
        />
      )}
    </div>
  );
}

WindowFirstShell.propTypes = {
  onExit: PropTypes.func.isRequired,
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
};
