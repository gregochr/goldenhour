import PropTypes from 'prop-types';
import { KIND } from '../../utils/askModel.js';

/** The footer's words: which run the answer came from, and what it cost the reader. */
function footerFor(answer) {
  const run = answer.runLabel ? ` from the ${answer.runLabel} run` : '';
  if (answer.kind === KIND.CANT) return 'No question used';
  if (answer.kind === KIND.READY) return `Ready answer${run} · no question used`;
  if (!answer.charged) return `Answered${run} · no question used`;
  if (answer.allowanceLeft !== null && answer.allowanceLimit !== null) {
    return `Answered${run} · ${answer.allowanceLeft} of ${answer.allowanceLimit} left today`;
  }
  return `Answered${run}`;
}

/** The answer's footer, under an answer and under a "Not in the forecast" reply alike. */
export default function AskAnswerFoot({ answer }) {
  return <p className="wf-ask-foot" data-testid="ask-footer">{footerFor(answer)}</p>;
}

AskAnswerFoot.propTypes = {
  answer: PropTypes.shape({
    kind: PropTypes.string,
    runLabel: PropTypes.string,
    charged: PropTypes.bool,
    allowanceLeft: PropTypes.number,
    allowanceLimit: PropTypes.number,
  }).isRequired,
};
