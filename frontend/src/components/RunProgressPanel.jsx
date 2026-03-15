import React, { useState, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';
import { subscribeToRunProgress, retryFailed } from '../api/runProgressApi';
import RunProgressRow from './RunProgressRow';

/**
 * Live progress panel for a forecast run. Subscribes to SSE and displays
 * per-location task states with a summary progress bar.
 *
 * @param {object} props - Component props.
 * @param {number} props.jobRunId - The job run ID to track.
 * @param {string} props.token - JWT access token for SSE auth.
 * @param {function} props.onComplete - Called when the run completes (to refresh job runs grid).
 */
const RunProgressPanel = ({ jobRunId, token, onComplete }) => {
  const [tasks, setTasks] = useState({});
  const [summary, setSummary] = useState(null);
  const [complete, setComplete] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [retryRunId, setRetryRunId] = useState(null);

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
    if (!jobRunId || !token) return;
    const cleanup = subscribeToRunProgress(
      jobRunId, token,
      handleTaskUpdate, handleRunSummary, handleRunComplete,
      () => {} // onError — silently handle
    );
    return cleanup;
  }, [jobRunId, token, handleTaskUpdate, handleRunSummary, handleRunComplete]);

  const handleRetry = async () => {
    setRetrying(true);
    try {
      const result = await retryFailed(jobRunId);
      setRetryRunId(result.jobRunId);
    } catch {
      // ignore retry errors
    } finally {
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
          className="btn-primary text-xs"
          onClick={handleRetry}
          disabled={retrying}
          data-testid="retry-failed-btn"
        >
          {retrying ? 'Retrying...' : `Retry ${failed} failed`}
        </button>
      )}

      {/* Retry run progress — recurse */}
      {retryRunId && (
        <RunProgressPanel
          jobRunId={retryRunId}
          token={token}
          onComplete={onComplete}
        />
      )}
    </div>
  );
};

RunProgressPanel.propTypes = {
  jobRunId: PropTypes.number.isRequired,
  token: PropTypes.string.isRequired,
  onComplete: PropTypes.func,
};

export default RunProgressPanel;
