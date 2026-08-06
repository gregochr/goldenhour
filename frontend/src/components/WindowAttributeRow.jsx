import React from 'react';
import PropTypes from 'prop-types';
import WindowTideSparkline from './chart/WindowTideSparkline.jsx';

/**
 * One attribute row on a window card — the tide the light falls on, or the snow underneath it.
 *
 * <h2>Three columns, and no fourth</h2>
 *
 * <p>The design's `.frow` is `auto auto 1fr auto`: kicker, chart, facts, and an action link
 * ("61 coastal locations →", "Filter to high ground →"). <b>The action column is not built.</b>
 * Both of its labels fail a rule this project already holds: the first is a count of our own data,
 * which §6 bans outright ("11 aligned is a fact about the database, not about tonight"), and the
 * second is a location-type filter, which is P11's control — shipping it inert now is exactly the
 * demo control §6 bans. So the tide row is `auto auto 1fr` and the snow row `auto 1fr`, and the
 * column returns with something true to put in it.
 *
 * <h2>The chart is the row's only optional column</h2>
 *
 * <p>Absent when the payload carries no drawable shape, and absent on a phone by media query — the
 * design's own `.wrap.mob .mini{display:none}`. Neither costs the reader anything, because the row
 * is `aria-hidden` on the picture and states the whole answer in the facts.
 *
 * @param {object} props
 * @param {object} props.row a descriptor from {@code buildWindowRows}
 */
export default function WindowAttributeRow({ row }) {
  return (
    <div
      data-testid="window-attribute-row"
      data-channel={row.channel}
      className={`wf-frow wf-frow-${row.channel}`}
    >
      <span data-testid="window-attribute-kicker" className="wf-frow-k">{row.kicker}</span>

      {row.chart && <WindowTideSparkline chart={row.chart} />}

      <span data-testid="window-attribute-facts" className="wf-facts">
        {/* Index-keyed, and deliberately: a row's facts are a fixed, ordered list built fresh from
            one payload — nothing reorders or splices them — while their text is not unique by
            construction, so keying on content is the option that could actually collide. */}
        {row.facts.map((fact, i) => (
          <span
            key={`${i}:${fact.segments[0]?.text ?? ''}`}
            data-testid="window-attribute-fact"
            className={fact.optional ? 'opt' : undefined}
          >
            {fact.segments.map((segment, j) => (
              <span key={`${j}:${segment.text}`} data-tone={segment.tone}>{segment.text}</span>
            ))}
          </span>
        ))}
      </span>
    </div>
  );
}

WindowAttributeRow.propTypes = {
  row: PropTypes.shape({
    channel: PropTypes.oneOf(['tide', 'snow']).isRequired,
    kicker: PropTypes.string.isRequired,
    facts: PropTypes.arrayOf(PropTypes.shape({
      segments: PropTypes.arrayOf(PropTypes.shape({
        text: PropTypes.string.isRequired,
        tone: PropTypes.oneOf(['base', 'strong']).isRequired,
      })).isRequired,
      optional: PropTypes.bool,
    })).isRequired,
    chart: PropTypes.shape({
      path: PropTypes.string.isRequired,
      markX: PropTypes.number,
      markY: PropTypes.number,
    }),
  }).isRequired,
};
