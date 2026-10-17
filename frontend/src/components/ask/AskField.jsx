import PropTypes from 'prop-types';
import { refShape } from './askShapes.js';

/**
 * The Ask field at the right end of the tab row (design README, "Where Ask lives": the 340 × 34 px
 * field with a `/` hint on desktop, 260px from 640px to 1179px, where the room goes to the dock or the
 * tab list). Below 1024px it opens the sheet; from 1024px it opens the dock.
 *
 * <p>Like the phone's bar it is a BUTTON that opens the Ask surface — the mock's `<button
 * class="askf">` — not an input: the question is typed on the surface, where the answer is.
 *
 * <p><b>It sits BESIDE the tab list, never inside it.</b> The tab list has roving arrow keys and
 * {@code overflow-x: auto}; a control inside it would join that key handling and scroll away with the
 * tabs. The shell renders it as the tab row's second child.
 *
 * <p><b>Collapsing.</b> With four tabs (an admin) there is not room for 260px beside the bar below
 * 720px of COLUMN width (a container query on the tab row: the dock narrows the column, not the
 * window), so the stylesheet takes the field down to a 34px "Ask" button there — and steps the 340px
 * form down to 260 under 800px first. It is CSS-only and keeps the prompt in the accessible name: the
 * label is clipped, never {@code display: none}. Whether it fits is a browser check, not a jsdom test
 * (plan §2.6).
 *
 * <p>{@code showKeyHint} draws the "/" key cap (aria-hidden) — only where there is room for it, from
 * 1180px. {@code keyShortcut} says the key works, to assistive technology, through
 * {@code aria-keyshortcuts}: the shortcut is the dock's (from 1024px) and is live in BOTH docked bands,
 * so the attribute is set in both even though the cap is drawn in one — the cap needs room and the
 * attribute needs none. The key cap implies the attribute. Neither is ever set below 1024px, where the
 * sheet has no key: a hint for a key that does nothing would be a lie.
 *
 * <p><b>What it opens decides what it claims.</b> Below 1024px it opens a modal sheet, so it says
 * {@code aria-haspopup="dialog"}. From 1024px it opens the dock, which is a complementary region and
 * NOT a dialog: a button that promised a dialog and opened a sidebar would mislead exactly the readers
 * who act on the promise. The dock's field passes {@code controls} (the dock's id) instead, which is
 * only a pointer while the dock is open ({@code aria-controls} must name something that exists).
 *
 * @param {object} props
 * @param {function(): void} props.onOpen opens the Ask surface
 * @param {260|340} [props.width=260] the design's two widths
 * @param {boolean} [props.showKeyHint=false] draw the "/" key cap (F2)
 * @param {boolean} [props.keyShortcut=false] the "/" key works: name it in {@code aria-keyshortcuts}
 * @param {boolean} [props.disabled]
 * @param {boolean} [props.expanded] whether the surface this opens is open
 * @param {string} [props.prompt] the field's text
 * @param {React.Ref} [props.buttonRef] the button's node, for the shell's focus return
 * @param {string} [props.controls] the id of the non-modal region this opens; when given the field
 *        does not claim a popup dialog
 */
export default function AskField({
  onOpen, width = 260, showKeyHint = false, keyShortcut = false, disabled = false, expanded = false,
  prompt = 'Ask about the forecasts…', buttonRef = undefined, controls = undefined,
}) {
  return (
    <button
      ref={buttonRef}
      type="button"
      className="wf-askf"
      data-testid="ask-field"
      data-width={width}
      aria-haspopup={controls === undefined ? 'dialog' : undefined}
      aria-controls={controls !== undefined && expanded ? controls : undefined}
      aria-expanded={expanded}
      aria-keyshortcuts={showKeyHint || keyShortcut ? '/' : undefined}
      disabled={disabled}
      onClick={onOpen}
    >
      <span className="wf-askf-k" aria-hidden="true">Ask</span>
      <span className="wf-askf-q">{prompt}</span>
      {showKeyHint && <kbd className="wf-askf-kbd" aria-hidden="true">/</kbd>}
    </button>
  );
}

AskField.propTypes = {
  onOpen: PropTypes.func.isRequired,
  width: PropTypes.oneOf([260, 340]),
  showKeyHint: PropTypes.bool,
  keyShortcut: PropTypes.bool,
  disabled: PropTypes.bool,
  expanded: PropTypes.bool,
  prompt: PropTypes.string,
  buttonRef: refShape,
  controls: PropTypes.string,
};
