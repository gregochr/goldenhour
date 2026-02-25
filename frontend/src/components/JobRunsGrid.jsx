import React, { useState } from 'react';
import PropTypes from 'prop-types';
import JobRunDetail from './JobRunDetail';

/**
 * Sortable/pageable grid of job runs with expandable detail rows.
 *
 * Features:
 * - Sort by date, duration, success count
 * - Pageable (20 per page)
 * - Color-coded status badges
 * - Expandable rows for per-service breakdown
 */
const JobRunsGrid = ({ runs, onLoadMore, hasMore, loading }) => {
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
      return <span className="inline-block px-2 py-1 rounded-full bg-green-100 text-green-700 text-xs font-medium">All OK</span>;
    }
    if (successRate >= 80) {
      return <span className="inline-block px-2 py-1 rounded-full bg-yellow-100 text-yellow-700 text-xs font-medium">Partial</span>;
    }
    return <span className="inline-block px-2 py-1 rounded-full bg-red-100 text-red-700 text-xs font-medium">Failed</span>;
  };

  return (
    <div className="bg-white rounded-lg shadow-sm border border-gray-200">
      {/* Sort controls */}
      <div className="p-4 border-b border-gray-200 flex justify-between items-center">
        <h3 className="font-semibold text-gray-900">Job Runs ({runs.length})</h3>
        <div className="flex gap-2">
          {['date', 'duration', 'succeeded'].map((option) => (
            <button
              key={option}
              onClick={() => setSortBy(option)}
              className={`px-3 py-1 text-sm rounded ${
                sortBy === option
                  ? 'bg-blue-100 text-blue-700 font-medium'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
              data-testid={`sort-${option}`}
            >
              {option === 'date' && 'Date'}
              {option === 'duration' && 'Duration'}
              {option === 'succeeded' && 'Succeeded'}
            </button>
          ))}
        </div>
      </div>

      {/* Runs table */}
      <div className="divide-y divide-gray-200">
        {sortedRuns.length === 0 ? (
          <div className="p-4 text-gray-500 text-sm">No job runs available</div>
        ) : (
          <>
            {sortedRuns.map((run) => (
              <div key={run.id}>
                <div
                  className="p-4 hover:bg-gray-50 cursor-pointer transition-colors"
                  onClick={() => setExpandedId(expandedId === run.id ? null : run.id)}
                  data-testid={`job-run-row-${run.id}`}
                >
                  <div className="flex justify-between items-center">
                    <div className="flex-1">
                      <div className="font-medium text-gray-900 flex items-center gap-2">
                        {run.jobName}
                        {getStatusBadge(run)}
                      </div>
                      <div className="text-xs text-gray-600 mt-1">
                        {new Date(run.startedAt).toLocaleString()} · {run.durationMs}ms
                      </div>
                    </div>
                    <div className="text-right flex-shrink-0 ml-4">
                      <div className="text-sm font-semibold text-gray-900">
                        {run.succeeded}/{(run.succeeded || 0) + (run.failed || 0)}
                      </div>
                      <div className="text-xs text-gray-600">
                        {run.failed > 0 && <span className="text-red-600">{run.failed} failed</span>}
                      </div>
                    </div>
                    <div className="ml-4 text-gray-400">
                      {expandedId === run.id ? '▼' : '▶'}
                    </div>
                  </div>
                </div>

                {/* Expanded detail */}
                {expandedId === run.id && (
                  <div className="p-4 bg-gray-50 border-t border-gray-200">
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
        <div className="p-4 border-t border-gray-200 text-center">
          <button
            onClick={onLoadMore}
            disabled={loading}
            className="px-4 py-2 text-sm font-medium text-blue-600 hover:text-blue-700 disabled:text-gray-400"
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
      jobName: PropTypes.string.isRequired,
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

JobRunsGrid.defaultProps = {
  hasMore: false,
  loading: false,
};

export default JobRunsGrid;
