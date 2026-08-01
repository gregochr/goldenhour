import React from 'react';
import PropTypes from 'prop-types';
import {
  VIEW_W,
  VIEW_H,
  MINUTES_PER_DAY,
  EDGE_PERCENT,
  toMinutes,
  percentOf,
  edgePin,
} from './chart/solarDayGeometry.js';

/**
 * Vertical padding inside the band, so a peak at the top of the domain is not clipped by the
 * viewBox edge and the zero line has room beneath it.
 */
const PAD_Y = 5;

/**
 * Floor for the plotted domain, in metres. Without it, a quiet day of 0.06 m wobble would be
 * auto-scaled into a dramatic mountain range — the classic way a chart lies while every number on
 * it stays true. A fixed floor means small surges LOOK small.
 */
const MIN_DOMAIN_M = 0.5;

/**
 * Minimum separation before a second label may share the peak's rail.
 *
 * NOT the shared LABEL_COLLISION_MINUTES (45). That one guards a label against a sun RAIL — a
 * 1px line — so 45 minutes is ample. Here two *labels* sit on one rail (both `.sr-lab`, same
 * `bottom`, both centre-transformed), and at the strip's real width roughly 0.4px per minute,
 * "▲ +0.72 m" against "HW 15:00" needs about 110 minutes of centre separation to clear.
 *
 * The old value was worse than merely tight: the backend calls a peak ALIGNED within 90 minutes
 * of high water, so every aligned day — the exact case this topic exists to surface — fell in the
 * 45-90 gap and rendered two mutually unreadable labels on top of each other.
 */
const SAME_RAIL_COLLISION_MINUTES = 120;

/**
 * A sibling of {@code TideRunRow}: the storm-surge curve for one day at one coastal location.
 *
 * <p><b>Why a sibling and not a mode of the tide row.</b> The tide chart maps a boolean —
 * `high ? HIGH_Y : LOW_Y` — because it plots shape, not scale, and a 1 m range and a 6 m range are
 * drawn identically. Surge is the opposite: the magnitude in metres IS the finding, so this maps a
 * domain to a range. That is a different function, not a parameter, and the two charts share only
 * the local-day axis (`chart/solarDayGeometry.js`).
 *
 * <p><b>The zero line is the predicted astronomical tide.</b> Surge is a residual, so everything
 * drawn here is water on top of (or below) what the almanac already predicts. The row never claims
 * an absolute water level, because the tide datum itself is undocumented upstream — the verdict's
 * datum note states this in words, and the zero rule states it visually.
 *
 * <p><b>The trace is dashed because it is a FORECAST.</b> A tide is an almanac: fixed, knowable,
 * and drawn solid on the neighbouring pill. A surge is a weather prediction that may not happen,
 * and two adjacent charts in one strip must not imply equal confidence. The dash is that
 * distinction, carried in the mark itself rather than only in a chip.
 *
 * <p><b>Gaps break the path.</b> A missing hour is a null, not a zero, and the line stops rather
 * than descending through a value nobody forecast.
 *
 * <p><b>Decorative to a screen reader.</b> The chart is `aria-hidden`; the row's verdict string
 * carries the same meaning in words and must never be hidden.
 */
function SurgeRunRow({ run, accentColor }) {
  const series = run.surgeMetres || [];
  const values = series.filter((v) => v != null);
  if (values.length === 0) {
    return null;
  }

  // The domain always INCLUDES zero, so the predicted-tide rule is on the chart whichever way the
  // residual goes — but it is not centred on zero. A symmetric domain wasted half the band on the
  // ordinary all-positive day, which both shrinks the shape being read and implies the empty half
  // is meaningful. Anchoring at zero puts the rule flush at the bottom for a positive day, at the
  // top for a negative one, and inside the band only when the day genuinely crosses.
  let domainLo = Math.min(0, ...values);
  let domainHi = Math.max(0, ...values);
  if (domainHi - domainLo < MIN_DOMAIN_M) {
    // Expand AWAY from zero rather than around the data, so the floor cannot push the zero rule
    // off the chart on a one-signed day.
    if (domainLo >= 0) {
      domainHi = domainLo + MIN_DOMAIN_M;
    } else if (domainHi <= 0) {
      domainLo = domainHi - MIN_DOMAIN_M;
    } else {
      const mid = (domainLo + domainHi) / 2;
      domainLo = mid - MIN_DOMAIN_M / 2;
      domainHi = mid + MIN_DOMAIN_M / 2;
    }
  }
  const bottom = VIEW_H - PAD_Y;
  const usable = VIEW_H - 2 * PAD_Y;
  const scale = (metres) => bottom - ((metres - domainLo) / (domainHi - domainLo)) * usable;
  const zeroY = scale(0);

  // One segment per unbroken stretch of readings, so a gap ends a path rather than bridging it.
  const segments = [];
  let current = [];
  series.forEach((value, hour) => {
    if (value == null) {
      if (current.length > 1) segments.push(current);
      current = [];
      return;
    }
    current.push([(hour / 24) * VIEW_W, scale(value)]);
  });
  if (current.length > 1) segments.push(current);

  const sunriseX = percentOf(run.sunrise);
  const sunsetX = percentOf(run.sunset);
  const peakX = percentOf(run.peakTime);
  const highWaterX = run.highWaterTime ? percentOf(run.highWaterTime) : null;

  // Glyph follows the SIGN. The backend only surfaces positive peaks today, but a residual can be
  // negative (high pressure pushes water BELOW the prediction) and an up-arrow on a downward
  // excursion would be the chart contradicting its own number.
  const peakGlyph = String(run.peak).trim().startsWith('-') ? '▼' : '▲';
  const markers = [
    { key: 'peak', x: peakX, text: `${peakGlyph} ${run.peak}`, colour: accentColor },
  ];
  // The high-water label is dropped whenever it cannot be read beside the peak's. Losing it costs
  // nothing: the verdict states the relationship in words ("· on high water", "· 1h20 before high
  // water"), and the dashed high-water RULE stays on the chart either way — it is only the text
  // that is suppressed.
  const separation = highWaterX == null
    ? Number.POSITIVE_INFINITY
    : Math.abs(toMinutes(run.highWaterTime) - toMinutes(run.peakTime));
  // Both inside an edge zone is the case a minute-gap test cannot catch: edgePin resolves each to
  // the same flush position, so they stack exactly however far apart in time they are.
  const bothPinnedToSameEdge = highWaterX != null
    && ((peakX < EDGE_PERCENT && highWaterX < EDGE_PERCENT)
      || (peakX > 100 - EDGE_PERCENT && highWaterX > 100 - EDGE_PERCENT));
  if (highWaterX != null && separation >= SAME_RAIL_COLLISION_MINUTES && !bothPinnedToSameEdge) {
    markers.push({ key: 'hw', x: highWaterX, text: `HW ${run.highWaterTime}`, colour: null });
  }

  return (
    <div className="sr-chart" aria-hidden="true">
      <svg viewBox={`0 0 ${VIEW_W} ${VIEW_H}`} preserveAspectRatio="none">
        {/* Night wash, matching the tide row so adjacent pills read on the same axis. */}
        <rect x="0" y="0" width={(sunriseX * 10).toFixed(0)} height={VIEW_H} fill="rgba(0,0,0,0.28)" />
        <rect
          x={(sunsetX * 10).toFixed(0)}
          y="0"
          width={(VIEW_W - sunsetX * 10).toFixed(0)}
          height={VIEW_H}
          fill="rgba(0,0,0,0.28)"
        />

        {/* The predicted tide: the datum everything here is measured from. */}
        <line
          x1="0"
          y1={zeroY}
          x2={VIEW_W}
          y2={zeroY}
          stroke="rgba(255,255,255,0.22)"
          strokeWidth="1"
          vectorEffect="non-scaling-stroke"
        />

        {highWaterX != null && (
          <line
            x1={(highWaterX * 10).toFixed(0)}
            y1="0"
            x2={(highWaterX * 10).toFixed(0)}
            y2={VIEW_H}
            stroke="var(--color-tide)"
            strokeWidth="1"
            strokeDasharray="2 3"
            vectorEffect="non-scaling-stroke"
            opacity="0.75"
          />
        )}

        {segments.map((points) => (
          <path
            key={`seg-${points[0][0].toFixed(0)}`}
            d={points.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(2)}`).join(' ')}
            fill="none"
            stroke={accentColor}
            strokeWidth="1.4"
            // Dashed: this is a forecast, not an almanac. See the class note.
            strokeDasharray="4 2.5"
            vectorEffect="non-scaling-stroke"
            opacity="0.95"
          />
        ))}
      </svg>

      <div className="sr-sun" style={{ left: `${sunriseX.toFixed(1)}%` }}>
        <span>↑ {run.sunrise}</span>
      </div>
      <div className="sr-sun sr-sun-set" style={{ left: `${sunsetX.toFixed(1)}%` }}>
        <span>↓ {run.sunset}</span>
      </div>

      {markers.map((marker) => (
        <div
          key={marker.key}
          className="sr-lab"
          style={{ left: `${marker.x.toFixed(1)}%`, ...edgePin(marker.x) }}
        >
          <b style={marker.colour ? { color: marker.colour } : undefined}>{marker.text}</b>
        </div>
      ))}
    </div>
  );
}

SurgeRunRow.propTypes = {
  run: PropTypes.shape({
    surgeMetres: PropTypes.arrayOf(PropTypes.number),
    sunrise: PropTypes.string.isRequired,
    sunset: PropTypes.string.isRequired,
    peak: PropTypes.string.isRequired,
    peakTime: PropTypes.string.isRequired,
    highWaterTime: PropTypes.string,
  }).isRequired,
  accentColor: PropTypes.string.isRequired,
};

export default SurgeRunRow;
