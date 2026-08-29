import React from 'react';
import PropTypes from 'prop-types';
import { formatArrivalDate } from '../utils/comingUpFeed.js';

/**
 * "The badge must land somewhere" (design §6): the line above the filter chips that gives a count
 * with something to find. Renders only while {@code badge} is non-null — the same condition that
 * puts a badge on the tab button, so the two either both show or both stay silent (plan D3/D4/D12).
 *
 * <h2>What this renders, and what it never composes</h2>
 *
 * <p>Every word describing WHY the entry scored what it did is server text — {@code scoreNote} for
 * a plain entry, or {@code joinNote} for one that won a coincidence merge (D10) and so has no
 * {@code scoreNote} of its own ({@code ComingUpAssembler.markScoreNotes} skips a merged winner
 * deliberately, because {@code joinNote} already explains the score). This component only places
 * {@code {bits, title, dates, scoreNote}} — plan §13's own annotation for this field — never
 * composing a sentence out of them.
 *
 * <h2>The arrival date is {@code enteredWindow}, not the entry's own date</h2>
 *
 * <p>The design bundle's demo copy prints a shower's OWN peak date next to "entered the window",
 * which cannot be literal: a peak 90 days out enters the window roughly 90 days before it peaks,
 * not on the peak itself. The demo is illustrative static text, not derived from the model's own
 * arithmetic (the same kind of bundle inconsistency plan §11 already catalogues elsewhere). This
 * component reports the date the field name promises — {@code entry.enteredWindow} — which is also
 * the exact date the badge's own {@code isNewEntry} test compares against.
 *
 * @param {object}   props
 * @param {?{band: 'announce'|'interrupt', count: ?number}} props.badge the derived badge state, or
 *        null to render nothing
 * @param {?object}  props.entry      the highest-bits qualifying arrival ({@code selectSinceEntry}),
 *                                    or null — must be non-null whenever {@code badge} is non-null
 * @param {function} props.onMarkSeen clears the badge and every NEW flag
 */
export default function WindowComingUpSinceLine({ badge = null, entry = null, onMarkSeen }) {
  if (!badge || !entry) return null;

  const isInterrupt = badge.band === 'interrupt';
  const note = entry.scoreNote ?? entry.joinNote ?? '';
  const dateLabel = formatArrivalDate(entry.enteredWindow);

  return (
    <div
      className={isInterrupt ? 'wf-cu-since wf-cu-since-rare' : 'wf-cu-since'}
      data-testid="coming-up-since"
    >
      {isInterrupt ? (
        <span>
          <b data-testid="coming-up-since-headline">{'◆ '}{entry.bits}{' bits'}</b>
          {` — the ${entry.title} entered the window, ${dateLabel}.`}
          {note && ` ${note}`}
        </span>
      ) : (
        <span>
          <b data-testid="coming-up-since-headline">{badge.count}{' announced'}</b>
          {` — the ${entry.title} entered the window, ${dateLabel}.`}
          {note && ` ${note}`}
          {' '}
          <span className="wf-cu-since-bits">{entry.bits}{' bits'}</span>
        </span>
      )}
      <button
        type="button"
        className="wf-cu-since-seen"
        data-testid="coming-up-since-mark-seen"
        onClick={onMarkSeen}
      >
        Mark seen
      </button>
    </div>
  );
}

WindowComingUpSinceLine.propTypes = {
  badge: PropTypes.shape({
    band: PropTypes.oneOf(['announce', 'interrupt']).isRequired,
    count: PropTypes.number,
  }),
  entry: PropTypes.shape({
    bits: PropTypes.number.isRequired,
    title: PropTypes.string.isRequired,
    enteredWindow: PropTypes.string.isRequired,
    scoreNote: PropTypes.string,
    joinNote: PropTypes.string,
  }),
  onMarkSeen: PropTypes.func.isRequired,
};
