import { modelLabel } from '../utils/modelLabels.js';
import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, ReferenceArea, CartesianGrid, Legend,
} from 'recharts';
import { getAvailableModels } from '../api/modelsApi.js';
import {
  runSkyRatingEval, getSkyRatingEvalRuns, getSkyRatingEvalRun, getSkyRatingEvalTrend,
} from '../api/skyRatingEvalApi';
import { formatCostUsd } from '../utils/formatCost';
import { formatInstantUk } from '../utils/conversions.js';
import useConfirmDialog from '../hooks/useConfirmDialog.js';
import ConfirmDialog from './shared/ConfirmDialog.jsx';
import { apiErrorMessage } from '../utils/apiError.js';
import ErrorBanner from './shared/ErrorBanner.jsx';

const COST_PER_CALL = { HAIKU: 0.002, HAIKU_55: 0.0003, SONNET: 0.005, SONNET_55: 0.005, OPUS: 0.008 };
const DEFAULT_RUNS_PER_FIXTURE = 8;
const DEFAULT_FIXTURE_COUNT = 6;
const POLL_INTERVAL_MS = 4000;
const FIXTURE_COLOURS = ['#a78bfa', '#f87171', '#fbbf24', '#34d399', '#60a5fa', '#f472b6', '#fb923c', '#22d3ee'];
const MODEL_COLOURS = { HAIKU: '#60a5fa', HAIKU_55: '#38bdf8', SONNET: '#a78bfa', SONNET_55: '#c084fc', OPUS: '#fbbf24' };
const FALLBACK_MODEL_COLOURS = ['#2dd4bf', '#f472b6', '#a3e635', '#fb7185', '#818cf8', '#e879f9'];
const Y_AXIS_FLOOR_STEP = 10;
const Y_AXIS_FLOOR_MARGIN = 5;

/**
 * Tailwind classes for a model's family, decided by name prefix so a newly added model is styled
 * without an edit here: Haiku is blue, Sonnet purple, anything else (Opus, a future family) amber.
 *
 * @param {string} model - EvaluationModel enum name
 * @returns {{text: string, badge: string}} the radio-label text class and the table-badge classes
 */
export function modelClasses(model) {
  const name = model || '';
  if (name.startsWith('HAIKU')) return { text: 'text-blue-300', badge: 'bg-blue-900/30 text-blue-300' };
  if (name.startsWith('SONNET')) return { text: 'text-purple-300', badge: 'bg-purple-900/30 text-purple-300' };
  return { text: 'text-amber-300', badge: 'bg-amber-900/30 text-amber-300' };
}

/**
 * The line colour for a model: its known colour, or a fallback cycled by position so an unknown
 * (new) model still draws a visible line.
 *
 * @param {string} model - EvaluationModel enum name
 * @param {number} index - the model's position among the plotted models
 * @returns {string} a hex colour
 */
export function modelColour(model, index) {
  if (Object.prototype.hasOwnProperty.call(MODEL_COLOURS, model)) return MODEL_COLOURS[model];
  return FALLBACK_MODEL_COLOURS[index % FALLBACK_MODEL_COLOURS.length];
}

/**
 * Estimated cost of one scoring call. A model with no entry is priced at the highest known rate,
 * a conservative estimate, so the confirm dialog never shows NaN.
 *
 * @param {string} model - EvaluationModel enum name
 * @returns {number} dollars per call
 */
export function costPerCall(model) {
  if (Object.prototype.hasOwnProperty.call(COST_PER_CALL, model)) return COST_PER_CALL[model];
  return Math.max(...Object.values(COST_PER_CALL));
}

/**
 * The models to draw on the accuracy chart: every distinct model among the completed runs (so a
 * retired model's history stays drawn), ordered by the fetched model list, then any others
 * alphabetically.
 *
 * @param {Array<{status: string, model: string}>} runs - eval runs
 * @param {string[]} fetchedModels - model names from the models endpoint, in display order
 * @returns {string[]} model names in plotting order
 */
export function orderModelsPresent(runs, fetchedModels) {
  const present = [...new Set(runs.filter((r) => r.status === 'COMPLETED').map((r) => r.model))];
  const rank = (m) => {
    const i = fetchedModels.indexOf(m);
    return i === -1 ? Infinity : i;
  };
  return present.sort((a, b) => {
    const ra = rank(a);
    const rb = rank(b);
    if (ra !== rb) return ra === Infinity ? 1 : rb === Infinity ? -1 : ra - rb;
    return a.localeCompare(b);
  });
}

/**
 * The lower bound of the accuracy chart's Y axis: the lowest plotted value less a margin, floored
 * to a multiple of ten and clamped at zero, so lines clustered near 100% are not squashed.
 *
 * @param {number[]} values - the plotted percentages
 * @returns {number} the axis minimum
 */
export function accuracyDomainFloor(values) {
  if (!values.length) return 0;
  const floored = Math.floor((Math.min(...values) - Y_AXIS_FLOOR_MARGIN) / Y_AXIS_FLOOR_STEP) * Y_AXIS_FLOOR_STEP;
  return Math.max(0, floored);
}

/**
 * The x-axis tick for a trend point — the run's date on the UK calendar.
 *
 * <p>`runTimestamp` is a bare `LocalDateTime` produced in UTC. The `Z` was already being appended,
 * so the instant was right and only the rendering zone was wrong: an overnight batch at 04:00 BST
 * plotted under the previous day west of Greenwich, which makes a drift chart answer a different
 * question from the one its axis is labelled with.
 *
 * <p>At module scope, and exported, because it is the only way to assert it. Recharts needs a
 * laid-out container to emit tick text and jsdom has none, so the rendered axis is empty in tests —
 * the same reason `formatDetectedAt` and `formatReportedAt` are exported from their own components.
 *
 * @param {string|null} iso - the run timestamp
 * @returns {string} the tick label, or '' when there is no usable instant
 */
export function tsLabel(iso) {
  return formatInstantUk(iso, { month: 'short', day: 'numeric' }) ?? '';
}

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
  const [models, setModels] = useState([]);
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
      setError(apiErrorMessage(err, err?.message || 'Failed to load eval data'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    (async () => { await loadAll(); })();
  }, [loadAll]);

  // The platform model list comes from the backend (EvaluationModel.selectable()), so a new model
  // appears without a frontend edit. A failure is shown but never hides the runs table or charts.
  useEffect(() => {
    (async () => {
      try {
        const data = await getAvailableModels();
        setModels((data?.available || []).map((m) => m.name));
      } catch (err) {
        setError(apiErrorMessage(err, err?.message || 'Failed to load the model list'));
      }
    })();
  }, []);

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
    const usd = costPerCall(selectedModel) * calls;
    openDialog({
      title: 'Run Sky-Rating Eval',
      message: `Scores ${fixtureCount} fixtures × ${runsPerFixture} runs (${calls} calls) with ${modelLabel(selectedModel)}.`,
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
          setError(apiErrorMessage(err, err?.message || 'Eval run failed.'));
        }
      },
    });
  };

  // --- chart data ---


  // Per-fixture drift, scoped to the selected model so the small-multiples aren't 3× lines.
  const byFixture = useMemo(() => {
    const map = {};
    trend.filter((p) => p.model === selectedModel).forEach((p) => {
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
  }, [trend, selectedModel]);

  // Model accuracy over time: one pass-rate line per model, so you can see whether Haiku keeps up
  // with Sonnet/Opus on the ground-truth fixtures (and whether any of them drifts).
  const modelAccuracySeries = useMemo(() => {
    const byTs = {};
    runs.filter((r) => r.status === 'COMPLETED').forEach((r) => {
      const key = tsLabel(r.runTimestamp);
      (byTs[key] ||= { tsLabel: key });
      byTs[key][r.model] = Math.round(r.passRate * 100);
    });
    return Object.values(byTs).reverse();
  }, [runs]);

  const modelsPresent = useMemo(() => orderModelsPresent(runs, models), [runs, models]);

  const accuracyFloor = useMemo(
    () => accuracyDomainFloor(modelAccuracySeries.flatMap(
      (point) => Object.entries(point).filter(([k, v]) => k !== 'tsLabel' && Number.isFinite(v)).map(([, v]) => v),
    )),
    [modelAccuracySeries],
  );

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
          {models.map((m) => (
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
              <span className={modelClasses(m).text}>
                {modelLabel(m)}
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
          {/* Model accuracy over time — one pass-rate line per model */}
          {modelAccuracySeries.length > 0 && (
            <div className="card">
              <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-3">
                Model accuracy over time — % of fixture runs in band
              </p>
              <ResponsiveContainer width="100%" height={160}>
                <LineChart data={modelAccuracySeries} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#ffffff14" />
                  <XAxis dataKey="tsLabel" tick={{ fontSize: 10, fill: '#9ca3af' }} />
                  <YAxis domain={[accuracyFloor, 100]} allowDecimals={false} tick={{ fontSize: 10, fill: '#9ca3af' }} width={44} unit="%" />
                  <Tooltip
                    contentStyle={{ background: '#1f2430', border: '1px solid #374151', fontSize: 12 }}
                    formatter={(v, name) => [`${v}%`, name]}
                  />
                  <Legend wrapperStyle={{ fontSize: 11 }} />
                  {modelsPresent.map((m, i) => (
                    <Line
                      key={m}
                      type="monotone"
                      dataKey={m}
                      name={modelLabel(m)}
                      stroke={modelColour(m, i)}
                      strokeWidth={2}
                      dot={{ r: 3 }}
                      connectNulls
                    />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}

          {/* Per-fixture drift small-multiples */}
          {fixtureNames.length === 0 ? (
            <p className="text-sm text-plex-text-muted" data-testid="sky-eval-empty">
              No completed {modelLabel(selectedModel)} runs yet — run the eval (or pick another model) to start the trend.
            </p>
          ) : (
            <div>
              <div className="flex items-center justify-between mb-3">
                <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide">
                  Per-fixture drift ·{' '}
                  <span className="normal-case text-plex-text-secondary">{modelLabel(selectedModel)}</span>{' '}
                  <span className="normal-case text-plex-text-muted">(pick model above)</span>
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
                          <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${modelClasses(run.model).badge}`}>
                            {modelLabel(run.model)}
                          </span>
                        </td>
                        <td className="py-2 pr-4 text-plex-text-secondary text-xs">{run.triggerSource}</td>
                        <td className="py-2 pr-4 text-plex-text">
                          {run.status === 'COMPLETED' ? `${Math.round(run.passRate * 100)}%` : '—'}
                          {run.status === 'COMPLETED' && run.errorMessage && (
                            <span
                              className="text-amber-400 ml-1 cursor-help"
                              title={run.errorMessage}
                              data-testid={`sky-eval-partial-${run.id}`}
                            >
                              *
                            </span>
                          )}
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
