import React, { useState } from 'react';
import ForecastTimeline from './components/ForecastTimeline.jsx';
import LocationTabs from './components/LocationTabs.jsx';
import LocationCompactCard from './components/LocationCompactCard.jsx';
import LocationTypeBadges from './components/LocationTypeBadges.jsx';
import ViewToggle from './components/ViewToggle.jsx';
import DateStrip from './components/DateStrip.jsx';
import MapView from './components/MapView.jsx';
import ManageView from './components/ManageView.jsx';
import LoginPage from './components/LoginPage.jsx';
import ChangePasswordPage from './components/ChangePasswordPage.jsx';
import SessionExpiryBanner from './components/SessionExpiryBanner.jsx';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import { useForecasts } from './hooks/useForecasts.js';
import { runForecast } from './api/forecastApi.js';

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
  const [selectedTab, setSelectedTab] = useState(0);
  const [viewMode, setViewMode] = useState('map');
  const [selectedDate, setSelectedDate] = useState(null);

  const sortedLocations = [...locations].sort((a, b) => a.name.localeCompare(b.name));
  const location = sortedLocations[selectedTab] ?? null;

  // All dates available across any location, sorted, for the By Date strip.
  const allDates = [...new Set(
    sortedLocations.flatMap((loc) => Array.from(loc.forecastsByDate.keys()))
  )].sort();

  // Default to the first available date when data loads.
  const effectiveDate = (selectedDate && allDates.includes(selectedDate))
    ? selectedDate
    : allDates[0] ?? null;

  async function handleRerun(date, type) {
    await runForecast(date, location.name, type);
    await refresh();
  }

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

        {!loading && !error && sortedLocations.length > 0 && (
          <>
            <div className="mb-6 flex items-center justify-between">
              <ViewToggle value={viewMode} onChange={setViewMode} isAdmin={isAdmin} />
              <button
                className="btn-secondary text-xs"
                onClick={refresh}
                disabled={loading}
                aria-label="Refresh forecast"
              >
                {loading ? 'Loading…' : '↻ Reload data'}
              </button>
            </div>

            {viewMode === 'location' && location && (
              <>
                <LocationTabs
                  locations={sortedLocations}
                  selectedIndex={selectedTab}
                  onSelect={setSelectedTab}
                />
                {(location.goldenHourType || location.locationType?.length > 0 || location.tideType?.length > 0) && (
                  <div className="mb-4">
                    <LocationTypeBadges
                      goldenHourType={location.goldenHourType}
                      locationType={location.locationType}
                      tideType={location.tideType}
                    />
                  </div>
                )}
                <ForecastTimeline
                  forecastsByDate={location.forecastsByDate}
                  outcomes={location.outcomes}
                  locationLat={location.lat}
                  locationLon={location.lon}
                  locationName={location.name}
                  onOutcomeSaved={refresh}
                  onRerun={handleRerun}
                />
              </>
            )}

            {(viewMode === 'date' || viewMode === 'map') && effectiveDate && (
              <DateStrip
                dates={allDates}
                selectedDate={effectiveDate}
                onSelect={setSelectedDate}
              />
            )}

            {viewMode === 'date' && effectiveDate && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {sortedLocations.map((loc) => {
                  const dayData = loc.forecastsByDate.get(effectiveDate);
                  return (
                    <LocationCompactCard
                      key={loc.name}
                      locationName={loc.name}
                      sunrise={dayData?.sunrise ?? null}
                      sunset={dayData?.sunset ?? null}
                      goldenHourType={loc.goldenHourType}
                      locationType={loc.locationType}
                      tideType={loc.tideType}
                    />
                  );
                })}
              </div>
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
