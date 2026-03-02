import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { formatDuration } from '../utils/conversions';
import { formatCostGbp, formatCostUsd } from '../utils/formatCost';
import JobRunDetail from './JobRunDetail';
import InfoTip from './InfoTip.jsx';

/**
 * Sortable/pageable grid of job runs with expandable detail rows.
 *
 * Features:
 * - Sort by date, duration, success count
 * - Pageable (20 per page)
 * - Color-coded status badges
 * - Expandable rows for per-service breakdown
 */
const JobRunsGrid = ({ runs, onLoadMore, hasMore = false, loading = false }) => {
  const [sortBy, setSortBy] = useState('date'); // date, duration, succeeded
  const [expandedId, setExpandedId] = useState(null);

  const sortedRuns = [...runs].sort((a, b) => {
    switch (sortBy) {
      case 'date':
        return new Date(b.startedAt) - new Date(a.startedAt);
      case 'duration':
        return (b.durationMs || 0) - (a.durationMs || 0);
      case 'succeeded':
        return (b.succeeded || 0) - (a.succeeded || 0);
      default:
        return 0;
    }
  });

  const getStatusBadge = (run) => {
    const total = (run.succeeded || 0) + (run.failed || 0);
    if (total === 0) return null;

    const successRate = (run.succeeded / total) * 100;
    if (successRate === 100) {
      return <span className="inline-block px-2 py-1 rounded-full bg-green-900/30 text-green-400 text-xs font-medium">All OK</span>;
    }
    if (successRate >= 80) {
      return <span className="inline-block px-2 py-1 rounded-full bg-yellow-900/30 text-yellow-400 text-xs font-medium">Partial</span>;
    }
    return <span className="inline-block px-2 py-1 rounded-full bg-red-900/30 text-red-400 text-xs font-medium">Failed</span>;
  };

  return (
    <div className="card">
      {/* Sort controls */}
      <div className="p-4 border-b border-plex-border flex justify-between items-center">
        <h3 className="font-semibold text-plex-text">Job Runs ({runs.length})</h3>
        <div className="flex gap-2">
          {[
            { value: 'date', label: 'Date', tooltip: 'Sort by start time (newest first)' },
            { value: 'duration', label: 'Duration', tooltip: 'Sort by runtime (longest first)' },
            { value: 'succeeded', label: 'Succeeded', tooltip: 'Sort by successful count (highest first)' },
          ].map((option) => (
            <div key={option.value} className="flex items-center gap-1">
              <button
                onClick={() => setSortBy(option.value)}
                className={`px-3 py-1 text-sm rounded ${
                  sortBy === option.value
                    ? 'bg-plex-gold/20 text-plex-gold font-medium'
                    : 'bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border'
                }`}
                data-testid={`sort-${option.value}`}
              >
                {option.label}
              </button>
              <InfoTip text={option.tooltip} />
            </div>
          ))}
        </div>
      </div>

      {/* Runs table */}
      <div className="divide-y divide-plex-border">
        {sortedRuns.length === 0 ? (
          <div className="p-4 text-plex-text-muted text-sm">No job runs available</div>
        ) : (
          <>
            {sortedRuns.map((run) => (
              <div key={run.id}>
                <div
                  className="p-4 hover:bg-plex-surface-light cursor-pointer transition-colors"
                  role="button"
                  tabIndex={0}
                  onClick={() => setExpandedId(expandedId === run.id ? null : run.id)}
                  onKeyDown={(e) => e.key === 'Enter' && setExpandedId(expandedId === run.id ? null : run.id)}
                  data-testid={`job-run-row-${run.id}`}
                >
                  <div className="flex justify-between items-center">
                    <div className="flex-1">
                      <div className="font-medium text-plex-text flex items-center gap-2">
                        {run.runType}{run.evaluationModel ? ` · ${run.evaluationModel}` : ''}
                        {getStatusBadge(run)}
                      </div>
                      <div className="text-xs text-plex-text-muted mt-1">
                        {new Date(run.startedAt).toLocaleString()} · {formatDuration(run.durationMs)}
                      </div>
                      {(run.totalCostMicroDollars > 0 || run.totalCostPence > 0) && (
                        <div className="text-xs text-plex-gold mt-1 font-semibold">
                          Cost: {formatCostGbp(run.totalCostMicroDollars, run.exchangeRateGbpPerUsd, run.totalCostPence)}
                          {run.totalCostMicroDollars > 0 && (
                            <span className="text-plex-text-muted font-normal"> ({formatCostUsd(run.totalCostMicroDollars)})</span>
                          )}
                        </div>
                      )}
                    </div>
                    <div className="text-right flex-shrink-0 ml-4">
                      <div className="text-sm font-semibold text-plex-text">
                        {run.succeeded}/{(run.succeeded || 0) + (run.failed || 0)}
                      </div>
                      <div className="text-xs text-plex-text-muted">
                        {run.failed > 0 && <span className="text-red-400">{run.failed} failed</span>}
                      </div>
                    </div>
                    <div className="ml-4 text-plex-text-secondary">
                      {expandedId === run.id ? '▼' : '▶'}
                    </div>
                  </div>
                </div>

                {/* Expanded detail */}
                {expandedId === run.id && (
                  <div className="p-4 bg-plex-surface border-t border-plex-border">
                    <JobRunDetail jobRun={run} />
                  </div>
                )}
              </div>
            ))}
          </>
        )}
      </div>

      {/* Load more */}
      {hasMore && (
        <div className="p-4 border-t border-plex-border text-center">
          <button
            onClick={onLoadMore}
            disabled={loading}
            className="px-4 py-2 text-sm font-medium text-plex-gold hover:text-plex-gold-light disabled:text-plex-text-muted"
            data-testid="load-more-btn"
          >
            {loading ? 'Loading...' : 'Load More'}
          </button>
        </div>
      )}
    </div>
  );
};

JobRunsGrid.propTypes = {
  runs: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.number.isRequired,
      runType: PropTypes.string.isRequired,
      startedAt: PropTypes.string.isRequired,
      durationMs: PropTypes.number,
      succeeded: PropTypes.number,
      failed: PropTypes.number,
    })
  ).isRequired,
  onLoadMore: PropTypes.func.isRequired,
  hasMore: PropTypes.bool,
  loading: PropTypes.bool,
};

export default JobRunsGrid;
