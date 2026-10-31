import PropTypes from 'prop-types';

/** The fixed scale every bar shares, in metres, so bars compare across locations. */
export const TIDE_SCALE_MIN = -3.5;
export const TIDE_SCALE_MAX = 3.5;

/**
 * Where a height sits on the bar, as a percentage of the track: {@code (v + 3.5) / 7}, clamped to the track.
 * Null or non-numeric gives null (the caller drops that band or tick rather than drawing it at 0).
 *
 * @param {number|string|null|undefined} metres
 * @returns {number|null} 0 to 100
 */
export function tidePosition(metres) {
  if (metres == null || metres === '') return null;
  const v = Number(metres);
  if (Number.isNaN(v)) return null;
  const fraction = (v - TIDE_SCALE_MIN) / (TIDE_SCALE_MAX - TIDE_SCALE_MIN);
  return Math.min(1, Math.max(0, fraction)) * 100;
}

/** The accessible name: the numbers the bar draws, leaving out any that are missing. */
function describe({ minLow, avgLow, avgHigh, maxHigh, p95High }) {
  const parts = [];
  const metres = (v) => `${Number(v).toFixed(2)} m`;
  if (tidePosition(minLow) != null && tidePosition(maxHigh) != null) {
    parts.push(`lowest low ${metres(minLow)} to highest high ${metres(maxHigh)}`);
  }
  if (tidePosition(avgLow) != null && tidePosition(avgHigh) != null) {
    parts.push(`average low ${metres(avgLow)} to average high ${metres(avgHigh)}`);
  }
  if (tidePosition(p95High) != null) parts.push(`P95 high ${metres(p95High)}`);
  return parts.length === 0 ? 'Tide range: no data' : `Tide range: ${parts.join('; ')}`;
}

/**
 * The tide range bar (data-tabs-redesign-plan.md §7): on a fixed -3.5 to +3.5 m scale, a light band from the lowest
 * low to the highest high, a darker band from the average low to the average high, a zero line and an amber tick at
 * the P95 high. A missing endpoint drops its band or tick. Shared by the Tides tab and (P6) the Locations panel.
 *
 * @param {object} props
 * @param {number|null} [props.minLow] lowest low
 * @param {number|null} [props.avgLow] average low
 * @param {number|null} [props.avgHigh] average high
 * @param {number|null} [props.maxHigh] highest high
 * @param {number|null} [props.p95High] P95 high
 * @param {'md'|'lg'} [props.size] {@code lg} is the panel's: 30px tall, 12px bands, 22px tick
 * @param {string} [props.testId]
 */
export default function TideRangeBar({
  minLow = null, avgLow = null, avgHigh = null, maxHigh = null, p95High = null, size = 'md', testId = 'tide-range-bar',
}) {
  const large = size === 'lg';
  const band = large ? 'h-3' : 'h-2';
  const full = [tidePosition(minLow), tidePosition(maxHigh)];
  const avg = [tidePosition(avgLow), tidePosition(avgHigh)];
  const p95 = tidePosition(p95High);
  const hasFull = full[0] != null && full[1] != null;
  const hasAvg = avg[0] != null && avg[1] != null;
  const zero = tidePosition(0);

  return (
    <div
      role="img"
      aria-label={describe({ minLow, avgLow, avgHigh, maxHigh, p95High })}
      className={`relative w-full ${large ? 'h-[30px]' : 'h-[18px]'}`}
      data-testid={testId}
    >
      <span className="absolute inset-x-0 top-1/2 h-0.5 -translate-y-1/2 bg-plex-border" aria-hidden="true" />
      {hasFull && (
        <span
          className={`absolute top-1/2 -translate-y-1/2 rounded-sm bg-tide/[.22] ${band}`}
          style={{ left: `${full[0]}%`, width: `${full[1] - full[0]}%` }}
          aria-hidden="true"
          data-testid={`${testId}-full`}
        />
      )}
      {hasAvg && (
        <span
          className={`absolute top-1/2 -translate-y-1/2 rounded-sm bg-tide/[.62] ${band}`}
          style={{ left: `${avg[0]}%`, width: `${avg[1] - avg[0]}%` }}
          aria-hidden="true"
          data-testid={`${testId}-avg`}
        />
      )}
      <span
        className="absolute inset-y-0 w-px bg-plex-text-muted"
        style={{ left: `${zero}%` }}
        aria-hidden="true"
        data-testid={`${testId}-zero`}
      />
      {p95 != null && (
        <span
          className={`absolute top-1/2 w-0.5 -translate-x-1/2 -translate-y-1/2 bg-verdict-marginal ${large ? 'h-[22px]' : 'h-3.5'}`}
          style={{ left: `${p95}%` }}
          aria-hidden="true"
          data-testid={`${testId}-p95`}
        />
      )}
    </div>
  );
}

TideRangeBar.propTypes = {
  minLow: PropTypes.number,
  avgLow: PropTypes.number,
  avgHigh: PropTypes.number,
  maxHigh: PropTypes.number,
  p95High: PropTypes.number,
  size: PropTypes.oneOf(['md', 'lg']),
  testId: PropTypes.string,
};
