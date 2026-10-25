import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';

/**
 * The scores upsell's words (docs/engineering/pro-features-gate-plan.md §4). Placeholder copy until the
 * owner signs it off, as Ask's was. There is no upgrade button, no link and no "upgrade" text anywhere
 * (the App Store forbids pointing at a web purchase, and `/upgrade` is not a route).
 */
export const SCORES_UPSELL_LABEL = 'Scores and the why';
export const SCORES_UPSELL_HEADING = 'Fiery Sky and Golden Hour scores';
export const SCORES_UPSELL_BODY = 'See how strong the colour and the light should be at each location, with a note on why.';

export const BEST_BETS_UPSELL_LABEL = 'Best bets';
export const BEST_BETS_UPSELL_HEADING = 'Best bets';
export const BEST_BETS_UPSELL_BODY = "See the forecast's two best windows, and where to go for each.";

/**
 * The generic upsell's content: the house pill, a heading and a body. No preview sketch: the two
 * existing sketches (tide, Ask) are feature drawings and a generic one would say nothing.
 * {@code headingRef} lets a popover move focus to the heading.
 */
export function ProUpsellContent({
  heading, body, headingId, headingRef = undefined, bodyId = undefined, testIdPrefix,
}) {
  return (
    <>
      <span className="wf-lens-pro" data-testid={`${testIdPrefix}-pill`}>Pro</span>
      <h3
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="wf-pro-upsell-heading"
        data-testid={`${testIdPrefix}-heading`}
      >
        {heading}
      </h3>
      <p id={bodyId} className="wf-pro-upsell-body" data-testid={`${testIdPrefix}-body`}>{body}</p>
    </>
  );
}

ProUpsellContent.propTypes = {
  heading: PropTypes.string.isRequired,
  body: PropTypes.string.isRequired,
  headingId: PropTypes.string.isRequired,
  headingRef: PropTypes.oneOfType([PropTypes.func, PropTypes.shape({ current: PropTypes.any })]),
  bodyId: PropTypes.string,
  testIdPrefix: PropTypes.string.isRequired,
};

/** Where focus goes if the door unmounts while focus is inside it: the host's nearest focusable ancestor. */
const FOCUSABLE_ANCESTOR = '[tabindex], a[href], button, input, select, textarea';

/**
 * A self-contained Pro upsell door: a trigger button (the label, with the {@code wf-lens-pro} pill INSIDE
 * it) and a non-modal popover, with the whole lifecycle owned here so a host only renders
 * {@code <ProUpsellDoor .../>} (plan §4):
 * <ol>
 *   <li>open state, {@code aria-expanded}, {@code aria-controls};</li>
 *   <li>an inline, absolutely positioned {@code role="dialog"} (no {@code aria-modal}) named by its heading
 *       and described by its body, the heading focused on open, and a "Not now" button;</li>
 *   <li>Escape on the popover or the trigger closes it, returns focus to the trigger and STOPS the key
 *       (propagation and default) so it never reaches a host {@code Modal}'s document listener;</li>
 *   <li>an outside {@code pointerdown} (capture) closes it (Safari gives a null {@code relatedTarget}, so
 *       blur alone is not enough);</li>
 *   <li>a {@code focusin} anywhere outside the door closes it, leaving focus where the reader put it;</li>
 *   <li>unmounting while open closes it and does not strand focus on {@code <body>}.</li>
 * </ol>
 *
 * <p>⚠️ No foreign-modal stand-down predicate. Such a predicate counts any dialog, including the sheet this
 * door lives in, so one Escape would close the whole sheet. The door is only ever the topmost layer while it
 * is open, so it handles its own keys unconditionally. It is not a {@code Modal} and adds no dialog to the
 * two-dialog count.
 */
export default function ProUpsellDoor({
  label, heading, body, testIdPrefix, className = '',
}) {
  const [open, setOpen] = useState(false);
  const uid = useId();
  const popId = `${uid}-pop`;
  const headingId = `${uid}-heading`;
  const bodyId = `${uid}-body`;
  const rootRef = useRef(null);
  const triggerRef = useRef(null);
  const headingRef = useRef(null);

  const popRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    headingRef.current?.focus({ preventScroll: true });
    // On a short viewport the popover can open below the fold; jsdom has no scrollIntoView.
    popRef.current?.scrollIntoView?.({ block: 'nearest' });
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const inside = (node) => !!rootRef.current && rootRef.current.contains(node);
    const onPointerDown = (e) => { if (!inside(e.target)) setOpen(false); };
    const onFocusIn = (e) => { if (!inside(e.target)) setOpen(false); };
    document.addEventListener('pointerdown', onPointerDown, true);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('focusin', onFocusIn);
    };
  }, [open]);

  // Unmount while open (the host re-rendered the door away): if focus is inside, hand it to the host's
  // nearest focusable ancestor before the nodes go, so it does not fall to <body>. A layout cleanup runs
  // while the nodes are still attached.
  useLayoutEffect(() => () => {
    const root = rootRef.current;
    if (!root || !root.contains(document.activeElement)) return;
    root.parentElement?.closest(FOCUSABLE_ANCESTOR)?.focus({ preventScroll: true });
  }, []);

  function close(returnFocus) {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus({ preventScroll: true });
  }

  function onKeyDown(e) {
    if (e.key !== 'Escape' || !open) return;
    e.stopPropagation();
    e.preventDefault();
    close(true);
  }

  return (
    // The wrapper owns Escape for both its children (trigger and popover); it is not itself interactive.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={rootRef}
      className={`wf-pro-upsell-door ${className}`.trim()}
      data-testid={`${testIdPrefix}-door`}
      onKeyDown={onKeyDown}
    >
      <button
        ref={triggerRef}
        type="button"
        className="wf-pro-upsell-trigger"
        data-testid={`${testIdPrefix}-trigger`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={popId}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="wf-pro-upsell-trigger-label">{label}</span>
        {' '}
        <span className="wf-lens-pro">Pro</span>
      </button>
      {open && (
        <div
          id={popId}
          ref={popRef}
          tabIndex={-1}
          role="dialog"
          aria-labelledby={headingId}
          aria-describedby={bodyId}
          className="wf-pro-upsell"
          data-testid={`${testIdPrefix}-popover`}
        >
          <ProUpsellContent
            heading={heading}
            body={body}
            headingId={headingId}
            headingRef={headingRef}
            bodyId={bodyId}
            testIdPrefix={testIdPrefix}
          />
          <button
            type="button"
            className="wf-pro-upsell-dismiss"
            data-testid={`${testIdPrefix}-dismiss`}
            onClick={() => close(true)}
          >
            Not now
          </button>
        </div>
      )}
    </div>
  );
}

ProUpsellDoor.propTypes = {
  label: PropTypes.string.isRequired,
  heading: PropTypes.string.isRequired,
  body: PropTypes.string.isRequired,
  testIdPrefix: PropTypes.string.isRequired,
  className: PropTypes.string,
};
