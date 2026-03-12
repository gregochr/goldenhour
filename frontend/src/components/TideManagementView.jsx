import React, { useEffect, useState, useMemo } from 'react';
import PropTypes from 'prop-types';
import { fetchAllTideStats } from '../api/tideApi.js';
import Pagination from './Pagination.jsx';
import usePagination from '../hooks/usePagination.js';

/**
 * Sortable header cell with optional text filter input.
 *
 * @param {object} props
 * @param {string} props.label - Column header label.
 * @param {string} props.sortKey - Key used for sorting.
 * @param {string} props.currentSortKey - Currently active sort key.
 * @param {'asc'|'desc'} props.currentSortDir - Current sort direction.
 * @param {function} props.onSort - Called with the sort key when clicked.
 * @param {string} [props.filterValue] - Current filter value (omit to hide filter input).
 * @param {function} [props.onFilter] - Called with new filter value.
 * @param {string} [props.filterPlaceholder] - Placeholder for filter input.
 * @param {string} [props.className] - Additional class names.
 */
function SortableHeader({ label, sortKey, currentSortKey, currentSortDir, onSort, filterValue, onFilter, filterPlaceholder, className = '' }) {
  const active = currentSortKey === sortKey;
  const arrow = active ? (currentSortDir === 'asc' ? ' ▲' : ' ▼') : '';

  return (
    <th className={`pb-1 font-medium align-bottom ${className}`}>
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className="text-xs text-plex-text-muted hover:text-plex-text cursor-pointer whitespace-nowrap"
      >
        {label}{arrow}
      </button>
      {onFilter != null && (
        <div className="mt-1">
          <input
            type="text"
            value={filterValue || ''}
            onChange={(e) => onFilter(e.target.value)}
            placeholder={filterPlaceholder || 'Filter…'}
            className="w-full bg-plex-surface-light border border-plex-border rounded px-1.5 py-0.5 text-xs text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
            data-testid={`filter-${sortKey}`}
          />
        </div>
      )}
      {onFilter == null && <div className="mt-1 h-[26px]" />}
    </th>
  );
}

SortableHeader.propTypes = {
  label: PropTypes.string.isRequired,
  sortKey: PropTypes.string.isRequired,
  currentSortKey: PropTypes.string.isRequired,
  currentSortDir: PropTypes.string.isRequired,
  onSort: PropTypes.func.isRequired,
  filterValue: PropTypes.string,
  onFilter: PropTypes.func,
  filterPlaceholder: PropTypes.string,
  className: PropTypes.string,
};

/**
 * Sort and filter hook for tide data rows.
 *
 * @param {string} defaultSortKey - Initial sort column.
 * @param {'asc'|'desc'} defaultSortDir - Initial sort direction.
 * @param {Object<string, function>} accessors - Map of sort key to value accessor.
 * @returns {object} Sort/filter state and handlers.
 */
function useSortAndFilter(defaultSortKey, defaultSortDir, accessors) {
  const [sortKey, setSortKey] = useState(defaultSortKey);
  const [sortDir, setSortDir] = useState(defaultSortDir);
  const [filters, setFilters] = useState({});

  function handleSort(key) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  function setFilter(key, value) {
    setFilters((prev) => ({ ...prev, [key]: value }));
  }

  function getFilterValue(key) {
    return filters[key] || '';
  }

  function apply(items) {
    let result = [...items];

    for (const [key, value] of Object.entries(filters)) {
      if (!value) continue;
      const accessor = accessors[key];
      if (!accessor) continue;
      const lower = value.toLowerCase();
      result = result.filter((item) => {
        const val = accessor(item);
        return val != null && String(val).toLowerCase().includes(lower);
      });
    }

    const accessor = accessors[sortKey];
    if (accessor) {
      result.sort((a, b) => {
        const va = accessor(a);
        const vb = accessor(b);
        if (va == null && vb == null) return 0;
        if (va == null) return 1;
        if (vb == null) return -1;
        if (typeof va === 'string') {
          const cmp = va.localeCompare(vb, undefined, { sensitivity: 'base' });
          return sortDir === 'asc' ? cmp : -cmp;
        }
        const cmp = va < vb ? -1 : va > vb ? 1 : 0;
        return sortDir === 'asc' ? cmp : -cmp;
      });
    }

    return result;
  }

  return { sortKey, sortDir, handleSort, setFilter, getFilterValue, apply };
}

/**
 * Formats a number to a fixed number of decimal places, or returns '—' if null.
 *
 * @param {number|null} val - The value to format.
 * @param {number} [dp=2] - Decimal places.
 * @returns {string} Formatted string.
 */
function fmt(val, dp = 2) {
  return val != null ? Number(val).toFixed(dp) : '—';
}

/**
 * Formats a decimal proportion (0–1) as a percentage string.
 *
 * @param {number|null} val - The value to format.
 * @returns {string} Formatted percentage string.
 */
function fmtPct(val) {
  return val != null ? `${(Number(val) * 100).toFixed(1)}%` : '—';
}

/**
 * Tide data management view. Displays tide statistics for all coastal locations
 * with sorting, filtering by location name, and pagination.
 */
export default function TideManagementView() {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const accessors = useMemo(() => ({
    name: (r) => r.name,
    dataPoints: (r) => r.stats.dataPoints,
    avgHigh: (r) => Number(r.stats.avgHighMetres),
    maxHigh: (r) => Number(r.stats.maxHighMetres),
    avgLow: (r) => Number(r.stats.avgLowMetres),
    minLow: (r) => Number(r.stats.minLowMetres),
    avgRange: (r) => r.stats.avgRangeMetres != null ? Number(r.stats.avgRangeMetres) : null,
    p95High: (r) => r.stats.p95HighMetres != null ? Number(r.stats.p95HighMetres) : null,
    springFreq: (r) => r.stats.springTideFrequency != null ? Number(r.stats.springTideFrequency) : null,
  }), []);

  const sf = useSortAndFilter('name', 'asc', accessors);

  const filteredRows = useMemo(() => sf.apply(rows), [sf, rows]);

  const { pageItems, ...pagination } = usePagination(filteredRows);

  useEffect(() => {
    setLoading(true);
    setError('');
    fetchAllTideStats()
      .then((data) => {
        const mapped = Object.entries(data).map(([name, stats]) => ({ name, stats }));
        setRows(mapped);
      })
      .catch(() => setError('Failed to load tide statistics.'))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm font-semibold text-plex-text">Tide Data</p>
        <span className="text-xs text-plex-text-muted">
          {rows.length} coastal location{rows.length !== 1 ? 's' : ''} with tide data
        </span>
      </div>

      {loading && (
        <p className="text-sm text-plex-text-muted animate-pulse">Loading tide statistics...</p>
      )}

      {error && (
        <p className="text-xs text-red-400">{error}</p>
      )}

      {!loading && !error && rows.length === 0 && (
        <p className="text-sm text-plex-text-muted">No coastal locations with tide data found.</p>
      )}

      {!loading && rows.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm text-left table-fixed" data-testid="tides-table">
            <thead>
              <tr className="text-xs text-plex-text-muted border-b border-plex-border">
                <SortableHeader
                  label="Location"
                  sortKey="name"
                  className="w-[20%]"
                  currentSortKey={sf.sortKey}
                  currentSortDir={sf.sortDir}
                  onSort={sf.handleSort}
                  filterValue={sf.getFilterValue('name')}
                  onFilter={(v) => sf.setFilter('name', v)}
                  filterPlaceholder="Filter location…"
                />
                <SortableHeader label="Data Pts" sortKey="dataPoints" className="w-[8%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <SortableHeader label="Avg High" sortKey="avgHigh" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <SortableHeader label="Max High" sortKey="maxHigh" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <SortableHeader label="Avg Low" sortKey="avgLow" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <SortableHeader label="Min Low" sortKey="minLow" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <SortableHeader label="Avg Range" sortKey="avgRange" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <SortableHeader label="P95 High" sortKey="p95High" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <SortableHeader label="Spring %" sortKey="springFreq" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} />
                <th className="pb-1 font-medium align-top w-[9%]">
                  <span className="text-xs text-plex-text-muted whitespace-nowrap">Unit</span>
                  <div className="mt-1 h-[26px]" />
                </th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map((row) => (
                <tr key={row.name} className="border-b border-plex-surface last:border-0">
                  <td className="py-2 text-plex-text truncate" title={row.name}>{row.name}</td>
                  <td className="py-2 text-plex-text-secondary text-xs">{row.stats.dataPoints}</td>
                  <td className="py-2 text-plex-text-secondary text-xs">{fmt(row.stats.avgHighMetres)}</td>
                  <td className="py-2 text-plex-text-secondary text-xs font-medium text-amber-400">{fmt(row.stats.maxHighMetres)}</td>
                  <td className="py-2 text-plex-text-secondary text-xs">{fmt(row.stats.avgLowMetres)}</td>
                  <td className="py-2 text-plex-text-secondary text-xs font-medium text-blue-400">{fmt(row.stats.minLowMetres)}</td>
                  <td className="py-2 text-plex-text-secondary text-xs">{fmt(row.stats.avgRangeMetres)}</td>
                  <td className="py-2 text-plex-text-secondary text-xs font-medium text-amber-300">{fmt(row.stats.p95HighMetres)}</td>
                  <td className="py-2 text-plex-text-secondary text-xs">{fmtPct(row.stats.springTideFrequency)}</td>
                  <td className="py-2 text-plex-text-muted text-xs">m</td>
                </tr>
              ))}
              {filteredRows.length === 0 && rows.length > 0 && (
                <tr>
                  <td colSpan={10} className="py-4 text-center text-xs text-plex-text-muted">
                    No locations match the current filter.
                  </td>
                </tr>
              )}
              {pageItems.length > 0 && pageItems.length < pagination.pageSize && (
                Array.from({ length: pagination.pageSize - pageItems.length }, (_, i) => (
                  <tr key={`spacer-${i}`} aria-hidden="true">
                    <td colSpan={10} className="py-2 text-sm">&nbsp;</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          <Pagination
            page={pagination.page}
            totalPages={pagination.totalPages}
            pageSize={pagination.pageSize}
            totalItems={filteredRows.length}
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
