import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  fetchSchedulerJobs,
  fetchSchedulerSummary,
  updateJobSchedule,
  fetchSolarPreview,
} from '../api/schedulerApi.js';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import AllJobsTab from './scheduler/AllJobsTab.jsx';
import SchedulerTabs, { SCHEDULER_PANEL_ID, schedulerTabId } from './scheduler/SchedulerTabs.jsx';
import SchedulerJobPanel from './scheduler/SchedulerJobPanel.jsx';
import { ScheduleSwitchDialog } from './SolarScheduleControls.jsx';
import SubRegionToast from './subregions/SubRegionToast.jsx';
import useCloseWhenFilteredOut from '../hooks/useCloseWhenFilteredOut.js';
import useSchedulerActions from '../hooks/useSchedulerActions.js';
import useHashSelection from '../hooks/useHashSelection.js';
import usePanelEscape from '../hooks/usePanelEscape.js';
import { useVisiblePoll } from '../hooks/useVisiblePoll.js';
import {
  SCHEDULE_CRON,
  SCHEDULE_FIXED_DELAY,
  STATUS_PAUSED,
  describeCron,
  formatClock,
  getUKTimezoneLabel,
  jobMatchesFilters,
  jobMatchesSearch,
} from '../utils/schedulerView.js';
import { savedMessage } from '../utils/schedulerEdit.js';
import { isSchedulerHash, readSchedulerQuery, writeSchedulerQuery } from '../utils/schedulerQuery.js';

/** Polling interval for refreshing job statuses, the summary and the solar preview. */
const POLL_INTERVAL_MS = 30_000;

/** The Manage tab key; the hash adds the open job's key ({@code #manage/scheduler/near_term_batch_evaluation}). */
const TAB = 'scheduler';

/**
 * Reads the segments after {@code #manage/scheduler}: one job key ({@code [a-z0-9_]+}) opens that job's panel;
 * anything else (the bare tab) names none. The tab and date ride the query ({@code ?view=all}), not the path
 * (utils/schedulerQuery.js). Module-level so the hash hook's listener stays stable.
 */
function parseSchedulerHash(segments) {
  return segments.length === 1 && /^[a-z0-9_]+$/.test(segments[0]) ? segments[0] : null;
}

/** The path after the tab for a selection, the inverse of {@link parseSchedulerHash}. */
const formatSchedulerHash = (jobKey) => jobKey;

/** {@code keepQuery}: opening and closing a panel must keep {@code ?view=&date=}. */
const HASH_OPTIONS = { parse: parseSchedulerHash, format: formatSchedulerHash, keepQuery: true };

/** The default filters: every job, no chip on. */
const NO_FILTERS = { type: 'all', status: null, claude: false, failed: false };

/** An open job's key for the close-when-filtered check; module-level so the hook's dependencies stay stable. */
const jobId = (job) => job.jobKey;

/** The page meta line: "{n} jobs · UK time (GMT) · {n} paused · next scheduled: {name} at {HH:MM}". */
function metaText(jobs, summary) {
  const total = summary?.total ?? jobs.length;
  const paused = summary?.byStatus?.[STATUS_PAUSED] ?? jobs.filter((job) => job.status === STATUS_PAUSED).length;
  const parts = [`${total} ${total === 1 ? 'job' : 'jobs'}`, `UK time (${getUKTimezoneLabel()})`, `${paused} paused`];
  const next = summary?.nextScheduled;
  if (next) parts.push(`next scheduled: ${next.displayName} at ${formatClock(next.at)}`);
  return parts.join(' · ');
}

/**
 * Operations → Runs → Scheduler (scheduler-redesign-plan.md S4a): the header (title and a meta line from the served
 * summary), two tabs (Day's jobs, All jobs) and, over either, the job's panel as a 520px drawer
 * ({@code DataSplitLayout mode="overlay"}). A row opens that job's panel ({@code #manage/scheduler/{jobKey}}); the
 * tab and the Day's date ride the hash's query ({@code ?view=day|all&date=}), written with {@code replaceState}.
 *
 * <p>The server serves each job's status, next fire, catalogue entry, last fire and next fires, plus a summary
 * (counts and the soonest scheduled fire). The solar preview feeds the panel's Next fires and the list's "today" time.
 * All three are re-read every 30 seconds, and a poll tick is skipped while the Operations pane is hidden (the pane
 * stays mounted when the reader is on the Map or Plan tab). Each loader numbers its requests and applies only the
 * latest one issued: a slow earlier response (a poll still in flight when a switch reloads) must not overwrite a
 * newer one. Every clock read in this view is the real clock (R-D7).
 */
export default function SchedulerView() {
  const [jobs, setJobs] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(null);
  const [preview, setPreview] = useState(null);
  const [previewFailed, setPreviewFailed] = useState(false);
  const [editingJob, setEditingJob] = useState(null);
  const [toast, setToast] = useState({ message: null, nonce: 0 });
  const [switchingJob, setSwitchingJob] = useState(null);
  const [filters, setFilters] = useState(NO_FILTERS);
  const [search, setSearch] = useState('');
  const [view, setView] = useState(() => readSchedulerQuery().view);

  const jobsRequest = useRef(0);
  const summaryRequest = useRef(0);
  const previewRequest = useRef(0);
  const rootRef = useRef(null);
  const panelRef = useRef(null);
  const loadedOnce = useRef(false);

  const { selectedId: selectedKey, select, clear } = useHashSelection(TAB, HASH_OPTIONS);

  // Back, Forward or a pasted link can change the tab in the address; follow it (only for this tab's own addresses).
  useEffect(() => {
    const onHashChange = () => {
      if (isSchedulerHash()) setView(readSchedulerQuery().view);
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const changeView = useCallback((next) => {
    setView(next);
    writeSchedulerQuery({ view: next });
  }, []);

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

  // The summary is chrome (the meta line and the chip counts): a failed read keeps the last one and says nothing.
  const loadSummary = useCallback(async () => {
    const id = ++summaryRequest.current;
    try {
      const data = await fetchSchedulerSummary();
      if (id !== summaryRequest.current) return;
      setSummary(data ?? null);
    } catch {
      // Keep what is on screen.
    }
  }, []);

  const loadJobs = useCallback(async () => {
    const id = ++jobsRequest.current;
    try {
      const data = await fetchSchedulerJobs();
      if (id !== jobsRequest.current) return data;
      setJobs(data);
      setError(null);
      loadedOnce.current = true;
      setLoaded(true);
      return data;
    } catch {
      if (id !== jobsRequest.current) return null;
      setError('Failed to load scheduler jobs');
      return null;
    } finally {
      if (id === jobsRequest.current) setLoading(false);
    }
  }, []);

  // A change to a job (Run now, Pause, Resume, a save) re-reads the summary too: its counts and next fire move.
  // It resolves with the re-read jobs (null when that read failed), which a save's toast quotes the next fire from.
  const reload = useCallback(async () => (await Promise.all([loadJobs(), loadSummary()]))[0], [loadJobs, loadSummary]);

  // The save toast (D16): "Saved. Next fire Tomorrow 12:27.", read from the job as re-read after the save.
  const showSaved = useCallback((reloaded, jobKey) => {
    const fresh = reloaded?.find((j) => j.jobKey === jobKey) ?? null;
    setToast((t) => ({ message: savedMessage(fresh), nonce: t.nonce + 1 }));
  }, []);
  const clearToast = useCallback(() => setToast((t) => ({ ...t, message: null })), []);

  // Run now, Pause and Resume, held per job so a guard survives leaving the panel and coming back.
  const actions = useSchedulerActions(reload);

  // The poll tick, and the first load: skipped only for a tick on a screen already loaded, while the Operations pane
  // is hidden.
  const refresh = useCallback(async () => {
    if (loadedOnce.current && rootRef.current?.closest('[hidden]') != null) return;
    await Promise.all([loadJobs(), loadSummary(), loadPreview()]);
  }, [loadJobs, loadSummary, loadPreview]);
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

  // A different panel starts with no editor open (state adjusted while rendering, the pattern React documents for
  // state that follows a prop). An action failure is kept per job and cleared by that job's next action.
  const [panelKey, setPanelKey] = useState(selectedKey);
  if (panelKey !== selectedKey) {
    setPanelKey(selectedKey);
    setEditingJob(null);
  }

  const visibleJobs = useMemo(
    () => jobs.filter((job) => jobMatchesFilters(job, filters) && jobMatchesSearch(job, search)),
    [jobs, filters, search],
  );

  const closePanel = useCallback(() => clear(), [clear]);
  useCloseWhenFilteredOut({
    selectedId: selectedKey,
    items: visibleJobs,
    getId: jobId,
    filterKey: `${filters.type}|${filters.status}|${filters.claude}|${filters.failed}|${search}`,
    onClose: closePanel,
  });

  const editing = selectedJob != null && editingJob === selectedJob.jobKey;
  const cancelEdit = useCallback(() => setEditingJob(null), []);
  // Esc steps back from the editor first, then closes the panel.
  usePanelEscape({ active: panelOpen, panelRef, onEscape: editing ? cancelEdit : closePanel });

  // A type chip pressed again turns itself off; All (no chip on) clears every chip.
  const handleType = (type) => setFilters((f) => ({ ...f, type: f.type === type ? 'all' : type }));
  const handleAll = () => setFilters(NO_FILTERS);
  const handleStatus = (status) => setFilters((f) => ({ ...f, status: f.status === status ? null : status }));
  const handleFlag = (flag) => setFilters((f) => ({ ...f, [flag]: !f[flag] }));

  const handleSolarSaved = async (jobKey) => {
    setEditingJob(null);
    const [fresh] = await Promise.all([reload(), loadPreview()]);
    showSaved(fresh, jobKey);
  };

  const handleSwitched = async () => {
    setSwitchingJob(null);
    await Promise.all([reload(), loadPreview()]);
  };

  // Rejects on failure, so the editor can say so beside its field. The cron editor adds the {@code timeZone} its UK
  // times were built in; the raw field and the fixed-delay presets send the value alone.
  const handleInlineSave = async (job, value, options = {}) => {
    // Only the two types that have an inline text edit may reach here; a solar or unrecognised job never sends a fixed
    // delay (a solar job has none, and 0 would be rejected or worse).
    let payload;
    if (job.scheduleType === SCHEDULE_CRON) {
      payload = { cronExpression: value, ...(options.timeZone ? { timeZone: options.timeZone } : {}) };
    } else if (job.scheduleType === SCHEDULE_FIXED_DELAY) {
      payload = { fixedDelayMs: Number(value) };
    } else {
      return;
    }
    await updateJobSchedule(job.jobKey, payload);
    setEditingJob(null);
    showSaved(await reload(), job.jobKey);
  };

  const previewFor = (jobKey) => preview?.jobs?.find((p) => p.jobKey === jobKey) ?? null;
  const switchTarget = switchingJob ? jobs.find((j) => j.jobKey === switchingJob) : null;

  const panel = selectedJob ? (
    <SchedulerJobPanel
      key={selectedJob.jobKey}
      job={selectedJob}
      jobs={jobs}
      preview={preview}
      previewFailed={previewFailed}
      editing={editing}
      actions={actions.stateOf(selectedJob.jobKey)}
      onClose={closePanel}
      onSelectJob={(jobKey) => select(jobKey)}
      onEditStart={() => setEditingJob(selectedJob.jobKey)}
      onEditCancel={cancelEdit}
      onSolarSaved={() => handleSolarSaved(selectedJob.jobKey)}
      onPartial={loadJobs}
      onInlineSave={handleInlineSave}
      onSwitch={() => setSwitchingJob(selectedJob.jobKey)}
      onRunNow={() => actions.runNow(selectedJob)}
      onPause={() => actions.pause(selectedJob)}
      onResume={() => actions.resume(selectedJob)}
    />
  ) : null;

  const list = (
    <div className="flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="scheduler-list-pane">
      <div className="flex min-w-0 flex-col gap-1">
        <h2
          className="font-serif text-[26px] font-semibold leading-tight text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="scheduler-heading"
        >
          Scheduler
        </h2>
        {(loaded || summary != null) && (
          <p className="font-mono text-[11px] text-plex-text-muted" data-testid="scheduler-meta">
            {metaText(jobs, summary)}
          </p>
        )}
      </div>

      <SchedulerTabs view={view} onChange={changeView} total={summary?.total ?? (loaded ? jobs.length : null)} />

      <div
        role="tabpanel"
        id={SCHEDULER_PANEL_ID}
        aria-labelledby={schedulerTabId(view)}
        tabIndex={view === 'day' ? 0 : undefined}
        className="min-w-0"
      >
        {view === 'all' ? (
          <AllJobsTab
            allJobs={jobs}
            jobs={visibleJobs}
            summary={summary}
            preview={preview}
            loading={loading}
            error={error}
            search={search}
            onSearch={setSearch}
            filters={filters}
            onType={handleType}
            onStatus={handleStatus}
            onFlag={handleFlag}
            onAll={handleAll}
            selectedKey={selectedJob?.jobKey ?? null}
            onSelect={(job) => select(job.jobKey)}
          />
        ) : (
          <p className="py-6 text-sm text-plex-text-secondary" data-testid="scheduler-day-placeholder">
            The day picture is coming soon.
          </p>
        )}
      </div>
    </div>
  );

  return (
    <div
      ref={rootRef}
      className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible"
      data-testid="scheduler-view"
    >
      <DataSplitLayout
        mode="overlay"
        list={list}
        panel={panel}
        onClose={closePanel}
        panelLabel={selectedJob ? `${selectedJob.displayName} job` : 'Job'}
        backLabel="Scheduler"
        panelRef={panelRef}
        selectionKey={selectedJob?.jobKey ?? null}
        rowTestIdPrefix="scheduler"
        testId="scheduler-split"
      />

      <SubRegionToast message={toast.message} nonce={toast.nonce} onDone={clearToast} />

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
