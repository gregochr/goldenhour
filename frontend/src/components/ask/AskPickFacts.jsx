import PropTypes from 'prop-types';
import TideWave from '../map/TideWave.jsx';
import { starsWord } from '../../utils/askModel.js';
import { STATE_WORD } from '../../utils/windowFirstRows.js';

/**
 * The facts a pick card and its "Plan this" view both print about one pick, written once: the verdict
 * word with the star, the tide glyph with its state word and clause, and the "Sat 06:12" time. Each is
 * a join over the card {@code buildPickCards} built from served data; nothing here is computed.
 *
 * <p>The two hosts differ only in the wrapper they draw around a fact (the card's score pill and the
 * plan view's carry different test-ids and one extra class). The tide's spoken clause reads
 * "Tide: …" on both: the plan view's cell already says Tide visibly, but a screen reader meets the
 * clause on its own inside the cell's value, and the two hosts said it differently only by history
 * (owner decision, 2026-10-08: "Tide:" everywhere).
 */

/**
 * "Sat 06:12" — the weekday and the served event time, or the weekday alone with no time.
 *
 * @param {{dayWord: string, eventTime: ?string}} card
 * @returns {string}
 */
export function pickWhen(card) {
  return card.eventTime ? `${card.dayWord} ${card.eventTime}` : card.dayWord;
}

/**
 * The verdict word and star, in the score pill. The star is drawn in the visible ("· 4") and spoken
 * (", 4 stars") forms side by side, since the visible one is a bare figure.
 *
 * @param {object} props
 * @param {object} props.card a card of {@code buildPickCards}
 * @param {string} props.testId the pill's {@code data-testid}
 * @param {string} [props.className] extra classes after the pill's own {@code wf-ask-sc}
 */
export function PickScore({ card, testId, className = undefined }) {
  return (
    <span
      className={className ? `wf-ask-sc ${className}` : 'wf-ask-sc'}
      data-tier={card.verdict}
      data-testid={testId}
    >
      {card.verdictLabel}
      {card.rating != null && (
        <>
          <span aria-hidden="true">{` · ${card.rating}`}</span>
          <span className="sr-only">{`, ${card.rating} ${starsWord(card.rating)}`}</span>
        </>
      )}
    </span>
  );
}

PickScore.propTypes = {
  card: PropTypes.shape({
    verdict: PropTypes.string.isRequired,
    verdictLabel: PropTypes.string.isRequired,
    rating: PropTypes.number,
  }).isRequired,
  testId: PropTypes.string.isRequired,
  className: PropTypes.string,
};

/**
 * The tide fact's contents: the wave glyph (a letter for a match, an arrow for a miss — the map
 * chip's own), the served state word, and the accessible clause. The visible words are
 * {@code aria-hidden} and the clause stands in for them, so the one fact is said once.
 *
 * <p>A fragment, not a wrapper: the card puts it in its {@code wf-ask-tide} span, the plan view in the
 * Tide cell's {@code dd}.
 *
 * @param {object} props
 * @param {{tier: string, state: ?string, shortfall: ?string, clause: ?string}} props.tide
 */
export function PickTide({ tide }) {
  return (
    <>
      <TideWave
        className="wf-ask-tide-wave"
        shortfall={tide.tier === 'miss' ? tide.shortfall : null}
        state={tide.tier === 'match' ? tide.state : null}
      />
      {STATE_WORD[tide.state] && (
        <span aria-hidden="true">{` ${STATE_WORD[tide.state]}`}</span>
      )}
      {tide.clause && (
        <span className="sr-only">{`Tide: ${tide.clause}`}</span>
      )}
    </>
  );
}

PickTide.propTypes = {
  tide: PropTypes.shape({
    tier: PropTypes.oneOf(['match', 'miss']).isRequired,
    state: PropTypes.string,
    shortfall: PropTypes.string,
    clause: PropTypes.string,
  }).isRequired,
};
