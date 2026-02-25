import React from 'react';
import PropTypes from 'prop-types';

const ALL_OPTIONS = [
  { value: 'map', label: 'Map', adminOnly: false },
  { value: 'manage', label: 'Manage', adminOnly: true },
];

/**
 * Segmented control for switching between view modes.
 *
 * @param {object} props
 * @param {'map'|'manage'} props.value - Currently active mode.
 * @param {function} props.onChange - Called with the new mode string when toggled.
 * @param {boolean} [props.isAdmin=false] - Whether to show the admin-only Manage tab.
 */
export default function ViewToggle({ value, onChange, isAdmin = false }) {
  const options = ALL_OPTIONS.filter((opt) => !opt.adminOnly || isAdmin);

  return (
    <div className="inline-flex rounded-lg border border-gray-700 bg-gray-900 p-0.5 gap-0.5">
      {options.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onChange(opt.value)}
          className={`px-4 py-1.5 text-sm font-medium rounded-md transition-colors ${
            value === opt.value
              ? 'bg-gray-700 text-gray-100'
              : 'text-gray-500 hover:text-gray-300'
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
