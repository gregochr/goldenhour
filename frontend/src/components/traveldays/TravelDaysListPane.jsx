import PropTypes from 'prop-types';
import DataGrid from '../data/DataGrid.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import TravelDaysCalendar from './TravelDaysCalendar.jsx';
import {
  groupRanges, isPastRange, lengthText, rangeText, whenText,
} from '../../utils/travelDays.js';

/** The grid's columns (plan §5.6): Range (the note under it), Length, and When as the secondary column. */
function columns(now) {
  return [
    {
      key: 'range',
      header: 'Range',
      width: '2fr',
      card: 'title',
      cell: (range) => (
        <>
          <span className="block font-medium" data-testid={`travel-range-${range.id}`}>{rangeText(range)}</span>
          {range.note && (
            <span className="block truncate text-xs text-plex-text-secondary" title={range.note}>{range.note}</span>
          )}
        </>
      ),
    },
    {
      key: 'length',
      header: 'Length',
      width: '.7fr',
      card: 'status',
      cell: (range) => (
        <span className="font-mono text-[12.5px]" data-testid={`travel-length-${range.id}`}>
          {lengthText(range.startDate, range.endDate)}
        </span>
      ),
    },
    {
      key: 'when',
      header: 'When',
      width: '.8fr',
      secondary: true,
      cell: (range) => (
        <span className="text-xs text-plex-text-secondary" data-testid={`travel-when-${range.id}`}>
          {whenText(range, now)}
        </span>
      ),
    },
    {
      key: 'card-when',
      card: 'summary',
      cardOnly: true,
      cell: (range) => (
        <span className="block truncate text-xs text-plex-text-secondary">{whenText(range, now)}</span>
      ),
    },
  ];
}

/** A row's accessible name, reading the whole row: "Mon 2 Nov → Wed 4 Nov, 3 days, In 2 days, London — work". */
function rowLabel(range, now) {
  return [rangeText(range), lengthText(range.startDate, range.endDate), whenText(range, now), range.note]
    .filter(Boolean).join(', ');
}

/**
 * The Travel days list pane (runs-redesign-plan.md §5.6): the header ("Travel days", "{n} upcoming", Add range), the
 * intro, the five-week calendar and the ranges as a grid with a group row "Upcoming · {n}" (open) and "Past · {n}"
 * (collapsed until pressed). Rows hold no controls; a row opens its range panel.
 *
 * @param {object} props
 * @param {Array<object>} props.ranges every served range, past included
 * @param {boolean} props.loading true until the first answer
 * @param {string|null} props.error the load error, if any
 * @param {Date} props.now the real clock
 * @param {number|null} props.selectedId the open range's id
 * @param {{upcoming: boolean, past: boolean}} props.groupOpen which groups are expanded (Past starts collapsed)
 * @param {(id: 'upcoming'|'past') => void} props.onToggleGroup expands or collapses a group
 * @param {(range: object) => void} props.onSelect called when a row (or an away day) is activated
 * @param {() => void} props.onAdd opens the Add panel
 * @param {(iso: string) => void} props.onAddFrom opens the Add panel with From set to a calendar day
 */
export default function TravelDaysListPane({
  ranges, loading, error = null, now, selectedId = null, groupOpen, onToggleGroup, onSelect, onAdd, onAddFrom,
}) {
  const { upcoming, past } = groupRanges(ranges, now);
  const rows = [];
  if (upcoming.length > 0) {
    rows.push({
      groupHeader: true,
      id: 'upcoming',
      label: `Upcoming \u00b7 ${upcoming.length}`,
      expanded: groupOpen.upcoming,
      onToggle: () => onToggleGroup('upcoming'),
    });
    if (groupOpen.upcoming) rows.push(...upcoming);
  }
  if (past.length > 0) {
    rows.push({
      groupHeader: true,
      id: 'past',
      label: `Past \u00b7 ${past.length}`,
      expanded: groupOpen.past,
      onToggle: () => onToggleGroup('past'),
    });
    if (groupOpen.past) rows.push(...past);
  }

  let body;
  if (loading) {
    body = (
      <p className="animate-pulse text-sm text-plex-text-muted motion-reduce:animate-none" data-testid="travel-loading">
        Loading travel days{'…'}
      </p>
    );
  } else if (ranges.length === 0 && error) {
    // A failed first load says so in the banner; "No travel days set" would be a claim the server never made.
    body = null;
  } else if (ranges.length === 0) {
    body = (
      <p className="py-6 text-center text-sm text-plex-text-secondary" data-testid="travel-day-empty">
        No travel days set. Every date is forecast.
      </p>
    );
  } else {
    body = (
      <DataGrid
        columns={columns(now)}
        rows={rows}
        getRowId={(range) => range.id}
        getRowLabel={(range) => rowLabel(range, now)}
        selectedId={selectedId}
        onSelect={onSelect}
        isOff={(range) => isPastRange(range, now)}
        testIdPrefix="travel-days"
      />
    );
  }

  return (
    <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="travel-list-pane">
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2">
        <div className="flex min-w-0 flex-1 flex-wrap items-baseline gap-x-3 gap-y-1">
          <h2
            className="text-sm font-semibold text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
            data-testid="travel-heading"
          >
            Travel days
          </h2>
          {!loading && (
            <span className="font-mono text-[11px] text-plex-text-muted" data-testid="travel-meta">
              {upcoming.length} upcoming
            </span>
          )}
        </div>
        <button
          type="button"
          className="btn-secondary max-[639px]:min-h-11"
          onClick={onAdd}
          data-testid="travel-day-add-open"
        >
          Add range
        </button>
      </div>

      <p className="text-xs text-plex-text-secondary" data-testid="travel-intro">
        The overnight batch skips forecasts for days you&apos;re away. Start and end dates are both included.
      </p>

      <ErrorBanner message={error} data-testid="travel-error" />

      <TravelDaysCalendar ranges={ranges} now={now} onOpenRange={onSelect} onAddFrom={onAddFrom} />

      {body}
    </div>
  );
}

TravelDaysListPane.propTypes = {
  ranges: PropTypes.arrayOf(PropTypes.object).isRequired,
  loading: PropTypes.bool.isRequired,
  error: PropTypes.string,
  now: PropTypes.instanceOf(Date).isRequired,
  selectedId: PropTypes.number,
  groupOpen: PropTypes.shape({ upcoming: PropTypes.bool, past: PropTypes.bool }).isRequired,
  onToggleGroup: PropTypes.func.isRequired,
  onSelect: PropTypes.func.isRequired,
  onAdd: PropTypes.func.isRequired,
  onAddFrom: PropTypes.func.isRequired,
};
