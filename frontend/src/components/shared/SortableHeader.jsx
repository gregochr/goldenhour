import React from 'react';
import PropTypes from 'prop-types';

/**
 * Sortable table header cell with an optional per-column filter input.
 *
 * <p>Previously copy-pasted (and drifted) across the admin management views;
 * this is the superset variant. Omit {@code onFilter} to hide the filter
 * input — by default a spacer keeps the header height aligned with filtered
 * columns in the same row; pass {@code spacer={false}} in tables where no
 * column has a filter.
 */
export default function SortableHeader({
  label,
  sortKey,
  currentSortKey,
  currentSortDir,
  onSort,
  filterValue = undefined,
  onFilter = undefined,
  filterPlaceholder = undefined,
  className = '',
  spacer = true,
}) {
  const active = currentSortKey === sortKey;
  const arrow = active ? (currentSortDir === 'asc' ? ' ▲' : ' ▼') : '';

  return (
    <th className={`pb-1 font-medium align-bottom ${className}`}>
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className="text-xs text-plex-text-muted hover:text-plex-text cursor-pointer whitespace-nowrap"
      >
        {label}
        {arrow}
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
      {onFilter == null && spacer && <div className="mt-1 h-[26px]" />}
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
  spacer: PropTypes.bool,
};
