import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { fetchTidesForDate } from '../api/forecastApi.js';
import { formatEventTimeUk } from '../utils/conversions.js';

/**
 * Fetches and displays the full daily tide schedule for a coastal location.
 * Shows all HIGH and LOW extremes for the given date in chronological order.
 * Returns null for non-coastal locations or when no data is available.
 *
 * @param {object} props
 * @param {string} props.locationName - The configured location name.
 * @param {string} props.date - Target date in ISO format (YYYY-MM-DD).
 * @param {function} [props.onFetchedAt] - Called with the fetchedAt timestamp when tide data loads.
 */
export default function TideIndicator({ locationName, date, onFetchedAt }) {
  const [tides, setTides] = useState(null);

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
  }, [locationName, date]);

  if (!tides || tides.length === 0) return null;

  return (
    <div
      data-testid="tide-indicator"
      className="flex items-center flex-wrap gap-x-3 gap-y-1 pt-2 border-t border-plex-border"
    >
      {tides.map((tide) => (
        <span key={tide.id} className="text-xs text-plex-text-secondary whitespace-nowrap">
          {tide.type === 'HIGH' ? '↑' : '↓'}{' '}
          {formatEventTimeUk(tide.eventTime)}{' '}
          <span className="text-plex-text-muted">{parseFloat(tide.heightMetres).toFixed(1)}m</span>
        </span>
      ))}
    </div>
  );
}

TideIndicator.propTypes = {
  locationName: PropTypes.string.isRequired,
  date: PropTypes.string.isRequired,
  onFetchedAt: PropTypes.func,
};
