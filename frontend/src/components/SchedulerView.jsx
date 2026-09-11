import React, { useState, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';
import {
  fetchSchedulerJobs,
  updateJobSchedule,
  pauseJob,
  resumeJob,
  triggerJob,
} from '../api/schedulerApi.js';

/** Polling interval for refreshing job statuses. */
const POLL_INTERVAL_MS = 30_000;

/** Duration to show the "Triggered" confirmation. */
const TRIGGER_CONFIRM_MS = 2000;

/**
 * Returns the current UK timezone label — "BST" in summer, "GMT" in winter.
 *
 * Uses {@code Intl.DateTimeFormat} with the {@code Europe/London} timezone so that
 * BST/GMT transitions are handled automatically without any hardcoded offset.
 *
 * @returns {string} "BST" or "GMT"
 */
function getUKTimezoneLabel() {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/London',
    timeZoneName: 'short',
  }).formatToParts(new Date());
  return parts.find((p) => p.type === 'timeZoneName')?.value || 'GMT';
}

/**
 * Converts a UTC hour/minute pair to a UK local time string (HH:MM).
 *
 * @param {number} utcHour - UTC hour (0-23)
 * @param {number} [utcMinute=0] - UTC minute (0-59)
 * @returns {string} time in HH:MM format, adjusted for Europe/London
 */
function formatUTCAsUK(utcHour, utcMinute = 0) {
  const date = new Date();
  date.setUTCHours(utcHour, utcMinute, 0, 0);
  return date.toLocaleTimeString('en-GB', {
    timeZone: 'Europe/London',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

/**
 * Returns a human-readable description of a cron expression, with times
 * converted to UK local time (BST or GMT).
 *
 * @param {string} cron - a 6-field Spring cron expression
 * @returns {string} human-readable description
 */
function describeCron(cron) {
  if (!cron) return '';
  const parts = cron.split(' ');
  if (parts.length < 6) return cron;

  const [sec, min, hour, dom, mon, dow] = parts;

  if (sec === '0' && dom === '*' && mon === '*') {
    const tzLabel = getUKTimezoneLabel();
    const minVal = parseInt(min, 10) || 0;
    const hours = hour.split(',');
    const timeStr = hours.map((h) => formatUTCAsUK(parseInt(h, 10), minVal)).join(', ');
    if (dow === '*') return `Daily at ${timeStr} ${tzLabel}`;
    const dayMap = { MON: 'Monday', TUE: 'Tuesday', WED: 'Wednesday', THU: 'Thursday', FRI: 'Friday', SAT: 'Saturday', SUN: 'Sunday' };
    return `Every ${dayMap[dow] || dow} at ${timeStr} ${tzLabel}`;
  }
  return cron;
}

/**
 * Returns a human-readable description of a fixed delay in milliseconds.
 *
 * @param {number} ms - delay in milliseconds
 * @returns {string} human-readable description
 */
function describeFixedDelay(ms) {
  if (!ms || ms <= 0) return '';
  if (ms >= 3_600_000) {
    const hours = ms / 3_600_000;
    return `Every ${hours === 1 ? 'hour' : `${hours} hours`}`;
  }
  if (ms >= 60_000) {
    const mins = ms / 60_000;
    return `Every ${mins === 1 ? 'minute' : `${mins} minutes`}`;
  }
  return `Every ${ms / 1000} seconds`;
}

/**
 * Returns a relative time string like "3 min ago".
 *
 * @param {string} isoString - ISO 8601 timestamp
 * @returns {string} relative time or empty string
 */
function relativeTime(isoString, { future = false } = {}) {
  if (!isoString) return 'Never';
  const diff = Date.now() - new Date(isoString).getTime();
  if (future) {
    const ahead = -diff;
    if (ahead <= 0) return 'just now';
    const seconds = Math.floor(ahead / 1000);
    if (seconds < 60) return `in ${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `in ${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `in ${hours}h`;
    const days = Math.floor(hours / 24);
    return `in ${days}d`;
  }
  if (diff < 0) return 'just now';
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

/**
 * Status pill colour classes.
 *
 * @param {string} status - ACTIVE, PAUSED, or DISABLED_BY_CONFIG
 * @returns {string} Tailwind classes
 */
function statusClasses(status) {
  switch (status) {
    case 'ACTIVE':
      return 'bg-green-900/40 text-green-400 border-green-700';
    case 'PAUSED':
      return 'bg-amber-900/40 text-amber-400 border-amber-700';
    case 'DISABLED_BY_CONFIG':
      return 'bg-neutral-800 text-neutral-400 border-neutral-600';
    default:
      return 'bg-neutral-800 text-neutral-400 border-neutral-600';
  }
}

/**
 * Formats the status enum for display.
 *
 * @param {string} status - the raw status string
 * @returns {string} formatted status
 */
function formatStatus(status) {
  if (status === 'DISABLED_BY_CONFIG') return 'Disabled by Config';
  return status?.charAt(0) + status?.slice(1).toLowerCase();
}

/**
 * One job's "Run Now" button, which owns its own "Triggered ✓" confirmation.
 *
 * <p>⚠️ The dismiss is an effect keyed on {@code triggered}, the idiom {@code RegisterPage}'s
 * cooldown and {@code ModelSelectionView}'s success banner already use, and
 * not a {@code setTimeout} armed by the click handler. The handler used to arm it *after* awaiting
 * {@code triggerJob}, and {@code ManageView} renders this screen behind
 * {@code activeTab === 'scheduler'} — so switching tab mid-request unmounted it before any timer
 * existed, the unmount cleanup had nothing to cancel, and the continuation then armed a timer
 * nothing owned. An effect has no such window: it never runs after unmount, and a
 * {@code setTriggered} on an unmounted component is a no-op, so no {@code isMounted} guard is
 * needed either.
 *
 * <p>⚠️ The button is disabled from the click until the call settles ({@code pending}), not only
 * once it resolves. It used to be the latter, so a double-click sent two
 * {@code POST .../trigger} calls — and {@code DynamicSchedulerService.triggerNow} queues an
 * immediate run for each, so one double-click ran a briefing or a tide refresh twice. React flushes
 * a click's state update before the next input event is dispatched, and a disabled button receives
 * no click, so a press while the call is in flight never reaches the handler. Keep
 * {@code setPending(true)} an ordinary update: moved into a transition it could lose that race.
 *
 * <p>This guards only this button's own round trip. {@code triggerNow} returns as soon as the run
 * is queued, so a press after the confirmation clears, a second tab, a remount mid-call, or a retry
 * after a failed response the server had in fact acted on can still queue another run while the
 * first may be going. Whether a job refuses an overlapping run is up to its target: the batch
 * submissions and the cloud-verification backfill already do, the briefing and tide refresh do not.
 *
 * @param {object} props
 * @param {string} props.jobKey - the job this button triggers
 * @param {boolean} props.disabled - true when the job cannot be triggered at all
 * @param {Function} props.onError - receives the message to show when the trigger call fails
 */
function RunNowButton({ jobKey, disabled, onError }) {
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
    <button
      onClick={handleTrigger}
      className="text-xs text-plex-gold hover:text-plex-gold/80 border border-plex-gold/30 rounded px-2 py-0.5"
      disabled={disabled || pending || triggered}
      data-testid={`trigger-btn-${jobKey}`}
    >
      {triggered ? 'Triggered ✓' : 'Run Now'}
    </button>
  );
}

RunNowButton.propTypes = {
  jobKey: PropTypes.string.isRequired,
  disabled: PropTypes.bool.isRequired,
  onError: PropTypes.func.isRequired,
};

/**
 * Admin view for managing dynamically scheduled jobs.
 * Shows a card per job with status, schedule, last/next fire times, and controls.
 */
export default function SchedulerView() {
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [editingJob, setEditingJob] = useState(null);
  const [editValue, setEditValue] = useState('');

  const loadJobs = useCallback(async () => {
    try {
      const data = await fetchSchedulerJobs();
      setJobs(data);
      setError(null);
    } catch {
      setError('Failed to load scheduler jobs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    (async () => {
      await loadJobs();
    })();
    const interval = setInterval(loadJobs, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [loadJobs]);

  const handlePause = async (jobKey) => {
    try {
      await pauseJob(jobKey);
      await loadJobs();
    } catch {
      setError(`Failed to pause ${jobKey}`);
    }
  };

  const handleResume = async (jobKey) => {
    try {
      await resumeJob(jobKey);
      await loadJobs();
    } catch {
      setError(`Failed to resume ${jobKey}`);
    }
  };

  const handleEditStart = (job) => {
    setEditingJob(job.jobKey);
    setEditValue(
      job.scheduleType === 'CRON' ? job.cronExpression : String(job.fixedDelayMs),
    );
  };

  const handleEditCancel = () => {
    setEditingJob(null);
    setEditValue('');
  };

  const handleEditSave = async (job) => {
    try {
      const payload =
        job.scheduleType === 'CRON'
          ? { cronExpression: editValue }
          : { fixedDelayMs: Number(editValue) };
      await updateJobSchedule(job.jobKey, payload);
      setEditingJob(null);
      setEditValue('');
      await loadJobs();
    } catch {
      setError(`Failed to update schedule for ${job.jobKey}`);
    }
  };

  if (loading) {
    return <p className="text-sm text-plex-text-muted">Loading scheduler jobs...</p>;
  }

  const isDisabled = (status) => status === 'DISABLED_BY_CONFIG';

  return (
    <div className="flex flex-col gap-4" data-testid="scheduler-view">
      {error && (
        <div className="text-sm text-red-400 bg-red-900/20 px-3 py-2 rounded">
          {error}
        </div>
      )}

      {jobs.map((job) => (
        <div
          key={job.jobKey}
          className="border border-plex-border rounded-lg p-4 flex flex-col gap-3"
          data-testid={`scheduler-job-${job.jobKey}`}
        >
          {/* Header: name + status pill */}
          <div className="flex items-center justify-between">
            <div>
              <span className="text-sm font-semibold text-plex-text">
                {job.displayName}
              </span>
              <p className="text-xs text-plex-text-muted mt-0.5">
                {job.description}
              </p>
            </div>
            <span
              className={`text-xs font-medium px-2 py-0.5 rounded border ${statusClasses(job.status)}`}
              data-testid={`status-pill-${job.jobKey}`}
            >
              {formatStatus(job.status)}
            </span>
          </div>

          {/* Schedule line */}
          <div className="text-xs text-plex-text-secondary">
            {editingJob === job.jobKey ? (
              <div className="flex flex-col gap-1">
                <div className="flex items-center gap-2">
                  <input
                    type="text"
                    value={editValue}
                    onChange={(e) => setEditValue(e.target.value)}
                    className="bg-plex-bg border border-plex-border rounded px-2 py-1 text-xs text-plex-text font-mono w-48"
                    data-testid={`edit-input-${job.jobKey}`}
                  />
                  <button
                    onClick={() => handleEditSave(job)}
                    className="text-xs text-green-400 hover:text-green-300"
                    data-testid={`save-btn-${job.jobKey}`}
                  >
                    Save
                  </button>
                  <button
                    onClick={handleEditCancel}
                    className="text-xs text-plex-text-muted hover:text-plex-text"
                    data-testid={`cancel-btn-${job.jobKey}`}
                  >
                    Cancel
                  </button>
                </div>
                {job.scheduleType === 'CRON' && editValue && (
                  <span className="text-plex-text-muted text-xs">
                    {describeCron(editValue)}
                  </span>
                )}
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <span className="font-mono">
                  {job.scheduleType === 'CRON'
                    ? job.cronExpression
                    : describeFixedDelay(job.fixedDelayMs)}
                </span>
                {job.scheduleType === 'CRON' && (
                  <span className="text-plex-text-muted">
                    {describeCron(job.cronExpression)}
                  </span>
                )}
                <button
                  onClick={() => handleEditStart(job)}
                  className="text-xs text-plex-gold hover:text-plex-gold/80 ml-1"
                  disabled={isDisabled(job.status)}
                  data-testid={`edit-btn-${job.jobKey}`}
                >
                  Edit
                </button>
              </div>
            )}
          </div>

          {/* Config source for disabled jobs */}
          {isDisabled(job.status) && job.configSource && (
            <p className="text-xs text-neutral-500">
              Requires: <span className="font-mono">{job.configSource}</span>
            </p>
          )}

          {/* Timestamps + actions */}
          <div className="flex items-center justify-between">
            <div className="flex gap-4 text-xs text-plex-text-muted">
              <span>
                Last run:{' '}
                <span className="text-plex-text-secondary" data-testid={`last-fire-${job.jobKey}`}>
                  {relativeTime(job.lastFireTime)}
                </span>
              </span>
              {job.nextFireTime && (
                <span>
                  Next:{' '}
                  <span className="text-plex-text-secondary" data-testid={`next-fire-${job.jobKey}`}>
                    {relativeTime(job.nextFireTime, { future: true })}
                  </span>
                </span>
              )}
            </div>

            <div className="flex gap-2">
              {job.status === 'ACTIVE' ? (
                <button
                  onClick={() => handlePause(job.jobKey)}
                  className="text-xs text-amber-400 hover:text-amber-300 border border-amber-700 rounded px-2 py-0.5"
                  data-testid={`pause-btn-${job.jobKey}`}
                >
                  Pause
                </button>
              ) : (
                <button
                  onClick={() => handleResume(job.jobKey)}
                  className="text-xs text-green-400 hover:text-green-300 border border-green-700 rounded px-2 py-0.5"
                  disabled={isDisabled(job.status)}
                  data-testid={`resume-btn-${job.jobKey}`}
                >
                  Resume
                </button>
              )}

              <RunNowButton
                jobKey={job.jobKey}
                disabled={isDisabled(job.status)}
                onError={setError}
              />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
