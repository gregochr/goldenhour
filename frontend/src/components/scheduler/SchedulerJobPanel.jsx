import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import PanelSection from '../data/PanelSection.jsx';
import StatusDot from '../data/StatusDot.jsx';
import JobSchedule from './JobSchedule.jsx';
import JobNextFires, { SunTimingNotes } from './JobNextFires.jsx';
import JobActions from './JobActions.jsx';
import SkyEvalWeeklyModels from './SkyEvalWeeklyModels.jsx';
import {
  CallsOutSection,
  ClaudeCostSection,
  ConnectedJobsSection,
  LastRunSection,
  NextFiresList,
  TechnicalSection,
} from './JobPanelSections.jsx';
import useJobDetail from '../../hooks/useJobDetail.js';
import { isCycleJob, isForecastCycleJob } from '../../utils/schedulerSolar.js';
import {
  SCHEDULE_SOLAR,
  STATUS_ACTIVE,
  STATUS_PAUSED,
  describeSchedule,
  formatNextFire,
  jobStatusView,
  relativeTime,
} from '../../utils/schedulerView.js';

/** The meta line for a job with no catalogue entry: "{type} · {status} · next {time} ({relative})". */
function metaLine(job) {
  const status = jobStatusView(job.status);
  const parts = [describeSchedule(job).type, status.label];
  if (job.status === STATUS_ACTIVE && job.nextFireTime) {
    parts.push(`next ${formatNextFire(job.nextFireTime)} (${relativeTime(job.nextFireTime, { future: true })})`);
  }
  return parts.join(' · ');
}

/** The header's second line: "{alias} · {purpose tag}" for a catalogued job, else the schedule meta line. */
function Subtitle({ job }) {
  const c = job.catalogue;
  if (!c) return <span data-testid="scheduler-panel-meta">{metaLine(job)}</span>;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1" data-testid="scheduler-panel-meta">
      <span>{c.alias}</span>
      <span aria-hidden="true">{'·'}</span>
      <span className="rounded border border-plex-border-light px-1.5 py-px font-mono text-[10px] uppercase leading-4 tracking-[0.06em]">
        {c.purpose}
      </span>
    </span>
  );
}

Subtitle.propTypes = { job: PropTypes.object.isRequired };

/**
 * The Scheduler job panel (scheduler-redesign-plan.md S4a), the drawer's contents. Top to bottom: What it does,
 * Schedule (the rule, why it runs then, the next three fires; the editors are the existing ones until S4b), Next fires
 * (a job that is or may become sun-relative), Last run, Claude cost, Calls out to, Connected jobs, Job actions,
 * Technical. Sections appear only when they have something to say; a job with no catalogue entry shows its stored
 * description in place of the plain-English ones.
 *
 * <p>The detail (typical duration, Claude cost) is fetched once when the panel opens.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {Array<object>} props.jobs every served job, for the Connected jobs' names
 * @param {object|null} props.preview the solar preview, or null while unavailable
 * @param {boolean} props.previewFailed true when the preview could not be loaded
 * @param {boolean} props.editing whether this job's schedule editor is open
 * @param {object} props.actions this job's Run now, Pause and Resume state (kept by the view, per job)
 * @param {() => void} props.onClose closes the panel
 * @param {(jobKey: string) => void} props.onSelectJob switches the panel to a connected job
 * @param {() => void} props.onEditStart opens the schedule editor
 * @param {() => void} props.onEditCancel closes it without saving
 * @param {() => void} props.onSolarSaved called after a sun-relative rule was saved
 * @param {() => void} props.onPartial called after a half-saved solar edit, to reload the job
 * @param {(job: object, value: string) => Promise<void>} props.onInlineSave saves the inline cron or delay
 * @param {() => void} props.onSwitch opens the schedule switch dialog
 * @param {() => void} props.onRunNow triggers the job
 * @param {() => void} props.onPause pauses the job
 * @param {() => void} props.onResume resumes it
 */
export default function SchedulerJobPanel({
  job, jobs, preview = null, previewFailed = false, editing, actions, onClose, onSelectJob, onEditStart,
  onEditCancel, onSolarSaved, onPartial, onInlineSave, onSwitch, onRunNow, onPause, onResume,
}) {
  const { detail, loading: detailLoading, error: detailError } = useJobDetail(job.jobKey);
  const solar = job.scheduleType === SCHEDULE_SOLAR;
  const showNextFires = solar || isForecastCycleJob(job.jobKey);
  const c = job.catalogue;
  const status = jobStatusView(job.status);
  return (
    <PanelShell
      title={job.displayName}
      subtitle={<Subtitle job={job} />}
      onClose={onClose}
      testIdPrefix="scheduler-panel"
    >
      {c ? (
        <PanelSection label="What it does" testId="scheduler-panel-what">
          <p className="text-[14px] leading-relaxed text-plex-text" data-testid="scheduler-panel-what-text">{c.what}</p>
        </PanelSection>
      ) : (
        job.description && (
          <p className="border-b border-plex-border px-[18px] py-3 text-[13px] text-plex-text-secondary max-[639px]:px-4">
            {job.description}
          </p>
        )
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
      >
        {!editing && (
          <>
            {job.status !== STATUS_ACTIVE && (
              <p className="mt-3" data-testid="scheduler-panel-status">
                <StatusDot tone={status.tone} tintText>{status.label}</StatusDot>
              </p>
            )}
            {c?.why && (
              <p className="mt-3 text-[13px] leading-relaxed text-plex-text-secondary" data-testid="scheduler-panel-why">
                {c.why}
              </p>
            )}
            <NextFiresList fires={job.nextFires} paused={job.status === STATUS_PAUSED} />
          </>
        )}
      </JobSchedule>
      {showNextFires && (
        <JobNextFires jobKey={job.jobKey} status={job.status} preview={preview} failed={previewFailed} showReference={!solar} />
      )}
      {job.jobKey === 'sky_rating_eval' && <SkyEvalWeeklyModels />}
      <LastRunSection job={job} detail={detail} />
      {c && <ClaudeCostSection catalogue={c} detail={detail} loading={detailLoading} error={detailError} />}
      {c && <CallsOutSection services={c.services ?? []} />}
      {c && <ConnectedJobsSection catalogue={c} jobs={jobs} onSelectJob={onSelectJob} />}
      {(solar || isCycleJob(job.jobKey)) && <SunTimingNotes jobKey={job.jobKey} />}
      <JobActions job={job} actions={actions} onRunNow={onRunNow} onPause={onPause} onResume={onResume} />
      <TechnicalSection job={job} />
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
    nextFires: PropTypes.arrayOf(PropTypes.string),
    catalogue: PropTypes.object,
  }).isRequired,
  jobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  preview: PropTypes.object,
  previewFailed: PropTypes.bool,
  editing: PropTypes.bool.isRequired,
  actions: PropTypes.object.isRequired,
  onClose: PropTypes.func.isRequired,
  onSelectJob: PropTypes.func.isRequired,
  onEditStart: PropTypes.func.isRequired,
  onEditCancel: PropTypes.func.isRequired,
  onSolarSaved: PropTypes.func.isRequired,
  onPartial: PropTypes.func.isRequired,
  onInlineSave: PropTypes.func.isRequired,
  onSwitch: PropTypes.func.isRequired,
  onRunNow: PropTypes.func.isRequired,
  onPause: PropTypes.func.isRequired,
  onResume: PropTypes.func.isRequired,
};
