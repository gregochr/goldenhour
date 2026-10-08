import { useId } from 'react';
import PropTypes from 'prop-types';
import { resolveSuggestions } from '../../utils/askModel.js';
import AskAnswerFoot from './AskAnswerFoot.jsx';
import AskSuggestion from './AskSuggestion.jsx';

/** State 5 — "Not in the forecast". */
export default function AskCantAnswer({ ask, questions, onOpen }) {
  const { answer } = ask;
  const headingId = useId();
  const tries = resolveSuggestions(answer.try, questions);
  return (
    <>
      <div className="wf-ask-cant" data-testid="ask-cant">
        <span className="wf-ask-cant-k">Not in the forecast</span>
        <span className="wf-ask-cant-t" data-testid="ask-summary">{answer.summary}</span>
        {answer.missing && (
          <span className="wf-ask-cant-m" data-testid="ask-missing">
            {`PhotoCast doesn’t have: ${answer.missing}`}
          </span>
        )}
      </div>
      {tries.length > 0 && (
        <div role="group" aria-labelledby={headingId} data-testid="ask-try">
          <p className="wf-ask-k wf-ask-ready-h" id={headingId}>Try asking</p>
          <div className="wf-ask-sugs">
            {tries.map((q) => (
              <AskSuggestion key={q.id} question={q} onOpen={onOpen} />
            ))}
          </div>
        </div>
      )}
      <AskAnswerFoot answer={answer} />
    </>
  );
}

AskCantAnswer.propTypes = {
  ask: PropTypes.object.isRequired,
  questions: PropTypes.arrayOf(PropTypes.object).isRequired,
  onOpen: PropTypes.func.isRequired,
};
