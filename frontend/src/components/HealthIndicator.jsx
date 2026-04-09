import React, { useState, useRef, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';

/** Maps backend service keys to display labels. */
const SERVICE_LABELS = {
  openMeteo: 'Open-Meteo',
  tideCheck: 'WorldTides',
  claudeApi: 'Claude API',
};

/**
 * Formats a duration in milliseconds to a human-readable string.
 * e.g. 135 min → "2h 15m", 45 min → "45m", 30 sec → "<1m"
 *
 * @param {number} ms duration in milliseconds
 * @returns {string}
 */
function formatDuration(ms) {
  const totalMinutes = Math.floor(ms / 60000);
  if (totalMinutes < 1) return '<1m';
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes}m`;
  if (minutes === 0) return `${hours}h`;
  return `${hours}h ${minutes}m`;
}

/**
 * Health status indicator: green (UP), amber (DEGRADED), red (DOWN).
 * Click to expand a panel with service probes, database, build info, and session details.
 */
export default function HealthIndicator({
  status, degraded, checkedAt, build, services,
  database, session, startedAt,
}) {
  const [open, setOpen] = useState(false);
  const [panelPos, setPanelPos] = useState({ top: 0, right: 0 });
  const pillRef = useRef(null);
  const panelRef = useRef(null);

  const computePosition = useCallback(() => {
    if (!pillRef.current) return;
    const rect = pillRef.current.getBoundingClientRect();
    setPanelPos({
      top: rect.bottom + 6,
      right: Math.max(8, window.innerWidth - rect.right),
    });
  }, []);

  useEffect(() => {
    if (!open) return;
    computePosition();

    function handleClickOutside(e) {
      if (
        pillRef.current && !pillRef.current.contains(e.target)
        && panelRef.current && !panelRef.current.contains(e.target)
      ) {
        setOpen(false);
      }
    }
    function handleScroll() { computePosition(); }

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('touchstart', handleClickOutside);
    window.addEventListener('scroll', handleScroll, true);
    window.addEventListener('resize', computePosition);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('touchstart', handleClickOutside);
      window.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', computePosition);
    };
  }, [open, computePosition]);

  if (!status) return null;

  let label, dotClass, bgClass;
  if (status === 'UP') {
    label = 'UP';
    dotClass = 'bg-green-400';
    bgClass = 'bg-green-900/30 border-green-700 text-green-400';
  } else if (status === 'DEGRADED') {
    label = 'DEGRADED';
    dotClass = 'bg-amber-400';
    bgClass = 'bg-amber-900/30 border-amber-700 text-amber-400';
  } else {
    label = 'DOWN';
    dotClass = 'bg-red-400';
    bgClass = 'bg-red-900/30 border-red-700 text-red-400';
  }

  const timeStr = checkedAt
    ? checkedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : null;

  const buildStr = build?.commitId
    ? `${build.commitId}${build.dirty ? ' (dirty)' : ''} on ${build.branch || 'unknown'}`
    : null;

  const buildTimeStr = build?.commitTime
    ? new Date(build.commitTime).toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' })
    : null;

  const loginAgo = session?.loginTime
    ? formatDuration(Date.now() - new Date(session.loginTime).getTime())
    : null;

  const startedAtStr = startedAt
    ? new Date(startedAt).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' })
      + ' ' + new Date(startedAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false })
    : null;

  const serviceEntries = services ? Object.entries(services) : [];

  return (
    <>
      <button
        ref={pillRef}
        type="button"
        className={`flex items-center gap-2 text-sm px-3 py-1.5 rounded-lg font-medium border cursor-pointer ${bgClass}`}
        data-testid="health-indicator"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        aria-label={`System status: ${label}`}
      >
        <span className={`w-2 h-2 rounded-full flex-shrink-0 ${dotClass}`} />
        <span className="flex-shrink-0">{label}</span>
      </button>

      {open && (
        <div
          ref={panelRef}
          style={{ position: 'fixed', top: panelPos.top, right: panelPos.right, zIndex: 9999 }}
          className="w-72 rounded-lg border border-plex-border bg-plex-surface shadow-xl text-xs"
          data-testid="health-panel"
        >
          {/* Overall status */}
          <div className={`flex items-center gap-2 px-3 py-2.5 rounded-t-lg border-b border-plex-border font-medium ${bgClass}`}>
            <span className={`w-2 h-2 rounded-full flex-shrink-0 ${dotClass}`} />
            <span>{label}</span>
            {degraded && degraded.length > 0 && (
              <span className="ml-auto text-plex-text-muted font-normal">{degraded.join(', ')}</span>
            )}
          </div>

          {/* Backend start time */}
          {startedAtStr && (
            <div className="px-3 py-2 border-b border-plex-border text-plex-text-muted" data-testid="health-started-at">
              Backend started {startedAtStr}
            </div>
          )}

          {/* Database */}
          {database && (
            <div className="px-3 py-2 border-b border-plex-border" data-testid="health-database-row">
              <div className="flex items-center justify-between">
                <span className="text-plex-text-secondary">Database</span>
                <StatusBadge status={database.status} />
              </div>
            </div>
          )}

          {/* Services */}
          {serviceEntries.length > 0 && (
            <div className="px-3 py-2 border-b border-plex-border space-y-1.5" data-testid="health-services">
              {serviceEntries.map(([key, svc]) => (
                <div key={key} className="flex items-center justify-between" data-testid={`health-service-${key}`}>
                  <span className="text-plex-text-secondary">{SERVICE_LABELS[key] || key}</span>
                  <span className="flex items-center gap-2">
                    {svc.latencyMs != null && (
                      <span className="text-plex-text-muted">{svc.latencyMs}ms</span>
                    )}
                    <StatusBadge status={svc.status} />
                  </span>
                </div>
              ))}
            </div>
          )}

          {/* Build info */}
          {buildStr && (
            <div className="px-3 py-2 border-b border-plex-border text-plex-text-muted" data-testid="health-build">
              <div>{buildStr}</div>
              {buildTimeStr && <div>Built {buildTimeStr}</div>}
            </div>
          )}

          {/* Session */}
          {session && (
            <div className="px-3 py-2 border-b border-plex-border text-plex-text-muted" data-testid="health-session">
              <div>{session.username} ({session.role})</div>
              {loginAgo && <div>Logged in {loginAgo} ago</div>}
            </div>
          )}

          {/* Last checked */}
          {timeStr && (
            <div className="px-3 py-2 text-plex-text-muted" data-testid="health-checked-at">
              Last checked {timeStr}
            </div>
          )}
        </div>
      )}
    </>
  );
}

/**
 * Small coloured status badge (UP/DOWN/UNKNOWN).
 */
function StatusBadge({ status }) {
  let color = 'text-plex-text-muted';
  if (status === 'UP') color = 'text-green-400';
  else if (status === 'DOWN') color = 'text-red-400';
  else if (status === 'DEGRADED' || status === 'UNKNOWN') color = 'text-amber-400';
  return <span className={`font-medium ${color}`}>{status}</span>;
}

StatusBadge.propTypes = {
  status: PropTypes.string.isRequired,
};

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
    latencyMs: PropTypes.number,
  })),
  database: PropTypes.shape({
    status: PropTypes.string,
  }),
  session: PropTypes.shape({
    username: PropTypes.string,
    role: PropTypes.string,
    loginTime: PropTypes.string,
  }),
  startedAt: PropTypes.string,
};
