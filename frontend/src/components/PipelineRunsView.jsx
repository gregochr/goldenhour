import React, { useState, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';
import { fetchPipelineRuns, fetchPipelineRunDetail } from '../api/pipelineRunApi.js';
import { formatTimestampUk } from '../utils/conversions';
import DispositionBreakdown from './DispositionBreakdown.jsx';

/** Poll interval while a RUNNING cycle is visible — fast enough to see waitingOn ticking. */
const RUNNING_POLL_INTERVAL_MS = 15_000;

/** Background refresh interval for the list view. */
const LIST_REFRESH_INTERVAL_MS = 60_000;

const PHASE_LABEL = {
  FORECAST_BATCH_SUBMIT: 'Submit batches',
  FORECAST_BATCH_WAIT: 'Wait for completion',
  BRIEFING: 'Briefing (gloss + best-bet)',
};

const STATUS_PILL_CLASSES = {
  RUNNING: 'bg-amber-900/30 text-amber-400',
  COMPLETED: 'bg-green-900/30 text-green-400',
  FAILED: 'bg-red-900/30 text-red-400',
};

const PHASE_STATUS_PILL_CLASSES = {
  RUNNING: 'bg-amber-900/30 text-amber-400',
  COMPLETED: 'bg-green-900/30 text-green-400',
  FAILED: 'bg-red-900/30 text-red-400',
};

const BATCH_STATUS_PILL_CLASSES = {
  SUBMITTED: 'bg-amber-900/30 text-amber-400',
  COMPLETED: 'bg-green-900/30 text-green-400',
  FAILED: 'bg-red-900/30 text-red-400',
  EXPIRED: 'bg-red-900/30 text-red-400',
  CANCELLED: 'bg-zinc-700 text-zinc-300',
};

/** "1m 23s" / "23s" / "1h 5m" — humanises a positive integer second count. */
function formatSeconds(s) {
  if (s == null) return '—';
  if (s < 60) return `${s}s`;
  if (s < 3600) {
    const m = Math.floor(s / 60);
    const rem = s % 60;
    return rem === 0 ? `${m}m` : `${m}m ${rem}s`;
  }
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

function StatusPill({ status, classes }) {
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded-full text-xs font-medium ${
        classes[status] || 'bg-zinc-700 text-zinc-300'
      }`}
      data-testid={`status-pill-${status}`}
    >
      {status}
    </span>
  );
}

StatusPill.propTypes = {
  status: PropTypes.string.isRequired,
  classes: PropTypes.object.isRequired,
};

/**
 * Pipeline Runs sub-view under Manage → Operations.
 *
 * <p>Shows the nightly pipeline orchestrator's recent cycles. When the user clicks
 * a row, the view replaces the list with a detail panel — a phase timeline plus
 * the cycle's forecast_batch rows, each of which links into the existing
 * disposition breakdown via its {@code jobRunId}. Live (RUNNING) cycles surface
 * {@code waitingOn} prominently so a stall is visible at a glance.
 *
 * @param {object} props
 * @param {number|null} props.activeRunId — currently expanded run id, or null for the list
 * @param {function}    props.onSelectRun — called with a run id when the user clicks a row
 * @param {function}    props.onCloseDetail — called when the user closes the detail panel
 */
export default function PipelineRunsView({ activeRunId, onSelectRun, onCloseDetail }) {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadRuns = useCallback(async () => {
    try {
      setError(null);
      const data = await fetchPipelineRuns();
      setRuns(data);
    } catch (e) {
      setError(e.message || 'Failed to load pipeline runs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRuns();
    const interval = setInterval(loadRuns, LIST_REFRESH_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [loadRuns]);

  if (activeRunId != null) {
    return <PipelineRunDetail runId={activeRunId} onClose={onCloseDetail} />;
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <p className="text-sm font-semibold text-plex-text">Pipeline Runs</p>
        <button
          type="button"
          onClick={loadRuns}
          className="text-xs text-plex-text-secondary hover:text-plex-text"
          data-testid="pipeline-runs-refresh"
        >
          Refresh
        </button>
      </div>
      <p className="text-xs text-plex-text-muted">
        The orchestrator runs <em>SUBMIT → WAIT → BRIEFING</em> on each nightly cycle.
        WAIT polls the DB until every batch tagged with the cycle id reaches a terminal
        status; BRIEFING runs only after WAIT completes. Click a row for the phase
        timeline and per-batch breakdown.
      </p>

      {loading && <p className="text-xs text-plex-text-muted">Loading…</p>}
      {error && (
        <p className="text-xs text-red-400" data-testid="pipeline-runs-error">
          {error}
        </p>
      )}

      {!loading && runs.length === 0 && (
        <p className="text-xs text-plex-text-muted" data-testid="pipeline-runs-empty">
          No pipeline runs yet. The next nightly cycle will produce one.
        </p>
      )}

      {runs.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm" data-testid="pipeline-runs-table">
            <thead>
              <tr className="text-left text-plex-text-secondary text-xs uppercase">
                <th className="py-2 pr-4">ID</th>
                <th className="py-2 pr-4">Started</th>
                <th className="py-2 pr-4">Status</th>
                <th className="py-2 pr-4">Current phase</th>
                <th className="py-2 pr-4">Waiting on / failure</th>
                <th className="py-2 pr-4">Duration</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((r) => {
                const detailText =
                  r.status === 'FAILED' && r.failureReason
                    ? r.failureReason
                    : r.waitingOn || '—';
                return (
                  <tr
                    key={r.id}
                    onClick={() => onSelectRun(r.id)}
                    className="border-t border-plex-border cursor-pointer hover:bg-plex-bg-elevated"
                    data-testid={`pipeline-run-row-${r.id}`}
                  >
                    <td className="py-2 pr-4 font-mono text-plex-text">#{r.id}</td>
                    <td className="py-2 pr-4 text-plex-text-secondary">
                      {formatTimestampUk(r.triggerTime)}
                    </td>
                    <td className="py-2 pr-4">
                      <StatusPill status={r.status} classes={STATUS_PILL_CLASSES} />
                    </td>
                    <td className="py-2 pr-4 text-plex-text-secondary">
                      {r.currentPhase ? PHASE_LABEL[r.currentPhase] || r.currentPhase : '—'}
                    </td>
                    <td className="py-2 pr-4 text-plex-text-secondary text-xs">
                      {detailText}
                    </td>
                    <td className="py-2 pr-4 text-plex-text-secondary">
                      {formatSeconds(r.durationSeconds)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

PipelineRunsView.propTypes = {
  activeRunId: PropTypes.number,
  onSelectRun: PropTypes.func.isRequired,
  onCloseDetail: PropTypes.func.isRequired,
};

PipelineRunsView.defaultProps = {
  activeRunId: null,
};

/**
 * Detail panel for one pipeline run: phase timeline + batch list with
 * embedded disposition breakdowns.
 */
function PipelineRunDetail({ runId, onClose }) {
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [expandedBatchJobRunIds, setExpandedBatchJobRunIds] = useState(() => new Set());

  const load = useCallback(async () => {
    try {
      setError(null);
      const data = await fetchPipelineRunDetail(runId);
      setDetail(data);
    } catch (e) {
      setError(e.message || 'Failed to load pipeline run detail');
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    load();
  }, [load]);

  // Faster poll while RUNNING — waitingOn updates every ~60s server-side.
  useEffect(() => {
    if (!detail || detail.run.status !== 'RUNNING') return undefined;
    const interval = setInterval(load, RUNNING_POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [detail, load]);

  if (loading && !detail) {
    return <p className="text-xs text-plex-text-muted">Loading pipeline run #{runId}…</p>;
  }
  if (error) {
    return (
      <div className="flex flex-col gap-2">
        <button
          type="button"
          onClick={onClose}
          className="text-xs text-plex-text-secondary hover:text-plex-text self-start"
        >
          ← Back to list
        </button>
        <p className="text-xs text-red-400">{error}</p>
      </div>
    );
  }

  const { run, phases, batches } = detail;

  const toggleBatch = (jobRunId) => {
    if (!jobRunId) return;
    setExpandedBatchJobRunIds((prev) => {
      const next = new Set(prev);
      if (next.has(jobRunId)) next.delete(jobRunId);
      else next.add(jobRunId);
      return next;
    });
  };

  return (
    <div className="flex flex-col gap-4" data-testid={`pipeline-run-detail-${runId}`}>
      <div className="flex items-center justify-between">
        <button
          type="button"
          onClick={onClose}
          className="text-xs text-plex-text-secondary hover:text-plex-text"
          data-testid="pipeline-run-detail-back"
        >
          ← Back to list
        </button>
        <button
          type="button"
          onClick={load}
          className="text-xs text-plex-text-secondary hover:text-plex-text"
          data-testid="pipeline-run-detail-refresh"
        >
          Refresh
        </button>
      </div>

      {/* Summary card */}
      <div className="card flex flex-col gap-2">
        <div className="flex items-center gap-2">
          <p className="text-sm font-semibold text-plex-text font-mono">
            Pipeline run #{run.id}
          </p>
          <StatusPill status={run.status} classes={STATUS_PILL_CLASSES} />
          <span className="text-xs text-plex-text-muted">{run.cycleType}</span>
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-1 text-xs">
          <div>
            <span className="text-plex-text-muted">Started: </span>
            <span className="text-plex-text-secondary">
              {formatTimestampUk(run.triggerTime)}
            </span>
          </div>
          <div>
            <span className="text-plex-text-muted">Completed: </span>
            <span className="text-plex-text-secondary">
              {run.completedAt ? formatTimestampUk(run.completedAt) : '—'}
            </span>
          </div>
          <div>
            <span className="text-plex-text-muted">Duration: </span>
            <span className="text-plex-text-secondary">
              {formatSeconds(run.durationSeconds)}
            </span>
          </div>
          <div>
            <span className="text-plex-text-muted">Current phase: </span>
            <span className="text-plex-text-secondary">
              {run.currentPhase ? PHASE_LABEL[run.currentPhase] || run.currentPhase : '—'}
            </span>
          </div>
        </div>
        {run.status === 'RUNNING' && run.waitingOn && (
          <p
            className="text-xs text-amber-400 italic"
            data-testid="pipeline-run-detail-waiting"
          >
            Waiting on: {run.waitingOn}
          </p>
        )}
        {run.status === 'FAILED' && run.failureReason && (
          <p
            className="text-xs text-red-400"
            data-testid="pipeline-run-detail-failure"
          >
            Failure: {run.failureReason}
          </p>
        )}
      </div>

      {/* Phase timeline */}
      <div className="card flex flex-col gap-2">
        <p className="text-sm font-semibold text-plex-text">Phase timeline</p>
        <table className="w-full text-sm" data-testid="pipeline-phases-table">
          <thead>
            <tr className="text-left text-plex-text-secondary text-xs uppercase">
              <th className="py-1 pr-3">#</th>
              <th className="py-1 pr-3">Phase</th>
              <th className="py-1 pr-3">Status</th>
              <th className="py-1 pr-3">Started</th>
              <th className="py-1 pr-3">Duration</th>
              <th className="py-1 pr-3">Detail</th>
            </tr>
          </thead>
          <tbody>
            {phases.map((p) => (
              <tr
                key={`${p.phase}-${p.sequenceOrder}`}
                className="border-t border-plex-border"
                data-testid={`pipeline-phase-row-${p.phase}`}
              >
                <td className="py-1 pr-3 text-plex-text-muted">{p.sequenceOrder}</td>
                <td className="py-1 pr-3 text-plex-text">
                  {PHASE_LABEL[p.phase] || p.phase}
                </td>
                <td className="py-1 pr-3">
                  <StatusPill status={p.status} classes={PHASE_STATUS_PILL_CLASSES} />
                </td>
                <td className="py-1 pr-3 text-plex-text-secondary">
                  {formatTimestampUk(p.startedAt)}
                </td>
                <td className="py-1 pr-3 text-plex-text-secondary">
                  {formatSeconds(p.durationSeconds)}
                </td>
                <td className="py-1 pr-3 text-plex-text-secondary text-xs">
                  {p.detail || '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Batch list with embedded disposition breakdowns */}
      <div className="card flex flex-col gap-2">
        <p className="text-sm font-semibold text-plex-text">Batches in this cycle</p>
        {batches.length === 0 ? (
          <p className="text-xs text-plex-text-muted" data-testid="pipeline-no-batches">
            No batches were submitted for this cycle. The orchestrator advanced straight
            to BRIEFING.
          </p>
        ) : (
          <table className="w-full text-sm" data-testid="pipeline-batches-table">
            <thead>
              <tr className="text-left text-plex-text-secondary text-xs uppercase">
                <th className="py-1 pr-3">Batch</th>
                <th className="py-1 pr-3">Status</th>
                <th className="py-1 pr-3">Requests</th>
                <th className="py-1 pr-3">OK / err</th>
                <th className="py-1 pr-3">Submitted</th>
                <th className="py-1 pr-3"></th>
              </tr>
            </thead>
            <tbody>
              {batches.map((b) => {
                const expanded = b.jobRunId && expandedBatchJobRunIds.has(b.jobRunId);
                return (
                  <React.Fragment key={b.id}>
                    <tr
                      className="border-t border-plex-border"
                      data-testid={`pipeline-batch-row-${b.id}`}
                    >
                      <td className="py-1 pr-3 font-mono text-xs text-plex-text-secondary">
                        {b.anthropicBatchId}
                      </td>
                      <td className="py-1 pr-3">
                        <StatusPill
                          status={b.status}
                          classes={BATCH_STATUS_PILL_CLASSES}
                        />
                      </td>
                      <td className="py-1 pr-3 text-plex-text-secondary">
                        {b.requestCount}
                      </td>
                      <td className="py-1 pr-3 text-plex-text-secondary">
                        {b.succeededCount != null ? b.succeededCount : '—'}
                        {' / '}
                        {b.erroredCount != null ? b.erroredCount : '—'}
                      </td>
                      <td className="py-1 pr-3 text-plex-text-secondary">
                        {formatTimestampUk(b.submittedAt)}
                      </td>
                      <td className="py-1 pr-3">
                        {b.jobRunId && (
                          <button
                            type="button"
                            onClick={() => toggleBatch(b.jobRunId)}
                            className="text-xs text-plex-text-secondary hover:text-plex-text"
                            data-testid={`pipeline-batch-toggle-${b.id}`}
                          >
                            {expanded ? 'Hide dispositions' : 'Show dispositions'}
                          </button>
                        )}
                      </td>
                    </tr>
                    {expanded && (
                      <tr
                        className="bg-plex-bg-elevated"
                        data-testid={`pipeline-batch-dispositions-${b.id}`}
                      >
                        <td colSpan="6" className="px-3 py-2">
                          <DispositionBreakdown jobRunId={b.jobRunId} />
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

PipelineRunDetail.propTypes = {
  runId: PropTypes.number.isRequired,
  onClose: PropTypes.func.isRequired,
};
