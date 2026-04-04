import React from 'react';
import PropTypes from 'prop-types';

const STATE_BADGES = {
  PENDING: { label: 'Pending', color: 'bg-gray-600 text-gray-200' },
  FETCHING_WEATHER: { label: 'Weather', color: 'bg-blue-700 text-blue-100' },
  FETCHING_CLOUD: { label: 'Cloud', color: 'bg-blue-600 text-blue-100' },
  FETCHING_TIDES: { label: 'Tides', color: 'bg-blue-500 text-blue-100' },
  EVALUATING: { label: 'Evaluating', color: 'bg-amber-700 text-amber-100' },
  COMPLETE: { label: 'Complete', color: 'bg-green-700 text-green-100' },
  FAILED: { label: 'Failed', color: 'bg-red-700 text-red-100' },
  SKIPPED: { label: 'Skipped', color: 'bg-slate-600 text-slate-200' },
  TRIAGED: { label: 'Triaged', color: 'bg-gray-500 text-gray-100' },
};

/**
 * Individual task row within the RunProgressPanel.
 * Shows location name, date, target type, state badge, and optional error tooltip.
 *
 * @param {object} props - Component props.
 * @param {object} props.task - Task snapshot from SSE.
 * @param {string} props.task.locationName - Location name.
 * @param {string} props.task.targetDate - Target date (ISO).
 * @param {string} props.task.targetType - SUNRISE, SUNSET, or HOURLY.
 * @param {string} props.task.state - Current FSM state.
 * @param {string|null} props.task.errorMessage - Error message if failed.
 */
const RunProgressRow = ({ task }) => {
  const badge = STATE_BADGES[task.state] || STATE_BADGES.PENDING;

  return (
    <div className="py-1 px-2 text-xs" data-testid="run-progress-row">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-plex-text truncate font-medium">{task.locationName}</span>
          <span className="text-plex-text-muted">{task.targetDate}</span>
          <span className="text-plex-text-muted">{task.targetType}</span>
        </div>
        <span
          className={`inline-block px-2 py-0.5 rounded text-xs font-medium shrink-0 ${badge.color}`}
          title={task.errorMessage || undefined}
        >
          {badge.label}
        </span>
      </div>
      {task.state === 'FAILED' && task.errorMessage && (
        <div className="text-red-400 truncate mt-0.5 pl-1" style={{ fontSize: '10px' }}>
          {task.errorMessage}
        </div>
      )}
    </div>
  );
};

RunProgressRow.propTypes = {
  task: PropTypes.shape({
    taskKey: PropTypes.string.isRequired,
    locationName: PropTypes.string.isRequired,
    targetDate: PropTypes.string.isRequired,
    targetType: PropTypes.string.isRequired,
    state: PropTypes.string.isRequired,
    errorMessage: PropTypes.string,
  }).isRequired,
};

export default RunProgressRow;
