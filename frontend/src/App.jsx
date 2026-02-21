import React from 'react';
import ForecastTimeline from './components/ForecastTimeline.jsx';
import { useForecasts } from './hooks/useForecasts.js';

const DEFAULT_LAT = 54.7753;
const DEFAULT_LON = -1.5849;

/**
 * Root application component. Fetches forecast data and renders the timeline.
 */
export default function App() {
  const { forecastsByDate, outcomes, locationName, loading, error, refresh } =
    useForecasts();

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="border-b border-gray-800 px-4 py-4">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-gray-100">
              Golden Hour
            </h1>
            <p className="text-sm text-gray-400">
              {locationName} — Sunrise &amp; Sunset Forecast
            </p>
          </div>
          <button
            className="btn-secondary text-xs"
            onClick={refresh}
            disabled={loading}
            aria-label="Refresh forecast"
          >
            {loading ? 'Loading…' : '↻ Refresh'}
          </button>
        </div>
      </header>

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

        {!loading && !error && (
          <ForecastTimeline
            forecastsByDate={forecastsByDate}
            outcomes={outcomes}
            locationLat={DEFAULT_LAT}
            locationLon={DEFAULT_LON}
            locationName={locationName}
            onOutcomeSaved={refresh}
          />
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
