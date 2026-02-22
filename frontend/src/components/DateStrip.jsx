import React from 'react';
import PropTypes from 'prop-types';
import { formatDateLabel } from '../utils/conversions.js';

/**
 * Horizontal scrollable strip of date chips for selecting a target date.
 *
 * @param {object} props
 * @param {Array<string>} props.dates - Sorted array of date strings (YYYY-MM-DD).
 * @param {string} props.selectedDate - The currently selected date.
 * @param {function} props.onSelect - Called with a date string when a chip is clicked.
 */
export default function DateStrip({ dates, selectedDate, onSelect }) {
  return (
    <div className="flex gap-2 overflow-x-auto pb-2 mb-6 scrollbar-none">
      {dates.map((date) => (
        <button
          key={date}
          onClick={() => onSelect(date)}
          className={`shrink-0 px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
            date === selectedDate
              ? 'bg-gray-100 text-gray-900'
              : 'bg-gray-800 text-gray-400 hover:bg-gray-700 hover:text-gray-200'
          }`}
        >
          {formatDateLabel(date)}
        </button>
      ))}
    </div>
  );
}

DateStrip.propTypes = {
  dates: PropTypes.arrayOf(PropTypes.string).isRequired,
  selectedDate: PropTypes.string.isRequired,
  onSelect: PropTypes.func.isRequired,
};
