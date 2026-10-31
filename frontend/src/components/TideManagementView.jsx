import React, { useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { fetchAllTideStats } from '../api/tideApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import Pagination from './Pagination.jsx';
import DataGrid from './data/DataGrid.jsx';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import { Chip, ChipDivider, ChipGroup, ChipRow, ChipSelect, SortChip } from './data/Chips.jsx';
import { DataSearch, DataTools } from './data/DataTools.jsx';
import TideRangeBar from './data/TideRangeBar.jsx';
import TideStatsPanel from './tides/TideStatsPanel.jsx';
import { textButtonClass } from './users/userFormClasses.js';
import useCloseWhenFilteredOut from '../hooks/useCloseWhenFilteredOut.js';
import useHashSelection from '../hooks/useHashSelection.js';
import usePagination from '../hooks/usePagination.js';
import useSortAndFilter from '../hooks/useSortAndFilter.js';
import {
  NO_VALUE, TIDE_SORT_ACCESSORS, buildTideRows, fmtCount, fmtMetres, fmtPercent, isThin, medianReadings, numOrNull,
} from '../utils/tideAdmin.js';

/** The Manage tab key; the hash is {@code #manage/tides/{locationId}}. */
const TAB = 'tides';

const ALL_REGIONS = 'ALL';

/** The columns a header can sort by, for the sort chip a card-mode list shows in their place. */
const SORT_OPTIONS = [
  { key: 'name', label: 'Location' },
  { key: 'avgRange', label: 'Avg range' },
  { key: 'p95High', label: 'P95 high' },
  { key: 'spring', label: 'Spring' },
  { key: 'dataPoints', label: 'Readings' },
];

/** A row's id; module-level so the selection guard's dependencies stay stable. */
const rowId = (row) => row.id;

/** A row that matches a location opens a panel; a statistics entry with none opens nothing. */
/** A row's location id (null for a statistics entry with no location). */
const locationIdOf = (row) => row.locationId;

const canOpen = (row) => row.locationId != null;

/** A metres figure with its unit (the card line and the row's spoken name), or an em dash. */
function metresText(val) {
  const text = fmtMetres(val);
  return text === NO_VALUE ? text : `${text} m`;
}

/** The accessible name of a row: who, where, the average range, the P95 high, the spring share and the readings. */
function rowLabel(row) {
  const s = row.stats;
  return [
    row.name,
    row.subRegion,
    `average range ${metresText(s.avgRangeMetres)}`,
    `P95 high ${metresText(s.p95HighMetres)}`,
    `spring ${fmtPercent(s.springTideFrequency)}`,
    s.dataPoints == null ? null : `${fmtCount(s.dataPoints)} readings${isThin(row) ? ', thin data' : ''}`,
  ].filter(Boolean).join(', ');
}

/** The Range cell: the bar for a row's numbers. */
function RangeCell({ row }) {
  const s = row.stats;
  return (
    <TideRangeBar
      minLow={numOrNull(s.minLowMetres)}
      avgLow={numOrNull(s.avgLowMetres)}
      avgHigh={numOrNull(s.avgHighMetres)}
      maxHigh={numOrNull(s.maxHighMetres)}
      p95High={numOrNull(s.p95HighMetres)}
      testId={`tide-bar-${row.id}`}
    />
  );
}

RangeCell.propTypes = { row: PropTypes.object.isRequired };

/** The grid's columns: Spring and Readings are secondary; in a card the average range rides line 1 and the bar line 2. */
const COLUMNS = [
  {
    key: 'location',
    header: 'Location',
    width: '1.35fr',
    sortKey: 'name',
    card: 'title',
    cell: (row) => (
      <>
        <span className="block truncate font-medium" title={row.name}>{row.name}</span>
        {row.subRegion && (
          <span className="block truncate text-xs text-plex-text-secondary @max-[540px]:hidden">{row.subRegion}</span>
        )}
      </>
    ),
  },
  {
    key: 'range',
    header: 'Range \u22123.5 \u00b7 0 \u00b7 +3.5',
    width: '1.6fr',
    card: 'summary',
    cell: (row) => <RangeCell row={row} />,
  },
  {
    key: 'avgRange',
    header: 'Avg range',
    width: '.6fr',
    sortKey: 'avgRange',
    cell: (row) => (
      <span className="font-mono text-[12.5px]" data-testid={`tide-avg-range-${row.id}`}>
        {fmtMetres(row.stats.avgRangeMetres)}
      </span>
    ),
  },
  {
    key: 'p95High',
    header: 'P95 high',
    width: '.6fr',
    sortKey: 'p95High',
    cell: (row) => (
      <span className="font-mono text-[12.5px]">{fmtMetres(row.stats.p95HighMetres)}</span>
    ),
  },
  {
    key: 'spring',
    header: 'Spring',
    width: '.55fr',
    sortKey: 'spring',
    secondary: true,
    cell: (row) => (
      <span className="font-mono text-[12.5px] text-plex-text-secondary">{fmtPercent(row.stats.springTideFrequency)}</span>
    ),
  },
  {
    key: 'readings',
    header: 'Readings',
    width: '.6fr',
    sortKey: 'dataPoints',
    secondary: true,
    cell: (row) => (
      <span
        className={`font-mono text-[12.5px] ${isThin(row) ? 'text-verdict-marginal' : 'text-plex-text-secondary'}`}
        data-testid={`tide-readings-${row.id}`}
      >
        {fmtCount(row.stats.dataPoints)}
      </span>
    ),
  },
  {
    key: 'card-status',
    card: 'status',
    cardOnly: true,
    cell: (row) => <span className="font-mono text-[12.5px]">{metresText(row.stats.avgRangeMetres)}</span>,
  },
];

/**
 * Operations → Places → Tides, on the shared Data list-and-panel shell (data-tabs-redesign-plan.md §7).
 *
 * <p>Read-only tide statistics for the enabled coastal locations: a range bar per row (shared scale, so rows
 * compare), the average range, the P95 high and, on a wide list, the spring share and the reading count (amber when
 * thin). A row opens a panel ({@code #manage/tides/{locationId}}) with the full figures. The bulk statistics are keyed
 * by location name, so they are joined to the locations by name; an entry that matches no location still lists, but
 * its row opens nothing. The locations fetch only decorates the rows (sub-region, region, tide types, status): if it
 * fails the statistics still show, with a notice, and a deep link is kept.
 */
export default function TideManagementView() {
  const [statsByName, setStatsByName] = useState(null);
  const [locations, setLocations] = useState([]);
  const [locationsFailed, setLocationsFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [thinOnly, setThinOnly] = useState(false);
  const [region, setRegion] = useState(ALL_REGIONS);
  const { selectedId, select, clear } = useHashSelection(TAB);
  const panelRef = useRef(null);
  const sort = useSortAndFilter('name', 'asc', TIDE_SORT_ACCESSORS);

  useEffect(() => {
    let live = true;
    // The locations only decorate the statistics, so their failure is not the view's: the rows list, but cannot open.
    Promise.all([
      fetchAllTideStats(),
      fetchLocations().then((locs) => ({ locs })).catch(() => ({ failed: true })),
    ])
      .then(([stats, loaded]) => {
        if (!live) return;
        setStatsByName(stats ?? {});
        setLocations(Array.isArray(loaded.locs) ? loaded.locs : []);
        setLocationsFailed(Boolean(loaded.failed));
      })
      .catch(() => { if (live) setError('Failed to load tide statistics.'); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, []);

  const rows = useMemo(() => buildTideRows(statsByName, locations), [statsByName, locations]);
    const regionNames = useMemo(
    () => [...new Set(rows.map((r) => r.region).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [rows],
  );

  const needle = query.trim().toLowerCase();
  // Search and region narrow the set the chip counts describe; the Thin chip narrows it further.
  const scoped = useMemo(() => rows.filter((r) => {
    if (region !== ALL_REGIONS && r.region !== region) return false;
    if (needle === '') return true;
    return r.name.toLowerCase().includes(needle) || (r.subRegion ?? '').toLowerCase().includes(needle);
  }), [rows, region, needle]);
  const thinCount = useMemo(() => scoped.filter(isThin).length, [scoped]);
  const filtered = useMemo(() => (thinOnly ? scoped.filter(isThin) : scoped), [scoped, thinOnly]);
  const sorted = useMemo(() => sort.apply(filtered), [sort, filtered]);
  const { pageItems, ...pagination } = usePagination(sorted);

  const selected = rows.find((r) => r.locationId != null && r.locationId === selectedId) ?? null;

  useCloseWhenFilteredOut({
    selectedId, items: filtered, getId: locationIdOf, filterKey: JSON.stringify([needle, thinOnly, region]),
    onClose: clear,
  });

  // A selection that is not on the list (a pasted unknown id) leaves the bare tab address, replacing the entry.
  useEffect(() => {
    if (!loading && !error && !locationsFailed && selectedId != null && selected == null) clear({ replace: true });
  }, [loading, error, locationsFailed, selectedId, selected, clear]);

  if (loading) {
    return <p className="text-sm text-plex-text-muted animate-pulse">Loading tide statistics...</p>;
  }

  if (error) {
    return <p className="p-4 text-xs text-plex-error" role="alert" data-testid="tides-error">{error}</p>;
  }

  const anyFilter = needle !== '' || thinOnly || region !== ALL_REGIONS;
  const clearFilters = () => {
    setQuery('');
    setThinOnly(false);
    setRegion(ALL_REGIONS);
  };

  let body;
  if (rows.length === 0) {
    body = (
      <p className="text-sm text-plex-text-muted" data-testid="tides-empty">
        No coastal locations with tide data found.
      </p>
    );
  } else if (sorted.length === 0) {
    body = (
      <div className="flex flex-col items-center gap-2 py-8 text-center" data-testid="tides-no-match">
        <p className="text-sm text-plex-text-secondary">No locations match.</p>
        {anyFilter && (
          <button type="button" className={`${textButtonClass} underline`} onClick={clearFilters} data-testid="tides-clear-filters">
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
          getRowId={rowId}
          getRowLabel={rowLabel}
          selectedId={selected ? selected.id : null}
          onSelect={(row) => select(row.locationId)}
          isSelectable={canOpen}
          sort={{ key: sort.sortKey, dir: sort.sortDir }}
          onSort={sort.handleSort}
          testIdPrefix="tides"
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

  const list = (
    <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="tides-list-pane">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-plex-text">Tides</h2>
        <span className="font-mono text-[11px] text-plex-text-muted" data-testid="tides-total">
          {rows.length} coastal location{rows.length === 1 ? '' : 's'} · heights in metres
        </span>
      </div>

      {locationsFailed && (
        <p className="text-xs text-plex-text-secondary" role="status" data-testid="tides-locations-failed">
          Couldn&apos;t load locations, so rows can&apos;t open.
        </p>
      )}

      {rows.length > 0 && (
        <>
          <DataTools>
            <DataSearch value={query} onChange={setQuery} placeholder="Search location or sub-region" testId="tides-search" />
            <ChipRow>
              <ChipGroup label="Data">
                <Chip label="All" count={scoped.length} on={!thinOnly} onClick={() => setThinOnly(false)} testId="tides-chip-all" />
                <Chip label="Thin data" count={thinCount} on={thinOnly} onClick={() => setThinOnly(true)} testId="tides-chip-thin" />
              </ChipGroup>
              {regionNames.length > 0 && (
                <>
                  <ChipDivider />
                  <ChipSelect
                    label="Region"
                    value={region}
                    options={[{ value: ALL_REGIONS, label: 'All regions' }, ...regionNames.map((n) => ({ value: n, label: n }))]}
                    onChange={setRegion}
                    on={region !== ALL_REGIONS}
                    testId="tides-region"
                  />
                </>
              )}
            </ChipRow>
            <SortChip
              options={SORT_OPTIONS}
              sortKey={sort.sortKey}
              sortDir={sort.sortDir}
              onChange={sort.setSort}
              testId="tides-sort-chip"
            />
          </DataTools>
          <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11.5px] text-plex-text-secondary" data-testid="tides-key">
            <li className="flex items-center gap-1.5">
              <span className="h-2 w-5 rounded-sm bg-tide/[.22]" aria-hidden="true" />
              Lowest low to highest high
            </li>
            <li className="flex items-center gap-1.5">
              <span className="h-2 w-5 rounded-sm bg-tide/[.62]" aria-hidden="true" />
              Average low to average high
            </li>
            <li className="flex items-center gap-1.5">
              <span className="h-3.5 w-0.5 bg-verdict-marginal" aria-hidden="true" />
              P95 high
            </li>
          </ul>
        </>
      )}

      {body}
    </div>
  );

  return (
    <div className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible" data-testid="tides-management">
      <DataSplitLayout
        list={list}
        panel={selected && (
          <TideStatsPanel key={selected.id} row={selected} median={medianReadings(rows, selected)} onClose={clear} panelRef={panelRef} />
        )}
        onClose={clear}
        panelLabel={selected ? `Tide statistics for ${selected.name}` : 'Tide statistics'}
        backLabel="Tides"
        panelRef={panelRef}
        selectionKey={selected ? selected.id : null}
        rowTestIdPrefix="tides"
        testId="tides-split"
      />
    </div>
  );
}
