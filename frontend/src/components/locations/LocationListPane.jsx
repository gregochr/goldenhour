import { useMemo } from 'react';
import PropTypes from 'prop-types';
import Pagination from '../Pagination.jsx';
import DataGrid from '../data/DataGrid.jsx';
import StatusDot from '../data/StatusDot.jsx';
import {
  Chip, ChipDivider, ChipGroup, ChipRow, ChipSelect, SortChip,
} from '../data/Chips.jsx';
import { DataSearch, DataTools } from '../data/DataTools.jsx';
import { textButtonClass } from '../users/userFormClasses.js';
import usePagination from '../../hooks/usePagination.js';
import useSortAndFilter from '../../hooks/useSortAndFilter.js';
import { DISPLAY_TYPES, locationTypeLabel } from '../../utils/locationTypes.js';
import {
  ALL_REGIONS, LIGHT_TOKENS, LOCATION_SORT_ACCESSORS, NO_VALUE, STATUS_FILTERS, TIDE_TOKENS, attentionSummary,
  isSeascape, locationRowLabel, locationStatus, locationSummary, typeWords,
} from '../../utils/locationAdmin.js';

const SORT_OPTIONS = [
  { key: 'name', label: 'Location' },
  { key: 'type', label: 'Type' },
  { key: 'bortle', label: 'Bortle' },
  { key: 'status', label: 'Status' },
];

const DEFAULT_LIGHT = ['SUNRISE', 'SUNSET'];

/** A row's id; module-level so the grid's props stay stable. */
const locationId = (loc) => loc.id;

/** Mono tokens, the held ones in ink and the others in the faintest ink ("Rise Set Day", "H M L"). */
function Tokens({ tokens, held, testIdPrefix }) {
  return (
    <span className="inline-flex gap-1.5 font-mono text-[11px]">
      {tokens.map((t) => (
        <span
          key={t.value}
          className={held.includes(t.value) ? 'text-plex-text' : 'text-plex-text-faint'}
          data-testid={`${testIdPrefix}-${t.value}`}
        >
          {t.label}
        </span>
      ))}
    </span>
  );
}

Tokens.propTypes = {
  tokens: PropTypes.arrayOf(PropTypes.object).isRequired,
  held: PropTypes.arrayOf(PropTypes.string).isRequired,
  testIdPrefix: PropTypes.string.isRequired,
};

/** The row's status as a dot and its words. */
function StatusCell({ loc }) {
  const status = locationStatus(loc);
  return <StatusDot tone={status.tone} testId={`location-status-${loc.id}`}>{status.label}</StatusDot>;
}

StatusCell.propTypes = { loc: PropTypes.object.isRequired };

/**
 * The grid's columns (plan §6.1): Location 1.7fr (the name, a Mist tag when the place is mist-prone, the sub-region
 * under it), Type .8fr, Light .95fr (secondary), Tides .6fr, Bortle .45fr (secondary, mono) and Status 1.05fr. In
 * card mode line 1 is the name and the status, line 2 "{Type} · Tides H M L · Bortle {n}". No cell holds a control.
 */
const COLUMNS = [
  {
    key: 'location',
    header: 'Location',
    width: '1.7fr',
    sortKey: 'name',
    card: 'title',
    cell: (loc) => (
      <>
        <span className="flex min-w-0 items-center gap-1.5">
          <span className="truncate font-medium" title={loc.name} data-testid={`location-name-${loc.id}`}>{loc.name}</span>
          {loc.mistProne === true && (
            <span
              className="shrink-0 rounded-sm bg-plex-surface-light px-1.5 py-px font-mono text-[10px] text-plex-text-secondary"
              title="Mist forms here: valley floor, riverside, lake shore"
              data-testid={`mist-prone-badge-${loc.id}`}
            >
              Mist
            </span>
          )}
        </span>
        {loc.subRegionName && (
          <span className="block truncate text-xs text-plex-text-secondary @max-[540px]:hidden">{loc.subRegionName}</span>
        )}
      </>
    ),
  },
  {
    key: 'type',
    header: 'Type',
    width: '.8fr',
    sortKey: 'type',
    cell: (loc) => <span className="block truncate" title={typeWords(loc.locationType)}>{typeWords(loc.locationType)}</span>,
  },
  {
    key: 'light',
    header: 'Light',
    width: '.95fr',
    secondary: true,
    cell: (loc) => (
      <Tokens
        tokens={LIGHT_TOKENS}
        held={Array.isArray(loc.solarEventType) && loc.solarEventType.length > 0 ? loc.solarEventType : DEFAULT_LIGHT}
        testIdPrefix={`location-light-${loc.id}`}
      />
    ),
  },
  {
    key: 'tides',
    header: 'Tides',
    width: '.6fr',
    cell: (loc) => (isSeascape(loc)
      ? <Tokens tokens={TIDE_TOKENS} held={loc.tideType ?? []} testIdPrefix={`location-tides-${loc.id}`} />
      : <span className="text-plex-text-muted" data-testid={`location-tides-none-${loc.id}`}>{NO_VALUE}</span>),
  },
  {
    key: 'bortle',
    header: 'Bortle',
    width: '.45fr',
    sortKey: 'bortle',
    secondary: true,
    cell: (loc) => (
      <span className="font-mono text-[12.5px] text-plex-text-secondary" data-testid={`bortle-${loc.id}`}>
        {loc.bortleClass ?? NO_VALUE}
      </span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    width: '1.05fr',
    sortKey: 'status',
    card: 'status',
    cell: (loc) => <StatusCell loc={loc} />,
  },
  {
    key: 'card-summary',
    card: 'summary',
    cardOnly: true,
    cell: (loc) => <span className="block truncate text-xs text-plex-text-secondary">{locationSummary(loc)}</span>,
  },
];

/**
 * The attention alert: one amber line naming the count and, at most, one failing and one turned-off place, with Show
 * them (the Needs attention chip). Replaces the old Location Issues box and its per-place Re-enable buttons, which
 * moved into the panel.
 */
function AttentionAlert({ summary, onShow }) {
  return (
    <div
      className="flex items-start gap-2.5 rounded-lg border border-verdict-marginal/30 bg-verdict-marginal/10 px-3 py-2.5 text-[13px] text-plex-text-secondary"
      data-testid="locations-attention-alert"
    >
      <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-verdict-marginal" aria-hidden="true" />
      <p className="min-w-0 flex-1">
        <strong className="font-semibold text-plex-text" data-testid="locations-attention-count">{summary.headline}</strong>
        {summary.failing && (
          <>
            {' '}
            <span className="text-plex-text" data-testid="locations-attention-failing">{summary.failing.name}</span>
            {' '}{summary.failing.text}
          </>
        )}
        {summary.turnedOff && (
          <>
            {' '}
            <span className="text-plex-text" data-testid="locations-attention-turned-off">{summary.turnedOff.name}</span>
            {' '}{summary.turnedOff.text}
          </>
        )}
      </p>
      <button
        type="button"
        className={`${textButtonClass} shrink-0 py-0 text-xs text-plex-text underline`}
        onClick={onShow}
        data-testid="locations-show-attention"
      >
        Show them
      </button>
    </div>
  );
}

AttentionAlert.propTypes = {
  summary: PropTypes.object.isRequired,
  onShow: PropTypes.func.isRequired,
};

/**
 * The Locations list on the shared Data grid (data-tabs-redesign-plan.md §6.1): the header with Add location, the
 * attention alert, the search and chips (type, status, region), the grid and its pagination. It reads the filters and
 * the filtered set from {@code useLocationFilters}; it owns only the sort and the page.
 *
 * @param {object} props
 * @param {Array<object>} props.locations every location (the alert and the empty state read the whole set)
 * @param {Array<object>} props.regions every region, for the region select
 * @param {object} props.filters the {@code useLocationFilters} result
 * @param {number|null} props.selectedId the location whose panel is open
 * @param {(location: object) => void} props.onSelect called when a row is activated
 * @param {() => void} props.onAdd called by Add location
 */
export default function LocationListPane({ locations, regions, filters, selectedId, onSelect, onAdd }) {
  const sort = useSortAndFilter('name', 'asc', LOCATION_SORT_ACCESSORS);
  const sorted = useMemo(() => sort.apply(filters.filtered), [sort, filters.filtered]);
  const { pageItems, ...pagination } = usePagination(sorted);
  const summary = useMemo(() => attentionSummary(locations), [locations]);
  const regionOptions = useMemo(() => [
    { value: ALL_REGIONS, label: 'All regions' },
    ...[...regions].sort((a, b) => a.name.localeCompare(b.name)).map((r) => ({ value: String(r.id), label: r.name })),
  ], [regions]);

  let body;
  if (locations.length === 0) {
    body = <p className="text-sm text-plex-text-muted" data-testid="locations-empty">No locations configured. Add one to get started.</p>;
  } else if (sorted.length === 0) {
    body = (
      <div className="flex flex-col items-center gap-2 py-8 text-center" data-testid="locations-no-match">
        <p className="text-sm text-plex-text-secondary">No locations match.</p>
        {filters.anyFilter && (
          <button type="button" className={`${textButtonClass} underline`} onClick={filters.clear} data-testid="locations-clear-filters">
            Clear filters
          </button>
        )}
      </div>
    );
  } else {
    body = (
      <div>
        <DataGrid
          columns={COLUMNS}
          rows={pageItems}
          getRowId={locationId}
          getRowLabel={locationRowLabel}
          selectedId={selectedId}
          onSelect={onSelect}
          isOff={(loc) => !loc.enabled}
          sort={{ key: sort.sortKey, dir: sort.sortDir }}
          onSort={sort.handleSort}
          testIdPrefix="locations"
        />
        <Pagination
          page={pagination.page}
          totalPages={pagination.totalPages}
          pageSize={pagination.pageSize}
          totalItems={sorted.length}
          onNextPage={pagination.nextPage}
          onPrevPage={pagination.prevPage}
          onFirstPage={pagination.firstPage}
          onLastPage={pagination.lastPage}
          onSetPageSize={pagination.setPageSize}
        />
      </div>
    );
  }

  return (
    <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="locations-list-pane">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h2 className="flex items-baseline gap-2 text-sm font-semibold text-plex-text">
          Locations
          <span className="font-mono text-[11px] font-normal text-plex-text-muted" data-testid="locations-total">
            {locations.length}
          </span>
        </h2>
        <button
          type="button"
          className="btn-secondary shrink-0 text-xs max-[639px]:min-h-11"
          onClick={onAdd}
          data-testid="add-location-btn"
        >
          Add location
        </button>
      </div>

      {summary && <AttentionAlert summary={summary} onShow={filters.showAttention} />}

      {locations.length > 0 && (
        <DataTools>
          <DataSearch
            value={filters.query}
            onChange={filters.setQuery}
            placeholder="Search name or sub-region"
            testId="locations-search"
          />
          <ChipRow>
            <ChipGroup label="Type">
              <Chip label="All" count={filters.typeCounts['']} on={filters.type === ''} onClick={() => filters.setType('')} testId="locations-chip-all" />
              {DISPLAY_TYPES.map((t) => (
                <Chip
                  key={t}
                  label={locationTypeLabel(t)}
                  count={filters.typeCounts[t]}
                  on={filters.type === t}
                  onClick={() => filters.setType(filters.type === t ? '' : t)}
                  testId={`locations-chip-${t}`}
                />
              ))}
            </ChipGroup>
            <ChipDivider />
            <ChipGroup label="Status">
              <Chip
                label="Needs attention"
                count={filters.statusCounts[STATUS_FILTERS.ATTENTION]}
                on={filters.status === STATUS_FILTERS.ATTENTION}
                onClick={() => filters.toggleStatus(STATUS_FILTERS.ATTENTION)}
                testId="locations-chip-attention"
              />
              <Chip
                label="Disabled"
                count={filters.statusCounts[STATUS_FILTERS.DISABLED]}
                on={filters.status === STATUS_FILTERS.DISABLED}
                onClick={() => filters.toggleStatus(STATUS_FILTERS.DISABLED)}
                testId="locations-chip-disabled"
              />
            </ChipGroup>
            {regions.length > 0 && (
              <>
                <ChipDivider />
                <ChipSelect
                  label="Filter by region"
                  value={filters.region}
                  options={regionOptions}
                  onChange={filters.setRegion}
                  on={filters.region !== ALL_REGIONS}
                  testId="locations-region"
                />
              </>
            )}
          </ChipRow>
          <SortChip
            options={SORT_OPTIONS}
            sortKey={sort.sortKey}
            sortDir={sort.sortDir}
            onChange={sort.setSort}
            testId="locations-sort-chip"
          />
        </DataTools>
      )}

      {body}
    </div>
  );
}

LocationListPane.propTypes = {
  locations: PropTypes.arrayOf(PropTypes.object).isRequired,
  regions: PropTypes.arrayOf(PropTypes.object).isRequired,
  filters: PropTypes.object.isRequired,
  selectedId: PropTypes.number,
  onSelect: PropTypes.func.isRequired,
  onAdd: PropTypes.func.isRequired,
};
