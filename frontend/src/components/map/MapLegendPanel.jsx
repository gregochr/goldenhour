import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { rampGradientCss } from '../../utils/scoreRamp.js';
import { RING_TIERS } from '../../utils/reachRings.js';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';

/** Below this handover fraction the field is still whole — README §8: "Field" / "the regional glance". */
const HANDOVER_FIELD_MAX = 0.05;
/** Above this fraction the field has settled to its floor wash — README §8: "Locations". */
const HANDOVER_LOCATIONS_MIN = 0.92;

/**
 * The Legend panel's three-state handover copy, from the same `t` `MapHeatLayer.fadeAt(zoom).markers`
 * computes — one number, one place it is turned into words.
 *
 * <p>⚠️ It used to add "so the indicator can never disagree with what is actually painting", and
 * that safety property is gone: `markers` was the medallion opacity, and nothing paints at it now
 * (`MapHeatLayer` hides those panes unconditionally). The indicator is still HONEST — `fadeAt`'s
 * other half, `heat`, ramps the field 1 → 0.12 across the identical band, so `Field` / `Handing
 * over` / `Locations` does track a real, visible change, and what it hands over TO is `MapLabels`'
 * chips, exactly as the bundle intends. But the two halves are now separate numbers from one
 * function rather than one number with two consumers: a future change to the field's own ramp can
 * move the picture without moving this text.
 *
 * <p>Exported (pure, no DOM) so the three bands can be pinned directly on the number rather than by
 * reverse-engineering a zoom that happens to land in each one.
 *
 * @param {number} t 0–1, `fadeAt(zoom).markers`
 * @returns {{label: string, detail: string}}
 */
export function handoverPhase(t) {
  if (t < HANDOVER_FIELD_MAX) return { label: 'Field', detail: 'the regional glance' };
  if (t > HANDOVER_LOCATIONS_MIN) return { label: 'Locations', detail: 'field kept as a faint wash' };
  return { label: 'Handing over', detail: 'field → locations' };
}

/**
 * The Map tab's Legend panel (map-tab-v2-plan.md §3 P10, `docs/design/map-tab-v2/README.md` §8) —
 * a `▤ Legend ▾` chip (desktop, bottom-left) opening a 262px panel: the ramp bar, whole-star labels,
 * the Field → Handing over → Locations indicator, the reach-rings toggle, and the confidence note.
 *
 * <p>⚠️ <b>The ramp bar paints from {@link rampGradientCss}, never the design bundle's own HTML.</b>
 * The bundle's `.legbar` gradient is the STALE red-amber-green ramp from before the temperature
 * scale shipped — copying it would invert what the field's own colours mean, exactly the trap
 * map-tab-v2-plan.md §4.5 records: "the legend paints from `rampGradientCss()`", not a second,
 * hand-authored gradient that can drift from the kernel's own stops.
 *
 * <p>Hidden entirely in Pins mode (README §3: "In Pins mode the Legend chip hides") — this
 * component does not know about that itself; `MapView` simply does not mount it there, the same
 * way the ramp key beside the Heat/Pins segment is withheld.
 *
 * <p>Purely presentational and fully controlled, like `FiltersPopover`/`WindowControl`: every value
 * and setter is a prop, and this component owns only its own open/close plumbing (click-outside,
 * `Escape`) plus the row markup.
 *
 * @param {object} props
 * @param {boolean} props.open
 * @param {Function} props.onOpenChange
 * @param {number} props.handoverFraction `MapHeatLayer.fadeAt(zoom).markers` — 0 when the field is
 *   not offered at all (the panel is not mounted in that case, but the prop stays required so a
 *   caller cannot forget to thread the live zoom through)
 * @param {boolean} props.ringsEnabled the shared `MapView` state `MapHeatLayer`'s canvas rings and
 *   `MapLabels`' ring labels already read — this toggle is P10's first WRITER of it
 * @param {Function} props.onToggleRings
 * @param {boolean} [props.hasHome] whether a real home COORDINATE exists — absent, the toggle is
 *   withheld entirely (the app's own "a control whose every press does nothing is banned outright"
 *   rule, `FiltersPopover`'s identical scope-row gate): with no home there is nothing for a ring to
 *   be drawn around, on any surface. ⚠️ The caller MUST derive this the same way `MapHeatLayer`'s
 *   ring paint and `MapLabels`' ring-label candidates do — `homeCoords?.lat != null &&
 *   homeCoords?.lon != null` — never from `heat.hasHome`, a different roster-level signal that can
 *   be true while `homeCoords` itself has not resolved (adversarial review C4: MapView's first cut
 *   passed `heat.hasHome` here and could show a toggle with nothing for either sibling to draw).
 * @param {boolean} [props.reachMeasured] whether a real drive time gates this reader's reach lens
 *   (§5.2's honesty rule) — the toggle states the ring tiers as a DISTANCE by default and only
 *   upgrades to durations once a measured drive exists, the same rule `MapLabels`' own ring labels
 *   and `WindowRowFieldMap`'s ring labels already apply.
 */
export default function MapLegendPanel({
  open, onOpenChange, handoverFraction, ringsEnabled, onToggleRings,
  hasHome = false, reachMeasured = false,
}) {
  const rootRef = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    function onDocMouseDown(e) {
      if (rootRef.current && !rootRef.current.contains(e.target)) onOpenChange(false);
    }
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function onKeyDown(e) {
    if (e.key === 'Escape' && open) {
      e.preventDefault();
      onOpenChange(false);
    }
  }

  const phase = handoverPhase(handoverFraction);
  const barWidthPct = Math.round(100 - handoverFraction * 80);
  const ringTierText = reachMeasured
    ? RING_TIERS.map((tier) => formatDriveDuration(tier.minutes)).join(', ')
    : RING_TIERS.map((tier) => `${tier.mi} mi`).join(', ');

  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div ref={rootRef} data-testid="wf-legend" className="wf-legend" onKeyDown={onKeyDown}>
      <button
        type="button"
        data-testid="wf-legend-chip"
        className="wf-legend-chip"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="wf-legend-panel"
        onClick={() => onOpenChange(!open)}
      >
        <span aria-hidden="true">&#9636; </span>
        Legend
        <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
      </button>

      {open && (
        <div id="wf-legend-panel" data-testid="wf-legend-panel" className="wf-legend-panel" role="dialog" aria-label="Map legend">
          <div
            data-testid="wf-legend-ramp"
            className="wf-legend-ramp"
            style={{ background: rampGradientCss() }}
          />
          <div className="wf-legend-ramp-row">
            <span>1&#9733; poor</span>
            <span>3&#9733;</span>
            <span>5&#9733; go</span>
          </div>

          <div data-testid="wf-legend-hand" className="wf-legend-hand">
            <span className="wf-legend-hand-bar">
              <i style={{ width: `${barWidthPct}%` }} />
            </span>
            <span className="wf-legend-hand-txt">
              <b>{phase.label}</b>
              {phase.detail}
            </span>
          </div>

          {hasHome && (
            <button
              type="button"
              data-testid="wf-legend-rings-toggle"
              aria-pressed={ringsEnabled}
              className={`wf-legend-ring-toggle${ringsEnabled ? ' on' : ''}`}
              onClick={onToggleRings}
            >
              <span aria-hidden="true" className="wf-legend-ring-box" />
              Reach rings &middot; {ringTierText}
            </button>
          )}

          <div data-testid="wf-legend-note" className="wf-legend-note">
            Warmth only where rated locations are, clipped to land. Later windows render hazier
            &mdash; lower confidence.
          </div>
        </div>
      )}
    </div>
  );
}

MapLegendPanel.propTypes = {
  open: PropTypes.bool.isRequired,
  onOpenChange: PropTypes.func.isRequired,
  handoverFraction: PropTypes.number.isRequired,
  ringsEnabled: PropTypes.bool.isRequired,
  onToggleRings: PropTypes.func.isRequired,
  hasHome: PropTypes.bool,
  reachMeasured: PropTypes.bool,
};
