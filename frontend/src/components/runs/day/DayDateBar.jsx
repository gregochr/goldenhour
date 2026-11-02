import { useCallback, useId, useRef } from 'react';
import PropTypes from 'prop-types';
import DayCalendar from './DayCalendar.jsx';
import { dayButtonParts, daysAgo, nextDay, previousDay } from '../../../utils/runsDay.js';

/** A 34px square step button (44px on a phone). */
const STEP_BUTTON = 'flex size-[34px] items-center justify-center rounded-lg border border-plex-border bg-plex-surface-light '
  + 'text-[15px] text-plex-text transition-colors enabled:cursor-pointer enabled:hover:bg-white/8 disabled:opacity-40 '
  + 'focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:size-11';

/**
 * The Day view's date bar: {@code [‹] [Today  Mon 2 Nov ▾] [›] [Today]  (n days ago)} and a keyboard hint. The
 * previous and next buttons step by calendar date (never plus 24 hours: a UK day is 23, 24 or 25), the next is
 * disabled on today and the previous on the earliest day. The date button opens the calendar disclosure
 * ({@code aria-expanded}, {@code aria-controls}); Today is disabled on today.
 *
 * @param {object} props
 * @param {string} props.date the shown day
 * @param {string} props.today the UK today
 * @param {string|null} [props.earliestDay] the earliest selectable day
 * @param {boolean} props.calendarOpen whether the calendar disclosure is open
 * @param {(open: boolean) => void} props.onCalendarOpenChange opens or closes it
 * @param {(iso: string) => void} props.onChange called with the day the reader moved to
 */
export default function DayDateBar({
  date, today, earliestDay = null, calendarOpen, onCalendarOpenChange, onChange,
}) {
  const calendarId = useId();
  const buttonRef = useRef(null);
  const { word, date: dateText } = dayButtonParts(date, today);
  const ago = daysAgo(date, today);
  const atEarliest = earliestDay != null && date <= earliestDay;
  const atToday = date >= today;

  const closeCalendar = useCallback((restoreFocus) => {
    onCalendarOpenChange(false);
    if (restoreFocus) buttonRef.current?.focus({ preventScroll: true });
  }, [onCalendarOpenChange]);

  const pick = useCallback((iso) => {
    onChange(iso);
    closeCalendar(true);
  }, [onChange, closeCalendar]);

  return (
    <div className="relative flex flex-wrap items-center gap-2" data-testid="runs-date-bar">
      <button
        type="button"
        className={STEP_BUTTON}
        aria-label="Previous day"
        disabled={atEarliest}
        onClick={() => onChange(previousDay(date))}
        data-testid="runs-day-prev"
      >
        {'‹'}
      </button>
      <button
        ref={buttonRef}
        type="button"
        className="flex h-[34px] min-w-[190px] cursor-pointer items-center justify-between gap-2 rounded-lg border border-plex-border bg-plex-surface-light px-3 text-left transition-colors hover:bg-white/8 focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11 max-[639px]:min-w-0 max-[639px]:flex-1"
        aria-expanded={calendarOpen}
        aria-controls={calendarId}
        onClick={() => onCalendarOpenChange(!calendarOpen)}
        data-testid="runs-day-date"
      >
        <span className="flex items-baseline gap-2">
          <span className="text-[13px] font-semibold text-plex-text" data-testid="runs-day-word">{word}</span>
          <span className="font-mono text-[12px] text-plex-text-secondary" data-testid="runs-day-date-text">{dateText}</span>
        </span>
        <span className="text-[9px] text-plex-text-muted" aria-hidden="true">&#9662;</span>
      </button>
      <button
        type="button"
        className={STEP_BUTTON}
        aria-label="Next day"
        disabled={atToday}
        onClick={() => onChange(nextDay(date))}
        data-testid="runs-day-next"
      >
        {'›'}
      </button>
      <button
        type="button"
        className="h-[34px] cursor-pointer rounded-lg border border-plex-border px-3 text-[12px] text-plex-text transition-colors enabled:hover:bg-white/8 disabled:cursor-default disabled:text-plex-text-muted focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
        disabled={atToday}
        onClick={() => onChange(today)}
        data-testid="runs-day-today"
      >
        Today
      </button>
      {ago > 0 && (
        <span className="text-[12px] text-plex-text-secondary" data-testid="runs-day-ago">
          {ago} {ago === 1 ? 'day' : 'days'} ago
        </span>
      )}
      <span
        className="ml-auto font-mono text-[11px] text-plex-text-muted max-[639px]:hidden"
        data-testid="runs-day-hint"
      >
        {'←'} {'→'} to step {'·'} T for today
      </span>
      {calendarOpen && (
        <DayCalendar
          id={calendarId}
          date={date}
          today={today}
          earliestDay={earliestDay}
          onPick={pick}
          onClose={closeCalendar}
          anchorRef={buttonRef}
        />
      )}
    </div>
  );
}

DayDateBar.propTypes = {
  date: PropTypes.string.isRequired,
  today: PropTypes.string.isRequired,
  earliestDay: PropTypes.string,
  calendarOpen: PropTypes.bool.isRequired,
  onCalendarOpenChange: PropTypes.func.isRequired,
  onChange: PropTypes.func.isRequired,
};
