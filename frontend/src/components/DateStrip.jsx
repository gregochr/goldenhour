import React, { useRef, useEffect } from 'react';
import PropTypes from 'prop-types';
import { formatDateLabel } from '../utils/conversions.js';

/**
 * Horizontal scrollable strip of date chips for selecting a target date.
 * Shows left/right arrows when the strip overflows. Past dates are
 * visually dimmed; a subtle divider separates past from today onwards.
 *
 * @param {object} props
 * @param {Array<string>} props.dates - Sorted array of date strings (YYYY-MM-DD).
 * @param {string} props.selectedDate - The currently selected date.
 * @param {function} props.onSelect - Called with a date string when a chip is clicked.
 */
export default function DateStrip({ dates, selectedDate, onSelect }) {
  const scrollRef = useRef(null);
  const now = new Date();
  const today = now.toISOString().slice(0, 10);
  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).toISOString().slice(0, 10);

  // Scroll so today (or the selected date) is visible on mount
  useEffect(() => {
    if (!scrollRef.current) return;
    const target = scrollRef.current.querySelector('[data-today="true"]')
      || scrollRef.current.querySelector('[data-selected="true"]');
    if (target) {
      target.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'instant' });
    }
  }, [dates.length]);

  function chipLabel(date) {
    if (date === today) return `Today · ${formatDateLabel(date, now, true)}`;
    if (date === tomorrow) return `Tomorrow · ${formatDateLabel(date, now, true)}`;
    return formatDateLabel(date);
  }

  function scroll(direction) {
    if (!scrollRef.current) return;
    const amount = scrollRef.current.clientWidth * 0.6;
    scrollRef.current.scrollBy({ left: direction * amount, behavior: 'smooth' });
  }

  const isPast = (date) => date < today;
  const isFirst = dates.length > 0 && selectedDate === dates[0];
  const isLast = dates.length > 0 && selectedDate === dates[dates.length - 1];

  // Find the boundary between past and today/future for the divider
  const firstTodayOrFutureIdx = dates.findIndex((d) => d >= today);
  const hasPastDates = firstTodayOrFutureIdx > 0;

  return (
    <div className="relative mb-6 flex items-center gap-1">
      {/* Left arrow */}
      <button
        onClick={() => scroll(-1)}
        disabled={isFirst}
        className={`shrink-0 w-7 h-7 flex items-center justify-center rounded-full transition-colors ${
          isFirst
            ? 'bg-plex-surface-light/50 text-plex-text-muted/30 cursor-not-allowed'
            : 'bg-plex-surface-light text-plex-text-muted hover:text-plex-text hover:bg-plex-border'
        }`}
        aria-label="Scroll dates left"
      >
        &#x2039;
      </button>

      <div className="relative flex-1 overflow-hidden">
        <div
          ref={scrollRef}
          data-testid="date-strip"
          className="flex gap-2 overflow-x-auto pb-2 scrollbar-none"
        >
          {dates.map((date, idx) => (
            <React.Fragment key={date}>
              {hasPastDates && idx === firstTodayOrFutureIdx && (
                <span className="shrink-0 w-px bg-plex-border self-stretch" />
              )}
              <button
                onClick={() => onSelect(date)}
                data-today={date === today ? 'true' : undefined}
                data-selected={date === selectedDate ? 'true' : undefined}
                className={`shrink-0 px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
                  date === selectedDate
                    ? 'bg-plex-gold text-gray-900'
                    : isPast(date)
                      ? 'bg-plex-surface-light/50 text-plex-text-muted hover:bg-plex-border hover:text-plex-text-secondary'
                      : 'bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text'
                }`}
              >
                {chipLabel(date)}
              </button>
            </React.Fragment>
          ))}
        </div>
        <div className="pointer-events-none absolute inset-y-0 right-0 w-10 bg-gradient-to-l from-plex-bg to-transparent" />
      </div>

      {/* Right arrow */}
      <button
        onClick={() => scroll(1)}
        disabled={isLast}
        className={`shrink-0 w-7 h-7 flex items-center justify-center rounded-full transition-colors ${
          isLast
            ? 'bg-plex-surface-light/50 text-plex-text-muted/30 cursor-not-allowed'
            : 'bg-plex-surface-light text-plex-text-muted hover:text-plex-text hover:bg-plex-border'
        }`}
        aria-label="Scroll dates right"
      >
        &#x203A;
      </button>
    </div>
  );
}

DateStrip.propTypes = {
  dates: PropTypes.arrayOf(PropTypes.string).isRequired,
  selectedDate: PropTypes.string.isRequired,
  onSelect: PropTypes.func.isRequired,
};
