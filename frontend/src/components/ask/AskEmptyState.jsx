import { useId } from 'react';
import PropTypes from 'prop-types';
import ProPill from '../shared/ProPill.jsx';
import { PRO_DAILY_LIMIT } from '../../utils/askModel.js';
import AskSuggestion from './AskSuggestion.jsx';

/**
 * "N of M own questions left today · Pro: 30 a day" (a free reader) or "N of M left today" (PRO and
 * ADMIN) — the server's figures, or nothing at all before they are known.
 */
function AllowanceLine({ ask }) {
  const { allowance, isPro } = ask;
  if (!allowance.loaded || allowance.enabled === false) return null;
  const { left, limit } = allowance;
  if (isPro) {
    return (
      <p className="wf-ask-usage" data-testid="ask-allowance">{`${left} of ${limit} left today`}</p>
    );
  }
  return (
    <p className="wf-ask-usage" data-testid="ask-allowance">
      {left > 0 ? `${left} of ${limit} own questions left today` : 'No own questions left today'}
      {' · '}
      <ProPill />
      <span className="sr-only">:</span>
      {` ${PRO_DAILY_LIMIT} a day`}
    </p>
  );
}

AllowanceLine.propTypes = { ask: PropTypes.object.isRequired };

/** State 1 — the suggestions, then the allowance. */
export default function AskEmptyState({ questions, runLabel, ask, onOpen }) {
  const headingId = useId();
  return (
    <>
      {questions.length > 0 && (
        <div role="group" aria-labelledby={headingId} data-testid="ask-ready-list">
          <p className="wf-ask-k wf-ask-ready-h" id={headingId}>
            {runLabel ? `Ready from the ${runLabel} run · free` : 'Ready questions · free'}
          </p>
          <div className="wf-ask-sugs">
            {questions.map((q) => (
              <AskSuggestion key={q.id} question={q} onOpen={onOpen} />
            ))}
          </div>
        </div>
      )}
      <AllowanceLine ask={ask} />
      {ask.allowance.enabled && ask.allowance.typedAvailable === false && (
        // Without this a reader sees "3 of 3 left" beside a disabled field and no reason.
        <p className="wf-ask-usage" data-testid="ask-typed-off">
          Typed questions are unavailable right now. Ready questions still work.
        </p>
      )}
    </>
  );
}

AskEmptyState.propTypes = {
  questions: PropTypes.arrayOf(PropTypes.object).isRequired,
  runLabel: PropTypes.string,
  ask: PropTypes.object.isRequired,
  onOpen: PropTypes.func.isRequired,
};
