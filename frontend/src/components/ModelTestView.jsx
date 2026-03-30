import React, { useState, useEffect, useCallback } from 'react';
import { runModelTest, runModelTestForLocation, rerunModelTest, rerunModelTestDeterministic, getModelTestRuns, getModelTestResults } from '../api/modelTestApi';
import { fetchLocations } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { formatCostGbp, formatCostUsd, formatTokens } from '../utils/formatCost';
import useConfirmDialog from '../hooks/useConfirmDialog.js';
import ConfirmDialog from './shared/ConfirmDialog.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';
import Modal from './shared/Modal.jsx';

/**
 * Model comparison test view — triggers A/B/C tests and displays results.
 *
 * Shows a "Run Model Test" button, recent test runs, and a comparison table
 * for the selected run showing Haiku/Sonnet/Opus results side by side.
 */
const ModelTestView = () => {
  const [runs, setRuns] = useState([]);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [loadingResults, setLoadingResults] = useState(false);
  const [error, setError] = useState(null);
  const { config: confirmDialog, openDialog, closeDialog } = useConfirmDialog();
  const [allLocations, setAllLocations] = useState([]);
  const [allRegions, setAllRegions] = useState([]);
  const [locationPickerOpen, setLocationPickerOpen] = useState(false);
  const [locationFilter, setLocationFilter] = useState('');
  const [selectedLocationId, setSelectedLocationId] = useState(null);
  const [runningLocation, setRunningLocation] = useState(false);
  const [rerunning, setRerunning] = useState(false);
  const [rerunningDeterministic, setRerunningDeterministic] = useState(false);
  const [parentResults, setParentResults] = useState([]);
  const [expandedSummary, setExpandedSummary] = useState(null);

  // Load locations and regions once for test summary
  useEffect(() => {
    Promise.all([fetchLocations(), fetchRegions()])
      .then(([locs, regs]) => { setAllLocations(locs); setAllRegions(regs); })
      .catch(() => {});
  }, []);

  /**
   * Computes which location will be tested per region (first enabled colour location alphabetically).
   * Matches backend ModelTestService.findRepresentativeLocation().
   */
  const getTestSummary = () => {
    const enabledRegions = allRegions.filter((r) => r.enabled);
    const enabledLocs = allLocations.filter((loc) => loc.enabled !== false);
    const entries = [];
    for (const region of enabledRegions) {
      const rep = enabledLocs
        .filter((loc) => {
          if (!loc.region || loc.region.id !== region.id) return false;
          const types = loc.locationType || [];
          if (types.length === 0) return true;
          return types.includes('LANDSCAPE') || types.includes('SEASCAPE');
        })
        .sort((a, b) => a.name.localeCompare(b.name))[0];
      if (rep) {
        entries.push({ region: region.name, location: rep.name });
      }
    }
    return entries;
  };

  const loadRuns = useCallback(async () => {
    try {
      setLoading(true);
      const response = await getModelTestRuns();
      setRuns(response.data || []);
    } catch (err) {
      setError(err.message || 'Failed to load test runs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRuns();
  }, [loadRuns]);

  const loadResults = useCallback(async (runId) => {
    try {
      setLoadingResults(true);
      const response = await getModelTestResults(runId);
      setResults(response.data || []);
    } catch (err) {
      setError(err.message || 'Failed to load results');
    } finally {
      setLoadingResults(false);
    }
  }, []);

  const handleSelectRun = (runId) => {
    if (selectedRunId === runId) {
      setSelectedRunId(null);
      setResults([]);
      setParentResults([]);
      return;
    }
    setSelectedRunId(runId);
    loadResults(runId);

    // Load parent results for SAME_DATA runs (for delta comparison)
    const run = runs.find((r) => r.id === runId);
    if (run?.rerunType === 'SAME_DATA' && run?.parentRunId) {
      getModelTestResults(run.parentRunId)
        .then((res) => setParentResults(res.data || []))
        .catch(() => setParentResults([]));
    } else {
      setParentResults([]);
    }
  };

  const handleRunTest = () => {
    const testEntries = getTestSummary();
    openDialog({
      title: 'Run Model Test',
      message: 'This will call all three Claude models (Haiku, Sonnet, Opus) for one location per region. This may incur significant API costs.',
      confirmLabel: 'Run Test',
      destructive: false,
      testEntries,
      onConfirm: async () => {
        closeDialog();
        setRunning(true);
        setError(null);
        try {
          const response = await runModelTest();
          const newRun = response.data;
          setRuns((prev) => [newRun, ...prev]);
          setSelectedRunId(newRun.id);
          loadResults(newRun.id);
        } catch (err) {
          setError(err?.response?.data?.message || err.message || 'Model test failed.');
        } finally {
          setRunning(false);
        }
      },
    });
  };

  /**
   * Locations eligible for single-location test: enabled, has colour types, has a region.
   */
  const eligibleLocations = allLocations
    .filter((loc) => loc.enabled !== false)
    .filter((loc) => {
      const types = loc.locationType || [];
      if (types.length === 0) return true;
      return types.includes('LANDSCAPE') || types.includes('SEASCAPE');
    })
    .filter((loc) => loc.region)
    .sort((a, b) => a.name.localeCompare(b.name));

  const filteredLocations = eligibleLocations.filter((loc) =>
    loc.name.toLowerCase().includes(locationFilter.toLowerCase())
  );

  const handleOpenLocationPicker = () => {
    setLocationFilter('');
    setSelectedLocationId(null);
    setLocationPickerOpen(true);
  };

  const handleRunLocationTest = async () => {
    if (!selectedLocationId) return;
    setLocationPickerOpen(false);
    setRunningLocation(true);
    setError(null);
    try {
      const response = await runModelTestForLocation(selectedLocationId);
      const newRun = response.data;
      setRuns((prev) => [newRun, ...prev]);
      setSelectedRunId(newRun.id);
      loadResults(newRun.id);
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Single-location test failed.');
    } finally {
      setRunningLocation(false);
    }
  };

  const handleRerunTest = () => {
    if (!selectedRunId) return;
    const locationNames = [...new Set(results.map((r) => r.locationName))];
    const testEntries = locationNames.map((name) => {
      const r = results.find((res) => res.locationName === name);
      return { region: r?.regionName || '', location: name };
    });
    openDialog({
      title: 'Re-run (Fresh Data)',
      message: 'Same locations, fresh weather data, all 3 models.',
      confirmLabel: 'Re-run',
      destructive: false,
      testEntries,
      onConfirm: async () => {
        closeDialog();
        setRerunning(true);
        setError(null);
        try {
          const response = await rerunModelTest(selectedRunId);
          const newRun = response.data;
          setRuns((prev) => [newRun, ...prev]);
          setSelectedRunId(newRun.id);
          loadResults(newRun.id);
          setParentResults([]);
        } catch (err) {
          setError(err?.response?.data?.message || err.message || 'Re-run failed.');
        } finally {
          setRerunning(false);
        }
      },
    });
  };

  const handleRerunDeterministic = () => {
    if (!selectedRunId) return;
    const locationNames = [...new Set(results.map((r) => r.locationName))];
    const testEntries = locationNames.map((name) => {
      const r = results.find((res) => res.locationName === name);
      return { region: r?.regionName || '', location: name };
    });
    const hasAtmosphericData = results.some((r) => r.atmosphericDataJson);
    if (!hasAtmosphericData) {
      setError('Cannot replay — this run has no stored atmospheric data (pre-V39 run).');
      return;
    }
    openDialog({
      title: 'Re-run (Same Data — Determinism Test)',
      message: 'Same locations, identical weather data replayed from storage, all 3 models. Tests whether Claude produces consistent evaluations.',
      confirmLabel: 'Re-run (Same Data)',
      destructive: false,
      testEntries,
      onConfirm: async () => {
        closeDialog();
        setRerunningDeterministic(true);
        setError(null);
        try {
          const response = await rerunModelTestDeterministic(selectedRunId);
          const newRun = response.data;
          setRuns((prev) => [newRun, ...prev]);
          setSelectedRunId(newRun.id);
          loadResults(newRun.id);
          // Load current run's results as parent for delta comparison
          if (newRun.rerunType === 'SAME_DATA' && newRun.parentRunId) {
            getModelTestResults(newRun.parentRunId)
              .then((res) => setParentResults(res.data || []))
              .catch(() => setParentResults([]));
          }
        } catch (err) {
          setError(err?.response?.data?.message || err.message || 'Determinism re-run failed.');
        } finally {
          setRerunningDeterministic(false);
        }
      },
    });
  };

  const isAnyRunning = running || runningLocation || rerunning || rerunningDeterministic;

  // Group results by region for display
  const groupedResults = results.reduce((acc, result) => {
    const key = result.regionName;
    if (!acc[key]) {
      acc[key] = { regionName: key, locationName: result.locationName, models: {} };
    }
    acc[key].models[result.evaluationModel] = result;
    return acc;
  }, {});

  const regions = Object.values(groupedResults);

  const selectedRun = runs.find((r) => r.id === selectedRunId);
  const isSameDataRun = selectedRun?.rerunType === 'SAME_DATA';

  // Build parent results lookup for SAME_DATA delta comparison
  const parentResultsMap = {};
  if (isSameDataRun && parentResults.length > 0) {
    for (const r of parentResults) {
      parentResultsMap[`${r.locationName}-${r.evaluationModel}`] = r;
    }
  }

  // Delta calculation: for SAME_DATA runs compare against same model in parent run;
  // for other runs compare against Haiku baseline within current run
  const getDelta = (region, model, field) => {
    const modelResult = region.models[model];
    if (!modelResult || modelResult[field] == null) return null;

    if (isSameDataRun && parentResults.length > 0) {
      const parentResult = parentResultsMap[`${region.locationName}-${model}`];
      if (!parentResult || parentResult[field] == null) return null;
      return modelResult[field] - parentResult[field];
    }

    // Default: Haiku baseline within current run
    const haikuResult = region.models['HAIKU'];
    if (!haikuResult || haikuResult[field] == null) return null;
    return modelResult[field] - haikuResult[field];
  };

  const formatDelta = (delta) => {
    if (delta == null) return '';
    if (delta === 0) return <span className="text-xs text-green-400 ml-1">=</span>;
    const sign = delta > 0 ? '+' : '';
    const absVal = Math.abs(delta);
    const colour = isSameDataRun
      ? (absVal === 0 ? 'text-green-400' : absVal <= 5 ? 'text-amber-400' : 'text-red-400')
      : (delta > 0 ? 'text-green-400' : 'text-red-400');
    return <span className={`text-xs ${colour} ml-1`}>{sign}{delta}</span>;
  };

  const formatDuration = (ms) => {
    if (ms == null) return '\u2014';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const formatRunCost = (run) => {
    const gbp = formatCostGbp(run.totalCostMicroDollars, run.exchangeRateGbpPerUsd, run.totalCostPence);
    const usd = formatCostUsd(run.totalCostMicroDollars);
    return usd !== '\u2014' ? `${gbp} (${usd})` : gbp;
  };

  const formatResultCost = (result, run) => {
    const exchangeRate = run ? run.exchangeRateGbpPerUsd : null;
    const gbp = formatCostGbp(result.costMicroDollars, exchangeRate, result.costPence);
    const usd = formatCostUsd(result.costMicroDollars);
    return usd !== '\u2014' ? `${gbp} (${usd})` : gbp;
  };

  return (
    <div className="space-y-6">
      {/* Run buttons */}
      <div className="space-y-3">
        <div className="flex items-center gap-4">
          <button
            className="btn-primary text-sm"
            onClick={handleRunTest}
            disabled={isAnyRunning}
            data-testid="run-model-test-btn"
          >
            {running ? '\u27F3 Running\u2026' : '\u27F3 Run Model Test'}
          </button>
          <button
            className="btn-secondary text-sm"
            onClick={handleOpenLocationPicker}
            disabled={isAnyRunning}
            data-testid="test-one-location-btn"
          >
            {runningLocation ? '\u27F3 Running\u2026' : '\u2316 Test One Location'}
          </button>
          {isAnyRunning && (
            <span className="text-xs text-plex-text-muted">
              {running ? 'Testing all models across regions\u2026 this may take a minute.'
                : rerunning ? 'Re-running test (fresh data)\u2026'
                : rerunningDeterministic ? 'Re-running test (same data / determinism)\u2026'
                : 'Testing single location\u2026'}
            </span>
          )}
        </div>
        <div className="text-xs text-plex-text-muted leading-relaxed space-y-1">
          <p><span className="font-semibold text-plex-text">Run Model Test</span> — picks one location per region, fetches fresh weather/tide data, then runs Haiku, Sonnet, and Opus against the same data for each location.</p>
          <p><span className="font-semibold text-plex-text">Test One Location</span> — you choose a single location; fetches fresh weather/tide data, then runs all three models against it.</p>
        </div>
      </div>

      <ErrorBanner message={error} data-testid="model-test-error" />

      {/* Recent runs */}
      <div>
        <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">
          Recent Test Runs
        </p>
        {loading ? (
          <p className="text-sm text-plex-text-muted">Loading\u2026</p>
        ) : runs.length === 0 ? (
          <p className="text-sm text-plex-text-muted">No test runs yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm" data-testid="model-test-runs-table">
              <thead>
                <tr className="text-left text-plex-text-muted border-b border-plex-border">
                  <th className="py-2 pr-4">ID</th>
                  <th className="py-2 pr-4">Type</th>
                  <th className="py-2 pr-4">Date</th>
                  <th className="py-2 pr-4">Target</th>
                  <th className="py-2 pr-4">Regions</th>
                  <th className="py-2 pr-4">OK / Fail / Total</th>
                  <th className="py-2 pr-4">Duration</th>
                  <th className="py-2 pr-4">Cost</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => {
                  const borderLeft = run.rerunType === 'FRESH_DATA'
                    ? 'border-l-2 border-l-blue-500'
                    : run.rerunType === 'SAME_DATA'
                    ? 'border-l-2 border-l-amber-500'
                    : '';
                  return (
                  <tr
                    key={run.id}
                    className={`border-b border-plex-border/50 cursor-pointer hover:bg-plex-surface-light transition-colors ${borderLeft} ${
                      selectedRunId === run.id ? 'bg-plex-surface-light' : ''
                    }`}
                    onClick={() => handleSelectRun(run.id)}
                    data-testid={`model-test-run-${run.id}`}
                  >
                    <td className="py-2 pr-4 text-plex-text">
                      {run.id}
                      {run.parentRunId && (
                        <button
                          className="text-xs text-plex-text-muted hover:text-plex-text ml-1"
                          title={`Parent: Run #${run.parentRunId}`}
                          onClick={(e) => { e.stopPropagation(); handleSelectRun(run.parentRunId); }}
                        >
                          {'\u21A9'} #{run.parentRunId}
                        </button>
                      )}
                    </td>
                    <td className="py-2 pr-4">
                      {run.rerunType === 'FRESH_DATA' && (
                        <span className="inline-block px-1.5 py-0.5 rounded text-xs font-medium bg-blue-900/30 text-blue-300">Fresh Data</span>
                      )}
                      {run.rerunType === 'SAME_DATA' && (
                        <span className="inline-block px-1.5 py-0.5 rounded text-xs font-medium bg-amber-900/30 text-amber-300">Same Data</span>
                      )}
                      {!run.rerunType && (
                        <span className="text-xs text-plex-text-muted">Original</span>
                      )}
                    </td>
                    <td className="py-2 pr-4 text-plex-text">{run.startedAt ? run.startedAt.slice(0, 19).replace('T', ' ') : run.targetDate}</td>
                    <td className="py-2 pr-4 text-plex-text">{run.targetType}</td>
                    <td className="py-2 pr-4 text-plex-text">{run.regionsCount}</td>
                    <td className="py-2 pr-4">
                      {(() => {
                        const total = (run.succeeded || 0) + (run.failed || 0);
                        const allPassed = run.failed === 0 && total > 0;
                        const allFailed = run.succeeded === 0 && total > 0;
                        const totalColour = allPassed ? 'text-green-400' : allFailed ? 'text-red-400' : 'text-amber-400';
                        return (
                          <>
                            <span className="text-green-400">{run.succeeded}</span>
                            {' / '}
                            <span className={run.failed > 0 ? 'text-red-400' : 'text-plex-text-muted'}>{run.failed}</span>
                            {' / '}
                            <span className={totalColour}>{total}</span>
                          </>
                        );
                      })()}
                    </td>
                    <td className="py-2 pr-4 text-plex-text-secondary">{formatDuration(run.durationMs)}</td>
                    <td className="py-2 pr-4 text-plex-text-secondary">{formatRunCost(run)}</td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Results comparison table */}
      {selectedRunId && (
        <div>
          <div className="flex items-center gap-3 mb-2 flex-wrap">
            <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide">
              Results for Run #{selectedRunId}
              {isSameDataRun && parentResults.length > 0 && (
                <span className="ml-2 text-amber-400 normal-case font-normal">
                  Deltas show drift from parent run #{selectedRun.parentRunId}
                </span>
              )}
            </p>
            <button
              className="btn-secondary text-xs py-0.5 px-2"
              onClick={handleRerunTest}
              disabled={isAnyRunning || results.length === 0}
              data-testid="rerun-model-test-btn"
            >
              {rerunning ? '\u27F3 Re-running\u2026' : '\u21BB Fresh Data'}
            </button>
            <button
              className="btn-secondary text-xs py-0.5 px-2"
              onClick={handleRerunDeterministic}
              disabled={isAnyRunning || results.length === 0 || !results.some((r) => r.atmosphericDataJson)}
              title={!results.some((r) => r.atmosphericDataJson) ? 'No atmospheric data stored (pre-V39 run)' : 'Replay identical weather data to test Claude determinism'}
              data-testid="rerun-deterministic-btn"
            >
              {rerunningDeterministic ? '\u27F3 Re-running\u2026' : '\u21BB Same Data'}
            </button>
          </div>
          {loadingResults ? (
            <p className="text-sm text-plex-text-muted">Loading results\u2026</p>
          ) : results.length === 0 ? (
            <p className="text-sm text-plex-text-muted">No results for this run.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid="model-test-results-table">
                <thead>
                  <tr className="text-left text-plex-text-muted border-b border-plex-border">
                    <th className="py-2 pr-4">Region</th>
                    <th className="py-2 pr-4">Location</th>
                    <th className="py-2 pr-4">Model</th>
                    <th className="py-2 pr-4">Rating</th>
                    <th className="py-2 pr-4">Fiery Sky</th>
                    <th className="py-2 pr-4">Golden Hour</th>
                    <th className="py-2 pr-4">Tokens</th>
                    <th className="py-2 pr-4">Cost</th>
                    <th className="py-2 pr-4">Duration</th>
                    <th className="py-2 pr-4 max-w-xs">Summary</th>
                  </tr>
                </thead>
                <tbody>
                  {regions.map((region) =>
                    ['HAIKU', 'SONNET', 'OPUS'].map((model, idx) => {
                      const result = region.models[model];
                      const isFirst = idx === 0;
                      return (
                        <tr
                          key={`${region.regionName}-${model}`}
                          className={`border-b border-plex-border/30 ${
                            idx === 2 ? 'border-b-plex-border' : ''
                          }`}
                        >
                          {isFirst && (
                            <td className="py-2 pr-4 text-plex-text font-medium align-top" rowSpan={3}>
                              {region.regionName}
                            </td>
                          )}
                          {isFirst && (
                            <td className="py-2 pr-4 text-plex-text-secondary align-top" rowSpan={3}>
                              {region.locationName}
                            </td>
                          )}
                          <td className="py-2 pr-4">
                            <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                              model === 'HAIKU' ? 'bg-blue-900/30 text-blue-300' :
                              model === 'SONNET' ? 'bg-purple-900/30 text-purple-300' :
                              'bg-amber-900/30 text-amber-300'
                            }`}>
                              {model}
                            </span>
                          </td>
                          <td className="py-2 pr-4 text-plex-text">
                            {result?.succeeded ? (
                              <>{result.rating != null ? `${result.rating}/5` : '\u2014'}</>
                            ) : (
                              <span className="text-red-400 text-xs">Failed</span>
                            )}
                          </td>
                          <td className="py-2 pr-4 text-plex-text">
                            {result?.succeeded ? (
                              <>
                                {result.fierySkyPotential ?? '\u2014'}
                                {(isSameDataRun || model !== 'HAIKU') && formatDelta(getDelta(region, model, 'fierySkyPotential'))}
                              </>
                            ) : '\u2014'}
                          </td>
                          <td className="py-2 pr-4 text-plex-text">
                            {result?.succeeded ? (
                              <>
                                {result.goldenHourPotential ?? '\u2014'}
                                {(isSameDataRun || model !== 'HAIKU') && formatDelta(getDelta(region, model, 'goldenHourPotential'))}
                              </>
                            ) : '\u2014'}
                          </td>
                          <td className="py-2 pr-4 text-plex-text-secondary text-xs">
                            {result?.succeeded && (result.inputTokens || result.outputTokens)
                              ? `${formatTokens((result.inputTokens || 0) + (result.outputTokens || 0))}`
                              : '\u2014'}
                          </td>
                          <td className="py-2 pr-4 text-plex-text-secondary text-xs">
                            {result?.succeeded
                              ? formatResultCost(result, runs.find((r) => r.id === selectedRunId))
                              : '\u2014'}
                          </td>
                          <td className="py-2 pr-4 text-plex-text-secondary">
                            {formatDuration(result?.durationMs)}
                          </td>
                          <td className="py-2 pr-4 text-plex-text-secondary text-xs max-w-xs">
                            {result?.succeeded ? (
                              result.summary ? (
                                <button
                                  className="text-left truncate block max-w-xs hover:text-plex-text transition-colors cursor-pointer"
                                  title="Click to view full summary"
                                  onClick={(e) => { e.stopPropagation(); setExpandedSummary({ model, region: region.regionName, text: result.summary }); }}
                                >
                                  {result.summary}
                                </button>
                              ) : '\u2014'
                            ) : (
                              <span className="text-red-400">{result?.errorMessage || 'Error'}</span>
                            )}
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Expanded summary modal */}
      {expandedSummary && (
        <Modal label="Summary" onClose={() => setExpandedSummary(null)} maxWidth="lg" className="gap-3" data-testid="summary-dialog">
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold text-plex-text">
                {expandedSummary.model} — {expandedSummary.region}
              </p>
              <button
                onClick={() => setExpandedSummary(null)}
                className="w-7 h-7 flex items-center justify-center rounded-full text-plex-text-muted hover:text-plex-text transition-colors"
                aria-label="Close"
              >
                &#x2715;
              </button>
            </div>
            <p className="text-sm text-plex-text-secondary leading-relaxed whitespace-pre-wrap">
              {expandedSummary.text}
            </p>
        </Modal>
      )}

      {/* Location picker modal */}
      {locationPickerOpen && (
        <Modal label="Test One Location" onClose={() => setLocationPickerOpen(false)} data-testid="location-picker-dialog">
            <p className="text-sm font-semibold text-plex-text">Test One Location</p>
            <p className="text-sm text-plex-text-secondary">
              Select a location to test with all three Claude models using identical weather data.
            </p>
            <input
              type="text"
              className="w-full px-3 py-2 rounded bg-plex-bg border border-plex-border text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:border-plex-accent"
              placeholder="Filter locations\u2026"
              value={locationFilter}
              onChange={(e) => setLocationFilter(e.target.value)}
              data-testid="location-picker-filter"
            />
            <div className="max-h-60 overflow-y-auto space-y-1" data-testid="location-picker-list">
              {filteredLocations.length === 0 ? (
                <p className="text-xs text-plex-text-muted py-2">No matching locations.</p>
              ) : (
                filteredLocations.map((loc) => (
                  <button
                    key={loc.id}
                    className={`w-full text-left px-3 py-2 rounded text-sm transition-colors ${
                      selectedLocationId === loc.id
                        ? 'bg-plex-accent/20 border border-plex-accent text-plex-text'
                        : 'hover:bg-plex-surface-light text-plex-text-secondary'
                    }`}
                    onClick={() => setSelectedLocationId(loc.id)}
                    data-testid={`location-picker-item-${loc.id}`}
                  >
                    <span className="font-medium">{loc.name}</span>
                    <span className="text-xs text-plex-text-muted ml-2">{loc.region?.name}</span>
                  </button>
                ))
              )}
            </div>
            <p className="text-xs text-plex-text-muted">
              1 location &times; 3 models = 3 API calls
            </p>
            <div className="flex justify-end gap-2">
              <button
                className="btn-secondary text-sm"
                onClick={() => setLocationPickerOpen(false)}
                data-testid="location-picker-cancel"
              >
                Cancel
              </button>
              <button
                className="btn-primary text-sm"
                onClick={handleRunLocationTest}
                disabled={!selectedLocationId}
                data-testid="location-picker-confirm"
              >
                Run Test
              </button>
            </div>
        </Modal>
      )}

      {/* Confirmation dialog */}
      {confirmDialog && (
        <ConfirmDialog
          title={confirmDialog.title}
          message={confirmDialog.message}
          confirmLabel={confirmDialog.confirmLabel}
          onConfirm={confirmDialog.onConfirm}
          onCancel={closeDialog}
          destructive={confirmDialog.destructive}
        >
          {confirmDialog.testEntries && confirmDialog.testEntries.length > 0 && (
            <div className="max-h-48 overflow-y-auto text-xs space-y-1">
              {confirmDialog.testEntries.map((e) => (
                <div key={e.region} className="flex gap-2">
                  <span className="text-plex-text font-medium">{e.region}:</span>
                  <span className="text-plex-text-muted">{e.location}</span>
                </div>
              ))}
              <p className="text-plex-text-secondary font-medium pt-1 border-t border-plex-border">
                {confirmDialog.testEntries.length} region{confirmDialog.testEntries.length !== 1 ? 's' : ''} &times; 3 models = {confirmDialog.testEntries.length * 3} API calls
              </p>
            </div>
          )}
        </ConfirmDialog>
      )}
    </div>
  );
};

export default ModelTestView;
