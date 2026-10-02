import React from 'react';
import PropTypes from 'prop-types';
import {
  completedWithFailures, failedOutright, runCountsLine, stoppedEarly,
} from '../utils/runOutcome.js';

/** "Forecast run failed" with whatever the payload can say about why. */
const failureText = (run) => {
  if (run.reason) return `Forecast run failed — ${run.reason}`;
  if (run.failed > 0) {
    return `Forecast run failed — ${run.failed} ${run.failed === 1 ? 'place' : 'places'} failed.`;
  }
  return 'Forecast run failed.';
};

/**
 * The app-wide banner shown when any forecast run completes.
 *
 * <ul>
 *   <li>FAILED: a red failure line (the reason when there is one, else the failed count, else just
 *       "failed"), never the green "completed" line, which would read "0 locations updated" for a run
 *       that never started its work. No Refresh: nothing updated.</li>
 *   <li>Stopped early (PARTIAL with a run-level reason): an amber line that says so, keeps the
 *       completed count, shows the reason and KEEPS Refresh, because what did complete was written.</li>
 *   <li>Completed with failures (some places updated, some failed, no run-level reason): the same
 *       amber treatment, "completed with failures" and the failed count, and KEEPS Refresh. It is not
 *       green: a run with failed places is not a clean success.</li>
 *   <li>Everything else, a clean run, keeps the green "completed" wording.</li>
 * </ul>
 *
 * @param {object} props - Component props.
 * @param {object} props.run - The run-complete payload.
 * @param {function} props.onRefresh - Reloads the forecast and closes the banner.
 */
const RunCompleteBanner = ({ run, onRefresh }) => {
  if (failedOutright(run)) {
    return (
      <div className="bg-red-900/40 border-b border-red-700 py-3" data-testid="run-complete-banner">
        <p className="max-w-4xl mx-auto px-4 text-sm text-red-300 text-center" role="alert">
          {failureText(run)}
        </p>
      </div>
    );
  }
  if (stoppedEarly(run)) {
    return (
      <div className="bg-amber-900/40 border-b border-amber-700 py-3" data-testid="run-complete-banner">
        <p className="max-w-4xl mx-auto px-4 text-sm text-amber-300 text-center">
          Forecast run stopped early — {runCountsLine(run)}.
          {' '}{run.reason}
          {' '}
          <button
            className="underline font-medium hover:text-amber-100"
            onClick={onRefresh}
          >
            Refresh
          </button>
        </p>
      </div>
    );
  }
  if (completedWithFailures(run)) {
    return (
      <div className="bg-amber-900/40 border-b border-amber-700 py-3" data-testid="run-complete-banner">
        <p className="max-w-4xl mx-auto px-4 text-sm text-amber-300 text-center">
          Forecast run completed with failures — {runCountsLine(run)}.
          {' '}
          <button
            className="underline font-medium hover:text-amber-100"
            onClick={onRefresh}
          >
            Refresh
          </button>
        </p>
      </div>
    );
  }
  return (
    <div className="bg-green-900/40 border-b border-green-700 py-3" data-testid="run-complete-banner">
      <p className="max-w-4xl mx-auto px-4 text-sm text-green-300 text-center">
        Forecast run completed — {run.completed} location{run.completed !== 1 ? 's' : ''} updated.
        {' '}
        <button
          className="underline font-medium hover:text-green-100"
          onClick={onRefresh}
        >
          Refresh
        </button>
      </p>
    </div>
  );
};

RunCompleteBanner.propTypes = {
  run: PropTypes.shape({
    status: PropTypes.string,
    reason: PropTypes.string,
    completed: PropTypes.number,
    failed: PropTypes.number,
  }).isRequired,
  onRefresh: PropTypes.func.isRequired,
};

export default RunCompleteBanner;
