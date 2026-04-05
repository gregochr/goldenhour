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
import UserSettingsModal from './components/UserSettingsModal.jsx';
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
  const [showSettings, setShowSettings] = useState(false);
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
              AI sunrise, sunset, and aurora forecasting
            </p>
          </div>
          <div className="flex flex-col items-end gap-1">
            {isAdmin && <HealthIndicator status={healthStatus} degraded={healthDegraded} checkedAt={healthCheckedAt} build={healthBuild} services={healthServices} database={healthDatabase} session={healthSession} />}
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

      <SessionExpiryBanner />
      <div className="max-w-4xl mx-auto px-4 mt-4">
        <AuroraBanner />
      </div>

      {showRunBanner && lastCompletedRun && (
        <div
          className="bg-green-900/40 border-b border-green-700 px-4 py-3"
          data-testid="run-complete-banner"
        >
          <p className="max-w-4xl mx-auto text-sm text-green-300 text-center">
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
        <div className="max-w-4xl mx-auto px-4 mt-2">
          <div
            className="bg-red-900/40 border border-red-700 rounded-lg px-4 py-3"
            data-testid="backend-down-banner"
          >
            <p className="text-sm text-red-300 text-center">
              Service is temporarily unavailable. Data shown may be stale.
            </p>
          </div>
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
                onForecastRun={refresh}
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

      {showSettings && (
        <UserSettingsModal
          onClose={() => setShowSettings(false)}
          onDriveTimesRefreshed={refresh}
        />
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
