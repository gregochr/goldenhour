import { useMemo } from 'react';
import PropTypes from 'prop-types';
import Pagination from '../Pagination.jsx';
import DataGrid from '../data/DataGrid.jsx';
import { SortChip } from '../data/Chips.jsx';
import StatusDot from '../data/StatusDot.jsx';
import usePagination from '../../hooks/usePagination.js';
import useSortAndFilter from '../../hooks/useSortAndFilter.js';
import { formatDataDate } from '../../utils/dataDates.js';
import {
  REGION_SORT_ACCESSORS, regionRowLabel, regionSummary,
} from '../../utils/regionAdmin.js';

const SORT_OPTIONS = [
  { key: 'name', label: 'Region' },
  { key: 'subRegions', label: 'Sub-regions' },
  { key: 'locations', label: 'Locations' },
  { key: 'created', label: 'Created' },
];

/** The id of the one group header the list has. */
const OFF_GROUP = 'off';

/**
 * The grid's columns (plan §5.4): Region 2fr, Sub-regions .7fr, Locations .7fr, Created .9fr and Status .8fr, the
 * last two secondary (they drop when the panel narrows the list). In card mode line 1 is the name and the status, and
 * line 2 the counts, which ride in two card-only cells because the wide columns are hidden there.
 */
const COLUMNS = [
  {
    key: 'region',
    header: 'Region',
    width: '2fr',
    sortKey: 'name',
    card: 'title',
    cell: (row) => <span className="block truncate font-medium text-plex-text" title={row.name}>{row.name}</span>,
  },
  {
    key: 'subRegions',
    header: 'Sub-regions',
    width: '.7fr',
    sortKey: 'subRegions',
    cell: (row) => (
      <span className="font-mono text-[12.5px] text-plex-text-secondary" data-testid={`region-sub-region-count-${row.id}`}>
        {row.subRegionCount}
      </span>
    ),
  },
  {
    key: 'locations',
    header: 'Locations',
    width: '.7fr',
    sortKey: 'locations',
    cell: (row) => (
      <span className="font-mono text-[12.5px] text-plex-text-secondary" data-testid={`region-location-count-${row.id}`}>
        {row.locationCount}
      </span>
    ),
  },
  {
    key: 'created',
    header: 'Created',
    width: '.9fr',
    sortKey: 'created',
    secondary: true,
    cell: (row) => <span className="font-mono text-[11.5px] text-plex-text-secondary">{formatDataDate(row.createdAt)}</span>,
  },
  {
    key: 'status',
    header: 'Status',
    width: '.8fr',
    secondary: true,
    cell: (row) => (
      <StatusDot tone={row.enabled ? 'go' : 'off'} testId={`region-status-${row.id}`}>
        {row.enabled ? 'Enabled' : 'Disabled'}
      </StatusDot>
    ),
  },
  {
    key: 'card-status',
    card: 'status',
    cardOnly: true,
    cell: (row) => <StatusDot tone={row.enabled ? 'go' : 'off'}>{row.enabled ? 'On' : 'Off'}</StatusDot>,
  },
  {
    key: 'card-summary',
    card: 'summary',
    cardOnly: true,
    cell: (row) => (
      <span className="block truncate text-xs text-plex-text-secondary">
        {regionSummary(row)}
      </span>
    ),
  },
];

/** The text of a row id, which the grid compares with {@code selectedId}. */
const regionId = (row) => row.id;

/**
 * The Regions list on the shared Data grid (plan §5.4): the header with the on/off split and Add region, then the
 * regions that are on, then a collapsible "Off · n" group holding the ones that are off (collapsed unless the reader
 * opens it or the hash selects an off region). Paging counts the rows that show: the off rows join the pages only
 * while the group is open, and the group header sits where the off rows begin (after the last "on" row when the group
 * is collapsed, on the last page).
 *
 * @param {object} props
 * @param {Array<object>} props.regions the region rows with their counts ({@code buildRegionRows})
 * @param {number|null} props.selectedId the region whose panel is open
 * @param {boolean} props.offOpen whether the Off group is open
 * @param {() => void} props.onToggleOff opens or closes the Off group
 * @param {(region: object) => void} props.onSelect called when a row is activated
 * @param {() => void} props.onAdd called by Add region
 * @param {string} [props.notice] a line under the header (a decorating fetch failed)
 */
export default function RegionListPane({ regions, selectedId, offOpen, onToggleOff, onSelect, onAdd, notice = '' }) {
  const sort = useSortAndFilter('name', 'asc', REGION_SORT_ACCESSORS);
  const on = useMemo(() => sort.apply(regions.filter((r) => r.enabled)), [sort, regions]);
  const off = useMemo(() => sort.apply(regions.filter((r) => !r.enabled)), [sort, regions]);
  const paged = useMemo(() => (offOpen ? [...on, ...off] : on), [offOpen, on, off]);
  const { pageItems, ...pagination } = usePagination(paged);

  const rows = useMemo(() => {
    if (off.length === 0) return pageItems;
    const header = {
      groupHeader: true, id: OFF_GROUP, label: `Off · ${off.length}`, expanded: offOpen, onToggle: onToggleOff,
    };
    const firstOff = pageItems.findIndex((r) => !r.enabled);
    if (firstOff >= 0) return [...pageItems.slice(0, firstOff), header, ...pageItems.slice(firstOff)];
    // Collapsed, the header closes the list: on the last page, after the last "on" row.
    return !offOpen && pagination.page >= pagination.totalPages ? [...pageItems, header] : pageItems;
  }, [pageItems, off.length, offOpen, onToggleOff, pagination.page, pagination.totalPages]);

  const onCount = regions.filter((r) => r.enabled).length;

  return (
    <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="region-list-pane">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h2 className="flex items-baseline gap-2 text-sm font-semibold text-plex-text">
          Regions
          <span className="font-mono text-[11px] font-normal text-plex-text-muted" data-testid="region-total">
            {onCount} on · {regions.length - onCount} off
          </span>
        </h2>
        <button
          type="button"
          className="btn-secondary text-xs shrink-0 max-[639px]:min-h-11"
          onClick={onAdd}
          data-testid="add-region-btn"
        >
          Add region
        </button>
      </div>

      {notice && (
        <p className="text-xs text-plex-text-secondary" role="status" data-testid="region-notice">{notice}</p>
      )}

      {regions.length === 0 ? (
        <p className="text-sm text-plex-text-muted" data-testid="region-empty">
          No regions configured. Add one to get started.
        </p>
      ) : (
        <>
          <div className="hidden @max-[540px]:block">
            <SortChip
              options={SORT_OPTIONS}
              sortKey={sort.sortKey}
              sortDir={sort.sortDir}
              onChange={sort.setSort}
              testId="region-sort-chip"
            />
          </div>
          <div>
            <DataGrid
              columns={COLUMNS}
              rows={rows}
              getRowId={regionId}
              getRowLabel={regionRowLabel}
              selectedId={selectedId}
              onSelect={onSelect}
              isOff={(row) => !row.enabled}
              sort={{ key: sort.sortKey, dir: sort.sortDir }}
              onSort={sort.handleSort}
              testIdPrefix="regions"
            />
            <Pagination
              page={pagination.page}
              totalPages={pagination.totalPages}
              pageSize={pagination.pageSize}
              totalItems={paged.length}
              onNextPage={pagination.nextPage}
              onPrevPage={pagination.prevPage}
              onFirstPage={pagination.firstPage}
              onLastPage={pagination.lastPage}
              onSetPageSize={pagination.setPageSize}
            />
          </div>
        </>
      )}
    </div>
  );
}

RegionListPane.propTypes = {
  regions: PropTypes.arrayOf(PropTypes.object).isRequired,
  selectedId: PropTypes.number,
  offOpen: PropTypes.bool.isRequired,
  onToggleOff: PropTypes.func.isRequired,
  onSelect: PropTypes.func.isRequired,
  onAdd: PropTypes.func.isRequired,
  notice: PropTypes.string,
};
