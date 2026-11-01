import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import DataSplitLayout from '../data/DataSplitLayout.jsx';
import RunProgressPanel from '../RunProgressPanel';
import RunsListPane from './RunsListPane.jsx';
import JobRunPanel from './JobRunPanel.jsx';
import CyclePanel from './CyclePanel.jsx';
import RunsLiveStrip from './RunsLiveStrip.jsx';
import StartRunPanel, { useStartRunState } from './StartRunPanel.jsx';
import { useIsSplitLayout } from '../../hooks/useIsSplitLayout.js';
import useCloseWhenFilteredOut from '../../hooks/useCloseWhenFilteredOut.js';
import useHashSelection from '../../hooks/useHashSelection.js';
import useCycleDetail from '../../hooks/useCycleDetail.js';
import useJobRunDetail from '../../hooks/useJobRunDetail.js';
import usePanelEscape from '../../hooks/usePanelEscape.js';
import useRunsList from '../../hooks/useRunsList.js';
import { needsAttentionStatus } from '../../utils/runStatus.js';
import { runRowId } from '../../utils/runsView.js';
import { textButtonClass } from '../users/userFormClasses.js';

/** The Manage tab key; the hash adds {@code job/{id}}, {@code cycle/{id}[/job/{id}]} or {@code start}. */
const TAB = 'runs';

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

/** The hash's range words and the server's. */
const RANGES = { today: 'TODAY', week: 'WEEK', '7d': 'WEEK', month: 'MONTH', '30d': 'MONTH' };
const KINDS = new Set(['ALL', 'CYCLES', 'BATCHES', 'TIDE', 'BRIEFING', 'AURORA', 'OTHER']);

/**
 * The first filters, from an inbound {@code ?range=&kind=&attention=} query (a link from elsewhere, like Locations'
 * {@code ?region=}). Read once and never written back, so a chip press adds no history entry. {@code aurora} in the
 * hash is {@code AURORA} on the wire.
 */
function filtersFromHash() {
  const query = new URLSearchParams(window.location.hash.replace('#', '').split('?')[1] ?? '');
  const kind = (query.get('kind') ?? '').toUpperCase();
  const attention = (query.get('attention') ?? '').toLowerCase();
  return {
    range: RANGES[(query.get('range') ?? '').toLowerCase()] ?? 'WEEK',
    kind: KINDS.has(kind) ? kind : 'ALL',
    attention: attention === 'true' || attention === '1',
  };
}

/** Drops the hash's {@code ?query} in place (no history entry), once the reader has taken the filters over. */
function dropInboundQuery() {
  const { hash } = window.location;
  if (!hash.includes('?')) return;
  window.history.replaceState(window.history.state, '', hash.split('?')[0]);
}

/** An open item's key for the close-when-filtered check; module-level so the hook's dependencies stay stable. */
const itemId = (item) => item.id;

/**
 * Operations → Runs → Runs (runs-redesign-plan.md §5.2, R2a): the merged list of pipeline cycles and job runs on the
 * shared Data list-and-panel shell. The server merges, groups, names and statuses every row (R-D1); this view asks
 * and renders.
 *
 * <p>A job-run row opens its panel ({@code #manage/runs/job/{id}}); a cycle row opens the cycle panel
 * ({@code #manage/runs/cycle/{id}}), whose batch rows open the job-run panel nested under it
 * ({@code .../cycle/{id}/job/{jobRunId}}). While the server names a running cycle a live strip sits above the summary;
 * it and the open cycle panel share one fetch ({@code useCycleDetail}). Start a run opens {@code #manage/runs/start},
 * today's trigger card lifted as it stands (R-D4).
 *
 * <p>The run {@code RunProgressPanel} follows ({@code activeRunId}, kept in ManageView so it survives leaving the tab)
 * draws above the list rather than inside the Start panel, so closing that panel never drops it. Every clock read in
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
  const [filters, setFilters] = useState(filtersFromHash);
  const rootRef = useRef(null);
  // The Operations pane stays mounted, hidden, while the reader is on the Map or Plan tab: no polling then.
  const isPaused = useCallback(() => rootRef.current?.closest('[hidden]') != null, []);
  const list = useRunsList(filters, { isPaused });
  const isSplit = useIsSplitLayout();
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
  const job = useJobRunDetail(jobId, list.pollCount);
  const cycle = useCycleDetail(cycleId, { isPaused });
  const liveId = list.meta?.liveCycleId ?? null;
  const live = useCycleDetail(liveId, { isPaused });
  const panelOpen = starting || jobId != null || isCycle;

  // Every refresh of the list carries the server's running count, which ManageView shows beside Runs.
  useEffect(() => {
    if (list.meta) onLiveCountChange?.(list.meta.runningCount ?? 0);
    // Only a refresh reports: the callback's identity must not re-report.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.pollCount]);

  // A hash naming no job run or cycle (a pasted unknown id) leaves the bare tab address, replacing the entry.
  const notFound = job.notFound || (isCycle && cycle.notFound);
  useEffect(() => {
    if (!notFound) return;
    // A batch the server has no run for returns to its cycle, not to the bare tab.
    if (isBatch && !cycle.notFound) select({ type: 'cycle', id: cycleId }, { replace: true });
    else clear({ replace: true });
  }, [notFound, isBatch, cycleId, cycle.notFound, select, clear]);

  const cycleRow = (id) => list.items.find((item) => item.kind === 'CYCLE' && item.id === id) ?? null;

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

  // The open item closes only when ITS OWN group and status no longer match the kind and attention filters, read
  // from the served detail, never from the loaded page (the page may simply not hold it). A nested batch is tested
  // by its parent cycle. A range change never closes it: the filter key leaves the range out.
  const detail = job.detail;
  let openKey = null;
  let openLoaded = false;
  let openGroup = null;
  let openStatus = null;
  if (selection?.type === 'job') {
    openKey = `job-${jobId}`;
    openLoaded = detail != null;
    openGroup = detail?.group;
    openStatus = detail?.status;
  } else if (cycleId != null) {
    openKey = `cycle-${cycleId}`;
    openLoaded = cycle.detail != null;
    openGroup = 'CYCLES';
    openStatus = cycle.detail?.run?.status;
  }
  const stillMatches = !openLoaded
    || ((filters.kind === 'ALL' || openGroup === filters.kind)
      && (!filters.attention || needsAttentionStatus(openStatus)));
  const matching = useMemo(() => (openKey != null && stillMatches ? [{ id: openKey }] : []), [openKey, stillMatches]);
  useCloseWhenFilteredOut({
    selectedId: openKey,
    items: matching,
    getId: itemId,
    filterKey: `${filters.kind}|${filters.attention}`,
    onClose: closePanel,
  });

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

  // The first press of any filter control retires the inbound ?range=&kind=&attention=: from then on the address
  // must not name filters the screen no longer shows (a reload or a shared link would bring them back).
  const filterActions = useMemo(() => {
    const touched = (update) => (value) => {
      dropInboundQuery();
      setFilters(update(value));
    };
    return {
      setRange: touched((range) => (f) => ({ ...f, range })),
      setKind: touched((kind) => (f) => ({ ...f, kind })),
      toggleAttention: touched(() => (f) => ({ ...f, attention: !f.attention })),
    };
  }, []);

  // A run started from the Start panel is followed by the progress panel on the list. Below 1000px the panel covers
  // the list (overlay or a full page), so it closes and the progress is in view. One RunProgressPanel mount only.
  const handleActiveRunChange = (jobRunId) => {
    onActiveRunChange(jobRunId);
    if (!isSplit) closePanel();
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

  function openRow(item) {
    if (item.kind === 'CYCLE') {
      select({ type: 'cycle', id: item.id });
      return;
    }
    select({ type: 'job', id: item.id });
  }

  let panel = null;
  if (starting) {
    panel = (
      <StartRunPanel
        onActiveRunChange={handleActiveRunChange}
        onStarted={list.refresh}
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
  let selectedRowKey = null;
  if (cycleId != null) selectedRowKey = runRowId({ kind: 'CYCLE', id: cycleId });
  else if (jobId != null) selectedRowKey = runRowId({ kind: 'JOB', id: jobId });

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

  const progress = activeRunId ? (
    <RunProgressPanel
      // key: starting another run while a finished one is kept must replace the panel, not hand a new run id to a
      // panel still holding the old run's tasks, summary and failure line.
      key={activeRunId}
      jobRunId={activeRunId}
      onComplete={list.refresh}
      onAutoClear={onActiveRunClear}
      onDismiss={handleDismissRun}
    />
  ) : null;

  return (
    <div
      ref={rootRef}
      className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible"
      data-testid="runs-view"
    >
      <DataSplitLayout
        list={(
          <RunsListPane
            headingRef={headingRef}
            startButtonRef={startButtonRef}
            items={list.items}
            meta={list.meta}
            loading={list.loading}
            loadingMore={list.loadingMore}
            error={list.error}
            updatedAt={list.updatedAt}
            hasMore={list.nextCursor != null}
            filters={filters}
            filterActions={filterActions}
            selectedKey={selectedRowKey}
            onSelect={openRow}
            onLoadMore={list.loadMore}
            onRefresh={list.refresh}
            onStart={() => select({ type: 'start' })}
            progress={progress}
            liveStrip={liveStrip}
          />
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
    </div>
  );
}

RunsView.propTypes = {
  activeRunId: PropTypes.number,
  onActiveRunChange: PropTypes.func.isRequired,
  onActiveRunClear: PropTypes.func.isRequired,
  onLiveCountChange: PropTypes.func,
};
