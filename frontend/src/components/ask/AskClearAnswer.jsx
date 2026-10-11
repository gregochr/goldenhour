import { useId } from 'react';
import { useAsk } from '../../context/AskContext.jsx';
import { refShape } from './askShapes.js';
import { SETTLED_PHASES } from '../../utils/askModel.js';

/** The button's words, here and on the phone peek's ✕: it ends the conversation, and says so. */
export const NEW_CONVERSATION_LABEL = 'New conversation';

/** Its accessible description: what pressing it costs, and what it does not. */
export const NEW_CONVERSATION_HINT = 'Ends this conversation. Your earlier questions are kept until then.';

/**
 * "New conversation" — the one explicit way to END a conversation (the sheet and the dock both CLOSE
 * without ending it, so a mis-tap never bins an answer the reader was charged a question for).
 * It is the only way back to the Ready suggestions, which the conversation shows only in its empty
 * phase.
 *
 * <p>It used to read "Clear answer" ("Clear" once a thread stood above the answer), which sat in the
 * conversation's foot just above the question field and read as housekeeping on ONE answer; a reader
 * who tapped it ended the whole thread and a follow-up then went out as a first question. The words
 * say what it does, on a first answer and over a thread alike, and its accessible description says that
 * the earlier questions are kept only until it is pressed.
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
  const hintId = useId();
  if (!SETTLED_PHASES.includes(ask.phase)) return null;
  const clearAnswer = () => {
    inputRef.current?.focus({ preventScroll: true });
    ask.clear();
  };
  return (
    <>
      <button
        type="button"
        className="wf-ask-clear"
        data-testid="ask-clear"
        aria-describedby={hintId}
        onClick={clearAnswer}
      >
        {NEW_CONVERSATION_LABEL}
      </button>
      <span id={hintId} className="sr-only">{NEW_CONVERSATION_HINT}</span>
    </>
  );
}

AskClearAnswer.propTypes = {
  inputRef: refShape.isRequired,
};
