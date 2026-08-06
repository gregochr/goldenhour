import React from 'react';
import PropTypes from 'prop-types';
import { CHART_W, CHART_H } from '../../utils/windowFirstRows.js';

/**
 * The window tide row's 104×24 sparkline: the day's tide shape, with the solar event marked on it.
 *
 * <h2>It is decorative, and the row beside it is the answer</h2>
 *
 * <p>`aria-hidden`, exactly as `TideRunRow`'s chart is, because everything it shows is already
 * stated in words one column to the right — the state, the direction, the nearest extreme and its
 * offset from the light. That split is the licence for the picture; do not hide the facts.
 *
 * <h2>It carries shape, never scale</h2>
 *
 * <p>The curve arrives normalised 0–1 across its own day, so a 1 m range and a 6 m range draw
 * identically and the metres are stated in words beside it. That is the same call `TideRunRow` makes
 * and the opposite of `SurgeRunRow`, which plots metres through a domain→range scale because a surge
 * has an absolute magnitude a reader acts on. At 104px there is no room for an axis, so a trace that
 * implied one would be the more misleading of the two.
 *
 * <h2>The box is 1:1, deliberately</h2>
 *
 * <p>`.wf-mini` fixes the SVG at 104×24 CSS pixels against a 104×24 viewBox, so no scaling happens
 * and the mark stays a circle. The design's own `preserveAspectRatio="none"` is therefore inert
 * here and is <b>not</b> carried over: were the column ever to stretch, `none` would quietly turn
 * the mark into an ellipse, where the default letterboxes instead. `TideRunRow` does stretch, which
 * is why it needs both that attribute and `vectorEffect`; this one needs neither.
 *
 * @param {object} props
 * @param {object} props.chart a descriptor from {@code tideSparkline} — `path`, and a mark that is
 *        independently nullable when the window's instant could not be placed on the trace
 */
export default function WindowTideSparkline({ chart }) {
  return (
    <span className="wf-mini" data-testid="window-tide-sparkline" aria-hidden="true">
      <svg
        data-testid="window-tide-svg"
        viewBox={`0 0 ${CHART_W} ${CHART_H}`}
        width={CHART_W}
        height={CHART_H}
      >
        <path
          data-testid="window-tide-trace"
          d={chart.path}
          fill="none"
          stroke="var(--color-tide)"
          strokeWidth="1.5"
        />
        {chart.markX != null && (
          <>
            {/* From above the box to below it: the mark is a "when", and a rule that stopped at the
                trace would read as a measurement of it. `.wf-mini svg { overflow: visible }` is
                what lets the overshoot show. */}
            <line
              data-testid="window-tide-mark-rule"
              x1={chart.markX}
              y1={-1}
              x2={chart.markX}
              y2={CHART_H}
              stroke="var(--color-verdict-marginal)"
              strokeWidth="1"
              strokeDasharray="2 2"
            />
            <circle
              data-testid="window-tide-mark"
              cx={chart.markX}
              cy={chart.markY}
              r="2.4"
              fill="var(--color-verdict-marginal)"
            />
          </>
        )}
      </svg>
    </span>
  );
}

WindowTideSparkline.propTypes = {
  chart: PropTypes.shape({
    path: PropTypes.string.isRequired,
    markX: PropTypes.number,
    markY: PropTypes.number,
  }).isRequired,
};
