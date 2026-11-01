import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import { triggerJob } from '../../api/schedulerApi.js';
import { isCycleJob } from '../../utils/schedulerSolar.js';
import { STATUS_ACTIVE, STATUS_OFF } from '../../utils/schedulerView.js';

/** Duration to show the "Started" confirmation. */
export const TRIGGER_CONFIRM_MS = 2000;

/** What Run now says it does: a cycle job starts a cycle and tonight's still runs; any other just runs. */
const RUN_NOW_CYCLE = "Starts a cycle straight away. Tonight's still runs.";
const RUN_NOW_OTHER = 'Runs the job straight away.';

/** What the confirmation says once the server has accepted the trigger. */
export const TRIGGERED_TEXT = "Started. It'll appear in Runs in a moment.";

const ROW = 'flex items-start justify-between gap-4 max-[639px]:flex-col max-[639px]:items-stretch max-[639px]:gap-2';
const BUTTON = 'btn-secondary shrink-0 text-xs disabled:opacity-50 max-[639px]:min-h-11';

/**
 * One job's Run now row, which owns its own "Started" confirmation.
 *
 * <p>⚠️ The dismiss is an effect keyed on {@code triggered}, the idiom {@code RegisterPage}'s cooldown and
 * {@code ModelSelectionView}'s success banner already use, and not a {@code setTimeout} armed by the click handler.
 * The handler used to arm it <em>after</em> awaiting {@code triggerJob}, and {@code ManageView} renders this screen
 * behind {@code activeTab === 'scheduler'}, so switching tab mid-request unmounted it before any timer existed, the
 * unmount cleanup had nothing to cancel, and the continuation then armed a timer nothing owned. An effect has no such
 * window: it never runs after unmount, and a {@code setTriggered} on an unmounted component is a no-op, so no
 * {@code isMounted} guard is needed either.
 *
 * <p>⚠️ The button is disabled from the click until the call settles ({@code pending}), not only once it resolves. It
 * used to be the latter, so a double-click sent two {@code POST .../trigger} calls, and
 * {@code DynamicSchedulerService.triggerNow} queues an immediate run for each, so one double-click ran a briefing or a
 * tide refresh twice. React flushes a click's state update before the next input event is dispatched, and a disabled
 * button receives no click, so a press while the call is in flight never reaches the handler. Keep
 * {@code setPending(true)} an ordinary update: moved into a transition it could lose that race.
 *
 * <p>This guards only this button's own round trip. {@code triggerNow} returns as soon as the run is queued, so a
 * press after the confirmation clears, a second tab, a remount mid-call, or a retry after a failed response the
 * server had in fact acted on can still queue another run while the first may be going. Whether a job refuses an
 * overlapping run is up to its target: the batch submissions and the cloud-verification backfill already do, the
 * briefing and tide refresh do not.
 *
 * @param {object} props
 * @param {string} props.jobKey the job this button triggers
 * @param {boolean} props.disabled true when the job cannot be triggered at all
 * @param {string} props.description what Run now does for this job, shown until the run is triggered
 * @param {(message: string) => void} props.onError receives the message to show when the trigger call fails
 */
export function RunNowButton({ jobKey, disabled, description, onError }) {
  const [triggered, setTriggered] = useState(false);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    if (!triggered) return undefined;
    const timer = setTimeout(() => setTriggered(false), TRIGGER_CONFIRM_MS);
    return () => clearTimeout(timer);
  }, [triggered]);

  const handleTrigger = async () => {
    setPending(true);
    try {
      await triggerJob(jobKey);
      setTriggered(true);
    } catch {
      onError(`Failed to trigger ${jobKey}`);
    } finally {
      setPending(false);
    }
  };

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
        onClick={handleTrigger}
        className={BUTTON}
        disabled={disabled || pending || triggered}
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
  description: PropTypes.string.isRequired,
  onError: PropTypes.func.isRequired,
};

/**
 * The panel's Job actions section: Run now and Pause or Resume, each with its one-line explanation. A job that is off
 * in config shows what it requires, in mono, and every action is disabled.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {(jobKey: string) => Promise<void>} props.onPause pauses the job
 * @param {(jobKey: string) => Promise<void>} props.onResume resumes it
 * @param {(message: string) => void} props.onError receives a failed trigger's message
 * @param {string|null} [props.error] the last action's failure, shown here because the list's own banner is out of
 *   sight behind the panel on a phone
 */
export default function JobActions({ job, onPause, onResume, onError, error = null }) {
  const off = job.status === STATUS_OFF;
  const [busy, setBusy] = useState(false);
  const active = job.status === STATUS_ACTIVE;

  const run = async (action) => {
    setBusy(true);
    try {
      await action(job.jobKey);
    } finally {
      setBusy(false);
    }
  };

  return (
    <PanelSection label="Job actions" testId="scheduler-panel-actions">
      <div className="flex flex-col gap-4">
        {off && job.configSource && (
          <p className="text-xs text-plex-text-secondary" data-testid={`requires-${job.jobKey}`}>
            Requires <span className="break-all font-mono text-plex-text">{job.configSource}</span>
          </p>
        )}
        <RunNowButton
          key={job.jobKey}
          jobKey={job.jobKey}
          disabled={off}
          description={isCycleJob(job.jobKey) ? RUN_NOW_CYCLE : RUN_NOW_OTHER}
          onError={onError}
        />
        <div className={ROW}>
          <div className="min-w-0">
            <p className="text-[13px] text-plex-text">{active ? 'Pause' : 'Resume'}</p>
            <p className="text-xs text-plex-text-secondary">
              {active ? 'Skips its fires until you resume it.' : 'Fires again from its next time.'}
            </p>
          </div>
          <button
            type="button"
            onClick={() => run(active ? onPause : onResume)}
            className={BUTTON}
            disabled={off || busy}
            data-testid={`${active ? 'pause' : 'resume'}-btn-${job.jobKey}`}
          >
            {active ? 'Pause' : 'Resume'}
          </button>
        </div>
        {error && (
          <p className="text-xs text-plex-coral" role="alert" data-testid="scheduler-action-error">
            {error}
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
  onPause: PropTypes.func.isRequired,
  onResume: PropTypes.func.isRequired,
  onError: PropTypes.func.isRequired,
  error: PropTypes.string,
};
