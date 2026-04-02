import React from 'react';
import PropTypes from 'prop-types';

/**
 * Small "Pro" pill badge used to indicate features requiring a PRO subscription.
 *
 * @param {object} props
 * @param {string} [props.className] - Additional CSS classes (e.g. margin adjustments).
 */
export default function ProPill({ className = '' }) {
  return (
    <span
      className={`bg-plex-surface-light text-plex-text-secondary px-2 py-0.5 rounded-md ${className}`.trim()}
      style={{ fontSize: '11px' }}
      data-testid="pro-pill"
    >
      Pro
    </span>
  );
}

ProPill.propTypes = {
  className: PropTypes.string,
};
