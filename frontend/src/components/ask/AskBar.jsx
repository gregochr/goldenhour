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
 * @param {string} props.prompt the tab's prompt: "Ask about this weekend…" / "Ask about rare events…"
 * @param {function(): void} props.onOpen opens the sheet
 * @param {boolean} [props.disabled]
 * @param {boolean} [props.expanded] whether the sheet this opens is open
 * @param {React.Ref} [props.buttonRef] the button's node, for the shell's focus return
 */
export default function AskBar({
  prompt, onOpen, disabled = false, expanded = false, buttonRef = undefined,
}) {
  return (
    <button
      ref={buttonRef}
      type="button"
      className="wf-ask-bar"
      data-testid="ask-bar"
      aria-haspopup="dialog"
      aria-expanded={expanded}
      disabled={disabled}
      onClick={onOpen}
    >
      <span className="wf-ask-bar-k" aria-hidden="true">Ask</span>
      <span className="wf-ask-bar-q">{prompt}</span>
      <span className="wf-ask-bar-go" aria-hidden="true">↑</span>
    </button>
  );
}

AskBar.propTypes = {
  prompt: PropTypes.string.isRequired,
  onOpen: PropTypes.func.isRequired,
  disabled: PropTypes.bool,
  expanded: PropTypes.bool,
  buttonRef: refShape,
};
