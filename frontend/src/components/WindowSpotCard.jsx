import React from 'react';
import PropTypes from 'prop-types';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { leaveBy } from '../utils/leaveBy.js';
import { spotBadgeStyle } from '../utils/windowFirstSpots.js';

/**
 * One spot, as a card — the film strip's unit and the drill-down grid's.
 *
 * <h2>Extracted rather than copied, and the two surfaces are the reason</h2>
 *
 * <p>P11 draws the same card in a second place. The house rule elsewhere in this project is to
 * duplicate a JSX frame and share only the pure derivation — {@code SurgeRunRow} against
 * {@code TideRunRow} — but that pairing is two <em>different</em> things that happen to look alike
 * (an almanac and a forecast), where these are one thing in two containers. The rules this card
 * carries are exactly the ones a copy loses first, and P10′'s review caught a copy losing one: a
 * sibling peek panel drew {@code ☆☆☆☆☆} for an unrated spot because the rule "absence is not zero"
 * did not travel with the markup. One component, one place to break it.
 *
 * <p>The proof the extraction was pure is that every assertion {@code WindowSpotStrip.test.jsx}
 * already carried passes <b>unchanged</b> — no test-id, attribute or string moved, and the file was
 * not touched to make the extraction land. It does grow in this commit, but by addition only, for
 * P11's own trigger and suppression rules.
 *
 * <h2>What is absent is deliberate in four places</h2>
 *
 * <ul>
 *   <li><b>No rating badge when the rating is null.</b> An unrated spot is one nothing has looked
 *       at, which is a different statement from a poor one — the window header omits its own star
 *       for the same reason, and {@link spotBadgeStyle} returns null rather than a grey placeholder
 *       so the rule cannot be bypassed by rendering the element anyway.</li>
 *   <li><b>No drive line when the drive time is unknown.</b> Plan §2.5 rule 1: absence means
 *       "unknown", never "out of reach", and it is the normal state for a user with no home
 *       postcode.</li>
 *   <li><b>No leave-by line without both a drive time and this slot's own event time.</b> The two
 *       absences mean different things and both mean silence — {@link leaveBy} carries which and
 *       why. Note it needs the <em>drive</em>, not the reach line: {@link reachLine} prints on
 *       either half, so a user who has saved a postcode but never run the drive-time calculation
 *       gets {@code 🚗 47 mi} with no leave-by under it. That state is ordinary, not
 *       transitional.</li>
 *   <li><b>No type line unless a caller asks for one.</b> The strip has no type control, so a type
 *       word there would be a fact with nothing to do; the sheet has one, so the card and the
 *       control speak the same vocabulary. {@code typeLabels} defaults to nothing rather than to the
 *       location's full set, which keeps the strip's markup byte-identical to what it shipped.</li>
 * </ul>
 *
 * @param {object}   props
 * @param {object}   props.spot      a descriptor from {@code buildWindowSpots}
 * @param {string[]} [props.typeLabels] display labels for the spot's location types, already
 *        ordered and filtered by the caller. Empty or absent renders no type text at all.
 * @param {Function} props.onOpen    what the click does — the map, or (M4) this place's own sheet
 * @param {string}   [props.openLabel] the words naming that destination. Defaults to the map, so a
 *        caller written before M4 renders exactly what it always did
 * @param {Function} [props.onMouseEnter] peek handlers; the strip wires them, the sheet does not
 * @param {Function} [props.onMouseLeave]
 * @param {Function} [props.onFocus]
 * @param {Function} [props.onBlur]
 */
export default function WindowSpotCard({
  spot, typeLabels, onOpen, openLabel = '◍ Open on map →',
  onMouseEnter, onMouseLeave, onFocus, onBlur,
}) {
  const badge = spotBadgeStyle(spot.rating);
  const reach = reachLine(spot.driveMinutes, spot.distanceMiles);
  const leave = leaveBy(spot.solarEventTime, spot.driveMinutes);
  // The region and the types share one line and one separator, so a spot with no region still
  // reads `Seascape` rather than ` · Seascape`. Both halves are independently absent.
  const meta = [spot.regionName, ...(typeLabels || [])].filter(Boolean).join(' · ');

  return (
    <button
      type="button"
      data-testid="window-spot"
      data-rating={spot.rating ?? undefined}
      data-far={spot.far ? 'true' : undefined}
      onClick={onOpen}
      // All four are the strip's peek handlers and all four are absent in the sheet, which has no
      // peek: the pointer and focus paths are wired symmetrically there so a keyboard user gets the
      // same shortcut, through the same delay that stops a Tab into a partly-offscreen card from
      // flashing the panel before scroll-into-view dismisses it. See `useSpotPeek`.
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      onFocus={onFocus}
      onBlur={onBlur}
      className={`wf-spot${spot.far ? ' far' : ''}`}
    >
      <span className="flex items-start justify-between" style={{ gap: '8px' }}>
        <span
          className="text-plex-text"
          style={{ fontSize: '12.5px', fontWeight: 600, lineHeight: 1.25 }}
        >
          {spot.locationName}
        </span>
        {/* Omitted, never shown as a dash: an unrated spot is one nothing has looked at,
            which is a different statement from a poor one. The window header omits its
            own star for the same reason. */}
        {badge && (
          <span
            data-testid="window-spot-rating"
            className="font-mono whitespace-nowrap"
            style={{
              fontSize: '10px',
              fontWeight: 600,
              padding: '2px 5px',
              borderRadius: '5px',
              flex: 'none',
              ...badge,
            }}
          >
            {`${spot.rating}★`}
          </span>
        )}
      </span>

      {/* `window-spot-region` is the meta LINE, not the region alone — it carries the type words
          in the sheet, and can carry neither when a slot has no region and no join. The test-id
          keeps P6's name because renaming it would break the one proof that this extraction was
          pure, which is `WindowSpotStrip.test.jsx` passing unedited. */}
      {meta && (
        <span
          data-testid="window-spot-region"
          className="font-mono text-plex-text-secondary"
          style={{ fontSize: '10px' }}
        >
          {meta}
        </span>
      )}

      {/* Absent, not zeroed. No drive time means "unknown", never "out of reach" —
          plan §2.5 — and this is the normal state for a user with no home postcode. */}
      {reach && (
        <span
          data-testid="window-spot-reach"
          className={`wf-spot-reach font-mono${spot.far ? ' far' : ' text-plex-text-secondary'}`}
          style={{ fontSize: '10px' }}
        >
          {reach}
        </span>
      )}

      {/* The plan, under the cost. Absent whenever either term is unknown — `leaveBy` carries the
          two ways that happens and why both are silence rather than a guess.

          The time takes the card's brightest ink at 600 while the words stay secondary: the
          design's own two-tone (`.lv` / `.lv b`), expressed in this app's tokens rather than in its
          #EBD9A8. That hex is `--color-segment-active`, whose meaning on this screen is "this
          control is on", and it would be a third gold on a card already carrying the lead wash and
          the badge — so the design's gold is REJECTED here and `--color-plex-text` carries the
          emphasis instead. Measured over the card's own composited backdrop: 14.99:1 for the time
          and 7.03:1 for the words, 13.98 and 6.74 on the lead card's gold wash.

          The line takes no `far` tint, unlike the reach line above it. That mark says a DRIVE is
          beyond today's default tier, and `index.css` justifies it as sitting on "the very line
          that is tinted" — the line printing the drive figure. A second tinted line about the same
          drive would double the accent without adding a fact. */}
      {leave && (
        <span
          data-testid="window-spot-leave"
          className="font-mono text-plex-text-secondary"
          style={{ fontSize: '10px' }}
        >
          {/* Hidden from the accessible name: the card is a button named from its contents, and
              U+21B0 is announced by some screen readers as "upwards arrow with tip leftwards" —
              four words of furniture in front of the one line that tells a reader when to go.
              The visible words are all still in the name, so 2.5.3 holds. */}
          <span aria-hidden="true">↰</span>
          {' leave '}
          <b className="text-plex-text" style={{ fontWeight: 600 }}>{leave}</b>
        </span>
      )}

      {/* ⚠️ It names WHERE THE CLICK GOES, so it is the caller's word and not this card's. M4 (D-3)
          retargeted the popup's copy of the ranked strip from the map to the location sheet, while
          the drill-down sheet's copy still opens the map — one component, two destinations, and a
          card that promised a map and delivered a sheet would be lying in its own accessible name
          (the span is inside the button). Defaulted to the map so every caller written before M4 is
          byte-identical, which is plan §3 rule 10's caller-opt-in shape. */}
      <span
        className="wf-spot-open font-mono text-plex-text-secondary"
        style={{ fontSize: '10px', marginTop: 'auto' }}
      >
        {openLabel}
      </span>
    </button>
  );
}

/** `🚗 45 min · 23 mi`, dropping whichever half this user has no data for. */
function reachLine(driveMinutes, distanceMiles) {
  const drive = formatDriveDuration(driveMinutes);
  const distance = distanceMiles == null ? null : `${distanceMiles} mi`;
  const parts = [drive, distance].filter(Boolean);
  return parts.length === 0 ? null : `🚗 ${parts.join(' · ')}`;
}

/** The descriptor shape, shared with {@code WindowSpotStrip}'s own prop type. */
export const SPOT_SHAPE = {
  key: PropTypes.string.isRequired,
  locationId: PropTypes.number,
  locationName: PropTypes.string.isRequired,
  regionName: PropTypes.string,
  /** This location's own solar event time, a bare UTC instant. Feeds {@link leaveBy}. */
  solarEventTime: PropTypes.string,
  rating: PropTypes.number,
  driveMinutes: PropTypes.number,
  distanceMiles: PropTypes.number,
  far: PropTypes.bool,
};

WindowSpotCard.propTypes = {
  spot: PropTypes.shape(SPOT_SHAPE).isRequired,
  typeLabels: PropTypes.arrayOf(PropTypes.string),
  onOpen: PropTypes.func.isRequired,
  openLabel: PropTypes.string,
  onMouseEnter: PropTypes.func,
  onMouseLeave: PropTypes.func,
  onFocus: PropTypes.func,
  onBlur: PropTypes.func,
};
