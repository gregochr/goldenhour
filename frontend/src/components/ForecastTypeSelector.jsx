import React from 'react';
import PropTypes from 'prop-types';

const TYPES = [
  { value: 'SUNRISE', label: '☀️ Sunrise' },
  { value: 'SUNSET',  label: '🌇 Sunset' },
  { value: 'AURORA',  label: '🌌 Aurora' },
];

/**
 * Sunrise / Sunset / Aurora toggle bar shown above the map.
 *
 * Aurora is always rendered for ADMIN/PRO users but is disabled (greyed out)
 * when there are no stored forecast results and no live alert is active.
 * LITE_USER never sees the Aurora button.
 *
 * @param {object}   props
 * @param {string}   props.eventType        - Currently selected type.
 * @param {function} props.onChange         - Called with the new type when a button is clicked.
 * @param {boolean}  props.showAurora       - True for ADMIN/PRO; false for LITE_USER.
 * @param {boolean}  props.auroraAvailable  - True when stored results exist or live alert is active.
 */
export default function ForecastTypeSelector({ eventType, onChange, showAurora, auroraAvailable }) {
  return (
    <div className="flex items-center gap-1" data-testid="forecast-type-selector">
      {TYPES.map(({ value, label }) => {
        if (value === 'AURORA' && !showAurora) return null;
        const isAurora = value === 'AURORA';
        const disabled = isAurora && !auroraAvailable;
        const active = eventType === value;
        return (
          <button
            key={value}
            data-testid={`forecast-type-${value.toLowerCase()}`}
            onClick={() => !disabled && onChange(value)}
            disabled={disabled}
            title={disabled ? 'No aurora forecast results available' : undefined}
            className={`px-4 py-1.5 text-sm font-medium rounded-full border transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
              active
                ? isAurora
                  ? 'bg-indigo-900/40 border-indigo-500/60 text-indigo-200'
                  : 'bg-plex-gold/20 border-plex-gold/50 text-plex-gold'
                : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
            }`}
          >
            {label}
          </button>
        );
      })}
    </div>
  );
}

ForecastTypeSelector.propTypes = {
  eventType: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
  showAurora: PropTypes.bool.isRequired,
  auroraAvailable: PropTypes.bool.isRequired,
};
