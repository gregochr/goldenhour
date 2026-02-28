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
  const now = new Date();
  const today = now.toISOString().slice(0, 10);
  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).toISOString().slice(0, 10);

  function chipLabel(date) {
    if (date === today) return `Today · ${formatDateLabel(date, now, true)}`;
    if (date === tomorrow) return `Tomorrow · ${formatDateLabel(date, now, true)}`;
    return formatDateLabel(date);
  }

  return (
    <div className="relative mb-6">
      <div data-testid="date-strip" className="flex gap-2 overflow-x-auto pb-2 scrollbar-none">
        {dates.map((date) => (
          <button
            key={date}
            onClick={() => onSelect(date)}
            className={`shrink-0 px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
              date === selectedDate
                ? 'bg-plex-gold text-gray-900'
                : 'bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text'
            }`}
          >
            {chipLabel(date)}
          </button>
        ))}
      </div>
      <div className="pointer-events-none absolute inset-y-0 right-0 w-10 bg-gradient-to-l from-plex-bg to-transparent" />
    </div>
  );
}

DateStrip.propTypes = {
  dates: PropTypes.arrayOf(PropTypes.string).isRequired,
  selectedDate: PropTypes.string.isRequired,
  onSelect: PropTypes.func.isRequired,
};
