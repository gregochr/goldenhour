import React from 'react';
import PropTypes from 'prop-types';
import BrandLockup from '../shared/BrandLockup.jsx';

/**
 * The wrapper every full-page auth screen sits in: the safe-area-aware page (`.auth-shell` in
 * `index.css` — dynamic viewport height, top-aligned with room for the keyboard on a phone,
 * centred from 640px), the `auth` brand lockup, and the 384px column the cards live in.
 *
 * <p>The lockup carries the page's one {@code <h1>}; the card headings under it are
 * {@link AuthHeading} {@code <h2>}s.
 */
export default function AuthShell({ children, wide = false }) {
  return (
    <div className="auth-shell" data-wide={wide ? '' : undefined} data-testid="auth-shell">
      <div className={wide ? 'w-full max-w-[520px]' : 'w-full max-w-sm'}>
        <div className="mb-8">
          <BrandLockup variant="auth" />
        </div>
        {children}
      </div>
    </div>
  );
}

AuthShell.propTypes = {
  children: PropTypes.node.isRequired,
  /** The sub-region step only: a 520px column so example places fit on one line. Every other screen is 384. */
  wide: PropTypes.bool,
};
