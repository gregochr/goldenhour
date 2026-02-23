import React from 'react';
import PropTypes from 'prop-types';
import { degreesToCompassFine } from '../utils/conversions.js';

/**
 * Displays the compass direction of a solar event (sunrise or sunset azimuth).
 *
 * Shows a rotated arrow pointing in the direction the sun rises or sets,
 * alongside a 16-point compass label and the numeric bearing.
 *
 * @param {object} props
 * @param {number} props.azimuthDeg - Azimuth in degrees clockwise from North (0–359).
 * @param {boolean} props.isSunrise - True for sunrise, false for sunset.
 */
export default function AzimuthIndicator({ azimuthDeg, isSunrise }) {
  const compass = degreesToCompassFine(azimuthDeg);
  const verb = isSunrise ? 'Rises' : 'Sets';

  return (
    <div data-testid="azimuth-indicator" className="flex items-center gap-3">
      <div
        className="text-xl leading-none text-gray-300"
        style={{ transform: `rotate(${azimuthDeg}deg)` }}
        aria-hidden="true"
        title={`${verb} ${compass} (${azimuthDeg}°)`}
      >
        ↑
      </div>
      <p className="text-sm text-gray-300">
        {verb} <span className="font-medium">{compass}</span>{' '}
        <span className="text-gray-500">({azimuthDeg}°)</span>
      </p>
    </div>
  );
}

AzimuthIndicator.propTypes = {
  azimuthDeg: PropTypes.number.isRequired,
  isSunrise: PropTypes.bool.isRequired,
};
