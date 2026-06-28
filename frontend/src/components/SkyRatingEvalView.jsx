import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, ReferenceArea, CartesianGrid,
} from 'recharts';
import {
  runSkyRatingEval, getSkyRatingEvalRuns, getSkyRatingEvalRun, getSkyRatingEvalTrend,
} from '../api/skyRatingEvalApi';
import { formatCostUsd } from '../utils/formatCost';
import useConfirmDialog from '../hooks/useConfirmDialog.js';
import ConfirmDialog from './shared/ConfirmDialog.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';

const MODELS = ['HAIKU', 'SONNET', 'OPUS'];
const COST_PER_CALL = { HAIKU: 0.002, SONNET: 0.005, OPUS: 0.008 };
const DEFAULT_RUNS_PER_FIXTURE = 8;
const DEFAULT_FIXTURE_COUNT = 6;
const POLL_INTERVAL_MS = 4000;
const FIXTURE_COLOURS = ['#a78bfa', '#f87171', '#fbbf24', '#34d399', '#60a5fa', '#f472b6', '#fb923c', '#22d3ee'];

/**
 * Sky-rating calibration eval — the persisted, graphable counterpart to the gated
 * SkyRatingEvalTest. Runs the frozen fixtures through the real scorer on demand (or weekly via the
 * scheduler) and charts calibration drift: each fixture's rating against its band over time, the
 * 0–100 sub-score drift, and the overall pass-rate trend.
 */
const SkyRatingEvalView = () => {
  const [runs, setRuns] = useState([]);
  const [trend, setTrend] = useState([]);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState(null);
  const [selectedModel, setSelectedModel] = useState('SONNET');
  const [runsPerFixture, setRunsPerFixture] = useState(DEFAULT_RUNS_PER_FIXTURE);
  const [metric, setMetric] = useState('rating');
  const { config: confirmDialog, openDialog, closeDialog } = useConfirmDialog();
  const pollingRef = useRef(null);

  const loadAll = useCallback(async () => {
    try {
      setLoading(true);
      const [runsRes, trendRes] = await Promise.all([getSkyRatingEvalRuns(), getSkyRatingEvalTrend()]);
      setRuns(runsRes.data || []);
      setTrend(trendRes.data || []);
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Failed to load eval data');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    (async () => { await loadAll(); })();
  }, [loadAll]);

  useEffect(() => () => { if (pollingRef.current) clearInterval(pollingRef.current); }, []);

  const startPolling = useCallback((runId) => {
    if (pollingRef.current) clearInterval(pollingRef.current);
    pollingRef.current = setInterval(async () => {
      try {
        const res = await getSkyRatingEvalRun(runId);
        const updated = res.data;
        setRuns((prev) => prev.map((r) => (r.id === runId ? updated : r)));
        if (updated.status !== 'RUNNING') {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
          setRunning(false);
          loadAll();
        }
      } catch {
        // polling errors are non-fatal
      }
    }, POLL_INTERVAL_MS);
  }, [loadAll]);

  // Resume polling for an in-progress run after load.
  const resumedRef = useRef(false);
  useEffect(() => {
    if (resumedRef.current || loading) return;
    const inProgress = runs.find((r) => r.status === 'RUNNING');
    if (inProgress) {
      resumedRef.current = true;
      (async () => { setRunning(true); startPolling(inProgress.id); })();
    }
  }, [loading, runs, startPolling]);

  const fixtureCount = runs.find((r) => r.fixtureCount)?.fixtureCount || DEFAULT_FIXTURE_COUNT;

  const handleRun = () => {
    const calls = fixtureCount * runsPerFixture;
    const usd = COST_PER_CALL[selectedModel] * calls;
    openDialog({
      title: 'Run Sky-Rating Eval',
      message: `Scores ${fixtureCount} fixtures × ${runsPerFixture} runs (${calls} calls) with ${selectedModel}.`,
      costLine: `Estimated cost: ~$${usd.toFixed(3)} · ~${Math.ceil(calls * 3 / 60)} min`,
      confirmLabel: 'Run Eval',
      onConfirm: async () => {
        closeDialog();
        setRunning(true);
        setError(null);
        try {
          const res = await runSkyRatingEval(selectedModel, runsPerFixture);
          const newRun = res.data;
          setRuns((prev) => [newRun, ...prev]);
          startPolling(newRun.id);
        } catch (err) {
          setRunning(false);
          setError(err?.response?.data?.message || err.message || 'Eval run failed.');
        }
      },
    });
  };

  // --- chart data ---

  const tsLabel = (iso) => {
    if (!iso) return '';
    const d = new Date(iso.endsWith('Z') ? iso : `${iso}Z`);
    return d.toLocaleDateString('en-GB', { month: 'short', day: 'numeric' });
  };

  const byFixture = useMemo(() => {
    const map = {};
    trend.forEach((p) => {
      (map[p.fixtureName] ||= []).push({
        tsLabel: tsLabel(p.runTimestamp),
        avgRating: p.avgRating,
        avgFierySky: p.avgFierySky,
        avgGoldenHour: p.avgGoldenHour,
        expectedMin: p.expectedMin,
        expectedMax: p.expectedMax,
        passes: p.passes,
        runs: p.runs,
      });
    });
    return map;
  }, [trend]);

  const passRateSeries = useMemo(() => runs
    .filter((r) => r.status === 'COMPLETED')
    .slice()
    .reverse()
    .map((r) => ({ tsLabel: tsLabel(r.runTimestamp), passRate: Math.round(r.passRate * 100), model: r.model })),
  [runs]);

  const fixtureNames = Object.keys(byFixture);

  const formatRelative = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso.endsWith('Z') ? iso : `${iso}Z`);
    const mins = Math.floor((new Date() - d) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    if (mins < 1440) return `${Math.floor(mins / 60)}h ago`;
    return `${Math.floor(mins / 1440)}d ago`;
  };

  const gitBadge = (run) => {
    if (!run.gitCommitHash) return '—';
    const hash = run.gitCommitHash.slice(0, 7);
    const branch = run.gitBranch && run.gitBranch !== 'main' ? `${run.gitBranch}@` : '';
    return `${branch}${hash}${run.gitDirty ? '*' : ''}`;
  };

  return (
    <div className="space-y-6" data-testid="sky-rating-eval-view">
      {/* Controls */}
      <div className="flex items-center gap-4 flex-wrap">
        <div className="flex items-center gap-3">
          {MODELS.map((m) => (
            <label key={m} className="flex items-center gap-1 cursor-pointer text-sm">
              <input
                type="radio"
                name="sky-eval-model"
                value={m}
                checked={selectedModel === m}
                onChange={() => setSelectedModel(m)}
                className="accent-plex-gold"
                data-testid={`sky-eval-model-${m}`}
              />
              <span className={m === 'HAIKU' ? 'text-blue-300' : m === 'SONNET' ? 'text-purple-300' : 'text-amber-300'}>
                {m}
              </span>
            </label>
          ))}
        </div>
        <label className="flex items-center gap-2 text-sm text-plex-text-secondary">
          Runs/fixture
          <input
            type="number"
            min="1"
            max="20"
            value={runsPerFixture}
            onChange={(e) => setRunsPerFixture(Math.max(1, Math.min(20, Number(e.target.value) || 1)))}
            className="w-16 bg-plex-surface-light border border-plex-border rounded px-2 py-1 text-plex-text"
            data-testid="sky-eval-runs-input"
          />
        </label>
        <button
          className="btn-primary text-sm"
          onClick={handleRun}
          disabled={running}
          data-testid="sky-eval-run-btn"
        >
          {running ? '⟳ Running…' : '⟳ Run Eval'}
        </button>
        <span className="text-xs text-plex-text-muted">
          {fixtureCount} fixtures · weekly when the scheduler job is resumed
        </span>
      </div>

      <ErrorBanner message={error} data-testid="sky-eval-error" />

      {loading ? (
        <p className="text-sm text-plex-text-muted">Loading{'…'}</p>
      ) : (
        <>
          {/* Overall pass-rate trend */}
          {passRateSeries.length > 0 && (
            <div className="card">
              <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-3">
                Overall pass rate over time
              </p>
              <ResponsiveContainer width="100%" height={140}>
                <LineChart data={passRateSeries} margin={{ top: 4, right: 8, bottom: 0, left: -16 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#ffffff14" />
                  <XAxis dataKey="tsLabel" tick={{ fontSize: 10, fill: '#9ca3af' }} />
                  <YAxis domain={[0, 100]} tick={{ fontSize: 10, fill: '#9ca3af' }} width={32} unit="%" />
                  <Tooltip
                    contentStyle={{ background: '#1f2430', border: '1px solid #374151', fontSize: 12 }}
                    formatter={(v) => [`${v}%`, 'pass rate']}
                  />
                  <Line type="monotone" dataKey="passRate" stroke="#34d399" strokeWidth={2} dot={{ r: 3 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}

          {/* Per-fixture drift small-multiples */}
          {fixtureNames.length === 0 ? (
            <p className="text-sm text-plex-text-muted" data-testid="sky-eval-empty">
              No completed runs yet — run the eval to start the trend.
            </p>
          ) : (
            <div>
              <div className="flex items-center justify-between mb-3">
                <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide">
                  Per-fixture drift
                </p>
                <div className="flex items-center gap-2 text-xs">
                  {['rating', 'subscores'].map((m) => (
                    <button
                      key={m}
                      onClick={() => setMetric(m)}
                      className={`px-2 py-0.5 rounded ${metric === m ? 'bg-plex-gold/20 text-plex-gold' : 'text-plex-text-muted hover:text-plex-text'}`}
                      data-testid={`sky-eval-metric-${m}`}
                    >
                      {m === 'rating' ? 'Rating (band)' : 'Sub-scores'}
                    </button>
                  ))}
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                {fixtureNames.map((name, idx) => {
                  const data = byFixture[name];
                  const band = data[0];
                  const colour = FIXTURE_COLOURS[idx % FIXTURE_COLOURS.length];
                  return (
                    <div key={name} className="card" data-testid={`sky-eval-chart-${name}`}>
                      <p className="text-xs font-medium text-plex-text mb-2 truncate" title={name}>{name}</p>
                      <ResponsiveContainer width="100%" height={150}>
                        {metric === 'rating' ? (
                          <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -24 }}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#ffffff14" />
                            <XAxis dataKey="tsLabel" tick={{ fontSize: 10, fill: '#9ca3af' }} />
                            <YAxis domain={[1, 5]} ticks={[1, 2, 3, 4, 5]} tick={{ fontSize: 10, fill: '#9ca3af' }} width={28} />
                            <ReferenceArea y1={band.expectedMin} y2={band.expectedMax} fill="#34d399" fillOpacity={0.13} />
                            <Tooltip
                              contentStyle={{ background: '#1f2430', border: '1px solid #374151', fontSize: 12 }}
                              formatter={(v) => [Number(v).toFixed(2), 'avg rating']}
                            />
                            <Line type="monotone" dataKey="avgRating" stroke={colour} strokeWidth={2} dot={{ r: 3 }} connectNulls />
                          </LineChart>
                        ) : (
                          <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -24 }}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#ffffff14" />
                            <XAxis dataKey="tsLabel" tick={{ fontSize: 10, fill: '#9ca3af' }} />
                            <YAxis domain={[0, 100]} tick={{ fontSize: 10, fill: '#9ca3af' }} width={28} />
                            <Tooltip contentStyle={{ background: '#1f2430', border: '1px solid #374151', fontSize: 12 }} />
                            <Line type="monotone" dataKey="avgFierySky" name="fiery" stroke="#fb923c" strokeWidth={2} dot={{ r: 2 }} connectNulls />
                            <Line type="monotone" dataKey="avgGoldenHour" name="golden" stroke="#fbbf24" strokeWidth={2} dot={{ r: 2 }} connectNulls />
                          </LineChart>
                        )}
                      </ResponsiveContainer>
                      <p className="text-[11px] text-plex-text-muted mt-1">
                        Band {'{'}{band.expectedMin}{band.expectedMin !== band.expectedMax ? `–${band.expectedMax}` : ''}{'}'}
                        {metric === 'subscores' && ' · fiery (orange) / golden (amber)'}
                      </p>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Runs table */}
          <div>
            <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">Recent runs</p>
            {runs.length === 0 ? (
              <p className="text-sm text-plex-text-muted">No runs yet.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm" data-testid="sky-eval-runs-table">
                  <thead>
                    <tr className="text-left text-plex-text-muted border-b border-plex-border">
                      <th className="py-2 pr-4">ID</th>
                      <th className="py-2 pr-4">When</th>
                      <th className="py-2 pr-4">Model</th>
                      <th className="py-2 pr-4">Trigger</th>
                      <th className="py-2 pr-4">Pass rate</th>
                      <th className="py-2 pr-4">Misses</th>
                      <th className="py-2 pr-4">Commit</th>
                      <th className="py-2 pr-4">Cost</th>
                      <th className="py-2 pr-4">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((run) => (
                      <tr key={run.id} className="border-b border-plex-border/40" data-testid={`sky-eval-run-${run.id}`}>
                        <td className="py-2 pr-4 text-plex-text">{run.id}</td>
                        <td className="py-2 pr-4 text-plex-text-secondary text-xs">{formatRelative(run.runTimestamp)}</td>
                        <td className="py-2 pr-4">
                          <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                            run.model === 'HAIKU' ? 'bg-blue-900/30 text-blue-300'
                              : run.model === 'SONNET' ? 'bg-purple-900/30 text-purple-300'
                                : 'bg-amber-900/30 text-amber-300'}`}
                          >
                            {run.model}
                          </span>
                        </td>
                        <td className="py-2 pr-4 text-plex-text-secondary text-xs">{run.triggerSource}</td>
                        <td className="py-2 pr-4 text-plex-text">
                          {run.status === 'COMPLETED' ? `${Math.round(run.passRate * 100)}%` : '—'}
                        </td>
                        <td className="py-2 pr-4 text-xs">
                          {run.status === 'COMPLETED' ? (
                            <>
                              <span className={run.belowMisses > 0 ? 'text-amber-400' : 'text-plex-text-muted'}>{run.belowMisses} DOWN</span>
                              {' / '}
                              <span className={run.aboveMisses > 0 ? 'text-red-400' : 'text-plex-text-muted'}>{run.aboveMisses} UP</span>
                            </>
                          ) : '—'}
                        </td>
                        <td className="py-2 pr-4 font-mono text-xs text-plex-text-secondary">{gitBadge(run)}</td>
                        <td className="py-2 pr-4 text-plex-text-secondary text-xs">{formatCostUsd(run.costMicroDollars)}</td>
                        <td className="py-2 pr-4">
                          {run.status === 'RUNNING' ? (
                            <span className="text-amber-400 text-xs" data-testid={`sky-eval-status-${run.id}`}>{'⟳'} Running{'…'}</span>
                          ) : run.status === 'FAILED' ? (
                            <span className="text-red-400 text-xs" title={run.errorMessage}>Failed</span>
                          ) : (
                            <span className="text-green-400 text-xs">Done</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {confirmDialog && (
        <ConfirmDialog
          title={confirmDialog.title}
          message={confirmDialog.message}
          confirmLabel={confirmDialog.confirmLabel}
          onConfirm={confirmDialog.onConfirm}
          onCancel={closeDialog}
        >
          {confirmDialog.costLine && (
            <p className="text-xs text-plex-text-muted">{confirmDialog.costLine}</p>
          )}
        </ConfirmDialog>
      )}
    </div>
  );
};

export default SkyRatingEvalView;
