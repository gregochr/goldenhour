import PropTypes from 'prop-types';

/** The failure state — the reason in the server's words, and a way to try again. */
export default function AskErrorState({ ask, onRetry }) {
  const { error } = ask;
  return (
    <div className="wf-ask-error" data-testid="ask-error">
      <p className="wf-ask-error-t" data-testid="ask-error-text">{error.message}</p>
      <button type="button" className="wf-ask-retry" data-testid="ask-retry" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}

AskErrorState.propTypes = {
  ask: PropTypes.object.isRequired,
  onRetry: PropTypes.func.isRequired,
};
