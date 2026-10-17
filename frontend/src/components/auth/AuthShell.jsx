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
export default function AuthShell({ children }) {
  return (
    <div className="auth-shell" data-testid="auth-shell">
      <div className="w-full max-w-sm">
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
};
