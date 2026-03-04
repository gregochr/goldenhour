import React from 'react';
import PropTypes from 'prop-types';

const SOLAR_EVENT_TYPE_META = {
  SUNRISE: { emoji: '🌅', label: 'Sunrise' },
  SUNSET:  { emoji: '🌇', label: 'Sunset' },
  ALLDAY:  { emoji: '☀️', label: 'All Day' },
};

const LOCATION_TYPE_META = {
  LANDSCAPE: { emoji: '🏔️', label: 'Landscape' },
  WILDLIFE:  { emoji: '🐾', label: 'Wildlife' },
  SEASCAPE:  { emoji: '🌊', label: 'Seascape' },
};

const TIDE_TYPE_META = {
  HIGH: { short: 'High tide' },
  MID:  { short: 'Mid tide' },
  LOW:  { short: 'Low tide' },
};

/**
 * Compact inline badges showing a location's solar event preference, photography type
 * tags, and tide preferences.
 *
 * @param {object}   props
 * @param {string[]} props.solarEventType - Array of SolarEventType values (SUNRISE, SUNSET, ALLDAY).
 * @param {string[]} props.locationType   - Array of LocationType values (LANDSCAPE, WILDLIFE, SEASCAPE).
 * @param {string[]} props.tideType       - Array of TideType values (HIGH, MID, LOW).
 */
export default function LocationTypeBadges({ solarEventType = [], locationType = [], tideType = [] }) {
  const solarTypes = solarEventType.filter((t) => SOLAR_EVENT_TYPE_META[t]);
  const coastalTides = tideType.filter((t) => TIDE_TYPE_META[t]);

  if (solarTypes.length === 0 && locationType.length === 0 && coastalTides.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-1">
      {solarTypes.map((type) => {
        const meta = SOLAR_EVENT_TYPE_META[type];
        return (
          <span
            key={type}
            className="inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full
              bg-plex-gold/10 text-plex-gold border border-plex-gold/30"
          >
            {meta.emoji} <span className="font-medium">{meta.label}</span>
          </span>
        );
      })}
      {locationType.map((type) => {
        const meta = LOCATION_TYPE_META[type];
        if (!meta) return null;
        return (
          <span
            key={type}
            className="inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full
              bg-plex-surface-light text-plex-text-secondary border border-plex-border"
          >
            {meta.emoji} <span className="font-medium">{meta.label}</span>
          </span>
        );
      })}
      {coastalTides.map((type) => {
        const meta = TIDE_TYPE_META[type];
        if (!meta) return null;
        return (
          <span
            key={type}
            className="inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full
              bg-cyan-950 text-cyan-300 border border-cyan-800/60"
          >
            🌊 <span className="font-medium">{meta.short}</span>
          </span>
        );
      })}
    </div>
  );
}

LocationTypeBadges.propTypes = {
  solarEventType: PropTypes.arrayOf(PropTypes.string),
  locationType: PropTypes.arrayOf(PropTypes.string),
  tideType: PropTypes.arrayOf(PropTypes.string),
};
