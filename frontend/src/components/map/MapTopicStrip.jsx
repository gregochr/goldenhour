import React, {
  useEffect, useId, useLayoutEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import BoltIcon from './BoltIcon.jsx';
import { barTarget, columnWord, labelTarget } from '../../utils/mapTopics.js';

/** The per-viewer preference: '1' (open), '0' (closed) or absent (the frame height decides). */
export const TOPIC_STRIP_OPEN_KEY = 'mapTopicStripOpen';

/** With no stored choice the strip starts open only on a map frame at least this tall (CSS px). */
export const OPEN_BY_DEFAULT_MIN_FRAME_PX = 720;

/** The stored preference as true / false, or null when there is none (or storage is unavailable). */
function readOpenPreference() {
  try {
    const v = localStorage.getItem(TOPIC_STRIP_OPEN_KEY);
    if (v === '1') return true;
    if (v === '0') return false;
    return null;
  } catch {
    return null;
  }
}

function writeOpenPreference(open) {
  try {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, open ? '1' : '0');
  } catch {
    // Storage unavailable: the in-memory state still holds the choice for this session.
  }
}

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
 * to include it. Desktop and tablet only: the caller does not mount it on a phone (M4 owns that).
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
 * doing so stores '1'. {@code forceCollapsed} (the tide strip is showing, or the landing card is open)
 * renders the header alone WITHOUT touching the stored preference, and the header then cannot open it;
 * it says why through {@code aria-describedby}.
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
 * @param {boolean} [props.forceCollapsed] draw the header only (tide strip showing, landing card open)
 * @param {string} [props.forceReason] why, in words, for {@code forceCollapsed}: "the tide strip is
 *   showing" or "the welcome card is open"
 * @param {Function} props.onToggleType {@code (type) => void}: toggle the topic where the reader is
 * @param {Function} props.onPick {@code (type, row, message) => void}: select the topic; {@code row} is
 *   the EV row to move to (null to stay), {@code message} the sentence to announce after a move
 * @param {?Function} [props.onHeightChange] called with the strip's height in px, null on unmount
 * @param {?Function} [props.fallbackFocus] where focus goes if the strip unmounts holding it
 */
export default function MapTopicStrip({
  grid, selectedType = null, currentTypes, forceCollapsed = false, forceReason = '', onToggleType,
  onPick, onHeightChange = undefined, fallbackFocus = null,
}) {
  const [pref, setPref] = useState(readOpenPreference);
  const [node, setNode] = useState(null);
  // The map frame's height while it is known (0 reads as hidden, i.e. unknown), for the default.
  const [frameHeight, setFrameHeight] = useState(null);
  const gridId = useId();
  const reasonId = useId();
  const headerRef = useRef(null);
  const focusedInGridRef = useRef(null);
  const fallbackRef = useRef(fallbackFocus);
  const defaultOpen = frameHeight == null || frameHeight >= OPEN_BY_DEFAULT_MIN_FRAME_PX;
  const stored = pref ?? defaultOpen;
  const open = stored && !forceCollapsed;

  // Reports the real height up (null on unmount): MapCallout re-measures the strip off the DOM and
  // needs only this value's identity changing as its repaint trigger.
  useEffect(() => {
    if (!node || typeof ResizeObserver === 'undefined') return undefined;
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
  }, [node, onHeightChange]);

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

  const parkFocus = () => node?.focus({ preventScroll: true });
  const onCurrent = (type) => currentTypes.includes(type);

  function toggleOpen() {
    if (forceCollapsed) return;
    const next = !open;
    setPref(next);
    writeOpenPreference(next);
  }

  /** "King tide selected; window moved to Friday sunrise": spoken once the window has changed. */
  function movedMessage(topicRow, column) {
    return `${topicRow.name} selected; window moved to ${columnWord(grid, column)}`;
  }

  function pressLabel(topicRow) {
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

  const rowCount = grid.rows.length;
  const cur = grid.currentColumn;

  return (
    <div
      ref={setNode}
      tabIndex={-1}
      role="group"
      aria-label="Hot topics, next four days"
      className="wf-topic-strip"
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
        aria-describedby={forceCollapsed && forceReason ? reasonId : undefined}
        data-testid="wf-topic-strip-toggle"
        onClick={toggleOpen}
      >
        <span>Hot topics · next four days</span>
        {!forceCollapsed && <span className="wf-tl-cv" aria-hidden="true">{open ? '▾' : '▴'}</span>}
      </button>
      {forceCollapsed && forceReason && (
        <span id={reasonId} className="sr-only" data-testid="wf-topic-strip-reason">
          {`Closed while ${forceReason}`}
        </span>
      )}
      {open && (
        <div className="wf-tl-grid" id={gridId} data-testid="wf-topic-strip-grid">
          {cur >= 0 && (
            <div
              className="wf-tl-now"
              aria-hidden="true"
              data-testid="wf-topic-strip-now"
              style={{ gridArea: `1 / ${cur + 2} / ${rowCount + 3} / ${cur + 3}` }}
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
            const isSelected = topicRow.type === selectedType;
            const onHere = onCurrent(topicRow.type);
            const labelGo = labelTarget(grid, topicRow, onHere);
            const first = topicRow.on.findIndex((on, c) => on && grid.columns[c]?.interactive);
            let jump = 'no window to open';
            if (first >= 0) jump = `jump to ${columnWord(grid, first)}`;
            // The toggle's name must not flip with its state: `aria-pressed` carries that.
            const labelName = onHere
              ? `${topicRow.shortName}: show on the map`
              : `${topicRow.shortName}: ${jump}`;
            return (
              <React.Fragment key={topicRow.type}>
                <button
                  type="button"
                  className="wf-tl-n"
                  aria-label={labelName}
                  aria-pressed={onHere ? isSelected : undefined}
                  aria-disabled={labelGo ? undefined : 'true'}
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
                  const covers = cur >= bar.start && cur <= bar.end && onHere;
                  const target = barTarget(grid, bar, onHere);
                  const span = spanWords(grid, bar);
                  const barProps = {
                    className: 'wf-tl-b',
                    'data-testid': `wf-topic-bar-${topicRow.type}-${bar.start}`,
                    'data-current': covers ? 'true' : undefined,
                    'data-selected': isSelected && covers ? 'true' : undefined,
                    style: {
                      gridArea: `${gridRow} / ${bar.start + 2} / ${gridRow + 1} / ${bar.end + 3}`,
                      '--wf-tc': topicRow.colour,
                    },
                  };
                  const key = `${topicRow.type}-${bar.start}`;
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
  onToggleType: PropTypes.func.isRequired,
  onPick: PropTypes.func.isRequired,
  onHeightChange: PropTypes.func,
  fallbackFocus: PropTypes.func,
};
