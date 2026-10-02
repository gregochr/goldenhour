import React, { useState, useEffect, useCallback, useRef } from 'react';
import PropTypes from 'prop-types';
import { subscribeToRunProgress, retryFailed } from '../api/runProgressApi';
import RunProgressRow from './RunProgressRow';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * The backend answers 404 with an empty body both when the run is unknown (never started, evicted
 * after 30 minutes, or lost to a restart) and when none of its failed places can be run again (no
 * failures recorded, or none is a sky location any more). The two are indistinguishable, so the
 * sentence is worded to be true of each.
 */
const NOTHING_TO_RETRY = "Nothing to retry: this run's failed places can no longer be run again.";
const RETRY_FALLBACK = 'Could not start the retry.';

/**
 * Busy without `disabled`: a focused button that becomes `disabled` loses focus to `<body>` (seen
 * in Chromium here), so a keyboard reader who pressed Retry would be dropped off it and a refused
 * retry would leave them there. `aria-disabled` keeps focus; this copies `.btn-primary`'s own
 * `disabled:` look and the handler refuses a second press. Same treatment as UserSettingsModal.
 */
const BUSY_BUTTON = 'aria-disabled:opacity-40 aria-disabled:cursor-not-allowed aria-disabled:hover:bg-plex-gold';

/**
 * Live progress panel for a forecast run. Subscribes to SSE and displays
 * per-location task states with a summary progress bar.
 *
 * @param {object} props - Component props.
 * @param {number} props.jobRunId - The job run ID to track.
 * @param {function} props.onComplete - Called when the run completes (to refresh job runs grid).
 */
const RunProgressPanel = ({ jobRunId, onComplete }) => {
  const [tasks, setTasks] = useState({});
  const [summary, setSummary] = useState(null);
  const [complete, setComplete] = useState(false);
  const [retrying, setRetrying] = useState(false);
  // A ref as well as state: two presses in one tick both see the same render's `retrying`.
  const retryInFlight = useRef(false);
  const [retryRunId, setRetryRunId] = useState(null);
  // Tagged with the run it belongs to, so a line from one run is never shown for another (the
  // panel is reused when jobRunId changes) and a late failure cannot land on the wrong run.
  const [retryError, setRetryError] = useState(null);

  const handleTaskUpdate = useCallback((data) => {
    setTasks((prev) => ({ ...prev, [data.taskKey]: data }));
  }, []);

  const handleRunSummary = useCallback((data) => {
    setSummary(data);
  }, []);

  const handleRunComplete = useCallback((data) => {
    setSummary(data);
    setComplete(true);
    onComplete?.();
  }, [onComplete]);

  useEffect(() => {
    if (!jobRunId) return;
    const cleanup = subscribeToRunProgress(
      jobRunId,
      handleTaskUpdate, handleRunSummary, handleRunComplete,
      () => {} // onError — silently handle
    );
    return cleanup;
  }, [jobRunId, handleTaskUpdate, handleRunSummary, handleRunComplete]);

  const handleRetry = async () => {
    if (retryInFlight.current) return;
    retryInFlight.current = true;
    const forRun = jobRunId;
    setRetrying(true);
    setRetryError(null);
    try {
      const result = await retryFailed(forRun);
      setRetryRunId(result.jobRunId);
    } catch (err) {
      const message = err?.status === 404
        ? NOTHING_TO_RETRY
        : apiErrorMessage(err, RETRY_FALLBACK);
      setRetryError({ runId: forRun, message });
    } finally {
      retryInFlight.current = false;
      setRetrying(false);
    }
  };

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
    <div className="card space-y-3" data-testid="run-progress-panel">
      {/* Header */}
      <div className="flex items-center justify-between">
        <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide">
          Run Progress {complete ? '(Complete)' : phase ? `(${phase.replace(/_/g, ' ')})` : ''}
        </p>
        <p className="text-xs text-plex-text-muted">
          {complete ? `${durationSec || elapsedSec}s` : `${elapsedSec}s`}
          {' | '}
          {completed + failed + skipped + triaged}/{total}
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

      {/* Retry button for failed tasks */}
      {complete && failed > 0 && !retryRunId && (
        <button
          className={`btn-primary text-xs ${BUSY_BUTTON}`}
          onClick={handleRetry}
          aria-disabled={retrying || undefined}
          data-testid="retry-failed-btn"
        >
          {retrying ? 'Retrying...' : `Retry ${failed} failed`}
        </button>
      )}

      {/* Why the retry did not start. Below the button so it never moves it; role="alert" because
          it answers a press just made, as the form errors in LoginPage and OutcomeModal do. It is
          unmounted when the button is pressed again, so a repeat failure is announced afresh. */}
      {retryError && retryError.runId === jobRunId && (
        <p className="text-xs text-red-400" role="alert" data-testid="retry-failed-error">
          {retryError.message}
        </p>
      )}

      {/* Retry run progress — recurse */}
      {retryRunId && (
        <RunProgressPanel
          jobRunId={retryRunId}
          onComplete={onComplete}
        />
      )}
    </div>
  );
};

RunProgressPanel.propTypes = {
  jobRunId: PropTypes.number.isRequired,
  onComplete: PropTypes.func,
};

export default RunProgressPanel;
