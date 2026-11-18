import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import { useWarnings } from '../../context/WarningsContext.jsx';
import { warningsForMapWindow } from '../../utils/warnings.js';
import WarningStrip from '../WarningStrip.jsx';

/**
 * The Map tab's Met Office warning chips (weather-warnings-plan.md W4): the warnings in force for the window on
 * screen, as the same text chips the callout and the Plan matrix draw, with the licence's attribution line and the
 * stale admission under them.
 *
 * <p>A row of its own INSIDE {@code .wf-map-chrome-tl}, under the hot-topic chips and never inside that bar (a warning
 * is not a hot topic), so it shares the group's width bound and label obstacle, and the group's height observer
 * ({@code useChromeTlHeight}) re-places the labels and the panels hung under the group whenever this row appears,
 * disappears or changes height. The row is {@code pointer-events: none} and the box {@code auto}, so the gaps never
 * swallow a map drag. The row is {@code width: 0; min-width: 100%}: it takes the width the control and the topic chips
 * already give the (shrink-to-fit) group and contributes none of its own, so a long warning wraps inside the group and
 * can never widen the label obstacle (the measured 504px bound in {@code index.css}).
 *
 * <p>No geometry, outline or shading is ever drawn on the map for a warning: the Met Office style guide v1.4 forbids
 * overlaying warning geometry with other information (plan section 12), so the chip is the whole map presence. It
 * opens nothing of ours: each chip is a link to the Met Office warnings page (no dialog, no history entry). A warning
 * never changes a rating, the heat field, a verdict or a pick; this reads {@code warnings} and nothing else.
 *
 * <p>Prints nothing while the feature is off, nothing for a window with no warning on a fresh feed (never an
 * all-clear), and only the stale line when the feed is stale. For a window the server never keys a warning to (a
 * night, High water, a past window) it prints nothing at all.
 *
 * <p>The warnings are matched on every render of the polled list ({@code warningsForMapWindow}), never cached or
 * trusted from a stored row's ids, so a withdrawn or new warning changes at the next poll (the licence's 10 minutes).
 *
 * @param {object} props
 * @param {?object} props.event the map's active EV row
 * @param {Array<object>} props.locations the whole roster (not the filtered pins)
 * @param {?object} [props.slotIndex] the callout's briefing-slot index (a place's own instant when it has no row)
 * @param {?object} [props.scoreIndex] the callout's score-row index (the last instant source)
 */
export default function MapWarningChips({
  event = null, locations = [], slotIndex = null, scoreIndex = null,
}) {
  const served = useWarnings();
  const warnings = useMemo(
    () => warningsForMapWindow(served, {
      event, locations, slotIndex, scoreIndex,
    }),
    [served, event, locations, slotIndex, scoreIndex],
  );
  const applies = event?.kind === 'solar' && !event.past;
  if (!applies || !served.enabled) return null;
  if (warnings.length === 0 && !served.stale) return null;
  return (
    <div className="pointer-events-none w-0 min-w-full" data-testid="wf-map-warnings-row">
      <div
        data-testid="wf-map-warnings"
        className="pointer-events-auto mt-1 inline-block max-w-full rounded border border-plex-border-light bg-plex-surface/90 p-1.5"
      >
        <WarningStrip warnings={warnings} testId="map-warning-chips" />
      </div>
    </div>
  );
}

MapWarningChips.propTypes = {
  event: PropTypes.shape({
    kind: PropTypes.string,
    past: PropTypes.bool,
    date: PropTypes.string,
    eventType: PropTypes.string,
  }),
  locations: PropTypes.arrayOf(PropTypes.object),
  slotIndex: PropTypes.object,
  scoreIndex: PropTypes.object,
};
