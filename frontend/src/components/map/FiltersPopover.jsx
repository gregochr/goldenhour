import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { rampHex } from '../../utils/scoreRamp.js';
import { STAND_DOWN_COLOUR } from '../markerUtils.js';
import InfoTip from '../InfoTip.jsx';
import BottomSheet from '../BottomSheet.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';

/**
 * The Map tab's filters popover — map-tab-v2-plan.md §3 P7,
 * docs/design/map-tab-v2/README.md "§4 Filters popover".
 *
 * <p>Replaces the tab's old slide-down drawer (the {@code advancedOpen}/`advanced-filters-panel`
 * pair still used, unchanged, by the Plan-tab overlay — see `MapView.jsx`'s `overlayMode` branch,
 * which this component never touches). A single chip (`Filters (N) ▾`) opens a 318px panel over
 * the map; the count excludes scope on purpose (§4: "N = count of active filters, scope not
 * counted") because switching "My area" ⇄ "Everywhere" reframes the camera rather than hiding
 * anything the reader asked to see.
 *
 * <p>Every row ports the OLD drawer's own semantics rather than the design mock's literal shape —
 * plan §3 P7 says so explicitly ("keep the persisted `mapFilterMinStars` default"): the minimum-
 * rating control stays a true "this-and-above" threshold (always a value, no "Any" state, unlike
 * the mock's five-way segment), and the drive-time control gains the mock's three named tiers
 * (45 min / 1h 30 / 2h 30) as a segmented control in place of the old `<select>`, because the two
 * live in separate `MapView` mounts (this one, and the untouched overlay) and so cannot disagree
 * with each other by construction.
 *
 * <p>Purely presentational and fully controlled: every value and every setter is a prop, and this
 * component holds no filter state of its own. It owns exactly two things — its own open/close
 * plumbing (click-outside, `Escape`) and the row markup.
 *
 * <h2>Phone: the same rows, in a {@code BottomSheet} (map-tab-v2-plan.md §3 P12)</h2>
 *
 * <p>README "Responsive" table: the 318px popover becomes a bottom sheet under the app's own
 * {@code useIsMobile} breakpoint (≤639px — reconciled from the design bundle's 390px iPhone
 * viewport, per the plan's own note that {@code useIsMobile} is the breakpoint to carry, not the
 * bundle's literal figure). The row markup below is shared byte-for-byte between the two shapes —
 * only the OUTER wrapper changes — so a filter row can never drift between viewports. `modal={false}`
 * on the sheet: this is `FiltersPopover`'s own disclosure widget, exactly like the desktop popover
 * beside it (no `aria-modal` there either, and `useDialogFocus` never traps focus either way), not
 * a modal dialog that happens to render at the bottom of the screen. The outside-click listener
 * below is desktop-only — `BottomSheet` already dismisses on its own backdrop, and `rootRef` does
 * not contain the sheet's body once it is portalled to `document.body`, so leaving that listener
 * live on a phone would close the sheet on the FIRST tap inside it.
 */
export default function FiltersPopover({
  open, onOpenChange,
  minStars, onSelectMinStars,
  activeTypeFilters, onToggleType, subjectChips, seasonalFeatures, role,
  driveTimeFilter, onSelectDriveTime,
  darkSkyFilter, onToggleDarkSky, darkSkyThreshold,
  hasHome, heatArea, onSelectScope, areaLabel,
  isAuroraMode, isAstroMode,
  showAdminRow, showStandDown, onToggleStandDown, hasStandDown,
  showUnrated, onToggleUnrated, hasUnrated,
  activeCount, filteredCount, scopeCount, onClearAll,
}) {
  const rootRef = useRef(null);
  const isMobile = useIsMobile();

  useEffect(() => {
    // Desktop/tablet only — see the class doc's phone section. `BottomSheet`'s own backdrop is
    // the phone's dismiss surface, and its content is portalled OUTSIDE `rootRef`, so this listener
    // would otherwise fire (and close the sheet) on the very first tap inside it.
    if (!open || isMobile) return undefined;
    function onDocMouseDown(e) {
      if (rootRef.current && !rootRef.current.contains(e.target)) onOpenChange(false);
    }
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, isMobile]);

  function onKeyDown(e) {
    if (e.key === 'Escape' && open) {
      e.preventDefault();
      onOpenChange(false);
    }
  }

  const showClear = activeCount > 0;

  // The rows themselves — identical on every viewport (class doc's phone section). Only the
  // wrapper around this fragment differs: a positioned popover `<div>` on desktop/tablet, a
  // `BottomSheet` on the phone.
  const panelBody = (
    <>
      {/* ── Minimum rating — a true "this and above" threshold, not the mock's Any/2+/3+/4+/5
          segment (plan §3 P7: "keep the persisted mapFilterMinStars default"). ── */}
      <div className="wf-filters-row">
        <span className="wf-filters-key">Minimum rating</span>
        <div className="wf-filters-seg" role="group" aria-label="Minimum rating">
          {[1, 2, 3, 4, 5].map((star) => (
            <button
              key={`star-${star}`}
              type="button"
              data-testid={`star-filter-${star}`}
              aria-pressed={star >= minStars}
              title={`Show ${star}★ and above`}
              className={`wf-filters-seg-btn${star >= minStars ? ' on' : ''}`}
              onClick={() => onSelectMinStars(star)}
            >
              <span aria-hidden="true" className="wf-filters-dot" style={{ width: 8, height: 8, backgroundColor: rampHex(star) }} />
              {star}&#9733;{star < 5 ? '+' : ''}
            </button>
          ))}
        </div>
        {showAdminRow && (
          <div className="wf-filters-admin-row">
            <span className="wf-filters-admin-tag">admin</span>
            <button
              type="button"
              onClick={onToggleStandDown}
              disabled={!hasStandDown}
              data-testid="star-filter-standdown"
              title={!hasStandDown
                ? 'No stand-down locations in view'
                : showStandDown ? 'Hide stand-down locations' : 'Show stand-down locations'}
              className={`wf-filters-seg-btn${showStandDown ? ' on' : ''}${!hasStandDown ? ' disabled' : ''}`}
            >
              <span aria-hidden="true" className="wf-filters-dot" style={{ width: 8, height: 8, backgroundColor: STAND_DOWN_COLOUR }} />
              &mdash; stand-down
            </button>
            <button
              type="button"
              onClick={onToggleUnrated}
              disabled={!hasUnrated}
              data-testid="star-filter-unrated"
              title={!hasUnrated ? 'No unknown-state locations in view' : 'Toggle locations with no evaluation'}
              className={`wf-filters-seg-btn wf-filters-seg-btn--unrated${showUnrated ? ' on' : ''}${!hasUnrated ? ' disabled' : ''}`}
            >
              <span
                aria-hidden="true"
                className="wf-filters-dot"
                style={{ width: 8, height: 8, backgroundColor: 'transparent', border: '1px dashed #888780' }}
              />
              ? unknown
            </button>
          </div>
        )}
      </div>

      {/* ── Subject — hidden in Aurora/Astro modes, matching the old drawer's own gate. ── */}
      {!isAuroraMode && !isAstroMode && (
        <div className="wf-filters-row">
          <span className="wf-filters-key">Subject</span>
          <div className="wf-filters-chips">
            {subjectChips.map(([type, { label, emoji }]) => (
              <button
                key={type}
                type="button"
                data-testid={`location-type-filter-${type}`}
                onClick={() => onToggleType(type)}
                className={`wf-filters-chip-btn${activeTypeFilters.has(type) ? ' on' : ''}`}
              >
                <span aria-hidden="true">{emoji}</span> {label}
              </button>
            ))}
            {seasonalFeatures.includes('BLUEBELL') && (
              <button
                key="BLUEBELL"
                type="button"
                data-testid="location-type-filter-BLUEBELL"
                onClick={() => (role !== 'LITE_USER' ? onToggleType('BLUEBELL') : undefined)}
                disabled={role === 'LITE_USER'}
                className={`wf-filters-chip-btn${activeTypeFilters.has('BLUEBELL') ? ' on' : ''}${role === 'LITE_USER' ? ' disabled' : ''}`}
                title={role === 'LITE_USER' ? 'Upgrade to Pro to filter by Bluebell sites' : undefined}
              >
                🌸 Bluebell
              </button>
            )}
          </div>
        </div>
      )}

      {/* ── Drive time — the mock's three named tiers, segmented (plan §3 P7). ── */}
      <div className="wf-filters-row">
        <span className="wf-filters-key">Drive time</span>
        <div className="wf-filters-seg" role="group" aria-label="Maximum drive time">
          {DRIVE_TIME_TIERS.map(([value, label]) => (
            <button
              key={value}
              type="button"
              data-testid={`drive-time-filter-${value}`}
              aria-pressed={driveTimeFilter === value}
              className={`wf-filters-seg-btn${driveTimeFilter === value ? ' on' : ''}`}
              onClick={() => onSelectDriveTime(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* ── Sky — dark-sky-only toggle, hidden in Aurora/Astro (the old drawer's own gate:
          those modes already narrow to a dark-sky-relevant roster by construction). ── */}
      {!isAuroraMode && !isAstroMode && (
        <div className="wf-filters-row">
          <span className="wf-filters-key">Sky</span>
          <div className="wf-filters-chips">
            <button
              type="button"
              onClick={onToggleDarkSky}
              data-testid="dark-sky-filter-toggle"
              title={`Show only locations with a light pollution rating of ${darkSkyThreshold} or lower — suitable for aurora, astrophotography, and stargazing.`}
              className={`wf-filters-chip-btn wf-filters-chip-btn--dark${darkSkyFilter ? ' on' : ''}`}
            >
              🔭 Dark sky only
            </button>
            <InfoTip text={`Shows locations with a light pollution rating of ${darkSkyThreshold} or lower — suitable for aurora, astrophotography, and stargazing.${role === 'ADMIN' ? '\n\nRun 🌌 Refresh Light Pollution in Location Management to populate ratings.' : ''}`} />
          </div>
        </div>
      )}

      {/* ── Scope — absent entirely without a home (heat.hasHome), matching the old toolbar's
          own rule (field-geography-glyphs-plan.md's coherence rule, NOT map-tab-v2-plan.md's
          D-6, which is the unrelated maxZoom-16 decision): with no postcode "My area" and
          "Everywhere" frame the same box over the same spots, and a control whose every
          press does nothing is banned outright. ── */}
      {hasHome && (
        <div className="wf-filters-row">
          <span className="wf-filters-key">Scope</span>
          <div className="wf-filters-seg" role="group" aria-label="Map area">
            <button
              type="button"
              data-testid="wf-filters-scope-home"
              aria-pressed={heatArea}
              className={`wf-filters-seg-btn${heatArea ? ' on' : ''}`}
              onClick={() => onSelectScope(true)}
            >
              <span aria-hidden="true">◎ </span>
              {areaLabel || 'My area'}
            </button>
            <button
              type="button"
              data-testid="wf-filters-scope-all"
              aria-pressed={!heatArea}
              className={`wf-filters-seg-btn${heatArea ? '' : ' on'}`}
              onClick={() => onSelectScope(false)}
            >
              Everywhere
            </button>
          </div>
        </div>
      )}

      <div className="wf-filters-foot">
        <span><b>{filteredCount}</b> of {scopeCount} shown</span>
        {showClear && (
          <button type="button" data-testid="clear-all-filters" className="wf-filters-clear" onClick={onClearAll}>
            Clear all
          </button>
        )}
      </div>
    </>
  );

  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div ref={rootRef} data-testid="wf-filters" className="wf-filters" onKeyDown={onKeyDown}>
      <button
        type="button"
        data-testid="wf-filters-chip"
        className={`wf-filters-chip${activeCount > 0 ? ' active' : ''}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="wf-filters-panel"
        onClick={() => onOpenChange(!open)}
      >
        Filters{activeCount > 0 && ` (${activeCount})`}
        <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
      </button>

      {isMobile ? (
        <BottomSheet open={open} onClose={() => onOpenChange(false)} label="Map filters" modal={false} reserveCloseStrip>
          <div id="wf-filters-panel" data-testid="wf-filters-panel" className="wf-filters-sheet">
            {panelBody}
          </div>
        </BottomSheet>
      ) : (
        open && (
          <div id="wf-filters-panel" data-testid="wf-filters-panel" className="wf-filters-panel" role="dialog" aria-label="Map filters">
            {panelBody}
          </div>
        )
      )}
    </div>
  );
}

/**
 * The design's three named drive-time tiers (README §4) — 45 min / 1h 30 / 2h 30 — replacing the
 * old drawer's six-option `<select>`. Distinct from `RING_TIERS`' 25/50-mile pair (field-geography
 * §5.2, `field-geography-glyphs-plan.md`): rings are a distance drawn on the field, this is a
 * minutes-based pool filter, and the two answer different questions even where their numbers are
 * close.
 */
export const DRIVE_TIME_TIERS = [
  [0, 'Any'],
  [45, '45 min'],
  [90, '1h 30'],
  [150, '2h 30'],
];

FiltersPopover.propTypes = {
  open: PropTypes.bool.isRequired,
  onOpenChange: PropTypes.func.isRequired,
  minStars: PropTypes.number.isRequired,
  onSelectMinStars: PropTypes.func.isRequired,
  activeTypeFilters: PropTypes.instanceOf(Set).isRequired,
  onToggleType: PropTypes.func.isRequired,
  subjectChips: PropTypes.arrayOf(PropTypes.array).isRequired,
  seasonalFeatures: PropTypes.arrayOf(PropTypes.string),
  role: PropTypes.string,
  driveTimeFilter: PropTypes.number.isRequired,
  onSelectDriveTime: PropTypes.func.isRequired,
  darkSkyFilter: PropTypes.bool.isRequired,
  onToggleDarkSky: PropTypes.func.isRequired,
  darkSkyThreshold: PropTypes.number,
  hasHome: PropTypes.bool,
  heatArea: PropTypes.bool,
  onSelectScope: PropTypes.func,
  areaLabel: PropTypes.string,
  isAuroraMode: PropTypes.bool,
  isAstroMode: PropTypes.bool,
  showAdminRow: PropTypes.bool,
  showStandDown: PropTypes.bool,
  onToggleStandDown: PropTypes.func,
  hasStandDown: PropTypes.bool,
  showUnrated: PropTypes.bool,
  onToggleUnrated: PropTypes.func,
  hasUnrated: PropTypes.bool,
  /** The chip's own `(N)` — active filters EXCLUDING scope (plan §3 P7, README §4). */
  activeCount: PropTypes.number.isRequired,
  /** The footer's "N of M shown" — the fully-filtered pool. */
  filteredCount: PropTypes.number.isRequired,
  /** The footer's "of M" — the scope-only pool, before every other filter. */
  scopeCount: PropTypes.number.isRequired,
  onClearAll: PropTypes.func.isRequired,
};
