import PropTypes from 'prop-types';

/**
 * A one-line, dismissible note under a view's header (`docs/engineering/one-screen-plan.md` D4): "That sunrise has
 * passed", "Not forecast yet". It states a fact about what the reader asked for and goes away when dismissed.
 *
 * <p>A polite status region so the line is read when it arrives with the view; the dismiss button names itself.
 *
 * @param {object} props
 * @param {string} props.text the sentence
 * @param {function(): void} props.onDismiss removes the note
 */
export default function ScreenNote({ text, onDismiss }) {
  return (
    <div role="status" data-testid="screen-note" className="wf-screen-note font-mono">
      <span>{text}</span>
      <button
        type="button"
        data-testid="screen-note-dismiss"
        className="wf-screen-note-x"
        aria-label="Dismiss this note"
        onClick={onDismiss}
      >
        <span aria-hidden="true">✕</span>
      </button>
    </div>
  );
}

ScreenNote.propTypes = {
  text: PropTypes.string.isRequired,
  onDismiss: PropTypes.func.isRequired,
};
