import { useEffect, useId, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { visibleDialogOpen } from '../../../hooks/usePanelEscape.js';
import useRunCalendar from '../../../hooks/useRunCalendar.js';
import {
  addMonthsClamped, formatDay, monthCells, monthLabel, monthOf, sameMonth, shiftMonth,
} from '../../../utils/travelDays.js';
import { shiftDateStr } from '../../../utils/mapDates.js';
import { formatGbp } from '../../../utils/runsDay.js';

/** The weekday heads, Monday first. */
const HEADS = [
  { key: 'mon', text: 'M' }, { key: 'tue', text: 'T' }, { key: 'wed', text: 'W' }, { key: 'thu', text: 'T' },
  { key: 'fri', text: 'F' }, { key: 'sat', text: 'S' }, { key: 'sun', text: 'S' },
];

const NAV_BUTTON = 'size-7 rounded-md text-[15px] text-plex-text-secondary transition-colors enabled:cursor-pointer '
  + 'enabled:hover:bg-white/8 enabled:hover:text-plex-text disabled:opacity-30 focus-visible:outline-2 '
  + 'focus-visible:outline-plex-gold max-[639px]:size-11';

/** A month as the {@code YYYY-MM} the server asks for. */
function monthKey({ year, month0 }) {
  return `${year}-${String(month0 + 1).padStart(2, '0')}`;
}

/** A month as a number that orders: its index in months. */
const monthIndex = ({ year, month0 }) => year * 12 + month0;

/** The cell's words for assistive tech and the hover tooltip: "Sun 1 Nov · 14 runs · £4.12 · 2 need attention". */
function cellText(iso, entry, now) {
  const parts = [formatDay(iso, now)];
  if (entry) {
    parts.push(`${entry.runs} ${entry.runs === 1 ? 'run' : 'runs'}`);
    parts.push(`${formatGbp(entry.spendGbp)}${entry.spendComplete === false ? ' so far' : ''}`);
    if (entry.attention > 0) parts.push(`${entry.attention} need attention`);
  }
  return parts.join(' · ');
}

/** The spend bar: an SVG rect (presentation attributes, no inline style) whose width is the day's share of the max. */
function SpendBar({ share }) {
  return (
    <svg
      className="absolute bottom-1 left-1.5 right-1.5 h-[3px] w-[calc(100%-12px)]"
      viewBox="0 0 100 3"
      preserveAspectRatio="none"
      aria-hidden="true"
      data-testid="runs-cal-bar"
      data-share={share.toFixed(3)}
    >
      <rect x="0" y="0" width={Math.max(6, share * 100)} height="3" rx="1.5" className="fill-tide" />
    </svg>
  );
}

SpendBar.propTypes = { share: PropTypes.number.isRequired };

/**
 * The Day view's calendar (runs-deep-dive-plan.md F2): a <b>disclosure</b>, never a dialog (no {@code role="dialog"}
 * or {@code aria-modal}: the Data layout and the Map's foreign-modal rules would count one). The date button opens it
 * as a labelled region under the date bar (a 330px popover on desktop, inline and full width on a phone).
 *
 * <p>One month, Monday first, six weeks. Each day shows its number, a 3px spend bar (the day's spend against the
 * month's largest) and an amber dot when something on it needs attention; today has a ring, the selected day is
 * filled, and days after today or before the earliest day are disabled. The grid is one focusable
 * {@code role="grid"} with an active-descendant cursor (the Travel days pattern): arrows move a day or a week,
 * PageUp and PageDown a month, Home and End to the ends of the week, Enter and Space pick. Every handled key calls
 * {@code preventDefault}, so the Day view's own Left, Right and T never also fire.
 *
 * <p>Esc closes it first (a capture listener that stops the event, so the open panel's Esc never sees it), as does an
 * outside press, a pick, and the Operations pane going hidden.
 *
 * @param {object} props
 * @param {string} props.id the region's id (the date button's {@code aria-controls})
 * @param {string} props.date the selected day
 * @param {string} props.today the UK today
 * @param {string|null} [props.earliestDay] the earliest selectable day
 * @param {(iso: string) => void} props.onPick called with the picked day
 * @param {(restoreFocus: boolean) => void} props.onClose closes the calendar; true when focus should go back to the button
 * @param {{current: HTMLElement|null}} props.anchorRef the date button, so pressing it is not an outside press
 */
export default function DayCalendar({ id, date, today, earliestDay = null, onPick, onClose, anchorRef }) {
  const idPrefix = useId();
  const rootRef = useRef(null);
  const gridRef = useRef(null);
  const [viewMonth, setViewMonth] = useState(() => monthOf(date));
  const [cursor, setCursor] = useState(date);
  const now = new Date();
  const month = monthKey(viewMonth);
  const calendar = useRunCalendar(month, true);
  const byDate = useMemo(
    () => new Map((calendar.data?.days ?? []).map((day) => [day.date, day])),
    [calendar.data],
  );
  const maxSpend = Number(calendar.data?.maxSpendGbp ?? 0);
  const cells = monthCells(viewMonth, now);
  const label = monthLabel(viewMonth);
  const weeks = Array.from({ length: cells.length / 7 }, (_, i) => cells.slice(i * 7, i * 7 + 7));
  const minMonth = monthOf(earliestDay ?? date);
  const maxMonth = monthOf(today);
  const canGoBack = monthIndex(viewMonth) > monthIndex(minMonth);
  const canGoForward = monthIndex(viewMonth) < monthIndex(maxMonth);

  const selectable = (iso) => iso <= today && (earliestDay == null || iso >= earliestDay);

  // Focus the grid when the calendar opens, so the keys work at once.
  useEffect(() => {
    gridRef.current?.focus({ preventScroll: true });
  }, []);

  // Esc closes this first: a capture listener, so the open panel's own Esc (a bubble listener) never sees the event.
  useEffect(() => {
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      if (visibleDialogOpen() || rootRef.current?.closest('[hidden]')) return;
      event.preventDefault();
      event.stopPropagation();
      onClose(true);
    };
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, [onClose]);

  // An outside press closes it (the date button toggles it, so a press there is not "outside").
  useEffect(() => {
    const onPointerDown = (event) => {
      const target = event.target;
      if (rootRef.current?.contains(target) || anchorRef.current?.contains(target)) return;
      onClose(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [onClose, anchorRef]);

  // The Operations pane stays mounted, hidden, behind the Map and Plan tabs: a calendar left open closes when it goes.
  useEffect(() => {
    const observer = new MutationObserver(() => {
      if (rootRef.current?.closest('[hidden]')) onClose(false);
    });
    observer.observe(document.body, { attributes: true, attributeFilter: ['hidden'], subtree: true });
    return () => observer.disconnect();
  }, [onClose]);

  function moveTo(iso) {
    let next = iso;
    if (next > today) next = today;
    if (earliestDay != null && next < earliestDay) next = earliestDay;
    setCursor(next);
    const target = monthOf(next);
    if (!sameMonth(target, viewMonth)) setViewMonth(target);
  }

  function handleKeyDown(event) {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    let next;
    switch (event.key) {
      case 'ArrowLeft': next = shiftDateStr(cursor, -1); break;
      case 'ArrowRight': next = shiftDateStr(cursor, 1); break;
      case 'ArrowUp': next = shiftDateStr(cursor, -7); break;
      case 'ArrowDown': next = shiftDateStr(cursor, 7); break;
      case 'PageUp': next = addMonthsClamped(cursor, -1); break;
      case 'PageDown': next = addMonthsClamped(cursor, 1); break;
      case 'Home':
      case 'End': {
        const col = cells.find((cell) => cell.iso === cursor)?.col ?? 0;
        next = shiftDateStr(cursor, event.key === 'Home' ? -col : 6 - col);
        break;
      }
      case 'Enter':
      case ' ':
        event.preventDefault();
        if (selectable(cursor)) onPick(cursor);
        return;
      default:
        return;
    }
    event.preventDefault();
    moveTo(next);
  }

  function handleClick(event) {
    const cell = event.target instanceof Element ? event.target.closest('[data-date]') : null;
    if (!cell || !gridRef.current.contains(cell)) return;
    const iso = cell.dataset.date;
    setCursor(iso);
    if (selectable(iso)) onPick(iso);
  }

  return (
    <div
      ref={rootRef}
      id={id}
      role="region"
      aria-label="Choose a day"
      className="rounded-xl border border-plex-border bg-plex-panel p-3 shadow-[0_12px_32px_rgba(0,0,0,0.5)] max-[639px]:shadow-none min-[640px]:absolute min-[640px]:left-0 min-[640px]:top-full min-[640px]:z-20 min-[640px]:mt-2 min-[640px]:w-[330px]"
      data-testid="runs-calendar"
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <button
          type="button"
          className={NAV_BUTTON}
          aria-label="Previous month"
          disabled={!canGoBack}
          onClick={() => setViewMonth(shiftMonth(viewMonth, -1))}
          data-testid="runs-cal-prev"
        >
          {'‹'}
        </button>
        <h3 className="text-[13px] font-semibold text-plex-text" data-testid="runs-cal-month">
          {label.month} {label.year}
        </h3>
        <button
          type="button"
          className={NAV_BUTTON}
          aria-label="Next month"
          disabled={!canGoForward}
          onClick={() => setViewMonth(shiftMonth(viewMonth, 1))}
          data-testid="runs-cal-next"
        >
          {'›'}
        </button>
      </div>
      <div className="mb-1 grid grid-cols-7 gap-1" aria-hidden="true">
        {HEADS.map((head) => (
          <span key={head.key} className="text-center font-mono text-[10px] text-plex-text-muted">{head.text}</span>
        ))}
      </div>
      {/* The grid is the one focusable widget; the cells are not. Its key and click handlers are the widget's own. */}
      <div
        ref={gridRef}
        role="grid"
        tabIndex={0}
        aria-label={`${label.month} ${label.year}`}
        aria-activedescendant={`${idPrefix}-${cursor}`}
        className="group flex flex-col gap-1 outline-none"
        onKeyDown={handleKeyDown}
        onClick={handleClick}
        data-testid="runs-cal-grid"
      >
        {weeks.map((week) => (
          <div key={week[0].iso} role="row" className="grid grid-cols-7 gap-1">
            {week.map((cell) => {
              if (!cell.inMonth) return <div key={cell.iso} role="gridcell" aria-hidden="true" className="h-10" />;
              const entry = byDate.get(cell.iso);
              const enabled = selectable(cell.iso);
              const selected = cell.iso === date;
              const text = cellText(cell.iso, entry, now);
              let tone = 'bg-plex-surface-light text-plex-text-secondary';
              if (selected) tone = 'bg-plex-text text-plex-bg';
              else if (!enabled) tone = 'bg-plex-surface-light/40 text-plex-text-faint';
              return (
                <div
                  key={cell.iso}
                  id={`${idPrefix}-${cell.iso}`}
                  role="gridcell"
                  aria-selected={selected}
                  aria-disabled={!enabled}
                  aria-current={cell.isToday ? 'date' : undefined}
                  aria-label={text}
                  title={text}
                  data-date={cell.iso}
                  data-cursor={cursor === cell.iso}
                  className={`relative flex h-10 items-center justify-center rounded-md font-mono text-[12px] ${tone} ${
                    enabled ? 'cursor-pointer hover:bg-white/10 hover:text-plex-text' : 'cursor-not-allowed'
                  } ${cell.isToday ? 'inset-ring-[1.5px] inset-ring-plex-text' : ''} ${
                    cursor === cell.iso ? 'group-focus-visible:outline-2 group-focus-visible:outline-offset-1 group-focus-visible:outline-plex-gold' : ''
                  }`}
                  data-testid={`runs-cal-cell-${cell.iso}`}
                >
                  {cell.day}
                  {entry && entry.attention > 0 && (
                    <span
                      className="absolute right-1 top-1 size-[5px] rounded-full bg-verdict-marginal"
                      aria-hidden="true"
                      data-testid={`runs-cal-dot-${cell.iso}`}
                    />
                  )}
                  {entry && maxSpend > 0 && Number(entry.spendGbp) > 0 && (
                    <SpendBar share={Number(entry.spendGbp) / maxSpend} />
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
      <div className="mt-2 min-h-5 text-[11.5px]" aria-live="polite">
        {calendar.loading && (
          <span className="text-plex-text-muted animate-pulse motion-reduce:animate-none" data-testid="runs-cal-loading">
            Loading the month{'…'}
          </span>
        )}
        {calendar.error && (
          <span className="text-plex-coral" data-testid="runs-cal-error">
            Couldn&apos;t load this month.{' '}
            <button
              type="button"
              className="underline focus-visible:outline-2 focus-visible:outline-plex-gold"
              onClick={calendar.retry}
              data-testid="runs-cal-retry"
            >
              Retry
            </button>
          </span>
        )}
      </div>
      <div className="mt-1 flex items-center justify-between gap-3 text-[11.5px] text-plex-text-muted">
        <span className="inline-flex items-center gap-3">
          <span className="inline-flex items-center gap-1.5">
            <span className="h-[3px] w-3 rounded-full bg-tide" aria-hidden="true" />spend
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="size-[5px] rounded-full bg-verdict-marginal" aria-hidden="true" />needs attention
          </span>
        </span>
        <button
          type="button"
          className="rounded-sm px-1 text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
          onClick={() => onPick(today)}
          data-testid="runs-cal-jump-today"
        >
          Jump to today
        </button>
      </div>
    </div>
  );
}

DayCalendar.propTypes = {
  id: PropTypes.string.isRequired,
  date: PropTypes.string.isRequired,
  today: PropTypes.string.isRequired,
  earliestDay: PropTypes.string,
  onPick: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  anchorRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
};
