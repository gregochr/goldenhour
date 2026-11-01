import PropTypes from 'prop-types';
import { calendarCells, cellLabel, rangeForDay } from '../../utils/travelDays.js';

/** The weekday heads, Monday first. */
const HEADS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/** A cell's base look: 36px tall (44px on a phone), radius 5, mono 12px. */
const CELL = 'flex min-h-9 items-center justify-center rounded-[5px] border font-mono text-[12px] max-[639px]:min-h-11';

/** One day of the key: a swatch and its words. */
function KeyItem({ swatch, children }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`size-3 rounded-[3px] ${swatch}`} aria-hidden="true" />
      {children}
    </span>
  );
}

KeyItem.propTypes = { swatch: PropTypes.string.isRequired, children: PropTypes.node.isRequired };

/**
 * The five-week calendar (runs-redesign-plan.md §5.6): this week's Monday (UK, the real clock) and four weeks on,
 * Monday first. Away days are tide at 55%, past days sit at 45% opacity and today has an inset ring; the 1st of a
 * month reads "1 Nov". Every cell is a button with an accessible name ("Mon 2 Nov, away (London — work)"): an away
 * day opens its range, an empty day from today on opens Add with From set to it, and a past empty day is inert
 * (disabled).
 *
 * @param {object} props
 * @param {Array<object>} props.ranges every served range, past included
 * @param {Date} props.now the real clock
 * @param {(range: object) => void} props.onOpenRange called when an away day is pressed
 * @param {(iso: string) => void} props.onAddFrom called when an empty day from today on is pressed
 */
export default function TravelDaysCalendar({ ranges, now, onOpenRange, onAddFrom }) {
  const cells = calendarCells(now);
  return (
    <div data-testid="travel-calendar">
      <div className="mb-1 grid grid-cols-7 gap-1" aria-hidden="true">
        {HEADS.map((head) => (
          <span key={head} className="text-center font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted">
            {head}
          </span>
        ))}
      </div>
      <div className="grid grid-cols-7 gap-1" role="group" aria-label="Next five weeks">
        {cells.map((cell) => {
          const range = rangeForDay(ranges, cell.iso);
          const inert = range == null && cell.isPast;
          let look = 'border-plex-border bg-plex-surface-light text-plex-text-secondary';
          if (range) look = 'border-tide/60 bg-tide/55 text-plex-bg font-medium';
          const ring = cell.isToday ? 'shadow-[inset_0_0_0_2px_var(--color-plex-text)]' : '';
          const dim = cell.isPast ? 'opacity-45' : '';
          const interaction = inert
            ? 'cursor-default'
            : 'cursor-pointer hover:border-plex-border-light focus-visible:outline-2 focus-visible:outline-plex-gold';
          return (
            <button
              key={cell.iso}
              type="button"
              disabled={inert}
              aria-label={cellLabel(cell, range)}
              aria-current={cell.isToday ? 'date' : undefined}
              onClick={() => (range ? onOpenRange(range) : onAddFrom(cell.iso))}
              className={`${CELL} ${look} ${ring} ${dim} ${interaction}`}
              data-testid={`travel-cell-${cell.iso}`}
              data-away={range ? 'true' : 'false'}
            >
              {cell.label}
            </button>
          );
        })}
      </div>
      <p className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11.5px] text-plex-text-muted" data-testid="travel-calendar-key">
        <KeyItem swatch="bg-tide/55">Away</KeyItem>
        <KeyItem swatch="border border-plex-border bg-plex-surface-light opacity-45">Past</KeyItem>
        <KeyItem swatch="border border-plex-border bg-plex-surface-light shadow-[inset_0_0_0_2px_var(--color-plex-text)]">
          Today
        </KeyItem>
      </p>
    </div>
  );
}

TravelDaysCalendar.propTypes = {
  ranges: PropTypes.arrayOf(PropTypes.object).isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
  onOpenRange: PropTypes.func.isRequired,
  onAddFrom: PropTypes.func.isRequired,
};
