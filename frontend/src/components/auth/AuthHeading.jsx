import React from 'react';
import PropTypes from 'prop-types';

/**
 * A card heading on an auth screen: Newsreader, 22px, bone ink. An {@code <h2>} because
 * {@code BrandLockup} already renders the page's {@code <h1>}. There is deliberately no kicker
 * above it on any auth screen — the spec makes one optional, and one answer for every screen beats
 * five — and no colour prop: coral is confined to the logo lockup and a verdict colour would claim
 * a forecast meaning, so the heading is always `text-plex-text`.
 */
export default function AuthHeading({ children, testId = undefined, id = undefined }) {
  return (
    <h2
      id={id}
      className="font-serif font-semibold text-[22px] leading-tight tracking-[-0.015em] text-plex-text"
      data-testid={testId}
    >
      {children}
    </h2>
  );
}

AuthHeading.propTypes = {
  children: PropTypes.node.isRequired,
  testId: PropTypes.string,
  id: PropTypes.string,
};
