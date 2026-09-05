import React, {
  useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore,
} from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import {
  buildSearchGroups, firstSelectable, flattenRows, nextSelectable,
} from '../utils/planSearch.js';

/**
 * The tick line's box in viewport coordinates, kept fresh — or null when it cannot be measured.
 *
 * <h2>Why the panel is placed rather than centred</h2>
 *
 * <p>The design replaces the masthead's tick line with the search field and hangs the dropdown off
 * the masthead's bottom edge. A14 kept this inside the shared {@code Modal} (which already solves
 * focus, Escape and scroll) and asked for the anchored LOOK — so the panel is positioned exactly
 * over the tick line and covers it. Every {@code Modal} is Tailwind's {@code z-50} and the masthead
 * is a static box in normal flow, so covering is what the stacking order already does; nothing has
 * to be hidden underneath, which is what keeps this to a position rather than a second piece of
 * state in the shell.
 *
 * <p>Measured through {@code useSyncExternalStore} rather than into state from an effect: the
 * viewport is an external store, and reading it during render is what makes the panel's first paint
 * the anchored one. Measured rather than derived from a published height: what is wanted is the tick
 * line's top in viewport coordinates, which differs from the masthead's height by the page's own
 * scroll offset and by the band's padding. Re-read on scroll and resize because a page scrolled
 * behind an open dialog carries the tick line with it and a panel that did not follow would float
 * over the matrix. ⚠️ It carries it the WHOLE way now, off the top of the viewport included: this
 * doc used to say the sticky masthead's edge moved "exactly once, from its resting position to the
 * top of the viewport", which was never true — that stick was trapped in a containing block ~46px
 * taller than the band (`index.css`'s `.wf-mast`), and the rule has since gone. Nothing locks page
 * scroll behind an open {@code Modal}, so an open panel can still be scrolled out of view; that is
 * a pre-existing edge this correction records rather than introduces.
 *
 * <p><b>Null is the honest answer and it has a real caller.</b> jsdom measures every box as zero,
 * and so does a first paint before layout; a zero-width panel is not a dropdown. The component
 * falls back to the centred box the dialog shipped as, which is also what a build with no tick line
 * on screen should get.
 *
 * @returns {?{top: number, left: number, width: number}} the box, or null
 */
function useTickLineBox() {
  // The last box handed out. `useSyncExternalStore` compares snapshots with `Object.is`, so a fresh
  // object per read would loop forever; this returns the SAME object until a number actually moves.
  const cache = useRef(null);

  const subscribe = useCallback((notify) => {
    window.addEventListener('resize', notify);
    // `capture`, because the page's own scroller may not be `window` — a scroll event from a nested
    // container bubbles to the document in the capture phase whatever its target.
    window.addEventListener('scroll', notify, { passive: true, capture: true });
    return () => {
      window.removeEventListener('resize', notify);
      window.removeEventListener('scroll', notify, { capture: true });
    };
  }, []);

  const getSnapshot = useCallback(() => {
    // ⚠️ The CLASS, not the testid. A `data-testid` is a test contract; making it the only coupling
    // between this dialog and the row it anchors to means a rename in a later sweep leaves the box
    // working, centred, with nothing failing — `null` is a legitimate return here, so the degrade
    // path would swallow the breakage. `.wf-tick` is a layout hook the responsive suite already
    // pins, which is the nearest thing this arm has to a structural selector.
    const node = document.querySelector('.wf-tick');
    const rect = node?.getBoundingClientRect?.();
    if (!rect || rect.width <= 0) { cache.current = null; return null; }
    const next = {
      top: Math.round(rect.top), left: Math.round(rect.left), width: Math.round(rect.width),
    };
    const prev = cache.current;
    if (prev && prev.top === next.top && prev.left === next.left && prev.width === next.width) {
      return prev;
    }
    cache.current = next;
    return next;
  }, []);

  // A store rather than an effect, and not only to satisfy a lint rule: the snapshot is read during
  // render, so the panel's FIRST paint is already anchored. Measuring in an effect would show it
  // centred for one frame and then jump, which is the flash this whole placement exists to avoid.
  return useSyncExternalStore(subscribe, getSnapshot, () => null);
}

/**
 * A label with the matched span in a {@code <mark>}, or the plain label when there is no range.
 *
 * <p>The range is an index pair into the ORIGINAL string, computed by {@code planSearch.matchRange}
 * against a fold that changes no character counts — so the slice below can be taken directly and
 * the mark can never land off by the width of an apostrophe. A row matched only by the wide fold
 * (an ampersand, a "saint", a query with the spaces left out) carries no range and renders plain,
 * which is the honest outcome: the row is still the answer, and a mark in the wrong place is worse
 * than none.
 */
function Highlight({ text, range }) {
  if (!range) return text;
  const [start, end] = range;
  return (
    <>
      {text.slice(0, start)}
      <mark data-testid="plan-search-mark" className="wf-search-mark">{text.slice(start, end)}</mark>
      {text.slice(end)}
    </>
  );
}

Highlight.propTypes = {
  text: PropTypes.string.isRequired,
  range: PropTypes.arrayOf(PropTypes.number),
};

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
 * @param {Function} [props.onPickLocation] opens that location's four-day sheet (P8); it was a
 *        jump straight to the map until §9.9 was resolved. The component itself is agnostic — it
 *        hands back the spot and lets the shell decide what a location result means.
 * @param {?Map}     [props.reachById] the reach map the page plans from, for a location's drive
 * @param {?object}  [props.scoreIndex] id-first ratings, for a location's best window
 * @param {?Array}   [props.scopeRegionNames] the plan's regions, for the "outside" clause
 * @param {?object}  [props.origin]    the origin descriptor, for that clause's wording
 */
export default function PlanSearch({
  windows, regions, locations, originId = null, initialQuery = '',
  onClose, onPickWindow, onPickRegion, onPickLocation,
  reachById = null, scoreIndex = null, scopeRegionNames = null, origin = null,
}) {
  // Seeded once. The caller keys this component on the seed, so a new seed mounts a fresh box
  // rather than overwriting what the reader has since typed into an open one.
  const [query, setQuery] = useState(initialQuery);
  const inputRef = useRef(null);

  const groups = useMemo(
    () => buildSearchGroups(query, {
      windows, regions, locations, originId, reachById, scoreIndex, scopeRegionNames, origin,
    }),
    [query, windows, regions, locations, originId, reachById, scoreIndex,
      scopeRegionNames, origin],
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
  const anchor = useTickLineBox();

  return (
    <Modal label="Search days, regions and places" onClose={onClose} bare closeOnEscape
      data-testid="plan-search">
      <div
        className={`wf-search-panel${anchor ? ' wf-search-anchored' : ''}`}
        data-testid="plan-search-panel"
        role="presentation"
        data-anchored={anchor ? 'true' : 'false'}
        // Inline because these are three measurements, not three design values — the stylesheet
        // owns everything about the panel except WHERE the tick line it replaces happens to be.
        // `absolute` inside `Modal`'s `fixed inset-0` overlay, so the numbers are viewport
        // coordinates either way and the flex centring above is simply not consulted.
        // The three controls the panel covers are taken out of the tab order by the shell while
        // this is open — see `MastheadTickLine`'s `searchOpen`. Reported here because the covering
        // is this component's doing.
        style={anchor ? {
          top: `${anchor.top}px`,
          left: `${anchor.left}px`,
          width: `${anchor.width}px`,
          // From the panel's own top rather than a viewport fraction: the anchor moves as the
          // sticky masthead settles, and a `calc` in the stylesheet could not see where it landed.
          // ⚠️ ONE arithmetic term, summed here rather than left as `- ${top}px - 16px`: jsdom's
          // CSS serializer re-orders a two-subtraction calc into `- 16px + 96px`, which is a
          // different number — so a multi-term form is untestable in this suite and only looks
          // fine. 16 is `Modal`'s own overlay padding (`p-4`), kept off the bottom edge.
          maxHeight: `calc(100dvh - ${anchor.top + 16}px)`,
        } : undefined}
      >
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
            aria-label="Search days, regions and places"
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
                    {/* Decorative: the group heading above already names the kind, and a screen
                        reader hearing "white diamond" learns nothing. */}
                    <span aria-hidden="true" className="wf-search-glyph-col">{row.glyph}</span>
                    <span className="wf-search-text">
                      <span className="wf-search-label">
                        <Highlight text={row.label} range={row.marks} />
                      </span>
                      {/* Clauses rather than one joined string, and the reason is truncation: the
                          sub-line's LAST clause is the one that changes what the row means
                          ("outside your 3h area"), and a single `nowrap` + ellipsis line is exactly
                          where it dies. Wrapped, each clause survives; toned, the one that matters
                          is findable. A `reason` (a row that cannot be chosen) replaces the whole
                          line — it is about the row rather than about the place. */}
                      <span data-testid="plan-search-sub" className="wf-search-sub">
                        {row.reason
                          ? row.reason
                          : (row.subParts || []).map((part, i) => (
                            <span
                              key={part.text}
                              className={part.tone ? `wf-search-cl on-${part.tone}` : 'wf-search-cl'}
                            >
                              {i > 0 && <span aria-hidden="true" className="wf-search-dot">·</span>}
                              {part.text}
                            </span>
                          ))}
                      </span>
                    </span>
                    {/* Both columns are omitted rather than blanked when there is nothing to put in
                        them (Rule 6). The figure's caption names the window a location's star came
                        from, so the star is never a bare number with no occasion attached. */}
                    {row.figure && (
                      <span className="wf-search-fig">
                        <b className="wf-search-fig-v">{row.figure.value}</b>
                        <span className="wf-search-fig-c">{row.figure.caption}</span>
                      </span>
                    )}
                    {/* `aria-hidden`: it names what Enter does, which the footer already says once
                        for the whole list — repeated on every row it would be 24 announcements of
                        one keyboard rule. It is also hidden on a phone, where it does not fit. */}
                    {row.action && (
                      <span aria-hidden="true" className="wf-search-act">{row.action}</span>
                    )}
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
  reachById: PropTypes.instanceOf(Map),
  scoreIndex: PropTypes.shape({ byId: PropTypes.instanceOf(Map), byName: PropTypes.instanceOf(Map) }),
  scopeRegionNames: PropTypes.arrayOf(PropTypes.string),
  origin: PropTypes.shape({ name: PropTypes.string }),
};
