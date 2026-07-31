import React from 'react';
import PropTypes from 'prop-types';
import StarRating from './StarRating.jsx';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { formatEventTimeUk } from '../utils/conversions.js';

/**
 * The two score bars, drawn the way the map popup draws them so a 68 reads identically in both
 * places. The fill is a static gradient with a grey mask sliding in from the right — the same
 * trick the popup uses, so a low score dims rather than changing hue.
 */
const FIERY_FILL = 'linear-gradient(90deg, #C8452F, #E0A542)';
const GOLDEN_FILL = 'linear-gradient(90deg, #8A6A2F, #E4C878)';

/* eslint-disable react/prop-types */

function ScoreRow({ label, score, fill }) {
  const pct = score != null ? Math.min(100, Math.max(0, score)) : null;
  return (
    <div style={{ marginBottom: '5px' }}>
      <div
        className="flex justify-between text-plex-text-secondary"
        style={{ fontSize: '10.5px', marginBottom: '3px' }}
      >
        <span>{label}</span>
        <span className="font-mono" style={{ fontWeight: 600 }}>{pct != null ? pct : '—'}</span>
      </div>
      <div
        style={{
          position: 'relative', height: '5px', background: fill,
          borderRadius: '999px', overflow: 'hidden',
        }}
      >
        <div
          style={{
            position: 'absolute', top: 0, right: 0, height: '100%',
            width: pct != null ? `${100 - pct}%` : '100%',
            background: 'var(--color-plex-surface-light)',
          }}
        />
      </div>
    </div>
  );
}

/* eslint-enable react/prop-types */

/**
 * A compact, read-only preview of a location's forecast, shown while the pointer rests on a card.
 *
 * <p>It is the map popup's headline reduced to what answers "should I look closer?" — the rating,
 * the trip, Claude's one sentence, and the two scores. Deliberately <b>not</b> the popup itself:
 * everything here comes from data the Plan tab already holds (the briefing evaluation scores, and
 * the forecast rows behind the map), so sweeping a pointer across a grid of cards costs nothing.
 * The popup's remaining sections need a per-location detail fetch, and that is what opening the
 * map is for.
 *
 * <p><b>Pointer-only.</b> Hover has no touch equivalent, so the caller gates this on viewport and
 * the card stays a button — the preview is a shortcut, never the only route to the information.
 * It is {@code aria-hidden} for the same reason: a screen reader gets the card's own text and the
 * map, not a floating duplicate it cannot dismiss.
 *
 * @param {Object}  props
 * @param {string}  props.locationName the location the preview is for
 * @param {?number} props.rating       1–5 stars, or null when unrated
 * @param {?number} props.driveMinutes the caller's drive time, or null
 * @param {?string} props.eventTime    UTC ISO time of the solar event, or null
 * @param {string}  props.eventLabel   `Today sunset` / `Tomorrow sunrise` — the window's wording
 * @param {?string} props.summary      Claude's sentence for this slot, or null
 * @param {?number} props.fierySky     Fiery Sky score 0–100, or null
 * @param {?number} props.goldenHour   Golden Hour score 0–100, or null
 * @param {boolean} props.isPro        PRO/ADMIN — gates the two scores, exactly as the map popup does
 * @param {Object}  props.position     fixed-position coordinates, `{ left, top }` in px
 */
export default function CardHoverPreview({
  locationName,
  rating = null,
  driveMinutes = null,
  eventTime = null,
  eventLabel,
  summary = null,
  fierySky = null,
  goldenHour = null,
  isPro = false,
  position,
}) {
  const drive = formatDriveDuration(driveMinutes);
  const time = formatEventTimeUk(eventTime);

  return (
    <div
      data-testid="cth-hover-preview"
      aria-hidden="true"
      className="cth-hover-preview"
      style={{ left: `${position.left}px`, top: `${position.top}px` }}
    >
      <div className="flex items-start justify-between" style={{ gap: '10px' }}>
        <span
          className="text-plex-text"
          style={{ fontSize: '14px', fontWeight: 700, letterSpacing: '-0.01em' }}
        >
          {locationName}
        </span>
        <StarRating rating={rating} testId="cth-hover-stars" />
      </div>

      <div
        className="font-mono text-plex-text-secondary"
        style={{ fontSize: '10.5px', marginTop: '4px' }}
      >
        {drive ? `🚗 ${drive} · ` : ''}
        {eventLabel}
        {time ? ` ${time}` : ''}
      </div>

      {summary && (
        <p
          data-testid="cth-hover-summary"
          className="text-plex-text-secondary"
          style={{ fontSize: '12px', lineHeight: 1.5, margin: '9px 0 10px' }}
        >
          {summary}
        </p>
      )}

      {/* Fiery Sky / Golden Hour are Pro content. The map popup gates both blocks on
          `role !== 'LITE_USER'` and offers "Upgrade to Pro for Fiery Sky & Golden Hour scores" in
          their place — the values reach a Lite client anyway (the evaluation-scores endpoint is
          Bearer-only), so the gate is the UX layer and has to be applied at every surface that
          renders them, or the preview quietly hands over what the popup withholds. */}
      {isPro && (fierySky != null || goldenHour != null) && (
        <div data-testid="cth-hover-scores" style={{ marginTop: summary ? 0 : '10px' }}>
          <ScoreRow label="Fiery Sky" score={fierySky} fill={FIERY_FILL} />
          <ScoreRow label="Golden Hour" score={goldenHour} fill={GOLDEN_FILL} />
        </div>
      )}
      {!isPro && (fierySky != null || goldenHour != null) && (
        <p
          data-testid="cth-hover-upsell"
          className="text-plex-text-muted"
          style={{ fontSize: '11px', margin: summary ? 0 : '10px 0 0' }}
        >
          Upgrade to Pro for Fiery Sky &amp; Golden Hour scores
        </p>
      )}
    </div>
  );
}

CardHoverPreview.propTypes = {
  locationName: PropTypes.string.isRequired,
  rating: PropTypes.number,
  driveMinutes: PropTypes.number,
  eventTime: PropTypes.string,
  eventLabel: PropTypes.string.isRequired,
  summary: PropTypes.string,
  fierySky: PropTypes.number,
  goldenHour: PropTypes.number,
  isPro: PropTypes.bool,
  position: PropTypes.shape({
    left: PropTypes.number.isRequired,
    top: PropTypes.number.isRequired,
  }).isRequired,
};
