import PropTypes from 'prop-types';

/**
 * The earlier exchanges of the conversation, collapsed (`docs/engineering/ask-thread-plan.md` §2.5, §5 Q1):
 * each is the reader's question as their bubble and the answer's summary as plain text. Nothing in an
 * earlier exchange is interactive — no cards, no chips, no "Plan this", no tide, no star: an earlier
 * answer's picks belong to the LIVE answer alone, and a star drawn beside a summary written against
 * another run of the forecast is exactly what the thread must not show. The reader reaches those places
 * by asking ("rank those"), not by scrolling back into stale cards.
 *
 * <p><b>Not a live region, and not announced.</b> These are words the reader has already been given
 * (the answer was announced when it landed, in the conversation's one live region); an answer moving
 * from there into this list when the next question is sent must not be read out a second time. The
 * list is a plain {@code <ol>}, each exchange an {@code <li>} whose two parts carry visually-hidden
 * labels, so a screen reader moving through it hears who said what.
 *
 * @param {object} props
 * @param {Array<{question: string, summary: string, answerId: number}>} props.exchanges the earlier
 *        exchanges, oldest first (`AskContextValue.history`)
 */
export default function AskThreadHistory({ exchanges }) {
  return (
    // `list-style: none` makes Safari drop the list role; the explicit role keeps it a list there.
    // eslint-disable-next-line jsx-a11y/no-redundant-roles
    <ol className="wf-ask-thread" role="list" aria-label="Earlier in this conversation" data-testid="ask-thread">
      {exchanges.map((exchange) => (
        <li key={exchange.answerId} className="wf-ask-thread-x" data-testid="ask-thread-exchange">
          <div className="wf-ask-yq" data-testid="ask-thread-question">
            <span className="sr-only">You asked: </span>
            {exchange.question}
          </div>
          <p className="wf-ask-thread-sum" data-testid="ask-thread-summary">
            <span className="sr-only">Answer: </span>
            {exchange.summary}
          </p>
        </li>
      ))}
    </ol>
  );
}

AskThreadHistory.propTypes = {
  exchanges: PropTypes.arrayOf(PropTypes.shape({
    question: PropTypes.string.isRequired,
    summary: PropTypes.string.isRequired,
    answerId: PropTypes.number.isRequired,
  })).isRequired,
};
