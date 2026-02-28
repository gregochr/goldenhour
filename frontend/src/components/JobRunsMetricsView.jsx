import React, { useState, useEffect, useCallback } from 'react';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { runVeryShortTermForecast, runShortTermForecast, runLongTermForecast } from '../api/forecastApi';
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
  const [runTypeFilter, setRunTypeFilter] = useState(undefined);
  const [hasMore, setHasMore] = useState(true);
  const [runningVeryShortTerm, setRunningVeryShortTerm] = useState(false);
  const [runningShortTerm, setRunningShortTerm] = useState(false);
  const [runningLongTerm, setRunningLongTerm] = useState(false);
  const [runStatus, setRunStatus] = useState(null); // { type: 'success'|'error', message: string }
  const PAGE_SIZE = 20;

  const loadJobRuns = useCallback(async (pageNum) => {
    try {
      setLoading(true);
      const response = await getJobRuns(runTypeFilter, pageNum, PAGE_SIZE);
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
  }, [runTypeFilter]);

  useEffect(() => {
    setPage(0);
    setRuns([]);
    loadJobRuns(0);
  }, [runTypeFilter, loadJobRuns]);

  const handleLoadMore = () => {
    loadJobRuns(page);
  };

  const anyRunning = runningVeryShortTerm || runningShortTerm || runningLongTerm;

  const handleRunVeryShortTerm = async () => {
    if (!window.confirm('Run very-short-term forecast (T, T+1)? This will trigger API calls to Open-Meteo and Claude (may incur costs).')) {
      return;
    }
    setRunningVeryShortTerm(true);
    setRunStatus(null);
    try {
      const results = await runVeryShortTermForecast();
      setRunStatus({ type: 'success', message: `Very-short-term run complete \u2014 ${results.length} evaluation(s) saved.` });
      loadJobRuns(0);
    } catch {
      setRunStatus({ type: 'error', message: 'Very-short-term run failed. Check the logs.' });
    } finally {
      setRunningVeryShortTerm(false);
    }
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
            onClick={handleRunVeryShortTerm}
            disabled={anyRunning}
            data-testid="run-very-short-term-btn"
          >
            {runningVeryShortTerm ? '\u27F3 Running\u2026' : '\u27F3 Optimise Very Short-Term (T, T+1)'}
          </button>
          <button
            className="btn-primary text-sm"
            onClick={handleRunShortTerm}
            disabled={anyRunning}
            data-testid="run-short-term-btn"
          >
            {runningShortTerm ? '\u27F3 Running\u2026' : '\u27F3 Run Short-Term (T, T+1, T+2)'}
          </button>
          <button
            className="btn-secondary text-sm"
            onClick={handleRunLongTerm}
            disabled={anyRunning}
            data-testid="run-long-term-btn"
          >
            {runningLongTerm ? '\u27F3 Running\u2026' : '\u27F3 Run Long-Term (T+3 \u2013 T+7)'}
          </button>
          {runStatus && (
            <p className={`text-xs ${runStatus.type === 'success' ? 'text-green-400' : 'text-red-400'}`}>
              {runStatus.message}
            </p>
          )}
        </div>
      )}

      {error && (
        <div className="bg-red-900/20 border border-red-700 rounded-lg p-4">
          <p className="text-red-400 text-sm">{error}</p>
        </div>
      )}

      {/* Summary cards */}
      <MetricsSummary runs={runs} apiCalls={allApiCalls} />

      {/* Run type filter */}
      <div className="card">
        <label htmlFor="run-type-filter-select" className="block text-sm font-medium text-plex-text-secondary mb-2">Filter by Run Type</label>
        <select
          id="run-type-filter-select"
          value={runTypeFilter || ''}
          onChange={(e) => setRunTypeFilter(e.target.value || undefined)}
          className="w-full px-3 py-2 bg-plex-surface-light border border-plex-border rounded-lg text-plex-text focus:ring-2 focus:ring-plex-gold focus:border-transparent"
          data-testid="run-type-filter-select"
        >
          <option value="">All Run Types</option>
          <option value="VERY_SHORT_TERM">Very Short-Term</option>
          <option value="SHORT_TERM">Short-Term</option>
          <option value="LONG_TERM">Long-Term</option>
          <option value="WEATHER">Weather</option>
          <option value="TIDE">Tide</option>
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
        <div className="bg-plex-surface rounded-lg p-8 text-center border border-plex-border">
          <p className="text-plex-text-muted">No job runs available</p>
        </div>
      )}
    </div>
  );
};

export default JobRunsMetricsView;
