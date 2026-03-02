import React from 'react';
import PropTypes from 'prop-types';
import { mpsToMph, degreesToCompass } from '../utils/conversions.js';

/**
 * Displays wind speed and direction with a simple arrow compass.
 *
 * @param {object} props
 * @param {number|string} props.windSpeed - Wind speed in metres per second.
 * @param {number} props.windDirection - Wind direction in degrees (0–360).
 */
export default function WindIndicator({ windSpeed, windDirection }) {
  const mph = mpsToMph(windSpeed);
  const compass = degreesToCompass(windDirection);

  return (
    <div data-testid="wind-indicator" className="flex flex-col items-center gap-1">
      <p className="text-xs text-plex-text-muted">Wind</p>
      <div
        className="text-xl leading-none"
        style={{ transform: `rotate(${windDirection}deg)` }}
        aria-hidden="true"
      >
        ↑
      </div>
      <p className="text-sm font-medium text-plex-text">
        {mph} mph {compass}
      </p>
    </div>
  );
}

WindIndicator.propTypes = {
  windSpeed: PropTypes.oneOfType([PropTypes.number, PropTypes.string]).isRequired,
  windDirection: PropTypes.number.isRequired,
};
