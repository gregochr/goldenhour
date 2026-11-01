import { useEffect, useId, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { textButtonClass } from '../users/userFormClasses.js';
import { shiftDateStr, ukDateStr } from '../../utils/mapDates.js';
import {
  addMonthsClamped, cellClasses, cellLabel, draftOverlaps, draftSpan, draftText, firstOfMonth, joinFlags, monthCells,
  monthLabel, monthOf, rangeForDay, sameMonth, shiftMonth,
} from '../../utils/travelDays.js';

/** The weekday heads, Monday first. */
const HEADS = [
  { key: 'mon', text: 'M' }, { key: 'tue', text: 'T' }, { key: 'wed', text: 'W' }, { key: 'thu', text: 'T' },
  { key: 'fri', text: 'F' }, { key: 'sat', text: 'S' }, { key: 'sun', text: 'S' },
];

/** The header's three buttons: a 28px tile (44px on a phone), muted until hovered. */
const NAV_BUTTON = 'h-7 rounded-[5px] bg-plex-surface-light text-[12px] text-plex-text-secondary transition-colors '
  + 'enabled:cursor-pointer enabled:hover:bg-white/8 enabled:hover:text-plex-text disabled:opacity-40 '
  + 'focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11';

/** One day of the key: a swatch and its words. */
function KeyItem({ swatch, children }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`size-3 rounded-[3px] ${swatch}`} aria-hidden="true" />
      {children}
    </span>
  );
}

KeyItem.propTypes = { swatch: PropTypes.string.isRequired, children: PropTypes.node.isRequired };

/** The cell a pointer event is over, if it is inside {@code grid}: the one under the pointer, else the event target. */
function cellAt(event, grid) {
  const hit = document.elementFromPoint?.(event.clientX, event.clientY) ?? event.target;
  const cell = hit instanceof Element ? hit.closest('[data-date]') : null;
  return cell && grid.contains(cell) ? cell : null;
}

/**
 * The Travel days month calendar (travel-days-calendar-plan.md, runs-redesign-plan.md §5.6): one month, always six
 * weeks, paged with the header's arrows. Away days of one range join into a bar along each week row; a press on one
 * opens its range. A press on a free day starts a draft: with a mouse or pen drag across the days, on touch tap two
 * days, from the keyboard Enter on two days. A completed draft shows a bar under the grid (Clear, Add range), or, with
 * the Add panel open, fills it.
 *
 * <p>It is one focusable {@code role="grid"} with an active-descendant cursor, so focus never leaves it while the
 * month, the selection or the draft change. Its own state is only the cursor and the drag in progress.
 *
 * @param {object} props
 * @param {Array<object>} props.ranges every served range, past included
 * @param {Date} props.now the real clock (Operations never follows Rewind)
 * @param {{year: number, month0: number}} props.viewMonth the month shown
 * @param {(month: {year: number, month0: number}) => void} props.onViewMonthChange pages the month
 * @param {number|null} props.selectedId the open range's id
 * @param {{start: string, end: string|null}|null} props.draft the draft, or null
 * @param {(draft: {start: string, end: string|null}|null) => void} props.onDraftChange sets or clears the draft
 * @param {boolean} props.addOpen whether the Add panel is open (a draft then fills it and shows no bar)
 * @param {(range: object, iso: string) => void} props.onSelectRange called when an away day is pressed
 * @param {() => void} props.onAddDraft the bar's Add range
 * @param {boolean} [props.draftSaving] whether the bar's Add range is in flight (drafting is then ignored)
 * @param {string} [props.draftError] the refusal to show under the bar
 * @param {{iso: string, seq: number}|null} [props.focusRequest] a request to focus the grid with the cursor on a day
 */
export default function TravelDaysCalendar({
  ranges, now, viewMonth, onViewMonthChange, selectedId = null, draft = null, onDraftChange, addOpen,
  onSelectRange, onAddDraft, draftSaving = false, draftError = '', focusRequest = null,
}) {
  const idPrefix = useId();
  const gridRef = useRef(null);
  const dragRef = useRef(null);
  const lastPointer = useRef(null);
  const dragJustEnded = useRef(false);
  const [drag, setDrag] = useState(null);
  const [focusDate, setFocusDate] = useState(null);
  const [seenSeq, setSeenSeq] = useState(focusRequest?.seq ?? 0);

  const today = ukDateStr(now);
  const todayMonth = monthOf(today);
  const cells = monthCells(viewMonth, now);
  const label = monthLabel(viewMonth);
  const weeks = Array.from({ length: cells.length / 7 }, (_, i) => cells.slice(i * 7, i * 7 + 7));
  const cursor = cells.some((cell) => cell.iso === focusDate) ? focusDate : null;

  // A focus request (a panel a calendar day opened has closed) puts the cursor on that day; the effect below focuses.
  if (focusRequest && focusRequest.seq !== seenSeq) {
    setSeenSeq(focusRequest.seq);
    setFocusDate(focusRequest.iso);
  }
  useEffect(() => {
    if (focusRequest) gridRef.current?.focus({ preventScroll: true });
  }, [focusRequest]);

  // The span painted as the draft: the drag in progress, else the committed draft.
  const span = draftSpan(drag ? { start: drag.start, end: drag.current } : draft);
  const barSpan = addOpen ? null : draftSpan(draft);
  const overlaps = draftOverlaps(ranges, barSpan);
  const barText = barSpan ? draftText(barSpan, now) : null;

  function moveTo(iso) {
    setFocusDate(iso);
    const month = monthOf(iso);
    if (!sameMonth(month, viewMonth)) onViewMonthChange(month);
  }

  function defaultCursor() {
    return sameMonth(todayMonth, viewMonth) ? today : firstOfMonth(viewMonth);
  }

  /** The draft logic shared by a touch tap and Enter: the first day starts it, the second ends it. */
  function pickDay(iso) {
    if (draftSaving) return;
    if (draft && !draft.end) onDraftChange({ start: draft.start, end: iso });
    else onDraftChange({ start: iso, end: null });
  }

  function activate(iso) {
    const range = rangeForDay(ranges, iso);
    if (range) onSelectRange(range, iso);
    else pickDay(iso);
  }

  function endDrag() {
    dragRef.current = null;
    setDrag(null);
  }

  function handlePointerDown(event) {
    lastPointer.current = event.pointerType ?? 'mouse';
    dragJustEnded.current = false;
    if (event.button > 0) return;
    const grid = gridRef.current;
    grid.focus({ preventScroll: true });
    if (event.pointerType === 'touch') return;
    const cell = cellAt(event, grid);
    if (!cell || cell.dataset.away === 'true' || draftSaving) return;
    dragRef.current = { start: cell.dataset.date, current: cell.dataset.date };
    setDrag(dragRef.current);
    try {
      grid.setPointerCapture?.(event.pointerId);
    } catch {
      // No capture (a synthetic event): the drag still follows the pointer over the grid.
    }
  }

  function handlePointerMove(event) {
    if (!dragRef.current) return;
    const cell = cellAt(event, gridRef.current);
    if (!cell || cell.dataset.date === dragRef.current.current) return;
    dragRef.current = { ...dragRef.current, current: cell.dataset.date };
    setDrag(dragRef.current);
  }

  function handlePointerUp(event) {
    const active = dragRef.current;
    if (!active) return;
    const cell = cellAt(event, gridRef.current);
    const end = cell ? cell.dataset.date : active.current;
    endDrag();
    dragJustEnded.current = true;
    const [start, last] = active.start <= end ? [active.start, end] : [end, active.start];
    onDraftChange({ start, end: last });
  }

  function handleClick(event) {
    const cell = event.target instanceof Element ? event.target.closest('[data-date]') : null;
    if (!cell || !gridRef.current.contains(cell)) return;
    if (dragJustEnded.current) {
      dragJustEnded.current = false;
      return;
    }
    const iso = cell.dataset.date;
    const range = rangeForDay(ranges, iso);
    if (range) {
      setFocusDate(iso);
      onSelectRange(range, iso);
    } else if (lastPointer.current === 'touch') {
      setFocusDate(iso);
      pickDay(iso);
    }
  }

  function handleKeyDown(event) {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    const from = cursor ?? defaultCursor();
    let next;
    switch (event.key) {
      case 'ArrowLeft': next = shiftDateStr(from, -1); break;
      case 'ArrowRight': next = shiftDateStr(from, 1); break;
      case 'ArrowUp': next = shiftDateStr(from, -7); break;
      case 'ArrowDown': next = shiftDateStr(from, 7); break;
      case 'PageUp': next = addMonthsClamped(from, -1); break;
      case 'PageDown': next = addMonthsClamped(from, 1); break;
      case 't':
      case 'T': next = today; break;
      case 'Home':
      case 'End': {
        const row = cells.find((cell) => cell.iso === from);
        // A cursor off the shown month (the month paged by hand) falls back to its own week arithmetic.
        const col = row ? row.col : 0;
        next = shiftDateStr(from, event.key === 'Home' ? -col : 6 - col);
        break;
      }
      case 'Enter':
      case ' ':
        event.preventDefault();
        setFocusDate(from);
        activate(from);
        return;
      case 'Escape':
        if (draft || dragRef.current) {
          event.preventDefault();
          endDrag();
          if (draft) onDraftChange(null);
        }
        return;
      default:
        return;
    }
    event.preventDefault();
    moveTo(next);
  }

  function handleFocus() {
    if (focusDate == null) setFocusDate(defaultCursor());
  }

  const draftSentence = barText
    ? (barSpan.end ? `Draft ${barText.dates}, ${barText.detail}` : `Draft from ${barText.dates}, ${barText.detail}`)
    : '';

  return (
    <div className="max-w-[520px] max-[639px]:max-w-none" data-testid="travel-calendar">
      <div className="mb-2 flex min-h-8 items-center justify-between gap-2">
        <h3 className="text-[13px] font-medium text-plex-text" data-testid="travel-calendar-month">
          {label.month} <span className="text-plex-text-muted">{label.year}</span>
        </h3>
        <div className="flex items-center gap-1">
          <button
            type="button"
            className={`${NAV_BUTTON} px-2.5`}
            disabled={sameMonth(todayMonth, viewMonth)}
            onClick={() => { onViewMonthChange(todayMonth); setFocusDate(today); }}
            data-testid="travel-calendar-today"
          >
            Today
          </button>
          <button
            type="button"
            className={`${NAV_BUTTON} w-7 text-[15px] max-[639px]:min-w-11`}
            aria-label="Previous month"
            onClick={() => { onViewMonthChange(shiftMonth(viewMonth, -1)); setFocusDate(null); }}
            data-testid="travel-calendar-prev"
          >
            {'‹'}
          </button>
          <button
            type="button"
            className={`${NAV_BUTTON} w-7 text-[15px] max-[639px]:min-w-11`}
            aria-label="Next month"
            onClick={() => { onViewMonthChange(shiftMonth(viewMonth, 1)); setFocusDate(null); }}
            data-testid="travel-calendar-next"
          >
            {'›'}
          </button>
        </div>
      </div>
      <div className="mb-1 grid grid-cols-7 gap-[3px]" aria-hidden="true">
        {HEADS.map((head) => (
          <span key={head.key} className="text-center font-mono text-[10px] text-plex-text-muted">{head.text}</span>
        ))}
      </div>
      {/* The pointer and key handlers are the grid's own: it is the one focusable widget, the cells are not. */}
      <div
        ref={gridRef}
        role="grid"
        tabIndex={0}
        aria-label={`${label.month} ${label.year}`}
        aria-multiselectable="true"
        aria-activedescendant={cursor ? `${idPrefix}-${cursor}` : undefined}
        aria-describedby={`${idPrefix}-hint`}
        className="group flex touch-manipulation flex-col gap-[3px] outline-none"
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={endDrag}
        onLostPointerCapture={endDrag}
        onClick={handleClick}
        onKeyDown={handleKeyDown}
        onFocus={handleFocus}
        data-testid="travel-calendar-grid"
      >
        {weeks.map((week) => (
          <div key={week[0].iso} role="row" className="grid grid-cols-7 gap-[3px]">
            {week.map((cell) => {
              const range = rangeForDay(ranges, cell.iso);
              const selected = range != null && range.id === selectedId;
              const inDraft = span != null && cell.iso >= span.start && cell.iso <= (span.end ?? span.start);
              const { joinLeft, joinRight } = joinFlags(ranges, cell.iso, cell.col);
              return (
                <div
                  key={cell.iso}
                  id={`${idPrefix}-${cell.iso}`}
                  role="gridcell"
                  aria-label={cellLabel(cell, range, now, { selected, inDraft })}
                  aria-selected={selected ? 'true' : 'false'}
                  aria-current={cell.isToday ? 'date' : undefined}
                  className={cellClasses({
                    away: range != null,
                    selected,
                    draft: inDraft,
                    today: cell.isToday,
                    past: cell.isPast,
                    outside: !cell.inMonth,
                    joinLeft,
                    joinRight,
                    cursor: cursor === cell.iso,
                  })}
                  data-date={cell.iso}
                  data-testid={`travel-cell-${cell.iso}`}
                  data-away={range ? 'true' : 'false'}
                  data-selected={selected ? 'true' : 'false'}
                  data-draft={inDraft ? 'true' : 'false'}
                >
                  {cell.day}
                </div>
              );
            })}
          </div>
        ))}
      </div>
      <span className="sr-only" aria-live="polite" data-testid="travel-calendar-live">
        <span>{label.month} {label.year}</span>
        <span>{draftSentence}</span>
      </span>

      {barSpan && barText && (
        <div
          className="mt-2 flex flex-wrap items-center gap-x-2.5 gap-y-1 rounded-md bg-plex-surface-light px-2.5 py-2 text-[12px] text-plex-text-secondary"
          data-testid="travel-draft-bar"
        >
          <span data-testid="travel-draft-text">
            {barSpan.end == null && 'From '}
            <span className="font-medium text-plex-text">{barText.dates}</span>
            {' · '}{barText.detail}
          </span>
          {overlaps && (
            <span className="text-plex-text" data-testid="travel-draft-overlap">
              {'·'} overlaps an existing range
            </span>
          )}
          <span className="flex-1" />
          <button type="button" className={textButtonClass} onClick={() => onDraftChange(null)} data-testid="travel-draft-clear">
            Clear
          </button>
          {barSpan.end != null && (
            <button
              type="button"
              className="btn-secondary max-[639px]:min-h-11"
              disabled={overlaps || draftSaving}
              onClick={onAddDraft}
              data-testid="travel-draft-add"
            >
              {draftSaving ? 'Adding…' : 'Add range'}
            </button>
          )}
          {draftError && (
            <p className="basis-full text-xs text-plex-error" role="alert" data-testid="travel-draft-error">{draftError}</p>
          )}
        </div>
      )}

      <p className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11.5px] text-plex-text-muted" data-testid="travel-calendar-key">
        <KeyItem swatch="bg-tide/55">Away {'·'} no forecasts</KeyItem>
        <KeyItem swatch="bg-plex-surface-light inset-ring-[1.5px] inset-ring-plex-text">Today</KeyItem>
        <span id={`${idPrefix}-hint`} className="ml-auto font-mono text-[10.5px]" data-testid="travel-calendar-hint">
          <span className="hidden pointer-fine:inline">Drag across days to add a range</span>
          <span className="hidden pointer-coarse:inline">Tap two days to add a range</span>
        </span>
      </p>
    </div>
  );
}

TravelDaysCalendar.propTypes = {
  ranges: PropTypes.arrayOf(PropTypes.object).isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
  viewMonth: PropTypes.shape({ year: PropTypes.number, month0: PropTypes.number }).isRequired,
  onViewMonthChange: PropTypes.func.isRequired,
  selectedId: PropTypes.number,
  draft: PropTypes.shape({ start: PropTypes.string, end: PropTypes.string }),
  onDraftChange: PropTypes.func.isRequired,
  addOpen: PropTypes.bool.isRequired,
  onSelectRange: PropTypes.func.isRequired,
  onAddDraft: PropTypes.func.isRequired,
  draftSaving: PropTypes.bool,
  draftError: PropTypes.string,
  focusRequest: PropTypes.shape({ iso: PropTypes.string, seq: PropTypes.number }),
};
