import React, { useState } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import WindowSpotCard, { SPOT_SHAPE } from './WindowSpotCard.jsx';
import { locationTypeLabel } from '../utils/locationTypes.js';
import { REACH_TIERS, tierById } from '../utils/reachLens.js';
import { spotOrderStatement } from '../utils/windowFirstSpots.js';
import {
  ANY_TYPE_ID,
  RATING_FLOORS,
  browseCountLine,
  browseEmptyLine,
  browseSpots,
  hasRatings,
  ratingFloorById,
  spotTypes,
  typeOptionsFor,
} from '../utils/windowSpotBrowse.js';

/** One segmented control. The bar's own markup, so a chip reads the same wherever it sits. */
function Segment({ id, label, options, value, disabled, onSelect, segClassName = '' }) {
  return (
    <div className="wf-sheet-seg">
      <span className="wf-lens-k" id={`${id}-label`}>{label}</span>
      <div
        className={`wf-seg${segClassName ? ` ${segClassName}` : ''}`}
        role="group"
        aria-labelledby={`${id}-label`}
        data-testid={id}
      >
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
  /** Marks the axis. The rating floor is green on both surfaces, or it is two settings. */
  segClassName: PropTypes.string,
};

/**
 * What the footer may say about how long each control's choice lives.
 *
 * <p>Derived rather than written out, because the design's line — "Rating floor is remembered ·
 * reach and type reset each visit" — names three controls unconditionally and two of them are
 * conditional. Claiming a setting is remembered on a window that offers no such control describes
 * something the reader cannot see; claiming type resets when no type control was drawn does the
 * same.
 *
 * <p><b>All three now reset, and the rating floor's clause changed with the handoff.</b> The floor
 * used to be stored from here, so the sentence read "Rating floor is remembered". It is now a
 * page-wide lens the bar owns and the sheet only inherits — what the reader changes in the dialog
 * is a browsing deviation that dies with it, exactly like reach — so saying it is remembered would
 * describe the bar's floor while pointing at a control that no longer keeps one.
 *
 * @param {object}  offered
 * @param {boolean} offered.rating whether the rating floor is on screen
 * @param {boolean} offered.type   whether the type control is on screen
 * @param {boolean} offered.reach  whether the reach control can be moved by this user
 * @returns {?string} the sentence, or null when there is nothing true to say
 */
function persistenceNote({ rating, type, reach }) {
  const perVisit = [
    reach ? 'reach' : null, rating ? 'rating' : null, type ? 'type' : null,
  ].filter(Boolean);
  if (perVisit.length === 0) return null;
  const verb = perVisit.length === 1 ? 'resets' : 'reset';
  const last = perVisit[perVisit.length - 1];
  const named = perVisit.length === 1
    ? last
    : `${perVisit.slice(0, -1).join(', ')} and ${last}`;
  // Sentence case on the leading clause, so the footer does not open lowercase beside the count.
  return `${named.charAt(0).toUpperCase() + named.slice(1)} ${verb} each visit`;
}

/**
 * The drill-down — one window's whole spot list, with the filters that only matter while browsing.
 *
 * <h2>⚠️ The rating floor moved to the bar; this one inherits it</h2>
 *
 * <p>Plan §5f put the floor here and argued the scope. The design handoff overrules that and makes
 * it a page-wide lens; {@code utils/ratingLens.js} records why §5f's stated hazard does not fire.
 * What this sheet keeps is the <em>browsing</em> half: it opens on the bar's floor and its own
 * changes are local, which is precisely how the reach control here has always worked. Nothing in
 * this component reads or writes storage any more.
 *
 * <p>Its option list is one step longer than the bar's — the 5★ step lives here and nowhere else.
 * A floor that usually empties six windows at once is no use on a sticky bar; over one window's
 * whole list "show me only the best" is a real question, and it is a browsing choice that dies with
 * the dialog rather than a stored preference the bar could not then draw a pressed chip for.
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
 * @param {string}   props.barFloorId   the lens bar's active rating floor, on the same terms: the
 *        value the sheet opens on and the comparand for "widened for browsing"
 * @param {string}   [props.openTierId] the tier to OPEN on, where that differs from the bar's. The
 *        shell passes "any" for a window the lens emptied, which is the one case where inheriting
 *        would open a dialog with nothing in it
 * @param {boolean}  [props.reachLocked] true when reach is inert for this user (LITE)
 * @param {?Map<string, string[]>} [props.typesByName] location name → {@code locationType} array
 * @param {Function} props.onClose      dismisses the sheet
 * @param {Function} [props.onOpenSpot] opens the map centred on a spot
 */
export default function WindowSpotSheet({
  card, barTierId, barFloorId, openTierId, reachLocked = false, typesByName, onClose, onOpenSpot, escapeEnabled = true,
}) {
  const spots = card.allSpots || [];
  // Inherited from the bar, exactly as the tier below is, and local from there on. Type is the one
  // that starts loose rather than inheriting, because the bar has no type control to inherit from.
  const [ratingFloorId, setRatingFloorId] = useState(barFloorId);
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
  };

  // Keyed on DIRECTION, not on difference. `tierId !== barTierId` fires just as readily when the
  // reader has tightened the sheet below the bar's tier — and "widened for browsing" over a list
  // that is shorter than the strip behind it is simply false. A null limit is the loosest of all,
  // so it compares as +∞ rather than as absent.
  //
  // Both axes, since P15c: the floor is inherited too, and loosening it shows the reader spots the
  // page behind the dialog is hiding. That is the same statement about the same dialog, and marking
  // only one of the two ways to reach it would leave the other silent.
  const barLimit = tierById(barTierId)?.limitMinutes ?? null;
  const barFloor = ratingFloorById(barFloorId)?.min ?? null;
  const floorMin = ratingFloorById(ratingFloorId)?.min ?? null;
  const widened = (limitMinutes ?? Infinity) > (barLimit ?? Infinity)
    || (floorMin ?? 0) < (barFloor ?? 0);
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
      // Nothing here is lost by dismissing: three filters over data already in memory, none of
      // which this dialog persists. (It said the floor was "written the moment it is chosen" — that
      // write moved to the bar with P15c and is no longer made anywhere in this file, which is what
      // the footer's "Reach, rating and type reset each visit" has told the reader all along.)
      // ⚠️ Conditional since M2, and that IS the Escape order. `Modal` installs a document-level
      // Escape listener per instance, so two open dialogs both close on one press. Each layer
      // declines the key while something sits over it, which makes a press take exactly one layer
      // (plan-matrix §6 M2.5). Defaults to true, so a caller that does not stack is unchanged.
      closeOnEscape={escapeEnabled}
      /* ⚠️ ONE predicate, two consequences, and they must never come apart: the layer that
         answers Escape is the layer that is not `inert`. M5 measured the alternative in a browser —
         three `aria-modal` dialogs at once and a Tab out of the top one landing inside the popup
         underneath — so a stacked layer holds no tab stops and leaves the accessibility tree, while
         the top one keeps both. Derived from `escapeEnabled` rather than taking a second prop
         precisely so a future caller cannot set one and forget the other. See `Modal`'s own note
         for what this is NOT: it is not a focus trap, and Tab still leaves the topmost dialog. */
      stacked={!escapeEnabled}
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
              // The bar's own phone shortening of this same control, and deliberately origin-free:
              // the sheet inherits whatever origin the bar is on and takes no `originBase`, so
              // `Drive` is true under home and away alike. It read `How far` until 2026-09-05,
              // which shared a stem with the bar's then-`How far tonight` — when that caption
              // became `Drive from home`, this one was the only reach control on the tab still
              // speaking the old vocabulary, and c6's "one source" reads as one source only while
              // the two ends sound like one control.
              label="Drive"
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
              segClassName="wf-seg-rating"
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
  /**
   * Whether Escape closes THIS dialog. False while another layer sits over it — the shell owns that
   * ordering, because only it knows what else is open (plan-matrix §6 M2.5).
   */
  escapeEnabled: PropTypes.bool,
  card: PropTypes.shape({
    when: PropTypes.string.isRequired,
    kicker: PropTypes.string,
    time: PropTypes.string,
    allSpots: PropTypes.arrayOf(PropTypes.shape(SPOT_SHAPE)),
  }).isRequired,
  barTierId: PropTypes.string.isRequired,
  barFloorId: PropTypes.string.isRequired,
  openTierId: PropTypes.string,
  reachLocked: PropTypes.bool,
  typesByName: PropTypes.instanceOf(Map),
  onClose: PropTypes.func.isRequired,
  onOpenSpot: PropTypes.func,
};
