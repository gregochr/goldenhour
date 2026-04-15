import PropTypes from 'prop-types';
import { TIER_LABELS } from '../utils/tierUtils.js';

const MAX_TIER = 5;

/**
 * Quality threshold slider for the Plan tab heatmap.
 *
 * The slider runs left-to-right from lowest quality (everything visible)
 * to highest quality (only the best cells). Internally the tier system
 * uses 0 = best and 5 = worst, so the visual position is inverted:
 * {@code internalValue = MAX_TIER - visualPosition}.
 *
 * Controlled component — all state lives in the parent.
 *
 * @param {number}   value                      Current internal tier (0 = best, 5 = worst)
 * @param {function} onChange                    Called with new internal tier on change
 * @param {number}   showing                     Count of currently-visible cells
 * @param {number}   total                       Total cell count (all tiers)
 * @param {boolean}  [showAllLocations]          Whether the show-all-locations toggle is on
 * @param {function} [onShowAllLocationsChange]  Called with new boolean on toggle
 */
export default function QualitySlider({
  value,
  onChange,
  showing,
  total,
  showAllLocations,
  onShowAllLocationsChange,
}) {
  const visualValue = MAX_TIER - value;

  function handleChange(e) {
    onChange(MAX_TIER - Number(e.target.value));
  }

  return (
    <div className="mb-3" data-testid="quality-slider">
      {/* Row 1 — full-width slider track */}
      <input
        type="range"
        min={0}
        max={MAX_TIER}
        step={1}
        value={visualValue}
        onChange={handleChange}
        className="quality-slider w-full"
        aria-label="Quality threshold"
        aria-valuetext={TIER_LABELS[value]}
      />

      {/* Row 2 — metadata left, toggle right */}
      <div className="flex items-center justify-between mt-1.5">
        <div className="flex items-center gap-0">
          <span className="text-plex-text-muted" style={{ fontSize: '13px' }}>
            Showing {showing} of {total} cells
          </span>
          <span className="text-plex-text-muted" style={{ fontSize: '13px', opacity: 0.6 }}>
            {' '}&middot;{' '}
          </span>
          <span className="text-plex-text-muted" style={{ fontSize: '13px' }}>
            {TIER_LABELS[value]}
          </span>
        </div>

        {onShowAllLocationsChange && (
          <button
            type="button"
            role="switch"
            aria-checked={showAllLocations}
            className="hidden sm:flex items-center gap-2 shrink-0 cursor-pointer"
            style={{ background: 'none', border: 'none', padding: 0 }}
            onClick={() => onShowAllLocationsChange(!showAllLocations)}
            data-testid="show-all-locations-toggle"
          >
            <span
              className="quality-toggle-track"
              data-checked={showAllLocations ? 'true' : 'false'}
            >
              <span className="quality-toggle-thumb" />
            </span>
            <span className="text-plex-text-muted" style={{ fontSize: '13px' }}>
              Show all locations
            </span>
          </button>
        )}
      </div>
    </div>
  );
}

QualitySlider.propTypes = {
  value: PropTypes.number.isRequired,
  onChange: PropTypes.func.isRequired,
  showing: PropTypes.number.isRequired,
  total: PropTypes.number.isRequired,
  showAllLocations: PropTypes.bool,
  onShowAllLocationsChange: PropTypes.func,
};
