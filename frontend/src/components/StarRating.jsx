import React from 'react';
import PropTypes from 'prop-types';

/**
 * Displays a 1–5 star rating with filled and empty stars.
 *
 * Null rating renders 0 filled stars. Values outside 1–5 are clamped.
 *
 * @param {object} props
 * @param {number|null} props.rating - Integer 1–5, or null for no rating.
 * @param {string} [props.label] - Accessible label used in aria-label.
 * @param {string} [props.testId] - Optional data-testid attribute.
 */
export default function StarRating({ rating, label, testId }) {
  const clamped = rating == null ? 0 : Math.max(1, Math.min(5, rating));
  const ariaLabel = label != null ? `${label}: ${clamped} out of 5` : `${clamped} out of 5`;

  return (
    <div
      className="flex items-center gap-1.5"
      data-testid={testId}
      role="img"
      aria-label={ariaLabel}
    >
      <div className="flex gap-0.5">
        {[1, 2, 3, 4, 5].map((star) => (
          <span
            key={star}
            className={star <= clamped ? 'text-plex-gold' : 'text-plex-border'}
            style={{ fontSize: '18px', lineHeight: 1 }}
            aria-hidden="true"
          >
            ★
          </span>
        ))}
      </div>
      {rating != null && (
        <span className="text-sm text-plex-text-secondary">{clamped}/5</span>
      )}
    </div>
  );
}

StarRating.propTypes = {
  rating: PropTypes.number,
  label: PropTypes.string,
  testId: PropTypes.string,
};
