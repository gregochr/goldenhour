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
 */
export default function TideIndicator({ locationName, date }) {
  const [tides, setTides] = useState(null);

  useEffect(() => {
    if (!locationName || !date) return;

    let cancelled = false;
    fetchTidesForDate(locationName, date)
      .then((data) => {
        if (!cancelled) setTides(data);
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
      className="flex items-center flex-wrap gap-x-3 gap-y-1 pt-2 border-t border-gray-800"
    >
      {tides.map((tide) => (
        <span key={tide.id} className="text-xs text-gray-400 whitespace-nowrap">
          {tide.type === 'HIGH' ? '↑' : '↓'}{' '}
          {formatEventTimeUk(tide.eventTime)}{' '}
          <span className="text-gray-500">{parseFloat(tide.heightMetres).toFixed(1)}m</span>
        </span>
      ))}
    </div>
  );
}

TideIndicator.propTypes = {
  locationName: PropTypes.string.isRequired,
  date: PropTypes.string.isRequired,
};
