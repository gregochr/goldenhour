import React from 'react';
import PropTypes from 'prop-types';
import { formatEventTimeUk } from '../utils/conversions.js';

const STATE_LABEL = { HIGH: 'High', MID: 'Mid', LOW: 'Low' };

/**
 * Displays the tide state, alignment, and next high/low tide events for a coastal forecast.
 * Returns null for non-coastal forecasts (tideState absent).
 *
 * @param {object} props
 * @param {object} props.forecast - Forecast evaluation data.
 */
export default function TideIndicator({ forecast }) {
  if (!forecast?.tideState) return null;

  const {
    tideState,
    tideAligned,
    nextHighTideTime,
    nextHighTideHeightMetres,
    nextLowTideTime,
    nextLowTideHeightMetres,
  } = forecast;

  return (
    <div
      data-testid="tide-indicator"
      className="flex items-center flex-wrap gap-x-3 gap-y-1 pt-2 border-t border-gray-800"
    >
      <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full
        bg-cyan-950 text-cyan-300 ring-1 ring-inset ring-cyan-800/50">
        🌊 {STATE_LABEL[tideState] ?? tideState}
      </span>

      {tideAligned && (
        <span className="text-xs text-green-400 font-medium">✓ Aligned</span>
      )}

      {nextHighTideTime && (
        <span className="text-xs text-gray-400">
          ↑ High {formatEventTimeUk(nextHighTideTime)}
          {nextHighTideHeightMetres != null && (
            <span className="text-gray-500"> {parseFloat(nextHighTideHeightMetres).toFixed(1)}m</span>
          )}
        </span>
      )}

      {nextLowTideTime && (
        <span className="text-xs text-gray-400">
          ↓ Low {formatEventTimeUk(nextLowTideTime)}
          {nextLowTideHeightMetres != null && (
            <span className="text-gray-500"> {parseFloat(nextLowTideHeightMetres).toFixed(1)}m</span>
          )}
        </span>
      )}
    </div>
  );
}

TideIndicator.propTypes = {
  forecast: PropTypes.object.isRequired,
};
