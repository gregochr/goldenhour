import React from 'react';
import PropTypes from 'prop-types';

const ALL_OPTIONS = [
  { value: 'plan', label: 'Plan', glyph: '◉', adminOnly: false },
  { value: 'map', label: 'Map', glyph: '◍', adminOnly: false },
  { value: 'manage', label: 'Manage', glyph: '⚙', adminOnly: true },
];

/**
 * Segmented control for switching between view modes.
 *
 * <p>A bordered track with a raised active segment, rather than the underlined text row it
 * replaced — as bare text the tabs read as floating labels with no affordance that they are a
 * switch. The leading glyphs are decorative (`aria-hidden`), so the accessible name of each
 * segment stays the plain word.
 *
 * @param {object} props
 * @param {'plan'|'map'|'manage'} props.value - Currently active mode.
 * @param {function} props.onChange - Called with the new mode string when toggled.
 * @param {boolean} [props.isAdmin=false] - Whether to show the admin-only Manage tab.
 */
export default function ViewToggle({ value, onChange, isAdmin = false }) {
  const options = ALL_OPTIONS.filter((opt) => !opt.adminOnly || isAdmin);

  if (options.length <= 1) return null;

  return (
    <div
      data-testid="view-toggle"
      className="inline-flex bg-plex-surface border border-plex-border"
      style={{ borderRadius: '9px', padding: '3px', gap: '2px' }}
    >
      {options.map((opt) => {
        const active = value === opt.value;
        return (
          <button
            key={opt.value}
            type="button"
            aria-current={active ? 'page' : undefined}
            onClick={() => onChange(opt.value)}
            className={`inline-flex items-center transition-colors ${
              active
                ? 'bg-plex-surface-light text-plex-text'
                : 'text-plex-text-muted hover:text-plex-text-secondary'
            }`}
            style={{
              fontSize: '13px',
              fontWeight: 500,
              padding: '7px 15px',
              borderRadius: '6px',
              gap: '7px',
              boxShadow: active ? 'inset 0 0 0 1px var(--color-plex-border-light)' : undefined,
            }}
          >
            <span aria-hidden="true" style={{ fontSize: '12px', opacity: 0.8 }}>{opt.glyph}</span>
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

ViewToggle.propTypes = {
  value: PropTypes.oneOf(['plan', 'map', 'manage']).isRequired,
  onChange: PropTypes.func.isRequired,
  isAdmin: PropTypes.bool,
};
