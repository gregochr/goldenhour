import { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import Pagination from '../Pagination.jsx';
import DataGrid from '../data/DataGrid.jsx';
import { Chip, ChipDivider, ChipGroup, ChipRow, SortChip } from '../data/Chips.jsx';
import { DataSearch, DataTools } from '../data/DataTools.jsx';
import StatusDot from '../data/StatusDot.jsx';
import usePagination from '../../hooks/usePagination.js';
import useSortAndFilter from '../../hooks/useSortAndFilter.js';
import useCloseWhenFilteredOut from '../../hooks/useCloseWhenFilteredOut.js';
import {
  NO_FILTERS, STATUS, STATUS_LABELS, USER_SORT_ACCESSORS, applyUserFilters, chipCounts, formatJoined,
  formatLastActive, roleLabel, statusOf, subRegionsCell,
} from '../../utils/userAdmin.js';
import { textButtonClass } from './userFormClasses.js';

const ROLE_CHIPS = [
  { value: 'ALL', label: 'All', count: 'all' },
  { value: 'LITE_USER', label: 'Lite', count: 'LITE_USER' },
  { value: 'PRO_USER', label: 'Pro', count: 'PRO_USER' },
  { value: 'ADMIN', label: 'Admin', count: 'ADMIN' },
];

const STATUS_CHIPS = [
  { key: 'awaiting', label: 'Awaiting verification' },
  { key: 'disabled', label: 'Disabled' },
];

const SUB_REGION_CHIPS = [
  { key: 'noSubRegions', label: 'No sub-regions' },
  { key: 'locked', label: 'Locked' },
  { key: 'overLimit', label: 'Over limit' },
];

/** The columns a header can sort by, for the sort chip a card-mode list shows in their place. */
const SORT_OPTIONS = [
  { key: 'username', label: 'User' },
  { key: 'role', label: 'Role' },
  { key: 'subRegions', label: 'Sub-regions' },
  { key: 'status', label: 'Status' },
  { key: 'lastActive', label: 'Last active' },
  { key: 'joined', label: 'Joined' },
];

/** Role text: Lite plain, Pro in gold, Admin in the tide colour. */
const ROLE_TEXT = { LITE_USER: 'text-plex-text', PRO_USER: 'text-plex-gold', ADMIN: 'text-tide' };

/** The status as a {@link StatusDot} tone: active is go, awaiting verification amber, disabled the quiet ink. */
const STATUS_DOT_TONE = { [STATUS.ACTIVE]: 'go', [STATUS.AWAITING]: 'amber', [STATUS.DISABLED]: 'off' };

/** A user's id; a module-level function, so the selection guard's dependencies stay stable. */
const userId = (user) => user.id;

/** The Sub-regions cell: two lines, the count and standing over the names (the names line drops in a card). */
function SubRegionsCell({ user }) {
  const cell = subRegionsCell(user);
  if (cell.kind !== 'picks') {
    return <span className="text-xs text-plex-text-muted">{cell.text}</span>;
  }
  return (
    <>
      <span className="block truncate text-xs text-plex-text-secondary">
        <span className="font-mono font-bold text-plex-text">{cell.count}</span>
        {' '}
        {cell.detail}
      </span>
      <span className="block truncate text-xs text-plex-text-secondary @max-[540px]:hidden" title={cell.names}>
        {cell.names}
      </span>
    </>
  );
}

SubRegionsCell.propTypes = { user: PropTypes.object.isRequired };

/** The Role cell. In a card the Status column is gone, so a status other than Active rides under the role. */
function RoleCell({ user }) {
  const status = statusOf(user);
  return (
    <span className="text-xs">
      <span className={ROLE_TEXT[user.role] ?? 'text-plex-text'}>{roleLabel(user.role)}</span>
      {status !== STATUS.ACTIVE && (
        <span className="mt-0.5 hidden @max-[540px]:flex">
          <StatusDot tone={STATUS_DOT_TONE[status]} className="text-[11px]">{STATUS_LABELS[status]}</StatusDot>
        </span>
      )}
    </span>
  );
}

RoleCell.propTypes = { user: PropTypes.object.isRequired };

/** The accessible name of a row, reading all of it: who, their address, role, status and sub-regions. */
function rowLabel(user) {
  const cell = subRegionsCell(user);
  const subRegions = cell.kind === 'picks' ? `${cell.count} ${cell.detail}: ${cell.names}` : cell.text;
  return `${user.username}, ${user.email || 'no email'}, ${roleLabel(user.role)}, ${STATUS_LABELS[statusOf(user)]}, `
    + `sub-regions ${subRegions}, last active ${formatLastActive(user.lastActiveAt)}, joined ${formatJoined(user.createdAt)}`;
}

/** The grid's columns, in the wide order; the three the old compact list dropped are secondary. */
const COLUMNS = [
  {
    key: 'user',
    header: 'User',
    width: '2.2fr',
    sortKey: 'username',
    card: 'title',
    cell: (user) => (
      <>
        <span className="block truncate font-medium text-plex-text" title={user.username}>{user.username}</span>
        <span className="block truncate text-xs text-plex-text-secondary" title={user.email || ''}>
          {user.email || '—'}
        </span>
      </>
    ),
  },
  {
    key: 'role',
    header: 'Role',
    width: '.7fr',
    sortKey: 'role',
    card: 'status',
    cell: (user) => <RoleCell user={user} />,
  },
  {
    key: 'subRegions',
    header: 'Sub-regions',
    width: '2.6fr',
    sortKey: 'subRegions',
    card: 'summary',
    cell: (user) => (
      <div data-testid={`user-sub-regions-${user.id}`}><SubRegionsCell user={user} /></div>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    width: '1.4fr',
    sortKey: 'status',
    secondary: true,
    cell: (user) => {
      const status = statusOf(user);
      return (
        <StatusDot tone={STATUS_DOT_TONE[status]} testId={`user-status-${user.id}`}>{STATUS_LABELS[status]}</StatusDot>
      );
    },
  },
  {
    key: 'lastActive',
    header: 'Last active',
    width: '.95fr',
    sortKey: 'lastActive',
    secondary: true,
    cell: (user) => (
      <span className="font-mono text-[11.5px] text-plex-text-secondary">{formatLastActive(user.lastActiveAt)}</span>
    ),
  },
  {
    key: 'joined',
    header: 'Joined',
    width: '.95fr',
    sortKey: 'joined',
    secondary: true,
    cell: (user) => (
      <span className="font-mono text-[11.5px] text-plex-text-secondary">{formatJoined(user.createdAt)}</span>
    ),
  },
];

/**
 * The Users list on the shared Data grid (handoff B2): one search box over username or email, the chip filters with
 * their counts, sortable columns, and rows that hold no buttons: the whole row is the target, opened by click, Enter
 * or Space. Which columns show is the grid's container query: with the panel open the list narrows and drops Status,
 * Last active and Joined (the secondary columns), and at card width a row is two lines. The chip row is hidden while
 * a panel is open, as before, and returns when it closes. Paging keeps the open panel while its reader is still in the
 * filtered set; a search or chip that filters the reader out closes it.
 *
 * @param {object} props
 * @param {Array<object>} props.users every user from GET /api/users
 * @param {number|null} props.selectedId the row whose panel is open
 * @param {boolean} props.panelOpen a panel (a reader or Add user) is open: the chip row steps aside
 * @param {(user: object) => void} props.onSelect called with the user whose row was activated
 * @param {() => void} props.onAdd called by Add user
 * @param {() => void} props.onSelectionFiltered called when the open reader is filtered out of the list
 * @param {import('react').ReactNode} [props.headerActions] controls shown in the header row before Add user
 *   (the Lite limit button)
 */
export default function UserListPane({
  users, selectedId, panelOpen, onSelect, onAdd, onSelectionFiltered, headerActions = null,
}) {
  const [filters, setFilters] = useState(NO_FILTERS);
  const sort = useSortAndFilter('username', 'asc', USER_SORT_ACCESSORS);
  const counts = useMemo(() => chipCounts(users), [users]);
  const filtered = useMemo(() => applyUserFilters(users, filters), [users, filters]);
  const sorted = useMemo(() => sort.apply(filtered), [sort, filtered]);
  const { pageItems, ...pagination } = usePagination(sorted);

  useCloseWhenFilteredOut({ selectedId, items: filtered, getId: userId, onClose: onSelectionFiltered });

  const anyFilter = filters.search !== '' || filters.role !== 'ALL' || STATUS_CHIPS.some((c) => filters[c.key])
    || SUB_REGION_CHIPS.some((c) => filters[c.key]);

  const toggle = (key) => setFilters((f) => ({ ...f, [key]: !f[key] }));
  const clear = () => setFilters(NO_FILTERS);

  return (
    <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="user-list-pane">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h2 className="flex items-baseline gap-2 text-sm font-semibold text-plex-text">
          Users
          <span className="font-mono text-[11px] font-normal text-plex-text-muted" data-testid="user-total">
            {users.length}
          </span>
        </h2>
        <div className="flex items-center gap-2">
          {headerActions}
          <button
            type="button"
            className="btn-secondary text-xs shrink-0 max-[639px]:min-h-11"
            onClick={onAdd}
            data-testid="add-user-btn"
          >
            Add user
          </button>
        </div>
      </div>

      <DataTools testId="user-tools">
        <DataSearch
          value={filters.search}
          onChange={(search) => setFilters((f) => ({ ...f, search }))}
          placeholder="Search username or email"
          testId="user-search"
        />
        {!panelOpen && (
          <ChipRow>
            <ChipGroup label="Role">
              {ROLE_CHIPS.map((chip) => (
                <Chip
                  key={chip.value}
                  label={chip.label}
                  count={counts[chip.count]}
                  on={filters.role === chip.value}
                  onClick={() => setFilters((f) => ({ ...f, role: chip.value }))}
                  testId={`chip-role-${chip.value}`}
                />
              ))}
            </ChipGroup>
            <ChipDivider />
            <ChipGroup label="Status">
              {STATUS_CHIPS.map((chip) => (
                <Chip
                  key={chip.key}
                  label={chip.label}
                  count={counts[chip.key]}
                  on={filters[chip.key]}
                  onClick={() => toggle(chip.key)}
                  testId={`chip-${chip.key}`}
                />
              ))}
            </ChipGroup>
            <ChipDivider />
            <ChipGroup label="Sub-regions">
              {SUB_REGION_CHIPS.map((chip) => (
                <Chip
                  key={chip.key}
                  label={chip.label}
                  count={counts[chip.key]}
                  on={filters[chip.key]}
                  onClick={() => toggle(chip.key)}
                  testId={`chip-${chip.key}`}
                />
              ))}
            </ChipGroup>
          </ChipRow>
        )}
        <SortChip
          options={SORT_OPTIONS}
          sortKey={sort.sortKey}
          sortDir={sort.sortDir}
          onChange={sort.setSort}
          testId="user-sort-chip"
        />
      </DataTools>

      {sorted.length === 0 ? (
        <div className="flex flex-col items-center gap-2 py-8 text-center" data-testid="user-empty">
          <p className="text-sm text-plex-text-secondary">No users match.</p>
          {anyFilter && (
            <button type="button" className={`${textButtonClass} underline`} onClick={clear} data-testid="clear-filters">
              Clear filters
            </button>
          )}
        </div>
      ) : (
        <div>
          <DataGrid
            columns={COLUMNS}
            rows={pageItems}
            getRowId={userId}
            getRowLabel={rowLabel}
            selectedId={selectedId}
            onSelect={onSelect}
            isOff={(user) => statusOf(user) === STATUS.DISABLED}
            sort={{ key: sort.sortKey, dir: sort.sortDir }}
            onSort={sort.handleSort}
            testIdPrefix="users"
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
      )}
    </div>
  );
}

UserListPane.propTypes = {
  users: PropTypes.arrayOf(PropTypes.object).isRequired,
  selectedId: PropTypes.number,
  panelOpen: PropTypes.bool.isRequired,
  onSelect: PropTypes.func.isRequired,
  onAdd: PropTypes.func.isRequired,
  onSelectionFiltered: PropTypes.func.isRequired,
  headerActions: PropTypes.node,
};
