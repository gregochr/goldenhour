import PropTypes from 'prop-types';
import { badgeChannel } from '../../utils/windowFirstCards.js';
import { formatDateLabel } from '../../utils/conversions.js';
import { eventKicker } from '../../utils/askModel.js';

/**
 * One event card — a hot topic or an almanac entry the answer names (plan §1 #10, §2.9;
 * design `evCard`).
 *
 * <p>Coloured through {@link badgeChannel}, the app's one type → channel mapping, so an aurora
 * card is the aurora badge's green and an eclipse the eclipse badge's rose; the design's three hex
 * values are not used (plan §4 #11). An unrecognised type takes the plain channel rather than a
 * wrong colour.
 *
 * <h2>The safety note is never optional</h2>
 *
 * <p>When the served event carries a {@code safetyNote} (the solar eclipse's lens-filter warning;
 * the server re-joins it from the served topic and the model cannot write or drop it), it is
 * rendered <b>visibly</b>: outside every role gate, in no media query that hides it, and as a real
 * element in the accessibility tree (a {@code note}) that names itself a safety warning to a screen
 * reader, not a tooltip, a glyph or a colour. An event without one renders no note and no empty
 * container.
 *
 * @param {object} props
 * @param {{type: string, label: string, date: ?string, why: ?string, safetyNote: ?string}}
 *        props.event the served event card
 */
export default function AskEventCard({ event }) {
  const note = typeof event.safetyNote === 'string' && event.safetyNote.trim() !== ''
    ? event.safetyNote
    : null;
  const when = event.date ? formatDateLabel(event.date) : null;
  return (
    <article
      className="wf-ask-evc"
      data-channel={badgeChannel(event.type)}
      data-testid="ask-event-card"
    >
      <span className="wf-ask-evc-k">{eventKicker(event.type)}</span>
      <span className="wf-ask-evc-hl">{event.label}</span>
      {when && <span className="wf-ask-evc-wh" data-testid="ask-event-when">{when}</span>}
      {event.why && <span className="wf-ask-evc-why">{event.why}</span>}
      {note && (
        <p className="wf-ask-evc-safety" role="note" data-testid="ask-event-safety">
          <span aria-hidden="true">⚠ </span>
          <span className="sr-only">Safety warning: </span>
          {note}
        </p>
      )}
    </article>
  );
}

AskEventCard.propTypes = {
  event: PropTypes.shape({
    type: PropTypes.string,
    label: PropTypes.string,
    date: PropTypes.string,
    why: PropTypes.string,
    safetyNote: PropTypes.string,
  }).isRequired,
};
