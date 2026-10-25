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
 * @param {boolean} [props.locked] the reader is not PRO or ADMIN: the bar is a live trigger for the Ask upsell
 *   (docs/engineering/ask-upsell-plan.md §4.3), drawn like the PRO one with the "Pro" pill inside it as a
 *   direct child, between the prompt and the arrow. It claims {@code aria-haspopup="dialog"},
 *   {@code aria-expanded} from {@code upsellOpen} and {@code aria-controls} while the popover is open, and is
 *   {@code disabled} only by {@code disabled}. No `aria-label`: the name is its content, "{prompt} Pro".
 * @param {boolean} [props.upsellOpen] locked only: the upsell popover is open
 * @param {string} [props.upsellId] locked only: the open popover's id
 * @param {function(React.KeyboardEvent): void} [props.onKeyDown] the shell's Escape handler for the open upsell
 * @param {boolean} [props.expanded] whether the sheet this opens is open
 * @param {React.Ref} [props.buttonRef] the button's node, for the shell's focus return
 */
export default function AskBar({
  prompt, onOpen, disabled = false, locked = false, expanded = false, buttonRef = undefined,
  upsellOpen = false, upsellId = undefined, onKeyDown = undefined,
}) {
  return (
    <button
      ref={buttonRef}
      type="button"
      className="wf-ask-bar"
      data-testid="ask-bar"
      data-locked={locked ? 'true' : undefined}
      aria-haspopup="dialog"
      aria-expanded={locked ? upsellOpen : expanded}
      aria-controls={locked && upsellOpen ? upsellId : undefined}
      disabled={disabled}
      onClick={onOpen}
      onKeyDown={onKeyDown}
    >
      <span className="wf-ask-bar-k" aria-hidden="true">Ask</span>
      <span className="wf-ask-bar-q">{prompt}</span>
      {locked && <span className="wf-lens-pro" data-testid="ask-bar-upsell">Pro</span>}
      <span className="wf-ask-bar-go" aria-hidden="true">↑</span>
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
  upsellOpen: PropTypes.bool,
  upsellId: PropTypes.string,
  onKeyDown: PropTypes.func,
};
