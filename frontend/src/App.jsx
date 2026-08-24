import React, { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { computeAutoSelection } from './utils/conversions.js';
import { buildMapOverlay } from './utils/mapOverlay.js';
import LoginPage from './components/LoginPage.jsx';
import RegisterPage from './components/RegisterPage.jsx';
import ChangePasswordPage from './components/ChangePasswordPage.jsx';
import SessionExpiryBanner from './components/SessionExpiryBanner.jsx';
import AuroraBanner from './components/AuroraBanner.jsx';
import NlcSightingBanner from './components/NlcSightingBanner.jsx';
import HealthIndicator, { VARIANT_MASTHEAD } from './components/HealthIndicator.jsx';
import UserSettingsModal from './components/UserSettingsModal.jsx';
import { getSettings } from './api/settingsApi.js';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import { AuroraStatusProvider } from './context/AuroraStatusContext.jsx';
import { useAuroraStatus } from './hooks/useAuroraStatus.js';
import { ukDateStr, ukDateStrOffset, resolveAuroraNight } from './utils/mapDates.js';
import { useForecasts } from './hooks/useForecasts.js';
import { useHealthStatus } from './hooks/useHealthStatus.js';
import { useRunNotifications } from './hooks/useRunNotifications.js';
import useAfterFirstPaint from './hooks/useAfterFirstPaint.js';
import useTodaysLight from './hooks/useTodaysLight.js';
import WindowFirstShell from './components/WindowFirstShell.jsx';
import PlanErrorBoundary from './components/PlanErrorBoundary.jsx';
import { WindowFirstBriefingProvider } from './context/WindowFirstBriefingContext.jsx';

// Code-split the heavy, rarely-first-viewed subtrees so they stay out of the initial bundle:
// the Leaflet map stack (Plan is the default tab; the map is a drill-down) and the admin-only
// Manage view (which also pulls in recharts). They load on demand behind the Suspense boundaries.
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
  return (
    <AuroraStatusProvider>
      <AppInner />
    </AuroraStatusProvider>
  );
}

/**
 * Inner app component — only rendered when the user is authenticated.
 */
function AppInner() {
  const { isAdmin, logout, token } = useAuth();
  const [showSettings, setShowSettings] = useState(false);
  const { locations, refresh } = useForecasts();
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
  const handoffNonce = useRef(0);

  /** Briefing evaluation scores lifted from the Plan shell, passed to MapView. */
  const [briefingScores, setBriefingScores] = useState(new Map());
  const handleEvaluationScoresChange = useCallback((scores) => setBriefingScores(scores), []);

  /** Seasonal features lifted from the Plan shell, passed to MapView. */
  const [seasonalFeatures, setSeasonalFeatures] = useState([]);
  const handleSeasonalFeaturesChange = useCallback((features) => setSeasonalFeatures(features), []);

  /**
   * Home coordinates resolved from the user's saved postcode — the same pipeline that already
   * backs the per-location drive times, reused to gate the Plan tab's "Close to home" block by
   * distance. No new setting and no new endpoint: null simply means no postcode is saved yet, and
   * the block hides itself. Re-read when the settings modal closes, so adding or moving a home
   * postcode takes effect without a page reload.
   */
  const [homeCoords, setHomeCoords] = useState(null);
  // The Close-to-home radius, from the same settings read. It frames the map's "centre on home"
  // camera move, so that control shows the area this user calls local rather than a fixed 30.
  const [homeRadiusMiles, setHomeRadiusMiles] = useState(null);
  // Non-null when the settings dialog was opened to land on a particular field — currently only
  // the map control's "you have no postcode" branch, which exists to point at exactly that input.
  const [settingsFocus, setSettingsFocus] = useState(null);
  // Bumped when the settings modal closes, so Close to home refetches after a postcode or radius
  // change. A counter rather than the values themselves: the panel depends on server-side state
  // this component never sees.
  const [homeSettingsVersion, setHomeSettingsVersion] = useState(0);
  /**
   * Today's light at the reader's home, for the window-first masthead's light rule.
   *
   * <p>Resolved here rather than inside the shell so the shell stays a render layer.
   * `homeSettingsVersion` is the same counter Close to home already refetches on, so saving a
   * postcode lights the rule without a reload.
   */
  const todaysLight = useTodaysLight(homeSettingsVersion);

  const loadHomeCoords = useCallback(() => {
    getSettings()
      .then((s) => {
        setHomeCoords(
          s?.homeLatitude != null && s?.homeLongitude != null
            ? { lat: s.homeLatitude, lon: s.homeLongitude }
            : null,
        );
        setHomeRadiusMiles(s?.localRadiusMiles ?? null);
      })
      .catch(() => { /* settings are optional — the block just stays hidden */ });
  }, []);
  useEffect(() => { loadHomeCoords(); }, [loadHomeCoords]);

  const [selectedDate, setSelectedDate] = useState(null);


  const sortedLocations = useMemo(
    () => [...locations].sort((a, b) => a.name.localeCompare(b.name)),
    [locations],
  );
  const visibleLocations = useMemo(
    () => sortedLocations.filter((loc) => loc.enabled !== false),
    [sortedLocations],
  );

  // All dates available across any visible (enabled) location, sorted, for the map date strip.
  const allDates = useMemo(
    () => [...new Set(
      visibleLocations.flatMap((loc) => Array.from(loc.forecastsByDate.keys()))
    )].sort(),
    [visibleLocations],
  );

  // Auto-select the next solar event using forecast data + a 30-min afterglow buffer.
  // Returns null when forecast data isn't loaded yet (fallback to default behaviour).
  const autoSelection = useMemo(
    () => visibleLocations.length === 0 ? null : computeAutoSelection(visibleLocations, new Date()),
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

  const defaultDate = allDates.find((d) => d >= todayStr) ?? allDates[allDates.length - 1] ?? null;
  const autoDate = autoSelection?.date ?? null;
  const effectiveDate = (selectedDate && allDates.includes(selectedDate))
    ? selectedDate
    : (autoDate && allDates.includes(autoDate))
      ? autoDate
      : defaultDate;

  /**
   * Called from any Plan-tab recommendation (Best Bet, Hot Topic, region row, grid cell, strip
   * pill). Opens the map as an *overlay* over the Plan tab — focused on what was tapped — instead
   * of a full tab switch, so the user keeps their place. The same handoff + date feed the Map tab,
   * so "Open the full Map tab →" lands exactly where the overlay was focused.
   */
  const handleShowOnMap = (dateOrHandoff, eventType, locationName = null) => {
    let trigger;
    // First, because it is the one caller that names its own kind. Without this branch the object
    // falls past `filterAction` and `region` into the final `else` and becomes an `event` trigger
    // whose `date` is the whole object — an overlay for a night that does not exist.
    if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'aurora') {
      trigger = { kind: 'aurora', date: dateOrHandoff.date };
    } else if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.filterAction) {
      trigger = { kind: 'topic', filterAction: dateOrHandoff.filterAction, label: dateOrHandoff.label, date: dateOrHandoff.date };
    } else if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.region) {
      // A region trigger, optionally carrying a hot topic's qualifying locations + label so the
      // overlay opens to just those pins (elevated inversion spots, coastal tide spots, …).
      trigger = {
        kind: 'region',
        region: dateOrHandoff.region,
        date: dateOrHandoff.date,
        eventType: dateOrHandoff.eventType,
        locationNames: dateOrHandoff.locationNames ?? null,
        label: dateOrHandoff.label ?? null,
        filterAction: dateOrHandoff.filterAction ?? null,
      };
    } else if (locationName) {
      trigger = { kind: 'location', locationName, date: dateOrHandoff, eventType };
    } else {
      trigger = { kind: 'event', date: dateOrHandoff, eventType };
    }

    const nonce = handoffNonce.current++;
    const overlay = buildMapOverlay(trigger, {
      locations: visibleLocations, briefingScores, todayStr, tomorrowStr, nonce,
    });
    if (trigger.date) setSelectedDate(trigger.date);
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
  const [mapTabHandoff, setMapTabHandoff] = useState(null);

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

  return (
    <div className="min-h-screen bg-plex-bg">
      <SessionExpiryBanner />
      <div className="max-w-4xl mx-auto px-4 mt-4">
        <AuroraBanner onViewOnMap={handleAuroraViewOnMap} />
        <div className="mt-2">
          {/* Inert: the window-first Plan has no Map tab to switch to and no route to give it. */}
          <NlcSightingBanner />
        </div>
      </div>

      {showRunBanner && lastCompletedRun && (
        <div
          className="bg-green-900/40 border-b border-green-700 py-3"
          data-testid="run-complete-banner"
        >
          <p className="max-w-4xl mx-auto px-4 text-sm text-green-300 text-center">
            Forecast run completed — {lastCompletedRun.completed} location{lastCompletedRun.completed !== 1 ? 's' : ''} updated
            {lastCompletedRun.failed > 0 && `, ${lastCompletedRun.failed} failed`}.
            {' '}
            <button
              className="underline font-medium hover:text-green-100"
              onClick={() => {
                refresh();
                setShowRunBanner(false);
              }}
            >
              Refresh
            </button>
          </p>
        </div>
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

      <main className="px-4 py-6">
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
            locations={visibleLocations}
          >
            <WindowFirstShell
              onOpenSettings={() => setShowSettings(true)}
              onSignOut={logout}
              light={todaysLight}
              // The band's nudge exists to get a postcode saved, so it lands ON that field
              // rather than on the settings screen in general — the same handler the map's
              // "you have no postcode" branch uses.
              onSetPostcode={() => setSettingsFocus('postcode')}
              contentDisabled={isDown}
              onShowOnMap={handleShowOnMap}
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
                  variant={VARIANT_MASTHEAD}
                />
              ) : null}
              onEvaluationScoresChange={handleEvaluationScoresChange}
              onSeasonalFeaturesChange={handleSeasonalFeaturesChange}
              locations={visibleLocations}
              tabRequest={tabRequest}
              // Withheld when there is nothing to map, which is the same rule the Operations tab
              // follows and §6's ban on controls that open nothing. `allDates` is empty whenever
              // `GET /api/forecast` returned no rows, and a Map tab onto no dates would be a tab
              // onto a blank.
              mapPane={allDates.length > 0 ? (
                <Suspense fallback={<ViewFallback />}>
                  <WindowFirstMapPane
                    locations={visibleLocations}
                    dates={allDates}
                    selectedDate={effectiveDate}
                    onSelectDate={setSelectedDate}
                    // Without this the pane's event type is whatever it derived at mount — and
                    // because this pane is never unmounted, opening the map at dawn and returning
                    // after sunset would still show the morning's event.
                    autoEventType={autoSelection?.eventType ?? null}
                    handoff={mapTabHandoff}
                    briefingScores={briefingScores}
                    onForecastRun={refresh}
                    seasonalFeatures={seasonalFeatures}
                    homeCoords={homeCoords}
                    homeRadiusMiles={homeRadiusMiles}
                    onOpenSettings={() => setSettingsFocus('postcode')}
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
          </WindowFirstBriefingProvider>
        </PlanErrorBoundary>
      </main>

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

      {(showSettings || settingsFocus) && (
        <UserSettingsModal
          focusField={settingsFocus}
          onClose={() => {
            setShowSettings(false);
            setSettingsFocus(null);
            loadHomeCoords();
            // Close to home is derived from the home postcode AND the local radius, both editable
            // in this modal, so bump a version the panel can depend on. Without it a widened
            // radius appeared to do nothing until a full page reload.
            setHomeSettingsVersion((v) => v + 1);
          }}
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
            // Withheld when there is no Map tab to reach (no forecast dates), because MapOverlay
            // drops the button when no handler arrives and a button onto nothing is what §6 bans.
            onOpenFullMap={allDates.length === 0 ? undefined : openFullMapTab}
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
              handoffLocationName={mapOverlay.handoff.locationName ?? null}
              handoffRegion={mapOverlay.handoff.region ?? null}
              handoffNonce={mapOverlay.nonce}
              focus={mapOverlay.focus}
              emphasiseLocationName={mapOverlay.handoff.locationName ?? null}
              briefingScores={briefingScores}
              onForecastRun={refresh}
              seasonalFeatures={seasonalFeatures}
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
