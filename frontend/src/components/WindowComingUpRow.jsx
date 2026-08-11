import React from 'react';
import PropTypes from 'prop-types';

/**
 * One row of the "Coming up" feed: when it is, what it is, and what is known about it.
 *
 * <p>Mock {@code .ev} (`Plan Window First.html` :164-171), rendered at :304-316. Everything the row
 * shows is decided in {@code utils/comingUpFeed.js}; this component only places it.
 *
 * <h2>Two columns, not the mock's three</h2>
 *
 * <p>The mock's grid is {@code 112px 1fr auto} and its right-hand column carries lines like
 * "4 regions / 11 nights left" and "2 regions / in 15 days". <b>Half of that cannot be rendered and
 * the other half is already in column one.</b> {@code regions} is empty on every entry the five
 * sources emit — all five pass {@code List.of()} and {@code @JsonInclude(NON_EMPTY)} means the key
 * never reaches the wire — so a region line would be permanently blank; and "in 15 days" is exactly
 * what the lead word says, 112px to the left. What is left is a duplicate beside an empty space, so
 * the column goes rather than being filled. Plan §5 P5 settled the same question the same way for
 * the window card: "no footer bar rather than an empty one".
 *
 * <h2>The whole row is inert</h2>
 *
 * <p>The mock gives {@code .ev} a {@code cursor: pointer} and a hover tint, but never says what a
 * click does, and there is nothing here to open: the feed carries no location, no rating and no
 * per-region breakdown, so a click could only land on a map with nothing to centre on. §6 bans a
 * control that cannot act. A later phase that gives the feed a destination can make the row a
 * button; until then it is text, and it is not styled to look otherwise.
 *
 * @param {object} props
 * @param {object} props.row a descriptor from {@code buildComingUpRows}
 */
export default function WindowComingUpRow({ row }) {
  return (
    <div className="wf-cu-row" role="listitem" data-testid="coming-up-row" data-type={row.type}>
      <div className="wf-cu-when">
        {/* Absent only when there is no usable today to measure against — the dates below are
            absolute and carry the row on their own in that case. */}
        {row.lead && <b data-testid="coming-up-lead">{row.lead}</b>}
        <span data-testid="coming-up-dates">{row.dates}</span>
        {/* What the span's edges mean, or which day inside it is the one. Absent far more often
            than present, so it is its own element rather than a clause appended to the dates. */}
        {row.whenNote && <span data-testid="coming-up-when-note">{row.whenNote}</span>}
      </div>

      <div className="wf-cu-body">
        <div className="wf-cu-name">
          <span data-testid="coming-up-title">{row.title}</span>
          {/* Marked only when it is NOT the page's stated default — see comingUpFeed.js. */}
          {row.kindLabel && (
            <span className="wf-cu-kind" data-testid="coming-up-kind">{row.kindLabel}</span>
          )}
        </div>

        <p className="wf-cu-detail" data-testid="coming-up-detail">{row.detail}</p>

        {/* `.wf-facts` is P7's, reused rather than re-declared: it is already the mono 10.5px
            label-then-value line at the tone this project has had to correct to five times, and it
            is not scoped to `.wf-rows`. What is deliberately NOT borrowed is the `.opt` phone-drop
            class — these facts have no ranking among them yet, and P14 owns that decision. */}
        {row.facts.length > 0 && (
          <div className="wf-facts" data-testid="coming-up-facts">
            {/* Index-keyed, for the reason WindowAttributeRow:39-41 gives: the list is fixed and
                ordered by the builder, so position is the stable identity and content is the one
                that could collide. */}
            {row.facts.map((fact, i) => (
              <span key={`${row.key}:fact:${i}`} data-testid="coming-up-fact">
                {fact.segments.map((segment, j) => (
                  <span key={`${j}:${segment.text}`} data-tone={segment.tone}>{segment.text}</span>
                ))}
              </span>
            ))}
          </div>
        )}

        {row.attribution && (
          <p className="wf-cu-attrib" data-testid="coming-up-attribution">{row.attribution}</p>
        )}

        {/* The degrade rule on screen. It replaces nothing — the row keeps its dates, its title and
            its explanation — and it names the absence rather than leaving a reader to notice that
            other rows have numbers and this one does not. No placeholder figure, ever. */}
        {row.figuresMissing && (
          <p className="wf-cu-degraded" data-testid="coming-up-figures-missing">
            Dates only — no heights or clock times for this run.
          </p>
        )}
      </div>
    </div>
  );
}

WindowComingUpRow.propTypes = {
  row: PropTypes.shape({
    key: PropTypes.string.isRequired,
    type: PropTypes.string,
    title: PropTypes.string.isRequired,
    detail: PropTypes.string,
    kindLabel: PropTypes.string,
    // Nullable: absent when there is no usable today to measure the span against.
    lead: PropTypes.string,
    dates: PropTypes.string.isRequired,
    whenNote: PropTypes.string,
    facts: PropTypes.arrayOf(PropTypes.shape({
      segments: PropTypes.arrayOf(PropTypes.shape({
        text: PropTypes.string.isRequired,
        tone: PropTypes.oneOf(['base', 'strong']).isRequired,
      })).isRequired,
    })).isRequired,
    attribution: PropTypes.string,
    figuresMissing: PropTypes.bool,
  }).isRequired,
};
