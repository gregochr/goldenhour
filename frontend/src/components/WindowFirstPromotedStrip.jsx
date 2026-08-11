import React from 'react';
import PropTypes from 'prop-types';

/**
 * The Plan pane's promoted strip — one coincidence, named, above the window list.
 *
 * <h2>It states the relationship and the window, and deliberately little else</h2>
 *
 * <p>Everything a strip could say about a window, the window's own card already says: its verdict,
 * its best rating, its pick, its topic badges, its attribute rows, its spots. The two things the
 * card structurally cannot say are that <em>two</em> attributes land on this one window, and which
 * window that is relative to the rest of the list. Those are the strip's whole content. Anything
 * else it drew would be a second, competing copy of a card that is a few pixels further down.
 *
 * <h2>What is NOT built, and why — three deliberate deviations</h2>
 *
 * <ul>
 *   <li><b>No chart.</b> Plan §5's P7b row specifies "chart 42px curve + 16px label band" and the
 *       design draws a 320×44 tide curve with two amber markers and four labelled extremes. Two
 *       findings killed it. First, the label band is not derivable: {@code BriefingWindowTide}
 *       carries exactly ONE extreme ({@code nearestType}/{@code nearestTime}) and no per-extreme x
 *       position, and that one clock time is wrapped modulo the day, so a 23:45 sunset's 00:05 high
 *       water would plot at the opposite edge of the axis. The four-label dataset exists only on
 *       {@code hotTopics[].tideRun}, which only spring and king tide topics carry and which selects
 *       its representative coastline <em>independently</em> of the window rollup — so the join can
 *       silently plot one coast's labels over another coast's curve. Second, and decisive even if
 *       the data were there: the curve alone would be the FOURTH tide chart in this arm on one pane
 *       (the same window's own {@code WindowTideSparkline}, plus {@code TideRunRow} and
 *       {@code SurgeRunRow} behind the hot-topics door), and it is meaningful for only 2 of the 12
 *       topic kinds that can become a badge. §6's rule is that new elements earn their place. To
 *       build it honestly: add an extremes list to {@code BriefingWindowTide}, or gate a
 *       {@code tideRun} join on {@code tideRun.locationName === tide.locationName}.</li>
 *   <li><b>No "why" clause.</b> The design's italic serif line explains the PAIR. No field says
 *       that: a badge's {@code detail} explains its own topic, and rendering one of the two as
 *       though it described both would be a sentence about half the strip presented as the whole
 *       of it. Authoring one would be inventing copy.</li>
 *   <li><b>No right-hand meta.</b> The mock's is "tonight · tomorrow · Monday — 61 coastal
 *       locations, 2 regions". "61 coastal locations" is a count of our own data, which §6 bans
 *       outright and which {@code WindowAttributeRow} and {@code WindowFirstDoors} have each
 *       already dropped for that exact reason. A rarity claim cannot replace it either: §2.6 rules
 *       out the mock's "first coincidence since 2 Mar" as an unscheduled historical scan, and the
 *       fixed ordinal that replaced it supports choosing a winner but not asserting a delta.
 *       Nothing else true was left that the title does not already say.</li>
 * </ul>
 *
 * <h2>The pair, and the `×`</h2>
 *
 * <p>Each topic's label is its own element and the `×` between them is {@code aria-hidden} — the
 * arm's established treatment for decorative punctuation ({@code ◎}, the expander's caret). No
 * {@code aria-label} is used anywhere on this component: one would REPLACE name-from-contents
 * rather than extend it, which is the defect that cost a review round when the heatmap cells were
 * named and silently dropped their star ratings. Every word here is real text.
 *
 * <h2>The route into the list, and when it is absent</h2>
 *
 * <p>The strip sits at the top of a pane whose only ordering spine is time, and it may describe the
 * fourth window down. That is tolerable precisely because it reads as an index INTO the list rather
 * than as an item in it — so it names its window and carries a control that opens it. When the
 * promoted window's card is already the next thing on the pane the control is not rendered: it
 * would scroll to the element immediately beneath it, and a control with no visible effect is what
 * §6 bans.
 *
 * <h2>Not role-gated</h2>
 *
 * <p>The same rule and the same reason as {@code windowFirstRows.js}: these are the window's own
 * context, not {@code HotTopicStrip}'s promotional surface, and no {@code role} is plumbed into this
 * arm. The strip shows labels and one measured fact per topic — the same class of content as the
 * attribute rows, which ship ungated for every role.
 *
 * @param {object}   props
 * @param {object}   props.strip a descriptor from {@code buildPromotedStrip}
 * @param {function} [props.onOpenWindow] opens and reveals the promoted window's card. Optional —
 *        without it the strip is a statement rather than a route, which is what an adjacent card
 *        already makes it.
 */
export default function WindowFirstPromotedStrip({ strip, onOpenWindow }) {
  const figures = strip.topics.filter((topic) => topic.figureValue);
  const showGo = !strip.adjacent && Boolean(onOpenWindow);

  return (
    <div
      data-testid="window-first-promo"
      data-channel={strip.channel}
      className="wf-promo"
    >
      <div data-testid="window-first-promo-head" className="wf-promo-h">
        <span data-testid="window-first-promo-kicker" className="wf-promo-k">
          {strip.topics.map((topic, index) => (
            <React.Fragment key={topic.key}>
              {index > 0 && (
                <span data-testid="window-first-promo-sep" aria-hidden="true">{' × '}</span>
              )}
              <span data-testid="window-first-promo-topic">{topic.label}</span>
            </React.Fragment>
          ))}
        </span>
        <span data-testid="window-first-promo-when" className="wf-promo-t">{strip.when}</span>
        {strip.time && (
          <span data-testid="window-first-promo-time" className="wf-promo-time">{strip.time}</span>
        )}
        {/* The design's flexible rule. Decorative, and it is what pushes nothing to the right —
            there is no right meta, so the rule simply closes the header. */}
        <span className="wf-promo-rule" aria-hidden="true" />
      </div>

      {/* Omitted entirely when no topic marks a headline quantity, rather than rendered as an empty
          band: a topic can carry no facts at all, and both of a coincidence's topics can. */}
      {figures.length > 0 && (
        <div data-testid="window-first-promo-figures" className="wf-promo-b">
          {figures.map((topic) => (
            <div key={topic.key} data-testid="window-first-promo-figure" className="wf-promo-fig">
              <span className="wf-promo-fig-k">{topic.figureLabel}</span>
              <span className="wf-promo-fig-v">{topic.figureValue}</span>
            </div>
          ))}
        </div>
      )}

      {showGo && (
        <div data-testid="window-first-promo-foot" className="wf-promo-foot">
          <button
            type="button"
            data-testid="window-first-promo-go"
            className="wf-promo-go"
            onClick={() => onOpenWindow(strip.windowKey)}
          >
            {`Go to ${strip.when}`}
            <span aria-hidden="true"> ↓</span>
          </button>
        </div>
      )}
    </div>
  );
}

WindowFirstPromotedStrip.propTypes = {
  strip: PropTypes.shape({
    windowKey: PropTypes.string.isRequired,
    when: PropTypes.string.isRequired,
    time: PropTypes.string,
    channel: PropTypes.string.isRequired,
    adjacent: PropTypes.bool,
    topics: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string.isRequired,
      label: PropTypes.string.isRequired,
      figureLabel: PropTypes.string,
      figureValue: PropTypes.string,
    })).isRequired,
  }).isRequired,
  onOpenWindow: PropTypes.func,
};
