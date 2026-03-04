import React, { useState, useEffect, useCallback, useRef } from 'react';
import { runPromptTest, replayPromptTest, getPromptTestRun, getPromptTestRuns, getPromptTestResults, getGitInfo } from '../api/promptTestApi';
import { getAvailableModels } from '../api/modelsApi';
import { fetchLocations } from '../api/forecastApi';
import { formatCostGbp, formatCostUsd } from '../utils/formatCost';

const USD_TO_GBP = 0.79;
const COST_PER_CALL = { HAIKU: 0.002, SONNET: 0.005, OPUS: 0.008 };
const MODELS = ['HAIKU', 'SONNET', 'OPUS'];
const RUN_TYPES = [
  { value: 'VERY_SHORT_TERM', label: 'Very Short Term', desc: 'T, T+1', days: 2 },
  { value: 'SHORT_TERM', label: 'Short Term', desc: 'T to T+2', days: 3 },
  { value: 'LONG_TERM', label: 'Long Term', desc: 'T+3 to T+7', days: 5 },
];

const POLL_INTERVAL_MS = 3000;

/**
 * Prompt regression test view — run Claude evaluations across all colour locations,
 * then replay with stored data to measure the impact of prompt changes.
 */
const PromptTestView = () => {
  const [runs, setRuns] = useState([]);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [replaying, setReplaying] = useState(false);
  const [loadingResults, setLoadingResults] = useState(false);
  const [error, setError] = useState(null);
  const [confirmDialog, setConfirmDialog] = useState(null);
  const [selectedModel, setSelectedModel] = useState('HAIKU');
  const [selectedRunType, setSelectedRunType] = useState('VERY_SHORT_TERM');
  const [gitInfo, setGitInfo] = useState(null);
  const [colourLocationCount, setColourLocationCount] = useState(0);
  const [checkedRunIds, setCheckedRunIds] = useState([]);
  const [comparisonResults, setComparisonResults] = useState({});
  const [expandedSummary, setExpandedSummary] = useState(null);
  const [modelVersions, setModelVersions] = useState({});
  const pollingRef = useRef(null);

  // Load git info, colour location count, and model versions on mount
  useEffect(() => {
    getGitInfo()
      .then((res) => setGitInfo(res.data))
      .catch(() => {});
    fetchLocations()
      .then((locs) => {
        const colour = locs.filter((loc) => {
          if (loc.enabled === false) return false;
          const types = loc.locationType || [];
          if (types.length === 0) return true;
          return types.includes('LANDSCAPE') || types.includes('SEASCAPE');
        });
        setColourLocationCount(colour.length);
      })
      .catch(() => {});
    getAvailableModels()
      .then((data) => {
        const versions = {};
        (data.available || []).forEach((m) => {
          if (m.name && m.version) versions[m.name] = m.version;
        });
        setModelVersions(versions);
      })
      .catch(() => {});
  }, []);

  // Clean up polling on unmount
  useEffect(() => {
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, []);

  const loadRuns = useCallback(async () => {
    try {
      setLoading(true);
      const response = await getPromptTestRuns();
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
      const response = await getPromptTestResults(runId);
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

  const formatGitBadge = (info) => {
    if (!info || !info.available) return '';
    const hash = info.commitAbbrev || info.commitHash?.slice(0, 7);
    if (!hash) return '';
    const dirtyMark = info.dirty ? '*' : '';
    const branchPrefix = info.branch && info.branch !== 'main' ? `${info.branch}@` : '';
    return `${branchPrefix}${hash}${dirtyMark}`;
  };

  const formatRunGitBadge = (run) => {
    if (!run.gitCommitHash) return '';
    const hash = run.gitCommitHash.slice(0, 7);
    const dirtyMark = run.gitDirty ? '*' : '';
    const branchPrefix = run.gitBranch && run.gitBranch !== 'main' ? `${run.gitBranch}@` : '';
    return `${branchPrefix}${hash}${dirtyMark}`;
  };

  const formatRelativeDate = (dateStr) => {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now - date;
    const diffMins = Math.floor(diffMs / 60000);
    if (diffMins < 1) return 'just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours}h ago`;
    const diffDays = Math.floor(diffHours / 24);
    return `${diffDays}d ago`;
  };

  const formatShortDate = (dateStr) => {
    if (!dateStr) return '\u2014';
    const date = new Date(dateStr + 'T00:00:00');
    return date.toLocaleDateString('en-GB', { month: 'short', day: 'numeric' });
  };

  // --- Polling logic ---

  const startPolling = useCallback((runId) => {
    if (pollingRef.current) clearInterval(pollingRef.current);
    pollingRef.current = setInterval(async () => {
      try {
        const response = await getPromptTestRun(runId);
        const updatedRun = response.data;
        setRuns((prev) => prev.map((r) => (r.id === runId ? updatedRun : r)));
        if (updatedRun.completedAt) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
          setRunning(false);
          setReplaying(false);
          // Auto-load results for the completed run
          loadResults(runId);
        }
      } catch {
        // Polling errors are non-fatal; keep trying
      }
    }, POLL_INTERVAL_MS);
  }, [loadResults]);

  // Resume polling for any in-progress run after initial load
  const resumedRef = useRef(false);
  useEffect(() => {
    if (resumedRef.current || loading || runs.length === 0) return;
    const inProgress = runs.find((r) => !r.completedAt);
    if (inProgress) {
      resumedRef.current = true;
      setRunning(true);
      setSelectedRunId(inProgress.id);
      startPolling(inProgress.id);
    }
  }, [loading, runs, startPolling]);

  const handleRunTest = () => {
    const runTypeInfo = RUN_TYPES.find((rt) => rt.value === selectedRunType) || RUN_TYPES[1];
    const totalSlots = colourLocationCount * runTypeInfo.days;
    const estimatedCostUsd = COST_PER_CALL[selectedModel] * totalSlots;
    const estimatedCostGbp = estimatedCostUsd * USD_TO_GBP;
    setConfirmDialog({
      title: 'Run Prompt Test',
      message: `This will evaluate ${colourLocationCount} colour location${colourLocationCount !== 1 ? 's' : ''} \u00D7 ${runTypeInfo.days} days (${runTypeInfo.desc}) using ${selectedModel}.`,
      costLine: `Estimated cost: ~\u00A3${estimatedCostGbp.toFixed(3)} (~$${estimatedCostUsd.toFixed(3)}) \u2014 ${totalSlots} evaluations`,
      confirmLabel: 'Run Test',
      onConfirm: async () => {
        setConfirmDialog(null);
        setRunning(true);
        setError(null);
        try {
          const response = await runPromptTest(selectedModel, selectedRunType);
          const newRun = response.data;
          setRuns((prev) => [newRun, ...prev]);
          setSelectedRunId(newRun.id);
          setResults([]);
          startPolling(newRun.id);
        } catch (err) {
          setRunning(false);
          setError(err?.response?.data?.message || err.message || 'Prompt test failed.');
        }
      },
    });
  };

  const handleReplayTest = (runId) => {
    const run = runs.find((r) => r.id === runId);
    if (!run) return;
    const hasAtmosphericData = results.some((r) => r.atmosphericDataJson);
    if (selectedRunId === runId && !hasAtmosphericData) {
      setError('Cannot replay — this run has no stored atmospheric data.');
      return;
    }

    const parentGit = formatRunGitBadge(run);
    const currentGit = formatGitBadge(gitInfo);
    const sameCode = parentGit && currentGit && parentGit === currentGit;

    setConfirmDialog({
      title: 'Replay Prompt Test',
      message: `Re-evaluates ${run.locationsCount || '?'} location${run.locationsCount !== 1 ? 's' : ''} using stored data from Run #${runId}. Model: ${run.evaluationModel}.`,
      gitComparison: {
        parentLabel: `Run #${runId}`,
        parentGit: parentGit || 'unknown',
        currentLabel: 'Current build',
        currentGit: currentGit || 'unknown',
        sameCode,
      },
      confirmLabel: 'Replay',
      onConfirm: async () => {
        setConfirmDialog(null);
        setReplaying(true);
        setError(null);
        try {
          const response = await replayPromptTest(runId);
          const newRun = response.data;
          setRuns((prev) => [newRun, ...prev]);
          setSelectedRunId(newRun.id);
          setResults([]);
          startPolling(newRun.id);
        } catch (err) {
          setReplaying(false);
          setError(err?.response?.data?.message || err.message || 'Replay failed.');
        }
      },
    });
  };

  // --- Comparison checkbox logic ---

  const handleCheckRun = (e, runId) => {
    e.stopPropagation();
    setCheckedRunIds((prev) => {
      if (prev.includes(runId)) {
        return prev.filter((id) => id !== runId);
      }
      const next = [...prev, runId];
      if (next.length > 2) next.shift(); // FIFO eviction
      return next;
    });
  };

  // Load results for checked runs
  useEffect(() => {
    if (checkedRunIds.length === 2) {
      const [idA, idB] = checkedRunIds;
      Promise.all([getPromptTestResults(idA), getPromptTestResults(idB)])
        .then(([resA, resB]) => {
          setComparisonResults({ [idA]: resA.data || [], [idB]: resB.data || [] });
        })
        .catch(() => setComparisonResults({}));
    } else {
      setComparisonResults({});
    }
  }, [checkedRunIds]);

  const isAnyRunning = running || replaying;

  // --- Cost and duration formatting ---

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

  const formatModelWithVersion = (modelName) => {
    const version = modelVersions[modelName];
    if (!version) return modelName;
    return (
      <>
        {modelName}
        <span className="text-plex-text-muted ml-0.5">{version}</span>
      </>
    );
  };

  // --- Comparison panel ---

  const renderComparisonPanel = () => {
    if (checkedRunIds.length !== 2 || Object.keys(comparisonResults).length !== 2) return null;
    const [idA, idB] = checkedRunIds;
    const runA = runs.find((r) => r.id === idA);
    const runB = runs.find((r) => r.id === idB);
    const resultsA = comparisonResults[idA] || [];
    const resultsB = comparisonResults[idB] || [];

    // Build lookup by locationId
    const mapA = {};
    for (const r of resultsA) { if (r.succeeded) mapA[r.locationId] = r; }
    const mapB = {};
    for (const r of resultsB) { if (r.succeeded) mapB[r.locationId] = r; }

    // All location IDs
    const allLocationIds = [...new Set([...Object.keys(mapA), ...Object.keys(mapB)])];

    // Build rows with deltas
    const rows = allLocationIds.map((locId) => {
      const a = mapA[locId];
      const b = mapB[locId];
      const name = a?.locationName || b?.locationName || `Location ${locId}`;
      const dRating = (a?.rating != null && b?.rating != null) ? a.rating - b.rating : null;
      const dFiery = (a?.fierySkyPotential != null && b?.fierySkyPotential != null)
        ? a.fierySkyPotential - b.fierySkyPotential : null;
      const dGolden = (a?.goldenHourPotential != null && b?.goldenHourPotential != null)
        ? a.goldenHourPotential - b.goldenHourPotential : null;
      const maxDelta = Math.max(
        Math.abs(dRating || 0),
        Math.abs(dFiery || 0),
        Math.abs(dGolden || 0)
      );
      return { locId, name, a, b, dRating, dFiery, dGolden, maxDelta };
    });

    // Sort by largest absolute delta first
    rows.sort((x, y) => y.maxDelta - x.maxDelta);

    // Averages
    const avgField = (results, field) => {
      const vals = results.filter((r) => r.succeeded && r[field] != null).map((r) => r[field]);
      return vals.length > 0 ? (vals.reduce((s, v) => s + v, 0) / vals.length) : null;
    };

    const avgRatingA = avgField(resultsA, 'rating');
    const avgRatingB = avgField(resultsB, 'rating');
    const avgFieryA = avgField(resultsA, 'fierySkyPotential');
    const avgFieryB = avgField(resultsB, 'fierySkyPotential');
    const avgGoldenA = avgField(resultsA, 'goldenHourPotential');
    const avgGoldenB = avgField(resultsB, 'goldenHourPotential');

    const formatDeltaColour = (delta) => {
      if (delta == null) return 'text-plex-text-muted';
      if (delta === 0) return 'text-plex-text-muted';
      const abs = Math.abs(delta);
      if (abs <= 5) return 'text-amber-400';
      return delta > 0 ? 'text-green-400' : 'text-red-400';
    };

    const formatDeltaStr = (delta) => {
      if (delta == null) return 'N/A';
      if (delta === 0) return '0';
      return `${delta > 0 ? '+' : ''}${typeof delta === 'number' && !Number.isInteger(delta) ? delta.toFixed(1) : delta}`;
    };

    return (
      <div className="card flex flex-col gap-3" data-testid="comparison-panel">
        <p className="text-sm font-semibold text-plex-text">
          Run #{idA}
          <span className="font-mono text-xs text-plex-text-muted ml-1">({formatRunGitBadge(runA)}, {runA?.evaluationModel})</span>
          {' vs '}
          Run #{idB}
          <span className="font-mono text-xs text-plex-text-muted ml-1">({formatRunGitBadge(runB)}, {runB?.evaluationModel})</span>
        </p>

        {/* Summary averages */}
        <div className="flex flex-wrap gap-4 text-xs text-plex-text-secondary">
          <span>
            Avg {'\u2605'} {avgRatingA?.toFixed(1) ?? 'N/A'} vs {avgRatingB?.toFixed(1) ?? 'N/A'}
            {avgRatingA != null && avgRatingB != null && (
              <span className={`ml-1 ${formatDeltaColour(avgRatingA - avgRatingB)}`}>
                ({formatDeltaStr(+(avgRatingA - avgRatingB).toFixed(1))})
              </span>
            )}
          </span>
          <span>
            Avg Fiery {avgFieryA?.toFixed(0) ?? 'N/A'} vs {avgFieryB?.toFixed(0) ?? 'N/A'}
            {avgFieryA != null && avgFieryB != null && (
              <span className={`ml-1 ${formatDeltaColour(avgFieryA - avgFieryB)}`}>
                ({formatDeltaStr(+(avgFieryA - avgFieryB).toFixed(0))})
              </span>
            )}
          </span>
          <span>
            Avg Golden {avgGoldenA?.toFixed(0) ?? 'N/A'} vs {avgGoldenB?.toFixed(0) ?? 'N/A'}
            {avgGoldenA != null && avgGoldenB != null && (
              <span className={`ml-1 ${formatDeltaColour(avgGoldenA - avgGoldenB)}`}>
                ({formatDeltaStr(+(avgGoldenA - avgGoldenB).toFixed(0))})
              </span>
            )}
          </span>
        </div>

        {/* Per-location comparison table */}
        <div className="overflow-x-auto">
          <table className="w-full text-sm" data-testid="comparison-table">
            <thead>
              <tr className="text-left text-plex-text-muted border-b border-plex-border">
                <th className="py-2 pr-4">Location</th>
                <th className="py-2 pr-2">{'\u2605'} A</th>
                <th className="py-2 pr-2">{'\u2605'} B</th>
                <th className="py-2 pr-4">{'\u0394'}</th>
                <th className="py-2 pr-2">Fiery A</th>
                <th className="py-2 pr-2">Fiery B</th>
                <th className="py-2 pr-4">{'\u0394'}</th>
                <th className="py-2 pr-2">Gold A</th>
                <th className="py-2 pr-2">Gold B</th>
                <th className="py-2 pr-4">{'\u0394'}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.locId} className="border-b border-plex-border/30">
                  <td className="py-1.5 pr-4 text-plex-text font-medium">{row.name}</td>
                  <td className="py-1.5 pr-2 text-plex-text">{row.a?.rating ?? 'N/A'}</td>
                  <td className="py-1.5 pr-2 text-plex-text">{row.b?.rating ?? 'N/A'}</td>
                  <td className={`py-1.5 pr-4 font-medium ${formatDeltaColour(row.dRating)}`}>
                    {formatDeltaStr(row.dRating)}
                  </td>
                  <td className="py-1.5 pr-2 text-plex-text">{row.a?.fierySkyPotential ?? 'N/A'}</td>
                  <td className="py-1.5 pr-2 text-plex-text">{row.b?.fierySkyPotential ?? 'N/A'}</td>
                  <td className={`py-1.5 pr-4 font-medium ${formatDeltaColour(row.dFiery)}`}>
                    {formatDeltaStr(row.dFiery)}
                  </td>
                  <td className="py-1.5 pr-2 text-plex-text">{row.a?.goldenHourPotential ?? 'N/A'}</td>
                  <td className="py-1.5 pr-2 text-plex-text">{row.b?.goldenHourPotential ?? 'N/A'}</td>
                  <td className={`py-1.5 pr-4 font-medium ${formatDeltaColour(row.dGolden)}`}>
                    {formatDeltaStr(row.dGolden)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    );
  };

  const selectedRun = runs.find((r) => r.id === selectedRunId);
  const isRunInProgress = (run) => run && !run.completedAt;

  return (
    <div className="space-y-6">
      {/* Current build info */}
      {gitInfo?.available && (
        <div className="flex items-center gap-4 text-xs bg-plex-surface-light rounded-lg px-4 py-2.5 border border-plex-border" data-testid="build-info">
          <span className="text-plex-text-muted font-semibold uppercase tracking-wide">Build</span>
          {gitInfo.branch && (
            <span className="text-plex-text-secondary">
              <span className="text-plex-text-muted">Branch:</span>{' '}
              <span className="font-mono">{gitInfo.branch}</span>
            </span>
          )}
          <span className="text-plex-text-secondary">
            <span className="text-plex-text-muted">Commit:</span>{' '}
            <span className="font-mono">
              {gitInfo.commitAbbrev || gitInfo.commitHash?.slice(0, 7) || '\u2014'}
              {gitInfo.dirty && <span className="text-amber-400">*</span>}
            </span>
          </span>
          {gitInfo.commitDate && (
            <span className="text-plex-text-muted">
              {formatRelativeDate(gitInfo.commitDate)}
            </span>
          )}
        </div>
      )}

      {/* Controls bar */}
      <div className="space-y-3">
        <div className="flex items-center gap-4 flex-wrap">
          {/* Model radio buttons */}
          <div className="flex items-center gap-3">
            {MODELS.map((model) => (
              <label key={model} className="flex items-center gap-1 cursor-pointer text-sm">
                <input
                  type="radio"
                  name="prompt-test-model"
                  value={model}
                  checked={selectedModel === model}
                  onChange={() => setSelectedModel(model)}
                  className="accent-plex-gold"
                  data-testid={`model-radio-${model}`}
                />
                <span className={`${
                  model === 'HAIKU' ? 'text-blue-300' :
                  model === 'SONNET' ? 'text-purple-300' :
                  'text-amber-300'
                }`}>
                  {model}
                  {modelVersions[model] && (
                    <span className="text-plex-text-muted ml-0.5 text-xs">{modelVersions[model]}</span>
                  )}
                </span>
              </label>
            ))}
          </div>

          {/* Run type radio buttons */}
          <span className="text-plex-text-muted text-xs">|</span>
          <div className="flex items-center gap-3">
            {RUN_TYPES.map((rt) => (
              <label key={rt.value} className="flex items-center gap-1 cursor-pointer text-sm">
                <input
                  type="radio"
                  name="prompt-test-run-type"
                  value={rt.value}
                  checked={selectedRunType === rt.value}
                  onChange={() => setSelectedRunType(rt.value)}
                  className="accent-plex-gold"
                  data-testid={`run-type-radio-${rt.value}`}
                />
                <span className="text-plex-text-secondary" title={rt.desc}>
                  {rt.label}
                </span>
              </label>
            ))}
          </div>

          <button
            className="btn-primary text-sm"
            onClick={handleRunTest}
            disabled={isAnyRunning}
            data-testid="run-prompt-test-btn"
          >
            {running ? '\u27F3 Running\u2026' : '\u27F3 Run Test'}
          </button>

          {/* Location count */}
          <span className="text-xs text-plex-text-muted">
            {colourLocationCount} colour location{colourLocationCount !== 1 ? 's' : ''}
          </span>
        </div>
      </div>

      {error && (
        <div className="bg-red-900/20 border border-red-700 rounded-lg p-4">
          <p className="text-red-400 text-sm" data-testid="prompt-test-error">{error}</p>
        </div>
      )}

      {/* Comparison panel — shown when exactly 2 runs checked */}
      {renderComparisonPanel()}

      {/* Recent runs */}
      <div>
        <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">
          Recent Test Runs
          {checkedRunIds.length > 0 && (
            <span className="ml-2 normal-case font-normal text-plex-text-secondary">
              ({checkedRunIds.length}/2 selected for comparison)
            </span>
          )}
        </p>
        {loading ? (
          <p className="text-sm text-plex-text-muted">Loading{'\u2026'}</p>
        ) : runs.length === 0 ? (
          <p className="text-sm text-plex-text-muted">No test runs yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm" data-testid="prompt-test-runs-table">
              <thead>
                <tr className="text-left text-plex-text-muted border-b border-plex-border">
                  <th className="py-2 pr-2 w-8"></th>
                  <th className="py-2 pr-4">ID</th>
                  <th className="py-2 pr-4">Model</th>
                  <th className="py-2 pr-4">Range</th>
                  <th className="py-2 pr-4">Git Commit</th>
                  <th className="py-2 pr-4">Date</th>
                  <th className="py-2 pr-4">Target</th>
                  <th className="py-2 pr-4">Loc</th>
                  <th className="py-2 pr-4">OK / Fail</th>
                  <th className="py-2 pr-4">Duration</th>
                  <th className="py-2 pr-4">Cost</th>
                  <th className="py-2 pr-4"></th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => {
                  const isReplay = run.parentRunId != null;
                  const inProgress = isRunInProgress(run);
                  const borderLeft = isReplay ? 'border-l-2 border-l-amber-500' : '';
                  return (
                    <tr
                      key={run.id}
                      className={`border-b border-plex-border/50 cursor-pointer hover:bg-plex-surface-light transition-colors ${borderLeft} ${
                        selectedRunId === run.id ? 'bg-plex-surface-light' : ''
                      }`}
                      onClick={() => handleSelectRun(run.id)}
                      data-testid={`prompt-test-run-${run.id}`}
                    >
                      <td className="py-2 pr-2" onClick={(e) => e.stopPropagation()}>
                        <input
                          type="checkbox"
                          checked={checkedRunIds.includes(run.id)}
                          onChange={(e) => handleCheckRun(e, run.id)}
                          className="accent-plex-gold"
                          data-testid={`compare-checkbox-${run.id}`}
                        />
                      </td>
                      <td className="py-2 pr-4 text-plex-text">
                        {run.id}
                        {isReplay && (
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
                        <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                          run.evaluationModel === 'HAIKU' ? 'bg-blue-900/30 text-blue-300' :
                          run.evaluationModel === 'SONNET' ? 'bg-purple-900/30 text-purple-300' :
                          'bg-amber-900/30 text-amber-300'
                        }`}>
                          {run.evaluationModel}
                          {modelVersions[run.evaluationModel] && (
                            <span className="opacity-60 ml-0.5">{modelVersions[run.evaluationModel]}</span>
                          )}
                        </span>
                      </td>
                      <td className="py-2 pr-4 text-xs text-plex-text-secondary">
                        {run.runType ? run.runType.replace(/_/g, ' ') : '\u2014'}
                      </td>
                      <td className="py-2 pr-4">
                        {run.gitCommitHash ? (
                          <span className="font-mono text-xs text-plex-text-secondary" title={`${run.gitBranch || ''}@${run.gitCommitHash}`}>
                            {formatRunGitBadge(run)}
                          </span>
                        ) : (
                          <span className="text-xs text-plex-text-muted">{'\u2014'}</span>
                        )}
                      </td>
                      <td className="py-2 pr-4 text-plex-text">
                        {run.startedAt ? run.startedAt.slice(0, 10) : run.targetDate}
                      </td>
                      <td className="py-2 pr-4 text-plex-text">{run.targetType}</td>
                      <td className="py-2 pr-4 text-plex-text">{run.locationsCount}</td>
                      <td className="py-2 pr-4">
                        {inProgress ? (
                          <span className="text-amber-400 text-xs" data-testid={`run-progress-${run.id}`}>
                            {'\u27F3'} {run.succeeded + run.failed}/{run.locationsCount || '?'}
                          </span>
                        ) : (
                          <>
                            <span className="text-green-400">{run.succeeded}</span>
                            {' / '}
                            <span className={run.failed > 0 ? 'text-red-400' : 'text-plex-text-muted'}>{run.failed}</span>
                          </>
                        )}
                      </td>
                      <td className="py-2 pr-4 text-plex-text-secondary">
                        {inProgress ? (
                          <span className="text-amber-400 text-xs">Running{'\u2026'}</span>
                        ) : formatDuration(run.durationMs)}
                      </td>
                      <td className="py-2 pr-4 text-plex-text-secondary">{formatRunCost(run)}</td>
                      <td className="py-2 pr-4">
                        <button
                          className="btn-secondary text-xs py-0.5 px-2"
                          onClick={(e) => { e.stopPropagation(); handleReplayTest(run.id); }}
                          disabled={isAnyRunning || inProgress}
                          data-testid={`replay-btn-${run.id}`}
                        >
                          {replaying ? '\u27F3\u2026' : '\u21BB Replay'}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Per-location results for selected run */}
      {selectedRunId && (
        <div>
          <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">
            Results for Run #{selectedRunId}
            {selectedRun && (
              <span className="ml-2 normal-case font-normal text-plex-text-secondary">
                {formatModelWithVersion(selectedRun.evaluationModel)} {'\u00B7'} {formatRunGitBadge(selectedRun) || 'no git info'}
              </span>
            )}
          </p>
          {isRunInProgress(selectedRun) ? (
            <p className="text-sm text-amber-400" data-testid="results-in-progress">
              {'\u27F3'} Run in progress{'\u2026'} {selectedRun.succeeded + selectedRun.failed}/{selectedRun.locationsCount || '?'} evaluated
            </p>
          ) : loadingResults ? (
            <p className="text-sm text-plex-text-muted">Loading results{'\u2026'}</p>
          ) : results.length === 0 ? (
            <p className="text-sm text-plex-text-muted">No results for this run.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid="prompt-test-results-table">
                <thead>
                  <tr className="text-left text-plex-text-muted border-b border-plex-border">
                    <th className="py-2 pr-4">Location</th>
                    <th className="py-2 pr-4">Date</th>
                    <th className="py-2 pr-4">Target</th>
                    <th className="py-2 pr-4">Rating</th>
                    <th className="py-2 pr-4">Fiery Sky</th>
                    <th className="py-2 pr-4">Golden Hour</th>
                    <th className="py-2 pr-4">Cost</th>
                    <th className="py-2 pr-4">Duration</th>
                    <th className="py-2 pr-4 max-w-xs">Summary</th>
                  </tr>
                </thead>
                <tbody>
                  {results.map((result) => (
                    <tr key={result.id} className="border-b border-plex-border/30">
                      <td className="py-2 pr-4 text-plex-text font-medium">{result.locationName}</td>
                      <td className="py-2 pr-4 text-plex-text-secondary text-xs">
                        {formatShortDate(result.targetDate)}
                      </td>
                      <td className="py-2 pr-4 text-plex-text-secondary text-xs">
                        {result.targetType === 'SUNRISE' ? (
                          <span className="text-orange-300">{'\u2600\uFE0F'} Rise</span>
                        ) : result.targetType === 'SUNSET' ? (
                          <span className="text-purple-300">{'\uD83C\uDF05'} Set</span>
                        ) : result.targetType || '\u2014'}
                      </td>
                      <td className="py-2 pr-4 text-plex-text">
                        {result.succeeded ? (
                          result.rating != null ? `${result.rating}/5` : '\u2014'
                        ) : (
                          <span className="text-red-400 text-xs">Failed</span>
                        )}
                      </td>
                      <td className="py-2 pr-4 text-plex-text">
                        {result.succeeded ? (result.fierySkyPotential ?? '\u2014') : '\u2014'}
                      </td>
                      <td className="py-2 pr-4 text-plex-text">
                        {result.succeeded ? (result.goldenHourPotential ?? '\u2014') : '\u2014'}
                      </td>
                      <td className="py-2 pr-4 text-plex-text-secondary text-xs">
                        {result.succeeded ? formatResultCost(result, selectedRun) : '\u2014'}
                      </td>
                      <td className="py-2 pr-4 text-plex-text-secondary">
                        {formatDuration(result.durationMs)}
                      </td>
                      <td className="py-2 pr-4 text-plex-text-secondary text-xs max-w-xs">
                        {result.succeeded ? (
                          result.summary ? (
                            <button
                              className="text-left truncate block max-w-xs hover:text-plex-text transition-colors cursor-pointer"
                              title="Click to view full summary"
                              onClick={(e) => { e.stopPropagation(); setExpandedSummary({ location: result.locationName, text: result.summary }); }}
                            >
                              {result.summary}
                            </button>
                          ) : '\u2014'
                        ) : (
                          <span className="text-red-400">{result.errorMessage || 'Error'}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Expanded summary modal */}
      {expandedSummary && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
          role="dialog"
          aria-modal="true"
          aria-label="Summary"
          data-testid="summary-dialog"
        >
          {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions, jsx-a11y/click-events-have-key-events -- backdrop dismiss */}
          <div className="absolute inset-0" onClick={() => setExpandedSummary(null)} />
          <div className="relative bg-plex-surface border border-plex-border rounded-xl shadow-2xl p-6 w-full max-w-lg flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold text-plex-text">
                {expandedSummary.location}
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
            {confirmDialog.costLine && (
              <p className="text-xs text-plex-text-muted">{confirmDialog.costLine}</p>
            )}
            {confirmDialog.gitComparison && (
              <div className="text-xs space-y-1 bg-plex-bg rounded-lg p-3 border border-plex-border">
                <div className="flex justify-between">
                  <span className="text-plex-text-muted">{confirmDialog.gitComparison.parentLabel}:</span>
                  <span className="font-mono text-plex-text-secondary">{confirmDialog.gitComparison.parentGit}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-plex-text-muted">{confirmDialog.gitComparison.currentLabel}:</span>
                  <span className="font-mono text-plex-text-secondary">{confirmDialog.gitComparison.currentGit}</span>
                </div>
                {confirmDialog.gitComparison.sameCode && (
                  <p className="text-amber-400 pt-1 border-t border-plex-border">
                    Same commit — prompts are identical. Consider committing changes first.
                  </p>
                )}
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
                className="btn-primary text-sm"
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

export default PromptTestView;
