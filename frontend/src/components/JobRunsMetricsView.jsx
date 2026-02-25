import React, { useState, useEffect } from 'react';
import { getJobRuns } from '../api/metricsApi';
import MetricsSummary from './MetricsSummary';
import JobRunsGrid from './JobRunsGrid';

/**
 * Main container for job run metrics.
 *
 * Displays:
 * - 7-day summary statistics
 * - Pageable job runs grid with filtering
 * - Per-run API call details
 */
const JobRunsMetricsView = () => {
  const [runs, setRuns] = useState([]);
  const [allApiCalls, setAllApiCalls] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [page, setPage] = useState(0);
  const [jobNameFilter, setJobNameFilter] = useState(undefined);
  const [hasMore, setHasMore] = useState(true);

  const PAGE_SIZE = 20;

  useEffect(() => {
    setPage(0);
    setRuns([]);
    loadJobRuns(0);
  }, [jobNameFilter]);

  const loadJobRuns = async (pageNum) => {
    try {
      setLoading(true);
      const response = await getJobRuns(jobNameFilter, pageNum, PAGE_SIZE);
      const newRuns = response.data?.content || [];

      if (pageNum === 0) {
        setRuns(newRuns);
      } else {
        setRuns((prev) => [...prev, ...newRuns]);
      }

      setPage(pageNum + 1);
      setHasMore(newRuns.length === PAGE_SIZE);
    } catch (err) {
      setError(err.message || 'Failed to load job runs');
    } finally {
      setLoading(false);
    }
  };

  const handleLoadMore = () => {
    loadJobRuns(page);
  };

  return (
    <div className="space-y-6">
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4">
          <p className="text-red-800 text-sm">{error}</p>
        </div>
      )}

      {/* Summary cards */}
      <MetricsSummary runs={runs} apiCalls={allApiCalls} />

      {/* Job name filter */}
      <div className="bg-white rounded-lg shadow-sm p-4 border border-gray-200">
        <label className="block text-sm font-medium text-gray-700 mb-2">Filter by Job</label>
        <select
          value={jobNameFilter || ''}
          onChange={(e) => setJobNameFilter(e.target.value || undefined)}
          className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          data-testid="job-filter-select"
        >
          <option value="">All Jobs</option>
          <option value="SONNET">SONNET</option>
          <option value="HAIKU">HAIKU</option>
          <option value="WILDLIFE">WILDLIFE</option>
          <option value="TIDE">TIDE</option>
        </select>
      </div>

      {/* Job runs grid */}
      <JobRunsGrid
        runs={runs}
        onLoadMore={handleLoadMore}
        hasMore={hasMore}
        loading={loading}
      />

      {runs.length === 0 && !loading && (
        <div className="bg-gray-50 rounded-lg p-8 text-center border border-gray-200">
          <p className="text-gray-600">No job runs available</p>
        </div>
      )}
    </div>
  );
};

export default JobRunsMetricsView;
