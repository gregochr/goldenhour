import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/**
 * The count of locations ("266 locations", "1 location").
 *
 * @param {number} n the count
 * @returns {string} the phrase
 */
export const pluralLocations = (n) => `${n} location${n === 1 ? '' : 's'}`;

/**
 * The tide options' scope: the coastal locations grouped by region, read-only. With none it says so.
 *
 * @param {object} props
 * @param {Array<{region: string, locations: string[]}>} props.groups the regions, each with its location names
 * @param {number} props.total how many locations in all
 */
export function LocationSummary({ groups, total }) {
  if (total === 0) {
    return (
      <p className="text-xs italic text-plex-text-muted" data-testid="start-run-scope-empty">
        No matching locations found.
      </p>
    );
  }
  return (
    <div className="max-h-48 space-y-2 overflow-y-auto text-xs" data-testid="start-run-location-summary">
      {groups.map((g) => (
        <div key={g.region}>
          <p className="font-medium text-plex-text">{g.region} ({g.locations.length})</p>
          <p className="ml-2 text-plex-text-muted">{g.locations.join(', ')}</p>
        </div>
      ))}
      <p className="border-t border-plex-border pt-1 font-medium text-plex-text-secondary">
        {pluralLocations(total)} total
      </p>
    </div>
  );
}

LocationSummary.propTypes = {
  groups: PropTypes.arrayOf(PropTypes.shape({
    region: PropTypes.string.isRequired,
    locations: PropTypes.arrayOf(PropTypes.string).isRequired,
  })).isRequired,
  total: PropTypes.number.isRequired,
};

const ROW_CLASS = 'flex min-h-8 cursor-pointer items-center gap-2.5 text-[13px] text-plex-text max-[639px]:min-h-11';
const BOX_CLASS = 'size-4 shrink-0 cursor-pointer accent-plex-gold';
const COUNT_CLASS = 'ml-auto font-mono text-[11.5px] text-plex-text-muted';

/**
 * A batch option's scope: an "All regions" row with the total, then each region with its location count.
 * {@code selection} is the resolved set of checked region ids (the caller turns an untouched selection into every
 * region); the caller owns it.
 *
 * @param {object} props
 * @param {Array<{id: number, name: string, locationCount: number}>} props.regions the regions
 * @param {Set<number>} props.selection the checked region ids
 * @param {(regionId: number|string) => void} props.onToggle toggles one region, or all of them ({@code '__all__'})
 * @param {boolean} props.disabled whether a run is in flight
 */
export function RegionChecklist({ regions, selection, onToggle, disabled }) {
  const allRef = useRef(null);
  const all = selection.size === regions.length;
  const some = selection.size > 0 && !all;
  useEffect(() => {
    if (allRef.current) allRef.current.indeterminate = some;
  }, [some]);

  const total = regions.reduce((sum, r) => sum + r.locationCount, 0);
  return (
    <div className="space-y-0.5" data-testid="start-run-regions">
      <label className={`${ROW_CLASS} font-medium`} data-testid="batch-region-toggle-all">
        <input
          ref={allRef}
          type="checkbox"
          className={BOX_CLASS}
          checked={all}
          disabled={disabled}
          onChange={() => onToggle('__all__')}
        />
        <span>All regions</span>
        <span className={COUNT_CLASS} data-testid="batch-region-count-all">{pluralLocations(total)}</span>
      </label>
      {regions.map((r) => (
        <label key={r.id} className={ROW_CLASS} data-testid={`batch-region-toggle-${r.id}`}>
          <input
            type="checkbox"
            className={BOX_CLASS}
            checked={selection.has(r.id)}
            disabled={disabled}
            onChange={() => onToggle(r.id)}
          />
          <span>{r.name}</span>
          <span className={COUNT_CLASS} data-testid={`batch-region-count-${r.id}`}>{r.locationCount}</span>
        </label>
      ))}
    </div>
  );
}

RegionChecklist.propTypes = {
  regions: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.number.isRequired,
    name: PropTypes.string.isRequired,
    locationCount: PropTypes.number.isRequired,
  })).isRequired,
  selection: PropTypes.instanceOf(Set).isRequired,
  onToggle: PropTypes.func.isRequired,
  disabled: PropTypes.bool.isRequired,
};
