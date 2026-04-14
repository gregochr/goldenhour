import React, { useState, useCallback, useEffect } from 'react';
import {
  runBriefingModelTest,
  getBriefingModelTestRuns,
  getBriefingModelTestResults,
} from '../api/briefingModelTestApi.js';
import { formatDuration } from '../utils/conversions.js';
import { formatCostGbp, formatCostUsd, formatTokens } from '../utils/formatCost.js';
import useConfirmDialog from '../hooks/useConfirmDialog.js';
import ConfirmDialog from './shared/ConfirmDialog.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';

const MODELS = ['HAIKU', 'SONNET', 'SONNET_ET', 'OPUS', 'OPUS_ET'];

const MODEL_LABEL = {
  HAIKU: 'Haiku',
  SONNET: 'Sonnet',
  SONNET_ET: 'Sonnet (ET)',
  OPUS: 'Opus',
  OPUS_ET: 'Opus (ET)',
};

/**
 * Briefing model comparison test view — runs five Claude variants (Haiku, Sonnet,
 * Sonnet+ET, Opus, Opus+ET) with the same briefing rollup and shows side-by-side
 * pick comparison with agreement highlighting.
 */
export default function BriefingModelTestView() {
  const [runs, setRuns] = useState([]);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [loadingResults, setLoadingResults] = useState(false);
  const [error, setError] = useState(null);
  const [showRollup, setShowRollup] = useState(false);
  const [expandedThinking, setExpandedThinking] = useState({});
  const { openDialog, closeDialog, config: dialogConfig } = useConfirmDialog();

  const loadRuns = useCallback(async () => {
    try {
      setLoading(true);
      const response = await getBriefingModelTestRuns();
      setRuns(response.data || []);
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Failed to load test runs');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadResults = useCallback(async (runId) => {
    try {
      setLoadingResults(true);
      const response = await getBriefingModelTestResults(runId);
      setResults(response.data || []);
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Failed to load results');
    } finally {
      setLoadingResults(false);
    }
  }, []);

  useEffect(() => {
    loadRuns();
  }, [loadRuns]);

  const handleRunTest = () => {
    openDialog({
      title: 'Run Briefing Model Comparison',
      message: 'This will call 5 Claude variants (Haiku, Sonnet, Sonnet+ET, Opus, Opus+ET) sequentially with the current briefing data. Extended thinking variants take longer and cost more.',
      confirmLabel: 'Run Comparison',
      onConfirm: async () => {
        closeDialog();
        setRunning(true);
        setError(null);
        try {
          const response = await runBriefingModelTest();
          setRuns((prev) => [response.data, ...prev]);
          setSelectedRunId(response.data.id);
          await loadResults(response.data.id);
        } catch (err) {
          setError(err?.response?.data?.message || err.message || 'Failed to run comparison');
        } finally {
          setRunning(false);
        }
      },
    });
  };

  const handleSelectRun = async (runId) => {
    if (selectedRunId === runId) {
      setSelectedRunId(null);
      setResults([]);
      setShowRollup(false);
      setExpandedThinking({});
      return;
    }
    setSelectedRunId(runId);
    setShowRollup(false);
    setExpandedThinking({});
    await loadResults(runId);
  };

  const toggleThinking = (model) => {
    setExpandedThinking((prev) => ({ ...prev, [model]: !prev[model] }));
  };

  const selectedRun = runs.find((r) => r.id === selectedRunId);

  const formatRunDate = (dateStr) => {
    if (!dateStr) return '\u2014';
    const d = new Date(dateStr + 'Z');
    return d.toLocaleString('en-GB', {
      day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
      timeZone: 'Europe/London',
    });
  };

  const formatBriefingAge = (briefingAt, runAt) => {
    if (!briefingAt || !runAt) return '\u2014';
    const diffMs = new Date(runAt + 'Z') - new Date(briefingAt + 'Z');
    const diffMin = Math.round(diffMs / 60000);
    if (diffMin < 60) return `${diffMin}m`;
    return `${Math.floor(diffMin / 60)}h ${diffMin % 60}m`;
  };

  const formatRunCost = (run) => {
    const gbp = formatCostGbp(run.totalCostMicroDollars, run.exchangeRateGbpPerUsd, null);
    const usd = formatCostUsd(run.totalCostMicroDollars);
    return `${gbp} (${usd})`;
  };

  const formatResultCost = (result) => {
    if (!selectedRun) return '\u2014';
    const gbp = formatCostGbp(result.costMicroDollars, selectedRun.exchangeRateGbpPerUsd, null);
    const usd = formatCostUsd(result.costMicroDollars);
    return `${gbp} (${usd})`;
  };

  // Parse picks from JSON for each model
  const picksByModel = {};
  for (const r of results) {
    try {
      picksByModel[r.evaluationModel] = r.picksJson ? JSON.parse(r.picksJson) : [];
    } catch {
      picksByModel[r.evaluationModel] = [];
    }
  }

  // Agreement highlighting: unique pick keys across all models that have a result at this rank
  const getAgreementColor = (rank) => {
    const picksAtRank = MODELS.map((m) => {
      const picks = picksByModel[m] || [];
      return picks.find((p) => p.rank === rank);
    }).filter(Boolean);

    if (picksAtRank.length < 2) return '';

    const keys = picksAtRank.map((p) => `${p.event || ''}_${p.region || ''}`);
    const unique = new Set(keys);

    if (unique.size === 1) return 'border-l-4 border-l-green-500';
    if (unique.size === 2) return 'border-l-4 border-l-amber-500';
    return 'border-l-4 border-l-red-500';
  };

  const modelBadgeColor = (model) => {
    switch (model) {
    case 'HAIKU': return 'bg-cyan-900/40 text-cyan-300';
    case 'SONNET': return 'bg-violet-900/40 text-violet-300';
    case 'SONNET_ET': return 'bg-violet-900/60 text-violet-200';
    case 'OPUS': return 'bg-amber-900/40 text-amber-300';
    case 'OPUS_ET': return 'bg-amber-900/60 text-amber-200';
    default: return 'bg-plex-card-bg text-plex-text-secondary';
    }
  };

  return (
    <div className="flex flex-col gap-4" data-testid="briefing-model-test-view">
      {/* Run button */}
      <div className="flex items-center gap-3">
        <button
          className="btn-primary text-sm"
          onClick={handleRunTest}
          disabled={running}
          data-testid="run-briefing-model-test-btn"
        >
          {running ? 'Running\u2026' : 'Run Comparison'}
        </button>
        {running && (
          <span className="text-xs text-plex-text-muted animate-pulse">
            Calling 5 variants sequentially\u2026
          </span>
        )}
      </div>

      {dialogConfig && (
        <ConfirmDialog
          title={dialogConfig.title}
          message={dialogConfig.message}
          confirmLabel={dialogConfig.confirmLabel}
          onConfirm={dialogConfig.onConfirm}
          onCancel={closeDialog}
        />
      )}

      <ErrorBanner message={error} data-testid="briefing-model-test-error" />

      {/* Runs table */}
      {loading ? (
        <p className="text-sm text-plex-text-muted">Loading runs\u2026</p>
      ) : runs.length > 0 ? (
        <div className="overflow-x-auto" data-testid="briefing-model-test-runs-table">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-plex-text-muted border-b border-plex-border">
                <th className="py-1.5 pr-3">ID</th>
                <th className="py-1.5 pr-3">Date</th>
                <th className="py-1.5 pr-3">Briefing Age</th>
                <th className="py-1.5 pr-3">OK / Fail</th>
                <th className="py-1.5 pr-3">Duration</th>
                <th className="py-1.5 pr-3">Cost</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr
                  key={run.id}
                  onClick={() => handleSelectRun(run.id)}
                  className={`cursor-pointer border-b border-plex-border hover:bg-plex-card-bg/50 transition-colors ${
                    selectedRunId === run.id ? 'bg-plex-card-bg' : ''
                  }`}
                  data-testid={`briefing-run-row-${run.id}`}
                >
                  <td className="py-1.5 pr-3 text-plex-text-secondary">#{run.id}</td>
                  <td className="py-1.5 pr-3">{formatRunDate(run.startedAt)}</td>
                  <td className="py-1.5 pr-3 text-plex-text-secondary">
                    {formatBriefingAge(run.briefingGeneratedAt, run.startedAt)}
                  </td>
                  <td className="py-1.5 pr-3">
                    <span className="text-green-400">{run.succeeded}</span>
                    {' / '}
                    <span className={run.failed > 0 ? 'text-red-400' : 'text-plex-text-muted'}>
                      {run.failed}
                    </span>
                  </td>
                  <td className="py-1.5 pr-3 text-plex-text-secondary">
                    {formatDuration(run.durationMs)}
                  </td>
                  <td className="py-1.5 pr-3 text-plex-text-secondary">{formatRunCost(run)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="text-sm text-plex-text-muted" data-testid="briefing-model-test-empty">
          No comparison runs yet.
        </p>
      )}

      {/* Results comparison */}
      {selectedRunId && (
        <div className="flex flex-col gap-4" data-testid="briefing-model-test-results">
          {loadingResults ? (
            <p className="text-sm text-plex-text-muted">Loading results\u2026</p>
          ) : results.length > 0 ? (
            <>
              {/* Metrics row — flex-wrap handles 5 cards gracefully */}
              <div className="flex flex-wrap gap-3">
                {MODELS.map((model) => {
                  const r = results.find((res) => res.evaluationModel === model);
                  if (!r) return (
                    <div key={model} className="rounded-lg bg-plex-card-bg p-3 min-w-[160px] flex-1">
                      <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${modelBadgeColor(model)}`}>
                        {MODEL_LABEL[model] || model}
                      </span>
                      <p className="text-sm text-plex-text-muted mt-2">No result</p>
                    </div>
                  );
                  return (
                    <div key={model} className="rounded-lg bg-plex-card-bg p-3 min-w-[160px] flex-1" data-testid={`metric-${model}`}>
                      <div className="flex items-center gap-2 mb-2">
                        <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${modelBadgeColor(model)}`}>
                          {MODEL_LABEL[model] || model}
                        </span>
                        {r.succeeded ? (
                          <span className="text-green-400 text-xs">OK</span>
                        ) : (
                          <span className="text-red-400 text-xs">FAILED</span>
                        )}
                      </div>
                      <div className="text-xs text-plex-text-secondary space-y-0.5">
                        <p>Duration: {formatDuration(r.durationMs)}</p>
                        <p>In: {formatTokens(r.inputTokens)} / Out: {formatTokens(r.outputTokens)}</p>
                        <p>Cost: {formatResultCost(r)}</p>
                        <p>Picks: {r.picksReturned} returned, {r.picksValid} valid
                          {r.picksReturned > 0 && (
                            <span className="text-plex-text-muted">
                              {' '}({Math.round((r.picksValid / r.picksReturned) * 100)}%)
                            </span>
                          )}
                        </p>
                      </div>

                      {/* Thinking text — ET variants only */}
                      {r.thinkingText && (
                        <div className="mt-2 border-t border-plex-border pt-2">
                          <button
                            className="text-xs text-violet-400 hover:text-violet-300 transition-colors"
                            onClick={() => toggleThinking(model)}
                            data-testid={`toggle-thinking-${model}`}
                          >
                            {expandedThinking[model] ? '\u25BC' : '\u25B6'} Extended Thinking
                          </button>
                          {expandedThinking[model] && (
                            <pre
                              className="mt-1 p-2 rounded bg-plex-bg text-xs text-plex-text-muted overflow-y-auto max-h-64 whitespace-pre-wrap break-words"
                              data-testid={`thinking-text-${model}`}
                            >
                              {r.thinkingText}
                            </pre>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              {/* Picks comparison */}
              <div className="flex flex-col gap-2">
                <p className="text-sm font-semibold text-plex-text">Pick Comparison</p>
                {[1, 2].map((rank) => {
                  const picksAtRank = MODELS.map((m) => ({
                    model: m,
                    pick: (picksByModel[m] || []).find((p) => p.rank === rank),
                  }));
                  const hasAny = picksAtRank.some((p) => p.pick);
                  if (!hasAny) return null;
                  return (
                    <div
                      key={rank}
                      className={`rounded-lg bg-plex-card-bg p-3 ${getAgreementColor(rank)}`}
                      data-testid={`pick-rank-${rank}`}
                    >
                      <p className="text-xs font-medium text-plex-text-muted mb-2">
                        Pick #{rank}
                      </p>
                      <div className="space-y-2">
                        {picksAtRank.map(({ model, pick }) => (
                          <div key={model} className="flex gap-2 items-start text-xs">
                            <span className={`inline-block px-1.5 py-0.5 rounded font-medium shrink-0 ${modelBadgeColor(model)}`}>
                              {MODEL_LABEL[model] || model}
                            </span>
                            {pick ? (
                              <div className="min-w-0">
                                <p className="text-plex-text">
                                  {pick.region && <span className="font-medium">{pick.region}</span>}
                                  {pick.event && (
                                    <span className="text-plex-text-secondary ml-1">
                                      {pick.event}
                                    </span>
                                  )}
                                  {pick.confidence && (
                                    <span className={`ml-1 ${
                                      pick.confidence === 'high' ? 'text-green-400'
                                        : pick.confidence === 'medium' ? 'text-amber-400' : 'text-red-400'
                                    }`}>
                                      [{pick.confidence}]
                                    </span>
                                  )}
                                </p>
                                {pick.headline && (
                                  <p className="text-plex-text-secondary">{pick.headline}</p>
                                )}
                                {pick.detail && (
                                  <p className="text-plex-text-muted">{pick.detail}</p>
                                )}
                              </div>
                            ) : (
                              <span className="text-plex-text-muted">No pick at this rank</span>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  );
                })}
              </div>

              {/* Expandable rollup JSON */}
              {selectedRun?.rollupJson && (
                <div>
                  <button
                    className="text-xs text-plex-text-secondary hover:text-plex-text transition-colors"
                    onClick={() => setShowRollup(!showRollup)}
                    data-testid="toggle-rollup-btn"
                  >
                    {showRollup ? '\u25BC Hide' : '\u25B6 Show'} Rollup JSON
                  </button>
                  {showRollup && (
                    <pre
                      className="mt-2 p-3 rounded-lg bg-plex-bg text-xs text-plex-text-secondary overflow-x-auto max-h-80 overflow-y-auto"
                      data-testid="rollup-json"
                    >
                      {JSON.stringify(JSON.parse(selectedRun.rollupJson), null, 2)}
                    </pre>
                  )}
                </div>
              )}
            </>
          ) : (
            <p className="text-sm text-plex-text-muted">No results for this run.</p>
          )}
        </div>
      )}
    </div>
  );
}
