import PropTypes from 'prop-types';

/**
 * Health status indicator: green (UP), amber (DEGRADED), red (DOWN).
 * Tooltip shows status, timestamp, and which components are degraded.
 */
export default function HealthIndicator({ status, degraded, checkedAt }) {
  if (!status) return null;

  const timeStr = checkedAt
    ? checkedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : '';

  let label, tooltip, dotClass, bgClass;

  if (status === 'UP') {
    label = 'UP';
    tooltip = `Up at ${timeStr}`;
    dotClass = 'bg-green-400';
    bgClass = 'bg-green-900/30 border-green-700 text-green-400';
  } else if (status === 'DEGRADED') {
    const failedParts = degraded.join(', ');
    label = 'DEGRADED';
    tooltip = `Degraded at ${timeStr} \u2014 ${failedParts} unavailable`;
    dotClass = 'bg-amber-400';
    bgClass = 'bg-amber-900/30 border-amber-700 text-amber-400';
  } else {
    label = 'DOWN';
    tooltip = `Down at ${timeStr}`;
    dotClass = 'bg-red-400';
    bgClass = 'bg-red-900/30 border-red-700 text-red-400';
  }

  return (
    <div
      className={`flex items-center gap-2 text-sm px-3 py-1.5 rounded-lg font-medium border ${bgClass}`}
      data-testid="health-indicator"
      title={tooltip}
    >
      <span className={`w-2 h-2 rounded-full flex-shrink-0 ${dotClass}`} />
      <span className="flex-shrink-0">{label}</span>
    </div>
  );
}

HealthIndicator.propTypes = {
  status: PropTypes.oneOf(['UP', 'DOWN', 'DEGRADED', null]),
  degraded: PropTypes.arrayOf(PropTypes.string),
  checkedAt: PropTypes.instanceOf(Date),
};
