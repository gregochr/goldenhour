import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  fetchSchedulerJobs,
  updateJobSchedule,
  pauseJob,
  resumeJob,
  fetchSolarPreview,
} from '../api/schedulerApi.js';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import SchedulerListPane from './scheduler/SchedulerListPane.jsx';
import SchedulerJobPanel from './scheduler/SchedulerJobPanel.jsx';
import { ScheduleSwitchDialog } from './SolarScheduleControls.jsx';
import useCloseWhenFilteredOut from '../hooks/useCloseWhenFilteredOut.js';
import useHashSelection from '../hooks/useHashSelection.js';
import usePanelEscape from '../hooks/usePanelEscape.js';
import { useVisiblePoll } from '../hooks/useVisiblePoll.js';
import {
  SCHEDULE_CRON,
  SCHEDULE_FIXED_DELAY,
  describeCron,
  jobMatchesFilters,
  sortJobs,
} from '../utils/schedulerView.js';

/** Polling interval for refreshing job statuses and the solar preview. */
const POLL_INTERVAL_MS = 30_000;

/** The Manage tab key; the hash adds the open job's key ({@code #manage/scheduler/near_term_batch_evaluation}). */
const TAB = 'scheduler';

/**
 * Reads the segments after {@code #manage/scheduler}: one job key ({@code [a-z0-9_]+}) opens that job's panel;
 * anything else (the bare tab) names none. Module-level so the hash hook's listener stays stable.
 */
function parseSchedulerHash(segments) {
  return segments.length === 1 && /^[a-z0-9_]+$/.test(segments[0]) ? segments[0] : null;
}

/** The path after the tab for a selection, the inverse of {@link parseSchedulerHash}. */
const formatSchedulerHash = (jobKey) => jobKey;

const HASH_OPTIONS = { parse: parseSchedulerHash, format: formatSchedulerHash };

/** The default filters (every job) and sort (next fire, soonest first, paused and off last). */
const NO_FILTERS = { type: 'all', status: null };
const DEFAULT_SORT = { key: 'next', dir: 'asc' };

/** An open job's key for the close-when-filtered check; module-level so the hook's dependencies stay stable. */
const jobId = (job) => job.jobKey;

/**
 * Operations → Runs → Scheduler (runs-redesign-plan.md §5.5, R4): the scheduled jobs on the shared Data
 * list-and-panel shell. A row opens that job's panel ({@code #manage/scheduler/{jobKey}}); everything that used to
 * sit in a card's row (Edit, Pause or Resume, Run now, the sun-relative switch) lives in the panel.
 *
 * <p>The server serves each job's status, next fire and, for the five jobs it can attribute runs to, its last run.
 * The solar preview feeds the panel's Next fires. Both are re-read every 30 seconds, and a poll tick is skipped
 * while the Operations pane is hidden (the pane stays mounted when the reader is on the Map or Plan tab). Each loader
 * numbers its requests and applies only the latest one issued: a slow earlier response (a poll still in flight when a
 * switch reloads) must not overwrite a newer one. Every clock read in this view is the real clock (R-D7).
 */
export default function SchedulerView() {
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [preview, setPreview] = useState(null);
  const [previewFailed, setPreviewFailed] = useState(false);
  const [editingJob, setEditingJob] = useState(null);
  const [switchingJob, setSwitchingJob] = useState(null);
  const [filters, setFilters] = useState(NO_FILTERS);
  const [sort, setSort] = useState(DEFAULT_SORT);
  const [pollCount, setPollCount] = useState(0);

  const jobsRequest = useRef(0);
  const previewRequest = useRef(0);
  const rootRef = useRef(null);
  const panelRef = useRef(null);
  const headingRef = useRef(null);
  const loadedOnce = useRef(false);

  const { selectedId: selectedKey, select, clear } = useHashSelection(TAB, HASH_OPTIONS);

  const loadPreview = useCallback(async () => {
    const id = ++previewRequest.current;
    try {
      const data = await fetchSolarPreview();
      if (id !== previewRequest.current) return;
      setPreview(data);
      setPreviewFailed(false);
    } catch {
      if (id !== previewRequest.current) return;
      setPreviewFailed(true);
    }
  }, []);

  const loadJobs = useCallback(async () => {
    const id = ++jobsRequest.current;
    try {
      const data = await fetchSchedulerJobs();
      if (id !== jobsRequest.current) return;
      setJobs(data);
      setError(null);
      loadedOnce.current = true;
      setLoaded(true);
    } catch {
      if (id !== jobsRequest.current) return;
      setError('Failed to load scheduler jobs');
    } finally {
      if (id === jobsRequest.current) setLoading(false);
    }
  }, []);

  // The poll tick, and the first load: skipped only for a tick on a screen already loaded, while the Operations pane
  // is hidden. It ticks the count the open panel's recent runs refetch on.
  const refresh = useCallback(async () => {
    if (loadedOnce.current && rootRef.current?.closest('[hidden]') != null) return;
    await Promise.all([loadJobs(), loadPreview()]);
    setPollCount((count) => count + 1);
  }, [loadJobs, loadPreview]);
  useVisiblePoll(refresh, POLL_INTERVAL_MS);

  const selectedJob = useMemo(
    () => (selectedKey == null ? null : jobs.find((job) => job.jobKey === selectedKey) ?? null),
    [jobs, selectedKey],
  );
  const panelOpen = selectedJob != null;

  // A hash naming no job (a pasted unknown key) leaves the bare tab address, replacing the entry. Only once the jobs
  // have loaded: a failed first load says nothing about what exists.
  useEffect(() => {
    if (loaded && selectedKey != null && selectedJob == null) clear({ replace: true });
  }, [loaded, selectedKey, selectedJob, clear]);

  // A different panel starts with no editor open and no stale action failure (state adjusted while rendering, the
  // pattern React documents for state that follows a prop).
  const [panelKey, setPanelKey] = useState(selectedKey);
  if (panelKey !== selectedKey) {
    setPanelKey(selectedKey);
    setEditingJob(null);
    setActionError(null);
  }

  const visibleJobs = useMemo(
    () => sortJobs(jobs.filter((job) => jobMatchesFilters(job, filters)), sort),
    [jobs, filters, sort],
  );

  const closePanel = useCallback(() => clear(), [clear]);
  useCloseWhenFilteredOut({
    selectedId: selectedKey,
    items: visibleJobs,
    getId: jobId,
    filterKey: `${filters.type}|${filters.status}`,
    onClose: closePanel,
  });

  const editing = selectedJob != null && editingJob === selectedJob.jobKey;
  const cancelEdit = useCallback(() => setEditingJob(null), []);
  // Esc steps back from the editor first, then closes the panel.
  usePanelEscape({ active: panelOpen, panelRef, onEscape: editing ? cancelEdit : closePanel });

  const handleType = (type) => setFilters((f) => ({ ...f, type }));
  const handleStatus = (status) => setFilters((f) => ({ ...f, status: f.status === status ? null : status }));
  const handleSort = (key) => setSort((s) => (s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }));
  const handleSortChip = (key, dir) => setSort({ key, dir });

  const handlePause = async (jobKey) => {
    setActionError(null);
    try {
      await pauseJob(jobKey);
      await loadJobs();
    } catch {
      setActionError(`Failed to pause ${jobKey}`);
    }
  };

  const handleResume = async (jobKey) => {
    setActionError(null);
    try {
      await resumeJob(jobKey);
      await loadJobs();
    } catch {
      setActionError(`Failed to resume ${jobKey}`);
    }
  };

  const handleSolarSaved = async () => {
    setEditingJob(null);
    await Promise.all([loadJobs(), loadPreview()]);
  };

  const handleSwitched = async () => {
    setSwitchingJob(null);
    await Promise.all([loadJobs(), loadPreview()]);
  };

  // Rejects on failure, so the inline form can say so beside its field.
  const handleInlineSave = async (job, value) => {
    // Only the two types that have an inline text edit may reach here; a solar or unrecognised job never sends a fixed
    // delay (a solar job has none, and 0 would be rejected or worse).
    let payload;
    if (job.scheduleType === SCHEDULE_CRON) {
      payload = { cronExpression: value };
    } else if (job.scheduleType === SCHEDULE_FIXED_DELAY) {
      payload = { fixedDelayMs: Number(value) };
    } else {
      return;
    }
    await updateJobSchedule(job.jobKey, payload);
    setEditingJob(null);
    await loadJobs();
  };

  const previewFor = (jobKey) => preview?.jobs?.find((p) => p.jobKey === jobKey) ?? null;
  const switchTarget = switchingJob ? jobs.find((j) => j.jobKey === switchingJob) : null;

  const panel = selectedJob ? (
    <SchedulerJobPanel
      key={selectedJob.jobKey}
      job={selectedJob}
      preview={preview}
      previewFailed={previewFailed}
      editing={editing}
      refreshKey={pollCount}
      actionError={actionError}
      onClose={closePanel}
      onEditStart={() => setEditingJob(selectedJob.jobKey)}
      onEditCancel={cancelEdit}
      onSolarSaved={handleSolarSaved}
      onPartial={loadJobs}
      onInlineSave={handleInlineSave}
      onSwitch={() => setSwitchingJob(selectedJob.jobKey)}
      onPause={handlePause}
      onResume={handleResume}
      onActionError={setActionError}
    />
  ) : null;

  return (
    <div
      ref={rootRef}
      className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible"
      data-testid="scheduler-view"
    >
      <DataSplitLayout
        list={(
          <SchedulerListPane
            headingRef={headingRef}
            allJobs={jobs}
            jobs={visibleJobs}
            loading={loading}
            error={error}
            filters={filters}
            onType={handleType}
            onStatus={handleStatus}
            sort={sort}
            onSort={handleSort}
            onSortChip={handleSortChip}
            selectedKey={selectedJob?.jobKey ?? null}
            onSelect={(job) => select(job.jobKey)}
          />
        )}
        panel={panel}
        onClose={closePanel}
        panelLabel={selectedJob ? `${selectedJob.displayName} job` : 'Job'}
        backLabel="Scheduler"
        panelRef={panelRef}
        selectionKey={selectedJob?.jobKey ?? null}
        rowTestIdPrefix="scheduler"
        testId="scheduler-split"
      />

      {switchTarget && (
        <ScheduleSwitchDialog
          job={switchTarget}
          previewJob={previewFor(switchTarget.jobKey)}
          cronWords={describeCron(switchTarget.cronExpression)}
          onCancel={() => setSwitchingJob(null)}
          onSwitched={handleSwitched}
        />
      )}
    </div>
  );
}
