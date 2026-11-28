import React, { useId, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { useWarnings } from '../../context/WarningsContext.jsx';
import {
  validityCompact, validityText, warningLabel, warningsForMapWindow,
} from '../../utils/warnings.js';
import WarningStrip from '../WarningStrip.jsx';
import WarningChip, { ServedWarningFooter } from '../WarningChip.jsx';
import { WarningDetail, WHAT_SHOULD_I_DO_URL } from '../WindowWarningsBody.jsx';

/** The Map's active window row, as far as the warnings read it. */
const EVENT_SHAPE = PropTypes.shape({
  kind: PropTypes.string,
  past: PropTypes.bool,
  date: PropTypes.string,
  eventType: PropTypes.string,
});

/** How many warning rows show before the rest wait behind "+N more warnings". */
const VISIBLE_ROWS = 3;

/**
 * The polled warnings that apply to the Map's window, matched on every render (never cached or trusted from a stored
 * row's ids), so a withdrawn or new warning changes at the next poll (the licence's 10 minutes).
 *
 * @param {object} args the same four inputs both mounts take
 * @returns {{served: object, warnings: Array<object>, applies: boolean}} {@code applies} is false off a solar window
 *          that is not past, and while the feature is off
 */
export function useMapWarnings({
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

/** The drawn chevron: 14px, pointing down closed and turning to point up open (none of the turn under reduced motion). */
function Chevron({ open }) {
  return (
    <span
      aria-hidden="true"
      data-testid="map-warning-chevron"
      className={`flex h-5 w-5 flex-none items-center justify-center text-plex-text transition-transform duration-150 motion-reduce:transition-none ${open ? 'rotate-180' : ''}`}
    >
      <svg width="14" height="14" viewBox="0 0 14 14">
        <path d="M3 5.5l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </span>
  );
}

Chevron.propTypes = { open: PropTypes.bool.isRequired };

/** One warning opened in place: the full validity, then the warning's text, then the two Met Office doors. */
function WarningRegion({ id, warning, metOfficeUrl }) {
  const full = validityText(warning);
  return (
    <div
      id={id}
      role="region"
      aria-label={`${warningLabel(warning)}, in full`}
      data-testid="map-warning-region"
      className="wf-warning-region flex max-h-[300px] flex-col gap-2 overflow-y-auto border-t border-plex-border bg-plex-surface px-2.5 pb-2.5 pt-2 max-sm:max-h-[44vh]"
    >
      {full && <p data-testid="map-warning-full-validity" className="font-mono text-[11px] text-plex-text-secondary">{full}</p>}
      <WarningDetail warning={warning} showChip={false} bare showValidity={false} />
      <div className="flex flex-col gap-1 text-[12.5px]">
        <a
          data-testid="map-warning-source-link"
          className="underline"
          href={metOfficeUrl}
          target="_blank"
          rel="noopener noreferrer"
        >
          Read it on the Met Office site
          <span aria-hidden="true">{' ↗'}</span>
          <span className="sr-only">, opens in a new tab</span>
        </a>
        <a
          data-testid="map-warning-advice-link"
          className="underline"
          href={WHAT_SHOULD_I_DO_URL}
          target="_blank"
          rel="noopener noreferrer"
        >
          What should I do? Advice on severe weather
          <span aria-hidden="true">{' ↗'}</span>
          <span className="sr-only">, opens in a new tab</span>
        </a>
      </div>
    </div>
  );
}

WarningRegion.propTypes = {
  id: PropTypes.string.isRequired,
  warning: PropTypes.object.isRequired,
  metOfficeUrl: PropTypes.string,
};

/**
 * The Map tab's Met Office warnings (weather-warnings-plan.md W4; the row design is map-warning-row-plan.md M1-M4): the
 * warnings in force for the window on screen, one disclosure row each, in one box the width of the event bar, with the
 * licence's one-line attribution (itself the link to the Met Office) and the stale admission at the foot.
 *
 * <p>A row of its own INSIDE {@code .wf-map-chrome-tl}, under the hot-topic chips and never inside that bar (a warning
 * is not a hot topic), so it shares the group's width bound and label obstacle, and the group's height observer
 * ({@code useChromeTlHeight}) re-places the labels and the panels hung under the group whenever this row appears,
 * disappears, opens or changes height. The wrapper is {@code pointer-events: none} and the box {@code auto}, so the gaps
 * never swallow a map drag. The wrapper is {@code width: 0; min-width: 100%}: it takes the width the control and the
 * topic chips already give the (shrink-to-fit) group and contributes none of its own, so a long warning wraps inside the
 * group and can never widen the label obstacle (the measured 504px bound in {@code index.css}); the box is
 * {@code w-full} of it.
 *
 * <p>No geometry, outline or shading is ever drawn on the map for a warning: the Met Office style guide v1.4 forbids
 * overlaying warning geometry with other information (plan section 12), so the text row is the whole map presence.
 * Each warning is ONE {@code <button aria-expanded aria-controls>}: the level chip as text (never a link, since a
 * link is never inside a button), the compact validity in UK time ({@code validityCompact}, never split: too narrow and
 * it drops whole under the chip), and a drawn chevron pinned right. Pressing it opens the warning in full in place,
 * directly under its row ({@link WarningRegion}); {@code MapView} holds which one, so one is open at a time and
 * Escape closes it. Beyond three warnings a "+N more" row reveals the rest. A stale feed with no warning has nothing
 * to expand and prints only the stale line. A warning never changes a rating, the heat field, a verdict or a pick; this
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
 * @param {?string} [props.openId] the warning whose full text is open under its row
 * @param {?Function} [props.onToggle] opens or closes one warning, given the warning id and the pressed row (so focus
 *        can return to it)
 */
export default function MapWarningChips({
  event = null, locations = [], slotIndex = null, scoreIndex = null, openId = null, onToggle = null,
}) {
  const { served, warnings, applies } = useMapWarnings({
    event, locations, slotIndex, scoreIndex,
  });
  const uid = useId();
  const [showAll, setShowAll] = useState(false);
  if (!applies) return null;
  if (warnings.length === 0 && !served.stale) return null;
  const shown = showAll ? warnings : warnings.slice(0, VISIBLE_ROWS);
  const extra = warnings.length - shown.length;
  // Nothing to expand on the stale-only line: it is exactly what it was.
  const strip = warnings.length === 0 ? (
    <WarningStrip warnings={warnings} testId="map-warning-chips" className="px-2 py-1.5" />
  ) : (
    <div data-testid="warning-strip" className="flex flex-col">
      <div
        data-testid="map-warning-chips"
        role="group"
        aria-label="Met Office weather warnings"
        className="flex flex-col divide-y divide-plex-border"
      >
        {shown.map((warning) => {
          const open = openId === warning.warningId;
          const regionId = `wf-mw-${uid}-${warning.warningId}`.replace(/\s/g, '_');
          const validity = validityCompact(warning);
          return (
            <div key={warning.warningId}>
              <button
                type="button"
                data-testid="map-warning-row"
                className="flex min-h-[36px] w-full items-center gap-2 px-2 py-1.5 text-left hover:bg-plex-surface-light aria-expanded:bg-plex-surface-light focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold"
                aria-expanded={open}
                aria-controls={open ? regionId : undefined}
                onClick={(click) => onToggle?.(warning.warningId, click.currentTarget)}
              >
                <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2.5 gap-y-0.5">
                  <WarningChip warning={warning} />
                  {validity && (
                    <>
                      <span
                        data-testid="map-warning-validity"
                        aria-hidden="true"
                        className="whitespace-nowrap font-mono text-[11px] text-plex-text"
                      >
                        {validity}
                      </span>
                      <span className="sr-only">{`, valid ${validity} UK time`}</span>
                    </>
                  )}
                </span>
                <Chevron open={open} />
              </button>
              {open && <WarningRegion id={regionId} warning={warning} metOfficeUrl={served.metOfficeUrl} />}
            </div>
          );
        })}
      </div>
      {extra > 0 && (
        <button
          type="button"
          data-testid="map-warning-more"
          className="w-full border-t border-plex-border px-2 py-1.5 text-left font-mono text-[11px] text-plex-text-secondary hover:bg-plex-surface-light focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold"
          onClick={() => setShowAll(true)}
        >
          {`+${extra} more warning${extra === 1 ? '' : 's'}`}
        </button>
      )}
      <ServedWarningFooter showAttribution guideAtEnd className="border-t border-plex-border px-2 py-1.5" />
    </div>
  );
  return (
    <div className="pointer-events-none w-0 min-w-full" data-testid="wf-map-warnings-row">
      <div
        data-testid="wf-map-warnings"
        className="pointer-events-auto mt-1 w-full overflow-hidden rounded-[10px] border border-plex-border-light bg-plex-surface/90"
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
