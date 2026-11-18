import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import { buildAreaSheet } from '../utils/areaFind.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';

/**
 * One area's spots (find-and-favourites-plan.md F2): what a reader reaches from an Areas row in Find on Plan and
 * Coming up.
 *
 * <p>A dialog like the place sheet, and the same shell shape ({@code wf-sheet-card}). It names the area, what kind
 * it is, how many spots it holds and the drive range, then lists the spots with each one's OWN best window, best
 * first and nearer first. Each row opens that place's next-days sheet, which stacks over this one (two deep, Find
 * having closed on the pick). The foot hands the area to the Map.
 *
 * <p><b>Never a verdict.</b> An area has no rating of its own; every figure here is a place's, with its window, and
 * the line under the title says so. A place outside a Lite reader's picks reads as outside them, never as unrated.
 *
 * @param {object}   props
 * @param {object}   props.area       the directory entry
 * @param {Array}    props.spots      the heat spots
 * @param {Array}    props.windows    the strip's descriptors
 * @param {?object}  [props.scoreIndex] from {@code buildScoreIndex}
 * @param {?Map}     [props.reachById] the reach map the page plans from
 * @param {*}        [props.allowedLocationIds] the briefing's served allowed list, on a shaped body only
 * @param {boolean}  [props.stacked]  a place sheet is over this one: inert, no {@code aria-modal}, Escape withheld
 * @param {?Function} [props.restoreFocusFallback] where focus goes on close when its opener cannot take it
 * @param {Function} props.onClose    dismisses the sheet
 * @param {Function} props.onOpenSpot opens a place's next-days sheet, given the heat spot
 * @param {?Function} [props.onShowOnMap] hands the area to the Map; absent when there is no map door
 */
export default function AreaSheet({
  area, spots, windows, scoreIndex = null, reachById = null, allowedLocationIds = null,
  stacked = false, restoreFocusFallback = null, onClose, onOpenSpot, onShowOnMap = null,
}) {
  const sheet = useMemo(
    () => buildAreaSheet(area, spots, windows, { scoreIndex, reachById, allowedLocationIds }),
    [area, spots, windows, scoreIndex, reachById, allowedLocationIds],
  );
  const meta = [sheet.kindLabel, sheet.regionName, `${sheet.count} ${sheet.count === 1 ? 'spot' : 'spots'}`,
    sheet.driveRange ? `${sheet.driveRange} drive` : null].filter(Boolean);

  return (
    <Modal
      label={`${sheet.name} — ${sheet.count} ${sheet.count === 1 ? 'spot' : 'spots'}`}
      onClose={onClose}
      bare
      closeOnEscape={!stacked}
      stacked={stacked}
      restoreFocusFallback={restoreFocusFallback}
      data-testid="area-sheet"
    >
      <div className="wf-sheet-card">
        <div className="wf-sheet-head">
          <span data-testid="area-sheet-title" className="wf-sheet-t">{sheet.name}</span>
          <span data-testid="area-sheet-meta" className="wf-sheet-w font-mono">{meta.join(' · ')}</span>
          <button type="button" data-testid="area-sheet-close" className="wf-sheet-x font-mono" onClick={onClose}>
            Close · Esc
          </button>
        </div>

        <div data-testid="area-sheet-rows" className="wf-loc-rows">
          <p data-testid="area-sheet-note" className="px-3 pt-2 text-xs text-plex-text-muted">
            {sheet.gatedCount > 0
              ? `Each spot's own best window. ${sheet.gatedCount} ${sheet.gatedCount === 1 ? 'is' : 'are'} outside your picks, so their ratings are not shown.`
              : "Each spot's own best window. An area has no rating of its own."}
          </p>
          {sheet.rows.length === 0 ? (
            <p data-testid="area-sheet-empty" className="px-3 py-3 text-sm text-plex-text-muted">
              {"None of this area's spots are on the plan yet."}
            </p>
          ) : (
            <ul className="m-0 list-none p-0">
              {sheet.rows.map((row) => (
                <li key={row.spot.id ?? row.spot.name}>
                  <button
                    type="button"
                    data-testid="area-sheet-spot"
                    data-gated={row.gated ? 'true' : undefined}
                    className="flex w-full items-center justify-between gap-3 border-0 bg-transparent px-3 py-2 text-left hover:bg-plex-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-plex-gold"
                    onClick={() => onOpenSpot(row.spot)}
                  >
                    <span className="flex min-w-0 flex-col">
                      <span className="truncate text-sm font-semibold text-plex-text">{row.spot.name}</span>
                      <span className="truncate text-xs text-plex-text-muted">
                        {[row.spot.regionName || null, row.drive == null ? null : formatDriveDuration(row.drive)]
                          .filter(Boolean).join(' · ')}
                      </span>
                    </span>
                    <span data-testid="area-sheet-spot-figure" className="shrink-0 text-right font-mono text-xs text-plex-text">
                      {/* The words a row reads as: its own best window, or the honest reason there is none. */}
                      {row.gated && 'Outside your picks'}
                      {!row.gated && row.top && `${row.top.rating}★ · ${row.top.label}`}
                      {!row.gated && !row.top && <span className="text-plex-text-muted">Not rated</span>}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="wf-sheet-foot font-mono">
          {onShowOnMap ? (
            <button type="button" data-testid="area-sheet-map" className="wf-loc-map" onClick={() => onShowOnMap(area)}>
              {'Show on map '}
              <span aria-hidden="true">→</span>
            </button>
          ) : (
            <span data-testid="area-sheet-nomap">The map opens once the forecast loads.</span>
          )}
        </div>
      </div>
    </Modal>
  );
}

AreaSheet.propTypes = {
  area: PropTypes.shape({
    id: PropTypes.number,
    name: PropTypes.string.isRequired,
    kind: PropTypes.string,
    regionName: PropTypes.string,
    locationIds: PropTypes.arrayOf(PropTypes.number),
  }).isRequired,
  spots: PropTypes.arrayOf(PropTypes.object),
  windows: PropTypes.arrayOf(PropTypes.object),
  scoreIndex: PropTypes.shape({ byId: PropTypes.instanceOf(Map), byName: PropTypes.instanceOf(Map) }),
  reachById: PropTypes.instanceOf(Map),
  allowedLocationIds: PropTypes.arrayOf(PropTypes.number),
  stacked: PropTypes.bool,
  restoreFocusFallback: PropTypes.func,
  onClose: PropTypes.func.isRequired,
  onOpenSpot: PropTypes.func.isRequired,
  onShowOnMap: PropTypes.func,
};
