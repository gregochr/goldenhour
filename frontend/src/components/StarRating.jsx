import React from 'react';
import PropTypes from 'prop-types';

const MAX_STARS = 5;

/**
 * Displays a 1-5 star rating with filled and empty stars.
 *
 * @param {object} props
 * @param {number|null} props.rating - Rating value (1–5), or null if unavailable.
 * @param {string} [props.label] - Accessible label (e.g. "Sunrise rating").
 * @param {string} [props.testId] - data-testid attribute value.
 */
export default function StarRating({ rating = null, label = 'Rating', testId = 'star-rating' }) {
  if (rating == null) {
    return (
      <div
        data-testid={testId}
        className="flex items-center gap-0.5 text-gray-600"
        aria-label={`${label}: no rating`}
      >
        {Array.from({ length: MAX_STARS }).map((_, i) => (
          <span key={i} aria-hidden="true">
            ☆
          </span>
        ))}
      </div>
    );
  }

  const clamped = Math.max(1, Math.min(MAX_STARS, Math.round(rating)));

  return (
    <div
      data-testid={testId}
      className="flex items-center gap-0.5"
      aria-label={`${label}: ${clamped} out of ${MAX_STARS} stars`}
      role="img"
    >
      {Array.from({ length: MAX_STARS }).map((_, i) => (
        <span
          key={i}
          className={i < clamped ? 'text-amber-400' : 'text-gray-600'}
          aria-hidden="true"
        >
          {i < clamped ? '★' : '☆'}
        </span>
      ))}
    </div>
  );
}

StarRating.propTypes = {
  rating: PropTypes.number,
  label: PropTypes.string,
  testId: PropTypes.string,
};

