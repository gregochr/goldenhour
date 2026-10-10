import PropTypes from 'prop-types';

/**
 * The "Asking about" chips (design README, Components): what the question will be sent with.
 *
 * <p>The window chip is removable — its ✕ takes the window out of the request, so "what about
 * Sunday?" can be asked of the whole forecast — and the view chip ("Map · My area", "Plan · all
 * regions") is not: it states the scope that is sent, which the reader changes by moving on the
 * Map, never here. Both are plain labels, computed by the surface that mounts Ask; this component
 * knows no window, region or tab.
 *
 * <p>The ✕ is 24px square, not the design's 20: 24px is the smallest pointer target WCAG 2.2 (2.5.8)
 * accepts without spacing, and the chip is 26px tall, so it costs nothing in layout.
 *
 * @param {object} props
 * @param {string} props.viewLabel the non-removable view chip's text
 * @param {?string} [props.windowLabel] the removable window chip's text; no chip when null
 * @param {number} [props.followUp] how many exchanges came before the question this answer belongs to;
 *        above zero a further chip says "follow-up · N so far" (the thread's own count, not a context the
 *        reader can change, so it is never removable)
 * @param {function(): void} [props.onRemoveWindow] called by the window chip's ✕. Absent, the chip is a
 *        plain label: that is how it reads above an answer, where it says what was SENT and removing
 *        it would change nothing
 */
export default function AskContextChips({
  viewLabel, windowLabel = null, followUp = 0, onRemoveWindow,
}) {
  return (
    <div className="wf-ask-ctx" role="group" aria-label="Asking about" data-testid="ask-context">
      <span className="wf-ask-k" aria-hidden="true">Asking about</span>
      {windowLabel && (
        <span className="wf-ask-cx" data-testid="ask-chip-window">
          {windowLabel}
          {onRemoveWindow && (
            <button
              type="button"
              className="wf-ask-cx-x"
              data-testid="ask-chip-window-remove"
              aria-label={`Remove ${windowLabel} from the question`}
              onClick={onRemoveWindow}
            >
              <span aria-hidden="true">✕</span>
            </button>
          )}
        </span>
      )}
      <span className="wf-ask-cx" data-testid="ask-chip-view">{viewLabel}</span>
      {followUp > 0 && (
        <span className="wf-ask-cx" data-testid="ask-chip-followup">{`follow-up · ${followUp} so far`}</span>
      )}
    </div>
  );
}

AskContextChips.propTypes = {
  viewLabel: PropTypes.string.isRequired,
  windowLabel: PropTypes.string,
  followUp: PropTypes.number,
  onRemoveWindow: PropTypes.func,
};
