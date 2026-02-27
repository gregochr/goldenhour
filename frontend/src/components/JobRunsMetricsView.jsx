import React, { useState, useEffect, useCallback } from 'react';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { runShortTermForecast, runLongTermForecast } from '../api/forecastApi';
import { useAuth } from '../context/AuthContext';
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
  const { isAdmin } = useAuth();
  const [runs, setRuns] = useState([]);
  const [allApiCalls, setAllApiCalls] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [page, setPage] = useState(0);
  const [jobNameFilter, setJobNameFilter] = useState(undefined);
  const [hasMore, setHasMore] = useState(true);
  const [runningShortTerm, setRunningShortTerm] = useState(false);
  const [runningLongTerm, setRunningLongTerm] = useState(false);
  const [runStatus, setRunStatus] = useState(null); // { type: 'success'|'error', message: string }
  const PAGE_SIZE = 20;

  const loadJobRuns = useCallback(async (pageNum) => {
    try {
      setLoading(true);
      const response = await getJobRuns(jobNameFilter, pageNum, PAGE_SIZE);
      const newRuns = response.data?.content || [];

      if (pageNum === 0) {
        setRuns(newRuns);
        // Load API calls for all runs to calculate slowest service
        if (newRuns.length > 0) {
          const apiCallPromises = newRuns.map((run) => getApiCalls(run.id));
          try {
            const apiCallResponses = await Promise.all(apiCallPromises);
            const allCalls = apiCallResponses.flatMap((res) => res.data || []);
            setAllApiCalls(allCalls);
          } catch {
            // Silently ignore API call loading failures; summary will show "No data"
          }
        } else {
          setAllApiCalls([]);
        }
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
  }, [jobNameFilter]);

  useEffect(() => {
    setPage(0);
    setRuns([]);
    loadJobRuns(0);
  }, [jobNameFilter, loadJobRuns]);

  const handleLoadMore = () => {
    loadJobRuns(page);
  };

  const handleRunShortTerm = async () => {
    if (!window.confirm('Run short-term forecast (T, T+1, T+2)? This will trigger API calls to Open-Meteo and Claude (may incur costs).')) {
      return;
    }
    setRunningShortTerm(true);
    setRunStatus(null);
    try {
      const results = await runShortTermForecast();
      setRunStatus({ type: 'success', message: `Short-term run complete — ${results.length} evaluation(s) saved.` });
      loadJobRuns(0);
    } catch {
      setRunStatus({ type: 'error', message: 'Short-term run failed. Check the logs.' });
    } finally {
      setRunningShortTerm(false);
    }
  };

  const handleRunLongTerm = async () => {
    if (!window.confirm('Run long-term forecast (T+3 through T+7)? This will trigger API calls to Open-Meteo and Claude (may incur costs).')) {
      return;
    }
    setRunningLongTerm(true);
    setRunStatus(null);
    try {
      const results = await runLongTermForecast();
      setRunStatus({ type: 'success', message: `Long-term run complete — ${results.length} evaluation(s) saved.` });
      loadJobRuns(0);
    } catch {
      setRunStatus({ type: 'error', message: 'Long-term run failed. Check the logs.' });
    } finally {
      setRunningLongTerm(false);
    }
  };

  return (
    <div className="space-y-6">
      {isAdmin && (
        <div className="flex flex-wrap items-center gap-3">
          <button
            className="btn-primary text-sm"
            onClick={handleRunShortTerm}
            disabled={runningShortTerm || runningLongTerm}
            data-testid="run-short-term-btn"
          >
            {runningShortTerm ? '⟳ Running…' : '⟳ Run Short-Term (T, T+1, T+2)'}
          </button>
          <button
            className="btn-secondary text-sm"
            onClick={handleRunLongTerm}
            disabled={runningShortTerm || runningLongTerm}
            data-testid="run-long-term-btn"
          >
            {runningLongTerm ? '⟳ Running…' : '⟳ Run Long-Term (T+3 – T+7)'}
          </button>
          {runStatus && (
            <p className={`text-xs ${runStatus.type === 'success' ? 'text-green-400' : 'text-red-400'}`}>
              {runStatus.message}
            </p>
          )}
        </div>
      )}

      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4">
          <p className="text-red-800 text-sm">{error}</p>
        </div>
      )}

      {/* Summary cards */}
      <MetricsSummary runs={runs} apiCalls={allApiCalls} />

      {/* Job name filter */}
      <div className="bg-white rounded-lg shadow-sm p-4 border border-gray-200">
        <label htmlFor="job-filter-select" className="block text-sm font-medium text-gray-700 mb-2">Filter by Job</label>
        <select
          id="job-filter-select"
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
