import React, { useState, useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { subscribeToRunProgress } from '../api/runProgressApi';
import RunProgressRow from './RunProgressRow';
import {
  completedWithFailures, needsAttention, stoppedEarly,
} from '../utils/runOutcome.js';

/** The task states from which a row does not move. */
const FINISHED_STATES = new Set(['COMPLETE', 'FAILED', 'SKIPPED', 'TRIAGED']);
const RUN_EXPIRED ="This run's progress is no longer available.";

/** The word shown after the title of a finished run. */
const statusWord = (payload) => {
  if (payload?.status === 'FAILED') return '(Failed)';
  if (stoppedEarly(payload)) return '(Stopped early)';
  return completedWithFailures(payload) ? '(Completed with failures)' : '(Complete)';
};

/**
 * Live progress panel for a forecast run. Subscribes to SSE and displays
 * per-location task states with a summary progress bar.
 *
 * <p>Who decides whether the panel stays: the panel, from the completion payload ({@code needsAttention} in {@code utils/runOutcome.js}:
 * its {@code failed} count, or a FAILED/PARTIAL status or a {@code reason}, which a run that failed
 * before it had any task carries instead). A run with none of these removes itself through
 * {@code onAutoClear}; one that needs attention stays, with its failed places and the run's reason,
 * until the admin presses Dismiss or the parent replaces it. Both happen inside this component, so
 * the parent never unmounts it in the tick it completes.
 *
 * <p>The status word after a finished run's title follows the payload: "(Failed)" for FAILED,
 * "(Stopped early)" for a PARTIAL run that carries a reason ({@code stoppedEarly}), "(Completed with
 * failures)" for a run that finished with some places failed and was neither
 * ({@code completedWithFailures}), otherwise "(Complete)".
 *
 * <p>The server replays {@code run-complete} to a subscriber that arrives after the run finished, so
 * a panel remounted after a tab switch completes exactly as a live one does; and tells a subscriber
 * to a run it no longer holds {@code run-expired}, which shows one line and Dismiss.
 *
 * <p>The callbacks are held in refs so the subscription depends on {@code jobRunId} alone: a parent
 * that re-renders (and passes new inline functions) must not re-open the stream. The parent keys this
 * component by run id, so one instance only ever follows one run.
 *
 * @param {object} props - Component props.
 * @param {number} props.jobRunId - The job run ID to track.
 * @param {function} [props.onComplete] - Called with the completion payload when the run completes,
 *   to refresh the job runs grid.
 * @param {function} [props.onAutoClear] - Called when the run completes with no failures: remove me.
 * @param {function} [props.onDismiss] - Called by the Dismiss button.
 */
const RunProgressPanel = ({
  jobRunId, onComplete, onAutoClear, onDismiss,
}) => {
  const [tasks, setTasks] = useState({});
  const [summary, setSummary] = useState(null);
  const [complete, setComplete] = useState(false);
  const [expired, setExpired] = useState(false);
  const [reason, setReason] = useState(null);
  const completeRef = useRef(false);

  const callbacks = useRef({});
  // Refreshed after every render (never during one), before any event can call them.
  useEffect(() => {
    callbacks.current = { onComplete, onAutoClear };
  });

  useEffect(() => {
    if (!jobRunId) return undefined;
    return subscribeToRunProgress(
      jobRunId,
      (data) => {
        if (completeRef.current) return;
        setTasks((prev) => {
          // A row that has finished never goes back: an update arriving out of order (a replay's older
          // copy after the live one) would otherwise leave a finished place on "Pending" or a phase.
          const shown = prev[data.taskKey];
          if (shown && FINISHED_STATES.has(shown.state) && !FINISHED_STATES.has(data.state)) return prev;
          return { ...prev, [data.taskKey]: data };
        });
      },
      (data) => {
        // A replayed summary after completion would overwrite the completion payload's duration.
        if (!completeRef.current) setSummary(data);
      },
      (data) => {
        completeRef.current = true;
        setSummary(data);
        setReason(data?.reason || null);
        setComplete(true);
        callbacks.current.onComplete?.(data);
        // The completion payload's own say-so, not the task events': one value rather than a tally
        // this panel kept.
        if (!needsAttention(data)) callbacks.current.onAutoClear?.();
      },
      () => {}, // onError — silently handle
      () => setExpired(true),
    );
  }, [jobRunId]);

  const taskList = Object.values(tasks).sort((a, b) => {
    const locCmp = a.locationName.localeCompare(b.locationName);
    if (locCmp !== 0) return locCmp;
    const dateCmp = a.targetDate.localeCompare(b.targetDate);
    if (dateCmp !== 0) return dateCmp;
    return a.targetType.localeCompare(b.targetType);
  });

  const total = summary?.total || 0;
  const completed = summary?.completed || 0;
  const failed = summary?.failed || 0;
  const skipped = summary?.skipped || 0;
  const triaged = summary?.triaged || 0;
  const inProgress = summary?.inProgress || 0;
  const phase = summary?.phase || null;
  const pending = total - completed - failed - skipped - triaged - inProgress;

  const pctComplete = total > 0 ? (completed / total) * 100 : 0;
  const pctFailed = total > 0 ? (failed / total) * 100 : 0;
  const pctSkipped = total > 0 ? (skipped / total) * 100 : 0;
  const pctTriaged = total > 0 ? (triaged / total) * 100 : 0;
  const pctInProgress = total > 0 ? (inProgress / total) * 100 : 0;

  const elapsedSec = summary?.elapsedMs ? (summary.elapsedMs / 1000).toFixed(1) : '0.0';
  const durationSec = summary?.durationMs ? (summary.durationMs / 1000).toFixed(1) : null;

  // Group tasks by location
  const tasksByLocation = {};
  for (const task of taskList) {
    if (!tasksByLocation[task.locationName]) {
      tasksByLocation[task.locationName] = [];
    }
    tasksByLocation[task.locationName].push(task);
  }

  return (
    <div className="card space-y-3" data-testid="run-progress-panel" role="region" aria-label="Run progress">
      {/* Header */}
      <div className="flex items-center justify-between">
        <p
          className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide"
        >
          Run Progress{' '}
          {complete ? (
            // The only text that says a kept panel has finished, so it is not the muted colour.
            <span className="text-plex-text-secondary" data-testid="run-progress-status">
              {statusWord(summary)}
            </span>
          ) : phase ? `(${phase.replace(/_/g, ' ')})` : ''}
        </p>
        <p className="text-xs text-plex-text-muted">
          {complete ? `${durationSec || elapsedSec}s` : `${elapsedSec}s`}
          {/* A run that failed before it had any task has no count to show: "0/0" would read as a result. */}
          {!(complete && total === 0) && ` | ${completed + failed + skipped + triaged}/${total}`}
        </p>
      </div>

      {/* Progress bar */}
      <div className="w-full h-2 bg-gray-700 rounded-full overflow-hidden flex">
        {pctComplete > 0 && (
          <div className="bg-green-500 h-full" style={{ width: `${pctComplete}%` }} />
        )}
        {pctInProgress > 0 && (
          <div className="bg-blue-500 h-full" style={{ width: `${pctInProgress}%` }} />
        )}
        {pctFailed > 0 && (
          <div className="bg-red-500 h-full" style={{ width: `${pctFailed}%` }} />
        )}
        {pctSkipped > 0 && (
          <div className="bg-slate-500 h-full" style={{ width: `${pctSkipped}%` }} />
        )}
        {pctTriaged > 0 && (
          <div className="bg-gray-500 h-full" style={{ width: `${pctTriaged}%` }} />
        )}
      </div>

      {/* Summary counts */}
      <div className="flex flex-wrap gap-3 text-xs">
        {completed > 0 && <span className="text-green-400">{completed} complete</span>}
        {inProgress > 0 && <span className="text-blue-400">{inProgress} in progress</span>}
        {pending > 0 && <span className="text-gray-400">{pending} pending</span>}
        {triaged > 0 && <span className="text-gray-300">{triaged} triaged</span>}
        {failed > 0 && <span className="text-red-400">{failed} failed</span>}
        {skipped > 0 && <span className="text-slate-400">{skipped} skipped</span>}
      </div>

      {/* Why the run failed as a whole, when the server says so. `reason` is set only by the completion
          payload, in the same handler that sets `complete`, so it needs no separate guard. role="alert"
          because it answers a run the admin started and is not otherwise announced. */}
      {reason && (
        <p className="text-xs text-red-400" role="alert" data-testid="run-progress-reason">
          {reason}
        </p>
      )}

      {/* Task list */}
      <div className="max-h-64 overflow-y-auto divide-y divide-plex-border/30">
        {Object.entries(tasksByLocation).map(([locName, locTasks]) => (
          <div key={locName}>
            {locTasks.map((task) => (
              <RunProgressRow key={task.taskKey} task={task} />
            ))}
          </div>
        ))}
      </div>

      {expired && (
        <p className="text-xs text-plex-text-secondary" data-testid="run-progress-expired">
          {RUN_EXPIRED}
        </p>
      )}

      {(complete || expired) && onDismiss && (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className="btn-secondary text-xs"
            onClick={onDismiss}
            aria-label="Dismiss run progress"
            data-testid="run-progress-dismiss"
          >
            Dismiss
          </button>
        </div>
      )}
    </div>
  );
};

RunProgressPanel.propTypes = {
  jobRunId: PropTypes.number.isRequired,
  onComplete: PropTypes.func,
  onAutoClear: PropTypes.func,
  onDismiss: PropTypes.func,
};

export default RunProgressPanel;
