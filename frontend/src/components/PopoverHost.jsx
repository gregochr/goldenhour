import React from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';

/**
 * The single body-parented panel every popover in the window-first subtree renders through.
 *
 * <h2>Portalled, not merely fixed</h2>
 *
 * <p>{@code position: fixed} escapes a scroller's {@code overflow} clipping, which is necessary but
 * not sufficient: a {@code transform}, {@code filter} or {@code will-change} on <em>any</em>
 * ancestor re-bases fixed positioning onto that ancestor, and the panel is quietly clipped again by
 * a rule nobody remembers writing. Portalling to {@code document.body} removes the ancestor chain
 * from the question entirely, so a later phase adding a transition to a window card cannot break
 * this.
 *
 * <h2>It is a tooltip, and therefore never the only route to anything</h2>
 *
 * <p>{@code role="tooltip"} describes supplementary detail about the trigger. Content that a reader
 * must reach — a destination, an action — belongs on the trigger itself or in a dialog, not here:
 * the card stays a button, and the peek is a shortcut, never the only route.
 *
 * <p>Rendering nothing when closed is deliberate rather than hiding with CSS: an
 * {@code aria-hidden} panel that still exists in the tree is one a screen reader can stumble into,
 * and an empty tooltip is worse than no tooltip.
 *
 * @param {object}  props
 * @param {?object} props.popover the open popover from {@code usePopoverHost}, or null when closed
 * @param {string}  [props.className] class for the panel, so each content kind can style its own
 */
export default function PopoverHost({ popover, className }) {
  if (!popover) return null;
  return createPortal(
    <div
      data-testid="popover-host"
      data-align={popover.alignRight ? 'right' : 'left'}
      className={className}
      role="tooltip"
      style={popover.style}
    >
      {popover.content}
    </div>,
    document.body,
  );
}

PopoverHost.propTypes = {
  popover: PropTypes.shape({
    key: PropTypes.string,
    content: PropTypes.node,
    style: PropTypes.object,
    alignRight: PropTypes.bool,
  }),
  className: PropTypes.string,
};
