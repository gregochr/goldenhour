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
 * plan view's carry different test-ids and one extra class) and in whether the tide's spoken clause
 * leads with its own "Tide: ". Every surface says Tide exactly once (owner decision, 2026-10-08): the
 * card has no visible label, so its clause carries the word; the plan view's cell has a {@code <dt>}
 * that already says Tide, so its clause is bare — a screen reader reading straight through would
 * otherwise say it twice.
 */

/**
 * "Sat 06:12" — the weekday and the served event time, or the weekday alone with no time. A NIGHT pick
 * reads "Tonight · 21:40–05:10": the night's own word (the Map pill's) and its served dark window, or the
 * word alone when the window is not served.
 *
 * @param {{dayWord: string, eventTime: ?string, night: ?boolean, nightWord: ?string,
 *        nightWindow: ?string}} card
 * @returns {string}
 */
export function pickWhen(card) {
  if (card.night) return card.nightWindow ? `${card.nightWord} · ${card.nightWindow}` : card.nightWord;
  return card.eventTime ? `${card.dayWord} ${card.eventTime}` : card.dayWord;
}

/** The two figures a night card can carry, primary first: the pick's own kind, then the other if held. */
function nightScores(card) {
  const sky = { key: 'astro', stars: card.astroStars, word: 'sky', spoken: 'Sky forecast' };
  const aurora = { key: 'aurora', stars: card.auroraStars, word: 'aurora', spoken: 'Aurora forecast' };
  const ordered = card.kind === 'aurora' ? [aurora, sky] : [sky, aurora];
  return ordered.filter((figure) => figure.stars != null);
}

/**
 * A NIGHT pick's stars (`docs/engineering/ask-night-plan.md` §2.6): "4★ sky" for the astro forecast and, beside
 * it when the reader has one, "aurora 3★" — the pick's own kind first. A night has no verdict word, so the
 * kind is what stands where the verdict does. The visible forms are {@code aria-hidden} and a spoken form
 * says each figure once ("Sky forecast 4 stars"), as {@link PickScore} does for the solar star.
 *
 * @param {object} props
 * @param {object} props.card a night card of {@code buildPickCards}
 * @param {string} props.testId the group's {@code data-testid}; each pill's is {@code `${testId}-sky|aurora`}
 */
export function NightScore({ card, testId }) {
  const figures = nightScores(card);
  if (figures.length === 0) return null;
  return (
    <span className="wf-ask-scs" data-testid={testId}>
      {figures.map((figure) => (
        <span
          key={figure.key}
          className="wf-ask-sc wf-ask-sc-night"
          data-kind={figure.key}
          data-testid={`${testId}-${figure.key === 'astro' ? 'sky' : 'aurora'}`}
        >
          <span aria-hidden="true">
            {figure.key === 'astro' ? `${figure.stars}★ ${figure.word}` : `${figure.word} ${figure.stars}★`}
          </span>
          <span className="sr-only">{`${figure.spoken} ${figure.stars} ${starsWord(figure.stars)}`}</span>
        </span>
      ))}
    </span>
  );
}

NightScore.propTypes = {
  card: PropTypes.shape({
    kind: PropTypes.string,
    astroStars: PropTypes.number,
    auroraStars: PropTypes.number,
  }).isRequired,
  testId: PropTypes.string.isRequired,
};

/** The kind badge's word for a night card: "ASTRO" or "AURORA" — the Map's own night kind chips. */
export function nightKindWord(card) {
  return card.kind === 'aurora' ? 'AURORA' : 'ASTRO';
}

/**
 * The verdict word and star, in the score pill. The star is drawn in the visible ("· 4★") and spoken
 * (", 4 stars") forms side by side, the glyph appears only in the visible form, so the spoken one says "stars" once.
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
          <span aria-hidden="true">{` · ${card.rating}★`}</span>
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
 * @param {boolean} [props.labelled=true] lead the spoken clause with "Tide: "; false where an adjacent
 *        label (the plan view's {@code <dt>}) already says it
 */
export function PickTide({ tide, labelled = true }) {
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
        <span className="sr-only">{labelled ? `Tide: ${tide.clause}` : tide.clause}</span>
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
  labelled: PropTypes.bool,
};
