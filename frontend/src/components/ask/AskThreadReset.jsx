import PropTypes from 'prop-types';

/** What the reader is told when the server ended the thread (`docs/engineering/ask-thread-plan.md` §5 Q3). */
export const THREAD_RESET_LINE = 'The forecast has updated since your last question — this is a fresh answer.';

/**
 * ...and when the NIGHT forecast is what moved (`docs/engineering/ask-night-plan.md` §2.2): the server's
 * {@code threadResetReason} is {@code "night forecast updated"}, because a night answer's claims (a clear
 * sky, an alert level) are not the briefing's and go stale on their own clock.
 */
export const NIGHT_THREAD_RESET_LINE = 'The night forecast has updated since your last question — this is a fresh answer.';

/** The server's {@code threadResetReason} for a night forecast that moved. */
export const NIGHT_RESET_REASON = 'night forecast updated';

/**
 * The sentence for a reset reason: the night's own for {@link NIGHT_RESET_REASON}, else the forecast's
 * (the reason is the server's and this is the client's wording, so any other reason reads as before).
 *
 * @param {?string} reason the conversation's {@code resetReason}
 * @returns {string}
 */
export function threadResetLine(reason) {
  return reason === NIGHT_RESET_REASON ? NIGHT_THREAD_RESET_LINE : THREAD_RESET_LINE;
}

/**
 * The one line above a fresh answer that followed a thread the server dropped because the forecast
 * moved: "closer than the one you gave me" is meaningless against another run, so the question was
 * answered from nothing, and the reader is told so rather than left to wonder where the earlier answers
 * went. Drawn only while {@code resetReason} is set; the next question clears it. It sits INSIDE the
 * conversation's live region with the answer, so it is announced with it, once.
 *
 * @param {object} props
 * @param {{resetReason: ?string}} props.ask the Ask context's value
 */
export default function AskThreadReset({ ask }) {
  if (!ask.resetReason) return null;
  return (
    <p className="wf-ask-thread-reset" data-testid="ask-thread-reset">{threadResetLine(ask.resetReason)}</p>
  );
}

AskThreadReset.propTypes = {
  ask: PropTypes.shape({ resetReason: PropTypes.string }).isRequired,
};
