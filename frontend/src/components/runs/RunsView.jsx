import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import DataSplitLayout from '../data/DataSplitLayout.jsx';
import RunProgressPanel from '../RunProgressPanel';
import RunsListPane from './RunsListPane.jsx';
import JobRunPanel from './JobRunPanel.jsx';
import StartRunPanel from './StartRunPanel.jsx';
import useCloseWhenFilteredOut from '../../hooks/useCloseWhenFilteredOut.js';
import useHashSelection from '../../hooks/useHashSelection.js';
import useJobRunDetail from '../../hooks/useJobRunDetail.js';
import usePanelEscape from '../../hooks/usePanelEscape.js';
import useRunsList from '../../hooks/useRunsList.js';
import { needsAttentionStatus } from '../../utils/runStatus.js';
import { runRowId } from '../../utils/runsView.js';

/** The Manage tab key; the hash adds {@code job/{id}} or {@code start}. */
const TAB = 'runs';

/**
 * Reads the segments after {@code #manage/runs}: {@code job/{id}} opens a job-run panel, {@code start} the Start a
 * run panel; anything else (the bare tab, a cycle path R2b has not built yet) names no panel. Module-level so the
 * hash hook's listener stays stable.
 */
function parseRunsHash(segments) {
  if (segments.length === 1 && segments[0] === 'start') return { type: 'start' };
  if (segments.length === 2 && segments[0] === 'job' && /^\d+$/.test(segments[1])) {
    return { type: 'job', id: Number(segments[1]) };
  }
  return null;
}

/** The path after the tab for a selection, the inverse of {@link parseRunsHash}. */
function formatRunsHash(selection) {
  return selection.type === 'start' ? 'start' : `job/${selection.id}`;
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

/** A job run's id for the close-when-filtered check; module-level so the hook's dependencies stay stable. */
const itemId = (item) => item.id;

/**
 * Operations → Runs → Runs (runs-redesign-plan.md §5.2, R2a): the merged list of pipeline cycles and job runs on the
 * shared Data list-and-panel shell. The server merges, groups, names and statuses every row (R-D1); this view asks
 * and renders.
 *
 * <p>A job-run row opens its panel ({@code #manage/runs/job/{id}}); a cycle row, until R2b builds the cycle panel,
 * opens the Pipeline Runs tab on that cycle ({@code #manage/pipelineruns/{id}}). Start a run opens
 * {@code #manage/runs/start}, today's trigger card lifted as it stands (R-D4).
 *
 * <p>The run {@code RunProgressPanel} follows ({@code activeRunId}, kept in ManageView so it survives leaving the tab)
 * draws above the list rather than inside the Start panel, so closing that panel never drops it. Every clock read in
 * this view is the real clock (R-D7): Operations never follows an admin's Rewind.
 *
 * @param {object} props
 * @param {number|null} [props.activeRunId] the run whose progress is followed, kept by ManageView
 * @param {(jobRunId: number) => void} props.onActiveRunChange sets that run
 * @param {() => void} props.onActiveRunClear clears it
 */
export default function RunsView({ activeRunId = null, onActiveRunChange, onActiveRunClear }) {
  const [filters, setFilters] = useState(filtersFromHash);
  const list = useRunsList(filters);
  const hashOptions = useMemo(() => ({ parse: parseRunsHash, format: formatRunsHash, keepQuery: true }), []);
  const { selectedId: selection, select, clear } = useHashSelection(TAB, hashOptions);
  const panelRef = useRef(null);

  const starting = selection?.type === 'start';
  const jobId = selection?.type === 'job' ? selection.id : null;
  const job = useJobRunDetail(jobId, list.pollCount);
  const panelOpen = starting || jobId != null;

  // A hash naming no job run (a pasted unknown id) leaves the bare tab address, replacing the entry.
  const { notFound } = job;
  useEffect(() => {
    if (notFound) clear({ replace: true });
  }, [notFound, clear]);

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

  // The open job run closes only when ITS OWN group and status no longer match the kind and attention filters, read
  // from the served detail, never from the loaded page (the page may simply not hold it). A range change never
  // closes it: the filter key leaves the range out.
  const detail = job.detail;
  const stillMatches = detail == null
    || ((filters.kind === 'ALL' || detail.group === filters.kind)
      && (!filters.attention || needsAttentionStatus(detail.status)));
  const matching = useMemo(() => (jobId != null && stillMatches ? [{ id: jobId }] : []), [jobId, stillMatches]);
  useCloseWhenFilteredOut({
    selectedId: jobId,
    items: matching,
    getId: itemId,
    filterKey: `${filters.kind}|${filters.attention}`,
    onClose: closePanel,
  });

  usePanelEscape({ active: panelOpen, panelRef, onEscape: closePanel });

  const filterActions = useMemo(() => ({
    setRange: (range) => setFilters((f) => ({ ...f, range })),
    setKind: (kind) => setFilters((f) => ({ ...f, kind })),
    toggleAttention: () => setFilters((f) => ({ ...f, attention: !f.attention })),
  }), []);

  function openRow(item) {
    if (item.kind === 'CYCLE') {
      // R2a: the cycle panel is R2b's. Until then a cycle opens on the Pipeline Runs tab.
      window.location.hash = `manage/pipelineruns/${item.id}`;
      return;
    }
    select({ type: 'job', id: item.id });
  }

  let panel = null;
  if (starting) {
    panel = (
      <StartRunPanel
        onActiveRunChange={onActiveRunChange}
        onStarted={list.refresh}
        onClose={closePanel}
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
      />
    );
  }

  let panelLabel = 'Run';
  if (starting) panelLabel = 'Start a run';
  else if (jobId != null) panelLabel = `Job run ${jobId}`;
  let selectionKey = null;
  if (starting) selectionKey = 'start';
  else if (jobId != null) selectionKey = `job-${jobId}`;
  const selectedRowKey = jobId != null ? runRowId({ kind: 'JOB', id: jobId }) : null;

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
    <div className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible" data-testid="runs-view">
      <DataSplitLayout
        list={(
          <RunsListPane
            headingRef={headingRef}
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
          />
        )}
        panel={panel}
        onClose={closePanel}
        panelLabel={panelLabel}
        backLabel="Runs"
        panelRef={panelRef}
        selectionKey={selectionKey}
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
};
