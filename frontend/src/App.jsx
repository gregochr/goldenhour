import React, { useState } from 'react';
import ViewToggle from './components/ViewToggle.jsx';
import DateStrip from './components/DateStrip.jsx';
import MapView from './components/MapView.jsx';
import ManageView from './components/ManageView.jsx';
import LoginPage from './components/LoginPage.jsx';
import ChangePasswordPage from './components/ChangePasswordPage.jsx';
import SessionExpiryBanner from './components/SessionExpiryBanner.jsx';
import HealthIndicator from './components/HealthIndicator.jsx';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import { useForecasts } from './hooks/useForecasts.js';
import { useHealthStatus } from './hooks/useHealthStatus.js';

/**
 * Auth gate — renders {@link LoginPage} when no token is present,
 * or the main app otherwise. This keeps hooks out of the unauthenticated path.
 */
function AuthGate() {
  const { token, mustChangePassword } = useAuth();
  if (!token) {
    return <LoginPage />;
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
  const { isAdmin, logout, sessionDaysRemaining } = useAuth();
  const { locations, loading, error, refresh } = useForecasts();
  const { status: healthStatus, checkedAt: healthCheckedAt } = useHealthStatus();
  const [viewMode, setViewMode] = useState('map');
  const [selectedDate, setSelectedDate] = useState(null);

  const sortedLocations = [...locations].sort((a, b) => a.name.localeCompare(b.name));
  const visibleLocations = sortedLocations.filter((loc) => loc.enabled !== false);

  // All dates available across any visible (enabled) location, sorted, for the map date strip.
  const allDates = [...new Set(
    visibleLocations.flatMap((loc) => Array.from(loc.forecastsByDate.keys()))
  )].sort();

  // Default to the first available date when data loads.
  const effectiveDate = (selectedDate && allDates.includes(selectedDate))
    ? selectedDate
    : allDates[0] ?? null;

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
            {isAdmin && <HealthIndicator status={healthStatus} checkedAt={healthCheckedAt} />}
            <button
              className="btn-secondary text-xs"
              onClick={logout}
              aria-label="Sign out"
            >
              Sign out
            </button>
            {isAdmin && sessionDaysRemaining !== null && (
              <p className="text-xs text-plex-text-muted">
                Session: {sessionDaysRemaining}d
              </p>
            )}
          </div>
        </div>
      </header>

      <SessionExpiryBanner />

      {!isAdmin && isDown && (
        <div
          className="bg-red-900/40 border-b border-red-700 px-4 py-3 text-center"
          data-testid="backend-down-banner"
        >
          <p className="text-sm text-red-300">
            Service is temporarily unavailable. Data shown may be stale.
          </p>
        </div>
      )}

      <main className={`max-w-4xl mx-auto px-4 py-6${!isAdmin && isDown ? ' opacity-50 pointer-events-none' : ''}`}>
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
              title={healthStatus === 'DOWN' ? 'Backend is down. Please wait...' : ''}
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

        {!loading && !error && sortedLocations.length === 0 && viewMode === 'manage' && (
          <>
            <div className="mb-6">
              <ViewToggle value={viewMode} onChange={setViewMode} isAdmin={isAdmin} />
            </div>
            <ManageView onComplete={refresh} />
          </>
        )}

        {!loading && !error && sortedLocations.length > 0 && allDates.length === 0 && (
          <div className="card text-center py-16">
            <p className="text-plex-text-secondary text-lg mb-4">No forecasts loaded yet</p>
            <p className="text-plex-text-muted text-sm mb-6">Forecasts are generated automatically at 06:00 and 18:00 UTC. Check back in a moment.</p>
            <button className="btn-primary" onClick={refresh}>
              Refresh
            </button>
          </div>
        )}

        {!loading && !error && sortedLocations.length > 0 && allDates.length > 0 && (
          <>
            <div className="mb-6">
              <ViewToggle value={viewMode} onChange={setViewMode} isAdmin={isAdmin} />
            </div>

            {viewMode === 'map' && effectiveDate && (
              <DateStrip
                dates={allDates}
                selectedDate={effectiveDate}
                onSelect={setSelectedDate}
              />
            )}

            {viewMode === 'map' && (
              <MapView locations={visibleLocations} date={effectiveDate} />
            )}

            {viewMode === 'manage' && (
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
