import React, { useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import {
  buildSearchGroups, firstSelectable, flattenRows, nextSelectable,
} from '../utils/planSearch.js';

/**
 * One box over three kinds of thing — windows, regions to plan from, and locations (plan §4.8).
 *
 * <h2>A dialog, and the same one on both viewports</h2>
 *
 * <p>{@code Modal} already moves focus in and hands it back, and opts into Escape per caller —
 * this one opts in, because the box holds nothing a reader would lose. The design's phone variant
 * is a bottom sheet; this ships the dialog at both widths and lets the stylesheet pin it to the
 * top of the viewport on a phone, because the one thing a search box must do on a touch device is
 * stay above the keyboard, and a bottom sheet is precisely where the keyboard appears.
 *
 * <h2>Keyboard</h2>
 *
 * <p>{@code ↑↓} move, {@code enter} opens, {@code esc} closes — the design's own three. The cursor
 * runs over one flat list across group boundaries and skips rows that cannot be chosen (a region
 * with no base town, or the one you are already planning from), so it can never rest on a row that
 * does nothing. The listbox pattern is spelled out on the input rather than implied: the box owns
 * {@code role="combobox"}, {@code aria-expanded}, {@code aria-controls} and
 * {@code aria-activedescendant}, so the active row is announced without focus ever leaving the
 * field — which is what lets typing and moving happen in the same gesture.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>No "Recent locations" resting group (§9.11): no recency store exists and none is planned, so
 * the resting list is windows only — every row of which is already on screen behind the dialog,
 * making the list a shortcut rather than a catalogue. Regions are absent at rest on the design's
 * own reasoning (the map is the region picker) and match the moment they are typed. And a region
 * with no base is <b>shown and disabled</b> with the reason on it, rather than hidden: hiding it
 * would make the search look broken for a region the reader can see on the map.
 *
 * @param {object}   props
 * @param {Array}    props.windows      heat strip descriptors, the resting list
 * @param {Array}    props.regions      region records from {@code GET /api/regions}
 * @param {Array}    props.locations    heat spots
 * @param {*}        [props.originId]   the current origin's region id
 * @param {string}   [props.initialQuery] the box's opening text — the beyond line pre-fills it
 * @param {Function} props.onClose      dismisses the dialog
 * @param {Function} props.onPickWindow opens a window's card, by key
 * @param {Function} props.onPickRegion moves the origin to a region record
 * @param {Function} [props.onPickLocation] shows a location on the map
 */
export default function PlanSearch({
  windows, regions, locations, originId = null, initialQuery = '',
  onClose, onPickWindow, onPickRegion, onPickLocation,
}) {
  // Seeded once. The caller keys this component on the seed, so a new seed mounts a fresh box
  // rather than overwriting what the reader has since typed into an open one.
  const [query, setQuery] = useState(initialQuery);
  const inputRef = useRef(null);

  const groups = useMemo(
    () => buildSearchGroups(query, {
      windows, regions, locations, originId,
    }),
    [query, windows, regions, locations, originId],
  );
  const rows = useMemo(() => flattenRows(groups), [groups]);

  const [selected, setSelected] = useState(() => firstSelectable(rows));
  /**
   * Re-anchored whenever the QUERY changes, never carried across it: an index into a list that has
   * just been re-filtered points at a different row, so Enter would open something the reader never
   * looked at.
   *
   * <p>⚠️ Keyed on the query, <b>not</b> on {@code rows} identity. {@code rows} derives from the
   * {@code windows}/{@code regions}/{@code locations} props, which are provider memos that rebuild
   * when the ratings fetch resolves, when travel days land, and on every ten-minute poll — so an
   * identity check snapped the cursor back to the top mid-keystroke, and Enter then opened a
   * different row from the one highlighted a frame earlier.
   *
   * <p>The clamp is the other half: an upstream rebuild CAN shorten the list without the query
   * changing, and an index past the end would leave {@code aria-activedescendant} pointing at
   * nothing. It only ever moves the cursor when the row it named has gone.
   *
   * <p>Adjusted DURING the render rather than in an effect — React's own "adjusting state when a
   * prop changes" pattern. In an effect it would be a cascading render, and worse, there would be
   * one commit in which the cursor pointed at a row belonging to the previous query.
   */
  const [querySeen, setQuerySeen] = useState(query);
  if (querySeen !== query) {
    setQuerySeen(query);
    setSelected(firstSelectable(rows));
  } else if (selected >= rows.length || rows[selected]?.disabled) {
    const next = firstSelectable(rows);
    if (next !== selected) setSelected(next);
  }

  // `Modal` puts focus on the dialog root; the field is where a reader has to be to type. Deferred
  // to an effect rather than `autoFocus` so it runs after the root's own focus call.
  useEffect(() => { inputRef.current?.focus?.(); }, []);

  const choose = (row) => {
    if (!row || row.disabled) return;
    if (row.kind === 'window') onPickWindow?.(row.windowKey);
    else if (row.kind === 'region') onPickRegion?.(row.region);
    else if (row.kind === 'location') onPickLocation?.(row.spot);
    onClose();
  };

  const onKeyDown = (event) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setSelected((current) => nextSelectable(rows, current, 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setSelected((current) => nextSelectable(rows, current, -1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      choose(rows[selected]);
    }
  };

  const activeId = rows[selected] ? `plan-search-row-${rows[selected].key}` : undefined;

  return (
    <Modal label="Search windows, regions and locations" onClose={onClose} bare closeOnEscape
      data-testid="plan-search">
      <div className="wf-search-panel" role="presentation">
        <div className="wf-search-field">
          <span aria-hidden="true" className="wf-search-glyph">⌕</span>
          <input
            ref={inputRef}
            type="text"
            role="combobox"
            aria-expanded={rows.length > 0}
            aria-controls="plan-search-list"
            aria-activedescendant={activeId}
            aria-autocomplete="list"
            aria-label="Search windows, regions and locations"
            data-testid="plan-search-input"
            className="wf-search-input"
            placeholder="Search a day, a region or a place…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onKeyDown}
          />
        </div>

        <div id="plan-search-list" role="listbox" aria-label="Search results"
          className="wf-search-list" data-testid="plan-search-list">
          {groups.map((group) => (
            <div key={group.id} role="group" aria-label={group.title}>
              <p className="wf-search-head" data-testid="plan-search-group">{group.title}</p>
              {group.rows.map((row) => {
                const index = rows.indexOf(row);
                return (
                  <button
                    key={row.key}
                    id={`plan-search-row-${row.key}`}
                    type="button"
                    role="option"
                    // Out of the tab order, because the combobox above drives this list through
                    // `aria-activedescendant` and focus never leaves the field. Natively tabbable
                    // options let Tab walk 24 rows while the field still claimed a different one
                    // was active — and past the last one, out through a non-trapping backdrop into
                    // a page an `aria-modal` dialog has told assistive tech is inert.
                    tabIndex={-1}
                    aria-selected={index === selected}
                    aria-disabled={row.disabled ? 'true' : undefined}
                    data-testid="plan-search-row"
                    data-kind={row.kind}
                    data-active={index === selected ? 'true' : 'false'}
                    // `aria-disabled` rather than `disabled`: the row still has to be readable and
                    // its reason still has to be announced, and a `disabled` button is skipped by
                    // some screen readers entirely. `choose` refuses it, so the click is inert.
                    className={`wf-search-row${index === selected ? ' on' : ''}${row.disabled ? ' off' : ''}`}
                    onMouseEnter={() => { if (!row.disabled) setSelected(index); }}
                    onClick={() => choose(row)}
                  >
                    <span className="wf-search-label">{row.label}</span>
                    <span className="wf-search-sub">{row.reason || row.sub}</span>
                  </button>
                );
              })}
            </div>
          ))}

        </div>

        {/* OUTSIDE the listbox: a paragraph is not a permitted child of `role="listbox"`, whose
            owned elements must be options or groups. */}
        {rows.length === 0 && (
          <p className="wf-search-empty" data-testid="plan-search-empty">
            {`Nothing matches “${query.trim()}”.`}
          </p>
        )}

        {/* ALWAYS mounted, so the count changes inside a live region rather than the region itself
            appearing — the idiom `WindowSpotSheet` already records ("role=\"status\" on the ALWAYS
            mounted count, not on the conditional empty paragraph"). Without it a screen-reader
            reader filtering 24 rows down to none hears nothing at all: the visible message is a
            bare paragraph, and `aria-expanded` flipping to false says the popup collapsed rather
            than that it holds an explanation. */}
        <p role="status" className="sr-only" data-testid="plan-search-status">
          {rows.length === 0
            ? `No results for ${query.trim()}`
            : `${rows.length} result${rows.length === 1 ? '' : 's'}`}
        </p>

        <p className="wf-search-foot" data-testid="plan-search-foot">
          {/* The glyphs are decorative and the words beside them carry the meaning — but `esc` is a
              WORD, not a glyph, so hiding it would remove information with no visual-only
              equivalent. The spoken form spells all three out; the drawn form keeps the glyphs. */}
          <span aria-hidden="true">
            ↑↓ move · ↵ open · esc close
          </span>
          <span className="sr-only">
            Up and down arrows move, Enter opens, Escape closes
          </span>
        </p>
      </div>
    </Modal>
  );
}

PlanSearch.propTypes = {
  windows: PropTypes.arrayOf(PropTypes.object),
  regions: PropTypes.arrayOf(PropTypes.object),
  locations: PropTypes.arrayOf(PropTypes.object),
  originId: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
  initialQuery: PropTypes.string,
  onClose: PropTypes.func.isRequired,
  onPickWindow: PropTypes.func,
  onPickRegion: PropTypes.func,
  onPickLocation: PropTypes.func,
};
