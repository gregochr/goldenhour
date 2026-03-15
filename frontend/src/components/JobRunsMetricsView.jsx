import React, { useState, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { runVeryShortTermForecast, runShortTermForecast, runLongTermForecast, refreshTideData, backfillTideData, fetchLocations } from '../api/forecastApi';
import { useAuth } from '../context/AuthContext';
import MetricsSummary from './MetricsSummary';
import JobRunsGrid from './JobRunsGrid';
import RunProgressPanel from './RunProgressPanel';

/** Returns true if the location has LANDSCAPE, SEASCAPE, or WATERFALL (or no types — defaults to colour). */
function hasColourTypes(loc) {
  const types = loc.locationType || [];
  if (types.length === 0) return true;
  return types.includes('LANDSCAPE') || types.includes('SEASCAPE') || types.includes('WATERFALL');
}

/** Returns true if the location is pure-wildlife only (WILDLIFE but no colour types). */
function isPureWildlife(loc) {
  const types = loc.locationType || [];
  return types.includes('WILDLIFE') && !hasColourTypes(loc);
}

/** Returns true if the location is coastal (has at least one tide type). */
function isCoastal(loc) {
  const tides = loc.tideType || [];
  return tides.length > 0;
}

/**
 * Filters enabled locations by run type and groups them by region.
 *
 * @param {Array} locations - All locations from the API.
 * @param {string} runType - VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, or TIDE.
 * @returns {{ groups: Array<{region: string, locations: string[]}>, total: number }}
 */
function getLocationSummary(locations, runType) {
  const enabled = locations.filter((loc) => loc.enabled !== false);

  let filtered;
  if (runType === 'TIDE') {
    filtered = enabled.filter(isCoastal);
  } else if (runType === 'WEATHER') {
    filtered = enabled.filter(isPureWildlife);
  } else {
    filtered = enabled.filter(hasColourTypes);
  }

  // Group by region name (or "No region")
  const byRegion = {};
  for (const loc of filtered) {
    const regionName = loc.region?.name || 'No region';
    if (!byRegion[regionName]) byRegion[regionName] = [];
    byRegion[regionName].push(loc.name);
  }

  const groups = Object.entries(byRegion)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([region, names]) => ({ region, locations: names.sort() }));

  return { groups, total: filtered.length };
}

/**
 * Renders the location summary grouped by region inside a confirmation dialog.
 */
function LocationSummary({ groups, total }) {
  if (total === 0) {
    return <p className="text-xs text-plex-text-muted italic">No matching locations found.</p>;
  }
  return (
    <div className="max-h-48 overflow-y-auto text-xs space-y-2">
      {groups.map((g) => (
        <div key={g.region}>
          <p className="text-plex-text font-medium">{g.region} ({g.locations.length})</p>
          <p className="text-plex-text-muted ml-2">{g.locations.join(', ')}</p>
        </div>
      ))}
      <p className="text-plex-text-secondary font-medium pt-1 border-t border-plex-border">{total} location{total !== 1 ? 's' : ''} total</p>
    </div>
  );
}

LocationSummary.propTypes = {
  groups: PropTypes.arrayOf(PropTypes.shape({
    region: PropTypes.string.isRequired,
    locations: PropTypes.arrayOf(PropTypes.string).isRequired,
  })).isRequired,
  total: PropTypes.number.isRequired,
};

/**
 * Main container for job run metrics.
 *
 * Displays:
 * - 7-day summary statistics
 * - Pageable job runs grid with filtering
 * - Per-run API call details
 */
const JobRunsMetricsView = () => {
  const { isAdmin, token } = useAuth();
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
  const [runningTide, setRunningTide] = useState(false);
  const [runningBackfill, setRunningBackfill] = useState(false);
  const [runStatus, setRunStatus] = useState(null);
  const [confirmDialog, setConfirmDialog] = useState(null);
  const [allLocations, setAllLocations] = useState([]);
  const [activeRunId, setActiveRunId] = useState(null);
  const PAGE_SIZE = 20;

  // Load locations once for run summaries
  useEffect(() => {
    fetchLocations().then(setAllLocations).catch(() => {});
  }, []);

  const loadJobRuns = useCallback(async (pageNum) => {
    try {
      setLoading(true);
      const response = await getJobRuns(runTypeFilter, pageNum, PAGE_SIZE);
      const newRuns = response.data?.content || [];

      if (pageNum === 0) {
        setRuns(newRuns);
        if (newRuns.length > 0) {
          const apiCallPromises = newRuns.map((run) => getApiCalls(run.id));
          try {
            const apiCallResponses = await Promise.all(apiCallPromises);
            const allCalls = apiCallResponses.flatMap((res) => res.data || []);
            setAllApiCalls(allCalls);
          } catch {
            // Silently ignore API call loading failures
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

  const anyRunning = runningVeryShortTerm || runningShortTerm || runningLongTerm || runningTide || runningBackfill;

  const buildConfirmDialog = (runType, title, message, confirmLabel, runFn, setRunning) => {
    const summary = getLocationSummary(allLocations, runType);
    setConfirmDialog({
      title,
      message,
      confirmLabel,
      destructive: false,
      locationSummary: summary,
      onConfirm: async () => {
        setConfirmDialog(null);
        setRunning(true);
        setRunStatus(null);
        try {
          const result = await runFn();
          setRunStatus({ type: 'success', message: result.status || 'Run started.' });
          if (result.jobRunId) {
            setActiveRunId(result.jobRunId);
          }
        } catch {
          setRunStatus({ type: 'error', message: `${title} failed. Check the logs.` });
        } finally {
          setRunning(false);
        }
      },
    });
  };

  const handleRunVeryShortTerm = () => buildConfirmDialog(
    'VERY_SHORT_TERM',
    'Run Very-Short-Term Forecast',
    'Run very-short-term forecast (T, T+1)? This will trigger API calls to Open-Meteo and Claude (may incur costs).',
    'Run',
    runVeryShortTermForecast,
    setRunningVeryShortTerm,
  );

  const handleRunShortTerm = () => buildConfirmDialog(
    'SHORT_TERM',
    'Run Short-Term Forecast',
    'Run short-term forecast (T, T+1, T+2)? This will trigger API calls to Open-Meteo and Claude (may incur costs).',
    'Run',
    runShortTermForecast,
    setRunningShortTerm,
  );

  const handleRunLongTerm = () => buildConfirmDialog(
    'LONG_TERM',
    'Run Long-Term Forecast',
    'Run long-term forecast (T+3 through T+5)? This will trigger API calls to Open-Meteo and Claude (may incur costs).',
    'Run',
    runLongTermForecast,
    setRunningLongTerm,
  );

  const handleRefreshTide = () => buildConfirmDialog(
    'TIDE',
    'Refresh Tide Data',
    'Refresh tide data for all coastal locations? This will call the WorldTides API.',
    'Refresh',
    refreshTideData,
    setRunningTide,
  );

  const handleBackfillTide = () => {
    const seascapeCoastal = allLocations
      .filter((loc) => loc.enabled !== false)
      .filter((loc) => (loc.locationType || []).includes('SEASCAPE'))
      .filter(isCoastal);
    const summary = getLocationSummary(
      allLocations.filter((loc) => (loc.locationType || []).includes('SEASCAPE')),
      'TIDE',
    );
    setConfirmDialog({
      title: 'Backfill Tide History',
      message: `Backfill 12 months of historical tide data for ${seascapeCoastal.length} SEASCAPE location(s)? This fetches in 7-day chunks, skipping dates where data already exists. Multiple WorldTides API calls will be made per location.`,
      confirmLabel: 'Backfill',
      destructive: false,
      locationSummary: summary,
      onConfirm: async () => {
        setConfirmDialog(null);
        setRunningBackfill(true);
        setRunStatus(null);
        try {
          const result = await backfillTideData();
          setRunStatus({ type: 'success', message: result.status || 'Backfill started.' });
        } catch {
          setRunStatus({ type: 'error', message: 'Tide backfill failed. Check the logs.' });
        } finally {
          setRunningBackfill(false);
        }
      },
    });
  };

  return (
    <div className="space-y-6">
      {isAdmin && (
        <div className="card space-y-4">
          <div>
            <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">Forecast Runs</p>
            <div className="flex flex-wrap gap-2">
              <button
                className="btn-primary text-sm"
                onClick={handleRunVeryShortTerm}
                disabled={anyRunning}
                data-testid="run-very-short-term-btn"
              >
                {runningVeryShortTerm ? '\u27F3 Running\u2026' : '\u27F3 Very Short-Term (T, T+1)'}
              </button>
              <button
                className="btn-primary text-sm"
                onClick={handleRunShortTerm}
                disabled={anyRunning}
                data-testid="run-short-term-btn"
              >
                {runningShortTerm ? '\u27F3 Running\u2026' : '\u27F3 Short-Term (T, T+1, T+2)'}
              </button>
              <button
                className="btn-primary text-sm"
                onClick={handleRunLongTerm}
                disabled={anyRunning}
                data-testid="run-long-term-btn"
              >
                {runningLongTerm ? '\u27F3 Running\u2026' : '\u27F3 Long-Term (T+3 \u2013 T+5)'}
              </button>
            </div>
          </div>
          <div>
            <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">Data Refresh</p>
            <div className="flex flex-wrap gap-2">
              <button
                className="btn-primary text-sm"
                onClick={handleRefreshTide}
                disabled={anyRunning}
                data-testid="refresh-tide-btn"
              >
                {runningTide ? '\u27F3 Running\u2026' : '\u27F3 Refresh Tide Data'}
              </button>
              <button
                className="btn-primary text-sm"
                onClick={handleBackfillTide}
                disabled={anyRunning}
                data-testid="backfill-tide-btn"
              >
                {runningBackfill ? '\u27F3 Running\u2026' : '\u27F3 Backfill Tide History (12 mo)'}
              </button>
            </div>
          </div>
          {runStatus && (
            <p className={`text-xs ${runStatus.type === 'success' ? 'text-green-400' : 'text-red-400'}`}>
              {runStatus.message}
            </p>
          )}
        </div>
      )}

      {/* Live run progress */}
      {activeRunId && token && (
        <RunProgressPanel
          jobRunId={activeRunId}
          token={token}
          onComplete={() => {
            setActiveRunId(null);
            loadJobRuns(0);
          }}
        />
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

      {/* Styled confirmation dialog */}
      {confirmDialog && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
          role="dialog"
          aria-modal="true"
          aria-label={confirmDialog.title}
          data-testid="confirm-dialog"
        >
          <div className="bg-plex-surface border border-plex-border rounded-xl shadow-2xl p-6 w-full max-w-md flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">{confirmDialog.title}</p>
            <p className="text-sm text-plex-text-secondary">{confirmDialog.message}</p>
            {confirmDialog.locationSummary && (
              <LocationSummary
                groups={confirmDialog.locationSummary.groups}
                total={confirmDialog.locationSummary.total}
              />
            )}
            <div className="flex justify-end gap-2">
              <button
                className="btn-secondary text-sm"
                onClick={() => setConfirmDialog(null)}
                data-testid="confirm-dialog-cancel"
              >
                Cancel
              </button>
              <button
                className={`text-sm px-4 py-1.5 rounded font-medium ${
                  confirmDialog.destructive
                    ? 'bg-red-700 hover:bg-red-600 text-white'
                    : 'btn-primary'
                }`}
                onClick={confirmDialog.onConfirm}
                data-testid="confirm-dialog-confirm"
              >
                {confirmDialog.confirmLabel}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default JobRunsMetricsView;
