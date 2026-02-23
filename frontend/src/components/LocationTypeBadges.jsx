import React from 'react';
import PropTypes from 'prop-types';

const LOCATION_TYPE_META = {
  LANDSCAPE: { emoji: '🏔️', label: 'Landscape' },
  WILDLIFE:  { emoji: '🦅', label: 'Wildlife' },
  SEASCAPE:  { emoji: '🌊', label: 'Seascape' },
};

const TIDE_TYPE_META = {
  HIGH_TIDE: { symbol: '↑', label: 'High tide' },
  LOW_TIDE:  { symbol: '↓', label: 'Low tide' },
  MID_TIDE:  { symbol: '~', label: 'Mid tide' },
  ANY_TIDE:  { symbol: '≈', label: 'Any tide' },
};

/**
 * Compact inline badges showing a location's photography type tags and tide preferences.
 *
 * @param {object}   props
 * @param {string[]} props.locationType - Array of LocationType values (LANDSCAPE, WILDLIFE, SEASCAPE).
 * @param {string[]} props.tideType     - Array of TideType values (HIGH_TIDE, LOW_TIDE, etc.).
 */
export default function LocationTypeBadges({ locationType = [], tideType = [] }) {
  const hasTypes = locationType.length > 0;
  const coastalTides = tideType.filter((t) => t !== 'NOT_COASTAL');
  const hasTides = coastalTides.length > 0;

  if (!hasTypes && !hasTides) return null;

  return (
    <div className="flex flex-wrap gap-1">
      {locationType.map((type) => {
        const meta = LOCATION_TYPE_META[type];
        if (!meta) return null;
        return (
          <span
            key={type}
            title={meta.label}
            className="inline-flex items-center gap-0.5 text-xs px-1.5 py-0.5 rounded
              bg-gray-800 text-gray-400 border border-gray-700"
          >
            {meta.emoji}
          </span>
        );
      })}
      {coastalTides.map((type) => {
        const meta = TIDE_TYPE_META[type];
        if (!meta) return null;
        return (
          <span
            key={type}
            title={meta.label}
            className="inline-flex items-center gap-0.5 text-xs px-1.5 py-0.5 rounded
              bg-gray-800 text-cyan-600 border border-gray-700 font-mono"
          >
            🌊{meta.symbol}
          </span>
        );
      })}
    </div>
  );
}

LocationTypeBadges.propTypes = {
  locationType: PropTypes.arrayOf(PropTypes.string),
  tideType: PropTypes.arrayOf(PropTypes.string),
};
