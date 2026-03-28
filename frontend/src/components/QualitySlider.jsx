import PropTypes from 'prop-types';
import { TIER_LABELS } from '../utils/tierUtils.js';

/**
 * Quality threshold slider for the Plan tab heatmap.
 *
 * Controlled component — all state lives in the parent.
 *
 * @param {number}   value     Current slider position (0–5)
 * @param {function} onChange  Called with new integer position on change
 * @param {number}   showing   Count of currently-visible cells
 * @param {number}   total     Total cell count (all tiers)
 */
export default function QualitySlider({ value, onChange, showing, total }) {
  return (
    <div className="mb-3" data-testid="quality-slider">
      <div className="flex items-center gap-2">
        <span
          className="shrink-0 font-medium text-green-400"
          style={{ fontSize: '11px', minWidth: '28px' }}
          aria-hidden="true"
        >
          Best
        </span>

        <input
          type="range"
          min={0}
          max={5}
          step={1}
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          className="quality-slider flex-1"
          aria-label="Quality threshold"
          aria-valuetext={TIER_LABELS[value]}
        />

        <span
          className="shrink-0 font-medium text-red-400/70"
          style={{ fontSize: '11px', minWidth: '18px', textAlign: 'right' }}
          aria-hidden="true"
        >
          All
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
