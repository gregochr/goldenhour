import React from 'react';
import PropTypes from 'prop-types';
import ProPill from './shared/ProPill.jsx';

const TYPES = [
  { value: 'SUNRISE', label: '☀️ Sunrise' },
  { value: 'SUNSET',  label: '🌇 Sunset' },
  { value: 'ASTRO',   label: '🌙 Astro' },
  { value: 'AURORA',  label: '🌌 Aurora' },
];

/**
 * Sunrise / Sunset / Astro / Aurora toggle bar shown above the map.
 *
 * Astro is shown to everyone; disabled when no available dates.
 * Aurora is always rendered — disabled (greyed out) when there are no stored
 * forecast results and no live alert is active. For LITE users the button is
 * locked with a Pro pill badge.
 *
 * @param {object}   props
 * @param {string}   props.eventType        - Currently selected type.
 * @param {function} props.onChange         - Called with the new type when a button is clicked.
 * @param {boolean}  props.showAurora       - True for ADMIN/PRO; false for LITE_USER.
 * @param {boolean}  props.auroraAvailable  - True when stored results exist or live alert is active.
 * @param {boolean}  props.astroAvailable   - True when astro condition dates exist.
 * @param {boolean}  props.sunriseAvailable - True when at least one location has a sunrise forecast for the selected day.
 * @param {boolean}  props.sunsetAvailable  - True when at least one location has a sunset forecast for the selected day.
 */
export default function ForecastTypeSelector({ eventType, onChange, showAurora, auroraAvailable, astroAvailable = false, sunriseAvailable = true, sunsetAvailable = true }) {
  return (
    <div className="flex items-center gap-1" data-testid="forecast-type-selector">
      {TYPES.map(({ value, label }) => {
        const isAurora = value === 'AURORA';
        const isAstro = value === 'ASTRO';
        const isSunrise = value === 'SUNRISE';
        const isSunset = value === 'SUNSET';
        const isLocked = isAurora && !showAurora;
        const disabled = isLocked
          || (isAurora && !auroraAvailable)
          || (isAstro && !astroAvailable)
          || (isSunrise && !sunriseAvailable)
          || (isSunset && !sunsetAvailable);
        const active = eventType === value;
        return (
          <button
            key={value}
            data-testid={`forecast-type-${value.toLowerCase()}`}
            onClick={() => !disabled && onChange(value)}
            disabled={disabled}
            title={disabled
              ? isLocked
                ? 'Upgrade to Pro for aurora forecasts'
                : isAstro ? 'No astro condition results available'
                : isAurora ? 'No aurora forecast results available'
                : 'No forecast available for this day'
              : undefined}
            className={`px-4 py-1.5 text-sm font-medium rounded-full border transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
              active
                ? isAurora
                  ? 'bg-indigo-900/40 border-indigo-500/60 text-indigo-200'
                  : isAstro
                    ? 'bg-blue-900/40 border-blue-500/60 text-blue-200'
                    : 'bg-plex-gold/20 border-plex-gold/50 text-plex-gold'
                : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
            }`}
          >
            {label}
            {isLocked && <ProPill className="ml-1.5" />}
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
  astroAvailable: PropTypes.bool,
  sunriseAvailable: PropTypes.bool,
  sunsetAvailable: PropTypes.bool,
};
