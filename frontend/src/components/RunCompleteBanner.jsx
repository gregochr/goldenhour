import React from 'react';
import PropTypes from 'prop-types';
import { failedOutright, failureMessage } from '../utils/runOutcome.js';

/**
 * The app-wide banner shown when any forecast run completes. A run that failed outright (the server
 * marked it FAILED, or gave a run-level reason) is a red failure line with the reason, never the
 * green "completed" line, which would read "0 locations updated" for a run that never started its
 * work. Every other run keeps the existing wording, including a mix with some failed places.
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
          Forecast run failed — {failureMessage(run)}
        </p>
      </div>
    );
  }
  return (
    <div className="bg-green-900/40 border-b border-green-700 py-3" data-testid="run-complete-banner">
      <p className="max-w-4xl mx-auto px-4 text-sm text-green-300 text-center">
        Forecast run completed — {run.completed} location{run.completed !== 1 ? 's' : ''} updated
        {run.failed > 0 && `, ${run.failed} failed`}.
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
