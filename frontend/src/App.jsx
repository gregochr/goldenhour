import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { computeAutoSelection } from './utils/conversions.js';
import ViewToggle from './components/ViewToggle.jsx';
import DateStrip from './components/DateStrip.jsx';
import MapView from './components/MapView.jsx';
import ManageView from './components/ManageView.jsx';
import LoginPage from './components/LoginPage.jsx';
import RegisterPage from './components/RegisterPage.jsx';
import ChangePasswordPage from './components/ChangePasswordPage.jsx';
import SessionExpiryBanner from './components/SessionExpiryBanner.jsx';
import AuroraBanner from './components/AuroraBanner.jsx';
import DailyBriefing from './components/DailyBriefing.jsx';
import HealthIndicator from './components/HealthIndicator.jsx';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import { useForecasts } from './hooks/useForecasts.js';
import { useHealthStatus } from './hooks/useHealthStatus.js';
import { useRunNotifications } from './hooks/useRunNotifications.js';

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
  return <AppInner />;
}

/**
 * Inner app component — only rendered when the user is authenticated.
 */
function AppInner() {
  const { isAdmin, logout, username, sessionDaysRemaining, token } = useAuth();
  const { locations, loading, error, refresh } = useForecasts();
  const {
    status: healthStatus, degraded: healthDegraded, checkedAt: healthCheckedAt,
    build: healthBuild, services: healthServices, database: healthDatabase,
    session: healthSession,
  } = useHealthStatus();
  const { lastCompletedRun } = useRunNotifications(!!token);
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

  /** Briefing evaluation scores lifted from DailyBriefing, passed to MapView. */
  const [briefingScores, setBriefingScores] = useState(new Map());
  const handleEvaluationScoresChange = useCallback((scores) => setBriefingScores(scores), []);

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

  /** Called from Plan tab drill-down — switches to Map tab with pre-selected date + event type. */
  const handleShowOnMap = (date, eventType) => {
    setSelectedDate(date);
    setMapHandoff({ eventType });
    setViewMode('map');
  };

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
  const defaultDate = allDates.find((d) => d >= todayStr) ?? allDates[allDates.length - 1] ?? null;
  const autoDate = autoSelection?.date ?? null;
  const effectiveDate = (selectedDate && allDates.includes(selectedDate))
    ? selectedDate
    : (autoDate && allDates.includes(autoDate))
      ? autoDate
      : defaultDate;

  // Show banner when a run completes, auto-dismiss after 15 seconds
  useEffect(() => {
    if (!lastCompletedRun) return;
    setShowRunBanner(true);
    const timer = setTimeout(() => setShowRunBanner(false), 15000);
    return () => clearTimeout(timer);
  }, [lastCompletedRun]);

  const isDown = healthStatus === 'DOWN';

  return (
    <div className="min-h-screen bg-plex-bg">
      <header className="border-b border-plex-border px-4 py-6">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <div>
            <h1 className="text-4xl font-extrabold tracking-tight text-plex-text flex items-center gap-3">
              <img src="/logo.png" alt="" className="h-10 w-10 object-contain" />
              PhotoCast
            </h1>
            <p className="text-base text-plex-text-secondary mt-1">
              AI Forecasts for Fiery Skies & Golden Light
            </p>
          </div>
          <div className="flex flex-col items-end gap-1">
            {isAdmin && <HealthIndicator status={healthStatus} degraded={healthDegraded} checkedAt={healthCheckedAt} build={healthBuild} services={healthServices} database={healthDatabase} session={healthSession} />}
            <button
              className="btn-secondary text-xs"
              onClick={logout}
              aria-label="Sign out"
            >
              Sign out
            </button>
            {(username || (isAdmin && sessionDaysRemaining !== null)) && (
              <p className="text-xs text-plex-text-muted">
                {username}{username && isAdmin && sessionDaysRemaining !== null && ' · '}{isAdmin && sessionDaysRemaining !== null && `${sessionDaysRemaining}d`}
              </p>
            )}
          </div>
        </div>
      </header>

      <SessionExpiryBanner />
      <AuroraBanner />

      {showRunBanner && lastCompletedRun && (
        <div
          className="bg-green-900/40 border-b border-green-700 px-4 py-3 text-center"
          data-testid="run-complete-banner"
        >
          <p className="text-sm text-green-300">
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
          className="bg-red-900/40 border-b border-red-700 px-4 py-3 text-center"
          data-testid="backend-down-banner"
        >
          <p className="text-sm text-red-300">
            Service is temporarily unavailable. Data shown may be stale.
          </p>
        </div>
      )}

      <main className={`max-w-4xl mx-auto px-4 py-6${isDown ? ' opacity-50 pointer-events-none' : ''}`}>
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

        {!loading && !error && sortedLocations.length === 0 && viewMode !== 'manage' && (
          <div className="card text-center py-16">
            <p className="text-plex-text-secondary text-lg mb-4">No forecasts available</p>
            <p className="text-plex-text-muted text-sm mb-6">Add locations in the Manage tab to get started</p>
            <button
              className="btn-primary"
              onClick={() => setViewMode('manage')}
            >
              Go to Manage
            </button>
          </div>
        )}

        {!loading && !error && sortedLocations.length === 0 && viewMode === 'manage' && isAdmin && (
          <>
            <div className="mb-6">
              <ViewToggle value={viewMode} onChange={setViewMode} isAdmin={isAdmin} />
            </div>
            <ManageView onComplete={refresh} />
          </>
        )}

        {!loading && !error && sortedLocations.length > 0 && (
          <>
            <div className="mb-6">
              <ViewToggle value={viewMode} onChange={setViewMode} isAdmin={isAdmin} />
            </div>

            {viewMode === 'plan' && (
              <DailyBriefing locations={visibleLocations} onShowOnMap={handleShowOnMap} onEvaluationScoresChange={handleEvaluationScoresChange} />
            )}

            {viewMode === 'map' && allDates.length > 0 && effectiveDate && (
              <DateStrip
                dates={allDates}
                selectedDate={effectiveDate}
                onSelect={setSelectedDate}
              />
            )}

            {viewMode === 'map' && allDates.length > 0 && (
              <MapView
                locations={visibleLocations}
                date={effectiveDate}
                autoEventType={autoSelection?.eventType ?? null}
                handoffEventType={mapHandoff?.eventType ?? null}
                briefingScores={briefingScores}
              />
            )}

            {viewMode === 'map' && allDates.length === 0 && (
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

            {viewMode === 'manage' && isAdmin && (
              <ManageView onComplete={refresh} />
            )}
          </>
        )}
      </main>

      <footer className="border-t border-plex-border px-4 py-4 mt-8">
        <div className="max-w-4xl mx-auto text-center text-xs text-plex-text-muted">
          <p>
            Made with ☕ by Chris —{' '}
            <a
              href="https://buymeacoffee.com/gregorychris"
              target="_blank"
              rel="noopener noreferrer"
              className="text-plex-text-secondary hover:text-plex-text underline"
            >
              Buy me a coffee
            </a>
          </p>
        </div>
      </footer>
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
