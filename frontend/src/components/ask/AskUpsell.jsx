import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/**
 * The Ask upsell's words (docs/engineering/ask-upsell-plan.md §2, owner Q4): placeholder copy until the
 * owner signs it off. There is no upgrade button, no link and no "upgrade" text anywhere (the App Store
 * forbids pointing at a web purchase, and `/upgrade` is not a route).
 */
export const ASK_UPSELL_HEADING = 'Ask about the forecasts in plain English';
export const ASK_UPSELL_BODY = 'Ask where and when to go for the light, and get an answer drawn from the forecasts, with the spots shown on the map.';

/**
 * The upsell's static preview: an abstract sketch of a question line, two answer bars and two numbered
 * marker dots. ⚠️ It is built from constants ONLY and takes no props, and it holds no place name, no score
 * and no text: a sketch that showed served data or a real place would read as a live answer to a reader
 * who has none. Hidden from assistive tech: it is decoration.
 */
export function AskUpsellPreview() {
  return (
    <svg
      className="wf-ask-upsell-preview"
      data-testid="wf-ask-upsell-preview"
      width="100%"
      height="70"
      viewBox="0 0 440 70"
      preserveAspectRatio="none"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="0" y="2" width="250" height="10" rx="5" fill="var(--color-plex-text-secondary)" fillOpacity="0.55" />
      <circle cx="10" cy="34" r="8" fill="none" stroke="var(--color-plex-gold)" strokeWidth="1.5" />
      <rect x="28" y="30" width="392" height="8" rx="4" fill="var(--color-plex-border-light)" />
      <circle cx="10" cy="58" r="8" fill="none" stroke="var(--color-plex-gold)" strokeWidth="1.5" />
      <rect x="28" y="54" width="300" height="8" rx="4" fill="var(--color-plex-border-light)" />
    </svg>
  );
}

/**
 * The upsell's content, shared by the field's popover, the bar's popover and (U2) the phone peek's Ask
 * section: the house pill, the heading, the body and the preview. {@code headingRef} lets a popover move
 * focus to the heading; an inline copy passes none.
 */
export function AskUpsellContent({
  headingId, headingRef = undefined, bodyId = undefined, testIdPrefix,
}) {
  return (
    <>
      <span className="wf-lens-pro" data-testid={`${testIdPrefix}-pill`}>Pro</span>
      <h3
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="wf-ask-upsell-heading"
        data-testid={`${testIdPrefix}-heading`}
      >
        {ASK_UPSELL_HEADING}
      </h3>
      <p id={bodyId} className="wf-ask-upsell-body" data-testid={`${testIdPrefix}-body`}>{ASK_UPSELL_BODY}</p>
      <AskUpsellPreview />
    </>
  );
}

AskUpsellContent.propTypes = {
  headingId: PropTypes.string.isRequired,
  bodyId: PropTypes.string,
  headingRef: PropTypes.oneOfType([PropTypes.func, PropTypes.shape({ current: PropTypes.any })]),
  testIdPrefix: PropTypes.string.isRequired,
};

/**
 * The tablet, desktop and phone-bar upsell popover (plan §3, §4.2, §4.3). Non-modal
 * ({@code role="dialog"} with no {@code aria-modal}) and rendered INLINE in the shell by the caller,
 * never portalled: a portalled dialog would read as a foreign dialog to `foreignDialogOpen` and to every
 * Map Escape rule.
 *
 * <p>Focus moves to the heading on open. Escape is handled here and STOPPED: on the Map tab the landing
 * card closes on a bubble-phase `document` Escape listener that only defers for the things it knows, so
 * without {@code stopPropagation} one Escape would close the popover and the card together. It stands
 * down while a dialog this shell does not own is open ({@code foreignOpen}). The trigger handles the same
 * key itself (the shell), for focus that has gone back to it with the popover still open.
 *
 * <p>Focus leaving for something that is neither the popover nor the trigger closes it WITHOUT moving
 * focus. A null {@code relatedTarget} is ignored and left to the outside press: Safari does not focus a
 * clicked button, so a null here would close the popover and the trigger's own click would reopen it.
 */
export default function AskUpsellPopover({
  id, rootRef, triggerRef, onClose, onFocusAway, foreignOpen, placement,
}) {
  const headingRef = useRef(null);
  const headingId = `${id}-heading`;
  const bodyId = `${id}-body`;

  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
  }, []);

  function onKeyDown(e) {
    if (e.key !== 'Escape') return;
    if (foreignOpen()) return;
    e.stopPropagation();
    e.preventDefault();
    onClose();
  }

  function onBlur(e) {
    const next = e.relatedTarget;
    if (!next || rootRef.current?.contains(next) || triggerRef.current?.contains(next)) return;
    onFocusAway();
  }

  return (
    // A non-modal dialog: Escape is the one key it owns.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      id={id}
      ref={rootRef}
      role="dialog"
      tabIndex={-1}
      aria-labelledby={headingId}
      aria-describedby={bodyId}
      className="wf-ask-upsell wf-ask-upsell-pop"
      data-placement={placement}
      data-testid="wf-ask-upsell"
      onKeyDown={onKeyDown}
      onBlur={onBlur}
    >
      <AskUpsellContent
        headingId={headingId}
        headingRef={headingRef}
        bodyId={bodyId}
        testIdPrefix="wf-ask-upsell"
      />
      <button
        type="button"
        className="wf-ask-upsell-dismiss"
        data-testid="wf-ask-upsell-dismiss"
        onClick={onClose}
      >
        Not now
      </button>
    </div>
  );
}

AskUpsellPopover.propTypes = {
  id: PropTypes.string.isRequired,
  rootRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
  triggerRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
  onClose: PropTypes.func.isRequired,
  onFocusAway: PropTypes.func.isRequired,
  foreignOpen: PropTypes.func.isRequired,
  placement: PropTypes.oneOf(['field', 'bar']).isRequired,
};
