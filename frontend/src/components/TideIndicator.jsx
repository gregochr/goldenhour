import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { fetchTidesForDate, fetchTideStats } from '../api/forecastApi.js';
import { formatEventTimeUk } from '../utils/conversions.js';

/**
 * Fetches and displays the full daily tide schedule for a coastal location,
 * plus historical average tide heights for cross-location comparison.
 * Returns null for non-coastal locations or when no data is available.
 *
 * @param {object} props
 * @param {string} props.locationName - The configured location name.
 * @param {string} props.date - Target date in ISO format (YYYY-MM-DD).
 * @param {function} [props.onFetchedAt] - Called with the fetchedAt timestamp when tide data loads.
 */
export default function TideIndicator({ locationName, date, onFetchedAt }) {
  const [tides, setTides] = useState(null);
  const [stats, setStats] = useState(null);

  useEffect(() => {
    if (!locationName || !date) return;

    let cancelled = false;
    fetchTidesForDate(locationName, date)
      .then((data) => {
        if (!cancelled) {
          setTides(data);
          if (onFetchedAt && data.length > 0 && data[0].fetchedAt) {
            onFetchedAt(data[0].fetchedAt);
          }
        }
      })
      .catch(() => {
        if (!cancelled) setTides([]);
      });

    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locationName, date]);

  useEffect(() => {
    if (!locationName) return;

    let cancelled = false;
    fetchTideStats(locationName)
      .then((data) => {
        if (!cancelled) setStats(data);
      })
      .catch(() => {
        if (!cancelled) setStats(null);
      });

    return () => { cancelled = true; };
  }, [locationName]);

  if (!tides || tides.length === 0) return null;

  return (
    <div data-testid="tide-indicator">
      <div className="flex items-center flex-wrap gap-x-3 gap-y-1 pt-2 border-t border-plex-border">
        {tides.map((tide) => (
          <span key={tide.id} className="text-xs text-plex-text-secondary whitespace-nowrap">
            {tide.type === 'HIGH' ? '↑' : '↓'}{' '}
            {formatEventTimeUk(tide.eventTime)}{' '}
            <span className="text-plex-text-muted">{parseFloat(tide.heightMetres).toFixed(1)}m</span>
          </span>
        ))}
      </div>
      {stats && (
        <div
          data-testid="tide-stats"
          className="text-[10px] text-plex-text-muted mt-1"
        >
          Typical range:{' '}
          {stats.avgHighMetres != null && (
            <span>
              ↑ avg {parseFloat(stats.avgHighMetres).toFixed(1)}m
              (max {parseFloat(stats.maxHighMetres).toFixed(1)}m)
            </span>
          )}
          {stats.avgHighMetres != null && stats.avgLowMetres != null && ' · '}
          {stats.avgLowMetres != null && (
            <span>
              ↓ avg {parseFloat(stats.avgLowMetres).toFixed(1)}m
              (min {parseFloat(stats.minLowMetres).toFixed(1)}m)
            </span>
          )}
        </div>
      )}
    </div>
  );
}

TideIndicator.propTypes = {
  locationName: PropTypes.string.isRequired,
  date: PropTypes.string.isRequired,
  onFetchedAt: PropTypes.func,
};
