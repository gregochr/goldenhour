import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';

/**
 * Health status indicator: green (UP), amber (DEGRADED), red (DOWN).
 * Tooltip shows enriched status: timestamp, build info, services, and degraded components.
 */
export default function HealthIndicator({ status, degraded, checkedAt, build, services }) {
  if (!status) return null;

  const timeStr = checkedAt
    ? checkedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : '';

  let label, dotClass, bgClass;
  const tooltipParts = [];

  if (status === 'UP') {
    label = 'UP';
    tooltipParts.push(`Up at ${timeStr}`);
    dotClass = 'bg-green-400';
    bgClass = 'bg-green-900/30 border-green-700 text-green-400';
  } else if (status === 'DEGRADED') {
    const failedParts = (degraded || []).join(', ');
    label = 'DEGRADED';
    tooltipParts.push(`Degraded at ${timeStr} \u2014 ${failedParts} unavailable`);
    dotClass = 'bg-amber-400';
    bgClass = 'bg-amber-900/30 border-amber-700 text-amber-400';
  } else {
    label = 'DOWN';
    tooltipParts.push(`Down at ${timeStr}`);
    dotClass = 'bg-red-400';
    bgClass = 'bg-red-900/30 border-red-700 text-red-400';
  }

  if (build?.commitId) {
    tooltipParts.push(`Build: ${build.commitId}${build.dirty ? ' (dirty)' : ''} on ${build.branch || 'unknown'}`);
  }

  if (services && Object.keys(services).length > 0) {
    const svcParts = Object.entries(services)
      .map(([name, svc]) => `${name}: ${svc.status}${svc.detail ? ` (${svc.detail})` : ''}`)
      .join(', ');
    tooltipParts.push(`Services: ${svcParts}`);
  }

  const tooltip = tooltipParts.join(' | ');

  return (
    <div
      className={`flex items-center gap-2 text-sm px-3 py-1.5 rounded-lg font-medium border ${bgClass}`}
      data-testid="health-indicator"
    >
      <span className={`w-2 h-2 rounded-full flex-shrink-0 ${dotClass}`} />
      <span className="flex-shrink-0">{label}</span>
      <InfoTip text={tooltip} position="below" />
    </div>
  );
}

HealthIndicator.propTypes = {
  status: PropTypes.oneOf(['UP', 'DOWN', 'DEGRADED', null]),
  degraded: PropTypes.arrayOf(PropTypes.string),
  checkedAt: PropTypes.instanceOf(Date),
  build: PropTypes.shape({
    commitId: PropTypes.string,
    branch: PropTypes.string,
    commitTime: PropTypes.string,
    dirty: PropTypes.bool,
  }),
  services: PropTypes.objectOf(PropTypes.shape({
    status: PropTypes.string,
    detail: PropTypes.string,
  })),
};
