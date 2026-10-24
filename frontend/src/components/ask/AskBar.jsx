import PropTypes from 'prop-types';
import { refShape } from './askShapes.js';

/**
 * The phone's Ask entry on Plan and Coming up: a 48px bar fixed above the bottom of the screen
 * (design README, "Where Ask lives"; `Ask Everywhere.html` Option B's `.askbar`).
 *
 * <p>It is a BUTTON that opens the sheet, not a field: the input lives in the sheet, where there is
 * room for the answer above it and where {@code useVisualViewportHeight} can lift it clear of the
 * keyboard. Pressing it here never types anything.
 *
 * <p><b>It stays mounted while the sheet is open</b> (the mock hides it): it is the sheet's return
 * address, and {@code useDialogFocus} sends focus back to a node that must still exist when the
 * sheet closes. The sheet's scrim covers it.
 *
 * <p>Drawn by the shell on Plan and Coming up below 640px and nowhere else — never on the phone Map,
 * which gets its entry in the peek sheet (F4), and never on Operations. Its z-index is BELOW `Modal`'s
 * z-50 and far below the sheet's scrim (see `.wf-ask-bar` in index.css), so a dialog covers it rather
 * than the other way round.
 *
 * <p>{@code disabled} is a real attribute: the shell sets it while a dialog is open, the backend is
 * down, or Ask itself is unreachable, and a disabled bar must read as one to a screen reader too.
 *
 * @param {object} props
 * @param {string} props.prompt the tab's prompt; Plan's follows the windows on the matrix ("this weekend" /
 *   "the next few days"), Coming up's is "Ask about rare events…"
 * @param {function(): void} props.onOpen opens the sheet
 * @param {boolean} [props.disabled]
 * @param {boolean} [props.locked] the reader is not PRO or ADMIN: the bar is {@code disabled}, its
 *   contents take the drive filter's `wf-lens-locked` grey and a "Pro" pill sits BESIDE them. The lock
 *   is on an INNER span, not the button: the button is the opaque fixed box, and `wf-lens-locked` on it
 *   would make it see-through and let a tap fall through to the card scrolled underneath.
 * @param {boolean} [props.expanded] whether the sheet this opens is open
 * @param {React.Ref} [props.buttonRef] the button's node, for the shell's focus return
 */
export default function AskBar({
  prompt, onOpen, disabled = false, locked = false, expanded = false, buttonRef = undefined,
}) {
  const body = (
    <>
      <span className="wf-ask-bar-k" aria-hidden="true">Ask</span>
      <span className="wf-ask-bar-q">{prompt}</span>
      <span className="wf-ask-bar-go" aria-hidden="true">↑</span>
    </>
  );
  return (
    <button
      ref={buttonRef}
      type="button"
      className="wf-ask-bar"
      data-testid="ask-bar"
      data-locked={locked ? 'true' : undefined}
      aria-haspopup={locked ? undefined : 'dialog'}
      aria-expanded={locked ? undefined : expanded}
      disabled={disabled || locked}
      onClick={onOpen}
    >
      {locked ? (
        <>
          <span className="wf-ask-bar-inner wf-lens-locked" data-testid="ask-bar-locked">{body}</span>
          <span className="wf-lens-pro" data-testid="ask-bar-upsell">Pro</span>
        </>
      ) : body}
    </button>
  );
}

AskBar.propTypes = {
  prompt: PropTypes.string.isRequired,
  onOpen: PropTypes.func.isRequired,
  disabled: PropTypes.bool,
  locked: PropTypes.bool,
  expanded: PropTypes.bool,
  buttonRef: refShape,
};
