import { useRef } from 'react';
import PropTypes from 'prop-types';
import { foreignModalOverPaneOf } from '../../utils/mapForeignModal.js';
import { useOutsideDismiss } from '../../hooks/useOutsideDismiss.js';
import { useFitToFrame } from '../../hooks/useFitToFrame.js';
import { rampGradientCss } from '../../utils/scoreRamp.js';
import { AURORA_RAMP } from '../../utils/auroraRamp.js';
import { RING_TIERS } from '../../utils/reachRings.js';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';
import BoltIcon from './BoltIcon.jsx';

/** Below this handover fraction the field is still whole — README §8's first zoom state. */
const HANDOVER_FIELD_MAX = 0.05;
/** Above this fraction the field has settled to its floor wash — README §8: "Locations". */
const HANDOVER_LOCATIONS_MIN = 0.92;

/**
 * The Legend panel's three-state handover copy, from the same `t` `MapHeatLayer.fadeAt(zoom).markers`
 * computes — one number, one place it is turned into words.
 *
 * <p>⚠️ It used to add "so the indicator can never disagree with what is actually painting", and
 * that safety property is gone: `markers` was the old location-marker opacity, and nothing paints at it now
 * (the markers are gone). The indicator is still HONEST — `fadeAt`'s
 * other half, `heat`, ramps the field 1 → 0.35 across the identical band, so `Field` / `Handing
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
  if (t < HANDOVER_FIELD_MAX) return { label: 'Regions', detail: 'the broad picture' };
  if (t > HANDOVER_LOCATIONS_MIN) {
    return { label: 'Places', detail: 'individual spots, region colour behind' };
  }
  return { label: 'Zooming in', detail: 'regions giving way to places' };
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
 * @param {'solar'|'aurora'} [props.variant] which ramp the panel describes (aurora-heat-plan.md §3
 *   A3). `'aurora'` paints the bar from {@link AURORA_RAMP}`.gradientCss()` — never
 *   {@link rampGradientCss}, for the reason above — and words the labels and note for an aurora
 *   night; the handover indicator and the reach-rings toggle are the same in both.
 * @param {boolean} [props.viewlineShown] whether the viewline overlay is actually mounted; the
 *   aurora note describes the line only when it is (never describe a line that is not there)
 * @param {boolean} [props.reachMeasured] whether a real drive time gates this reader's reach lens
 *   (§5.2's honesty rule) — the toggle states the ring tiers as a DISTANCE by default and only
 *   upgrades to durations once a measured drive exists, the same rule `MapLabels`' own ring labels
 *   and `WindowRowFieldMap`'s ring labels already apply.
 */
export default function MapLegendPanel({
  open, onOpenChange, handoverFraction, ringsEnabled, onToggleRings,
  hasHome = false, reachMeasured = false, variant = 'solar', viewlineShown = false,
  groundKey = true, stoodKey = false, seasKey = false,
}) {
  const aurora = variant === 'aurora';
  const rootRef = useRef(null);
  const panelRef = useRef(null);

  // Opens UP from the bottom-left chip, so it is the frame's TOP edge it can run past: the room stops under
  // the top-left chrome group (window pill, big-seas line, topic chips), not at the frame's top.
  useFitToFrame(panelRef, rootRef, { direction: 'up', open, clearTopChrome: true });


  // A press on the MAP dismisses nothing — `useOutsideDismiss` carries that rule for all four map
  // panels so they cannot drift apart.
  //
  // ⚠️ **No `enabled` gate, and the reason is a mount condition rather than this component.** Its
  // two siblings pass `!isMobile` because they become a portalled `BottomSheet` on a phone; this
  // panel is simply not mounted there (`MapView` gates it on `!isMobile`). ⚠️ If it ever grows a
  // phone surface — `map-tab-v2-plan.md` **O-15** option (b) proposes exactly a `BottomSheet`
  // variant — this call needs the gate, or the first tap inside the sheet will close it.
  useOutsideDismiss({
    open, rootRef, onDismiss: () => onOpenChange(false),
  });

  function onKeyDown(e) {
    // ⚠️ Stand down entirely while a dialog from OUTSIDE the map pane is over it — the same
    // predicate `MapView`'s pane handler, the landing card's listener and the drilldown's two
    // panels read. Without it this operates the map BEHIND an open sheet: `map-landing-plan.md`
    // §4 #37 recorded that for the two panels, and an accessibility lens then found this control
    // is on the very route that reaches them — a keyboard reader gets here by Tabbing out of the
    // non-trapping sheet onto the pill, so the drilldown cannot be opened behind a sheet without
    // opening THIS first.
    if (foreignModalOverPaneOf(rootRef.current)) return;
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
        <div ref={panelRef} id="wf-legend-panel" data-testid="wf-legend-panel" className="wf-legend-panel" role="dialog" aria-label="Map legend">
          {aurora && <div className="wf-legend-ramp-label">COLOUR = AURORA RATING</div>}
          <div
            data-testid="wf-legend-ramp"
            className={aurora ? 'wf-legend-ramp wf-legend-ramp-aurora' : 'wf-legend-ramp'}
            style={{ background: aurora ? AURORA_RAMP.gradientCss() : rampGradientCss() }}
          />
          <div className="wf-legend-ramp-row">
            {aurora ? (
              <>
                <span>1&#9733; dark sky, no show</span>
                <span>3&#9733;</span>
                <span>5&#9733; aurora green</span>
              </>
            ) : (
              <>
                <span>1&#9733; poor</span>
                <span>3&#9733;</span>
                <span>5&#9733; worth it</span>
              </>
            )}
          </div>

          {/* thin-sample plan F4: the two ground states beside the ramp's "rated" — the hatch is drawn
              around places nobody has rated for this window, plain ground where there are no places.
              Solar windows only; an aurora night draws no hatch, so its key has none. */}
          {!aurora && groundKey && (
            <div data-testid="wf-legend-ground" className="wf-legend-ground">
              <span className="wf-legend-ground-item">
                <i aria-hidden="true" className="wf-legend-swatch wf-legend-swatch-hatch" />
                not rated yet
              </span>
              <span className="wf-legend-ground-item">
                <i aria-hidden="true" className="wf-legend-swatch" />
                no places
              </span>
            </div>
          )}

          {/* Big-seas plan F3: the stand-down hatch's key, while the hatch is drawn (solar, with a
              weather stand-down in the window), beside the ground key. */}
          {stoodKey && !aurora && (
            <div data-testid="wf-legend-standdown">
              <div className="wf-legend-ramp-label">Stood down</div>
              <div className="wf-legend-ground">
                <span className="wf-legend-ground-item">
                  <i aria-hidden="true" className="wf-legend-swatch wf-legend-swatch-standdown" />
                  ruled out by the weather, cause named on the ground
                </span>
              </div>
            </div>
          )}

          {/* Big-seas plan F2: the chip's key, while chips are drawn (one condition with the chip mount,
              the `viewlineShown` pattern). The swatch is the chip's own plate and bolt, so the key
              reads as the thing it names; "ringed" is the aligned chip's second ring. */}
          {seasKey && !aurora && (
            <div data-testid="wf-legend-bigseas">
              <div className="wf-legend-ramp-label">Big seas</div>
              <div className="wf-legend-ground">
                <span className="wf-legend-ground-item">
                  <i aria-hidden="true" className="wf-legend-swatch-seas">
                    <BoltIcon />
                  </i>
                  waves onto a pier or lighthouse at high water &middot; ringed when high water is on the light
                </span>
              </div>
            </div>
          )}

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

          {aurora ? (
            <div data-testid="wf-legend-note" className="wf-legend-note">
              <b>Rating, not Kp.</b> Each location&rsquo;s score already folds in latitude, cloud and
              sky darkness.
              {viewlineShown && ' The line is where the aurora clears the northern horizon on camera; dashed amber is the forecast, pale is live.'}
            </div>
          ) : (
            <div data-testid="wf-legend-note" className="wf-legend-note">
              Colour appears only near rated locations, and only over land. Later days look
              hazier &mdash; lower confidence.
            </div>
          )}
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
  variant: PropTypes.oneOf(['solar', 'aurora']),
  viewlineShown: PropTypes.bool,
  /** Whether the hatch is drawn for this window (solar, and rated); the ground key is withheld otherwise. */
  groundKey: PropTypes.bool,
  /** Whether the stand-down hatch is drawn for this window (big-seas plan F3). */
  stoodKey: PropTypes.bool,
  /** Whether big-seas chips are drawn (big-seas plan F2); the key is withheld otherwise. */
  seasKey: PropTypes.bool,
};
