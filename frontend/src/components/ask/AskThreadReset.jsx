import PropTypes from 'prop-types';

/** What the reader is told when the server ended the thread (`docs/engineering/ask-thread-plan.md` §5 Q3). */
export const THREAD_RESET_LINE = 'The forecast has updated since your last question — this is a fresh answer.';

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
    <p className="wf-ask-thread-reset" data-testid="ask-thread-reset">{THREAD_RESET_LINE}</p>
  );
}

AskThreadReset.propTypes = {
  ask: PropTypes.shape({ resetReason: PropTypes.string }).isRequired,
};
