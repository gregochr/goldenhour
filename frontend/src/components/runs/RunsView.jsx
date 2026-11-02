import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import DataSplitLayout from '../data/DataSplitLayout.jsx';
import RunProgressPanel from '../RunProgressPanel';
import RunsHeader from './RunsHeader.jsx';
import RunsTopStack from './RunsTopStack.jsx';
import RunsViewTabs, { PANEL_ID, tabId } from './RunsViewTabs.jsx';
import RunsCostView from './cost/RunsCostView.jsx';
import RunsDayView from './day/RunsDayView.jsx';
import RunsTimingView from './timing/RunsTimingView.jsx';
import JobRunPanel from './JobRunPanel.jsx';
import CyclePanel from './CyclePanel.jsx';
import RunsLiveStrip from './RunsLiveStrip.jsx';
import { useIsSplitLayout } from '../../hooks/useIsSplitLayout.js';
import StartRunPanel, { useStartRunState } from './StartRunPanel.jsx';
import SubRegionToast from '../subregions/SubRegionToast.jsx';
import useHashSelection from '../../hooks/useHashSelection.js';
import useCycleDetail from '../../hooks/useCycleDetail.js';
import useJobRunDetail from '../../hooks/useJobRunDetail.js';
import usePanelEscape from '../../hooks/usePanelEscape.js';
import useRunDay from '../../hooks/useRunDay.js';
import { useRunsCost, useRunsTiming } from '../../hooks/useRunsServed.js';
import useRunsLive from '../../hooks/useRunsLive.js';
import { ukDateStr } from '../../utils/mapDates.js';
import { dayTabWord } from '../../utils/runsDay.js';
import { readRunsQuery, writeRunsQuery } from '../../utils/runsQuery.js';
import {
  COST_RANGES, DEFAULT_COST_RANGE, DEFAULT_TIMING_RANGE, TIMING_RANGES, TIMING_TYPES,
} from '../../utils/runsTiming.js';
import { isCycleType } from '../../utils/runsDay.js';
import { textButtonClass } from '../users/userFormClasses.js';

/** The Manage tab key; the hash adds {@code job/{id}}, {@code cycle/{id}[/job/{id}]} or {@code start}. */
const TAB = 'runs';

/** The keys of the address's query, for telling whether it moved. */
const QUERY_KEYS = ['view', 'date', 'filter', 'range', 'type', 'measure'];

const isId = (segment) => /^\d+$/.test(segment ?? '');

/**
 * Reads the segments after {@code #manage/runs}: {@code job/{id}} opens a job-run panel, {@code cycle/{id}} a cycle's,
 * {@code cycle/{id}/job/{jobRunId}} one of that cycle's batches, {@code start} the Start a run panel; anything else
 * (the bare tab) names no panel. Module-level so the hash hook's listener stays stable.
 */
function parseRunsHash(segments) {
  const [a, b, c, d] = segments;
  if (segments.length === 1 && a === 'start') return { type: 'start' };
  if (segments.length === 2 && a === 'job' && isId(b)) return { type: 'job', id: Number(b) };
  if (segments.length === 2 && a === 'cycle' && isId(b)) return { type: 'cycle', id: Number(b) };
  if (segments.length === 4 && a === 'cycle' && isId(b) && c === 'job' && isId(d)) {
    return { type: 'batch', cycleId: Number(b), id: Number(d) };
  }
  return null;
}

/** The path after the tab for a selection, the inverse of {@link parseRunsHash}. */
function formatRunsHash(selection) {
  switch (selection.type) {
    case 'start': return 'start';
    case 'cycle': return `cycle/${selection.id}`;
    case 'batch': return `cycle/${selection.cycleId}/job/${selection.id}`;
    default: return `job/${selection.id}`;
  }
}

/**
 * Operations → Runs (runs-deep-dive-plan.md D2): the Runs header, the progress panel and the running-cycle banner, the
 * view tabs (Day, Cost trends, Timing) and the view, on the shared Data list-and-panel shell. The server serves
 * every row, status, figure and count (R-D1); this view asks and renders.
 *
 * <p>The view, the Day's date and its filter chip ride the hash query ({@code ?view=day&date=2026-11-01&filter=cycles},
 * {@code utils/runsQuery.js}); the path ({@code job/{id}}, {@code cycle/{id}[/job/{id}]}, {@code start}) is the
 * panel's. A Day row opens its detail panel through its "Open run detail" link: a cycle opens the cycle panel, whose
 * batch rows open the job-run panel nested under it. While the server names a running cycle a live banner sits above
 * the view tabs, on every view; it and the open cycle panel share one fetch ({@code useCycleDetail}). Start a run
 * opens {@code #manage/runs/start}. A start with a job run id opens that run's panel; one without closes the Start
 * panel and toasts the server's line.
 *
 * <p>The run {@code RunProgressPanel} follows ({@code activeRunId}, kept in ManageView so it survives leaving the tab)
 * draws above the views rather than inside the Start panel, so closing that panel never drops it. Every clock read in
 * this view is the real clock (R-D7): Operations never follows an admin's Rewind.
 *
 * @param {object} props
 * @param {number|null} [props.activeRunId] the run whose progress is followed, kept by ManageView
 * @param {(jobRunId: number) => void} props.onActiveRunChange sets that run
 * @param {() => void} props.onActiveRunClear clears it
 * @param {(runningCount: number) => void} [props.onLiveCountChange] told the server's {@code runningCount} on each of
 *   this view's own refreshes, for the live count beside Runs in ManageView's navigation
 */
export default function RunsView({
  activeRunId = null, onActiveRunChange, onActiveRunClear, onLiveCountChange = undefined,
}) {
  // The view, the Day's date and its filter ride the hash query (F4); one helper reads and writes them.
  const [query, setQuery] = useState(readRunsQuery);
  const isSplit = useIsSplitLayout();
  const rootRef = useRef(null);
  // The Operations pane stays mounted, hidden, while the reader is on the Map or Plan tab: no polling then.
  const isPaused = useCallback(() => rootRef.current?.closest('[hidden]') != null, []);
  const runsLive = useRunsLive({ isPaused });
  // The address can move under the view: Back after a panel, an inbound link while mounted. Re-read it then (our
  // own writes use replaceState and fire neither event).
  useEffect(() => {
    const onAddressChange = () => {
      const [area, tab] = window.location.hash.replace('#', '').split('?')[0].split('/');
      if (area !== 'manage' || tab !== 'runs') return;
      const next = readRunsQuery();
      setQuery((prev) => (QUERY_KEYS.every((key) => prev[key] === next[key]) ? prev : next));
    };
    window.addEventListener('hashchange', onAddressChange);
    window.addEventListener('popstate', onAddressChange);
    return () => {
      window.removeEventListener('hashchange', onAddressChange);
      window.removeEventListener('popstate', onAddressChange);
    };
  }, []);
  const handleDateChange = useCallback((date) => {
    setQuery((q) => ({ ...q, date }));
    writeRunsQuery({ date });
  }, []);
  const day = useRunDay(query.date, {
    enabled: query.view === 'day', isPaused, onClamp: handleDateChange,
    runningCount: runsLive.meta?.runningCount,
  });
  // The Cost and Timing views' own parameters (DD-9): an unknown or missing one falls back to the view's default.
  const costRange = COST_RANGES.includes(query.range) ? query.range : DEFAULT_COST_RANGE;
  const costIsolate = query.type;
  const timingRange = TIMING_RANGES.includes(query.range) ? query.range : DEFAULT_TIMING_RANGE;
  const timingType = TIMING_TYPES.includes(query.type) ? query.type : 'AFTERNOON';
  // Until answers ready is for the three cycles only; any other type measures the run itself.
  const timingMeasure = isCycleType(timingType) ? query.measure : 'cycle';
  const cost = useRunsCost(costRange, { enabled: query.view === 'cost' });
  const timing = useRunsTiming(timingType, timingRange, timingMeasure, { enabled: query.view === 'timing' });
  const handleViewChange = useCallback((view) => {
    // The Cost and Timing parameters mean different things in each view, so a change of view clears them.
    setQuery((q) => ({ ...q, view, range: null, type: null, measure: 'cycle' }));
    writeRunsQuery({ view, range: null, type: null, measure: null });
  }, []);
  const handleParams = useCallback((patch) => {
    setQuery((q) => ({ ...q, ...patch }));
    writeRunsQuery(patch);
  }, []);
  // A day clicked in the Cost chart or the Timing scatter opens in the Day view (a day that is today is "today").
  const handleOpenDay = useCallback((date) => {
    const target = date >= ukDateStr(new Date()) ? null : date;
    setQuery((q) => ({ ...q, view: 'day', date: target, range: null, type: null, measure: 'cycle' }));
    writeRunsQuery({ view: 'day', date: target, range: null, type: null, measure: null });
  }, []);
  const handleFilterChange = useCallback((filter) => {
    setQuery((q) => ({ ...q, filter }));
    writeRunsQuery({ filter });
  }, []);
  // One refresh for the header, the progress panel and a start (F5): the live head and the view on screen.
  const refreshLive = runsLive.refresh;
  const refreshDay = day.refresh;
  const refreshCost = cost.refresh;
  const refreshTiming = timing.refresh;
  const refreshAll = useCallback(() => {
    refreshLive();
    if (query.view === 'day') refreshDay();
    else if (query.view === 'cost') refreshCost();
    else refreshTiming();
  }, [refreshLive, refreshDay, refreshCost, refreshTiming, query.view]);
  // The Start a run panel's in-flight flags and message live here, so closing it mid-request keeps both.
  const startState = useStartRunState();
  const hashOptions = useMemo(() => ({ parse: parseRunsHash, format: formatRunsHash, keepQuery: true }), []);
  const { selectedId: selection, select, clear } = useHashSelection(TAB, hashOptions);
  const panelRef = useRef(null);

  const starting = selection?.type === 'start';
  const isBatch = selection?.type === 'batch';
  const isCycle = selection?.type === 'cycle';
  // A nested batch is a job-run panel too; its cycle stays read (shared, so going back is instant).
  const jobId = selection?.type === 'job' || isBatch ? selection.id : null;
  let cycleId = null;
  if (isCycle) cycleId = selection.id;
  else if (isBatch) cycleId = selection.cycleId;
  const job = useJobRunDetail(jobId, runsLive.pollCount);
  const cycle = useCycleDetail(cycleId, { isPaused });
  const liveId = runsLive.meta?.liveCycleId ?? null;
  const live = useCycleDetail(liveId, { isPaused });
  const panelOpen = starting || jobId != null || isCycle;

  // Every refresh of the live head carries the server's running count, which ManageView shows beside Runs.
  useEffect(() => {
    if (runsLive.meta) onLiveCountChange?.(runsLive.meta.runningCount ?? 0);
    // Only a refresh reports: the callback's identity must not re-report.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runsLive.pollCount]);

  // A hash naming no job run or cycle (a pasted unknown id) leaves the bare tab address, replacing the entry.
  const notFound = job.notFound || (isCycle && cycle.notFound);
  useEffect(() => {
    if (!notFound) return;
    // A batch the server has no run for returns to its cycle, not to the bare tab.
    if (isBatch && !cycle.notFound) select({ type: 'cycle', id: cycleId }, { replace: true });
    else clear({ replace: true });
  }, [notFound, isBatch, cycleId, cycle.notFound, select, clear]);

  // The cycle's served name, when the Day on screen holds its row; the banner and panel fall back to the cycle type's.
  const cycleRow = (id) => day.data?.runs.find((row) => row.id === `cycle-${id}`) ?? null;

  // Dismissing a kept progress panel removes the thing that held focus, so focus goes to the Runs heading. Only a
  // Dismiss moves focus: a clean run clearing itself leaves it where it was.
  const headingRef = useRef(null);
  const focusHeading = useRef(false);
  const handleDismissRun = () => {
    focusHeading.current = true;
    onActiveRunClear();
  };
  useEffect(() => {
    if (!activeRunId && focusHeading.current) {
      focusHeading.current = false;
      headingRef.current?.focus();
    }
  }, [activeRunId]);

  const closePanel = useCallback(() => clear(), [clear]);

  // F7: nothing closes an open panel but the reader. A date change or a Day chip never does (it was opened
  // deliberately, and it is read by its own id, not from the day on screen).
  const detail = job.detail;

  // Back, Esc and the back control return a batch to its cycle; the x closes everything.
  const backToCycle = useCallback(() => {
    if (cycleId != null) select({ type: 'cycle', id: cycleId });
  }, [cycleId, select]);
  usePanelEscape({ active: panelOpen, panelRef, onEscape: isBatch ? backToCycle : closePanel });

  // Coming back from a batch (any route: the control, Esc, the browser's Back) puts focus on that batch's row.
  const previousSelection = useRef(null);
  const focusBatchRow = useRef(null);
  useEffect(() => {
    const previous = previousSelection.current;
    previousSelection.current = selection;
    if (previous?.type === 'batch' && selection?.type === 'cycle' && previous.cycleId === selection.id) {
      focusBatchRow.current = previous.id;
    }
  }, [selection]);
  useEffect(() => {
    const jobRunId = focusBatchRow.current;
    if (jobRunId == null || !isCycle || !cycle.detail) return;
    focusBatchRow.current = null;
    panelRef.current?.querySelector(`[data-job-run-id="${jobRunId}"]`)?.focus({ preventScroll: true });
  }, [isCycle, cycle.detail, selection]);

  // What the Start a run panel's handlers may do after the panel has gone: a start the reader closed the panel on, or
  // moved on from, must not close or replace whatever panel is open now.
  const startingRef = useRef(false);
  useEffect(() => {
    startingRef.current = starting;
  }, [starting]);
  const [toast, setToast] = useState(null);
  const clearToast = useCallback(() => setToast(null), []);
  const toastCount = useRef(0);

  // A start that returned a job run id: the progress panel on the list follows it (ManageView keeps the id), the
  // list's head reloads, and, while the Start panel is still the open one, the new run's own panel replaces it.
  const handleRunStarted = (jobRunId) => {
    onActiveRunChange(jobRunId);
    refreshAll();
    if (startingRef.current) select({ type: 'job', id: jobRunId });
  };

  // A start with no id (and the aurora forecast, once its modal finishes): reload the head, toast the line, and close
  // the Start panel if it is still the open one.
  const handleStartFinished = (message) => {
    refreshAll();
    // Not while the Operations pane is hidden (the reader is on the Map or Plan tab): the line would float over it.
    // The counter restarts the toast's timer when the same line comes round again.
    if (rootRef.current?.closest('[hidden]') == null) {
      toastCount.current += 1;
      setToast({ text: message, count: toastCount.current });
    }
    if (startingRef.current) closePanel();
  };

  // Closing Start a run puts focus back on the button that opened it, unless focus is already somewhere the reader
  // put it. A layout effect, so it runs before the shell's own fallback (the list heading) sees a focus-less page.
  const startButtonRef = useRef(null);
  const wasStarting = useRef(false);
  useLayoutEffect(() => {
    const active = document.activeElement;
    if (wasStarting.current && !starting && (!active || active === document.body)) {
      startButtonRef.current?.focus({ preventScroll: true });
    }
    wasStarting.current = starting;
  }, [starting]);

  // A Day row's "Open run detail": a cycle opens the cycle panel, a job run its job-run panel.
  function openDetail(row) {
    if (row.kind === 'CYCLE') select({ type: 'cycle', id: row.cycleId });
    else select({ type: 'job', id: row.jobRunId });
  }

  const progress = activeRunId ? (
    <RunProgressPanel
      // key: starting another run while a finished one is kept must replace the panel, not hand a new run id to a
      // panel still holding the old run's tasks, summary and failure line.
      key={activeRunId}
      jobRunId={activeRunId}
      onComplete={refreshAll}
      onAutoClear={onActiveRunClear}
      onDismiss={handleDismissRun}
    />
  ) : null;
  // Below 1000px the open job-run panel covers the list, so the run it names carries its own live progress inside it.
  // Moved, never duplicated: one mount, hence one subscription, wherever it sits.
  // Not while the panel is still loading or failed (it shows no progress then, and a move would re-open the stream).
  const progressInPanel = !isSplit && jobId != null && jobId === activeRunId && !job.error && !job.notFound;

  let panel = null;
  if (starting) {
    panel = (
      <StartRunPanel
        onRunStarted={handleRunStarted}
        onFinished={handleStartFinished}
        onClose={closePanel}
        runState={startState}
      />
    );
  } else if (isCycle) {
    panel = (
      <CyclePanel
        key={`cycle-${cycleId}`}
        id={cycleId}
        detail={cycle.detail}
        item={cycleRow(cycleId)}
        loading={cycle.loading}
        error={cycle.error}
        notFound={cycle.notFound}
        fetchedAt={cycle.fetchedAt}
        onRefresh={cycle.refresh}
        onClose={closePanel}
        onOpenBatch={(jobRunId) => select({ type: 'batch', cycleId, id: jobRunId })}
      />
    );
  } else if (jobId != null) {
    panel = (
      <JobRunPanel
        key={jobId}
        detail={detail}
        loading={job.loading}
        error={job.error}
        notFound={job.notFound}
        isActiveRun={jobId === activeRunId}
        onClose={closePanel}
        backLabel={isBatch ? `Cycle #${cycleId}` : undefined}
        onBack={isBatch ? backToCycle : undefined}
        progress={progressInPanel ? progress : null}
      />
    );
  }

  let panelLabel = 'Run';
  if (starting) panelLabel = 'Start a run';
  else if (isCycle) panelLabel = `Cycle #${cycleId}`;
  else if (jobId != null) panelLabel = `Job run ${jobId}`;
  // A batch keeps its cycle's key, so closing everything returns focus to the cycle's row; focusKey tells the shell
  // that the body changed (cycle to batch and back) and the title takes focus again.
  let selectionKey = null;
  let focusKey;
  if (starting) selectionKey = 'start';
  else if (cycleId != null) {
    selectionKey = `cycle-${cycleId}`;
    focusKey = isBatch ? `batch-${jobId}` : 'cycle';
  } else if (jobId != null) selectionKey = `job-${jobId}`;
  // The Day row whose panel is open (a batch marks its cycle's row, as it keeps its cycle's focus key).
  let selectedRowKey = null;
  if (cycleId != null) selectedRowKey = `cycle-${cycleId}`;
  else if (jobId != null) selectedRowKey = `job-${jobId}`;

  const liveItem = liveId != null ? cycleRow(liveId) : null;
  const liveStrip = liveId != null && live.detail?.run?.status === 'RUNNING' ? (
    <RunsLiveStrip detail={live.detail} item={liveItem} onOpen={() => select({ type: 'cycle', id: liveId })} />
  ) : null;

  // On a phone the layout's back bar is the only back row: from a batch it names the cycle, from a cycle it carries
  // Refresh (frame 12).
  const backLabel = isBatch ? `Cycle #${cycleId}` : 'Runs';
  const backAction = isCycle ? (
    <button type="button" className={`${textButtonClass} min-h-11 px-3`} onClick={cycle.refresh} data-testid="cycle-back-refresh">
      Refresh
    </button>
  ) : null;


  // What sits in the list column: the header, the stack above the views (progress, error, the live banner: on every
  // view), the view tabs, and the view itself.
  const today = day.data?.isToday ? day.data.date : ukDateStr(new Date());
  const shownDay = day.data?.date ?? query.date ?? today;
  // "Updated" follows the view on screen: a view still loading shows none (never the last one's).
  const viewUpdatedAt = { day: day.updatedAt, cost: cost.updatedAt, timing: timing.updatedAt }[query.view];
  let viewBody;
  if (query.view === 'day') {
    viewBody = (
      <RunsDayView
        day={day}
        date={query.date}
        onDateChange={handleDateChange}
        filter={query.filter}
        onFilterChange={handleFilterChange}
        selectedKey={selectedRowKey}
        panelOpen={panelOpen}
        onOpenDetail={openDetail}
      />
    );
  } else if (query.view === 'cost') {
    viewBody = (
      <RunsCostView
        cost={cost}
        range={costRange}
        onRangeChange={(range) => handleParams({ range })}
        isolate={costIsolate}
        onIsolate={(type) => handleParams({ type })}
        onOpenDay={handleOpenDay}
      />
    );
  } else {
    viewBody = (
      <RunsTimingView
        timing={timing}
        type={timingType}
        onTypeChange={(type) => handleParams({ type, measure: 'cycle' })}
        range={timingRange}
        onRangeChange={(range) => handleParams({ range })}
        measure={timingMeasure}
        onMeasureChange={(measure) => handleParams({ measure })}
        onOpenDay={handleOpenDay}
      />
    );
  }

  return (
    <div
      ref={rootRef}
      className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible"
      data-testid="runs-view"
    >
      <DataSplitLayout
        list={(
          <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="runs-list-pane">
            <RunsHeader
              headingRef={headingRef}
              startButtonRef={startButtonRef}
              updatedAt={viewUpdatedAt}
              refreshes={query.view === 'day' && day.data?.isToday !== false}
              onRefresh={refreshAll}
              onStart={() => select({ type: 'start' })}
            />
            <RunsTopStack
              progress={progressInPanel ? null : progress}
              error={day.error && query.view === 'day' ? null : runsLive.error}
              liveStrip={liveStrip}
            />
            <RunsViewTabs view={query.view} onChange={handleViewChange} dayWord={dayTabWord(shownDay, today)} />
            <div id={PANEL_ID} role="tabpanel" aria-labelledby={tabId(query.view)} className="flex flex-col gap-4">
              {viewBody}
            </div>
          </div>
        )}
        panel={panel}
        onClose={closePanel}
        panelLabel={panelLabel}
        backLabel={backLabel}
        onBack={isBatch ? backToCycle : undefined}
        backAction={backAction}
        panelRef={panelRef}
        selectionKey={selectionKey}
        focusKey={focusKey}
        rowTestIdPrefix="runs"
        testId="runs-split"
      />
      <SubRegionToast message={toast?.text ?? null} nonce={toast?.count} onDone={clearToast} />
    </div>
  );
}

RunsView.propTypes = {
  activeRunId: PropTypes.number,
  onActiveRunChange: PropTypes.func.isRequired,
  onActiveRunClear: PropTypes.func.isRequired,
  onLiveCountChange: PropTypes.func,
};
