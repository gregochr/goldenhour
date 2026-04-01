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
 * @param {number}   value     Current internal tier (0 = best, 5 = worst)
 * @param {function} onChange  Called with new internal tier on change
 * @param {number}   showing   Count of currently-visible cells
 * @param {number}   total     Total cell count (all tiers)
 */
export default function QualitySlider({ value, onChange, showing, total }) {
  const visualValue = MAX_TIER - value;

  function handleChange(e) {
    onChange(MAX_TIER - Number(e.target.value));
  }

  return (
    <div className="mb-3" data-testid="quality-slider">
      <div className="flex items-center gap-2">
        <span
          className="shrink-0 font-medium text-red-400/70"
          style={{ fontSize: '11px', minWidth: '28px' }}
          aria-hidden="true"
        >
          Worst
        </span>

        <input
          type="range"
          min={0}
          max={MAX_TIER}
          step={1}
          value={visualValue}
          onChange={handleChange}
          className="quality-slider flex-1"
          aria-label="Quality threshold"
          aria-valuetext={TIER_LABELS[value]}
        />

        <span
          className="shrink-0 font-medium text-green-400"
          style={{ fontSize: '11px', minWidth: '28px', textAlign: 'right' }}
          aria-hidden="true"
        >
          Best
        </span>
      </div>

      <div className="flex items-center gap-2 mt-0.5" style={{ paddingLeft: '36px' }}>
        <span className="text-plex-text-muted" style={{ fontSize: '11px' }}>
          Showing {showing} of {total} cells
        </span>
        <span className="text-plex-text-secondary" style={{ fontSize: '11px' }}>
          · {TIER_LABELS[value]}
        </span>
      </div>
    </div>
  );
}

QualitySlider.propTypes = {
  value: PropTypes.number.isRequired,
  onChange: PropTypes.func.isRequired,
  showing: PropTypes.number.isRequired,
  total: PropTypes.number.isRequired,
};
