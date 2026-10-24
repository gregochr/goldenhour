import React, { useRef } from 'react';
import PropTypes from 'prop-types';
import { appNow } from '../../utils/rewind.js';
import { bigSeasCopy, bigSeasFor } from '../../utils/bigSeas.js';
import BoltIcon from './BoltIcon.jsx';

/**
 * The Map tab's big-seas line (big-seas plan F1; handoff "Big-seas line").
 *
 * <p>One answer to one question: should I go to the coast to see waves break over the piers and
 * lighthouses? The full form speaks of the day on screen (any solar or night row of it), the compact
 * form of the next day in the horizon that has big seas, and nothing is drawn otherwise. All roles
 * see it (owner Q8). It sits on its own row INSIDE {@code .wf-map-chrome-tl}, under the window
 * control, so it shares the control's 504px bound and rides in the same label-placement obstacle.
 *
 * <p>The action ("On the sunset ›" / "See Saturday ›") is the {@code MapTideStrip} jump pattern:
 * focus the stable target FIRST, then select the row, because the jump commonly removes the very
 * button that was pressed. The target is the line's own root ({@code tabIndex=-1}), which stays
 * mounted while the action disappears. The root is a labelled group, not {@code role="status"}: it
 * is not a live announcement, and a status region would speak on every window change.
 *
 * <p>The group's height is published as {@code --wf-chrome-tl-h} by {@code useChromeTlHeight} in
 * {@code MapView} (it moved out of this component with the topic chips, which grow the group on days
 * with no big seas too). Phone: the line runs the control's full width and wraps; the badge never
 * does.
 *
 * @param {object} props
 * @param {?Array<object>} props.bigSeas the served {@code briefing.bigSeas}
 * @param {?object} props.evRow the EV row on screen
 * @param {Array<object>} props.events the EV list ({@code buildMapEvents})
 * @param {?Function} [props.onSelectEv] called with the aligned EV row when the action is pressed
 */
export default function BigSeasLine({ bigSeas, evRow, events, onSelectEv = undefined }) {
  const rootRef = useRef(null);
  // `appNow()` is read at render, like every clock read here: a Rewind re-renders the pane.
  const now = appNow();
  // A few rows and days at most, filter/select only: cheap enough to run every render, which is
  // also what keeps it honest under a Rewind.
  const selection = bigSeasFor(bigSeas, evRow, events, now);
  const copy = bigSeasCopy(selection);
  const shown = copy != null;

  if (!shown) return null;
  const jumpRow = selection.jumpRow;
  return (
    <div
      ref={rootRef}
      tabIndex={-1}
      role="group"
      aria-label="Big seas"
      className="wf-big-seas"
      data-testid="wf-big-seas"
      data-form={selection.form}
    >
      <span className="wf-big-seas-badge">
        <BoltIcon />
        {' '}
        {/* aria-hidden: the group's own label already says "Big seas". */}
        <span aria-hidden="true">Big seas</span>
      </span>
      <span className="wf-big-seas-text">
        <b className="wf-big-seas-l1" data-testid="wf-big-seas-l1">{copy.line1}</b>
        <span className="wf-big-seas-l2" data-testid="wf-big-seas-l2">{copy.line2}</span>
      </span>
      {copy.action && jumpRow && (
        <button
          type="button"
          className="wf-big-seas-go"
          data-testid="wf-big-seas-go"
          onClick={() => {
            // Focus the stable target FIRST, as the tide strip's next-fit jump does: the jump may
            // unmount this very button, and parking focus before the change is the safer order.
            rootRef.current?.focus();
            onSelectEv?.(jumpRow);
          }}
        >
          {copy.action}
        </button>
      )}
    </div>
  );
}

BigSeasLine.propTypes = {
  bigSeas: PropTypes.arrayOf(PropTypes.object),
  evRow: PropTypes.object,
  events: PropTypes.arrayOf(PropTypes.object),
  onSelectEv: PropTypes.func,
};
