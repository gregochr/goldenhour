import React from 'react';
import PropTypes from 'prop-types';

/**
 * Location name for a briefing slot. When an {@link onShowOnMap} handler and a
 * {@code date} are supplied it renders as a button that hands off to the Map tab
 * with the date, event type and location pre-selected; otherwise it falls back
 * to a plain label.
 */
export default function SlotLocationName({
  name,
  typeIcon = null,
  date = null,
  targetType = null,
  onShowOnMap = null,
  className = '',
}) {
  const content = (
    <>
      {/* ⚠️ The space inside the icon span, after the icon, is LOAD-BEARING in a browser: the span
          is plain inline content beside the name, so without it the accessible name reads
          "🏔️Angel of the North" (measured 2026-09-14, confirmed against Chromium's native tree).
          Browsers keep a space inside an element's own content; only jsdom's polyfill trims it.
          Keep `{typeIcon} </span>` on one line — JSX drops whitespace-only text that contains a
          line break, so moving `</span>` onto its own line would delete the space silently. */}
      {typeIcon && <span data-testid="slot-type-icon">{typeIcon} </span>}
      {name}
    </>
  );

  if (onShowOnMap && date) {
    return (
      <button
        type="button"
        data-testid="slot-location-link"
        title="Show on map"
        onClick={(e) => {
          e.stopPropagation();
          onShowOnMap(date, targetType, name);
        }}
        className={`font-medium text-plex-text hover:text-plex-gold hover:underline
          transition-colors text-left ${className}`}
        style={{ fontSize: '13px' }}
      >
        {content}
      </button>
    );
  }

  return (
    <span className={`font-medium text-plex-text ${className}`} style={{ fontSize: '13px' }}>
      {content}
    </span>
  );
}

SlotLocationName.propTypes = {
  name: PropTypes.string.isRequired,
  typeIcon: PropTypes.string,
  date: PropTypes.string,
  targetType: PropTypes.string,
  onShowOnMap: PropTypes.func,
  className: PropTypes.string,
};
