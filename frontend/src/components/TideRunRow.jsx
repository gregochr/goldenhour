import React from 'react';
import PropTypes from 'prop-types';
import {
  VIEW_W,
  VIEW_H,
  MINUTES_PER_DAY,
  SAMPLE_MINUTES,
  LABEL_COLLISION_MINUTES,
  EDGE_PERCENT,
  toMinutes,
  percentOf,
} from './chart/solarDayGeometry.js';

/** A semidiurnal tide cycle is ~12h25m. Half of one is the extension past each end of the day. */
const CYCLE_MINUTES = 745;

/**
 * Curve baselines — high water rides near the top of the band, low water near the bottom.
 *
 * <p>These stay HERE rather than in the shared geometry, and so does the boolean mapping that uses
 * them: a tide curve plots high-or-low, while the surge curve plots metres through a scale. That is
 * a different function, not a different parameter, which is why the surge chart is a sibling
 * component rather than a mode of this one.
 */
const HIGH_Y = 7;
const LOW_Y = 25;

/**
 * Builds the SVG path for a day's tide curve.
 *
 * <p>Cosine interpolation between extrema — a real tide is very close to sinusoidal between high
 * and low water, so this is a fair rendering of the shape rather than a decorative squiggle. The
 * series is extended half a cycle past both ends of the day, otherwise the curve flat-lines from
 * midnight to the first extremum and reads as slack water that is not there.
 *
 * @param {Array<{type: string, time: string}>} tides the day's extrema, chronological
 * @returns {string} an SVG path `d` attribute
 */
function curvePath(tides) {
  const extrema = tides.map((t) => ({ high: t.type === 'H', m: toMinutes(t.time) }));
  const first = extrema[0];
  const last = extrema[extrema.length - 1];
  const series = [
    { high: !first.high, m: first.m - CYCLE_MINUTES / 2 },
    ...extrema,
    { high: !last.high, m: last.m + CYCLE_MINUTES / 2 },
  ];
  const y = (high) => (high ? HIGH_Y : LOW_Y);

  const points = [];
  for (let m = 0; m <= MINUTES_PER_DAY; m += SAMPLE_MINUTES) {
    let i = 0;
    while (i < series.length - 2 && series[i + 1].m < m) i += 1;
    const a = series[i];
    const b = series[i + 1];
    const f = Math.min(1, Math.max(0, (m - a.m) / (b.m - a.m)));
    const eased = (1 - Math.cos(Math.PI * f)) / 2;
    points.push([(m / MINUTES_PER_DAY) * VIEW_W, y(a.high) + (y(b.high) - y(a.high)) * eased]);
  }
  return points
    .map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(2)}`)
    .join(' ');
}

/**
 * The 24-hour chart: a day's tide curve drawn against that day's own sunrise and sunset.
 *
 * <p>Night is a translucent wash before sunrise and after sunset; the solar events are thin amber
 * rules. Sun times sit above the curve and tide times below it, so the two never share a line.
 *
 * <p><b>Decorative to a screen reader.</b> The chart is `aria-hidden` and the row's verdict string
 * carries the same meaning in words — that split is deliberate, so do not hide the verdict.
 */
function TideChart({ day }) {
  const sunriseX = percentOf(day.sunrise);
  const sunsetX = percentOf(day.sunset);
  const sunriseMin = toMinutes(day.sunrise);
  const sunsetMin = toMinutes(day.sunset);

  return (
    <div className="tr-chart" aria-hidden="true">
      <svg viewBox={`0 0 ${VIEW_W} ${VIEW_H}`} preserveAspectRatio="none">
        <rect x="0" y="0" width={(sunriseX * 10).toFixed(0)} height={VIEW_H} fill="rgba(0,0,0,0.28)" />
        <rect
          x={(sunsetX * 10).toFixed(0)}
          y="0"
          width={(VIEW_W - sunsetX * 10).toFixed(0)}
          height={VIEW_H}
          fill="rgba(0,0,0,0.28)"
        />
        <path
          d={curvePath(day.tides)}
          fill="none"
          stroke="var(--tr-accent)"
          strokeWidth="1.4"
          vectorEffect="non-scaling-stroke"
          opacity="0.9"
        />
      </svg>

      <div className="tr-sun" style={{ left: `${sunriseX.toFixed(1)}%` }}>
        <span>↑ {day.sunrise}</span>
      </div>
      <div className="tr-sun tr-sun-set" style={{ left: `${sunsetX.toFixed(1)}%` }}>
        <span>↓ {day.sunset}</span>
      </div>

      {day.tides.map((tide) => {
        const m = toMinutes(tide.time);
        // A tide label sitting on top of a sun marker is unreadable, and the verdict already
        // names that extremum — drop the label rather than overprint.
        if (Math.min(Math.abs(m - sunriseMin), Math.abs(m - sunsetMin)) < LABEL_COLLISION_MINUTES) {
          return null;
        }
        const x = (m / MINUTES_PER_DAY) * 100;
        const edge = x < EDGE_PERCENT
          ? { left: 0, transform: 'none' }
          : x > 100 - EDGE_PERCENT
            ? { right: 0, left: 'auto', transform: 'none' }
            : { left: `${x.toFixed(1)}%` };
        return (
          <span key={`${tide.type}-${tide.time}`} className="tr-lab" style={edge}>
            {tide.type === 'H' ? 'HW' : 'LW'} <b>{tide.time}</b>
          </span>
        );
      })}
    </div>
  );
}

TideChart.propTypes = {
  day: PropTypes.object.isRequired,
};

/**
 * One day of a multi-day tidal run: its range, its 24-hour tide chart against that day's sunrise
 * and sunset, and the alignment verdict in words.
 *
 * <p>This replaces the generic fact chips on a tide pill because it answers the question those
 * chips left as mental arithmetic. `LW 22:19` and `sunrise 05:10` were true, adjacent, and useless
 * together — the reader had to subtract. Here the low water is drawn against the sun and the
 * verdict states the gap, so <em>does low water land near sunrise?</em> is readable at a glance.
 *
 * <p>Emphasis, never reordering: the aligned day's verdict takes the topic accent. The run stays in
 * date order — a second ordering by quality inside a chronological list hides that the run
 * continues on days with poor timing, which is exactly what someone planning Thursday needs.
 *
 * @param {Object}    props
 * @param {Object}    props.day         the run day from `topic.tideRun`
 * @param {string}    props.accentColor the topic's accent, driving the curve and aligned verdict
 * @param {?Function} props.onShowOnMap opens the map at this topic's locations, or null
 */
export default function TideRunRow({ day, accentColor, onShowOnMap = null }) {
  return (
    <div
      data-testid="tide-run-row"
      className="tide-run"
      style={{ '--tr-accent': accentColor }}
    >
      <div className={`tide-row${day.aligned ? ' aligned' : ''}${day.peak ? ' peak' : ''}`}>
        {/* A king run leads with how HIGH the water gets and how far that clears the spring
            threshold — that excess is what makes it king rather than merely spring. A spring run
            leads with the swing against its own mean. Same slot, different question. */}
        {day.highWater ? (
          <span data-testid="tide-run-metric" className="tr-range">
            high water <b>{day.highWater}</b>
            {day.highWaterAnomaly && <i>{day.highWaterAnomaly}</i>}
            {/* How extraordinary, which the spring excess cannot say — that threshold is 125% of
                mean high water, so metres-over-the-mean would be the spring figure plus a constant.
                This one is measured against the highest ever recorded here. */}
            {day.highWaterRank && (
              <i data-testid="tide-run-rank" className="tr-rank">{day.highWaterRank}</i>
            )}
          </span>
        ) : (
          <span data-testid="tide-run-metric" className="tr-range">
            range <b>{day.range}</b>
            {day.rangeAnomaly && <i>{day.rangeAnomaly} avg</i>}
          </span>
        )}

        <TideChart day={day} />

        <span data-testid="tide-run-verdict" className="tr-verdict">
          <b>{day.verdict}</b>
        </span>
      </div>

      <div className="tr-foot">
        <span className="key"><span className="swatch" />tide height</span>
        <span className="key"><span className="swatch sun" />sunrise / sunset</span>
        {day.seas && <span>seas <b>{day.seas}</b></span>}
        {/* Which coastline the curve is for. The topic can span 50 locations whose alignment
            differs by ~20 minutes, and naming the one we drew is the honest form of that caveat. */}
        <span data-testid="tide-run-location" className="tr-loc">at {day.locationName}</span>
        {/* Absent on an unaligned day: the backend only claims the draw when the water
            lands in usable light, so there is nothing to print rather than an empty slot. */}
        {day.phrase && <span className="phrase">{day.phrase}</span>}
        {onShowOnMap && (
          <button
            type="button"
            data-testid="tide-run-map-link"
            className="topic-map"
            onClick={(e) => { e.stopPropagation(); onShowOnMap(); }}
          >
            ◍ Open on map →
          </button>
        )}
      </div>
    </div>
  );
}

TideRunRow.propTypes = {
  day: PropTypes.shape({
    runLabel: PropTypes.string,
    dayNumber: PropTypes.number,
    dayCount: PropTypes.number,
    dayLabel: PropTypes.string,
    locationName: PropTypes.string,
    range: PropTypes.string.isRequired,
    rangeAnomaly: PropTypes.string,
    highWater: PropTypes.string,
    highWaterAnomaly: PropTypes.string,
    highWaterRank: PropTypes.string,
    sunrise: PropTypes.string.isRequired,
    sunset: PropTypes.string.isRequired,
    seas: PropTypes.string,
    tides: PropTypes.arrayOf(PropTypes.shape({
      type: PropTypes.oneOf(['H', 'L']).isRequired,
      time: PropTypes.string.isRequired,
    })).isRequired,
    verdict: PropTypes.string.isRequired,
    aligned: PropTypes.bool,
    alignedEvent: PropTypes.oneOf(['sunrise', 'sunset']),
    peak: PropTypes.bool,
    phrase: PropTypes.string,
  }).isRequired,
  accentColor: PropTypes.string.isRequired,
  onShowOnMap: PropTypes.func,
};
