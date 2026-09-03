import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { rampHex } from '../../utils/scoreRamp.js';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';

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
 * @param {object} props
 * @param {boolean} props.open
 * @param {Function} props.onOpenChange
 * @param {Array<{name: string, driveMinutes: ?number, beyondArea: boolean, bestRating: ?number}>}
 *        props.rows from {@code utils/regionsJump.buildJumpRows}
 * @param {(regionName: string) => void} props.onSelectRegion
 */
export default function RegionsJump({
  open, onOpenChange, rows, onSelectRegion,
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

  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div ref={rootRef} data-testid="wf-jump" className="wf-jump" onKeyDown={onKeyDown}>
      <button
        type="button"
        data-testid="wf-jump-chip"
        className="wf-jump-chip"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => onOpenChange(!open)}
      >
        <span aria-hidden="true">&#9678; </span>
        Regions
        <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
      </button>

      {open && (
        <div data-testid="wf-jump-menu" className="wf-jump-menu" role="dialog" aria-label="Jump to a region">
          {rows.length === 0 ? (
            <div data-testid="wf-jump-empty" className="wf-jump-empty">No regions yet</div>
          ) : rows.map((row) => (
            <button
              key={row.name}
              type="button"
              data-testid="wf-jump-row"
              className="wf-jump-row"
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
          ))}
        </div>
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
};
