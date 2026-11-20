import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import { useWarnings } from '../../context/WarningsContext.jsx';
import { warningLabel, warningsForMapWindow } from '../../utils/warnings.js';
import WarningStrip from '../WarningStrip.jsx';
import WarningChip, { WarningFooter } from '../WarningChip.jsx';
import { WindowWarningsBody } from '../WindowWarningRow.jsx';
import BottomSheet from '../BottomSheet.jsx';

/** The Map's active window row, as far as the warnings read it. */
const EVENT_SHAPE = PropTypes.shape({
  kind: PropTypes.string,
  past: PropTypes.bool,
  date: PropTypes.string,
  eventType: PropTypes.string,
});

/** The id the toggles' {@code aria-controls} names, on {@link MapWarningPanel}'s root. */
export const MAP_WARNING_PANEL_ID = 'wf-map-warning-panel';

/**
 * The polled warnings that apply to the Map's window, matched on every render (never cached or trusted from a stored
 * row's ids), so a withdrawn or new warning changes at the next poll (the licence's 10 minutes).
 *
 * @param {object} args the same four inputs both mounts take
 * @returns {{served: object, warnings: Array<object>, applies: boolean}} {@code applies} is false off a solar window
 *          that is not past, and while the feature is off
 */
function useMapWarnings({
  event, locations, slotIndex, scoreIndex,
}) {
  const served = useWarnings();
  const warnings = useMemo(
    () => warningsForMapWindow(served, {
      event, locations, slotIndex, scoreIndex,
    }),
    [served, event, locations, slotIndex, scoreIndex],
  );
  const applies = event?.kind === 'solar' && !event.past && served.enabled === true;
  return { served, warnings, applies };
}

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
 * overlaying warning geometry with other information (plan section 12), so the chip is the whole map presence. Each
 * chip is a link to the Met Office warnings page (no dialog, no history entry). Beside each chip a SEPARATE "Details"
 * button (a chip is a link and is never inside a disclosure button) opens the warning in full in
 * {@link MapWarningPanel}, a frame-level sibling hung under this group (never inside it, which would change the
 * group's height and re-place the map's labels), through the same {@code WindowWarningsBody} the window popup draws
 * (glance-to-map-plan.md G1). The attribution stays visible open or closed, and a stale feed with no warning has
 * nothing to expand and so no button. A warning never changes a rating, the heat field, a verdict or a pick; this
 * reads {@code warnings} and nothing else.
 *
 * <p>Prints nothing while the feature is off, nothing for a window with no warning on a fresh feed (never an
 * all-clear), and only the stale line when the feed is stale. For a window the server never keys a warning to (a
 * night, High water, a past window) it prints nothing at all.
 *
 * @param {object} props
 * @param {?object} props.event the map's active EV row
 * @param {Array<object>} props.locations the whole roster (not the filtered pins)
 * @param {?object} [props.slotIndex] the callout's briefing-slot index (a place's own instant when it has no row)
 * @param {?object} [props.scoreIndex] the callout's score-row index (the last instant source)
 * @param {?string} [props.openId] the warning whose full text is open ({@link MapWarningPanel})
 * @param {?Function} [props.onToggle] opens or closes one warning's full text, given the warning id and the pressed button
 *        (so focus can return to it); withheld, no button is drawn
 */
export default function MapWarningChips({
  event = null, locations = [], slotIndex = null, scoreIndex = null, openId = null, onToggle = null,
}) {
  const { served, warnings, applies } = useMapWarnings({
    event, locations, slotIndex, scoreIndex,
  });
  if (!applies) return null;
  if (warnings.length === 0 && !served.stale) return null;
  // Nothing to expand on the stale-only line: it is exactly what it was.
  const strip = warnings.length === 0 ? (
    <WarningStrip warnings={warnings} testId="map-warning-chips" />
  ) : (
    <div data-testid="warning-strip" className="flex flex-col gap-1">
      <div
        data-testid="map-warning-chips"
        role="group"
        aria-label="Met Office weather warnings"
        className="flex flex-wrap gap-x-3 gap-y-1"
      >
        {warnings.map((warning) => {
          const open = openId === warning.warningId;
          const name = warningLabel(warning);
          return (
            <div key={warning.warningId} className="flex flex-wrap items-center gap-1.5">
              <WarningChip warning={warning} href={served.metOfficeUrl} />
              {onToggle && (
                <button
                  type="button"
                  data-testid="map-warning-toggle"
                  className="min-h-[32px] min-w-[44px] rounded border border-plex-border-light px-2 py-1 font-mono text-[10px] text-plex-text-secondary max-sm:min-h-[44px] hover:text-plex-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-plex-gold"
                  aria-expanded={open}
                  aria-controls={open ? MAP_WARNING_PANEL_ID : undefined}
                  aria-label={`${open ? 'Hide' : 'Show'} details of ${name}`}
                  onClick={(event) => onToggle(warning.warningId, event.currentTarget)}
                >
                  {open ? 'Hide' : 'Details'}
                </button>
              )}
            </div>
          );
        })}
      </div>
      <WarningFooter
        showAttribution
        stale={served.stale}
        lastCheckedAt={served.lastCheckedAt}
        attribution={served.attribution}
        href={served.metOfficeUrl}
      />
    </div>
  );
  return (
    <div className="pointer-events-none w-0 min-w-full" data-testid="wf-map-warnings-row">
      <div
        data-testid="wf-map-warnings"
        className="pointer-events-auto mt-1 inline-block max-w-full rounded border border-plex-border-light bg-plex-surface/90 p-1.5"
      >
        {strip}
      </div>
    </div>
  );
}

MapWarningChips.propTypes = {
  event: EVENT_SHAPE,
  locations: PropTypes.arrayOf(PropTypes.object),
  slotIndex: PropTypes.object,
  scoreIndex: PropTypes.object,
  openId: PropTypes.string,
  onToggle: PropTypes.func,
};

/**
 * One warning in full, for the Map (glance-to-map-plan.md G1): a capped panel hung under the chrome group at the
 * frame level (the window panel's own placement and z-index), or on a phone a bottom sheet (not `aria-modal`, but with the sheet's scrim). It reads the
 * same polled list as the chips and finds the open warning in it, so a warning the next poll withdraws simply closes
 * it. Prints nothing off a solar window, while the feature is off, or for an id that is no longer in force.
 *
 * @param {object} props
 * @param {?object} props.event the map's active EV row
 * @param {Array<object>} props.locations the whole roster
 * @param {?object} [props.slotIndex] the callout's briefing-slot index
 * @param {?object} [props.scoreIndex] the callout's score-row index
 * @param {?string} props.openId the warning to show in full
 * @param {Function} props.onClose closes the panel
 * @param {boolean} [props.isMobile] hosts the body in a bottom sheet
 */
export function MapWarningPanel({
  event = null, locations = [], slotIndex = null, scoreIndex = null, openId = null, onClose, isMobile = false,
}) {
  const { warnings, applies } = useMapWarnings({
    event, locations, slotIndex, scoreIndex,
  });
  const warning = applies && openId != null ? (warnings.find((w) => w.warningId === openId) ?? null) : null;
  if (!warning) return null;
  const body = (
    <div
      id={MAP_WARNING_PANEL_ID}
      data-testid="map-warning-panel"
      role="region"
      aria-label="Met Office weather warning in full"
      className="flex flex-col gap-2"
    >
      <WindowWarningsBody warnings={[warning]} showFooter={false} />
    </div>
  );
  if (isMobile) {
    // `modal={false}`: no `aria-modal`, so the sheet is not a foreign modal to the Map's Escape rules. It still draws
    // the sheet's scrim, and a tap on the map closes it (BottomSheet's own backdrop). No `closeOnEscape`: the pane's one
    // Escape rung closes this panel (a portal's key events bubble to the pane through React), so one press is one layer.
    return (
      <BottomSheet
        open
        onClose={onClose}
        label="Met Office weather warning"
        modal={false}
        reserveCloseStrip
      >
        {body}
      </BottomSheet>
    );
  }
  return (
    <div className="wf-warn-panel" data-testid="map-warning-panel-host">
      <button
        type="button"
        data-testid="map-warning-panel-close"
        className="wf-warn-panel-x font-mono"
        onClick={onClose}
      >
        Close
      </button>
      {body}
    </div>
  );
}

MapWarningPanel.propTypes = {
  event: EVENT_SHAPE,
  locations: PropTypes.arrayOf(PropTypes.object),
  slotIndex: PropTypes.object,
  scoreIndex: PropTypes.object,
  openId: PropTypes.string,
  onClose: PropTypes.func.isRequired,
  isMobile: PropTypes.bool,
};
