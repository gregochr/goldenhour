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
  const healthStatus = useHealthStatus();
  const [viewMode, setViewMode] = useState('map');
  const [selectedDate, setSelectedDate] = useState(null);

  const sortedLocations = [...locations].sort((a, b) => a.name.localeCompare(b.name));

  // All dates available across any location, sorted, for the map date strip.
  const allDates = [...new Set(
    sortedLocations.flatMap((loc) => Array.from(loc.forecastsByDate.keys()))
  )].sort();

  // Default to the first available date when data loads.
  const effectiveDate = (selectedDate && allDates.includes(selectedDate))
    ? selectedDate
    : allDates[0] ?? null;

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="border-b border-gray-800 px-4 py-6">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <div>
            <h1 className="text-4xl font-extrabold tracking-tight text-gray-100">
              🌅 Golden Hour
            </h1>
            <p className="text-base text-gray-400 mt-1">
              Sunrise &amp; Sunset Colour Forecast
            </p>
          </div>
          <div className="flex flex-col items-end gap-1">
            {isAdmin && <HealthIndicator status={healthStatus} />}
            <button
              className="btn-secondary text-xs"
              onClick={logout}
              aria-label="Sign out"
            >
              Sign out
            </button>
            {isAdmin && sessionDaysRemaining !== null && (
              <p className="text-xs text-gray-600">
                Session: {sessionDaysRemaining}d
              </p>
            )}
          </div>
        </div>
      </header>

      <SessionExpiryBanner />

      <main className="max-w-4xl mx-auto px-4 py-6">
        {loading && (
          <div className="flex justify-center py-16">
            <p className="text-gray-400 animate-pulse">Loading forecast…</p>
          </div>
        )}

        {!loading && error && (
          <div
            data-testid="error-message"
            className="card border border-red-900/50 text-center py-8"
            role="alert"
          >
            <p className="text-red-400 font-medium mb-2">Unable to load forecast</p>
            <p className="text-gray-400 text-sm mb-4">{error}</p>
            <button className="btn-primary" onClick={refresh}>
              Try again
            </button>
          </div>
        )}

        {!loading && !error && sortedLocations.length === 0 && (
          <div className="card border border-gray-800 text-center py-16">
            <p className="text-gray-400 text-lg mb-4">No forecasts available</p>
            <p className="text-gray-500 text-sm mb-6">Add locations in the Manage tab to get started</p>
            <button
              className="btn-primary"
              onClick={() => setViewMode('manage')}
            >
              Go to Manage
            </button>
          </div>
        )}

        {!loading && !error && sortedLocations.length > 0 && allDates.length === 0 && (
          <div className="card border border-gray-800 text-center py-16">
            <p className="text-gray-400 text-lg mb-4">No forecasts loaded yet</p>
            <p className="text-gray-500 text-sm mb-6">Forecasts are generated automatically at 06:00 and 18:00 UTC. Check back in a moment.</p>
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
              <MapView locations={sortedLocations} date={effectiveDate} />
            )}

            {viewMode === 'manage' && (
              <ManageView onComplete={refresh} />
            )}
          </>
        )}
      </main>

      <footer className="border-t border-gray-800 px-4 py-4 mt-8">
        <div className="max-w-4xl mx-auto text-center text-xs text-gray-600">
          <p>
            Made with ☕ by Chris —{' '}
            <a
              href="https://buymeacoffee.com/gregorychris"
              target="_blank"
              rel="noopener noreferrer"
              className="text-gray-500 hover:text-gray-300 underline"
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
