import React from 'react';
import PropTypes from 'prop-types';

const PAGE_SIZE_OPTIONS = [10, 25, 50];

const navBtnClass = 'px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text disabled:opacity-40 disabled:cursor-not-allowed';

/**
 * Dark-themed pagination controls for data tables.
 *
 * @param {object} props
 * @param {number} props.page - Current 1-indexed page number.
 * @param {number} props.totalPages - Total number of pages.
 * @param {number} props.pageSize - Current page size.
 * @param {number} props.totalItems - Total number of items across all pages.
 * @param {function} props.onNextPage - Called when Next is clicked.
 * @param {function} props.onPrevPage - Called when Prev is clicked.
 * @param {function} props.onFirstPage - Called when First is clicked.
 * @param {function} props.onLastPage - Called when Last is clicked.
 * @param {function} props.onSetPageSize - Called with new page size.
 */
export default function Pagination({ page, totalPages, pageSize, totalItems, onNextPage, onPrevPage, onFirstPage, onLastPage, onSetPageSize }) {
  if (totalPages <= 1) return null;

  const start = (page - 1) * pageSize + 1;
  const end = Math.min(page * pageSize, totalItems);

  return (
    <div
      className="flex flex-wrap items-center justify-between gap-2 text-xs text-plex-text-muted pt-3"
      data-testid="pagination"
    >
      <span data-testid="pagination-summary">
        Showing {start}-{end} of {totalItems}
      </span>

      <div className="flex items-center gap-1">
        <button
          type="button"
          className={navBtnClass}
          onClick={onFirstPage}
          disabled={page <= 1}
          data-testid="pagination-first"
        >
          First
        </button>
        <button
          type="button"
          className={navBtnClass}
          onClick={onPrevPage}
          disabled={page <= 1}
          data-testid="pagination-prev"
        >
          Prev
        </button>
        <span className="px-1" data-testid="pagination-indicator">
          Page {page} of {totalPages}
        </span>
        <button
          type="button"
          className={navBtnClass}
          onClick={onNextPage}
          disabled={page >= totalPages}
          data-testid="pagination-next"
        >
          Next
        </button>
        <button
          type="button"
          className={navBtnClass}
          onClick={onLastPage}
          disabled={page >= totalPages}
          data-testid="pagination-last"
        >
          Last
        </button>
      </div>

      <div className="flex items-center gap-1" data-testid="pagination-sizes">
        {PAGE_SIZE_OPTIONS.map((size) => (
          <button
            key={size}
            type="button"
            className={`px-1.5 py-0.5 rounded ${
              size === pageSize
                ? 'bg-plex-gold/20 text-plex-gold font-medium'
                : 'bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text'
            }`}
            onClick={() => onSetPageSize(size)}
            data-testid={`pagination-size-${size}`}
          >
            {size}
          </button>
        ))}
      </div>
    </div>
  );
}

Pagination.propTypes = {
  page: PropTypes.number.isRequired,
  totalPages: PropTypes.number.isRequired,
  pageSize: PropTypes.number.isRequired,
  totalItems: PropTypes.number.isRequired,
  onNextPage: PropTypes.func.isRequired,
  onPrevPage: PropTypes.func.isRequired,
  onFirstPage: PropTypes.func.isRequired,
  onLastPage: PropTypes.func.isRequired,
  onSetPageSize: PropTypes.func.isRequired,
};
