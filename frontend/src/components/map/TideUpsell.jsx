import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { foreignModalOverPaneOf } from '../../utils/mapForeignModal.js';

/**
 * The tide upsell's words (docs/engineering/tide-pro-gate-plan.md §6.1, owner Q5): the README's
 * placeholder copy. There is no upgrade button, no link and no "upgrade" text anywhere (Q1; the
 * App Store forbids pointing at a web purchase, and `/upgrade` is not a route).
 */
export const TIDE_UPSELL_HEADING = 'Match spots to the tide at your light';
export const TIDE_UPSELL_BODY = 'See the tide curve for each sunrise and sunset, and dim coastal spots that need different water.';

/**
 * The popover's static preview: a committed curve with two light markers. ⚠️ It is built from
 * constants ONLY and takes no props, so it can never carry the reader's served tide (that would hand
 * the Pro curve to a reader who does not have it). Hidden from assistive tech: it is decoration.
 */
export function TideUpsellPreview() {
  return (
    <svg
      className="wf-tide-upsell-preview"
      data-testid="wf-tide-upsell-preview"
      width="100%"
      height="70"
      viewBox="0 0 440 70"
      preserveAspectRatio="none"
      aria-hidden="true"
      focusable="false"
    >
      <line
        x1="132" y1="0" x2="132" y2="70"
        stroke="var(--color-verdict-marginal)" strokeOpacity="0.6" strokeWidth="1" strokeDasharray="3 3"
      />
      <line
        x1="308" y1="0" x2="308" y2="70"
        stroke="var(--color-verdict-marginal)" strokeOpacity="0.6" strokeWidth="1" strokeDasharray="3 3"
      />
      <path
        d="M0 38 C 55 -6, 110 -6, 165 38 S 275 82, 330 38 S 400 -2, 440 24"
        fill="none"
        stroke="var(--color-tide)"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
  );
}

/**
 * The Coming up locked row's sketch (coming-up-tide-light-locked-row-plan.md §2): a fixed tide curve between
 * two dashed light lines, drawn at its own small size so the stroke stays visible. Like
 * {@code TideUpsellPreview}, constants ONLY and no props, so it can never carry the reader's served tide.
 * Hidden from assistive tech: it is decoration.
 */
export function TideLightLockedSketch() {
  return (
    <svg
      className="wf-cu-locked-sketch"
      data-testid="wf-cu-locked-sketch"
      width="104"
      height="24"
      viewBox="0 0 104 24"
      aria-hidden="true"
      focusable="false"
    >
      <line
        x1="31" y1="0" x2="31" y2="24"
        stroke="var(--color-verdict-marginal)" strokeOpacity="0.6" strokeWidth="1" strokeDasharray="2 2"
      />
      <line
        x1="73" y1="0" x2="73" y2="24"
        stroke="var(--color-verdict-marginal)" strokeOpacity="0.6" strokeWidth="1" strokeDasharray="2 2"
      />
      <path
        d="M0 13 C 13 -2, 27 -2, 39 13 S 65 28, 78 13 S 94 1, 104 8"
        fill="none"
        stroke="var(--color-tide)"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
    </svg>
  );
}

/**
 * The upsell's content, shared by the desktop popover and the phone peek's Tide section: the house
 * pill, the heading, the body and the preview. {@code headingRef} lets the popover move focus to the
 * heading; the phone section passes none.
 */
export function TideUpsellContent({
  headingId, headingRef = undefined, bodyId = undefined, testIdPrefix,
}) {
  return (
    <>
      <span className="wf-lens-pro" data-testid={`${testIdPrefix}-pill`}>Pro</span>
      <h3
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="wf-tide-upsell-heading"
        data-testid={`${testIdPrefix}-heading`}
      >
        {TIDE_UPSELL_HEADING}
      </h3>
      <p id={bodyId} className="wf-tide-upsell-body" data-testid={`${testIdPrefix}-body`}>{TIDE_UPSELL_BODY}</p>
      <TideUpsellPreview />
    </>
  );
}

TideUpsellContent.propTypes = {
  headingId: PropTypes.string.isRequired,
  bodyId: PropTypes.string,
  headingRef: PropTypes.oneOfType([PropTypes.func, PropTypes.shape({ current: PropTypes.any })]),
  testIdPrefix: PropTypes.string.isRequired,
};

/**
 * The desktop and tablet upsell popover (plan §6.1). Non-modal ({@code role="dialog"} with no
 * {@code aria-modal}) and rendered INLINE in the Map pane, never portalled: a portalled dialog would
 * read as a foreign modal to `foreignModalOver` and stand every Map Escape rule down.
 *
 * <p>Focus moves to the heading on open. Escape is handled here, guarded by
 * {@code foreignModalOverPaneOf} (the `FiltersPopover` pattern); {@code MapView} restores focus to the
 * opener through {@code onClose}. Every other close path (outside press, map click, hand pan or zoom,
 * teaser unmount) is wired in `MapView`.
 */
export default function TideUpsellPopover({
  id, onClose, onFocusAway, rootRef, doorSelector,
}) {
  const headingRef = useRef(null);
  const headingId = `${id}-heading`;
  const bodyId = `${id}-body`;

  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
  }, []);

  function onKeyDown(e) {
    if (e.key !== 'Escape') return;
    if (foreignModalOverPaneOf(rootRef.current)) return;
    e.preventDefault();
    onClose();
  }

  // Focus left the popover for something that is not a tide door (Shift+Tab onto the four-day strip it
  // overlays, say: WCAG 2.4.11, those stops are hidden): close WITHOUT moving focus, which is already
  // where the reader put it. A null `relatedTarget` (a click on bare ground, the window losing focus) is
  // left to the outside-press and map-click routes.
  function onBlur(e) {
    const next = e.relatedTarget;
    if (!next || rootRef.current?.contains(next)) return;
    if (next.closest?.(doorSelector)) return;
    onFocusAway();
  }

  return (
    <div className="wf-tide-upsell-anchor">
      {/* A non-modal dialog: Escape is the one key it owns. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        id={id}
        ref={rootRef}
        role="dialog"
        aria-labelledby={headingId}
        aria-describedby={bodyId}
        className="wf-tide-upsell"
        data-testid="wf-tide-upsell"
        onKeyDown={onKeyDown}
        onBlur={onBlur}
      >
        <TideUpsellContent
          headingId={headingId}
          headingRef={headingRef}
          bodyId={bodyId}
          testIdPrefix="wf-tide-upsell"
        />
        <button
          type="button"
          className="wf-tide-upsell-dismiss"
          data-testid="wf-tide-upsell-dismiss"
          onClick={onClose}
        >
          Not now
        </button>
      </div>
    </div>
  );
}

TideUpsellPopover.propTypes = {
  id: PropTypes.string.isRequired,
  onClose: PropTypes.func.isRequired,
  onFocusAway: PropTypes.func.isRequired,
  doorSelector: PropTypes.string.isRequired,
  rootRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
};
