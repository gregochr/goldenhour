import { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import Pagination from '../Pagination.jsx';
import SortableHeader from '../shared/SortableHeader.jsx';
import usePagination from '../../hooks/usePagination.js';
import useSortAndFilter from '../../hooks/useSortAndFilter.js';
import {
  NO_FILTERS, STATUS_LABELS, STATUS_TONES, USER_SORT_ACCESSORS, applyUserFilters, chipCounts, formatJoined,
  formatLastActive, roleLabel, statusOf, subRegionsCell,
} from '../../utils/userAdmin.js';
import { inputClass, textButtonClass } from './userFormClasses.js';

const CHIP_BASE = 'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 font-mono text-[11px] transition-colors cursor-pointer focus-visible:outline-2 focus-visible:outline-plex-gold';
const CHIP_ON = 'border-plex-gold bg-plex-gold/10 text-plex-text';
const CHIP_OFF = 'border-plex-border text-plex-text-secondary hover:border-plex-border-light hover:text-plex-text';

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

/** Role text: Lite plain, Pro in gold, Admin in the tide colour. */
const ROLE_TEXT = { LITE_USER: 'text-plex-text', PRO_USER: 'text-plex-gold', ADMIN: 'text-tide' };

/** A filter chip carrying its count. A toggle (aria-pressed), or one of the single-select role group. */
function Chip({ label, count, on, onClick, testId }) {
  return (
    <button
      type="button"
      className={`${CHIP_BASE} ${on ? CHIP_ON : CHIP_OFF}`}
      aria-pressed={on}
      onClick={onClick}
      data-testid={testId}
    >
      {label}
      <span className="text-plex-text-muted" data-testid={`${testId}-count`}>{count}</span>
    </button>
  );
}

Chip.propTypes = {
  label: PropTypes.string.isRequired,
  count: PropTypes.number.isRequired,
  on: PropTypes.bool.isRequired,
  onClick: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};

/** The Sub-regions cell: two lines, the count and standing over the names. */
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
      <span className="block truncate text-xs text-plex-text-secondary" title={cell.names}>{cell.names}</span>
    </>
  );
}

SubRegionsCell.propTypes = { user: PropTypes.object.isRequired };

/**
 * The Users list (handoff B2): one search box over username or email, the chip filters with their counts,
 * sortable columns, and rows that hold no buttons: the whole row is the target, opened by click or Enter.
 * With the reader panel open the list narrows to User, Sub-regions and Role.
 *
 * @param {object} props
 * @param {Array<object>} props.users every user from GET /api/users
 * @param {number|null} props.selectedId the row whose panel is open
 * @param {boolean} props.compact the panel is open: show the narrow column set
 * @param {(user: object) => void} props.onSelect called with the user whose row was activated
 * @param {() => void} props.onAdd called by Add user
 */
export default function UserListPane({ users, selectedId, compact, onSelect, onAdd }) {
  const [filters, setFilters] = useState(NO_FILTERS);
  const sort = useSortAndFilter('username', 'asc', USER_SORT_ACCESSORS);
  const counts = useMemo(() => chipCounts(users), [users]);
  const filtered = useMemo(() => applyUserFilters(users, filters), [users, filters]);
  const sorted = useMemo(() => sort.apply(filtered), [sort, filtered]);
  const { pageItems, ...pagination } = usePagination(sorted);

  const anyFilter = filters.search !== '' || filters.role !== 'ALL' || STATUS_CHIPS.some((c) => filters[c.key])
    || SUB_REGION_CHIPS.some((c) => filters[c.key]);

  const toggle = (key) => setFilters((f) => ({ ...f, [key]: !f[key] }));
  const clear = () => setFilters(NO_FILTERS);

  const headerProps = (label, sortKey, className) => ({
    label,
    sortKey,
    className,
    spacer: false,
    currentSortKey: sort.sortKey,
    currentSortDir: sort.sortDir,
    onSort: sort.handleSort,
  });

  function onRowKeyDown(event, user) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect(user);
    }
  }

  return (
    <div className="flex flex-col gap-4 p-4" data-testid="user-list-pane">
      <div className="flex items-center justify-between gap-4">
        <h2 className="flex items-baseline gap-2 text-sm font-semibold text-plex-text">
          Users
          <span className="font-mono text-[11px] font-normal text-plex-text-muted" data-testid="user-total">
            {users.length}
          </span>
        </h2>
        <button type="button" className="btn-secondary text-xs shrink-0" onClick={onAdd} data-testid="add-user-btn">
          Add user
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-2" data-testid="user-tools">
        <div className="relative w-[300px] max-w-full">
          <input
            type="search"
            aria-label="Search username or email"
            placeholder="Search username or email"
            className={`${inputClass} pr-8 [&::-webkit-search-cancel-button]:appearance-none`}
            value={filters.search}
            onChange={(e) => setFilters((f) => ({ ...f, search: e.target.value }))}
            data-testid="user-search"
          />
          {filters.search !== '' && (
            <button
              type="button"
              className="absolute inset-y-0 right-0 flex w-8 items-center justify-center rounded text-base leading-none text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
              aria-label="Clear search"
              onClick={() => setFilters((f) => ({ ...f, search: '' }))}
              data-testid="user-search-clear"
            >
              <span aria-hidden="true">&times;</span>
            </button>
          )}
        </div>
        {!compact && (
          <>
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Role">
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
        </div>
        <span className="h-5 w-px bg-plex-border" aria-hidden="true" />
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Status">
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
        </div>
        <span className="h-5 w-px bg-plex-border" aria-hidden="true" />
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Sub-regions">
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
        </div>
          </>
        )}
      </div>

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
        <div className="overflow-x-auto">
          <table className="w-full table-fixed text-left text-sm" data-testid="users-table">
            <thead>
              <tr className="border-b border-plex-border">
                <SortableHeader {...headerProps('User', 'username', compact ? 'w-[40%]' : 'w-[24%]')} />
                {!compact && <SortableHeader {...headerProps('Role', 'role', 'w-[8%]')} />}
                <SortableHeader {...headerProps('Sub-regions', 'subRegions', compact ? 'w-[40%]' : 'w-[30%]')} />
                {compact && <SortableHeader {...headerProps('Role', 'role', 'w-[20%]')} />}
                {!compact && <SortableHeader {...headerProps('Status', 'status', 'w-[16%]')} />}
                {!compact && <SortableHeader {...headerProps('Last active', 'lastActive', 'w-[11%]')} />}
                {!compact && <SortableHeader {...headerProps('Joined', 'joined', 'w-[11%]')} />}
              </tr>
            </thead>
            <tbody>
              {pageItems.map((user) => {
                const selected = user.id === selectedId;
                const status = statusOf(user);
                const tone = STATUS_TONES[status];
                const roleCell = (
                  <td className="py-2 pr-2 align-top text-xs">
                    <span className={ROLE_TEXT[user.role] ?? 'text-plex-text'}>{roleLabel(user.role)}</span>
                    {compact && status !== 'active' && (
                      <span className={`mt-0.5 flex items-center gap-1.5 text-[11px] ${tone.text}`}>
                        <span className={`size-1.5 shrink-0 rounded-full ${tone.dot}`} aria-hidden="true" />
                        {STATUS_LABELS[status]}
                      </span>
                    )}
                  </td>
                );
                return (
                  <tr
                    key={user.id}
                    role="button"
                    tabIndex={0}
                    aria-current={selected ? 'true' : undefined}
                    onClick={() => onSelect(user)}
                    onKeyDown={(e) => onRowKeyDown(e, user)}
                    className={`cursor-pointer border-b border-plex-border/60 transition-colors last:border-0 hover:bg-plex-surface-light focus-visible:outline-2 focus-visible:outline-plex-gold ${
                      selected ? 'bg-plex-surface-light shadow-[inset_2px_0_0_var(--color-plex-gold)]' : ''
                    }`}
                    data-testid={`user-row-${user.id}`}
                  >
                    <td className="py-2 pl-2 pr-2 align-top">
                      <span className="block truncate font-medium text-plex-text" title={user.username}>{user.username}</span>
                      <span className="block truncate text-xs text-plex-text-secondary" title={user.email || ''}>
                        {user.email || '—'}
                      </span>
                    </td>
                    {!compact && roleCell}
                    <td className="py-2 pr-2 align-top" data-testid={`user-sub-regions-${user.id}`}>
                      <SubRegionsCell user={user} />
                    </td>
                    {compact && roleCell}
                    {!compact && (
                      <td className="py-2 pr-2 align-top text-xs">
                        <span className={`flex items-center gap-1.5 ${tone.text}`} data-testid={`user-status-${user.id}`}>
                          <span className={`size-1.5 shrink-0 rounded-full ${tone.dot}`} aria-hidden="true" />
                          {STATUS_LABELS[status]}
                        </span>
                      </td>
                    )}
                    {!compact && (
                      <td className="py-2 pr-2 align-top font-mono text-[11.5px] text-plex-text-secondary">
                        {formatLastActive(user.lastActiveAt)}
                      </td>
                    )}
                    {!compact && (
                      <td className="py-2 pr-2 align-top font-mono text-[11.5px] text-plex-text-secondary">
                        {formatJoined(user.createdAt)}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
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
  compact: PropTypes.bool.isRequired,
  onSelect: PropTypes.func.isRequired,
  onAdd: PropTypes.func.isRequired,
};
