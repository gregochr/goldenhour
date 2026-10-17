import PropTypes from 'prop-types';

/** The Ready tag and its question — one tappable row. */
export default function AskSuggestion({ question, onOpen }) {
  return (
    <button
      type="button"
      className="wf-ask-sug"
      data-testid={`ask-ready-${question.id}`}
      onClick={() => onOpen(question)}
    >
      <span>{question.text}</span>
      <span className="wf-ask-rd">Ready</span>
    </button>
  );
}

AskSuggestion.propTypes = {
  question: PropTypes.shape({ id: PropTypes.string, text: PropTypes.string }).isRequired,
  onOpen: PropTypes.func.isRequired,
};
