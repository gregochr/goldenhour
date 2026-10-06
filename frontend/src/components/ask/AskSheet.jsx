import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import BottomSheet from '../BottomSheet.jsx';
import AskClearAnswer from './AskClearAnswer.jsx';
import AskConversation from './AskConversation.jsx';
import AskInputRow from './AskInputRow.jsx';
import { useAsk } from '../../context/AskContext.jsx';
import useAskRequestContext from '../../hooks/useAskRequestContext.js';

/**
 * Ask PhotoCast's sheet — the answer surface below 1024px (plan §2.6): a {@link BottomSheet} in its
 * `tall` size (the visual viewport minus 24px) over the app's shared scrim, holding the conversation
 * and, at its foot, the question field.
 *
 * <p>It is THE modal while it is open. The shell makes the whole app container {@code inert}, refuses
 * to open anything else, and closes it on any tab change; this component only draws. It is mounted by
 * the shell and holds no open state of its own.
 *
 * <p><b>The handle and the ✕ are {@code BottomSheet}'s</b> (a 40 × 4 drag handle and a close button at
 * the top right), not a second copy drawn here; the strip under the button is reserved so the
 * conversation's first row — the "Asking about" chips — never sits under it. The scrim is the app's
 * one dialog scrim (`.app-scrim`, 74%), not the mock's 50%: every sheet and modal shares it.
 *
 * <p><b>Closing keeps the conversation</b> (the ✕, the scrim and Escape all just close — plan §2.6,
 * §2.8); <b>"Clear answer" ends it</b>, and is the only way back to the Ready suggestions, which the
 * conversation shows only in its empty phase. It moves focus to the field first: the button
 * unmounts when pressed, and a focused node that goes takes focus to {@code <body>} with it.
 *
 * <p><b>Opening focuses the question field</b> (effect order is the reason it is safe: this component
 * is the PARENT of the sheet, so the sheet's own {@code useDialogFocus} has already captured the
 * opener as its return address before this effect moves focus) — unless typed questions are off, in
 * which case the field can take no text and focus is left on the dialog, which says what it is.
 * Closing returns focus to the opener, or to {@code restoreFallback}'s answer when the opener cannot
 * take it, which is routine here: the page behind the sheet is {@code inert}, and a tap on iOS never
 * focused the opener in the first place.
 *
 * @param {object} props
 * @param {boolean} props.open
 * @param {function(): void} props.onClose the ✕, the scrim and Escape all call this
 * @param {'map'|'plan'|'coming-up'} props.view the tab the suggestions are offered for and the view the
 *        question is sent with
 * @param {string} props.viewLabel the view chip's text, e.g. "Plan · all regions"
 * @param {function(object): React.ReactNode} [props.pickActions] controls for each pick card's row
 * @param {function(): ?Element} [props.restoreFallback] where focus goes when the opener cannot take it
 */
export default function AskSheet({
  open, onClose, view, viewLabel, pickActions = undefined, restoreFallback = undefined,
}) {
  const ask = useAsk();
  const inputRef = useRef(null);
  const requestContext = useAskRequestContext(view, viewLabel);
  const { typedDisabled } = ask;
  useEffect(() => {
    if (open && !typedDisabled) inputRef.current?.focus({ preventScroll: true });
  }, [open, typedDisabled]);

  return (
    <BottomSheet
      open={open}
      onClose={onClose}
      size="tall"
      closeOnEscape
      reserveCloseStrip
      label="Ask PhotoCast"
      restoreFallback={restoreFallback}
      footer={<AskInputRow inputRef={inputRef} view={view} viewLabel={viewLabel} />}
    >
      <AskConversation
        view={view}
        scope={requestContext.scope}
        viewLabel={requestContext.viewLabel}
        windowLabel={requestContext.windowLabel}
        windowId={requestContext.windowId}
        regionIds={requestContext.regionIds}
        pickActions={pickActions}
      />
      <AskClearAnswer inputRef={inputRef} />
    </BottomSheet>
  );
}

AskSheet.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  view: PropTypes.oneOf(['map', 'plan', 'coming-up']).isRequired,
  viewLabel: PropTypes.string.isRequired,
  pickActions: PropTypes.func,
  restoreFallback: PropTypes.func,
};
