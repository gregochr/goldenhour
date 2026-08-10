import React, { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { computeAutoSelection } from './utils/conversions.js';
import ViewToggle from './components/ViewToggle.jsx';
import DateStrip from './components/DateStrip.jsx';
import { buildMapOverlay } from './utils/mapOverlay.js';
import LoginPage from './components/LoginPage.jsx';
import RegisterPage from './components/RegisterPage.jsx';
import ChangePasswordPage from './components/ChangePasswordPage.jsx';
import SessionExpiryBanner from './components/SessionExpiryBanner.jsx';
import AuroraBanner from './components/AuroraBanner.jsx';
import NlcSightingBanner from './components/NlcSightingBanner.jsx';
import DailyBriefing from './components/DailyBriefing.jsx';
import HealthIndicator from './components/HealthIndicator.jsx';
import BrandLockup from './components/shared/BrandLockup.jsx';
import UserSettingsModal from './components/UserSettingsModal.jsx';
import { getSettings } from './api/settingsApi.js';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import { AuroraStatusProvider } from './context/AuroraStatusContext.jsx';
import { useForecasts } from './hooks/useForecasts.js';
import { useHealthStatus } from './hooks/useHealthStatus.js';
import { useRunNotifications } from './hooks/useRunNotifications.js';
import useAfterFirstPaint from './hooks/useAfterFirstPaint.js';
import usePlanLayout, { PLAN_V1, PLAN_V2 } from './hooks/usePlanLayout.js';
import WindowFirstShell from './components/WindowFirstShell.jsx';
import PlanLayoutErrorBoundary from './components/PlanLayoutErrorBoundary.jsx';
import { WindowFirstBriefingProvider } from './context/WindowFirstBriefingContext.jsx';

// Code-split the heavy, rarely-first-viewed subtrees so they stay out of the initial bundle:
// the Leaflet map stack (Plan is the default tab; the map is a drill-down) and the admin-only
// Manage view (which also pulls in recharts). They load on demand behind the Suspense boundaries.
const MapView = lazy(() => import('./components/MapView.jsx'));
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
  const { isAdmin, logout, username, sessionDaysRemaining, token } = useAuth();
  const [showSettings, setShowSettings] = useState(false);
  // Which Plan tab this browser renders. Held here rather than read independently wherever it is
  // needed: `useLocalStorageState` is per-instance, so a second reader would keep its own copy and
  // toggling in the settings modal would write storage without re-rendering the page behind it.
  const [planLayout, setPlanLayout] = usePlanLayout();
  const { locations, loading, error, refresh } = useForecasts();
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
  const [viewMode, setViewModeState] = useState(() => {
    const hash = window.location.hash.replace('#', '');
    if (hash === 'plan') return 'plan';
    if (hash === 'map') return 'map';
    if (hash.startsWith('manage') && isAdmin) return 'manage';
    return 'plan';
  });

  /** Pending handoff from Plan tab to Map tab (event type to pre-select). */
  const [mapHandoff, setMapHandoff] = useState(null);
  /** Map overlay opened over the Plan tab (null = closed). Reuses the same handoff/date as the Map tab. */
  const [mapOverlay, setMapOverlay] = useState(null);
  /** Monotonic counter so repeat taps on the same location re-trigger the handoff. */
  const handoffNonce = useRef(0);

  /** Briefing evaluation scores lifted from DailyBriefing, passed to MapView. */
  const [briefingScores, setBriefingScores] = useState(new Map());
  const handleEvaluationScoresChange = useCallback((scores) => setBriefingScores(scores), []);

  /** Seasonal features lifted from DailyBriefing, passed to MapView. */
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

  /** Update viewMode and sync to URL hash. */
  const setViewMode = (mode) => {
    setViewModeState(mode);
    window.location.hash = mode;
  };

  // React to hash changes (e.g. AuroraBanner setting window.location.hash = 'map')
  useEffect(() => {
    function handleHashChange() {
      const hash = window.location.hash.replace('#', '');
      if (hash === 'plan') setViewModeState('plan');
      else if (hash === 'map') setViewModeState('map');
      else if (hash.startsWith('manage') && isAdmin) setViewModeState('manage');
    }
    window.addEventListener('hashchange', handleHashChange);
    return () => window.removeEventListener('hashchange', handleHashChange);
  }, [isAdmin]);
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
  const todayStr = new Date().toISOString().slice(0, 10);
  const tomorrowStr = (() => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    return d.toISOString().slice(0, 10);
  })();
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
    // Keep the shared handoff in sync so the "Open the full Map tab →" escape hatch lands focused.
    setMapHandoff({ ...overlay.handoff, nonce });
    setMapOverlay({ ...overlay, nonce, date: trigger.date });
  };

  /**
   * Aurora banner "View on map".
   *
   * <p>The banner sits above the Plan-layout branch, so it is live in both arms — but its v1 action
   * is "switch to the Map tab", and the window-first arm has no Map tab. There the press used to set
   * a tab state nothing rendered and write a hash nothing answered: a control that could not act,
   * which §6 bans. The window-first arm reaches the map through the same overlay every plan card
   * uses, so the banner is routed through that instead of being hidden.
   *
   * <p>The v1 path below is byte-identical to what it has always been. Both arms end up handing
   * `MapView` the same `handoffEventType`, which is what makes this a route rather than a second
   * behaviour.
   */
  const handleAuroraViewOnMap = () => {
    if (planLayout === PLAN_V2) {
      handleShowOnMap({ kind: 'aurora', date: todayStr });
      return;
    }
    setMapHandoff({ eventType: 'AURORA', nonce: handoffNonce.current++ });
    setViewMode('map');
  };

  /** Close the overlay and hand off to the full Map tab, landing where the overlay was focused. */
  const openFullMapTab = () => {
    setMapOverlay(null);
    setViewMode('map');
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
      {/* Suppressed for the window-first arm, which renders its own masthead carrying the same
          wordmark, cog and Sign out. Rendering both would stack two headers and two wordmarks. */}
      {planLayout !== PLAN_V2 && (
      <header className="border-b border-plex-border px-4 py-6">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <BrandLockup variant="header" />
          <div className="flex flex-col items-end gap-1">
            {isAdmin && <HealthIndicator status={healthStatus} degraded={healthDegraded} checkedAt={healthCheckedAt} build={healthBuild} services={healthServices} database={healthDatabase} session={healthSession} appVersion={healthAppVersion} startedAt={healthStartedAt} />}
            <div className="flex items-center gap-2">
              <button
                className="text-plex-text-muted hover:text-plex-text transition-colors"
                onClick={() => setShowSettings(true)}
                aria-label="Settings"
                data-testid="settings-cog-btn"
                title="Settings"
              >
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4">
                  <path fillRule="evenodd" d="M7.84 1.804A1 1 0 0 1 8.82 1h2.36a1 1 0 0 1 .98.804l.331 1.652a6.993 6.993 0 0 1 1.929 1.115l1.598-.54a1 1 0 0 1 1.186.447l1.18 2.044a1 1 0 0 1-.205 1.251l-1.267 1.113a7.047 7.047 0 0 1 0 2.228l1.267 1.113a1 1 0 0 1 .206 1.25l-1.18 2.045a1 1 0 0 1-1.187.447l-1.598-.54a6.993 6.993 0 0 1-1.929 1.115l-.33 1.652a1 1 0 0 1-.98.804H8.82a1 1 0 0 1-.98-.804l-.331-1.652a6.993 6.993 0 0 1-1.929-1.115l-1.598.54a1 1 0 0 1-1.186-.447l-1.18-2.044a1 1 0 0 1 .205-1.251l1.267-1.114a7.05 7.05 0 0 1 0-2.227L1.821 7.773a1 1 0 0 1-.206-1.25l1.18-2.045a1 1 0 0 1 1.187-.447l1.598.54A6.993 6.993 0 0 1 7.51 3.456l.33-1.652ZM10 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" clipRule="evenodd" />
                </svg>
              </button>
              <button
                className="btn-secondary text-xs"
                onClick={logout}
                aria-label="Sign out"
              >
                Sign out
              </button>
            </div>
            {(username || (isAdmin && sessionDaysRemaining !== null)) && (
              <p className="text-xs text-plex-text-muted">
                {username}{username && isAdmin && sessionDaysRemaining !== null && ' · '}{isAdmin && sessionDaysRemaining !== null && `${sessionDaysRemaining}d`}
              </p>
            )}
          </div>
        </div>
      </header>
      )}

      <SessionExpiryBanner />
      <div className="max-w-4xl mx-auto px-4 mt-4">
        <AuroraBanner onViewOnMap={handleAuroraViewOnMap} />
        <div className="mt-2">
          {/* Inert in the window-first arm, which has no Map tab for it to switch to. Unlike the
              aurora banner beside it there is nothing to re-route: v1's action is a bare tab switch
              with no event type, no filter and no location. */}
          <NlcSightingBanner interactive={planLayout !== PLAN_V2} />
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

      {/* The Plan-layout flag branches ABOVE the tab bar, not inside the Plan pane. The
          window-first design has its own tabs — a different control from ViewToggle — and its
          subtree owns its own shell and tab state, so branching here is what keeps DailyBriefing
          and ViewToggle untouched while both layouts are alive. */}
      {planLayout === PLAN_V2 ? (
        <main className="px-4 py-6">
          {/* isDown is passed DOWN rather than applied here: the shell's masthead carries the cog,
              Sign out and the exit hatch, and greying the whole subtree would strand a user with no
              route out of a broken app. The v1 arm has never had this problem because its header
              sits outside the gated element. */}
          {/* The provider is mounted HERE rather than beside AuroraStatusProvider so it exists only
              while the v2 arm is on screen. Hoisted, it would poll /api/briefing for every v1 user
              too — a second request on the same 10-minute tick as DailyBriefing's, and a second
              focus listener firing both. App.jsx's flag branch is a hard either/or, and this keeps
              it one. */}
          {/* INSIDE the flag branch, and that placement is load-bearing rather than tidy. The flag
              is what turns an ordinary render crash into a trap: the header above is suppressed for
              this arm, so the cog and Sign out live inside the subtree that would die, the settings
              modal is a sibling of this ternary and dies with the tree, and the flag survives the
              reload — blank page, reload, blank page. Sitting here, the boundary is discarded when
              the branch flips, so recovery needs no reset logic; hoisted above the ternary it would
              survive the flip and show its fallback over a healthy arm. The current Plan is
              deliberately NOT wrapped: its honest recovery is not "go to the other arm". */}
          <PlanLayoutErrorBoundary onRecover={() => setPlanLayout(PLAN_V1)}>
            <WindowFirstBriefingProvider homeSettingsVersion={homeSettingsVersion}>
              <WindowFirstShell
                onExit={() => setPlanLayout(PLAN_V1)}
                onOpenSettings={() => setShowSettings(true)}
                onSignOut={logout}
                contentDisabled={isDown}
                onShowOnMap={handleShowOnMap}
                onEvaluationScoresChange={handleEvaluationScoresChange}
                onSeasonalFeaturesChange={handleSeasonalFeaturesChange}
                locations={visibleLocations}
              />
            </WindowFirstBriefingProvider>
          </PlanLayoutErrorBoundary>
        </main>
      ) : (
        <main className={`max-w-4xl mx-auto px-4 py-6${isDown ? ' opacity-50 pointer-events-none' : ''}`}>
          <>
          {/* Tab shell — always visible; needs no server data, so it paints instantly on refresh. */}
          <div className="mb-6">
            <ViewToggle value={viewMode} onChange={setViewMode} isAdmin={isAdmin} />
          </div>

          {/* PLAN (default tab) renders immediately: DailyBriefing owns its own briefing fetch and
              skeleton and tolerates an empty locations list, so Best Bet / Hot Topics / regions paint
              without waiting on the forecast + locations + outcomes load. */}
          {viewMode === 'plan' && (
            <DailyBriefing locations={visibleLocations} onShowOnMap={handleShowOnMap} onEvaluationScoresChange={handleEvaluationScoresChange} onSeasonalFeaturesChange={handleSeasonalFeaturesChange} homeSettingsVersion={homeSettingsVersion} />
          )}

          {/* MAP needs the forecast/location data — keep the loading / error / empty gating here. */}
          {viewMode === 'map' && (
            <>
              {loading && (
                <div className="flex justify-center py-16">
                  <p className="text-plex-text-secondary animate-pulse">Loading forecast…</p>
                </div>
              )}

              {!loading && error && (
                <div
                  data-testid="error-message"
                  className="card border-red-900/50 text-center py-8"
                  role="alert"
                >
                  <p className="text-red-400 font-medium mb-2">Unable to load forecast</p>
                  <p className="text-plex-text-secondary text-sm mb-4">{error}</p>
                  <button
                    className="btn-primary"
                    onClick={refresh}
                    disabled={healthStatus === 'DOWN'}
                  >
                    Try again
                  </button>
                </div>
              )}

              {!loading && !error && allDates.length > 0 && effectiveDate && (
                <DateStrip
                  dates={allDates}
                  selectedDate={effectiveDate}
                  onSelect={setSelectedDate}
                />
              )}

              {!loading && !error && allDates.length > 0 && (
                <Suspense fallback={<ViewFallback />}>
                  <MapView
                    locations={visibleLocations}
                    date={effectiveDate}
                    autoEventType={autoSelection?.eventType ?? null}
                    handoffEventType={mapHandoff?.eventType ?? null}
                    handoffFilterAction={mapHandoff?.filterAction ?? null}
                    handoffLocationName={mapHandoff?.locationName ?? null}
                    handoffRegion={mapHandoff?.region ?? null}
                    handoffNonce={mapHandoff?.nonce ?? null}
                    briefingScores={briefingScores}
                    onForecastRun={refresh}
                    seasonalFeatures={seasonalFeatures}
                    // "Centre on home" reads the coordinates already resolved for Close to home —
                    // the postcode was geocoded once, server-side, when it was saved, and this
                    // state is refreshed when the settings modal closes. No second geocoding path,
                    // and no lookup on a click.
                    homeCoords={homeCoords}
                    homeRadiusMiles={homeRadiusMiles}
                    onOpenSettings={() => setSettingsFocus('postcode')}
                  />
                </Suspense>
              )}

              {!loading && !error && allDates.length === 0 && (
                <div className="card text-center py-16">
                  <p className="text-plex-text-secondary text-lg mb-4">No forecasts loaded yet</p>
                  <p className="text-plex-text-muted text-sm mb-6">Forecasts are generated automatically at 06:00 and 18:00 UTC. Check back in a moment.</p>
                  <div className="flex justify-center gap-3">
                    <button className="btn-primary" onClick={refresh}>
                      Refresh
                    </button>
                    {isAdmin && (
                      <button className="btn-secondary" onClick={() => setViewMode('manage')}>
                        Manage Locations
                      </button>
                    )}
                  </div>
                </div>
              )}
            </>
          )}

          {/* MANAGE — admin only. */}
          {viewMode === 'manage' && isAdmin && (
            <Suspense fallback={<ViewFallback />}>
              <ManageView onComplete={refresh} />
            </Suspense>
          )}
          </>
        </main>
      )}

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
          planLayout={planLayout}
          onPlanLayoutChange={setPlanLayout}
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
            // The v2 arm renders no Map pane, so the hatch is withheld rather than naming a
            // destination it cannot reach. MapOverlay drops the button when no handler arrives.
            onOpenFullMap={planLayout === PLAN_V2 ? undefined : openFullMapTab}
          >
            <MapView
              locations={visibleLocations}
              date={mapOverlay.date ?? effectiveDate}
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
