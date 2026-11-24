import React from 'react';
import PropTypes from 'prop-types';

/**
 * A served star rating as the screen prints it: {@code 4★} for the eye, "4 stars" for a screen reader (the glyph is
 * {@code aria-hidden}, because NVDA does not read U+2605 and would announce a bare "4"). Used where a rating sits
 * inside a line of other words (a Your places chip, a watched lane's plan), so the visible text stays a substring of
 * the accessible name.
 *
 * @param {object} props
 * @param {number} props.n the rating
 */
export default function StarFigure({ n }) {
  return (
    <>
      {n}
      <span aria-hidden="true">★</span>
      {' '}
      <span className="sr-only">{n === 1 ? 'star' : 'stars'}</span>
    </>
  );
}

StarFigure.propTypes = {
  n: PropTypes.number.isRequired,
};
