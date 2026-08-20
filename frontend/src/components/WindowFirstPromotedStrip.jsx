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
 * <h2>Two of the three original omissions are now conditional, and one is not</h2>
 *
 * <p>The strip promotes under two rules ({@code windowFirstPromoted.js}), and the descriptor says
 * which via {@code reason}. The refusals below were all written about a COINCIDENCE strip, where
 * the subject is a pair. Two of them turn out to be arguments about the pair rather than about the
 * element, and they lapse on a solo-rarity strip:
 *
 * <ul>
 *   <li><b>The "why" clause.</b> Refused because "the design's italic serif line explains the PAIR.
 *       No field says that … rendering one of the two as though it described both would be a
 *       sentence about half the strip presented as the whole of it." With ONE topic there is no
 *       half to mistake for the whole: {@code badge.detail} is, by definition, the sentence about
 *       the whole strip. So it renders on the rarity branch and is still refused on the
 *       coincidence branch — the discriminator is what lets both truths stand.</li>
 *   <li><b>The rarity line.</b> §2.6 ruled out the mock's "first coincidence since 2 Mar" as an
 *       unscheduled historical scan. Read precisely, that refuses a <em>backward</em> claim needing
 *       a scan of history. {@code rarityNote} is a <em>forward</em> claim read out of a seeded
 *       catalogue — no scan, no history, no delta between two observed things — and it is composed
 *       on the backend, so no number is parsed or re-formatted here.</li>
 *   <li><b>The right-hand meta stays refused, on both branches.</b> That objection was never about
 *       the pair: "61 coastal locations" is a count of our own data, which §6 bans outright, and
 *       nothing about a lone topic changes that. The design's "clear to the west — 31 of 57 sites"
 *       is the same species and is not built.</li>
 * </ul>
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
 * @param {function} [props.onOpenWindow] opens the promoted window's popup. Optional — without it
 *        the strip is a statement rather than a route.
 */
export default function WindowFirstPromotedStrip({ strip, onOpenWindow }) {
  const figures = strip.topics.filter((topic) => topic.figureValue);
  // ⚠️ NO adjacency gate any more, and its removal belongs to M2 (plan-matrix §6 M2.8). The gate
  // read `!strip.adjacent`, and its whole rationale was scroll-specific: the control scrolled to
  // the row directly beneath the strip, which has no visible effect (§6's ban on such controls).
  // The control now OPENS A DIALOG, which is visible wherever the window sits — so left in place
  // the gate hid the route for exactly the promoted topic most likely to be on the first window,
  // for as long as the strip survives (it goes at M5, D-1). `strip.adjacent` is still DERIVED by
  // `buildPromotedStrip`; nothing reads it, and it goes with the strip.
  const showGo = Boolean(onOpenWindow);
  // The foot is not the button's own container. A warning must be unconditional — that is what
  // "non-dismissible" means — and the original gate hid the whole foot whenever the promoted window
  // was already the next card on the pane, which on an eclipse evening is exactly the case.
  const showFoot = showGo || Boolean(strip.safetyNote);

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
              {/* The spaces sit OUTSIDE the hidden span, and that is not cosmetic. JSX drops the
                  newline-only whitespace between sibling elements, so with ` × ` wholly inside an
                  `aria-hidden` subtree the only boundary between two topic names is pruned along
                  with it and a browse-mode buffer reads "King tideAurora". Every other decorative
                  glyph in this arm is a prefix or a suffix whose removal leaves its label whole;
                  this is the first used as the boundary BETWEEN two labels. `textContent` is
                  unchanged either way, which is why the pinned header string still holds. */}
              {index > 0 && (
                <>
                  {' '}
                  <span data-testid="window-first-promo-sep" aria-hidden="true">×</span>
                  {' '}
                </>
              )}
              <span data-testid="window-first-promo-topic">{topic.label}</span>
            </React.Fragment>
          ))}
        </span>
        <span data-testid="window-first-promo-when" className="wf-promo-t">{strip.when}</span>
        {strip.time && (
          <span data-testid="window-first-promo-time" className="wf-promo-time">{strip.time}</span>
        )}
        {/* The design's flexible rule. Decorative. With no rarity line it closes the header; with
            one it separates the title group from it, which is the job it does in the mock. */}
        <span className="wf-promo-rule" aria-hidden="true" />
        {strip.rarityNote && (
          <span data-testid="window-first-promo-rare" className="wf-promo-rare">
            {strip.rarityNote}
          </span>
        )}
      </div>

      {/* Omitted entirely when no topic marks a headline quantity, rather than rendered as an empty
          band: a topic can carry no facts at all, and both of a coincidence's topics can. */}
      {figures.length > 0 && (
        <div data-testid="window-first-promo-figures" className="wf-promo-b">
          {figures.map((topic) => (
            <div key={topic.key} data-testid="window-first-promo-figure" className="wf-promo-fig">
              {/* Only when the fact carries its own lead-in. A keyless headline fact — which is what
                  aurora and NLC emit — is self-describing, and filling the slot with the topic's
                  name would print that name twice on one small element. */}
              {topic.figureLabel && (
                <span className="wf-promo-fig-k">{topic.figureLabel}</span>
              )}
              <span className="wf-promo-fig-v">{topic.figureValue}</span>
            </div>
          ))}
        </div>
      )}

      {/* The promoted topic's own sentence, in the serif italic this arm already uses for an
          editorial line (`.tr-foot .phrase`). Solo-rarity strips only — see the class Javadoc. */}
      {strip.why && (
        <div data-testid="window-first-promo-why" className="wf-promo-why">{strip.why}</div>
      )}

      {showFoot && (
        <div data-testid="window-first-promo-foot" className="wf-promo-foot">
          {/* First item, always visible, no dismiss control and no persisted suppression. This is
              the one topic in the catalogue whose subject is the sun itself, and the app is
              actively telling someone to point a camera at it. */}
          {strip.safetyNote && (
            <span data-testid="window-first-promo-warn" className="wf-promo-warn">
              <span aria-hidden="true">⚠ </span>
              {strip.safetyNote}
            </span>
          )}
          {showGo && (
            <button
              type="button"
              data-testid="window-first-promo-go"
              className="wf-promo-go"
              onClick={() => onOpenWindow(strip.windowKey)}
            >
              {`Go to ${strip.when}`}
              <span aria-hidden="true"> ↓</span>
            </button>
          )}
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
    reason: PropTypes.oneOf(['coincidence', 'rarity']),
    rarityNote: PropTypes.string,
    why: PropTypes.string,
    safetyNote: PropTypes.string,
    topics: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string.isRequired,
      label: PropTypes.string.isRequired,
      figureLabel: PropTypes.string,
      figureValue: PropTypes.string,
    })).isRequired,
  }).isRequired,
  onOpenWindow: PropTypes.func,
};
