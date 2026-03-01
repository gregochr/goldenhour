import PropTypes from 'prop-types';

/**
 * Health status indicator: green dot for UP, red for DOWN.
 * Shows a tooltip with the status and last-checked timestamp.
 */
export default function HealthIndicator({ status, checkedAt }) {
  if (!status) return null; // Don't render until first poll completes

  const isUp = status === 'UP';
  const timeStr = checkedAt
    ? checkedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : '';
  const tooltip = `${isUp ? 'Up' : 'Down'} at ${timeStr}`;

  return (
    <div
      className={`flex items-center gap-2 text-sm px-3 py-1.5 rounded-lg font-medium ${
        isUp
          ? 'bg-green-900/30 border border-green-700 text-green-400'
          : 'bg-red-900/30 border border-red-700 text-red-400'
      }`}
      data-testid="health-indicator"
      title={tooltip}
    >
      <span
        className={`w-2 h-2 rounded-full flex-shrink-0 ${isUp ? 'bg-green-400' : 'bg-red-400'}`}
      />
      <span className="flex-shrink-0">{status}</span>
    </div>
  );
}

HealthIndicator.propTypes = {
  status: PropTypes.oneOf(['UP', 'DOWN', null]),
  checkedAt: PropTypes.instanceOf(Date),
};
