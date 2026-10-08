import { useId, useState } from 'react';
import PropTypes from 'prop-types';
import { useAsk } from '../../context/AskContext.jsx';
import useAskRequestContext from '../../hooks/useAskRequestContext.js';
import { refShape } from './askShapes.js';
import { READY_ONLY_PLACEHOLDER } from '../../utils/askModel.js';

/** The server's own length limit (`POST /api/ask` answers 400 `INVALID` beyond it). */
const MAX_QUESTION_LENGTH = 200;

/**
 * The question field and its ↑ button — the sheet's input row (`Ask Everywhere.html`'s `.inrow`),
 * and the row F2's dock and F4's peek row repeat, which is why it is a component of its own.
 *
 * <p>Mounted only while its surface is open, so its text resets with every opening.
 *
 * <p><b>Submits on Enter and on ↑, and never while an answer is being fetched</b>: the provider has no
 * in-flight guard — a second question would supersede the first and drop a charged answer — so this
 * is the guard. The field is cleared on submit and the text PUT BACK when the question was refused or
 * ignored, so a typo costs the reader nothing to correct; an answer, a failure (which has its own
 * "Try again") and a superseded question leave it empty.
 *
 * <p><b>"Disabled" is {@code readOnly} + {@code aria-disabled}, deliberately not {@code disabled}.</b>
 * {@code typedDisabled} can flip while the reader is typing (a refusal that means "none today"), and
 * a {@code disabled} control that held focus drops it to {@code <body>} — the defect this app has
 * fixed five times on the Map — after which the surface's own keys go quiet for a keyboard reader. A
 * read-only field keeps focus, takes no text, raises no keyboard on iOS and is announced as dimmed.
 * The reason ("Ready questions only today") is the placeholder AND a hidden sentence the field
 * {@code aria-describedby}, because a placeholder is not reliably announced on a read-only field.
 *
 * <p><b>16px text</b>: iOS Safari zooms the page on focusing a field set smaller (the stylesheet pins
 * it). The accessible name is the placeholder's words, so a voice-control user who says what they
 * see finds it.
 *
 * <p>The refusal sentence is NOT rendered here — {@code AskConversation} renders {@code inputError}
 * itself, in its own live region, and a second copy would be read out twice.
 *
 * <p><b>The context a question is sent with is the surface's, resolved at the moment of asking</b>
 * ({@code useAskRequestContext}): on the Map tab the region in scope and the window the pill shows
 * (the chip the reader can remove), elsewhere "all regions". The provider stores it on the answer.
 *
 * <p><b>A "Next question" row above the field whenever the answer on screen was asked in another
 * context, or the Map has a window the reader could remove.</b> The chips over an answer say what was
 * SENT and cannot be edited; the NEXT question is sent with the surface's context of the moment — and
 * choosing a pick moves the Map's window, so after one the next question would carry a window nothing on
 * screen named. The row says it, and its ✕ takes the window out of that question. It moves focus to the
 * field first (the ✕ unmounts with the chip, and focus on a node that goes falls to {@code <body>}).
 * With nothing asked yet the live chips are the conversation's own, at its top, and this row is not drawn.
 *
 * @param {object} props
 * @param {React.Ref} props.inputRef the input's node, so the surface can move focus to it
 * @param {'map'|'plan'|'coming-up'} props.view the view the question is sent with
 * @param {string} [props.viewLabel] the surface's own chip text, for the views the Map says nothing about
 */
export default function AskInputRow({ inputRef, view, viewLabel = undefined }) {
  const ask = useAsk();
  const requestContext = useAskRequestContext(view, viewLabel);
  const reasonId = useId();
  const [text, setText] = useState('');
  const busy = ask.phase === 'busy';
  const locked = ask.typedDisabled;
  const empty = text.trim() === '';
  // What the next question carries, against what the answer on screen was asked in.
  const { asked } = ask;
  const nextWindowId = requestContext.windowLabel && ask.removedWindow !== requestContext.windowId
    ? requestContext.windowId
    : null;
  const showNext = ask.phase !== 'empty' && asked != null && (
    nextWindowId !== null
    || (asked.windowId ?? null) !== nextWindowId
    || (asked.viewLabel || null) !== (requestContext.viewLabel || null)
  );
  const removeNextWindow = () => {
    // The ✕ goes with the chip it sits on: focus moves to the field before it does.
    inputRef.current?.focus({ preventScroll: true });
    ask.removeContextWindow(requestContext.windowId);
  };

  const submit = async (event) => {
    event.preventDefault();
    if (locked || busy) return;
    const question = text.trim();
    if (question === '') return;
    setText('');
    const outcome = await ask.askTyped(question, {
      regionIds: requestContext.regionIds,
      windowId: requestContext.windowId,
      windowLabel: requestContext.windowLabel,
      view,
      viewLabel: requestContext.viewLabel,
    });
    if (outcome === 'refused' || outcome === 'ignored') {
      // Only if the reader has not started typing something else meanwhile.
      setText((current) => (current === '' ? question : current));
    }
  };

  return (
    <>
      {showNext && (
        <div
          className="wf-ask-ctx wf-ask-next"
          role="group"
          aria-label="Next question"
          data-testid="ask-next-context"
        >
          <span className="wf-ask-k" aria-hidden="true">Next question</span>
          <span className="wf-ask-cx" data-testid="ask-next-view">{requestContext.viewLabel ?? viewLabel}</span>
          {nextWindowId !== null && (
            <span className="wf-ask-cx" data-testid="ask-next-window">
              {requestContext.windowLabel}
              <button
                type="button"
                className="wf-ask-cx-x"
                data-testid="ask-next-window-remove"
                aria-label={`Remove ${requestContext.windowLabel} from the next question`}
                onClick={removeNextWindow}
              >
                <span aria-hidden="true">✕</span>
              </button>
            </span>
          )}
        </div>
      )}
      <form className="wf-ask-in" data-testid="ask-input-row" onSubmit={submit}>
        <input
          ref={inputRef}
          type="text"
          className="wf-ask-in-field"
          data-testid="ask-input"
          aria-label="Ask about the forecasts"
          aria-describedby={locked ? reasonId : undefined}
          placeholder={locked ? READY_ONLY_PLACEHOLDER : 'Ask about the forecasts…'}
          value={locked ? '' : text}
          readOnly={locked}
          aria-disabled={locked || undefined}
          maxLength={MAX_QUESTION_LENGTH}
          autoComplete="off"
          enterKeyHint="send"
          onChange={(event) => { if (!locked) setText(event.target.value); }}
        />
        {locked && <span id={reasonId} className="sr-only">{READY_ONLY_PLACEHOLDER}</span>}
        <button
          type="submit"
          className="wf-ask-in-go"
          data-testid="ask-send"
          aria-label="Send question"
          aria-disabled={locked || busy || empty || undefined}
        >
          <span aria-hidden="true">↑</span>
        </button>
      </form>
    </>
  );
}

AskInputRow.propTypes = {
  inputRef: refShape.isRequired,
  view: PropTypes.oneOf(['map', 'plan', 'coming-up']).isRequired,
  viewLabel: PropTypes.string,
};
