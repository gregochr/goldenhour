import { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import DayDateBar from './DayDateBar.jsx';
import DayRunList from './DayRunList.jsx';
import DayStrip from './DayStrip.jsx';
import DaySummary from './DaySummary.jsx';
import { visibleDialogOpen } from '../../../hooks/usePanelEscape.js';
import { nextDay, previousDay, rowMatchesFilter } from '../../../utils/runsDay.js';
import { ukDateStr } from '../../../utils/mapDates.js';

/** Whether a key event's target is a field the reader types in (the date keys then belong to it). */
function isTypingTarget(target) {
  if (!(target instanceof HTMLElement)) return false;
  return ['INPUT', 'SELECT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable;
}

/** The loading skeleton: the summary, strip and list as quiet pulsing blocks. */
function DaySkeleton() {
  return (
    <div className="flex flex-col gap-4 animate-pulse motion-reduce:animate-none" data-testid="runs-day-loading" aria-busy="true">
      <p className="sr-only">Loading the day{'…'}</p>
      <div className="h-[84px] rounded-[10px] bg-plex-surface-light/60" />
      <div className="h-[190px] rounded-xl bg-plex-surface-light/60" />
      <div className="h-[260px] rounded-xl bg-plex-surface-light/60" />
    </div>
  );
}

/**
 * Operations → Runs → Day (runs-deep-dive-plan.md §4 D2): the date bar, the summary strip, the 24-hour strip and the
 * run card for one UK day. The data, the date and the filter are the caller's ({@code RunsView} owns the address,
 * the poll and the refresh); this owns what is open on screen: the expanded row (one at a time), the calendar
 * disclosure, and the keys.
 *
 * <p>Keys (F1): Left and Right step a day, T jumps to today. The listener does nothing when the Runs view is inside
 * {@code [hidden]} (the Operations pane stays mounted behind Map and Plan), when the key was already handled
 * ({@code defaultPrevented}: the calendar grid, the view tabs), when Ctrl, Meta or Alt is held, when focus is in an
 * input, select or textarea, when a visible dialog is open, or while a run's panel is open.
 *
 * <p>States (F13): a loading skeleton; an error with Retry; an empty day; a filter with no rows (Show all). A failed
 * refresh of a day already on screen keeps it, with a line saying so.
 *
 * @param {object} props
 * @param {{data: object|null, loading: boolean, error: string|null, refresh: () => void}} props.day the Day data
 * @param {string|null} props.date the shown day, or null for today
 * @param {(date: string|null) => void} props.onDateChange moves to a day (null is today)
 * @param {string} props.filter the filter chip key
 * @param {(filter: string) => void} props.onFilterChange sets the chip
 * @param {string|null} [props.selectedKey] the row whose panel is open ({@code cycle-12})
 * @param {boolean} props.panelOpen whether any panel is open (the date keys stand down)
 * @param {(row: object) => void} props.onOpenDetail opens a row's panel
 */
export default function RunsDayView({
  day, date, onDateChange, filter, onFilterChange, selectedKey = null, panelOpen, onOpenDetail,
}) {
  const rootRef = useRef(null);
  const [scrollRequest, setScrollRequest] = useState(null);
  const [openId, setOpenId] = useState(null);
  const [calendarOpen, setCalendarOpen] = useState(false);
  const data = day.data;
  const realToday = ukDateStr(new Date());
  const today = data?.isToday ? data.date : realToday;
  const shown = data?.date ?? date ?? today;
  const earliestDay = data?.earliestDay ?? null;

  // A different day (a step, a pick, Back, an inbound link) starts with no row open.
  const [openFor, setOpenFor] = useState(shown);
  if (openFor !== shown) {
    setOpenFor(shown);
    setOpenId(null);
  }

  const goTo = useCallback((iso) => {
    let target = iso;
    if (earliestDay && target < earliestDay) target = earliestDay;
    setOpenId(null);
    onDateChange(target >= today ? null : target);
  }, [earliestDay, today, onDateChange]);

  // The keys, through a ref so the one document listener never re-binds.
  const keysRef = useRef(null);
  useEffect(() => {
    keysRef.current = (event) => {
      if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
      if (rootRef.current?.closest('[hidden]')) return;
      if (isTypingTarget(event.target) || visibleDialogOpen() || panelOpen) return;
      let target = null;
      if (event.key === 'ArrowLeft' && !event.shiftKey) {
        if (earliestDay == null || shown > earliestDay) target = previousDay(shown);
      } else if (event.key === 'ArrowRight' && !event.shiftKey) {
        if (shown < today) target = nextDay(shown);
      } else if ((event.key === 't' || event.key === 'T') && shown !== today) {
        target = today;
      }
      // Only a key that moves the date is handled: at the edge it stays free for whatever else wants it.
      if (target == null) return;
      event.preventDefault();
      goTo(target);
      if (calendarOpen) {
        // The calendar closes with the date; focus goes back to its button rather than to the page.
        setCalendarOpen(false);
        rootRef.current?.querySelector('[data-testid="runs-day-date"]')?.focus({ preventScroll: true });
      }
    };
  });
  useEffect(() => {
    const onKeyDown = (event) => keysRef.current?.(event);
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);

  // A strip block opens its row (clearing a filter that hides it) and scrolls it into view.
  const handleStripSelect = (id) => {
    const row = data?.runs.find((r) => r.id === id);
    if (row && !rowMatchesFilter(row, filter)) onFilterChange('all');
    setOpenId(id);
    // A fresh object per press, so pressing the block of a row that is already open still scrolls to it.
    setScrollRequest({ id });
  };
  useEffect(() => {
    if (scrollRequest == null) return;
    rootRef.current?.querySelector(`[data-testid="runs-day-item-${scrollRequest.id}"]`)?.scrollIntoView?.({ block: 'nearest' });
  }, [scrollRequest]);

  const toggle = useCallback((id) => setOpenId((current) => (current === id ? null : id)), []);

  let body;
  if (day.error && !data) {
    body = (
      <div className="rounded-xl border border-red-700 bg-red-900/20 p-4" data-testid="runs-day-error">
        <p className="text-sm text-red-400">{day.error}</p>
        <button type="button" className="btn-secondary mt-2 text-xs max-[639px]:min-h-11" onClick={day.refresh} data-testid="runs-day-retry">
          Retry
        </button>
      </div>
    );
  } else if (day.loading || !data) {
    body = <DaySkeleton />;
  } else {
    body = (
      <>
        {day.error && (
          <p className="text-xs text-plex-coral" role="status" data-testid="runs-day-stale">
            Couldn&apos;t refresh this day: {day.error}. Showing what was last loaded.
          </p>
        )}
        <DaySummary summary={data.summary} isToday={data.isToday} />
        <DayStrip day={data} selectedId={openId} onSelect={handleStripSelect} />
        <DayRunList
          day={data}
          filter={filter}
          onFilterChange={onFilterChange}
          openId={openId}
          onToggle={toggle}
          selectedKey={selectedKey}
          onOpenDetail={onOpenDetail}
        />
      </>
    );
  }

  return (
    <div ref={rootRef} className="flex flex-col gap-4" data-testid="runs-day-view">
      <DayDateBar
        date={shown}
        today={today}
        earliestDay={earliestDay}
        calendarOpen={calendarOpen}
        onCalendarOpenChange={setCalendarOpen}
        onChange={goTo}
      />
      {body}
    </div>
  );
}

RunsDayView.propTypes = {
  day: PropTypes.shape({
    data: PropTypes.object,
    loading: PropTypes.bool.isRequired,
    error: PropTypes.string,
    refresh: PropTypes.func.isRequired,
  }).isRequired,
  date: PropTypes.string,
  onDateChange: PropTypes.func.isRequired,
  filter: PropTypes.string.isRequired,
  onFilterChange: PropTypes.func.isRequired,
  selectedKey: PropTypes.string,
  panelOpen: PropTypes.bool.isRequired,
  onOpenDetail: PropTypes.func.isRequired,
};
