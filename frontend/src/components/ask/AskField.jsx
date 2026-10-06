import PropTypes from 'prop-types';

/**
 * The Ask field at the right end of the tab row (design README, "Where Ask lives": the 340 × 34 px
 * field with a `/` hint on desktop, the same field at 260px on iPad). F1b draws the 260px form from
 * 640 to 1023px; F2 reuses this component at 340px with the hint.
 *
 * <p>Like the phone's bar it is a BUTTON that opens the Ask surface — the mock's `<button
 * class="askf">` — not an input: the question is typed on the surface, where the answer is.
 *
 * <p><b>It sits BESIDE the tab list, never inside it.</b> The tab list has roving arrow keys and
 * {@code overflow-x: auto}; a control inside it would join that key handling and scroll away with the
 * tabs. The shell renders it as the tab row's second child.
 *
 * <p><b>Collapsing.</b> With four tabs (an admin) there is not room for 260px beside the bar below
 * 720px, so the stylesheet takes the field down to a 34px "Ask" button there. It is CSS-only and
 * keeps the prompt in the accessible name: the label is clipped, never {@code display: none}.
 * Whether it fits is a browser check, not a jsdom test (plan §2.6).
 *
 * <p>{@code showKeyHint} draws the "/" key cap (aria-hidden, with {@code aria-keyshortcuts} on the
 * button carrying the fact for assistive technology). F1b never sets it: the shortcut is F2's, and
 * a hint for a key that does nothing would be a lie.
 *
 * @param {object} props
 * @param {function(): void} props.onOpen opens the Ask surface
 * @param {260|340} [props.width=260] the design's two widths
 * @param {boolean} [props.showKeyHint=false] draw the "/" key cap (F2)
 * @param {boolean} [props.disabled]
 * @param {boolean} [props.expanded] whether the surface this opens is open
 * @param {string} [props.prompt] the field's text
 * @param {React.Ref} [props.buttonRef] the button's node, for the shell's focus return
 */
export default function AskField({
  onOpen, width = 260, showKeyHint = false, disabled = false, expanded = false,
  prompt = 'Ask about the forecasts…', buttonRef = undefined,
}) {
  return (
    <button
      ref={buttonRef}
      type="button"
      className="wf-askf"
      data-testid="ask-field"
      data-width={width}
      aria-haspopup="dialog"
      aria-expanded={expanded}
      aria-keyshortcuts={showKeyHint ? '/' : undefined}
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
  disabled: PropTypes.bool,
  expanded: PropTypes.bool,
  prompt: PropTypes.string,
  buttonRef: PropTypes.oneOfType([PropTypes.func, PropTypes.shape({ current: PropTypes.any })]),
};
