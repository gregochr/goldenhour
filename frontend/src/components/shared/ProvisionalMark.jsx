import React from 'react';
import PropTypes from 'prop-types';

/**
 * The quiet low-confidence marker in the confidence channel — a small, muted dot shown next
 * to a rated element (grid cell, pill) whose forecast is provisional (far horizon or wide
 * model spread). It reads as "don't over-trust this yet" without competing with the verdict
 * or the star for attention.
 *
 * Kept deliberately minimal: a hollow dot + an accessible label. It never encodes information
 * by colour alone (the label carries it for screen readers).
 *
 * @param {object} props
 * @param {string} [props.title] accessible/hover text; defaults to a plain-English gloss.
 * @param {string} [props.className] optional extra classes on the wrapper.
 */
export default function ProvisionalMark({ title = 'Provisional — lower-confidence forecast', className = '' }) {
  return (
    <span
      data-testid="provisional-mark"
      title={title}
      aria-label={title}
      role="img"
      className={`inline-flex items-center ${className}`}
      style={{ lineHeight: 1 }}
    >
      <span
        aria-hidden="true"
        style={{
          width: '5px',
          height: '5px',
          borderRadius: '50%',
          border: '1px solid var(--color-plex-text-muted)',
          background: 'transparent',
          display: 'inline-block',
        }}
      />
    </span>
  );
}

ProvisionalMark.propTypes = {
  title: PropTypes.string,
  className: PropTypes.string,
};
