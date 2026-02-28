import React from 'react';
import PropTypes from 'prop-types';

const GOLDEN_HOUR_TYPE_META = {
  SUNRISE:    { emoji: '🌅', label: 'Sunrise' },
  SUNSET:     { emoji: '🌇', label: 'Sunset' },
  BOTH_TIMES: { emoji: '🌅🌇', label: 'Sunrise & Sunset' },
  ANYTIME:    { emoji: '☀️', label: 'Anytime' },
};

const LOCATION_TYPE_META = {
  LANDSCAPE: { emoji: '🏔️', label: 'Landscape' },
  WILDLIFE:  { emoji: '🦅', label: 'Wildlife' },
  SEASCAPE:  { emoji: '🌊', label: 'Seascape' },
};

const TIDE_TYPE_META = {
  HIGH_TIDE: { short: 'High tide' },
  LOW_TIDE:  { short: 'Low tide' },
  MID_TIDE:  { short: 'Mid tide' },
  ANY_TIDE:  { short: 'Any tide' },
};

/**
 * Compact inline badges showing a location's golden hour preference, photography type
 * tags, and tide preferences.
 *
 * @param {object}   props
 * @param {string}   props.goldenHourType - GoldenHourType value (SUNRISE, SUNSET, BOTH_TIMES, ANYTIME).
 * @param {string[]} props.locationType   - Array of LocationType values (LANDSCAPE, WILDLIFE, SEASCAPE).
 * @param {string[]} props.tideType       - Array of TideType values (HIGH_TIDE, LOW_TIDE, etc.).
 */
export default function LocationTypeBadges({ goldenHourType, locationType = [], tideType = [] }) {
  const goldenMeta = goldenHourType ? GOLDEN_HOUR_TYPE_META[goldenHourType] : null;
  const coastalTides = tideType.filter((t) => t !== 'NOT_COASTAL');

  if (!goldenMeta && locationType.length === 0 && coastalTides.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-1">
      {goldenMeta && (
        <span
          title={goldenMeta.label}
          className="inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full
            bg-plex-gold/10 text-plex-gold border border-plex-gold/30"
        >
          {goldenMeta.emoji} <span className="font-medium">{goldenMeta.label}</span>
        </span>
      )}
      {locationType.map((type) => {
        const meta = LOCATION_TYPE_META[type];
        if (!meta) return null;
        return (
          <span
            key={type}
            title={meta.label}
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
            title={meta.short}
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
  goldenHourType: PropTypes.string,
  locationType: PropTypes.arrayOf(PropTypes.string),
  tideType: PropTypes.arrayOf(PropTypes.string),
};
