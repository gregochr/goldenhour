import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { rampHex } from '../../utils/scoreRamp.js';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';
import BottomSheet from '../BottomSheet.jsx';
import useDialogFocus from '../../hooks/useDialogFocus.js';
import { useIsMobile } from '../../hooks/useIsMobile.js';

/**
 * The Map tab's Regions jump list (map-tab-v2-plan.md §3 P11,
 * `docs/design/map-tab-v2/README.md` §2 "Regions jump list — search, without a text field") —
 * "search, without a text field": `◎ Regions ▾` opens one row per region, nearest measured drive
 * first, and selecting a row is the whole interaction.
 *
 * <p>Purely presentational and fully controlled, exactly like `FiltersPopover`/`MapLegendPanel`:
 * every row is a prop (`rows`, from `utils/regionsJump.buildJumpRows`) and this component owns only
 * its own open/close plumbing (click-outside, `Escape`) plus the row markup. It derives nothing —
 * the sort, the "beyond your area" suffix and the best-score join all happen in the caller, off the
 * SAME data every other chrome on this tab reads, so a second implementation cannot disagree with
 * the first about where a region sits.
 *
 * <h2>Phone: the same rows, in a {@code BottomSheet} (map-tab-v2-plan.md §3 P12)</h2>
 *
 * <p>README "Responsive" table: the 300px popover becomes a bottom sheet under the app's own
 * {@code useIsMobile} breakpoint, the exact same treatment `FiltersPopover` takes and for the same
 * reasons — see that component's class doc for the full reasoning (shared row markup, `modal={false}`
 * because this is a disclosure widget rather than a dialog, and the outside-click listener gated off
 * on the phone because `BottomSheet` owns its own backdrop dismissal there).
 *
 * <h2>The way back is a row in this list, not a control somewhere else</h2>
 *
 * <p>A jump is a completed navigation, so its inverse belongs beside the action that caused it.
 * Before this, undoing one meant `CentreOnHomeControl`'s `⌂` or `FiltersPopover`'s scope
 * segment — and on the phone the first is hidden outright (`index.css`'s
 * `.wf-map-tab .map-home-control { display: none }`, because the bottom bar covers Leaflet's
 * bottom-right corner) while the second is withheld whenever the area frame does not narrow
 * (`heat.hasHome`). A phone reader with no saved postcode therefore had NO way back at all, and one
 * with a postcode had a way back filed under a different chip that never mentions regions. So
 * `resetLabel`/`onReset` render one more row at the top of the list, and `activeRegion` marks the
 * row whose jump is in force. ⚠️ Not the only thing on the tab that names it — `MapBreadcrumb`'s
 * region clause does too — but that strip mounts only on a Plan-door arrival carrying that same
 * region, so on every other route here this list is the only place the framed region is named.
 *
 * <p>The reset row is drawn ONLY while a jump stands (`activeRegion` non-null). With no jump there
 * is nothing to undo and the row would be the no-op control `FiltersPopover`'s own scope comment
 * bans outright. Its label names the scope the reader LANDS in rather than saying "all regions",
 * because a reader whose scope is My area lands back in My area — a subset — and the
 * caller is the only place that knows which (see `MapView.jumpResetLabel`).
 *
 * @param {object} props
 * @param {boolean} props.open
 * @param {Function} props.onOpenChange
 * @param {Array<{name: string, driveMinutes: ?number, beyondArea: boolean, bestRating: ?number}>}
 *        props.rows from {@code utils/regionsJump.buildJumpRows}
 * @param {(regionName: string) => void} props.onSelectRegion
 * @param {?string} [props.activeRegion] the region the map's jump-fit override is currently framing
 *        ({@code MapView}'s {@code jumpFitOverride.regionName}), or null when no jump stands
 * @param {?string} [props.resetLabel] the scope the reset row returns to, already resolved by the
 *        caller — "My area", "Everywhere", or an away origin's own "Around …"
 * @param {?Function} [props.onReset] clears the standing jump ({@code MapView.clearRegionJump})
 */
export default function RegionsJump({
  open, onOpenChange, rows, onSelectRegion,
  activeRegion = null, resetLabel = null, onReset = null,
}) {
  const rootRef = useRef(null);
  const isMobile = useIsMobile();
  /**
   * Focus-in-and-restore for the DESKTOP popover — never containment (`useDialogFocus`'s own class
   * doc records why this app refuses to trap app-wide). The phone twin has had this since it was
   * built, because `BottomSheet` runs the same hook; the popover never did, and pressing a row
   * unmounts it while that row still holds focus, which browsers reset to `<body>` — the exact
   * failure that hook's doc names. Harmless while every row was a jump, and no longer: the reset
   * row's whole job is recovery, so ending a press with the reader's place thrown away is the
   * opposite of what it is for. Passing `open && !isMobile` keeps it a no-op on the phone, where
   * `BottomSheet` already owns it — running both would restore focus twice.
   */
  const popoverRef = useDialogFocus(open && !isMobile);

  useEffect(() => {
    // Desktop/tablet only — `FiltersPopover`'s identical guard, for the identical reason.
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

  // The way back, above the rows it undoes. Drawn only while a jump stands — see the class doc.
  const resetRow = activeRegion && onReset ? (
    <button
      type="button"
      data-testid="wf-jump-reset"
      className="wf-jump-row wf-jump-reset"
      onClick={onReset}
    >
      <span className="wf-jump-name">
        <span aria-hidden="true">&#8634; </span>
        Back to {resetLabel}
      </span>
    </button>
  ) : null;

  // The rows themselves — identical on every viewport. Only the wrapper differs (popover on
  // desktop/tablet, `BottomSheet` on the phone), `FiltersPopover`'s own pattern.
  const panelBody = rows.length === 0 ? (
    <div data-testid="wf-jump-empty" className="wf-jump-empty">No regions yet</div>
  ) : rows.map((row) => (
    <button
      key={row.name}
      type="button"
      data-testid="wf-jump-row"
      className="wf-jump-row"
      // The jump in force, marked where it was chosen — nothing else on this tab names it. A CSS
      // treatment rather than an extra glyph or a fourth grid column, both of which would move the
      // marked row's own text out of line with every other row's.
      aria-current={row.name === activeRegion ? 'true' : undefined}
      onClick={() => onSelectRegion(row.name)}
    >
      <span className="wf-jump-name">{row.name}</span>
      <span data-testid="wf-jump-drive" className="wf-jump-drive">
        {row.driveMinutes != null
          ? `${formatDriveDuration(row.driveMinutes)}${row.beyondArea ? ' · beyond your area' : ''}`
          : ''}
      </span>
      <span className="wf-jump-score">
        {row.bestRating != null ? (
          <>
            <i aria-hidden="true" style={{ background: rampHex(row.bestRating) }} />
            {row.bestRating}&#9733;
          </>
        ) : (
          <span className="wf-jump-unscored">&mdash;</span>
        )}
      </span>
    </button>
  ));

  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div ref={rootRef} data-testid="wf-jump" className="wf-jump" onKeyDown={onKeyDown}>
      <button
        type="button"
        data-testid="wf-jump-chip"
        className="wf-jump-chip"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="wf-jump-menu"
        onClick={() => onOpenChange(!open)}
      >
        <span aria-hidden="true">&#9678; </span>
        Regions
        <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
      </button>

      {isMobile ? (
        <BottomSheet open={open} onClose={() => onOpenChange(false)} label="Jump to a region" modal={false} reserveCloseStrip>
          <div id="wf-jump-menu" data-testid="wf-jump-menu" className="wf-jump-sheet">
            {resetRow}
            {panelBody}
          </div>
        </BottomSheet>
      ) : (
        open && (
          <div
            ref={popoverRef}
            tabIndex={-1}
            id="wf-jump-menu"
            data-testid="wf-jump-menu"
            className="wf-jump-menu"
            role="dialog"
            aria-label="Jump to a region"
          >
            {resetRow}
            {panelBody}
          </div>
        )
      )}
    </div>
  );
}

RegionsJump.propTypes = {
  open: PropTypes.bool.isRequired,
  onOpenChange: PropTypes.func.isRequired,
  rows: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    driveMinutes: PropTypes.number,
    beyondArea: PropTypes.bool.isRequired,
    bestRating: PropTypes.number,
  })).isRequired,
  onSelectRegion: PropTypes.func.isRequired,
  activeRegion: PropTypes.string,
  resetLabel: PropTypes.string,
  onReset: PropTypes.func,
};
