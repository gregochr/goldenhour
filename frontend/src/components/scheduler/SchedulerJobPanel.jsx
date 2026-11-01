import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import PanelSection from '../data/PanelSection.jsx';
import StatusDot from '../data/StatusDot.jsx';
import JobSchedule from './JobSchedule.jsx';
import JobNextFires, { SunTimingNotes } from './JobNextFires.jsx';
import JobActions from './JobActions.jsx';
import useJobRecentRuns from '../../hooks/useJobRecentRuns.js';
import { isCycleJob, isForecastCycleJob } from '../../utils/schedulerSolar.js';
import {
  SCHEDULE_SOLAR,
  describeSchedule,
  formatNextFire,
  jobStatusView,
  relativeTime,
} from '../../utils/schedulerView.js';
import { runStatusView } from '../../utils/runStatus.js';
import { formatRunStarted, formatSeconds } from '../../utils/runsView.js';

/** A recent run's address in the Runs tab: a cycle opens its cycle panel, a job run its job-run panel. */
function runHref(item) {
  return `#manage/runs/${item.kind === 'CYCLE' ? 'cycle' : 'job'}/${item.id}`;
}

/** The "Recent runs" row text: "{started} · {duration}", the status riding after it. */
function runSummary(item) {
  const duration = item.status === 'RUNNING' ? 'running' : formatSeconds(item.durationSeconds);
  return [formatRunStarted(item.startedAt), duration].filter((part) => part !== '' && part !== '—').join(' · ');
}

/**
 * The panel's Recent runs section, for a job whose runs the server can attribute: up to three, newest first, each a
 * link to its Runs panel, and "All in Runs" to the list filtered to the job's group.
 */
function JobRecentRuns({ job, refreshKey }) {
  const { runs, loading, error } = useJobRecentRuns(job.jobKey, true, refreshKey);
  const allInRuns = (
    <a
      href={`#manage/runs?kind=${job.runGroup.toLowerCase()}`}
      className="flex items-center text-[13px] text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
      data-testid={`scheduler-all-runs-${job.jobKey}`}
    >
      All in Runs
    </a>
  );
  let body;
  if (loading) {
    body = <p className="text-xs text-plex-text-muted" data-testid="scheduler-runs-loading">Loading runs{'…'}</p>;
  } else if (runs == null && error) {
    body = <p className="text-xs text-plex-coral" data-testid="scheduler-runs-error">Couldn&apos;t load recent runs.</p>;
  } else if (runs == null || runs.length === 0) {
    body = <p className="text-xs text-plex-text-secondary" data-testid="scheduler-runs-empty">No runs recorded yet.</p>;
  } else {
    body = (
      <ul className="flex flex-col" data-testid="scheduler-runs-list">
        {runs.map((item) => {
          const status = runStatusView(item.status);
          return (
            <li key={`${item.kind}-${item.id}`} className="border-b border-plex-border/60 last:border-b-0">
              <a
                href={runHref(item)}
                className="flex min-h-10 items-center justify-between gap-3 py-1.5 text-[13px] text-plex-text hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
                data-testid={`scheduler-run-${item.kind === 'CYCLE' ? 'cycle' : 'job'}-${item.id}`}
              >
                <span className="min-w-0 font-mono text-[12.5px]">{runSummary(item)}</span>
                <StatusDot tone={status.tone} halo={status.halo} tintText>{status.label}</StatusDot>
              </a>
            </li>
          );
        })}
      </ul>
    );
  }
  return (
    <PanelSection label="Recent runs" action={allInRuns} testId="scheduler-panel-runs">
      {body}
    </PanelSection>
  );
}

JobRecentRuns.propTypes = {
  job: PropTypes.shape({ jobKey: PropTypes.string.isRequired, runGroup: PropTypes.string.isRequired }).isRequired,
  refreshKey: PropTypes.number.isRequired,
};

/** The meta line: "{type} · {status} · next {time} ({relative})". */
function metaLine(job) {
  const status = jobStatusView(job.status);
  const parts = [describeSchedule(job).type, status.label];
  if (job.status === 'ACTIVE' && job.nextFireTime) {
    parts.push(`next ${formatNextFire(job.nextFireTime)} (${relativeTime(job.nextFireTime, { future: true })})`);
  }
  return parts.join(' · ');
}

/**
 * The Scheduler job panel (runs-redesign-plan.md §5.5): Schedule, Next fires (a job that is or may become
 * sun-relative), Recent runs (a job whose runs the server can attribute), the "How sun-relative timing works"
 * disclosure (the cycle jobs) and Job actions. Sections appear only when they have something to say.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {object|null} props.preview the solar preview, or null while unavailable
 * @param {boolean} props.previewFailed true when the preview could not be loaded
 * @param {boolean} props.editing whether this job's schedule editor is open
 * @param {number} props.refreshKey changes on each jobs poll, so Recent runs keep up
 * @param {string|null} props.actionError the last pause, resume or trigger failure
 * @param {() => void} props.onClose closes the panel
 * @param {() => void} props.onEditStart opens the schedule editor
 * @param {() => void} props.onEditCancel closes it without saving
 * @param {() => void} props.onSolarSaved called after a sun-relative rule was saved
 * @param {() => void} props.onPartial called after a half-saved solar edit, to reload the job
 * @param {(job: object, value: string) => Promise<void>} props.onInlineSave saves the inline cron or delay
 * @param {() => void} props.onSwitch opens the schedule switch dialog
 * @param {(jobKey: string) => Promise<void>} props.onPause pauses the job
 * @param {(jobKey: string) => Promise<void>} props.onResume resumes it
 * @param {(message: string) => void} props.onActionError reports a failed trigger
 */
export default function SchedulerJobPanel({
  job, preview = null, previewFailed = false, editing, refreshKey, actionError = null, onClose, onEditStart,
  onEditCancel, onSolarSaved, onPartial, onInlineSave, onSwitch, onPause, onResume, onActionError,
}) {
  const solar = job.scheduleType === SCHEDULE_SOLAR;
  const showNextFires = solar || isForecastCycleJob(job.jobKey);
  return (
    <PanelShell
      title={job.displayName}
      subtitle={<span data-testid="scheduler-panel-meta">{metaLine(job)}</span>}
      onClose={onClose}
      testIdPrefix="scheduler-panel"
    >
      {job.description && (
        <p className="border-b border-plex-border px-[18px] py-3 text-[13px] text-plex-text-secondary max-[639px]:px-4">
          {job.description}
        </p>
      )}
      <JobSchedule
        job={job}
        preview={preview}
        previewFailed={previewFailed}
        editing={editing}
        onEditStart={onEditStart}
        onEditCancel={onEditCancel}
        onSolarSaved={onSolarSaved}
        onPartial={onPartial}
        onInlineSave={onInlineSave}
        onSwitch={onSwitch}
      />
      {showNextFires && (
        <JobNextFires jobKey={job.jobKey} preview={preview} failed={previewFailed} showReference={!solar} />
      )}
      {job.runGroup && <JobRecentRuns key={job.jobKey} job={job} refreshKey={refreshKey} />}
      {(solar || isCycleJob(job.jobKey)) && <SunTimingNotes key={job.jobKey} jobKey={job.jobKey} />}
      <JobActions
        job={job}
        onPause={onPause}
        onResume={onResume}
        onError={onActionError}
        error={actionError}
      />
    </PanelShell>
  );
}

SchedulerJobPanel.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    displayName: PropTypes.string.isRequired,
    description: PropTypes.string,
    scheduleType: PropTypes.string,
    status: PropTypes.string,
    nextFireTime: PropTypes.string,
    runGroup: PropTypes.string,
  }).isRequired,
  preview: PropTypes.object,
  previewFailed: PropTypes.bool,
  editing: PropTypes.bool.isRequired,
  refreshKey: PropTypes.number.isRequired,
  actionError: PropTypes.string,
  onClose: PropTypes.func.isRequired,
  onEditStart: PropTypes.func.isRequired,
  onEditCancel: PropTypes.func.isRequired,
  onSolarSaved: PropTypes.func.isRequired,
  onPartial: PropTypes.func.isRequired,
  onInlineSave: PropTypes.func.isRequired,
  onSwitch: PropTypes.func.isRequired,
  onPause: PropTypes.func.isRequired,
  onResume: PropTypes.func.isRequired,
  onActionError: PropTypes.func.isRequired,
};
