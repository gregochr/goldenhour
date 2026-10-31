import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import TideRangeBar from '../data/TideRangeBar.jsx';
import { fetchTideStats } from '../../api/tideApi.js';
import { fmtMetres, fmtPercent, numOrNull, signedMetres } from '../../utils/tideAdmin.js';

/** A metres figure with its unit, or an em dash. */
function metres(val) {
  const text = fmtMetres(val);
  return text === '—' ? text : `${text} m`;
}

/** One mono figure with its label (Avg range, P95 high, Spring). */
function Stat({ label, value, tone = '', testId }) {
  return (
    <div className="min-w-0">
      <p className="text-[11px] text-plex-text-secondary">{label}</p>
      <p className={`font-mono text-[15px] ${tone || 'text-plex-text'}`} data-testid={testId}>{value}</p>
    </div>
  );
}

Stat.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  tone: PropTypes.string,
  testId: PropTypes.string.isRequired,
};

/**
 * The Tides section of a seascape's panel (replaces the old Tides button and its modal): the large range bar with its
 * min, 0 and max labels, then Avg range, P95 high (amber) and Spring %, read from {@code fetchTideStats}. It says so
 * while loading, when the fetch fails ("Couldn't load tide statistics.") and when the location has no data yet
 * (a 204: "No tide data yet."). {@code Full tide stats} links to the Tides tab only when stats came back AND the
 * location is on, because the Tides list holds enabled locations only.
 *
 * <p>Mount it with {@code key={location.id}} so a different location starts loading afresh.
 *
 * @param {object} props
 * @param {object} props.location the seascape
 */
export default function LocationTidesSection({ location }) {
  const [state, setState] = useState({ status: 'loading', stats: null });

  useEffect(() => {
    let live = true;
    fetchTideStats(location.name)
      .then((stats) => { if (live) setState(stats ? { status: 'ready', stats } : { status: 'empty', stats: null }); })
      .catch(() => { if (live) setState({ status: 'error', stats: null }); });
    return () => { live = false; };
  }, [location.name]);

  const { status, stats } = state;
  const action = status === 'ready' && location.enabled ? (
    <a
      href={`#manage/tides/${location.id}`}
      className="text-xs text-tide underline hover:text-tide-light focus-visible:outline-2 focus-visible:outline-plex-gold"
      data-testid="location-full-tide-stats"
    >
      Full tide stats
    </a>
  ) : null;

  return (
    <PanelSection label="Tides" action={action} testId="location-tides">
      {status === 'loading' && (
        <p className="animate-pulse text-sm text-plex-text-muted" data-testid="location-tides-loading">
          Loading tide statistics…
        </p>
      )}
      {status === 'error' && (
        <p className="text-xs text-plex-error" role="alert" data-testid="location-tides-error">
          Couldn&apos;t load tide statistics.
        </p>
      )}
      {status === 'empty' && (
        <p className="text-sm text-plex-text-muted" data-testid="location-tides-empty">No tide data yet.</p>
      )}
      {status === 'ready' && (
        <>
          <TideRangeBar
            size="lg"
            minLow={numOrNull(stats.minLowMetres)}
            avgLow={numOrNull(stats.avgLowMetres)}
            avgHigh={numOrNull(stats.avgHighMetres)}
            maxHigh={numOrNull(stats.maxHighMetres)}
            p95High={numOrNull(stats.p95HighMetres)}
            testId="location-tide-bar"
          />
          <div className="mt-1 flex justify-between font-mono text-[11px] text-plex-text-secondary" data-testid="location-tide-bar-labels">
            <span>{signedMetres(stats.minLowMetres)}</span>
            <span>0</span>
            <span>{signedMetres(stats.maxHighMetres)}</span>
          </div>
          <div className="mt-3 grid grid-cols-3 gap-x-3">
            <Stat label="Avg range" value={metres(stats.avgRangeMetres)} testId="location-tide-avg-range" />
            <Stat label="P95 high" value={metres(stats.p95HighMetres)} tone="text-verdict-marginal" testId="location-tide-p95" />
            <Stat label="Spring" value={fmtPercent(stats.springTideFrequency)} testId="location-tide-spring" />
          </div>
        </>
      )}
    </PanelSection>
  );
}

LocationTidesSection.propTypes = { location: PropTypes.object.isRequired };
