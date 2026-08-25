import React, { useState, useRef, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';
import { formatInstantUk } from '../utils/conversions.js';

/** Maps backend service keys to display labels. */
const SERVICE_LABELS = {
  openMeteo: 'Open-Meteo',
  tideCheck: 'WorldTides',
  claudeApi: 'Claude API',
};

/**
 * The expanded panel's width in pixels — the `w-72` on its own element, restated because the
 * position is computed before the panel exists in the DOM and so cannot be measured. Keep the two
 * in step.
 */
const PANEL_WIDTH_PX = 288;
/** Minimum gap the panel keeps from either viewport edge. */
const PANEL_VIEWPORT_MARGIN_PX = 8;

/**
 * This component's tones, in the window-first arm's own badge idiom.
 *
 * <p>Copied in shape, not in spirit, from the window popup's {@code VERDICT_TREATMENT}:
 * a fill at 12–14% of the hue, a border at 40–50%, and the <em>lifted</em> text variant for the
 * word, because this pill is 10.5px type on a tint of its own hue — precisely the size band
 * {@code --color-badge-*} exists for (see the palette note in {@code index.css}).
 *
 * <p>The dot takes the full-strength verdict token rather than the badge one. That is the palette's
 * own rule — "never use a {@code --color-badge-*} as a fill or a border" — and it still clears the
 * 3:1 non-text minimum against each tint it sits on (computed: 5.88:1 go, 3.48:1 stand-down).
 *
 * <p><b>These are the verdict hues, on the one surface where that is safe.</b> Chrome in this system
 * is bone and never colour, so that a green never competes with a forecast verdict — but the
 * masthead carries no forecast, which is the same exemption {@code --color-plex-coral} is granted a
 * few lines above these tokens. It is also the only status vocabulary a reader already knows.
 */
const MASTHEAD_TONE = {
  UP: {
    dot: 'var(--color-verdict-go)',
    fill: 'rgba(138,174,114,0.14)',
    border: 'rgba(138,174,114,0.5)',
    ink: 'var(--color-badge-go)',
  },
  DEGRADED: {
    dot: 'var(--color-verdict-marginal)',
    fill: 'rgba(224,165,66,0.14)',
    border: 'rgba(224,165,66,0.5)',
    ink: 'var(--color-badge-maybe)',
  },
  DOWN: {
    dot: 'var(--color-verdict-standdown)',
    fill: 'rgba(200,69,47,0.12)',
    border: 'rgba(200,69,47,0.4)',
    ink: 'var(--color-badge-poor)',
  },
};

/**
 * The per-service badge ink, which is NOT the overall status: a service row reports UP, DOWN,
 * DEGRADED or UNKNOWN independently of the pill above it.
 */
const MASTHEAD_BADGE_INK = {
  UP: 'var(--color-badge-go)',
  DOWN: 'var(--color-badge-poor)',
  DEGRADED: 'var(--color-badge-maybe)',
  UNKNOWN: 'var(--color-badge-maybe)',
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
 *
 * <p>Wears the window-first masthead's Kodachrome palette — the tones the rest of that masthead
 * wears, rather than Tailwind's own green/amber/red. The v1 header this component used to also
 * serve is gone, so there is only the one look now.
 */
export default function HealthIndicator({
  status, degraded, checkedAt, build, services,
  database, session, appVersion, startedAt,
}) {
  const [open, setOpen] = useState(false);
  const [panelPos, setPanelPos] = useState({ top: 0, right: 0 });
  const pillRef = useRef(null);
  const panelRef = useRef(null);

  const computePosition = useCallback(() => {
    if (!pillRef.current) return;
    const rect = pillRef.current.getBoundingClientRect();
    /**
     * The panel is right-anchored to the pill, then pulled back so its LEFT edge stays on screen.
     *
     * <p>Two clamps that pull in opposite directions, and the second is not decorative. The first
     * keeps a pill near the right edge from pushing the panel off that side. The second exists
     * because the panel is anchored to the pill's right edge, not the viewport's — so how far off
     * the left it hangs is a function of how much sits to the RIGHT of the pill, which the panel
     * cannot see. In the masthead the cog and Sign out follow the pill, so at 390px the anchor put
     * the panel at left −45 and every label in it was clipped — measured on the running app, where
     * the whole left column read "nd started", "ase", "Tides".
     *
     * <p>The width is a constant rather than a measurement because the position is computed before
     * the panel is in the DOM; it must stay in step with the `w-72` class below.
     */
    const maxRight = Math.max(
      PANEL_VIEWPORT_MARGIN_PX,
      window.innerWidth - PANEL_WIDTH_PX - PANEL_VIEWPORT_MARGIN_PX,
    );
    setPanelPos({
      top: rect.bottom + 6,
      right: Math.min(Math.max(PANEL_VIEWPORT_MARGIN_PX, window.innerWidth - rect.right), maxRight),
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
    function handleKeyDown(e) {
      if (e.key === 'Escape') setOpen(false);
    }

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('touchstart', handleClickOutside);
    document.addEventListener('keydown', handleKeyDown);
    window.addEventListener('scroll', handleScroll, true);
    window.addEventListener('resize', computePosition);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('touchstart', handleClickOutside);
      document.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', computePosition);
    };
  }, [open, computePosition]);

  if (!status) return null;

  let label;
  if (status === 'UP') {
    label = 'UP';
  } else if (status === 'DEGRADED') {
    label = 'DEGRADED';
  } else {
    label = 'DOWN';
  }
  // Keyed off the resolved LABEL, not off `status`, so an unrecognised status reads DOWN in words
  // and in colour together.
  const tone = MASTHEAD_TONE[label];
  // Muted fails AA at this size and this project has corrected that five times already — see the
  // masthead note in `WindowFirstShell` (it was the rail footer's until M3 deleted that row).
  // Measured on the running app at 10px on
  // `--color-plex-bg`: muted 3.55:1, secondary 7.09:1. The masthead panel is 10.5px, so it takes
  // secondary throughout and gets its hierarchy from position and weight instead of from a grey
  // nobody can read.
  const quietInk = 'text-plex-text-secondary';
  const panelEdge = 'border-plex-border-light';

  // All three timestamps in this popup read the UK clock. `commitTime` and `startedAt` are backend
  // instants and were being rendered in the reader's zone; `checkedAt` is the browser's own probe
  // time and was not wrong on its own — but leaving it alone would have put two clocks side by side
  // under one heading, which is a harder thing to read than either being uniformly off.
  const timeStr = formatInstantUk(checkedAt, {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  });

  const buildStr = build?.commitId
    ? `${build.commitId}${build.dirty ? ' (dirty)' : ''} on ${build.branch || 'unknown'}`
    : null;

  const buildTimeStr = formatInstantUk(build?.commitTime, {
    year: 'numeric', month: 'short', day: 'numeric',
  });

  const loginAgo = session?.loginTime
    // Snapshot of elapsed time since login, recomputed on each render of this
    // popup; a live clock is unnecessary for a coarse "logged in X ago" label.
    // eslint-disable-next-line react-hooks/purity
    ? formatDuration(Date.now() - new Date(session.loginTime).getTime())
    : null;

  const startedAtDay = formatInstantUk(startedAt, { day: '2-digit', month: 'short' });
  const startedAtStr = startedAtDay
    ? `${startedAtDay} ${formatInstantUk(startedAt, { hour: '2-digit', minute: '2-digit', hour12: false })}`
    : null;

  const serviceEntries = services ? Object.entries(services) : [];

  return (
    <>
      <button
        ref={pillRef}
        type="button"
        // The masthead's geometry is its two neighbours' geometry, to the pixel — the cog and Sign
        // out are both 10.5px mono at `7px` radius and `5px 10px` padding. A third control in that
        // row that sets its own is what makes a masthead look assembled rather than designed.
        className="flex items-center gap-2 font-mono border cursor-pointer transition-colors"
        style={{
          fontSize: '10.5px', borderRadius: '7px', padding: '5px 10px',
          backgroundColor: tone.fill, borderColor: tone.border, color: tone.ink,
        }}
        data-testid="health-indicator"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        aria-controls="health-panel"
        aria-label={`System status: ${label}`}
      >
        <span
          // 6px, not 8px: the dot is a fraction of its own label, and an 8px disc beside 10.5px
          // type reads as a bullet the reader keeps trying to parse.
          className="w-1.5 h-1.5 rounded-full flex-shrink-0"
          style={{ backgroundColor: tone.dot }}
        />
        <span className="flex-shrink-0">{label}</span>
        {appVersion && appVersion !== 'dev' && (
          <span className="opacity-70 flex-shrink-0">
            {appVersion}
          </span>
        )}
      </button>

      {open && (
        <div
          ref={panelRef}
          style={{
            position: 'fixed', top: panelPos.top, right: panelPos.right, zIndex: 9999,
            fontSize: '10.5px',
          }}
          // The arm's own floating-panel shell — `.wf-peek`'s surface, edge and shadow. A popover
          // that kept the frame's background would read as part of the page it is covering.
          className="w-72 rounded-lg border border-plex-border-light bg-plex-surface-light shadow-xl font-mono"
          data-testid="health-panel"
          id="health-panel"
        >
          {/* Overall status */}
          <div
            className={`flex items-center gap-2 px-3 py-2.5 rounded-t-lg border-b ${panelEdge} font-medium`}
            style={{ backgroundColor: tone.fill, color: tone.ink }}
          >
            <span
              className="w-1.5 h-1.5 rounded-full flex-shrink-0"
              style={{ backgroundColor: tone.dot }}
            />
            <span>{label}</span>
            {degraded && degraded.length > 0 && (
              <span className={`ml-auto font-normal ${quietInk}`}>{degraded.join(', ')}</span>
            )}
          </div>

          {/* Backend start time */}
          {startedAtStr && (
            <div className={`px-3 py-2 border-b ${panelEdge} ${quietInk}`} data-testid="health-started-at">
              Backend started {startedAtStr}
            </div>
          )}

          {/* Database */}
          {database && (
            <div className={`px-3 py-2 border-b ${panelEdge}`} data-testid="health-database-row">
              <div className="flex items-center justify-between">
                <span className="text-plex-text-secondary">Database</span>
                <StatusBadge status={database.status} />
              </div>
            </div>
          )}

          {/* Services */}
          {serviceEntries.length > 0 && (
            <div className={`px-3 py-2 border-b ${panelEdge} space-y-1.5`} data-testid="health-services">
              {serviceEntries.map(([key, svc]) => (
                <div key={key} className="flex items-center justify-between" data-testid={`health-service-${key}`}>
                  <span className="text-plex-text-secondary">{SERVICE_LABELS[key] || key}</span>
                  <span className="flex items-center gap-2">
                    {svc.latencyMs != null && (
                      <span className={quietInk}>{svc.latencyMs}ms</span>
                    )}
                    <StatusBadge status={svc.status} />
                  </span>
                </div>
              ))}
            </div>
          )}

          {/* Build info */}
          {buildStr && (
            <div className={`px-3 py-2 border-b ${panelEdge} ${quietInk}`} data-testid="health-build">
              <div>{buildStr}</div>
              {buildTimeStr && <div>Built {buildTimeStr}</div>}
            </div>
          )}

          {/* Session */}
          {session && (
            <div className={`px-3 py-2 border-b ${panelEdge} ${quietInk}`} data-testid="health-session">
              <div>{session.username} ({session.role})</div>
              {loginAgo && <div>Logged in {loginAgo} ago</div>}
            </div>
          )}

          {/* Last checked */}
          {timeStr && (
            <div className={`px-3 py-2 ${quietInk}`} data-testid="health-checked-at">
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
  // An unknown string is not silently neutral-inked: it falls to secondary, which is the same
  // treatment the arm's AWAITING badge takes for "no verdict yet".
  const ink = MASTHEAD_BADGE_INK[status] ?? 'var(--color-plex-text-secondary)';
  return <span className="font-medium" style={{ color: ink }}>{status}</span>;
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
  appVersion: PropTypes.string,
  startedAt: PropTypes.string,
};
