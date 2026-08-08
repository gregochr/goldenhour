import React, { useState } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import WindowSpotCard, { SPOT_SHAPE } from './WindowSpotCard.jsx';
import { locationTypeLabel } from '../utils/locationTypes.js';
import { REACH_TIERS, tierById } from '../utils/reachLens.js';
import { spotOrderStatement } from '../utils/windowFirstSpots.js';
import {
  ANY_RATING_ID,
  ANY_TYPE_ID,
  RATING_FLOORS,
  browseCountLine,
  browseEmptyLine,
  browseSpots,
  hasRatings,
  ratingFloorById,
  readStoredRatingFloorId,
  spotTypes,
  typeOptionsFor,
  writeStoredRatingFloorId,
} from '../utils/windowSpotBrowse.js';

/** One segmented control. The bar's own markup, so a chip reads the same wherever it sits. */
function Segment({ id, label, options, value, disabled, onSelect }) {
  return (
    <div className="wf-sheet-seg">
      <span className="wf-lens-k" id={`${id}-label`}>{label}</span>
      <div className="wf-seg" role="group" aria-labelledby={`${id}-label`} data-testid={id}>
        {options.map((option) => (
          <button
            key={option.id}
            type="button"
            data-testid={`${id}-option`}
            data-option={option.id}
            // `aria-pressed` rather than a radiogroup, matching the lens bar: these are toggle
            // buttons in a labelled group, not form inputs.
            aria-pressed={option.id === value}
            disabled={disabled}
            onClick={() => onSelect(option.id)}
            className={`wf-seg-btn${option.id === value ? ' on' : ''}`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

Segment.propTypes = {
  id: PropTypes.string.isRequired,
  label: PropTypes.string.isRequired,
  options: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
  })).isRequired,
  value: PropTypes.string.isRequired,
  disabled: PropTypes.bool,
  onSelect: PropTypes.func.isRequired,
};

/**
 * What the footer may say about how long each control's choice lives.
 *
 * <p>Derived rather than written out, because the design's line — "Rating floor is remembered ·
 * reach and type reset each visit" — names three controls unconditionally and two of them are
 * conditional. Claiming a floor is remembered on a window that offers no floor describes a setting
 * the reader cannot see; claiming type resets when no type control was drawn does the same.
 *
 * @param {object}  offered
 * @param {boolean} offered.rating whether the rating floor is on screen
 * @param {boolean} offered.type   whether the type control is on screen
 * @param {boolean} offered.reach  whether the reach control can be moved by this user
 * @returns {?string} the sentence, or null when there is nothing true to say
 */
function persistenceNote({ rating, type, reach }) {
  const parts = [];
  if (rating) parts.push('Rating floor is remembered');
  const perVisit = [reach ? 'reach' : null, type ? 'type' : null].filter(Boolean);
  if (perVisit.length === 1) parts.push(`${perVisit[0]} resets each visit`);
  if (perVisit.length === 2) parts.push(`${perVisit.join(' and ')} reset each visit`);
  if (parts.length === 0) return null;
  // Sentence case on whichever clause leads. Without this a window that offers no rating floor
  // opens its footer with a lowercase "reach resets each visit" beside the sentence-case count.
  const [first, ...rest] = parts;
  return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(' · ');
}

/**
 * The drill-down — one window's whole spot list, with the filters that only matter while browsing.
 *
 * <h2>Browsing filters live here, not on the lens bar</h2>
 *
 * <p>Plan §5c`:908` expected P11 to add a rating floor and a type control to the sticky bar and
 * warned that {@code scroll-margin-top: 60px} would then need re-measuring. It does not: the bar
 * keeps its one control and its measured 53.5px. The scope argument is in
 * {@code windowSpotBrowse.js} and the decision in plan §5f — briefly, reach is a fact about *today*
 * that governs every window on the page, while a rating floor and a type are about the list in
 * front of you, and a page-wide floor would leave a window header reading "best 5★" over a strip
 * its own control had emptied of everything below 4.
 *
 * <h2>The reach control inherits, and says so only when it has moved</h2>
 *
 * <p>{@code Adversarial Review.html} charge c6 ("Reach is stated in three places", Guilty) asks for
 * exactly one source: "the lens shows it, the rail stops repeating it, the drilldown inherits and
 * says 'widened for browsing'". So the sheet opens on the bar's tier and the header carries the
 * clause only while the two differ — a widening that is temporary by construction, since closing
 * the sheet unmounts the state that held it.
 *
 * <p><b>The reach control takes the same LITE lock the bar does.</b> §7 makes reach a PRO control
 * and {@code useReachLens} pins a LITE user to "Any" so that nothing is ever withheld. A second,
 * unlocked reach control one click away would hand back the ability to narrow that the greyed bar
 * had just reserved, and would make the "Pro" pill describe nothing. The rating floor and the type
 * are <em>not</em> gated: they need no per-user data, they withhold nothing a role could not
 * already see, and CLAUDE.md's rule is breadcrumbs rather than paywalls.
 *
 * <h2>No star in the header</h2>
 *
 * <p>The design's header reads {@code N spots · best B}. The star goes, and the count moves to the
 * footer where the sort claim already lives. {@code compareSpots} ranks rating-first and sorts
 * nulls last, so whenever <em>anything</em> in the list is rated the first card is the best-rated
 * one and carries its own badge, drawn 40px below — printing the number above it marks one fact
 * twice (§2.7). And where nothing is rated there is no best to state at all: the window card's own
 * header omits its star in exactly that case, so the header loses nothing it could have said.
 *
 * <p>It also removes the one thing here that could contradict the card behind it. That card's
 * {@code best N★} is deliberately NOT re-derived from any filtered set (§5c`:913-918`), so a sheet
 * header repeating it above a list its own rating floor had trimmed would print a star no card in
 * view could account for.
 *
 * @param {object}   props
 * @param {object}   props.card         the window card descriptor, carrying {@code allSpots}
 * @param {string}   props.barTierId    the lens bar's active tier — the comparand for "widened for
 *        browsing", and the tier the sheet inherits unless {@code openTierId} overrides it
 * @param {string}   [props.openTierId] the tier to OPEN on, where that differs from the bar's. The
 *        shell passes "any" for a window the lens emptied, which is the one case where inheriting
 *        would open a dialog with nothing in it
 * @param {boolean}  [props.reachLocked] true when reach is inert for this user (LITE)
 * @param {?Map<string, string[]>} [props.typesByName] location name → {@code locationType} array
 * @param {Function} props.onClose      dismisses the sheet
 * @param {Function} [props.onOpenSpot] opens the map centred on a spot
 */
export default function WindowSpotSheet({
  card, barTierId, openTierId, reachLocked = false, typesByName, onClose, onOpenSpot,
}) {
  const spots = card.allSpots || [];
  // Read once, lazily. The floor is the only one of the three that outlives the sheet; reach is
  // inherited from the bar and type deliberately starts loose, both of which cost nothing to
  // re-derive because closing unmounts this component.
  const [ratingFloorId, setRatingFloorId] = useState(
    () => readStoredRatingFloorId() ?? ANY_RATING_ID,
  );
  // Usually the bar's tier — the sheet inherits, which is charge c6's own remedy. The exception is
  // a window the lens emptied: opening THAT one on the tier that emptied it puts the reader in a
  // dialog whose whole content is "nothing matches", which is a door onto a wall. It opens widened
  // instead, says so in the header, and the widening still dies with the sheet.
  const [tierId, setTierId] = useState(openTierId ?? barTierId);
  const [typeId, setTypeId] = useState(ANY_TYPE_ID);

  const ratingOffered = hasRatings(spots);
  const typeOptions = typeOptionsFor(spots, typesByName);
  const limitMinutes = tierById(tierId)?.limitMinutes ?? null;
  const browseArgs = { spots, limitMinutes, ratingFloorId, typeId, typesByName };
  const visible = browseSpots(browseArgs);

  const chooseRating = (id) => {
    if (!ratingFloorById(id)) return;
    setRatingFloorId(id);
    writeStoredRatingFloorId(id);
  };

  // Keyed on DIRECTION, not on difference. `tierId !== barTierId` fires just as readily when the
  // reader has tightened the sheet below the bar's tier — and "widened for browsing" over a list
  // that is shorter than the strip behind it is simply false. A null limit is the loosest of all,
  // so it compares as +∞ rather than as absent.
  const barLimit = tierById(barTierId)?.limitMinutes ?? null;
  const widened = (limitMinutes ?? Infinity) > (barLimit ?? Infinity);
  const note = persistenceNote({
    rating: ratingOffered,
    type: typeOptions.length > 0,
    reach: !reachLocked,
  });

  return (
    <Modal
      label={`All spots — ${[card.kicker, card.when].filter(Boolean).join(' ')}`}
      onClose={onClose}
      bare
      // Nothing here is lost by dismissing: three filters over data already in memory, and the
      // rating floor is written the moment it is chosen rather than on close.
      closeOnEscape
      data-testid="window-spot-sheet"
    >
      <div className="wf-sheet-card">
        <div className="wf-sheet-head">
          {/* The KICKER as well as the title, and it is not decoration. On a lead card `when` is
              the bare event — "Sunset" — because the day has moved into the kicker; without it the
              header of a dialog opened from a six-window page reads "Sunset · 22:41" and names no
              day at all. Found in the browser, not by a test. It takes the card's own accent so the
              two headings read as the same heading. */}
          {card.kicker && (
            <span data-testid="window-spot-sheet-kicker" className="wf-sheet-k">
              {card.kicker}
            </span>
          )}
          <span data-testid="window-spot-sheet-title" className="wf-sheet-t">
            {[card.when, card.time].filter(Boolean).join(' · ')}
          </span>
          {/* c6's own remedy, and only while it is true. */}
          {widened && (
            <span data-testid="window-spot-sheet-widened" className="wf-sheet-w">
              widened for browsing
            </span>
          )}
          <button
            type="button"
            data-testid="window-spot-sheet-close"
            className="wf-sheet-x font-mono"
            onClick={onClose}
          >
            Close · Esc
          </button>
        </div>

        {/* The greying stops at the controls, exactly as the lens bar's does: WCAG 1.4.3 exempts an
            inactive component, which is what licenses a greyed tier — it does not license the
            upsell that explains it, which sits outside. */}
        <div className="wf-sheet-fbar">
          <div
            data-testid="window-spot-sheet-reach-wrap"
            className={`wf-sheet-seg-wrap${reachLocked ? ' wf-lens-locked' : ''}`}
          >
            <Segment
              id="window-spot-sheet-reach"
              label="How far"
              options={REACH_TIERS}
              value={tierId}
              // `disabled` as well as the wrapper's `pointer-events: none`, because pointer-events
              // does not stop a keyboard.
              disabled={reachLocked}
              onSelect={setTierId}
            />
          </div>
          {reachLocked && (
            <span data-testid="window-spot-sheet-upsell" className="wf-lens-pro">Pro</span>
          )}

          {/* Offered only where there is something to floor — see `windowSpotBrowse.js`. The gate
              keys on the same predicate, so a floor can never run without its control on screen. */}
          {ratingOffered && (
            <Segment
              id="window-spot-sheet-rating"
              label="At least"
              options={RATING_FLOORS}
              value={ratingFloorId}
              onSelect={chooseRating}
            />
          )}

          {/* Empty whenever the window's spots offer fewer than two distinct types, which is when a
              type control would be a label wearing a control's clothes. */}
          {typeOptions.length > 0 && (
            <Segment
              id="window-spot-sheet-type"
              label="Type"
              options={typeOptions}
              value={typeId}
              onSelect={setTypeId}
            />
          )}
        </div>

        {/* Always mounted, and the empty message lives INSIDE it — which is what the mock does
            and what `WindowPickDialog` does with its own prose. This is the card's only scroll
            container: swapping it for a sibling paragraph when nothing matches leaves a short
            viewport (a landscape phone, a 400% zoom) with a header, three rows of controls and a
            footer inside `overflow: hidden` and nothing able to scroll. */}
        <div data-testid="window-spot-sheet-list" className="wf-sheet-list">
          {visible.length > 0 ? visible.map((spot) => (
            <WindowSpotCard
              key={spot.key}
              spot={spot}
              // Words rather than icons, and only where the control that uses them is on screen:
              // the chip says "Seascape" and so does the card, which is the whole reason the type
              // is stated here at all.
              typeLabels={typeOptions.length > 0
                ? spotTypes(spot, typesByName).map(locationTypeLabel)
                : undefined}
              onOpen={() => onOpenSpot?.(spot)}
            />
          )) : (
            <p data-testid="window-spot-sheet-empty" className="wf-sheet-none font-mono">
              {/* The same arguments the list was built from, so the sentence is derived from the
                  very call that produced nothing rather than from a parallel reading of the state. */}
              {browseEmptyLine(browseArgs)}
            </p>
          )}
        </div>

        <div className="wf-sheet-foot font-mono">
          {/* The sort claim is dropped when nothing is drawn. `spotOrderStatement` derives its
              sentence from the spots, and over an empty list every key is absent — so it falls
              through to "Listed alphabetically", which is vacuously true and reads as a claim about
              an ordering that never happened. §6 asks that a footer's claimed sort match what is
              rendered; with nothing rendered the honest answer is to say nothing. */}
          {/* `role="status"` on the ALWAYS-mounted count, not on the conditional empty paragraph:
              a live region inserted into the DOM in the same commit as its content is unreliably
              announced, so the one element that survives every filter press is the one that can
              carry it. Pressing a chip otherwise rewrites the list in silence. */}
          <span data-testid="window-spot-sheet-count" role="status">
            {[browseCountLine(visible.length, spots.length),
              visible.length > 0 ? spotOrderStatement(visible) : null].filter(Boolean).join(' · ')}
          </span>
          {note && <span data-testid="window-spot-sheet-note">{note}</span>}
        </div>
      </div>
    </Modal>
  );
}

WindowSpotSheet.propTypes = {
  card: PropTypes.shape({
    when: PropTypes.string.isRequired,
    kicker: PropTypes.string,
    time: PropTypes.string,
    allSpots: PropTypes.arrayOf(PropTypes.shape(SPOT_SHAPE)),
  }).isRequired,
  barTierId: PropTypes.string.isRequired,
  openTierId: PropTypes.string,
  reachLocked: PropTypes.bool,
  typesByName: PropTypes.instanceOf(Map),
  onClose: PropTypes.func.isRequired,
  onOpenSpot: PropTypes.func,
};
