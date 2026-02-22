import React from 'react';
import PropTypes from 'prop-types';

const OPTIONS = [
  { value: 'location', label: 'By Location' },
  { value: 'date', label: 'By Date' },
  { value: 'map', label: 'Map' },
  { value: 'manage', label: 'Manage' },
];

/**
 * Segmented control for switching between view modes.
 *
 * @param {object} props
 * @param {'location'|'date'|'map'|'manage'} props.value - Currently active mode.
 * @param {function} props.onChange - Called with the new mode string when toggled.
 */
export default function ViewToggle({ value, onChange }) {
  return (
    <div className="inline-flex rounded-lg border border-gray-700 bg-gray-900 p-0.5 gap-0.5">
      {OPTIONS.map((opt) => (
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
  value: PropTypes.oneOf(['location', 'date', 'map', 'manage']).isRequired,
  onChange: PropTypes.func.isRequired,
};
