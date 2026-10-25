import React, {
  useEffect, useId, useLayoutEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import BoltIcon from './BoltIcon.jsx';
import { barTarget, columnWord, labelTarget } from '../../utils/mapTopics.js';

/** No locked types: every row is an ordinary topic door (PRO, Admin, and every bare render). */
const NO_LOCKED_TYPES = [];
/** Appended to a locked door's name when it has nothing to open (the phone, no coastal spot in view). */
const DOOR_DISABLED_SUFFIX = ', no coastal spot in view';

/** The per-viewer preference: '1' (open), '0' (closed) or absent (the frame height decides). */
export const TOPIC_STRIP_OPEN_KEY = 'mapTopicStripOpen';

/** With no stored choice the strip starts open only on a map frame at least this tall (CSS px). */
export const OPEN_BY_DEFAULT_MIN_FRAME_PX = 720;

/** The phone sheet's own preference (M4): a rotate across 640px must not carry one variant's choice into the other. */
export const TOPIC_STRIP_OPEN_SHEET_KEY = 'mapTopicStripOpenSheet';

/** The stored preference as true / false, or null when there is none (or storage is unavailable). */
function readOpenPreference(key) {
  try {
    const v = localStorage.getItem(key);
    if (v === '1') return true;
    if (v === '0') return false;
    return null;
  } catch {
    return null;
  }
}

function writeOpenPreference(key, open) {
  try {
    localStorage.setItem(key, open ? '1' : '0');
  } catch {
    // Storage unavailable: the in-memory state still holds the choice for this session.
  }
}

/** The sheet variant's row heights (px): a 32px row, and 44px for the Big seas row while a tick is drawn. */
const SHEET_ROW_PX = 32;
const SHEET_SEAS_ROW_PX = 44;

/** "Friday sunrise" or "Friday sunrise to Saturday sunset" for a bar's columns. */
function spanWords(grid, bar) {
  return bar.start === bar.end
    ? columnWord(grid, bar.start)
    : `${columnWord(grid, bar.start)} to ${columnWord(grid, bar.end)}`;
}

/**
 * The Map tab's four-day hot-topics strip (hot-topics-by-day-plan §3 M2; handoff §2C): which topic
 * is on which of the next eight windows, today to +3, sunrise and sunset.
 *
 * <p>A flex child of {@code .wf-map-chrome-bl}, BEFORE the tide strip, never separately positioned,
 * so the legend chip above clears it by flex order and the label layers' bottom-left obstacle grows
 * to include it. The panel variant is desktop and tablet only; the phone mounts the {@code sheet} variant inside the peek sheet (M4).
 *
 * <p><b>The grid.</b> Eight columns by DATE, one row per topic from {@code fourDayGrid} (the served
 * index, never badges). A topic on contiguous windows is one bar. Every grid item is explicitly
 * placed ({@code grid-area}). Bars carry no text, so their accessible names start with the row's
 * visible label (WCAG 2.5.3) and then say which windows and where a press goes. A column with no EV
 * row, and a past column of today, are drawn but cannot be pressed.
 *
 * <p><b>Collapse.</b> The header is a {@code <button aria-expanded>}. The preference lives in
 * {@code localStorage} under try/catch and is three-way: '1', '0', or absent, and when absent the strip
 * starts open only on a map frame at least {@link OPEN_BY_DEFAULT_MIN_FRAME_PX} tall (a short frame is
 * where its label cost is highest). That default is NOT a forced collapse: the reader can open it, and
 * doing so stores '1'. {@code forceCollapsed} (the landing card is open) renders the header alone WITHOUT
 * touching the stored preference, and the header then cannot open it; a muted {@code · closed} cue is
 * drawn in the chevron slot and the reason is spoken through {@code aria-describedby}.
 *
 * <p><b>Held.</b> {@code held} (an open tide strip is showing) draws the strip closed but leaves the
 * header pressable: a press stores '1' and calls {@code onOpenHeld}; the strip does NOT open itself, it
 * opens when the owner re-renders with {@code held} false (topic-strip-tide-yield-plan). {@code
 * forceCollapsed} beats {@code held}; the sheet variant ignores {@code held}.
 *
 * <p><b>Focus.</b> A press that MOVES the window parks focus on the strip root (kept mounted,
 * {@code tabIndex -1}) BEFORE it changes it, the tide strip's {@code focusAfterJump} order, because the
 * press can unmount the very button pressed. A toggle or a press in place leaves focus where it is
 * (the control stays mounted). If focus is inside the grid when the grid goes away (the strip is forced
 * collapsed from elsewhere) it moves to the header, and when the whole strip unmounts it goes to
 * {@code fallbackFocus}, so it is never dropped to {@code <body>}.
 *
 * @param {object} props
 * @param {object} props.grid {@code fourDayGrid}'s result
 * @param {?string} props.selectedType the selected topic type, or null
 * @param {Array<string>} props.currentTypes the topic types that are chips on the window on screen
 * @param {boolean} [props.forceCollapsed] draw the header only, inert (the landing card is open)
 * @param {string} [props.forceReason] why, in words, for {@code forceCollapsed}: "the welcome card is open"
 * @param {boolean} [props.held] draw closed but pressable (the tide strip is open); panel only
 * @param {string} [props.heldReason] what holds it, in words: "the tide strip"
 * @param {?Function} [props.onOpenHeld] called when the reader presses the header while held
 * @param {?Function} [props.onCloseYielded] called when the reader closes an open strip
 * @param {Function} props.onToggleType {@code (type) => void}: toggle the topic where the reader is
 * @param {Function} props.onPick {@code (type, row, message) => void}: select the topic; {@code row} is
 *   the EV row to move to (null to stay), {@code message} the sentence to announce after a move
 * @param {?Function} [props.onSelectHw] {@code (row, message) => void}: a press on a day's High-water tick moves
 *   to that daylight High-water EV row (the tick sits on the Big seas row, over the day's two columns)
 * @param {?Function} [props.onHeightChange] called with the strip's height in px, null on unmount
 * @param {?Function} [props.fallbackFocus] where focus goes if the strip unmounts holding it
 * @param {'panel'|'sheet'} [props.variant] {@code 'sheet'} (M4, phone only) is the same grid inside the peek
 *   sheet's Windows body: full width, 70px label column, no frame-height default (it starts closed when no
 *   preference is stored (unless {@code openByDefault}), no height report, its own storage key
 *   ({@link TOPIC_STRIP_OPEN_SHEET_KEY}), and a scroll into view when the reader opens it. The model, names and
 *   presses are the panel's own.
 * @param {boolean} [props.openByDefault] sheet only: start OPEN when no preference is stored (the phone's
 *   big-seas days, where the chips row is hidden and this is the way to topics)
 * @param {Array<string>} [props.lockedTopicTypes] the types that are a Pro map view for this reader (the tide
 *   topics, for LITE; docs/engineering/tide-pro-gate-plan.md §6.2). A press on a locked row never moves the
 *   window or picks: it parks focus on the strip and calls {@code onToggleType(type, stripRoot)}, so the owner
 *   can open the upsell and return focus to the strip. The row's name says it is a Pro map view.
 * @param {boolean} [props.upsellOpen] whether the upsell is open (a locked row's {@code aria-expanded})
 * @param {string} [props.upsellId] the popover's id, while open ({@code aria-controls})
 * @param {boolean} [props.doorPopup] false on the phone, where the door opens a sheet section, not a dialog
 * @param {boolean} [props.doorDisabled] a locked door with nothing to open (phone, no coastal spot in view)
 */
export default function MapTopicStrip({
  grid, selectedType = null, currentTypes, forceCollapsed = false, forceReason = '', held = false,
  heldReason = '', onOpenHeld = undefined, onCloseYielded = undefined, onToggleType,
  onPick, onSelectHw = undefined, onHeightChange = undefined, fallbackFocus = null, variant = 'panel',
  openByDefault = false, lockedTopicTypes = NO_LOCKED_TYPES, upsellOpen = false,
  upsellId = undefined, doorPopup = true, doorDisabled = false,
}) {
  const inSheet = variant === 'sheet';
  const storageKey = inSheet ? TOPIC_STRIP_OPEN_SHEET_KEY : TOPIC_STRIP_OPEN_KEY;
  const [pref, setPref] = useState(() => readOpenPreference(storageKey));
  const [node, setNode] = useState(null);
  // The map frame's height while it is known (0 reads as hidden, i.e. unknown), for the default.
  const [frameHeight, setFrameHeight] = useState(null);
  const gridId = useId();
  const reasonId = useId();
  const heldId = useId();
  const headerRef = useRef(null);
  const focusedInGridRef = useRef(null);
  const scrollOnOpenRef = useRef(false);
  const fallbackRef = useRef(fallbackFocus);
  const defaultOpen = inSheet
    ? openByDefault
    : (frameHeight == null || frameHeight >= OPEN_BY_DEFAULT_MIN_FRAME_PX);
  const stored = pref ?? defaultOpen;
  // `forceCollapsed` beats `held`; the sheet has no tide strip, so it ignores `held`.
  const isHeld = held && !inSheet && !forceCollapsed;
  const open = stored && !forceCollapsed && !isHeld;

  // Reports the real height up (null on unmount): MapCallout re-measures the strip off the DOM and
  // needs only this value's identity changing as its repaint trigger.
  useEffect(() => {
    if (inSheet || !node || typeof ResizeObserver === 'undefined') return undefined;
    // The frame is the positioned ancestor of the chrome-bl stack this strip sits in: its height is
    // watched by the SAME observer that reports the strip's own height (no second observer).
    const stack = node.parentElement;
    const frame = stack?.offsetParent ?? stack?.parentElement ?? null;
    const write = () => {
      onHeightChange?.(Math.round(node.offsetHeight));
      const h = frame?.offsetHeight ?? 0;
      setFrameHeight(h > 0 ? h : null);
    };
    write();
    const observer = new ResizeObserver(write);
    observer.observe(node);
    if (frame) observer.observe(frame);
    return () => {
      observer.disconnect();
      onHeightChange?.(null);
    };
  }, [node, onHeightChange, inSheet]);

  useEffect(() => { fallbackRef.current = fallbackFocus; }, [fallbackFocus]);

  // Focus inside the grid when it goes away (forced collapse from elsewhere): remember the focused
  // control's node on focusin and, if it is gone after a commit with nothing else focused, move to the
  // header. Never from a blur (Chromium blurs a removed node while it is still connected).
  useEffect(() => {
    if (!node) return undefined;
    function onFocusIn(e) {
      const inGrid = e.target?.closest?.('.wf-tl-grid');
      focusedInGridRef.current = inGrid && node.contains(inGrid) ? e.target : null;
    }
    document.addEventListener('focusin', onFocusIn);
    return () => document.removeEventListener('focusin', onFocusIn);
  }, [node]);
  useLayoutEffect(() => {
    const was = focusedInGridRef.current;
    if (!was || was.isConnected) return;
    focusedInGridRef.current = null;
    const active = document.activeElement;
    if (active && active !== document.body) return;
    headerRef.current?.focus({ preventScroll: true });
  });
  // Unmounting while holding focus (the topics all went, the width crossed the phone line): the pane.
  useLayoutEffect(() => () => {
    const root = headerRef.current?.parentElement;
    if (root && root.contains(document.activeElement)) fallbackRef.current?.();
  }, []);

  // The sheet's strip sits below a list: when the reader opens it, bring the grid into view (the smooth
  // scroll steps back to an instant jump under prefers-reduced-motion).
  useLayoutEffect(() => {
    if (!scrollOnOpenRef.current || !open) return;
    scrollOnOpenRef.current = false;
    const gridEl = node?.querySelector('.wf-tl-grid');
    if (!gridEl?.scrollIntoView) return;
    const reduce = typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    gridEl.scrollIntoView({ block: 'nearest', behavior: reduce ? 'auto' : 'smooth' });
  }, [open, node]);

  const parkFocus = () => node?.focus({ preventScroll: true });
  const onCurrent = (type) => currentTypes.includes(type);

  function toggleOpen() {
    if (forceCollapsed) return;
    if (isHeld) {
      // The owner opens it by re-rendering with `held` false; nothing opens locally.
      setPref(true);
      writeOpenPreference(storageKey, true);
      onOpenHeld?.();
      return;
    }
    const next = !open;
    setPref(next);
    writeOpenPreference(storageKey, next);
    scrollOnOpenRef.current = inSheet && next;
    if (!next && !inSheet) onCloseYielded?.();
  }

  /** "King tide selected; window moved to Friday sunrise": spoken once the window has changed. */
  function movedMessage(topicRow, column) {
    return `${topicRow.name} selected; window moved to ${columnWord(grid, column)}`;
  }

  const isLockedType = (type) => lockedTopicTypes.includes(type);

  /** A locked row is a popover door: the window does not move and nothing is picked. */
  function pressLocked(topicRow) {
    parkFocus();
    onToggleType(topicRow.type, node);
  }

  function pressLabel(topicRow) {
    if (isLockedType(topicRow.type)) {
      pressLocked(topicRow);
      return;
    }
    const target = labelTarget(grid, topicRow, onCurrent(topicRow.type));
    if (!target) return;
    if (target.toggle) {
      // The label stays mounted and keeps focus where it is.
      onToggleType(topicRow.type);
      return;
    }
    parkFocus();
    onPick(topicRow.type, grid.columns[target.column].row, movedMessage(topicRow, target.column));
  }

  function pressBar(topicRow, bar) {
    if (isLockedType(topicRow.type)) {
      pressLocked(topicRow);
      return;
    }
    const target = barTarget(grid, bar, onCurrent(topicRow.type));
    if (!target) return;
    if (target.stay) {
      // Selecting where the reader already is: nothing unmounts, focus stays on the bar.
      onPick(topicRow.type, null, null);
      return;
    }
    parkFocus();
    onPick(topicRow.type, grid.columns[target.column].row, movedMessage(topicRow, target.column));
  }

  let describedBy;
  if (forceCollapsed && forceReason) describedBy = reasonId;
  else if (isHeld && heldReason) describedBy = heldId;

  const rowCount = grid.rows.length;
  const cur = grid.currentColumn;
  // The columns the "you are here" shade covers: one for a window, the whole day for the daylight
  // High-water row. A grid without `currentSpan` (an older caller) falls back to the single column.
  const shade = grid.currentSpan ?? (cur >= 0 ? { start: cur, end: cur } : null);
  // The High-water ticks sit on the Big seas row, the only row that has a day with high water on it.
  const seasRowIndex = grid.rows.findIndex((r) => r.bigSeas);
  const hwTicks = seasRowIndex >= 0 && Array.isArray(grid.hwTicks) ? grid.hwTicks : [];

  // The two header rows size to content; the Big seas track is 36px (24 for the row, 12 for the tick's label
  // above it), every other topic row 24px. Only while a tick is drawn.
  const seasPx = inSheet ? SHEET_SEAS_ROW_PX : 36;
  const rowPx = inSheet ? SHEET_ROW_PX : 24;
  const seasTrackRows = ['auto', 'auto', ...grid.rows.map((_, i) => (i === seasRowIndex ? `${seasPx}px` : `${rowPx}px`))].join(' ');

  function pressTick(tick) {
    if (tick.current) return;
    parkFocus();
    onSelectHw?.(tick.row, `Window moved to ${tick.dow} high water ${tick.time}`);
  }

  return (
    <div
      ref={setNode}
      tabIndex={-1}
      role="group"
      aria-label="Hot topics, next four days"
      className={`wf-topic-strip${inSheet ? ' wf-topic-strip-sheet' : ''}`}
      data-variant={inSheet ? 'sheet' : undefined}
      data-testid="wf-topic-strip"
      data-open={open ? 'true' : 'false'}
    >
      <button
        ref={headerRef}
        type="button"
        className="wf-tl-head"
        aria-expanded={open}
        aria-controls={open ? gridId : undefined}
        aria-disabled={forceCollapsed ? 'true' : undefined}
        aria-describedby={describedBy}
        data-testid="wf-topic-strip-toggle"
        onClick={toggleOpen}
      >
        <span>Hot topics · next four days</span>
        {forceCollapsed
          ? <span className="wf-tl-cv wf-tl-closed-cue" aria-hidden="true" data-testid="wf-topic-strip-closed-cue">· closed</span>
          : <span className="wf-tl-cv" aria-hidden="true">{open ? '▾' : '▴'}</span>}
      </button>
      {isHeld && heldReason && (
        <span id={heldId} className="sr-only" data-testid="wf-topic-strip-held-reason">
          {`Opening it collapses ${heldReason}`}
        </span>
      )}
      {forceCollapsed && forceReason && (
        <span id={reasonId} className="sr-only" data-testid="wf-topic-strip-reason">
          {`Closed while ${forceReason}`}
        </span>
      )}
      {open && (
        <div
          className="wf-tl-grid"
          id={gridId}
          data-testid="wf-topic-strip-grid"
          style={hwTicks.length > 0 ? { gridTemplateRows: seasTrackRows } : undefined}
        >
          {shade != null && (
            <div
              className="wf-tl-now"
              aria-hidden="true"
              data-testid="wf-topic-strip-now"
              data-whole-day={shade.end > shade.start ? 'true' : undefined}
              style={{ gridArea: `1 / ${shade.start + 2} / ${rowCount + 3} / ${shade.end + 3}` }}
            />
          )}
          {grid.columns.filter((col) => col.row != null && !col.interactive).map((col) => (
            <div
              key={`past-${col.index}`}
              className="wf-tl-past"
              aria-hidden="true"
              data-testid={`wf-topic-strip-past-${col.index}`}
              style={{ gridArea: `1 / ${col.index + 2} / ${rowCount + 3} / ${col.index + 3}` }}
            />
          ))}
          {grid.days.map((day, i) => (
            <div
              key={day.date}
              className="wf-tl-d"
              aria-hidden="true"
              data-testid={`wf-topic-strip-day-${day.date}`}
              data-today={day.today ? 'true' : undefined}
              style={{ gridArea: `1 / ${i * 2 + 2} / 2 / ${i * 2 + 4}` }}
            >
              <i>{day.dow.slice(0, 3)}</i>
              <b>{day.dayNum}</b>
            </div>
          ))}
          {grid.columns.map((col) => (
            <div
              key={`x-${col.index}`}
              className="wf-tl-x"
              aria-hidden="true"
              style={{ gridArea: `2 / ${col.index + 2}` }}
            >
              {col.eventType === 'SUNRISE' ? '↑' : '↓'}
            </div>
          ))}
          {grid.rows.map((topicRow, r) => {
            const gridRow = r + 3;
            // The Big seas track is taller while ticks are drawn: its label rides in the top of the track,
            // the label, bars and tick keep the bottom 24px, and nothing overlaps the arrow row above.
            const tall = topicRow.bigSeas && hwTicks.length > 0;
            const rowLocked = isLockedType(topicRow.type);
            const isSelected = !rowLocked && topicRow.type === selectedType;
            const onHere = onCurrent(topicRow.type);
            const labelGo = labelTarget(grid, topicRow, onHere);
            const first = topicRow.on.findIndex((on, c) => on && grid.columns[c]?.interactive);
            let jump = 'no window to open';
            if (first >= 0) jump = `jump to ${columnWord(grid, first)}`;
            // The toggle's name must not flip with its state: `aria-pressed` carries that.
            let labelName = onHere
              ? `${topicRow.shortName}: show on the map`
              : `${topicRow.shortName}: ${jump}`;
            // A locked row opens the upsell: "show on the map" / "jump to ..." would be false.
            if (rowLocked) labelName = `${topicRow.name}, a Pro map view${doorDisabled ? DOOR_DISABLED_SUFFIX : ''}`;
            return (
              <React.Fragment key={topicRow.type}>
                <button
                  type="button"
                  className={`wf-tl-n${tall ? ' wf-tl-tall' : ''}`}
                  aria-label={labelName}
                  aria-pressed={onHere && !rowLocked ? isSelected : undefined}
                  aria-haspopup={rowLocked && doorPopup ? 'dialog' : undefined}
                  aria-expanded={rowLocked ? upsellOpen : undefined}
                  aria-controls={rowLocked && upsellId ? upsellId : undefined}
                  aria-disabled={rowLocked ? (doorDisabled ? 'true' : undefined) : (labelGo ? undefined : 'true')}
                  data-testid={`wf-topic-strip-label-${topicRow.type}`}
                  data-selected={isSelected ? 'true' : undefined}
                  style={{ gridArea: `${gridRow} / 1`, '--wf-tc': topicRow.colour }}
                  onClick={() => pressLabel(topicRow)}
                >
                  <span className="wf-tl-g" aria-hidden="true">
                    {topicRow.bigSeas ? <BoltIcon /> : topicRow.glyph}
                  </span>
                  <span aria-hidden="true">{topicRow.shortName}</span>
                </button>
                {topicRow.bars.map((bar) => {
                  // "Current" means the window on screen has this topic: a bar can cover the active column
                  // yet not be on the row (a sunset-only dust bar under tonight's astro night row).
                  const covers = shade != null && shade.start <= bar.end && shade.end >= bar.start && onHere;
                  const target = barTarget(grid, bar, onHere);
                  const span = spanWords(grid, bar);
                  const barProps = {
                    className: `wf-tl-b${tall ? ' wf-tl-tall' : ''}`,
                    'data-testid': `wf-topic-bar-${topicRow.type}-${bar.start}`,
                    'data-current': covers ? 'true' : undefined,
                    'data-selected': isSelected && covers ? 'true' : undefined,
                    style: {
                      gridArea: `${gridRow} / ${bar.start + 2} / ${gridRow + 1} / ${bar.end + 3}`,
                      '--wf-tc': topicRow.colour,
                    },
                  };
                  const key = `${topicRow.type}-${bar.start}`;
                  if (rowLocked) {
                    return (
                      <button
                        key={key}
                        type="button"
                        {...barProps}
                        aria-label={`${topicRow.name}, ${span}, a Pro map view${doorDisabled ? DOOR_DISABLED_SUFFIX : ''}`}
                        aria-haspopup={doorPopup ? 'dialog' : undefined}
                        aria-expanded={upsellOpen}
                        aria-controls={upsellId}
                        aria-disabled={doorDisabled ? 'true' : undefined}
                        onClick={() => pressBar(topicRow, bar)}
                      />
                    );
                  }
                  if (!target) {
                    return (
                      <span key={key} {...barProps} data-interactive="false">
                        <span className="sr-only">
                          {`${topicRow.shortName}, ${span}, no window to open`}
                        </span>
                      </span>
                    );
                  }
                  let name = `${topicRow.shortName}, ${span}, opens ${columnWord(grid, target.column)}`;
                  if (target.stay) name = `${topicRow.shortName}, ${span}, current window`;
                  if (target.stay && isSelected) name += ', selected';
                  return (
                    <button
                      key={key}
                      type="button"
                      {...barProps}
                      aria-label={name}
                      aria-current={target.stay ? 'true' : undefined}
                      onClick={() => pressBar(topicRow, bar)}
                    />
                  );
                })}
                {/* The daylight High-water ticks (M3), in the Big seas row's own fragment so the tab order
                    runs label, bars, ticks. A SIBLING of the bars, never inside one (a button in a button
                    is invalid and the tick's press is not the bar's), over the row's two columns for that
                    day. The wrapper passes pointer events through; the button is the only target, at least
                    24px square, and its printed "HW 12:40" rides inside it (label in name). */}
                {topicRow.bigSeas && hwTicks.map((tick) => (
                  <div
                    key={`hw-${tick.date}`}
                    className="wf-tl-hw"
                    data-testid={`wf-topic-strip-hw-${tick.date}`}
                    style={{
                      gridArea: `${gridRow} / ${tick.day * 2 + 2} / ${gridRow + 1} / ${tick.day * 2 + 4}`,
                      '--x': `${tick.percent}%`,
                    }}
                  >
                    <button
                      type="button"
                      data-testid={`wf-topic-strip-hw-tick-${tick.date}`}
                      data-current={tick.current ? 'true' : undefined}
                      aria-label={`HW ${tick.time}, high water ${tick.dow}`}
                      aria-current={tick.current ? 'true' : undefined}
                      onClick={() => pressTick(tick)}
                    >
                      <span className="wf-tl-hwl" aria-hidden="true">{`HW ${tick.time}`}</span>
                    </button>
                  </div>
                ))}
              </React.Fragment>
            );
          })}
        </div>
      )}
    </div>
  );
}

MapTopicStrip.propTypes = {
  grid: PropTypes.shape({
    days: PropTypes.arrayOf(PropTypes.shape({
      date: PropTypes.string, dow: PropTypes.string, dayNum: PropTypes.string, today: PropTypes.bool,
    })).isRequired,
    columns: PropTypes.arrayOf(PropTypes.shape({
      index: PropTypes.number,
      date: PropTypes.string,
      eventType: PropTypes.string,
      row: PropTypes.object,
      interactive: PropTypes.bool,
      current: PropTypes.bool,
    })).isRequired,
    currentColumn: PropTypes.number.isRequired,
    /** The columns the "you are here" shade covers: one, or both of a day's for the High-water row. */
    currentSpan: PropTypes.shape({ start: PropTypes.number, end: PropTypes.number }),
    /** The daylight High-water ticks (`fourDayGrid`): only those whose bounds were served. */
    hwTicks: PropTypes.arrayOf(PropTypes.shape({
      date: PropTypes.string,
      day: PropTypes.number,
      percent: PropTypes.number,
      time: PropTypes.string,
      dow: PropTypes.string,
      row: PropTypes.object,
      current: PropTypes.bool,
    })),
    rows: PropTypes.arrayOf(PropTypes.shape({
      type: PropTypes.string,
      shortName: PropTypes.string,
      glyph: PropTypes.string,
      colour: PropTypes.string,
      bigSeas: PropTypes.bool,
      on: PropTypes.arrayOf(PropTypes.bool),
      bars: PropTypes.arrayOf(PropTypes.shape({ start: PropTypes.number, end: PropTypes.number })),
    })).isRequired,
  }).isRequired,
  selectedType: PropTypes.string,
  currentTypes: PropTypes.arrayOf(PropTypes.string).isRequired,
  forceCollapsed: PropTypes.bool,
  forceReason: PropTypes.string,
  held: PropTypes.bool,
  heldReason: PropTypes.string,
  onOpenHeld: PropTypes.func,
  onCloseYielded: PropTypes.func,
  onToggleType: PropTypes.func.isRequired,
  onPick: PropTypes.func.isRequired,
  onSelectHw: PropTypes.func,
  onHeightChange: PropTypes.func,
  fallbackFocus: PropTypes.func,
  openByDefault: PropTypes.bool,
  variant: PropTypes.oneOf(['panel', 'sheet']),
  lockedTopicTypes: PropTypes.arrayOf(PropTypes.string),
  upsellOpen: PropTypes.bool,
  upsellId: PropTypes.string,
  doorPopup: PropTypes.bool,
  doorDisabled: PropTypes.bool,
};
