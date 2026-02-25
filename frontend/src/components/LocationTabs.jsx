import React from 'react';
import PropTypes from 'prop-types';

/**
 * Tab strip for switching between configured forecast locations.
 *
 * @param {object} props
 * @param {Array<{name: string}>} props.locations - Available locations.
 * @param {number} props.selectedIndex - Index of the currently active tab.
 * @param {function} props.onSelect - Called with the index when a tab is clicked.
 */
export default function LocationTabs({ locations, selectedIndex, onSelect }) {
  return (
    <div className="flex flex-wrap gap-1 border-b border-gray-800 mb-6">
      {locations.map((loc, i) => (
        <div key={loc.name} className="flex items-center gap-1">
          <button
            onClick={() => onSelect(i)}
            className={`px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors ${
              i === selectedIndex
                ? 'border-gray-100 text-gray-100'
                : 'border-transparent text-gray-500 hover:text-gray-300 hover:border-gray-700'
            }`}
          >
            {loc.name}
          </button>
          {loc.disabledReason && (
            <div
              title={loc.disabledReason}
              className="px-2 py-1 text-xs font-medium bg-red-900/60 text-red-300 rounded border border-red-700/50"
            >
              ⚠️ Disabled
            </div>
          )}
          {!loc.disabledReason && loc.consecutiveFailures > 0 && (
            <div
              title={`${loc.consecutiveFailures} consecutive failure${loc.consecutiveFailures !== 1 ? 's' : ''}`}
              className="px-2 py-1 text-xs font-medium bg-amber-900/60 text-amber-300 rounded border border-amber-700/50"
            >
              🔄 {loc.consecutiveFailures}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

LocationTabs.propTypes = {
  locations: PropTypes.arrayOf(PropTypes.shape({ name: PropTypes.string.isRequired }))
    .isRequired,
  selectedIndex: PropTypes.number.isRequired,
  onSelect: PropTypes.func.isRequired,
};
