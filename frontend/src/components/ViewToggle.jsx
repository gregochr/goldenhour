import React from 'react';
import PropTypes from 'prop-types';

const ALL_OPTIONS = [
  { value: 'map', label: 'Map', adminOnly: false },
  { value: 'manage', label: 'Manage', adminOnly: true },
];

/**
 * Underline tab bar for switching between view modes.
 *
 * @param {object} props
 * @param {'map'|'manage'} props.value - Currently active mode.
 * @param {function} props.onChange - Called with the new mode string when toggled.
 * @param {boolean} [props.isAdmin=false] - Whether to show the admin-only Manage tab.
 */
export default function ViewToggle({ value, onChange, isAdmin = false }) {
  const options = ALL_OPTIONS.filter((opt) => !opt.adminOnly || isAdmin);

  if (options.length <= 1) return null;

  return (
    <div className="flex gap-6 border-b border-plex-border" data-testid="view-toggle">
      {options.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onChange(opt.value)}
          className={`pb-2 text-sm font-medium transition-colors border-b-2 ${
            value === opt.value
              ? 'text-plex-gold border-plex-gold'
              : 'text-plex-text-secondary hover:text-plex-text border-transparent'
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}

ViewToggle.propTypes = {
  value: PropTypes.oneOf(['map', 'manage']).isRequired,
  onChange: PropTypes.func.isRequired,
  isAdmin: PropTypes.bool,
};
