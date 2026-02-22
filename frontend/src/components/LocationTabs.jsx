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
    <div className="flex gap-1 border-b border-gray-800 mb-6">
      {locations.map((loc, i) => (
        <button
          key={loc.name}
          onClick={() => onSelect(i)}
          className={`px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors ${
            i === selectedIndex
              ? 'border-gray-100 text-gray-100'
              : 'border-transparent text-gray-500 hover:text-gray-300 hover:border-gray-700'
          }`}
        >
          {loc.name}
        </button>
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
