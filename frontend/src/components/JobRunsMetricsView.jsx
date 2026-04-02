import React, { useState, useEffect, useCallback, useMemo } from 'react';
import PropTypes from 'prop-types';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { runVeryShortTermForecast, runShortTermForecast, runLongTermForecast, refreshTideData, backfillTideData, fetchLocations } from '../api/forecastApi';
import { enrichBortle } from '../api/auroraApi';
import { runBriefing } from '../api/briefingApi.js';
import { getAvailableModels } from '../api/modelsApi';
import { useAuth } from '../context/AuthContext';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import MetricsSummary from './MetricsSummary';
import useConfirmDialog from '../hooks/useConfirmDialog.js';
import ConfirmDialog from './shared/ConfirmDialog.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';
import JobRunsGrid from './JobRunsGrid';
import RunProgressPanel from './RunProgressPanel';
import AuroraForecastModal from './AuroraForecastModal';
import AuroraSimulateModal from './AuroraSimulateModal';

/** Human-readable labels for optimisation strategies shown in the run confirmation dialog. */
const STRATEGY_LABELS = {
  SKIP_LOW_RATED: 'Skip Low-Rated',
  SKIP_EXISTING: 'Skip Already-Evaluated',
  FORCE_IMMINENT: 'Always Evaluate Today',
  FORCE_STALE: 'Re-evaluate Stale Data',
  EVALUATE_ALL: 'Evaluate Everything',
  NEXT_EVENT_ONLY: 'Next Event Only',
  SENTINEL_SAMPLING: 'Sentinel Sampling',
  TIDE_ALIGNMENT: 'Weather/Tide Triage',
};

/**
 * Builds the list of (date, targetType) slots for a given run type.
 * Past slots (sunrise past noon UTC; sunset past 21:00 UTC) are marked disabled.
 *
 * @param {'VERY_SHORT_TERM'|'SHORT_TERM'} runType
 * @returns {Array<{date: string, targetType: string, isPast: boolean, selected: boolean}>}
 */
function computeSlots(runType) {
  const now = new Date();
  const hourUtc = now.getUTCHours();
  const days = runType === 'VERY_SHORT_TERM' ? 2 : 3;
  const slots = [];
  for (let i = 0; i < days; i++) {
    const d = new Date(now);
    d.setUTCDate(d.getUTCDate() + i);
    const date = d.toISOString().slice(0, 10);
    const isToday = i === 0;
    const sunrisePast = isToday && hourUtc >= 12;
    const sunsetPast = isToday && hourUtc >= 21;
    slots.push({ date, targetType: 'SUNRISE', isPast: sunrisePast, selected: !sunrisePast });
    slots.push({ date, targetType: 'SUNSET', isPast: sunsetPast, selected: !sunsetPast });
  }
  return slots;
}

/** Formats a slot date for display, e.g. "Today", "Tomorrow", or "Wed 25 Mar". */
function formatSlotDate(dateStr, index) {
  if (index === 0) return 'Today';
  if (index === 1) return 'Tomorrow';
  const d = new Date(dateStr + 'T12:00:00Z');
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
}

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
const JobRunsMetricsView = ({ activeRunId, onActiveRunChange, onActiveRunClear }) => {
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
  const [runningTide, setRunningTide] = useState(false);
  const [runningBackfill, setRunningBackfill] = useState(false);
  const [runningLightPollution, setRunningLightPollution] = useState(false);
  const [runningBriefing, setRunningBriefing] = useState(false);
  const [showAuroraModal, setShowAuroraModal] = useState(false);
  const [showSimulateModal, setShowSimulateModal] = useState(false);
  const { status: auroraStatus } = useAuroraStatus();
  const [runStatus, setRunStatus] = useState(null);
  const { config: confirmDialog, openDialog, closeDialog, setConfig: setConfirmDialog } = useConfirmDialog();
  const [allLocations, setAllLocations] = useState([]);
  const [dateRange, setDateRange] = useState('7d');
  const [showBriefingRuns, setShowBriefingRuns] = useState(false);
  const [strategies, setStrategies] = useState({});
  const PAGE_SIZE = 20;

  // Load locations and optimisation strategies once for run summaries
  useEffect(() => {
    fetchLocations().then(setAllLocations).catch(() => {});
    getAvailableModels()
      .then((data) => setStrategies(data.optimisationStrategies || {}))
      .catch(() => {});
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

  const anyRunning = runningVeryShortTerm || runningShortTerm || runningLongTerm || runningTide || runningBackfill || runningLightPollution || runningBriefing;

  const buildConfirmDialog = (runType, title, message, confirmLabel, runFn, setRunning) => {
    const summary = getLocationSummary(allLocations, runType);
    const activeStrategies = (strategies[runType] || [])
      .filter((s) => s.enabled)
      .map((s) => ({
        label: STRATEGY_LABELS[s.strategyType] || s.strategyType,
        param: s.paramValue,
        type: s.strategyType,
      }));
    const slots = (runType === 'VERY_SHORT_TERM' || runType === 'SHORT_TERM')
      ? computeSlots(runType)
      : null;
    // Drive-time-based location exclusion is disabled — per-user drive times
    // are not available in the admin run dialog context.
    const hasDriveTimes = false;
    openDialog({
      title,
      message,
      confirmLabel,
      destructive: false,
      locationSummary: summary,
      activeStrategies,
      slots,
      runFn,
      setRunning,
      hasDriveTimes,
      driveTimeThreshold: 0,
      onConfirm: async (resolvedSlots, threshold) => {
        closeDialog();
        setRunning(true);
        setRunStatus(null);
        try {
          const excluded = resolvedSlots
            ? resolvedSlots.filter((s) => !s.selected && !s.isPast).map(({ date, targetType }) => ({ date, targetType }))
            : [];
          const excludedLocations = threshold > 0
            ? allLocations
                .filter(() => false)
                .map((l) => l.name)
            : [];
          const result = await runFn(excluded, excludedLocations);
          setRunStatus({ type: 'success', message: result.status || 'Run started.' });
          if (result.jobRunId) {
            onActiveRunChange(result.jobRunId);
          }
        } catch {
          setRunStatus({ type: 'error', message: `${title} failed. Check the logs.` });
        } finally {
          setRunning(false);
        }
      },
    });
  };

  const handleEnrichLightPollution = async () => {
    setRunningLightPollution(true);
    setRunStatus(null);
    try {
      const result = await enrichBortle();
      const msg = result.jobRunId
        ? `Light pollution enrichment started (job #${result.jobRunId}).`
        : 'Light pollution enrichment started.';
      setRunStatus({ type: 'success', message: msg });
      if (result.jobRunId) {
        onActiveRunChange(result.jobRunId);
      }
    } catch (err) {
      const msg = err?.response?.status === 400
        ? 'Light pollution API key not configured.'
        : 'Light pollution refresh failed.';
      setRunStatus({ type: 'error', message: msg });
    } finally {
      setRunningLightPollution(false);
    }
  };

  const handleRunBriefing = async () => {
    setRunningBriefing(true);
    setRunStatus(null);
    try {
      const result = await runBriefing();
      setRunStatus({ type: 'success', message: result.status || 'Briefing refreshed.' });
      loadJobRuns(0);
    } catch {
      setRunStatus({ type: 'error', message: 'Briefing refresh failed. Check the logs.' });
    } finally {
      setRunningBriefing(false);
    }
  };

  const handleRunVeryShortTerm = () => buildConfirmDialog(
    'VERY_SHORT_TERM',
    'Run Very-Short-Term Forecast',
    'Run very-short-term forecast (T, T+1)?',
    'Run',
    runVeryShortTermForecast,
    setRunningVeryShortTerm,
  );

  const handleRunShortTerm = () => buildConfirmDialog(
    'SHORT_TERM',
    'Run Short-Term Forecast',
    'Run short-term forecast (T, T+1, T+2)?',
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
    openDialog({
      title: 'Backfill Tide History',
      message: `Backfill 12 months of historical tide data for ${seascapeCoastal.length} SEASCAPE location(s)? This fetches in 7-day chunks, skipping dates where data already exists. Multiple WorldTides API calls will be made per location.`,
      confirmLabel: 'Backfill',
      destructive: false,
      locationSummary: summary,
      onConfirm: async () => {
        closeDialog();
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

  const todayStr = useMemo(() => new Date().toLocaleDateString('en-CA'), []);
  const dateFilteredRuns = useMemo(() => {
    if (!runs || runs.length === 0) return [];
    let filtered = runs;
    if (!showBriefingRuns) {
      filtered = filtered.filter((r) => r.runType !== 'BRIEFING');
    }
    if (dateRange === 'today') {
      return filtered.filter((r) => r.startedAt && r.startedAt.slice(0, 10) === todayStr);
    }
    const cutoff = new Date();
    cutoff.setDate(cutoff.getDate() - 7);
    return filtered.filter((r) => r.startedAt && new Date(r.startedAt) >= cutoff);
  }, [runs, dateRange, todayStr, showBriefingRuns]);

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
              <button
                className="text-sm px-3 py-1.5 font-medium rounded border border-indigo-500/50 bg-indigo-900/30 text-indigo-300 hover:bg-indigo-800/40 transition-colors disabled:opacity-50"
                onClick={() => setShowAuroraModal(true)}
                disabled={anyRunning}
                title="Choose which nights to generate Claude aurora forecasts for"
                data-testid="run-aurora-forecast-btn"
              >
                🌌 Aurora Forecast
              </button>
              <button
                className={`text-sm px-3 py-1.5 font-medium rounded border transition-colors disabled:opacity-50 ${
                  auroraStatus?.simulated
                    ? 'border-amber-500/50 bg-amber-900/30 text-amber-300 hover:bg-amber-800/40'
                    : 'border-plex-border bg-plex-bg text-plex-text-muted hover:bg-plex-border/30'
                }`}
                onClick={() => setShowSimulateModal(true)}
                title={auroraStatus?.simulated ? 'Simulation active — click to manage' : 'Inject fake NOAA data for UI testing'}
                data-testid="aurora-simulate-btn"
              >
                {auroraStatus?.simulated ? '🧪 Simulated' : '🧪 Simulate'}
              </button>
              <button
                className="btn-primary text-sm"
                onClick={handleRunBriefing}
                disabled={anyRunning}
                title="Trigger an immediate briefing refresh"
                data-testid="run-briefing-btn"
              >
                {runningBriefing ? '\u27F3 Running\u2026' : '\u27F3 Briefing'}
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
              <button
                className="btn-primary text-sm"
                onClick={handleEnrichLightPollution}
                disabled={anyRunning}
                title="Enrich all unenriched locations with Bortle light-pollution class"
                data-testid="refresh-light-pollution-btn"
              >
                {runningLightPollution ? '\u27F3 Running\u2026' : '🌌 Refresh Light Pollution'}
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
      {activeRunId && (
        <RunProgressPanel
          jobRunId={activeRunId}
          onComplete={() => {
            onActiveRunClear();
            loadJobRuns(0);
          }}
        />
      )}

      <ErrorBanner message={error} />

      {/* Summary cards */}
      <MetricsSummary runs={runs} apiCalls={allApiCalls} range={dateRange} onRangeChange={setDateRange} />

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
          <option value="BRIEFING">Briefing</option>
        </select>
        <label className="flex items-center gap-2 mt-2 text-sm text-plex-text-secondary cursor-pointer">
          <input
            type="checkbox"
            checked={showBriefingRuns}
            onChange={(e) => setShowBriefingRuns(e.target.checked)}
            data-testid="show-briefing-checkbox"
            className="rounded border-plex-border"
          />
          Show briefing runs
        </label>
      </div>

      {/* Job runs grid — filtered by the same date range as the summary */}
      <JobRunsGrid
        runs={dateFilteredRuns}
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
        <ConfirmDialog
          title={confirmDialog.title}
          message={confirmDialog.message}
          confirmLabel={confirmDialog.confirmLabel}
          onConfirm={() => confirmDialog.onConfirm(confirmDialog.slots, confirmDialog.driveTimeThreshold || 0)}
          onCancel={closeDialog}
          destructive={confirmDialog.destructive}
        >
          {/* Slot selector — VST and ST only */}
          {confirmDialog.slots && (() => {
            const slots = confirmDialog.slots;
            const anyDeselected = slots.some((s) => !s.isPast && !s.selected);
            const hasJfdi = confirmDialog.activeStrategies?.some((s) => s.type === 'FORCE_IMMINENT');
            const hasNextEventOnly = confirmDialog.activeStrategies?.some((s) => s.type === 'NEXT_EVENT_ONLY');
            const uniqueDates = [...new Set(slots.map((s) => s.date))];
            return (
              <div data-testid="confirm-dialog-slots">
                <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">Slots to evaluate</p>
                <div className="flex flex-col gap-1.5">
                  {uniqueDates.map((date, di) => (
                    <div key={date} className="flex items-center gap-3">
                      <span className="text-xs text-plex-text-muted w-20 shrink-0">{formatSlotDate(date, di)}</span>
                      {slots.filter((s) => s.date === date).map((slot) => {
                        const id = `slot-${slot.date}-${slot.targetType}`;
                        return (
                          <label
                            key={id}
                            htmlFor={id}
                            className={`flex items-center gap-1.5 text-xs cursor-pointer select-none ${slot.isPast ? 'opacity-40 cursor-not-allowed' : 'text-plex-text'}`}
                          >
                            <input
                              id={id}
                              type="checkbox"
                              checked={slot.selected}
                              disabled={slot.isPast}
                              data-testid={id}
                              onChange={() => {
                                if (slot.isPast) return;
                                setConfirmDialog((prev) => ({
                                  ...prev,
                                  slots: prev.slots.map((s) =>
                                    s.date === slot.date && s.targetType === slot.targetType
                                      ? { ...s, selected: !s.selected }
                                      : s,
                                  ),
                                }));
                              }}
                              className="accent-blue-400"
                            />
                            {slot.targetType === 'SUNRISE' ? '🌅 Sunrise' : '🌇 Sunset'}
                            {slot.isPast && <span className="text-plex-text-muted italic">(past)</span>}
                          </label>
                        );
                      })}
                    </div>
                  ))}
                </div>
                {anyDeselected && (hasJfdi || hasNextEventOnly) && (
                  <p className="mt-2 text-xs text-amber-400" data-testid="slot-override-warning">
                    ⚠️{' '}
                    {hasJfdi && hasNextEventOnly
                      ? 'JFDI and Next Event Only are'
                      : hasJfdi
                        ? 'JFDI (Always Evaluate Today) is'
                        : 'Next Event Only is'}{' '}
                    active — {hasJfdi && hasNextEventOnly ? 'they' : 'it'} will override your slot selection.
                  </p>
                )}
              </div>
            );
          })()}

          {/* Drive time threshold — only for VST/ST with cached drive times */}
          {confirmDialog.hasDriveTimes && confirmDialog.slots && (() => {
            const threshold = confirmDialog.driveTimeThreshold || 0;
            const excludedCount = threshold > 0
              ? allLocations.filter(() => false).length
              : 0;
            return (
              <div data-testid="confirm-dialog-drive-time">
                <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-1.5">Drive time limit</p>
                <div className="flex items-center gap-3">
                  <select
                    value={threshold}
                    onChange={(e) => setConfirmDialog((prev) => ({ ...prev, driveTimeThreshold: parseInt(e.target.value, 10) }))}
                    className="text-xs px-2 py-1 bg-plex-surface-light border border-plex-border rounded text-plex-text"
                    data-testid="drive-time-threshold-select"
                  >
                    <option value={0}>All distances</option>
                    <option value={30}>Within 30 min</option>
                    <option value={45}>Within 45 min</option>
                    <option value={60}>Within 60 min</option>
                    <option value={90}>Within 90 min</option>
                    <option value={120}>Within 2 hours</option>
                  </select>
                  {excludedCount > 0 && (
                    <span className="text-xs text-amber-400">
                      {excludedCount} location{excludedCount !== 1 ? 's' : ''} excluded
                    </span>
                  )}
                </div>
              </div>
            );
          })()}

          {confirmDialog.activeStrategies?.length > 0 && (
            <div data-testid="confirm-dialog-strategies">
              <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-1.5">Active optimisations</p>
              <div className="flex flex-wrap gap-1.5">
                {confirmDialog.activeStrategies.map((s) => (
                  <span
                    key={s.type}
                    className="inline-block text-xs px-2 py-0.5 rounded-full bg-blue-900/40 text-blue-300 border border-blue-700/40"
                  >
                    {s.label}{s.param != null ? ` (${s.param})` : ''}
                  </span>
                ))}
              </div>
            </div>
          )}
          {confirmDialog.locationSummary && (
            <LocationSummary
              groups={confirmDialog.locationSummary.groups}
              total={confirmDialog.locationSummary.total}
            />
          )}
        </ConfirmDialog>
      )}

      {/* Aurora forecast night selector modal */}
      {showAuroraModal && (
        <AuroraForecastModal
          onClose={() => setShowAuroraModal(false)}
          onComplete={(result) => {
            const nightCount = result.nights?.length ?? 0;
            const scored = result.nights?.reduce((sum, n) => sum + (n.locationsScored ?? 0), 0) ?? 0;
            setRunStatus({
              type: 'success',
              message: `Aurora forecast complete: ${nightCount} night${nightCount !== 1 ? 's' : ''}, ${scored} location${scored !== 1 ? 's' : ''} scored. Cost: ${result.estimatedCost ?? '—'}.`,
            });
            loadJobRuns(0);
          }}
        />
      )}

      {/* Aurora simulation modal */}
      {showSimulateModal && (
        <AuroraSimulateModal
          isActive={auroraStatus?.simulated === true}
          onClose={() => setShowSimulateModal(false)}
          onSuccess={(msg) => {
            setRunStatus({ type: 'success', message: msg });
          }}
        />
      )}
    </div>
  );
};

JobRunsMetricsView.propTypes = {
  activeRunId: PropTypes.number,
  onActiveRunChange: PropTypes.func.isRequired,
  onActiveRunClear: PropTypes.func.isRequired,
};

export default JobRunsMetricsView;
