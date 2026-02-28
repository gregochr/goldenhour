import React from 'react';
import PropTypes from 'prop-types';
import { metresToKm } from '../utils/conversions.js';

const VISIBILITY_THRESHOLDS = [
  { min: 20000, label: 'Excellent', color: 'text-green-400' },
  { min: 10000, label: 'Good', color: 'text-lime-400' },
  { min: 4000, label: 'Moderate', color: 'text-yellow-400' },
  { min: 1000, label: 'Poor', color: 'text-orange-400' },
  { min: 0, label: 'Very poor', color: 'text-red-400' },
];

/**
 * Returns the quality label and colour class for a given visibility value.
 *
 * @param {number} metres - Visibility in metres.
 * @returns {{ label: string, color: string }}
 */
function visibilityQuality(metres) {
  return VISIBILITY_THRESHOLDS.find((t) => metres >= t.min) ?? VISIBILITY_THRESHOLDS.at(-1);
}

/**
 * Displays visibility in kilometres with a qualitative label.
 *
 * @param {object} props
 * @param {number} props.visibility - Visibility in metres.
 */
export default function VisibilityIndicator({ visibility }) {
  const unavailable = !visibility || visibility === 0;
  const km = metresToKm(visibility);
  const { label, color } = visibilityQuality(visibility);

  return (
    <div data-testid="visibility-indicator" className="flex flex-col items-center gap-1">
      <p className="text-xs text-plex-text-muted">Visibility</p>
      {unavailable ? (
        <p className="text-sm font-medium text-plex-text-muted">N/A</p>
      ) : (
        <>
          <p className={`text-sm font-medium ${color}`}>{km} km</p>
          <p className="text-xs text-plex-text-secondary">{label}</p>
        </>
      )}
    </div>
  );
}

VisibilityIndicator.propTypes = {
  visibility: PropTypes.number.isRequired,
};
