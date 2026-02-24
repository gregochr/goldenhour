import PropTypes from 'prop-types';

/**
 * Health status indicator: green dot for UP, red for DOWN.
 */
export default function HealthIndicator({ status }) {
  if (!status) return null; // Don't render until first poll completes

  const isUp = status === 'UP';
  return (
    <div
      className={`flex items-center gap-1 text-[10px] px-2 py-0.5 rounded-full ${
        isUp
          ? 'bg-green-900/30 border border-green-700 text-green-400'
          : 'bg-red-900/30 border border-red-700 text-red-400'
      }`}
      data-testid="health-indicator"
    >
      <span
        className={`w-1.5 h-1.5 rounded-full ${isUp ? 'bg-green-400' : 'bg-red-400'}`}
      />
      {status}
    </div>
  );
}

HealthIndicator.propTypes = {
  status: PropTypes.oneOf(['UP', 'DOWN', null]),
};
