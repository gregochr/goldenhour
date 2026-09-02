import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { rampHex } from '../../utils/scoreRamp.js';
import { calDow } from '../../utils/windowFirstStrip.js';
import { badgeChannel } from '../../utils/windowFirstCards.js';

/**
 * The Map tab's single chronological window control — map-tab-v2-plan.md §3 P6,
 * docs/design/map-tab-v2/README.md "1. The window control".
 *
 * <p>One pill (kind chip · label · time · caret), flanked by `‹ ›` steppers, opening a day-grouped
 * dropdown that states each event's best achievable score — "choosing a window is then an informed
 * act rather than a guess" (README). This is the whole reason the menu computes a figure at all:
 * it is not decoration, it is what makes the choice informed.
 *
 * <p>Pure presentation over `utils/mapEvents.js`'s built EV list — this component owns no fetching
 * and derives no score; every row it draws is exactly what it was handed.
 *
 * <h2>Keyboard is scoped to this control, not the document</h2>
 *
 * <p>`←`/`→` step and `Esc` closes the dropdown, via a local `onKeyDown` on this component's own
 * wrapper — bubbling from whichever descendant has focus, never a `document` listener. The Map
 * pane is never unmounted (only hidden), so a global key listener would keep firing for a control
 * that is not on screen; a listener scoped to this subtree cannot.
 *
 * <p>The dropdown's own outside-click dismissal (a `document.mousedown` listener, matching this
 * app's existing disclosure idiom in `HealthIndicator.jsx`/`InfoTip.jsx`) is a different case and
 * does not carry the same risk: it can only ever close state that is not visible when it fires
 * from another tab, never act on anything.
 *
 * <h2>Optionally controlled, for menu exclusivity (map-tab-v2-plan.md §3 P7)</h2>
 *
 * <p>P7 adds a second popover to the same map pane (`FiltersPopover`), and "opening one closes the
 * others" needs one caller-owned source of truth. Passing both {@code open} and
 * {@code onOpenChange} puts this component in CONTROLLED mode — the caller's boolean becomes the
 * only truth and every internal open/close (pill click, row selection, a stepper closing the menu,
 * outside click, `Escape`) is reported upward instead of applied to local state. Omitting both
 * keeps the original uncontrolled behaviour byte-for-byte, which is what every test written before
 * P7 already exercises and what the Plan-tab overlay would fall back to if it ever mounted this
 * control (it does not, today).
 */
export default function WindowControl({
  events, activeIndex, onSelect, open: openProp, onOpenChange = null,
}) {
  const isControlled = openProp !== undefined;
  const [openState, setOpenState] = useState(false);
  const open = isControlled ? openProp : openState;
  // `useCallback`, closing over the CURRENT `open` (controlled or not) so a functional update
  // (`setOpen(v => !v)`, the pill's own toggle) always flips the value actually on screen rather
  // than a possibly-stale internal `openState` the controlled caller has since overridden.
  // Recreated whenever `open` changes, which is exactly when the outside-click effect below needs
  // to re-subscribe anyway — listing it as a dependency costs nothing extra.
  const setOpen = useCallback((next) => {
    const value = typeof next === 'function' ? next(open) : next;
    if (!isControlled) setOpenState(value);
    onOpenChange?.(value);
  }, [open, isControlled, onOpenChange]);
  const rootRef = useRef(null);

  const active = activeIndex >= 0 && activeIndex < events.length ? events[activeIndex] : null;
  // Stepping from "nowhere" is ambiguous — the map is on a date/event the list has no row for.
  // The retired `wf-map-window` `<select>` this control absorbed put it best in its own comment:
  // "a real state and not an error", since `GET /api/forecast` reaches further than the
  // briefing's rendered horizon. Both ends are disabled rather than guessing which direction
  // "next" means.
  const atStart = !active || activeIndex <= 0;
  const atEnd = !active || activeIndex >= events.length - 1;

  useEffect(() => {
    if (!open) return undefined;
    function onDocMouseDown(e) {
      if (rootRef.current && !rootRef.current.contains(e.target)) setOpen(false);
    }
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [open, setOpen]);

  /** Grouped by date, in the list's own order — the list is already chronological. */
  const groups = useMemo(() => {
    const out = [];
    let lastDate = null;
    for (const row of events) {
      if (row.date !== lastDate) {
        out.push({ date: row.date, rows: [] });
        lastDate = row.date;
      }
      out[out.length - 1].rows.push(row);
    }
    return out;
  }, [events]);

  function selectRow(row) {
    setOpen(false);
    onSelect(row);
  }

  function step(delta) {
    const next = activeIndex + delta;
    if (next < 0 || next >= events.length) return;
    setOpen(false);
    onSelect(events[next]);
  }

  function onKeyDown(e) {
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      step(-1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      step(1);
    } else if (e.key === 'Escape' && open) {
      e.preventDefault();
      setOpen(false);
    }
  }

  // Genuinely nothing to show — no briefing, no forecast domain, nothing fetched yet. Distinct
  // from `!active` below: an EMPTY list has no dropdown to offer either, where a non-empty list
  // with no matching row still has somewhere useful for the pill to send the reader.
  if (events.length === 0) return null;

  return (
    // The div itself is not interactive — every real affordance inside it (the pill, the two
    // steppers, each dropdown row) is a native `<button>`. `onKeyDown` here exists purely to
    // catch `ArrowLeft`/`ArrowRight`/`Escape` bubbling up from whichever of those has focus, which
    // is what "scoped to the map pane, never document-global" (map-tab-v2-plan.md §3 P6) means in
    // practice — a `document` listener would keep firing for a control that is not on screen,
    // since the Map pane is never unmounted (only hidden).
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={rootRef}
      data-testid="wf-win-control"
      className="wf-win-control"
      onKeyDown={onKeyDown}
    >
      <button
        type="button"
        data-testid="wf-win-prev"
        aria-label="Previous event"
        className="wf-win-step"
        disabled={atStart}
        onClick={() => step(-1)}
      >
        &#x2039;
      </button>

      <button
        type="button"
        data-testid="wf-win-pill"
        className="wf-win-pill"
        aria-haspopup="listbox"
        aria-expanded={open}
        title={active?.rosterNote || undefined}
        onClick={() => setOpen((v) => !v)}
      >
        {active ? (
          <>
            <KindChip row={active} />
            <span className="wf-win-label">{active.label}</span>
            {active.time && <span className="wf-win-time">{active.time}</span>}
          </>
        ) : (
          // The map is on a date/event this list has no row for — an ordinary state (the map's
          // own domain can reach further than the briefing, or further than any night on record),
          // not an error. Still opens the dropdown, exactly like the retired `<select>`'s own
          // `No forecast window` option did, so the reader is never stuck with no way back.
          <span className="wf-win-label" data-testid="wf-win-no-match">No forecast window</span>
        )}
        <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
      </button>

      <button
        type="button"
        data-testid="wf-win-next"
        aria-label="Next event"
        className="wf-win-step"
        disabled={atEnd}
        onClick={() => step(1)}
      >
        &#x203A;
      </button>

      {open && (
        <div data-testid="wf-win-menu" className="wf-win-menu" role="listbox" aria-label="Choose an event">
          {groups.map((group) => (
            <div key={group.date}>
              <div data-testid="wf-win-day" className="wf-win-day">
                {dayHeading(group.date)}
              </div>
              {group.rows.map((row) => (
                <WindowRow
                  key={row.id}
                  row={row}
                  active={row.id === active?.id}
                  onSelect={() => selectRow(row)}
                />
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

WindowControl.propTypes = {
  events: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string.isRequired,
    kind: PropTypes.string.isRequired,
    eventType: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    time: PropTypes.string,
    bestRating: PropTypes.number,
    scored: PropTypes.bool,
    badges: PropTypes.array,
    rosterNote: PropTypes.string,
  })).isRequired,
  /** Index into `events` of the currently-shown row; -1 when nothing matches yet. */
  activeIndex: PropTypes.number.isRequired,
  /** Called with the chosen row — never an index, so the caller never has to re-look it up. */
  onSelect: PropTypes.func.isRequired,
  /**
   * Controlled dropdown state (map-tab-v2-plan.md §3 P7's menu exclusivity). Omit both this and
   * `onOpenChange` for the original uncontrolled behaviour.
   */
  open: PropTypes.bool,
  /** Fired on every open/close this component would otherwise have applied to local state. */
  onOpenChange: PropTypes.func,
};

/** "SAT 12" — the dropdown's day heading, from the app's own weekday abbreviation. */
function dayHeading(dateStr) {
  const dayNum = Number(dateStr.split('-')[2]);
  return `${calDow(dateStr)} ${dayNum}`.toUpperCase();
}

/** am / pm / night — the CSS colour-class key. Astro and Aurora share one colour (README table). */
function kindClass(row) {
  if (row.kind === 'solar') return row.eventType === 'SUNRISE' ? 'am' : 'pm';
  return 'night';
}

const KIND_TEXT = { SUNRISE: 'Sunrise', SUNSET: 'Sunset', ASTRO: 'Astro', AURORA: 'Aurora' };

function KindChip({ row }) {
  // Reuses `.wf-hc-sun` — the day rail's own kind-chip class (matrix-axis plan D14) — rather than
  // minting a second chip vocabulary. `.am`/`.pm` already exist there; `.night` is this phase's one
  // addition (index.css), sharing the same `color-mix` idiom against the new astro tokens.
  return (
    <span className={`wf-hc-sun ${kindClass(row)}`}>
      {KIND_TEXT[row.eventType] || row.eventType}
    </span>
  );
}

KindChip.propTypes = {
  row: PropTypes.shape({
    kind: PropTypes.string,
    eventType: PropTypes.string,
  }).isRequired,
};

/** A handful of topic channels get a glyph in the dropdown; anything else is a plain dot. */
const CHANNEL_ICON = {
  tide: '\u{1F30A}',
  aurora: '\u{1F30C}',
  nlc: '✨',
  snow: '❄️',
  eclipse: '●',
  plain: '•',
};

/** One dropdown row — kind chip · label+time · `N★ best` with swatch · topic icons. */
function WindowRow({ row, active, onSelect }) {
  const icons = useMemo(() => {
    const seen = new Set();
    const out = [];
    for (const badge of row.badges || []) {
      const channel = badgeChannel(badge?.type);
      if (seen.has(channel)) continue;
      seen.add(channel);
      out.push(CHANNEL_ICON[channel] || CHANNEL_ICON.plain);
    }
    return out;
  }, [row.badges]);

  return (
    <button
      type="button"
      data-testid="wf-win-row"
      data-ev-id={row.id}
      role="option"
      aria-selected={active}
      className={`wf-win-row${active ? ' on' : ''}`}
      // The bortle-only-roster caveat (README OPEN 1: "if the real astro score only exists for
      // dark-sky locations, the event row should say so") — a native tooltip rather than a second
      // line, since the row's four-column grid has no spare space for one.
      title={row.rosterNote || undefined}
      onClick={onSelect}
    >
      <KindChip row={row} />
      <span className="wf-win-row-main">
        <b>{row.label}</b>
        {row.time && <span className="wf-win-row-time">{row.time}</span>}
      </span>
      <span className="wf-win-row-score">
        {row.scored ? (
          <>
            <i aria-hidden="true" style={{ background: rampHex(row.bestRating) }} />
            {row.bestRating}&#9733; best
          </>
        ) : (
          <span className="wf-win-row-unscored">&mdash;</span>
        )}
      </span>
      <span className="wf-win-row-topics" aria-hidden="true">
        {icons.join('')}
      </span>
    </button>
  );
}

WindowRow.propTypes = {
  row: PropTypes.shape({
    id: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    time: PropTypes.string,
    bestRating: PropTypes.number,
    scored: PropTypes.bool,
    badges: PropTypes.array,
    rosterNote: PropTypes.string,
  }).isRequired,
  active: PropTypes.bool.isRequired,
  onSelect: PropTypes.func.isRequired,
};
