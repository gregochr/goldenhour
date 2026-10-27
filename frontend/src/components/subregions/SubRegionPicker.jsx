import { useEffect, useId, useRef, useState } from 'react';
import PropTypes from 'prop-types';

/** The reason an unchosen row gives at the limit (handoff A1, verbatim). */
function limitLine(limit) {
  return `Choose up to ${limit} — remove one to swap.`;
}

/** What an unchosen row says when the reader may only remove picks (over a lowered limit). */
const REMOVALS_ONLY_LINE = 'Remove sub-regions to get within the limit. You can add again after saving.';

/** "12 locations · Berwick Pier, Lindisfarne Castle" (the API sends up to two examples, alphabetically). */
function rowMeta(option) {
  const count = `${option.locationCount} ${option.locationCount === 1 ? 'location' : 'locations'}`;
  return option.exampleLocations?.length ? `${count} · ${option.exampleLocations.join(', ')}` : count;
}

/**
 * The shared sub-region picker (sub-regions-plan §5.4 S4; design handoff A1): registration, the sign-in
 * prompt and Settings all render this one component. It holds no rule of its own: what the reader may do
 * comes in as props, decided by the server's state.
 *
 * <p><b>The limit never uses {@code disabled}.</b> An unchosen row at the limit is {@code aria-disabled}
 * so it stays focusable and a keyboard or screen-reader reader can reach it and be told why; activating it
 * changes nothing and shows the reason under the row until the next change.
 *
 * <p>{@code lockedIds} are picks that cannot be removed (plan §4.3: while locked, adding under the limit is
 * still allowed, removing is not). {@code removalsOnly} is the over-limit case: the saved picks exceed a
 * lowered limit, so only removals are allowed. {@code mode="read"} makes every row inert and the chips
 * plain.
 *
 * <p>Focus: toggling a checkbox leaves it where it was (the element is stable); removing a pick from its
 * chip, which unmounts the button the reader pressed, moves focus to that sub-region's row.
 *
 * @param {object} props
 * @param {Array<{regionId: number, regionName: string, subRegions: Array<object>}>} props.options
 *   the GET's options, grouped by region
 * @param {number[]} props.value chosen sub-region (area) ids
 * @param {number} props.limit the most that may be chosen
 * @param {(ids: number[]) => void} [props.onChange] called with the new chosen ids
 * @param {'edit'|'read'} [props.mode]
 * @param {number[]} [props.lockedIds] picks that cannot be removed
 * @param {string} [props.lockedReason] shown under a locked row when the reader tries to remove it
 * @param {boolean} [props.removalsOnly] only removals are allowed
 * @param {string} [props.testIdPrefix]
 */
export default function SubRegionPicker({
  options, value, limit, onChange = () => {}, mode = 'edit', lockedIds = [], lockedReason = '',
  removalsOnly = false, testIdPrefix = 'sub-region-picker',
}) {
  const baseId = useId();
  const limitId = `${baseId}-limit`;
  const read = mode === 'read';
  const chosen = new Set(value);
  const locked = new Set(lockedIds);
  const count = value.length;
  const addsBlocked = !read && (removalsOnly || count >= limit);
  const hintText = removalsOnly ? REMOVALS_ONLY_LINE : limitLine(limit);

  // A region starts open when it holds a chosen (or pre-chosen, suggested) sub-region.
  const [open, setOpen] = useState(() => new Set(
    options.filter((r) => r.subRegions.some((s) => chosen.has(s.areaId))).map((r) => r.regionId),
  ));
  // The row whose reason is showing, until the next change.
  const [nudge, setNudge] = useState(null);
  const rowRefs = useRef(new Map());
  const focusAfter = useRef(null);

  useEffect(() => {
    if (focusAfter.current == null) return;
    rowRefs.current.get(focusAfter.current)?.focus();
    focusAfter.current = null;
  });

  const byId = new Map();
  const regionOf = new Map();
  for (const region of options) {
    for (const option of region.subRegions) {
      byId.set(option.areaId, option);
      regionOf.set(option.areaId, region.regionId);
    }
  }

  function toggleRegion(regionId) {
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(regionId)) next.delete(regionId); else next.add(regionId);
      return next;
    });
  }

  function toggle(areaId) {
    setNudge(null);
    if (read) return;
    if (chosen.has(areaId)) {
      if (locked.has(areaId)) {
        if (lockedReason) setNudge({ areaId, text: lockedReason });
        return;
      }
      onChange(value.filter((id) => id !== areaId));
      return;
    }
    if (addsBlocked) {
      setNudge({ areaId, text: hintText });
      return;
    }
    onChange([...value, areaId]);
  }

  function removeFromChip(areaId) {
    setNudge(null);
    const regionId = regionOf.get(areaId);
    if (regionId != null) setOpen((prev) => new Set(prev).add(regionId));
    focusAfter.current = areaId;
    onChange(value.filter((id) => id !== areaId));
  }

  const chips = value.filter((id) => byId.has(id));

  return (
    <div className="flex flex-col gap-3" data-testid={testIdPrefix}>
      <div>
        <p
          className="font-mono text-xs text-plex-text"
          aria-live="polite"
          data-testid={`${testIdPrefix}-count`}
        >
          {read ? `${count} chosen` : `${count} of ${limit} chosen`}
        </p>
        <ul className="flex flex-wrap gap-2 mt-2" aria-label="Chosen sub-regions" data-testid={`${testIdPrefix}-chips`}>
          {chips.length === 0 && (
            <li className="text-xs text-plex-text-muted py-1">None chosen yet</li>
          )}
          {chips.map((id) => (
            <li
              key={id}
              className={`inline-flex items-center gap-1 rounded-full border border-plex-border bg-plex-surface-light py-1 text-xs text-plex-text ${
                read || locked.has(id) ? 'px-3' : 'pl-3 pr-1'
              }`}
              data-testid={`${testIdPrefix}-chip-${id}`}
            >
              <span>{byId.get(id).name}</span>
              {!read && !locked.has(id) && (
                <button
                  type="button"
                  className="inline-flex items-center justify-center w-6 h-6 pointer-coarse:w-8 pointer-coarse:h-8 rounded-full text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
                  aria-label={`Remove ${byId.get(id).name}`}
                  onClick={() => removeFromChip(id)}
                >
                  <span aria-hidden="true">&times;</span>
                </button>
              )}
            </li>
          ))}
        </ul>
        {addsBlocked && (
          <p
            id={limitId}
            className="text-xs text-plex-text-secondary mt-2"
            data-testid={`${testIdPrefix}-limit-line`}
          >
            {hintText}
          </p>
        )}
      </div>

      {options.map((region) => {
        const regionOpen = open.has(region.regionId);
        const k = region.subRegions.filter((s) => chosen.has(s.areaId)).length;
        const panelId = `${baseId}-region-${region.regionId}`;
        return (
          <div
            key={region.regionId}
            className="rounded-lg border border-plex-border overflow-hidden"
            data-testid={`${testIdPrefix}-region-${region.regionId}`}
          >
            <button
              type="button"
              className="flex w-full min-h-12 items-center justify-between gap-3 px-3 bg-plex-surface-light text-left text-sm font-medium text-plex-text focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold"
              aria-expanded={regionOpen}
              aria-controls={panelId}
              onClick={() => toggleRegion(region.regionId)}
            >
              <span>{region.regionName}</span>
              <span className="flex items-center gap-2 font-mono text-xs">
                {k > 0 ? (
                  <span className="text-plex-gold">{k} chosen</span>
                ) : (
                  <span className="text-plex-text-muted">{region.subRegions.length} sub-regions</span>
                )}
                <svg
                  width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                  strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
                  className={`text-plex-text-secondary ${regionOpen ? 'rotate-180' : ''}`}
                >
                  <path d="m6 9 6 6 6-6" />
                </svg>
              </span>
            </button>
            {regionOpen && (
              <ul id={panelId}>
                {region.subRegions.map((option) => {
                  const isChosen = chosen.has(option.areaId);
                  const isLocked = isChosen && locked.has(option.areaId);
                  const blocked = read || isLocked || (!isChosen && addsBlocked);
                  const describedBy = !read && !isChosen && addsBlocked ? limitId : undefined;
                  return (
                    <li key={option.areaId} className="border-t border-plex-border">
                      <label
                        className={`flex min-h-14 items-center gap-3 px-3 py-2 ${
                          blocked ? 'cursor-default' : 'cursor-pointer'
                        }`}
                      >
                        <input
                          ref={(el) => {
                            if (el) rowRefs.current.set(option.areaId, el); else rowRefs.current.delete(option.areaId);
                          }}
                          type="checkbox"
                          className="peer sr-only"
                          checked={isChosen}
                          aria-disabled={blocked ? 'true' : undefined}
                          aria-describedby={describedBy}
                          onChange={() => toggle(option.areaId)}
                          data-testid={`${testIdPrefix}-row-${option.areaId}`}
                        />
                        <span
                          aria-hidden="true"
                          className={`flex w-[18px] h-[18px] shrink-0 items-center justify-center rounded-[4px] border peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-plex-gold ${
                            isChosen
                              ? `${read || isLocked ? 'bg-plex-text-secondary border-plex-text-secondary' : 'bg-plex-text border-plex-text'} text-plex-bg`
                              : `border-plex-border-light ${blocked ? 'border-dashed' : ''}`
                          }`}
                        >
                          {isChosen && (
                            <svg
                              width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                              strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round"
                            >
                              <path d="m5 12 5 5L20 7" />
                            </svg>
                          )}
                        </span>
                        <span className="flex flex-col min-w-0">
                          <span
                            className={`text-sm ${
                              !isChosen && blocked ? 'text-plex-text-muted' : 'text-plex-text'
                            }`}
                          >
                            {option.name}
                          </span>
                          <span className="text-xs text-plex-text-secondary">{rowMeta(option)}</span>
                        </span>
                      </label>
                      {nudge?.areaId === option.areaId && (
                        <p
                          role="status"
                          className="px-3 pb-2 pl-[42px] text-xs text-plex-gold"
                          data-testid={`${testIdPrefix}-nudge`}
                        >
                          {nudge.text}
                        </p>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        );
      })}
    </div>
  );
}

SubRegionPicker.propTypes = {
  options: PropTypes.arrayOf(PropTypes.shape({
    regionId: PropTypes.number.isRequired,
    regionName: PropTypes.string.isRequired,
    subRegions: PropTypes.arrayOf(PropTypes.shape({
      areaId: PropTypes.number.isRequired,
      name: PropTypes.string.isRequired,
      locationCount: PropTypes.number.isRequired,
      exampleLocations: PropTypes.arrayOf(PropTypes.string),
    })).isRequired,
  })).isRequired,
  value: PropTypes.arrayOf(PropTypes.number).isRequired,
  limit: PropTypes.number.isRequired,
  onChange: PropTypes.func,
  mode: PropTypes.oneOf(['edit', 'read']),
  lockedIds: PropTypes.arrayOf(PropTypes.number),
  lockedReason: PropTypes.string,
  removalsOnly: PropTypes.bool,
  testIdPrefix: PropTypes.string,
};
