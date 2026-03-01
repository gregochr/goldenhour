import React, { useState, useEffect, useCallback } from 'react';
import { runModelTest, runModelTestForLocation, rerunModelTest, getModelTestRuns, getModelTestResults } from '../api/modelTestApi';
import { fetchLocations } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';

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
  const [confirmDialog, setConfirmDialog] = useState(null);
  const [allLocations, setAllLocations] = useState([]);
  const [allRegions, setAllRegions] = useState([]);
  const [locationPickerOpen, setLocationPickerOpen] = useState(false);
  const [locationFilter, setLocationFilter] = useState('');
  const [selectedLocationId, setSelectedLocationId] = useState(null);
  const [runningLocation, setRunningLocation] = useState(false);
  const [rerunning, setRerunning] = useState(false);

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
      return;
    }
    setSelectedRunId(runId);
    loadResults(runId);
  };

  const handleRunTest = () => {
    const testEntries = getTestSummary();
    setConfirmDialog({
      title: 'Run Model Test',
      message: 'This will call all three Claude models (Haiku, Sonnet, Opus) for one location per region. This may incur significant API costs.',
      confirmLabel: 'Run Test',
      destructive: false,
      testEntries,
      onConfirm: async () => {
        setConfirmDialog(null);
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
    setConfirmDialog({
      title: 'Re-run Model Test',
      message: 'This will re-test the same locations with fresh weather data and fresh Anthropic API calls.',
      confirmLabel: 'Re-run',
      destructive: false,
      testEntries: locationNames.map((name) => {
        const r = results.find((res) => res.locationName === name);
        return { region: r?.regionName || '', location: name };
      }),
      onConfirm: async () => {
        setConfirmDialog(null);
        setRerunning(true);
        setError(null);
        try {
          const response = await rerunModelTest(selectedRunId);
          const newRun = response.data;
          setRuns((prev) => [newRun, ...prev]);
          setSelectedRunId(newRun.id);
          loadResults(newRun.id);
        } catch (err) {
          setError(err?.response?.data?.message || err.message || 'Re-run failed.');
        } finally {
          setRerunning(false);
        }
      },
    });
  };

  const isAnyRunning = running || runningLocation || rerunning;

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

  // Find Haiku baseline for delta calculation
  const getDelta = (region, model, field) => {
    const haikuResult = region.models['HAIKU'];
    const modelResult = region.models[model];
    if (!haikuResult || !modelResult || haikuResult[field] == null || modelResult[field] == null) {
      return null;
    }
    return modelResult[field] - haikuResult[field];
  };

  const formatDelta = (delta) => {
    if (delta == null) return '';
    if (delta === 0) return '';
    const sign = delta > 0 ? '+' : '';
    const colour = delta > 0 ? 'text-green-400' : 'text-red-400';
    return <span className={`text-xs ${colour} ml-1`}>{sign}{delta}</span>;
  };

  const formatDuration = (ms) => {
    if (ms == null) return '\u2014';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const formatCost = (pence) => {
    if (pence == null || pence === 0) return '\u2014';
    return `${pence}p`;
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
                : rerunning ? 'Re-running test\u2026'
                : 'Testing single location\u2026'}
            </span>
          )}
        </div>
        <div className="text-xs text-plex-text-muted leading-relaxed space-y-1">
          <p><span className="font-semibold text-plex-text">Run Model Test</span> — picks one location per region, fetches fresh weather/tide data, then runs Haiku, Sonnet, and Opus against the same data for each location.</p>
          <p><span className="font-semibold text-plex-text">Test One Location</span> — you choose a single location; fetches fresh weather/tide data, then runs all three models against it.</p>
        </div>
      </div>

      {error && (
        <div className="bg-red-900/20 border border-red-700 rounded-lg p-4">
          <p className="text-red-400 text-sm" data-testid="model-test-error">{error}</p>
        </div>
      )}

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
                  <th className="py-2 pr-4">Date</th>
                  <th className="py-2 pr-4">Target</th>
                  <th className="py-2 pr-4">Regions</th>
                  <th className="py-2 pr-4">OK / Fail</th>
                  <th className="py-2 pr-4">Duration</th>
                  <th className="py-2 pr-4">Cost</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr
                    key={run.id}
                    className={`border-b border-plex-border/50 cursor-pointer hover:bg-plex-surface-light transition-colors ${
                      selectedRunId === run.id ? 'bg-plex-surface-light' : ''
                    }`}
                    onClick={() => handleSelectRun(run.id)}
                    data-testid={`model-test-run-${run.id}`}
                  >
                    <td className="py-2 pr-4 text-plex-text">{run.id}</td>
                    <td className="py-2 pr-4 text-plex-text">{run.startedAt ? run.startedAt.slice(0, 19).replace('T', ' ') : run.targetDate}</td>
                    <td className="py-2 pr-4 text-plex-text">{run.targetType}</td>
                    <td className="py-2 pr-4 text-plex-text">{run.regionsCount}</td>
                    <td className="py-2 pr-4">
                      <span className="text-green-400">{run.succeeded}</span>
                      {' / '}
                      <span className={run.failed > 0 ? 'text-red-400' : 'text-plex-text-muted'}>{run.failed}</span>
                    </td>
                    <td className="py-2 pr-4 text-plex-text-secondary">{formatDuration(run.durationMs)}</td>
                    <td className="py-2 pr-4 text-plex-text-secondary">{formatCost(run.totalCostPence)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Results comparison table */}
      {selectedRunId && (
        <div>
          <div className="flex items-center gap-3 mb-2">
            <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide">
              Results for Run #{selectedRunId}
            </p>
            <button
              className="btn-secondary text-xs py-0.5 px-2"
              onClick={handleRerunTest}
              disabled={isAnyRunning || results.length === 0}
              data-testid="rerun-model-test-btn"
            >
              {rerunning ? '\u27F3 Re-running\u2026' : '\u21BB Re-run'}
            </button>
            <span className="text-xs text-plex-text-muted">Same locations, fresh weather/tide data, all three models.</span>
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
                                {model !== 'HAIKU' && formatDelta(getDelta(region, model, 'fierySkyPotential'))}
                              </>
                            ) : '\u2014'}
                          </td>
                          <td className="py-2 pr-4 text-plex-text">
                            {result?.succeeded ? (
                              <>
                                {result.goldenHourPotential ?? '\u2014'}
                                {model !== 'HAIKU' && formatDelta(getDelta(region, model, 'goldenHourPotential'))}
                              </>
                            ) : '\u2014'}
                          </td>
                          <td className="py-2 pr-4 text-plex-text-secondary">
                            {formatDuration(result?.durationMs)}
                          </td>
                          <td className="py-2 pr-4 text-plex-text-secondary text-xs max-w-xs truncate">
                            {result?.succeeded ? (result.summary || '\u2014') : (
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

      {/* Location picker modal */}
      {locationPickerOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
          role="dialog"
          aria-modal="true"
          aria-label="Test One Location"
          data-testid="location-picker-dialog"
        >
          <div className="bg-plex-surface border border-plex-border rounded-xl shadow-2xl p-6 w-full max-w-md flex flex-col gap-4">
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
          </div>
        </div>
      )}

      {/* Confirmation dialog */}
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

export default ModelTestView;
