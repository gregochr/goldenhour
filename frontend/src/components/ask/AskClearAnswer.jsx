import { useAsk } from '../../context/AskContext.jsx';
import { refShape } from './askShapes.js';
import { SETTLED_PHASES } from '../../utils/askModel.js';

/**
 * "Clear answer" — the one explicit way to END a conversation (the sheet and the dock both CLOSE
 * without ending it, so a mis-tap never bins an answer the reader was charged a question for).
 * It is the only way back to the Ready suggestions, which the conversation shows only in its empty
 * phase.
 *
 * <p>Drawn only while there is something settled to clear: never while an answer is still being
 * fetched, since clearing then would drop a charged answer. It moves focus to the question field
 * FIRST — the button unmounts when pressed, and a focused node that goes takes focus to
 * {@code <body>} with it, after which the surface's own Escape rule goes quiet.
 *
 * <p>Shared by the sheet and the dock, which used to be one copy and are now two.
 *
 * @param {object} props
 * @param {{current: ?HTMLElement}} props.inputRef the surface's question field
 */
export default function AskClearAnswer({ inputRef }) {
  const ask = useAsk();
  if (!SETTLED_PHASES.includes(ask.phase)) return null;
  const clearAnswer = () => {
    inputRef.current?.focus({ preventScroll: true });
    ask.clear();
  };
  return (
    <button
      type="button"
      className="wf-ask-clear"
      data-testid="ask-clear"
      onClick={clearAnswer}
    >
      Clear answer
    </button>
  );
}

AskClearAnswer.propTypes = {
  inputRef: refShape.isRequired,
};
