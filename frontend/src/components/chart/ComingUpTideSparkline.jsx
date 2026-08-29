import React from 'react';
import PropTypes from 'prop-types';
import {
  SPARKLINE_VIEW_W, SPARKLINE_VIEW_H, SPARKLINE_MARKER_X, SPARKLINE_AXIS_Y,
  SPARKLINE_GHOST_AMPLITUDE, SPARKLINE_MARKER_RADIUS,
  tideSparklineAmplitude, tideWavePath, tideMarkerY,
} from '../../utils/comingUpSparkline.js';

/**
 * The Coming up chronology card's tide sparkline (design README §4 "Tide sparkline", plan §6b) —
 * amplitude carries the range so a big run is visibly taller without a reader doing arithmetic, and
 * a faint "ghost" wave behind it draws what an average tide at this port looks like.
 *
 * <h2>Decorative — the facts text is the accessible answer</h2>
 *
 * <p>The whole `<svg>` is `aria-hidden`, matching `TideRunRow`'s own chart and the design's own
 * rule: everything it draws is already stated in words in the numeric label beside it (`5.2 m` /
 * `+1.9 vs avg`), which is NOT hidden — a screen-reader user gets the same facts a sighted reader
 * gets from the picture, just as text instead of a curve.
 *
 * <h2>Stretched, unlike `WindowTideSparkline`</h2>
 *
 * <p>`preserveAspectRatio="none"` on purpose: the design's box renders 84×24 against a 104×24
 * viewBox, so the wave is deliberately squashed horizontally. That is the opposite call to the
 * window row's own sparkline (`WindowTideSparkline`, 1:1, default `preserveAspectRatio`), which
 * plots a different shape entirely (a day's normalised tide curve, not this fixed-phase cosine) and
 * would turn its circular marker into an ellipse if stretched. The two are unrelated components
 * beyond sharing "a small tide SVG" in the name.
 *
 * <h2>Colour comes from the ancestor, not a prop</h2>
 *
 * <p>The live wave strokes `var(--wf-cu-accent)`, the same custom property `.wf-cu-card[data-family]`
 * already sets for the fact row's own accent tone (`index.css`) — this component only ever renders
 * inside that card, so there is nothing to thread as a colour prop.
 *
 * @param {object} props
 * @param {object} props.tide `{ range, delta, phase }` from `ComingUpEntry.tide` (plan §13) —
 *        `delta` is the served `range - avgRangeMetres`; `phase` is `'HW'` or `'LW'` for the marked
 *        water and drives the wave's sign (plan §6b)
 */
export default function ComingUpTideSparkline({ tide }) {
  const { range, delta, phase } = tide;
  const isLow = phase === 'LW';
  const amplitude = tideSparklineAmplitude(delta);
  const markerY = tideMarkerY(amplitude, isLow);

  return (
    <span className="wf-cu-spark" data-testid="coming-up-tide-fact">
      <svg
        className="wf-cu-spark-svg"
        data-testid="coming-up-tide-sparkline"
        viewBox={`0 0 ${SPARKLINE_VIEW_W} ${SPARKLINE_VIEW_H}`}
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <path
          d={tideWavePath(SPARKLINE_GHOST_AMPLITUDE, isLow)}
          fill="none"
          stroke="var(--wf-cu-accent)"
          strokeWidth="1"
          opacity="0.26"
        />
        <path
          d={tideWavePath(amplitude, isLow)}
          fill="none"
          stroke="var(--wf-cu-accent)"
          strokeWidth="1.5"
          strokeLinejoin="round"
        />
        <line
          x1={SPARKLINE_MARKER_X}
          y1={markerY}
          x2={SPARKLINE_MARKER_X}
          y2={SPARKLINE_AXIS_Y}
          stroke="var(--color-topic-marker)"
          strokeWidth="1"
          strokeDasharray="2 2"
          opacity="0.75"
        />
        <circle
          data-testid="coming-up-tide-sparkline-marker"
          cx={SPARKLINE_MARKER_X}
          cy={markerY}
          r={SPARKLINE_MARKER_RADIUS}
          fill="var(--color-topic-marker)"
        />
      </svg>
      <span className="wf-cu-spark-label" data-testid="coming-up-tide-sparkline-label">
        <b>{`${range.toFixed(1)} m`}</b>
        {' '}
        <span className="wf-cu-spark-delta">
          {`${delta > 0 ? '+' : ''}${delta.toFixed(1)} vs avg`}
        </span>
      </span>
    </span>
  );
}

ComingUpTideSparkline.propTypes = {
  tide: PropTypes.shape({
    range: PropTypes.number.isRequired,
    delta: PropTypes.number.isRequired,
    phase: PropTypes.oneOf(['HW', 'LW']).isRequired,
  }).isRequired,
};
