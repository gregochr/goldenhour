import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import { isCycleJob } from '../../utils/schedulerSolar.js';
import { STATUS_ACTIVE, STATUS_OFF } from '../../utils/schedulerView.js';

/** What Run now says it does: a cycle job starts a cycle and tonight's still runs; any other just runs. */
const RUN_NOW_CYCLE = "Starts a cycle straight away. Tonight's still runs.";
const RUN_NOW_OTHER = 'Runs the job straight away.';

/** What the confirmation says once the server has accepted the trigger. */
export const TRIGGERED_TEXT = "Started. It'll appear in Runs in a moment.";

const ROW = 'flex items-start justify-between gap-4 max-[639px]:flex-col max-[639px]:items-stretch max-[639px]:gap-2';
const BUTTON = 'btn-secondary shrink-0 text-xs disabled:opacity-50 aria-disabled:opacity-50 aria-disabled:cursor-default max-[639px]:min-h-11';

/**
 * One job's Run now row. It holds no state: whether a call is in flight or confirmed lives in
 * {@code useSchedulerActions}, per job, so it survives the panel being closed and reopened.
 *
 * <p>The button is {@code aria-disabled} (with the handler's own guard), not {@code disabled}, while a call is in
 * flight or confirmed, so a press never drops keyboard focus to the page; only a job that is off in config is truly
 * disabled.
 *
 * @param {object} props
 * @param {string} props.jobKey the job this button triggers
 * @param {boolean} props.disabled true when the job cannot be triggered at all
 * @param {boolean} props.pending true while the trigger call is in flight
 * @param {boolean} props.triggered true while the "Started" confirmation shows
 * @param {string} props.description what Run now does for this job, shown until the run is triggered
 * @param {() => void} props.onRun starts the run
 */
export function RunNowButton({ jobKey, disabled, pending, triggered, description, onRun }) {
  const blocked = pending || triggered;
  return (
    <div className={ROW} data-testid={`run-now-${jobKey}`}>
      <div className="min-w-0">
        <p className="text-[13px] text-plex-text">Run now</p>
        <p className="text-xs text-plex-text-secondary" aria-live="polite" data-testid={`run-now-text-${jobKey}`}>
          {triggered ? TRIGGERED_TEXT : description}
        </p>
      </div>
      <button
        type="button"
        onClick={() => { if (!blocked) onRun(); }}
        className={BUTTON}
        disabled={disabled}
        aria-disabled={blocked || undefined}
        data-testid={`trigger-btn-${jobKey}`}
      >
        {triggered ? 'Started ✓' : 'Run now'}
      </button>
    </div>
  );
}

RunNowButton.propTypes = {
  jobKey: PropTypes.string.isRequired,
  disabled: PropTypes.bool.isRequired,
  pending: PropTypes.bool.isRequired,
  triggered: PropTypes.bool.isRequired,
  description: PropTypes.string.isRequired,
  onRun: PropTypes.func.isRequired,
};

/**
 * The panel's Job actions section: Run now and Pause or Resume, each with its one-line explanation. A job that is off
 * in config shows what it requires, in mono, and every action is disabled.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {{pending: boolean, triggered: boolean, busy: boolean, error: string|null}} props.actions this job's action
 *   state (kept by the view, per job)
 * @param {() => void} props.onRunNow triggers the job
 * @param {() => void} props.onPause pauses it
 * @param {() => void} props.onResume resumes it
 */
export default function JobActions({ job, actions, onRunNow, onPause, onResume }) {
  const off = job.status === STATUS_OFF;
  const active = job.status === STATUS_ACTIVE;

  return (
    <PanelSection label="Job actions" testId="scheduler-panel-actions">
      <div className="flex flex-col gap-4">
        {off && job.configSource && (
          <p className="text-xs text-plex-text-secondary" data-testid={`requires-${job.jobKey}`}>
            Requires <span className="break-all font-mono text-plex-text">{job.configSource}</span>
          </p>
        )}
        <RunNowButton
          jobKey={job.jobKey}
          disabled={off}
          pending={actions.pending}
          triggered={actions.triggered}
          description={isCycleJob(job.jobKey) ? RUN_NOW_CYCLE : RUN_NOW_OTHER}
          onRun={onRunNow}
        />
        <div className={ROW}>
          <div className="min-w-0">
            <p className="text-[13px] text-plex-text">{active ? 'Pause' : 'Resume'}</p>
            <p className="text-xs text-plex-text-secondary" aria-live="polite" data-testid={`pause-text-${job.jobKey}`}>
              {active ? 'Skips its fires until you resume it.' : 'Fires again from its next time.'}
            </p>
          </div>
          <button
            type="button"
            onClick={() => { if (!actions.busy) (active ? onPause : onResume)(); }}
            className={BUTTON}
            disabled={off}
            aria-disabled={actions.busy || undefined}
            data-testid={`${active ? 'pause' : 'resume'}-btn-${job.jobKey}`}
          >
            {active ? 'Pause' : 'Resume'}
          </button>
        </div>
        {actions.error && (
          <p className="text-xs text-plex-coral" role="alert" data-testid="scheduler-action-error">
            {actions.error}
          </p>
        )}
      </div>
    </PanelSection>
  );
}

JobActions.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    status: PropTypes.string,
    configSource: PropTypes.string,
  }).isRequired,
  actions: PropTypes.shape({
    pending: PropTypes.bool,
    triggered: PropTypes.bool,
    busy: PropTypes.bool,
    error: PropTypes.string,
  }).isRequired,
  onRunNow: PropTypes.func.isRequired,
  onPause: PropTypes.func.isRequired,
  onResume: PropTypes.func.isRequired,
};
