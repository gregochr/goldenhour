import React from 'react';
import PropTypes from 'prop-types';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { firstClause } from '../utils/firstClause.js';

/** Filled/hollow glyph row — `★★★★☆`. Ratings are integers, so no half star exists. */
function starGlyphs(rating) {
  const filled = Math.max(0, Math.min(5, Math.round(rating)));
  return '★'.repeat(filled) + '☆'.repeat(5 - filled);
}

/**
 * The Close-to-home hover peek: three lines, tooltip weight.
 *
 * <p><b>Match the weight to the gesture.</b> This panel used to carry the map popup's headline —
 * name, stars, drive, the full generated paragraph and two score bars — summoned by a pointer
 * merely passing over a card. Modal-weight content on an accidental trigger, with no shell and a
 * `max-height` that cut the second score bar in half, read as the page glitching rather than a
 * panel opening. Hover now gives a glance; the click gives the full read in the map overlay that
 * already exists, so nothing has left the product — the scores and the whole narrative moved one
 * click away, to the surface that was always their home.
 *
 * <p>Three lines, and the list is closed: the rating, the drive, one clause of the why, and the
 * prompt. Explicitly absent — score bars, the generated-at timestamp, the region line, tide
 * detail, a header bar, a ✕ and a footer. The name is absent too, because the arrow tethers the
 * panel to the card that just named it.
 *
 * <p><b>Pointer-only.</b> Hover has no touch equivalent, so the caller gates this on viewport and
 * pointer type, and the card stays a button — the peek is a shortcut, never the only route to the
 * information. It is {@code aria-hidden} for the same reason: a screen reader gets the card's own
 * text and the map, not a floating duplicate it cannot dismiss. That is also what licenses the
 * click handler on a non-interactive element here — it duplicates the card's own activation, which
 * is a real focusable button one tab stop away.
 *
 * @param {Object}    props
 * @param {?number}   props.rating       1–5 stars, or null when unrated
 * @param {?number}   props.driveMinutes the caller's drive time, or null
 * @param {?string}   props.summary      Claude's sentence for this slot — truncated to one clause
 * @param {Object}    props.position     fixed-position coordinates, `{ left, top }` or
 *                                       `{ left, bottom }` in px, from `previewPlacement`
 * @param {string}    props.placement    'below' | 'above' — which side of the card it sits on
 * @param {number}    props.arrowLeft    arrow offset from the panel's left edge, in px
 * @param {Function}  props.onOpen       opens the map overlay for this spot — the prompt's action
 * @param {Function}  props.onPointerEnter keeps the panel open while the pointer rests on it
 * @param {Function}  props.onPointerLeave starts the dismissal grace
 */
export default function CardHoverPreview({
  rating = null,
  driveMinutes = null,
  summary = null,
  position,
  placement = 'below',
  arrowLeft = 24,
  onOpen,
  onPointerEnter,
  onPointerLeave,
}) {
  const drive = formatDriveDuration(driveMinutes);
  const clause = firstClause(summary);

  return (
    // Not a <button>: the panel is aria-hidden and pointer-only, and wrapping three lines of
    // prose in a control would put a duplicate of the card's own action into the accessibility
    // tree for anyone the panel is invisible to. Keyboard users reach the same action on the card.
    <div
      data-testid="cth-hover-preview"
      aria-hidden="true"
      className="cth-hover-preview"
      data-placement={placement}
      onClick={onOpen}
      onMouseEnter={onPointerEnter}
      onMouseLeave={onPointerLeave}
      style={{
        left: `${position.left}px`,
        ...(placement === 'above'
          ? { bottom: `${position.bottom}px` }
          : { top: `${position.top}px` }),
        '--cth-arrow-left': `${arrowLeft}px`,
      }}
    >
      {/* 1 · the rating, and what it costs to get there */}
      <div className="flex items-center" style={{ gap: '8px' }}>
        <span
          data-testid="cth-hover-stars"
          style={{ color: 'var(--color-marginal)', letterSpacing: '2px', fontSize: '14px' }}
        >
          {starGlyphs(rating ?? 0)}
        </span>
        {rating != null && (
          <span className="font-mono text-plex-text-muted" style={{ fontSize: '10.5px' }}>
            {rating.toFixed(1)}/5
          </span>
        )}
        {drive && (
          <span data-testid="cth-hover-drive" className="cth-hover-chip font-mono">
            🚗 {drive}
          </span>
        )}
      </div>

      {/* 2 · one clause of the why, in Claude's voice.
          Serif italic is this app's typographic mark for generated prose — the drill-down gloss,
          the map overlay's summary, the InfoTip card body and the tide run's phrase all use it,
          and this fragment is the same kind of thing: an argued sentence, not a measurement. */}
      {clause && (
        <p
          data-testid="cth-hover-summary"
          className="text-plex-text-secondary"
          style={{
            fontFamily: 'var(--font-serif)',
            fontStyle: 'italic',
            fontSize: '12.5px',
            lineHeight: 1.55,
            margin: '8px 0 0',
          }}
        >
          {clause}
        </p>
      )}

      {/* 3 · where the rest of it lives */}
      <p
        data-testid="cth-hover-prompt"
        style={{
          fontSize: '11.5px',
          fontWeight: 600,
          color: 'var(--color-tide)',
          margin: '8px 0 0',
        }}
      >
        Click for the full read + map →
      </p>
    </div>
  );
}

CardHoverPreview.propTypes = {
  rating: PropTypes.number,
  driveMinutes: PropTypes.number,
  summary: PropTypes.string,
  position: PropTypes.shape({
    left: PropTypes.number.isRequired,
    top: PropTypes.number,
    bottom: PropTypes.number,
  }).isRequired,
  placement: PropTypes.oneOf(['above', 'below']),
  arrowLeft: PropTypes.number,
  onOpen: PropTypes.func.isRequired,
  onPointerEnter: PropTypes.func.isRequired,
  onPointerLeave: PropTypes.func.isRequired,
};
