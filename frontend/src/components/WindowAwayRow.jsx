import React from 'react';
import PropTypes from 'prop-types';

/**
 * The dashed row that stands where an away day's windows would have been.
 *
 * <p>Mock {@code .skipped} (:48-50), rendered at :491. Three deviations, each recorded in
 * {@code windowFirstAway.js} where the reasoning belongs: the row sits in date order rather than
 * after the whole list, the mock's multi-user/solo demo toggle is not built, and the
 * "Mark yourself back →" action is dropped because its surface is ADMIN-only and unreachable from
 * this arm.
 *
 * <h2>Two tones, and neither is the mock's</h2>
 *
 * <p>The mock puts the row body on {@code --ink-3} at 11px and the date range on {@code --ink-2}.
 * Muted at that size on these surfaces has now failed AA on this redesign six times — the spot
 * card's sub-lines, the attribute row's dim tone, the rail footer, the card's {@code best N★} and
 * its reach clause. So the body takes {@code --color-plex-text-secondary} and the date range the
 * full {@code --color-plex-text}, keeping the mock's two-step hierarchy with both steps legible.
 *
 * <h2>The second clause is not filler</h2>
 *
 * <p>"Sun times still show in the rail" is the one thing this row can offer a reader who was going
 * to shoot anyway: P4c settled that an away tile <b>keeps</b> its sunrise and sunset, because those
 * are almanac and true whether or not a forecast ran. Without the sentence the row is purely a
 * subtraction, and a reader has no reason to look up at the tile that still has something for them.
 *
 * @param {object}   props
 * @param {string}   props.label       `Mon 3` or `Mon 3 – Tue 4`
 * @param {?string}  [props.note]      the travel range's own note, or null when there is none to
 *                                     attribute — see {@code windowFirstAway.js}
 * @param {number}   props.windowCount how many windows the away days took with them
 */
export default function WindowAwayRow({ label, note, windowCount }) {
  const windows = windowCount === 1 ? '1 window not forecast' : `${windowCount} windows not forecast`;
  return (
    <div data-testid="window-away-row" className="wf-away">
      <span>
        <span aria-hidden="true">✈ </span>
        <b data-testid="window-away-label">{label}</b>
        {` · away — ${windows}`}
      </span>
      {/* Its own span rather than a clause, so the operator's free-text note never has to fit
          someone else's grammar. The sentence beside it is complete without one. */}
      {note && <span data-testid="window-away-note">{note}</span>}
      <span data-testid="window-away-almanac">Sun times still show in the rail</span>
    </div>
  );
}

WindowAwayRow.propTypes = {
  label: PropTypes.string.isRequired,
  note: PropTypes.string,
  windowCount: PropTypes.number.isRequired,
};
