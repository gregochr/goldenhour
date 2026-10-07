import React, { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { computeAutoSelection, colourForecastDates } from './utils/conversions.js';
import { buildMapOverlay, normalizeMapTrigger } from './utils/mapOverlay.js';
import LoginPage from './components/LoginPage.jsx';
import RegisterPage from './components/RegisterPage.jsx';
import ChangePasswordPage from './components/ChangePasswordPage.jsx';
import SessionExpiryBanner from './components/SessionExpiryBanner.jsx';
import AuroraBanner from './components/AuroraBanner.jsx';
import NlcSightingBanner from './components/NlcSightingBanner.jsx';
import HealthIndicator from './components/HealthIndicator.jsx';
import UserSettingsModal from './components/UserSettingsModal.jsx';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import { AuroraStatusProvider } from './context/AuroraStatusContext.jsx';
import { useAuroraStatus } from './hooks/useAuroraStatus.js';
import { ukDateStr, ukDateStrOffset, resolveAuroraNight, resolveMapDate } from './utils/mapDates.js';
import { useForecasts } from './hooks/useForecasts.js';
import { useHealthStatus } from './hooks/useHealthStatus.js';
import { useRunNotifications } from './hooks/useRunNotifications.js';
import RunCompleteBanner from './components/RunCompleteBanner.jsx';
import useAfterFirstPaint from './hooks/useAfterFirstPaint.js';
import useTodaysLight from './hooks/useTodaysLight.js';
import useReaderSettings from './hooks/useReaderSettings.js';
import { createColourSaveQueue, keepColourSaveLineOpen } from './utils/colourSaveQueue.js';
import { resolveInitialTab } from './utils/initialTab.js';
import { appNow } from './utils/rewind.js';
import { useRewind } from './hooks/useRewind.js';
import { setRewind } from './utils/rewind.js';
import { clearForecastDetailCache } from './api/forecastApi.js';
import RewindPill from './components/RewindPill.jsx';
import WindowFirstShell from './components/WindowFirstShell.jsx';
import PlanErrorBoundary from './components/PlanErrorBoundary.jsx';
import { WindowFirstBriefingProvider } from './context/WindowFirstBriefingContext.jsx';
import { AskProvider } from './context/AskContext.jsx';

// Code-split the heavy subtrees so they stay out of the initial bundle: the Leaflet map stack and
// the admin-only Manage view (which also pulls in recharts). They load on demand behind the
// Suspense boundaries. The map is now the OPENING tab on tablet/desktop
// (default-tab-by-device-plan.md — `utils/initialTab.js`), not merely a drill-down, but it stays
// lazy regardless: a phone (where Plan opens) never needs this chunk at first paint at all, and on
// tablet/desktop the fallback below covers the fetch — see `WindowFirstShell.jsx`'s own note on the
// Map pane not existing until `hasForecastData` for why that fetch cannot simply be moved
// earlier.
const MapView = lazy(() => import('./components/MapView.jsx'));
const WindowFirstMapPane = lazy(() => import('./components/WindowFirstMapPane.jsx'));
const MapOverlay = lazy(() => import('./components/MapOverlay.jsx'));
const ManageView = lazy(() => import('./components/ManageView.jsx'));

/** Lightweight fallback shown while a lazily-loaded view chunk is fetched. */
function ViewFallback() {
  return (
    <div className="flex justify-center py-16">
      <p className="text-plex-text-secondary animate-pulse">Loading…</p>
    </div>
  );
}

/**
 * Auth gate — renders {@link LoginPage} when no token is present,
 * or the main app otherwise. This keeps hooks out of the unauthenticated path.
 */
function AuthGate() {
  const { token, mustChangePassword } = useAuth();
  const [showRegister, setShowRegister] = useState(false);

  // Check URL for ?token= param (email verification link) and clear it once consumed
  const [verifyToken, setVerifyToken] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get('token') || null;
  });

  // Once authenticated, clear any leftover verify token from URL and state.
  // RegisterPage unmounts before its own cleanup can run, so we handle it here.
  useEffect(() => {
    if (token && verifyToken) {
      window.history.replaceState({}, '', window.location.pathname);
      // One-time cleanup coupled to the history side effect above: clear the
      // consumed verify token so RegisterPage stops rendering in verify mode.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setVerifyToken(null);
    }
  }, [token, verifyToken]);

  if (!token) {
    // If there's a verification token in the URL, show RegisterPage in verify mode
    if (verifyToken) {
      return (
        <RegisterPage
          verifyToken={verifyToken}
          onBackToLogin={() => {
            window.history.replaceState({}, '', window.location.pathname);
            setVerifyToken(null);
            setShowRegister(false);
          }}
        />
      );
    }
    if (showRegister) {
      return <RegisterPage onBackToLogin={() => setShowRegister(false)} />;
    }
    return <LoginPage onRegister={() => setShowRegister(true)} />;
  }
  if (mustChangePassword) {
    return <ChangePasswordPage />;
  }
  return <RewindGate />;
}

/**
 * Remounts the whole authenticated app whenever an admin sets or clears a rewind
 * (`utils/rewind.js`), so every clock read that happens once at mount — the Map tab's default
 * event, the Plan provider's SWR hydrate, the shell's "a passed window never comes back" assumption
 * — is re-evaluated against the rewound clock rather than patched one site at a time. The cost is a
 * reload-shaped reset of the UI (tab, selection, open dialogs) at the moment of the rewind, which is
 * the one moment the admin expects one: they have just asked for a different page.
 */
function RewindGate() {
  const rewind = useRewind();
  // A rewind is an admin's, for this signed-in page alone: it ends when the authenticated tree does
  // (sign-out, session expiry). Cleared HERE, on unmount, rather than in `AuthContext`'s own
  // sign-out path, because a store write there lands before `setToken(null)` and re-keys this gate
  // while the token is already gone — one remount of the whole app with no Authorization header,
  // every mount fetch a 401, and a spurious session-expired event out of the refresh interceptor.
  useEffect(() => () => setRewind(null), []);
  // The lazily-fetched popup detail is keyed by row id and should be clock-free, but nothing pins
  // that; dropping it on every change is free and keeps a rewound row from outliving the rewind.
  useEffect(() => { clearForecastDetailCache(); }, [rewind?.to]);
  return (
    <AuroraStatusProvider key={rewind?.to ?? 'live'}>
      <AppInner />
    </AuroraStatusProvider>
  );
}

/**
 * Ask PhotoCast's provider, except while an admin's rewind is active.
 *
 * <p>No provider means {@code useAsk()} answers its default — Ask is off — so no entry, sheet or
 * request exists for the whole rewound page; see the call site for why that is the rule.
 *
 * @param {object} props
 * @param {boolean} props.rewound whether a rewind is active
 * @param {React.ReactNode} props.children
 */
function AskWhenLive({ rewound, children }) {
  return rewound ? children : <AskProvider>{children}</AskProvider>;
}

AskWhenLive.propTypes = {
  rewound: PropTypes.bool.isRequired,
  children: PropTypes.node,
};

/**
 * Inner app component — only rendered when the user is authenticated.
 */
function AppInner() {
  const { isAdmin, logout, token } = useAuth();
  // The admin's rewind, if any. Read ONCE per mount for the two seeds below (`RewindGate` remounts
  // this component on every change, so a mount always sees the rewind it was mounted for) and
  // live for the pill.
  const rewind = useRewind();
  const [showSettings, setShowSettings] = useState(false);
  /**
   * The device's opening tab (default-tab-by-device-plan.md §4.2) — Plan on a phone, Map on
   * anything larger, resolved ONCE per mount by a pure viewport read and handed to the shell as
   * `initialTab`. A `useState` initialiser, so `resolveInitialTab()` runs exactly once regardless
   * of how many times `AppInner` re-renders (plan §3.3 — rotating or resizing after load must not
   * move the reader to another tab).
   */
  const [initialTab] = useState(() => resolveInitialTab());
  /**
   * The Plan shell's own active tab (map-tab-v2-plan.md §3 P7's first full-frame owner). `App`
   * cannot otherwise learn this — `WindowFirstShell`'s `effectiveTab` is shell-internal — and it
   * needs to know in order to recast the page as a flex column on the Map tab (see the root
   * `<div>` below). Defaults to `'plan'` regardless of `initialTab`: on the very first render the
   * shell itself is ALWAYS on Plan too, because the Map pane does not exist yet at that point
   * (`hasForecastData` below is false before the first forecast fetch resolves) — a device
   * whose preference is Map only reaches it once the shell's own mount effect (`onTabChange`, wired
   * below) reports the move, which corrects this state one render later. See
   * `WindowFirstShell.jsx`'s "Tab selection is deliberately not persisted" section for the full
   * preference/commit mechanism that makes that move happen at most once, and never after the
   * reader has touched the page.
   *
   * <p>⚠️ A `calc(100dvh - …)` height chain (measured masthead + tab bar + banner block, each via
   * its own `ResizeObserver`) was tried here first and shipped, then reverted: a live measurement
   * found 16px of page scroll surviving with every banner suppressed — the panel's measured top
   * sat 16px below the sum of the three measured terms, an inter-element MARGIN/gap a
   * `ResizeObserver` on element BOXES structurally cannot see (RO measures boxes, not the space
   * between them). Every additional term found so far has been a symptom of the same class of gap,
   * not the last one — so the fix is not a fourth term. Flexbox is: it absorbs every margin, gap,
   * rule and banner with no arithmetic to keep in sync, because the browser lays the column out
   * itself rather than being told the answer.
   */
  const [activePlanTab, setActivePlanTab] = useState('plan');
  const isMapTabActive = activePlanTab === 'map';
  const {
    locations, loading: forecastsLoading, error: forecastsError, refresh,
  } = useForecasts();
  // Defer the two long-lived SSE streams until after first paint so they don't compete with the
  // critical forecast/briefing fetches during boot.
  const streamsReady = useAfterFirstPaint();
  const {
    status: healthStatus, degraded: healthDegraded, checkedAt: healthCheckedAt,
    build: healthBuild, services: healthServices, database: healthDatabase,
    session: healthSession, appVersion: healthAppVersion, startedAt: healthStartedAt,
  } = useHealthStatus(streamsReady);
  const { lastCompletedRun } = useRunNotifications(!!token && streamsReady);
  const [showRunBanner, setShowRunBanner] = useState(false);

  /** Map overlay opened over the Plan tab (null = closed). Reuses the same handoff/date as the Map tab. */
  const [mapOverlay, setMapOverlay] = useState(null);
  /** Monotonic counter so repeat taps on the same location re-trigger the handoff. */
  // Nonce -1 is reserved for the rewind seed `mapTabHandoff` below is initialised with, so the
  // first live handoff (nonce 0) can never repeat the seed's nonce and be swallowed by the keyed
  // effect. Not "start at 1": `AppOpenMapTabFromPlan.test.jsx` proves the handoff and tab-request
  // nonces are separate refs by their inequality on the first door, and starting here at 1 made
  // them coincide.
  const handoffNonce = useRef(0);

  /** Briefing evaluation scores lifted from the Plan shell, passed to MapView. */
  const [briefingScores, setBriefingScores] = useState(new Map());
  const handleEvaluationScoresChange = useCallback((scores) => setBriefingScores(scores), []);

  /** Seasonal features lifted from the Plan shell, passed to MapView. */
  const [seasonalFeatures, setSeasonalFeatures] = useState([]);
  const handleSeasonalFeaturesChange = useCallback((features) => setSeasonalFeatures(features), []);

  // Non-null when the settings dialog was opened to land on a particular field: the masthead's "set
  // a postcode" nudge and the map control's "you have no postcode" branch, which both exist to point
  // at exactly that input.
  const [settingsFocus, setSettingsFocus] = useState(null);
  /**
   * The one record of the reader's own settings: the home — for the tick line (through the Plan
   * provider), the map's HOME marker, reach rings and ⌂ control, and the Plan tab's home dot — the
   * Coming up last-seen date, and the map-colour preference, which the hook hands to `scoreRamp`.
   * Read once on mount and kept current by the settings dialog's own answers, never by a read of
   * its own. With it, the two counters the reads derived from the home key on: each moves only
   * when an answer changes what it counts — the home, or its drive times — so the reads keyed on
   * them can drop a request a newer move supersedes. A counter rather than the values themselves:
   * its readers depend on server-side state this component never sees. See the hook.
   */
  const {
    homePlace, homeCoords, comingUpLastSeenDate, setComingUpLastSeenDate,
    homeSettingsVersion, driveTimesVersion, mapColourScale, colourScaleDefaulted,
    startSettingsRead, homeSaved, driveTimesRecalculated, colourSaved,
    // Threaded to the Map pane on the same route as `mapColourScale` (map-mobile-sheet-plan.md
    // M4) — unread by any UI until M5, which is why nothing here calls `saveTideMode` yet.
    mapTideMode, saveTideMode,
  } = useReaderSettings();
  /**
   * Where the settings dialog puts focus on close if the element that opened it has gone — a
   * function returning that element, or null. Only the masthead's "set a postcode" nudge supplies
   * one, because only its control is REPLACED by the save it exists for: on the Map tab a saved
   * home swaps the nudge for a non-interactive statement the moment `homeSaved` updates the record,
   * while the dialog is still open, so its recorded opener is detached by the time it closes and
   * focus fell to `<body>`. The cog and the map's ⌂ keep their node through a save (the ⌂ goes from
   * `null` to coordinates, never through the `undefined` that empties it), and their dialogs
   * restore exactly as before.
   *
   * <p>⚠️ That opener is the pressed control only when nothing was covered. Every route into
   * settings closes the dialogs it would open over, in the commit that opens it (`settingsOpen`,
   * below), and a closing dialog hands focus back to ITS opener — which settings then records as its
   * own. So on a covered route, the nudge's included, the close restores to that control, and this
   * is asked only if the control has gone by then. Where a dialog does stay open beneath — an
   * Operations-tab admin `Modal` — `useDialogFocus` declines a successor outside it; the nudge keeps
   * its node there anyway, since only the Map tab swaps it.
   *
   * <p>Held for the life of one dialog and cleared on its close, so a later open from another
   * route can never inherit it. A function in state, hence the updater form wherever it is set.
   */
  const [settingsReturnFocus, setSettingsReturnFocus] = useState(null);
  /**
   * The page's one line of map-colour saves, handed to every opening of the settings dialog. Here
   * rather than in the dialog because the dialog unmounts on close and its saves do not stop: with a
   * line per opening, a closed dialog's waiting choice went out after a reopened dialog's newer one.
   * See `colourSaveQueue.js`.
   *
   * <p>⚠️ Held open only while this page is mounted, which is the signed-in session: signing out
   * unmounts it, and a choice still waiting would otherwise go out under the NEXT account's token.
   */
  const [colourSaveQueue] = useState(createColourSaveQueue);
  useEffect(() => keepColourSaveLineOpen(colourSaveQueue), [colourSaveQueue]);
  /**
   * Today's light at the reader's home, for the window-first masthead's light rule.
   *
   * <p>Resolved here rather than inside the shell so the shell stays a render layer. Keyed on the
   * home counter alone — a drive-time recalculation cannot change the light — so saving a postcode
   * lights the rule without a reload.
   */
  const todaysLight = useTodaysLight(homeSettingsVersion);

  // Seeded with the rewind's own window when there is one: the admin rewound to a particular
  // sunrise or sunset, and the map should open on it rather than on the default the clock would
  // pick (which, pre-dawn, is today's SUNSET — `computeAutoSelection` never chooses a sunrise).
  const [selectedDate, setSelectedDate] = useState(rewind?.focus?.date ?? null);
  /**
   * Whether {@code selectedDate} NAMES A NIGHT — the aurora banner's route — rather than a calendar
   * day. Carried beside the date because `resolveMapDate`'s never-past exemption keys on the
   * selection's provenance and must never infer it from the value: in the small hours the night in
   * progress IS yesterday's date, so a stale solar pick from last evening equals it exactly, and
   * matching on value alone let that stale pick hold the map on a day that was over (Codex, #803).
   *
   * ⚠️ Written only through {@code selectDate} below, never with a bare `setSelectedDate`, so the
   * flag cannot drift from the date it describes.
   */
  const [selectedDateIsNight, setSelectedDateIsNight] = useState(false);
  /**
   * The one writer for the pair above. {@code isNight} defaults false, so every route that is not
   * explicitly a night selection — a Plan door, a grid cell, the Map tab's own `onSelectDate` —
   * gets the safe answer without having to know this rule exists.
   */
  const selectDate = useCallback((date, { isNight = false } = {}) => {
    setSelectedDate(date);
    setSelectedDateIsNight(isNight);
  }, []);


  const sortedLocations = useMemo(
    () => [...locations].sort((a, b) => a.name.localeCompare(b.name)),
    [locations],
  );
  const visibleLocations = useMemo(
    () => sortedLocations.filter((loc) => loc.enabled !== false),
    [sortedLocations],
  );

  // All dates with a sunrise or sunset forecast for any visible (enabled) location, sorted — the
  // map's forecast-date domain. ⚠️ Not simply every `forecastsByDate` key: a wildlife hide's hourly
  // comfort rows reach a day further than the colour pipeline evaluates, and a date holding only
  // those would otherwise become a Sunrise/Sunset window nothing rates (`colourForecastDates`).
  const allDates = useMemo(
    () => colourForecastDates(visibleLocations),
    [visibleLocations],
  );
  // ⚠️ Two different questions, kept apart. `allDates` answers "which dates get a window"; this
  // answers "did `GET /api/forecast` return ANYTHING for a visible location" — what gates the Map
  // tab and the "open the full map" doors. It must not follow `allDates`: a hides-only forecast (a
  // fresh deploy, a colour run that failed) has hourly rows and no sunrise or sunset, and the Map
  // tab would vanish while the data it can draw exists.
  const hasForecastData = useMemo(
    () => visibleLocations.some((loc) => loc.forecastsByDate.size > 0),
    [visibleLocations],
  );

  // Auto-select the next solar event using forecast data + a 30-min afterglow buffer.
  // Returns null when forecast data isn't loaded yet (fallback to default behaviour).
  const autoSelection = useMemo(
    () => visibleLocations.length === 0 ? null : computeAutoSelection(visibleLocations, appNow()),
    [visibleLocations],
  );

  // Default to today (or the nearest future date) when data loads.
  //
  // The UK calendar, which is the one every forecast date on the wire is keyed to. These were UTC
  // (a day behind for an hour after UK midnight under BST), then the browser's own zone (a day out
  // all day for a reader outside the UK). `ukDateStr` records both measurements.
  const todayStr = ukDateStr();
  const tomorrowStr = ukDateStrOffset(1);

  // The night aurora results are stored under, which in the small hours is YESTERDAY's date — a
  // night runs dusk-to-dawn, so it is not a calendar question and the backend answers it. Only the
  // aurora paths below use it; the colour map keeps its calendar default, because at 02:00 a
  // landscape photographer wants today's sunrise, not last night's sunset.
  const { status: auroraStatus } = useAuroraStatus();
  const auroraNightStr = resolveAuroraNight(auroraStatus);

  // ⚠️ Never a PAST date on ANY branch — `resolveMapDate` owns the whole precedence and records
  // what the old shape cost, the same way `normalizeMapTrigger` owns the handoff branch selection
  // below rather than sitting inline here. It replaced a three-branch expression whose "not past"
  // rule sat on the LAST branch only, guarded on the other two by a bare `allDates.includes(...)`
  // that a past date passes — and `autoDate` is frozen at mount, so a tab left open across UK
  // midnight took the stale branch every time and never reached the rule.
  //
  // `?? todayStr` only where there is forecast data but no date to window it on (hides-only): the
  // pane still mounts and needs a date, but `allDates` stays empty, so no window row is drawn.
  const effectiveDate = resolveMapDate({
    selectedDate,
    selectedIsNight: selectedDateIsNight,
    autoDate: autoSelection?.date ?? null,
    allDates,
    todayStr,
    // The one "past" date an explicit choice may name — see `handleAuroraViewOnMap` below, which
    // sets it deliberately, and `resolveMapDate`'s own note on the regression this prevents.
    nightDate: auroraNightStr,
  }) ?? (hasForecastData ? todayStr : null);

  /**
   * Called from any Plan-tab recommendation (Best Bet, Hot Topic, region row, grid cell, strip
   * pill). Opens the map as an *overlay* over the Plan tab — focused on what was tapped — instead
   * of a full tab switch, so the user keeps their place. The same handoff + date feed the Map tab,
   * so "Open the full Map tab →" lands exactly where the overlay was focused.
   */
  const handleShowOnMap = (dateOrHandoff, eventType, locationName = null) => {
    // The branch-selection logic (and its ordering — the part that actually matters, D8) lives in
    // `normalizeMapTrigger`, tested directly there rather than only indirectly through this handler.
    const trigger = normalizeMapTrigger(dateOrHandoff, eventType, locationName);

    const nonce = handoffNonce.current++;
    const overlay = buildMapOverlay(trigger, {
      locations: visibleLocations, briefingScores, todayStr, tomorrowStr, nonce,
    });
    // `kind: 'aurora'` is the only night-naming trigger `normalizeMapTrigger` produces — see
    // `selectedDateIsNight` above for why the flag rides along rather than being inferred later.
    if (trigger.date) selectDate(trigger.date, { isNight: trigger.kind === 'aurora' });
    setMapOverlay({ ...overlay, nonce, date: trigger.date });
  };

  /**
   * Aurora banner "View on map".
   *
   * <p>The window-first Plan has no Map tab, so the banner reaches the map through the same overlay
   * every plan card uses.
   *
   * <p>⚠️ Setting the date here (rather than leaving it to `effectiveDate`'s default) is what keeps
   * the viewline gate and the destination on the same night. The gate is keyed on the night in
   * progress, not the calendar date — pressed at 02:00 with a live alert and no stored run for that
   * night, `effectiveDate`'s default lands on today while the night is yesterday, and `MapView`'s
   * jump cannot help — it is gated on stored aurora RESULTS, which a live NOAA alert does not imply.
   * Left to the default, the banner would take the reader to the map with the viewline missing, for
   * up to seven hours a night in midwinter. Found by review; the two conditions are genuinely
   * independent.
   */
  const handleAuroraViewOnMap = () => {
    // The night in progress, not today. Pressed at 02:00 this used to open the map on a date the
    // run that produced the banner's own alert never scored.
    handleShowOnMap({ kind: 'aurora', date: auroraNightStr });
  };

  /**
   * A tab the window-first shell should select, asked for from out here. Nonce'd for the same
   * reason map handoffs are: the reader can open the overlay and press the hatch twice running, and
   * the second press has to land even though the destination has not changed.
   */
  const [tabRequest, setTabRequest] = useState(null);
  const tabRequestNonce = useRef(0);

  /**
   * The handoff the MAP TAB should act on, which is deliberately not App's overlay handoff.
   *
   * <p>⚠️ Found by review and reproduced at 390px. The shell mounts a pane once and then hides it
   * rather than unmounting it, so once the Map tab has been visited its `MapView` is alive for the
   * rest of the session — and it was being handed App's overlay handoff, which every plan-card tap
   * sets. On a phone `MapView` answers a location handoff with a `BottomSheet`, which is
   * `createPortal(…, document.body)` at `z-index: 10000`, so `display: none` on the panel cannot
   * suppress it: tapping "Open on map" on the PLAN tab raised **two** stacked sheets — one from the
   * overlay the reader asked for, one from a map that is not on screen — and locked body scroll.
   *
   * <p>So the tab is handed a handoff only when the reader explicitly asks to be taken to it, which
   * is the hatch below and nothing else. Every other handoff belongs to the overlay.
   */
  const [mapTabHandoff, setMapTabHandoff] = useState(() => (rewind?.focus ? {
    // The rewind's window, as a map-sourced handoff: `source: 'map'` is the one shape that selects
    // a window as an EXPLICIT choice (so the auto-selection effect cannot replace it a tick later)
    // while carrying no lens — no floor, tier, scope or camera to overwrite, and no `tabRequest`,
    // since `initialTab` already decides where the page opens.
    source: 'map',
    eventType: rewind.focus.eventType,
    date: rewind.focus.date,
    locationName: null,
    nonce: -1, // reserved for this seed — see `handoffNonce` above
  } : null));

  /**
   * Close the overlay and hand off to the full Map tab, landing where the overlay was focused.
   */
  const openFullMapTab = () => {
    // Read before the overlay is cleared — this is what "landing where the overlay was focused"
    // actually means, and it is the only handoff the Map tab ever receives.
    const focus = mapOverlay?.handoff ?? null;
    setMapOverlay(null);
    tabRequestNonce.current += 1;
    if (focus) setMapTabHandoff({ ...focus, nonce: handoffNonce.current++ });
    setTabRequest({ id: 'map', nonce: tabRequestNonce.current });
  };

  /**
   * The location a four-day sheet should be opened for, asked for from the Map tab's selection
   * callout (map-tab-v2-plan.md §3 P9) — `openFullMapTab`'s shape, in reverse. Kept SEPARATE from
   * `tabRequest` for the identical reason `mapTabHandoff` is kept separate from it in the forward
   * direction: the Plan tab's body is `hidden` rather than unmounted while another tab is active
   * (`WindowFirstShell.jsx`'s own sticky-pane idiom), so a handoff that arrived on some OTHER
   * channel while it was hidden must not be mistaken for one the reader explicitly asked for.
   */
  const [locationSheetHandoff, setLocationSheetHandoff] = useState(null);

  /**
   * Open one location's four-day sheet, asked for from the Map tab's selection callout.
   *
   * <p>⚠️ <b>The tab move is the callout's choice, not this function's, and it does NOT ride
   * `tabRequest`.</b> The callout has two routes into the same sheet and they differ only in where
   * the reader ends up: the clamped prose's `Four days here ›` opens it OVER the map, so closing it
   * puts the reader back on the callout they pressed it from (an owner ask — "I'd like it to stay
   * with the map behind, then I can back track on my user journey"), while the actions row's
   * `Open in Plan` names the Plan tab and still goes there. `spot.inPlan` carries which, and
   * `WindowFirstShell`'s handoff effect performs the move ITSELF rather than App raising a second
   * `tabRequest` alongside this one — that effect calls `selectTab('plan')` and then writes
   * `sheetSpot` in the SAME synchronous body (i.e. the same React batch), which is the whole reason
   * the sheet survives `selectTab`'s own dialog-clearing. Two channels would put those two writes
   * in two effects and make the outcome depend on their declaration order.
   *
   * @param {{id: *, name: string, regionName: ?string, inPlan: boolean, date: ?string,
   *        targetType: ?string}} spot the sheet's own identity shape
   *        (`utils/locationSheet.sheetSpotOf`'s), the destination flag, and the window the map is
   *        on (which the shell turns into the sheet's `focusWindowKey`) — all built by
   *        `MapView.jsx`'s caller
   */
  const openLocationSheet = (spot) => {
    setLocationSheetHandoff({ ...spot, nonce: handoffNonce.current++ });
  };

  /**
   * A door from the Plan tab onto the Map tab (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 1)
   * — `door = {date, targetType, region: ?string, minRating: ?number, limitMinutes: ?number,
   * locationName: ?string}`, already carrying the Plan's own lens values (read at the moment of the
   * tap by `WindowFirstShell`'s internal `openMapTab`, never re-read here).
   *
   * <p>Reuses the SAME `mapTabHandoff`/`tabRequest` channel `openFullMapTab` uses, not a second
   * one — `App.jsx:307–318`'s own ⚠️ about that channel applies unchanged: the Map pane is never
   * unmounted, so a handoff on it must only ever arrive when the reader explicitly asked to be
   * taken there, which a door tap is. The two are told apart downstream by `source: 'plan'`, which
   * `openFullMapTab`'s own handoff never carries.
   *
   * <p><b>Origin is deliberately NOT in the payload</b> (plan §2, §4 #1) — it is shared state the
   * Map tab already reads from the SAME `WindowFirstBriefingContext` the Plan tab does, so sending
   * it here would be the increment's own `org`-in-the-URL mistake in reverse: a parameter the map
   * never reads because it already has a truer answer to the same question.
   *
   * <p>⚠️ <b>`door.inPlace` is not a door at all</b> — it is the same footer pressed from a sheet
   * that is already OVER the map, and it takes the other branch below for the reasons stated there.
   */
  const openMapTabFromPlan = (door) => {
    selectDate(door.date);
    if (door.inPlace) {
      // ⚠️ The reader is ALREADY on the Map tab — the four-day sheet was opened over it by the
      // callout's `Four days here ›` peek, and this is its footer's `Show on map → <window>`. A
      // door PAYLOAD here would be actively destructive rather than merely redundant: `MapView`'s
      // landing effect writes `minRating` (and PERSISTS it to `mapFilterMinStars`), writes
      // `limitMinutes`, and — because a sheet door always carries `region: null` — calls
      // `resetToMyArea()`, which flips scope back to My area and refits the camera. So a reader who
      // had just set a 4★ floor and jumped to a region on this very map would have all three
      // silently undone by a press that only ever asked to change the WINDOW. The breadcrumb would
      // then appear over it reading "Where you came from · ← Plan", which is not where they came
      // from, and the `tabRequest` would move focus to the Map tab button of the tab they are on.
      //
      // ⚠️ `source: 'map'`, NOT an absent source. A source-less payload is the OVERLAY HATCH's
      // shape, and the hatch's own effect sets `userHasOverriddenEvent` to FALSE — which re-runs
      // `MapView`'s auto-selection effect and immediately replaces the window this press just named
      // with `autoEventType`. So "Show on map → Monday sunrise", pressed from a map the reader had
      // manually stepped to, landed on tonight's sunset instead. (Codex review, P1 on the first cut
      // — the structured door effect already sets that flag TRUE for exactly this reason, and its
      // own comment records the same reset as a previously shipped blocking defect.) The payload
      // therefore rides the STRUCTURED channel, where the event is treated as an explicit
      // selection, and carries no lens fields at all — `MapView` skips the lens/scope/camera block
      // and the landing strip for a map-sourced one. No `tabRequest` either: there is no tab to
      // change.
      setMapTabHandoff({
        source: 'map',
        eventType: door.targetType,
        date: door.date,
        locationName: door.locationName ?? null,
        nonce: handoffNonce.current++,
      });
      return;
    }
    tabRequestNonce.current += 1;
    setMapTabHandoff({
      source: 'plan',
      eventType: door.targetType,
      date: door.date,
      region: door.region ?? null,
      minRating: door.minRating ?? null,
      limitMinutes: door.limitMinutes ?? null,
      locationName: door.locationName ?? null,
      nonce: handoffNonce.current++,
    });
    setTabRequest({ id: 'map', nonce: tabRequestNonce.current });
  };

  /**
   * The breadcrumb's `← Plan` (doors D2) — lands on the plan itself, no dialog reopened, no window
   * key carried (plan §6 Q2, decided). The shell's existing `tabRequest` effect already selects the
   * tab and moves focus there; this needs no new channel of its own.
   */
  const returnToPlan = () => {
    tabRequestNonce.current += 1;
    setTabRequest({ id: 'plan', nonce: tabRequestNonce.current });
  };

  // Show banner when a run completes, auto-dismiss after 15 seconds
  useEffect(() => {
    if (!lastCompletedRun) return;
    // Effect-driven banner: show on each newly completed run, then auto-dismiss
    // via the timer below. The reveal is the effect's purpose, not derivable state.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setShowRunBanner(true);
    const timer = setTimeout(() => setShowRunBanner(false), 15000);
    return () => clearTimeout(timer);
  }, [lastCompletedRun]);

  const isDown = healthStatus === 'DOWN';
  /**
   * Whether the settings dialog is open — the one condition it mounts on below, and what the Plan
   * shell is told. The dialog is a sibling of the shell here, so the shell cannot see it arrive;
   * three routes open it (the masthead cog and nudge, and the Map tab's ⌂ through the map pane),
   * and the ⌂ is the one that never passes through the shell. Hearing this, the shell takes its own
   * dialogs down in the same commit.
   */
  const settingsOpen = Boolean(showSettings || settingsFocus);
  /**
   * …and `App` takes down its OWN other dialog, the map overlay, in the same commit — the shell's
   * edge cannot, because the overlay is state here.
   *
   * <p>⚠️ The overlay is `aria-modal` and deliberately no trap, and it paints over settings
   * (`zIndex: 200` against `Modal`'s `z-50`): a reader who Tabbed out of it and pressed ⚙ or the
   * nudge — or the ⌂, with the overlay opened over the Map tab — got settings UNDER it, holding
   * focus in a dialog they could not see. Settings is not a destination, and closing the overlay
   * loses only what its own ✕ does. During render on the rising edge, as the shell's is, so no
   * commit holds both; an effect's close would land a commit later.
   *
   * <p>What is still NOT the only modal under settings, named rather than implied: an
   * Operations-tab admin `Modal` (left open — it can hold data a close would lose) and any dialog
   * opened behind settings after it opened (the reverse route).
   */
  const [settingsWasOpen, setSettingsWasOpen] = useState(settingsOpen);
  if (settingsOpen !== settingsWasOpen) {
    setSettingsWasOpen(settingsOpen);
    if (settingsOpen) setMapOverlay(null);
  }

  return (
    // Recast as a flex column on the Map tab (map-tab-v2-plan.md §3 P7's full-frame owner,
    // rebuilt after the `calc(100dvh - …)` chain above proved to be chasing terms rather than
    // fixing the actual shape of the problem — see that comment for the measurement that killed
    // it). `h-[100dvh]` + `overflow-hidden` on THIS root is the outermost backstop: whatever the
    // flex children below do, nothing can push the page taller than one screen. Every other tab
    // keeps `min-h-screen` and today's ordinary document flow — the conditional is scoped to
    // exactly the tab that needs it, nothing else.
    // `app-safe` carries the viewport's safe-area insets as padding, so everything in normal flow
    // below is inset without knowing about them (index.css's "Safe areas" block lists the four
    // elements that DO have to know, because they touch a viewport edge themselves). On the Map tab
    // it also does the right thing to `h-[100dvh]`: Tailwind's preflight makes every box
    // `border-box`, so the padding comes OUT of the screen height and the `flex-1` map pane
    // shrinks to fit, rather than the page growing past one screen.
    <div className={`app-safe ${isMapTabActive ? 'h-[100dvh] flex flex-col overflow-hidden bg-plex-bg' : 'min-h-screen bg-plex-bg'}`}>
      {/* A natural-height, non-shrinking flex item on the Map tab — `flex-shrink-0` so a tight
          column never squeezes a banner instead of the map panel, which is the one element with
          `flex-1` and so the one meant to absorb the difference. Inert on every other tab (the
          root above is not `display:flex` there, so these flex-only classes do nothing). */}
      <div className={isMapTabActive ? 'flex-shrink-0' : undefined}>
        <SessionExpiryBanner />
        {isAdmin && rewind && <RewindPill rewind={rewind} />}
        <div className="max-w-4xl mx-auto px-4 mt-4">
          <AuroraBanner onViewOnMap={handleAuroraViewOnMap} />
          <div className="mt-2">
            {/* Inert: the window-first Plan has no Map tab to switch to and no route to give it. */}
            <NlcSightingBanner />
          </div>
        </div>

        {showRunBanner && lastCompletedRun && (
          <RunCompleteBanner
            run={lastCompletedRun}
            onRefresh={() => {
              refresh();
              setShowRunBanner(false);
            }}
          />
        )}

        {isDown && (
          <div
            className="bg-red-900/40 border-b border-red-700 py-3"
            data-testid="backend-down-banner"
            style={{ width: '100%', boxSizing: 'border-box', overflow: 'hidden' }}
          >
            <p className="max-w-4xl mx-auto px-4 text-sm text-red-300 text-center">
              Service is temporarily unavailable. Data shown may be stale.
            </p>
          </div>
        )}
      </div>

      {/* On the Map tab: top padding only (`pt-6`, below) and `flex-1 min-h-0` so this element — the one link
          in the chain between the flex root and `WindowFirstShell`'s own `.wf-shell`
          (`PlanErrorBoundary` returns `children` directly when healthy, and
          `WindowFirstBriefingProvider` is a bare context provider, so neither interposes a DOM
          node here) — actually receives the space flexbox is distributing rather than sizing to
          its content. `flex flex-col` so ITS single child (`.wf-shell`) can do the same one level
          down. Every other tab keeps the usual `px-4 py-6` inset and ordinary block flow.

          TOP padding matches every other tab's `py-6` (`pt-6`, unconditional — not `sm:pt-6`: the
          phone residue below is about HORIZONTAL full-bleed only, and vertically the phone
          masthead must not move either), so the masthead, band and tab row sit at the same height
          on every tab and a tab switch does not make them jump by 24px. That padding costs the
          map nothing it can lose to page scroll: Tailwind preflight makes every box `border-box`,
          `<main>` is `flex-1 min-h-0` inside the root's `overflow-hidden` flex column, and its
          child `.wf-shell` is itself `flex-1 min-h-0`, so the padding comes out of `<main>`'s OWN
          flex-distributed height and the map pane simply shrinks by 24px — the same mechanism
          `app-safe`'s padding on the root already relies on (see the root's comment above).
          BOTTOM padding stays zero: the footer is suppressed on the Map tab, and the map frame
          bleeds to the bottom edge of the screen, where a 24px dead band would be a regression.
          HORIZONTAL padding is `sm:px-4` — present at `sm` (640px) and up,
          absent below it — and that split is load-bearing, not cosmetic:

          · At `sm` and up, `WindowFirstShell`'s own panel-region wrapper already caps the visible
            width to `WRAP_MAX_WIDTH` (1080px, centred) since O-17, so `<main>`'s own inset is
            free to match every other tab's `px-4` with no visual cost — and matching it is what
            makes tab switches NOT reflow the masthead/tab-strip's horizontal position between
            640px and (1080px + 2×16px) — the width the column would otherwise seem to jump by.
            Adversarial review caught this: dropping `<main>`'s horizontal padding entirely, as an
            earlier cut of O-17 did, left the masthead 32px narrower on the Map tab than on every
            other tab in that range, silently contradicting O-17's whole point.
          · BELOW `sm` the map stays genuinely full-bleed by design — P12's phone chrome (the
            top/bottom bars' edge-hugging insets, `index.css` ~:2916-2933) was measured and tuned
            against a full-width frame, and `WindowFirstShell`'s own width cap does not bind at
            phone widths anyway (390px is nowhere near 1080px). So a real, narrower residue
            survives ONLY below `sm`: the masthead reflows by `<main>`'s own `px-4` (16px each
            side, 32px total) on a Plan⇄Map switch on a phone. That is P12's existing, deliberate
            full-bleed phone treatment continuing to apply — not a gap this change introduces —
            and is called out here rather than left for a reader to rediscover. */}
      <main className={isMapTabActive ? 'sm:px-4 pt-6 flex-1 min-h-0 flex flex-col' : 'px-4 py-6'}>
        {/* isDown is passed DOWN rather than applied here: the shell's masthead carries the cog
            and Sign out, and greying the whole subtree would strand a user with no route out of a
            broken app. */}
        {/* The provider is mounted INSIDE the boundary below, not beside it, so a provider crash
            is caught the same way a shell crash is (§4.1) — the reader always lands on the same
            fallback rather than a blank page. */}
        <PlanErrorBoundary onSignOut={logout}>
          {/* `locations` joins `homeSettingsVersion` here because the heat field's catalogue is
              a join across two contracts and only ONE of them carries geography: the briefing
              and scores payloads have no lat/lng and are not to be given any (plan §3). The
              shell already received the same memoised array; the provider needs it to build the
              join once for the strip, the row maps and the Map tab rather than three times. */}
          <WindowFirstBriefingProvider
            homeSettingsVersion={homeSettingsVersion}
            driveTimesVersion={driveTimesVersion}
            homePlace={homePlace}
            comingUpLastSeenDate={comingUpLastSeenDate}
            setComingUpLastSeenAt={setComingUpLastSeenDate}
            locations={visibleLocations}
          >
            {/* Ask PhotoCast's conversation (plan §2.6). Inside the briefing provider (it reads the
                briefing and the HOME reach map from it) and around the shell, so every Ask surface
                the shell draws — and, from F3, the map pane it hands over — sees one conversation.
                ⚠️ NOT MOUNTED UNDER A REWIND: the provider's first act is `GET /api/user/settings/ask`,
                and the axios interceptor stamps the rewound clock on every GET outside `/api/admin/`,
                while a POST sees the real clock (plan §1 #21). Without a provider `useAsk()` is Ask
                "off" and no Ask surface exists. `RewindGate` remounts this whole tree on every change,
                so the choice never has to flip in place. (The shell below is deliberately NOT
                re-indented one level, for the reason the tab row's wrapper in the shell gives.) */}
            <AskWhenLive rewound={Boolean(rewind)}>
            <WindowFirstShell
              mapColourScale={mapColourScale}
              initialTab={initialTab}
              onTabChange={setActivePlanTab}
              onOpenSettings={() => setShowSettings(true)}
              settingsOpen={settingsOpen}
              onSignOut={logout}
              // The dates the reader holds a colour forecast for — the Coming up tab withholds a
              // map door whose date is not among them (a 90-day feed, a few forecast days).
              // ⚠️ Three-valued: `null` means "not known yet" and leaves the doors as they were —
              // while the first fetch is outstanding, or when it failed with nothing on screen (a
              // failed request is not knowledge of an absence); only a settled list can withhold
              // one, and locations left by a cache hydrate or an earlier fetch keep theirs real.
              forecastDates={
                forecastsLoading || (forecastsError && visibleLocations.length === 0) ? null : allDates
              }
              light={todaysLight}
              // The Map pane's own home marker source, reused so the Plan surfaces' home marker and
              // reach rings can never name a different point (field-geography plan §2.1).
              homeCoords={homeCoords}
              // The band's nudge exists to get a postcode saved, so it lands ON that field
              // rather than on the settings screen in general — the same field the map's
              // "you have no postcode" branch opens. It also hands over a way to find its own
              // successor, for the dialog's close (see `settingsReturnFocus`).
              onSetPostcode={(returnFocus) => {
                setSettingsReturnFocus(() => (typeof returnFocus === 'function' ? returnFocus : null));
                setSettingsFocus('postcode');
              }}
              contentDisabled={isDown}
              onShowOnMap={handleShowOnMap}
              // The map doors (D2) — withheld under the identical rule that withholds `mapPane`
              // and `onOpenFullMap` below: a door onto no map is what §6 of the matrix plan bans.
              // No door UI ships in this phase (D3/D4 add the buttons), but the shell's own
              // `openMapTab` reads this prop already, so the wiring is live from here on.
              onOpenMapTab={hasForecastData ? openMapTabFromPlan : undefined}
              // The same admin gate the Operations pane uses, and for the same reason: the role
              // stays here, and the shell renders whatever node it is handed. Withheld for a
              // pilot user, who has no use for a build id or a WorldTides latency. The pill is
              // fed by `useHealthStatus` above.
              healthPill={isAdmin ? (
                <HealthIndicator
                  status={healthStatus}
                  degraded={healthDegraded}
                  checkedAt={healthCheckedAt}
                  build={healthBuild}
                  services={healthServices}
                  database={healthDatabase}
                  session={healthSession}
                  appVersion={healthAppVersion}
                  startedAt={healthStartedAt}
                />
              ) : null}
              onEvaluationScoresChange={handleEvaluationScoresChange}
              onSeasonalFeaturesChange={handleSeasonalFeaturesChange}
              locations={visibleLocations}
              tabRequest={tabRequest}
              locationSheetHandoff={locationSheetHandoff}
              // Withheld when there is nothing to map, which is the same rule the Operations tab
              // follows and §6's ban on controls that open nothing. `hasForecastData` is false
              // whenever `GET /api/forecast` returned no rows for a visible location, and a Map tab
              // onto no data would be a tab onto a blank. (`allDates` can be empty while this is
              // true — a hides-only forecast — and then the pane draws chips and no window.)
              mapPane={hasForecastData ? (
                <Suspense fallback={<ViewFallback />}>
                  <WindowFirstMapPane
                    locations={visibleLocations}
                    dates={allDates}
                    selectedDate={effectiveDate}
                    onSelectDate={selectDate}
                    // Without this the pane's event type is whatever it derived at mount — and
                    // because this pane is never unmounted, opening the map at dawn and returning
                    // after sunset would still show the morning's event.
                    autoEventType={autoSelection?.eventType ?? null}
                    handoff={mapTabHandoff}
                    briefingScores={briefingScores}
                    onForecastRun={refresh}
                    seasonalFeatures={seasonalFeatures}
                    homeCoords={homeCoords}
                    mapColourScale={mapColourScale}
                    colourScaleDefaulted={colourScaleDefaulted}
                    mapTideMode={mapTideMode}
                    saveTideMode={saveTideMode}
                    onOpenSettings={() => setSettingsFocus('postcode')}
                    onOpenLocationSheet={openLocationSheet}
                    // The breadcrumb's `← Plan` (D2) — a `tabRequest` for `'plan'`, no window key.
                    onReturnToPlan={returnToPlan}
                  />
                </Suspense>
              ) : null}
              // The admin gate, in full. The shell takes no role, no `isAdmin` boolean and no
              // prop shaped like one — it simply renders a tab for each pane it was handed, so
              // withholding the pane withholds the tab. The role stays here, where it already
              // lives, and nothing role-derived crosses this boundary (plan §5c).
              operationsPane={isAdmin ? (
                <Suspense fallback={<ViewFallback />}>
                  <ManageView onComplete={refresh} />
                </Suspense>
              ) : null}
            />
            </AskWhenLive>
          </WindowFirstBriefingProvider>
        </PlanErrorBoundary>
      </main>

      {/* Suppressed on the Map tab (adversarial review, real finding #2, measured live: the
          footer alone overflowed the full-frame page by 99px at 1280×800, clipping the map's
          bottom edge — with ZERO banners showing, before this class of gap was even in scope).
          `100dvh`'s own accounting has no room for a footer under a screen whose whole point is
          "fills the frame... and does not scroll" (README) — dead space under a map nobody can
          reach without breaking that promise. Every other tab keeps it. */}
      {!isMapTabActive && (
        <footer className="border-t border-plex-border px-4 py-4 mt-8">
          <div className="max-w-4xl mx-auto text-center text-xs text-plex-text-muted">
            <div className="flex justify-center gap-4">
              <a
                href="https://www.instagram.com/photocastuk"
                target="_blank"
                rel="noopener noreferrer"
                aria-label="Instagram"
                className="text-plex-text-muted hover:text-plex-gold transition-colors"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="5" ry="5"/><path d="M16 11.37A4 4 0 1 1 12.63 8 4 4 0 0 1 16 11.37z"/><line x1="17.5" y1="6.5" x2="17.51" y2="6.5"/></svg>
              </a>
              <a
                href="https://www.facebook.com/photocast"
                target="_blank"
                rel="noopener noreferrer"
                aria-label="Facebook"
                className="text-plex-text-muted hover:text-plex-gold transition-colors"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 2h-3a5 5 0 0 0-5 5v3H7v4h3v8h4v-8h3l1-4h-4V7a1 1 0 0 1 1-1h3z"/></svg>
              </a>
            </div>
          </div>
        </footer>
      )}

      {settingsOpen && (
        <UserSettingsModal
          focusField={settingsFocus}
          restoreFocusFallback={settingsReturnFocus}
          onClose={() => {
            setShowSettings(false);
            setSettingsFocus(null);
            setSettingsReturnFocus(null);
          }}
          // After mount the page hears of changes to the reader's home and colour only from the
          // dialog's answers — its own read on opening, a saved home, a recalculation, a saved
          // colour — and nothing moves on a close. Each save reports from its own continuation,
          // so one still in flight when the dialog closes reports when it lands. See
          // `useReaderSettings`.
          startSettingsRead={startSettingsRead}
          onHomeSaved={homeSaved}
          onDriveTimesRecalculated={driveTimesRecalculated}
          onColourSaved={colourSaved}
          colourSaveQueue={colourSaveQueue}
          onDriveTimesRefreshed={refresh}
        />
      )}

      {mapOverlay && (
        <Suspense fallback={<ViewFallback />}>
          <MapOverlay
            title={mapOverlay.title}
            subLine={mapOverlay.subLine}
            caption={mapOverlay.caption}
            narrative={mapOverlay.narrative}
            narrativeHead={mapOverlay.narrativeHead}
            narrativeTone={mapOverlay.narrativeTone}
            onClose={() => setMapOverlay(null)}
            // Withheld when there is no Map tab to reach (no forecast data), because MapOverlay
            // drops the button when no handler arrives and a button onto nothing is what §6 bans.
            onOpenFullMap={hasForecastData ? openFullMapTab : undefined}
          >
            <MapView
              locations={visibleLocations}
              date={mapOverlay.date ?? effectiveDate}
              // Deliberately NO onSelectDate, and the omission IS the mechanism — `MapView` checks
              // for the handler and asks for nothing without one. Do not "fix" that by adding one
              // on the assumption the overlay would ignore it: the date below falls through to
              // `effectiveDate` whenever the trigger carried none, and `effectiveDate` is driven by
              // `selectedDate`, so a handler here would move the Plan tab under the reader and
              // could move the overlay itself. The aurora path in already opens on the right night
              // — `handleAuroraViewOnMap` targets it directly.
              //
              // Deliberately NO `heat`: this map opens focused on one spot from a card that already
              // answered the question; `MapView` keys the field on the prop's presence
              // (`heatOffered`), so the omission IS the mechanism — do not add one.
              autoEventType={autoSelection?.eventType ?? null}
              handoffEventType={mapOverlay.handoff.eventType ?? null}
              handoffFilterAction={mapOverlay.handoff.filterAction ?? null}
              handoffDarkSky={mapOverlay.handoff.darkSky ?? null}
              handoffLocationName={mapOverlay.handoff.locationName ?? null}
              handoffRegion={mapOverlay.handoff.region ?? null}
              handoffNonce={mapOverlay.nonce}
              focus={mapOverlay.focus}
              emphasiseLocationName={mapOverlay.handoff.locationName ?? null}
              briefingScores={briefingScores}
              onForecastRun={refresh}
              seasonalFeatures={seasonalFeatures}
              colourScaleDefaulted={colourScaleDefaulted}
              // This map arrived from a plan card, which already chose the location and the solar
              // event — so it opens with the filters folded behind a one-line context bar and
              // spends the reclaimed height on the map. The Map tab keeps the full rail.
              overlayMode
            />
          </MapOverlay>
        </Suspense>
      )}
    </div>
  );
}

/**
 * Root application component. Wraps the app in the authentication provider.
 */
export default function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  );
}
