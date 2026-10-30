import React, { useState, useEffect, useCallback, useRef } from 'react';
import PropTypes from 'prop-types';
import {
  fetchSchedulerJobs,
  updateJobSchedule,
  pauseJob,
  resumeJob,
  triggerJob,
  fetchSolarPreview,
} from '../api/schedulerApi.js';
import SolarSchedulePanel from './SolarSchedulePanel.jsx';
import { ScheduleSwitchDialog, SolarEditForm } from './SolarScheduleControls.jsx';
import {
  describeSolarRule,
  formatUkDateTime,
  isForecastCycleJob,
} from '../utils/schedulerSolar.js';

/** Polling interval for refreshing job statuses. */
const POLL_INTERVAL_MS = 30_000;

/** The schedule types this screen knows how to show and edit; anything else is "unrecognised". */
const SCHEDULE_CRON = 'CRON';
const SCHEDULE_FIXED_DELAY = 'FIXED_DELAY';
const SCHEDULE_SOLAR = 'SOLAR_OFFSET';

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
 * converted to UK local time (BST or GMT). A job whose cron already runs in
 * Europe/London shows its hours as-is, labelled "UK time".
 *
 * @param {string} cron - a 6-field Spring cron expression
 * @param {string} [timeZone] - the zone the cron runs in; absent or "UTC" means UTC
 * @returns {string} human-readable description
 */
function describeCron(cron, timeZone) {
  if (!cron) return '';
  const parts = cron.split(' ');
  if (parts.length < 6) return cron;

  const [sec, min, hour, dom, mon, dow] = parts;

  if (sec === '0' && dom === '*' && mon === '*') {
    const isUK = timeZone === 'Europe/London';
    const tzLabel = isUK ? 'UK time' : getUKTimezoneLabel();
    const minVal = parseInt(min, 10) || 0;
    const hours = hour.split(',');
    const timeStr = hours
      .map((h) => (isUK
        ? `${String(parseInt(h, 10)).padStart(2, '0')}:${String(minVal).padStart(2, '0')}`
        : formatUTCAsUK(parseInt(h, 10), minVal)))
      .join(', ');
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
  const [preview, setPreview] = useState(null);
  const [previewFailed, setPreviewFailed] = useState(false);
  const [switchingJob, setSwitchingJob] = useState(null);

  // Each loader numbers its requests and applies only the latest one issued: a slow earlier
  // response (a poll still in flight when a switch reloads) must not overwrite a newer one.
  const jobsRequest = useRef(0);
  const previewRequest = useRef(0);
  const editButtons = useRef({});
  const focusBack = useRef(null);

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
    } catch {
      if (id !== jobsRequest.current) return;
      setError('Failed to load scheduler jobs');
    } finally {
      if (id === jobsRequest.current) setLoading(false);
    }
  }, []);

  // Leaving an editor unmounts it, and with it the Edit button the reader pressed (the button is
  // part of the row the editor replaces): hand focus back to the new Edit button once it exists.
  useEffect(() => {
    if (editingJob !== null || focusBack.current === null) return;
    editButtons.current[focusBack.current]?.focus();
    focusBack.current = null;
  }, [editingJob]);

  useEffect(() => {
    const refresh = async () => {
      await Promise.all([loadJobs(), loadPreview()]);
    };
    (async () => {
      await refresh();
    })();
    const interval = setInterval(refresh, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [loadJobs, loadPreview]);

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
      job.scheduleType === SCHEDULE_CRON ? job.cronExpression : String(job.fixedDelayMs),
    );
  };

  const handleSolarSaved = async () => {
    focusBack.current = editingJob;
    setEditingJob(null);
    setEditValue('');
    await Promise.all([loadJobs(), loadPreview()]);
  };

  const handleSwitched = async () => {
    setSwitchingJob(null);
    await Promise.all([loadJobs(), loadPreview()]);
  };

  const handleEditCancel = () => {
    focusBack.current = editingJob;
    setEditingJob(null);
    setEditValue('');
  };

  const handleEditSave = async (job) => {
    try {
      // Only the two types that have an inline text edit may reach here; a solar or unrecognised
      // row never sends a fixed delay (a solar row has none, and 0 would be rejected or worse).
      let payload;
      if (job.scheduleType === SCHEDULE_CRON) {
        payload = { cronExpression: editValue };
      } else if (job.scheduleType === SCHEDULE_FIXED_DELAY) {
        payload = { fixedDelayMs: Number(editValue) };
      } else {
        return;
      }
      await updateJobSchedule(job.jobKey, payload);
      focusBack.current = job.jobKey;
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
  const displayNames = Object.fromEntries(jobs.map((j) => [j.jobKey, j.displayName]));
  const previewFor = (jobKey) => preview?.jobs?.find((p) => p.jobKey === jobKey) ?? null;
  const switchTarget = switchingJob ? jobs.find((j) => j.jobKey === switchingJob) : null;

  return (
    <div className="flex flex-col gap-4" data-testid="scheduler-view">
      {error && (
        <div className="text-sm text-red-400 bg-red-900/20 px-3 py-2 rounded">
          {error}
        </div>
      )}

      <SolarSchedulePanel preview={preview} failed={previewFailed} displayNames={displayNames} />

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

          {/* Schedule line: an explicit branch per schedule type */}
          <div className="text-xs text-plex-text-secondary">
            {job.scheduleType === SCHEDULE_SOLAR ? (
              editingJob === job.jobKey ? (
                <SolarEditForm
                  job={job}
                  onCancel={handleEditCancel}
                  onSaved={handleSolarSaved}
                  onPartial={loadJobs}
                />
              ) : (
                <div className="flex flex-col gap-1" data-testid={`solar-line-${job.jobKey}`}>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-plex-text" data-testid={`solar-rule-${job.jobKey}`}>
                      {describeSolarRule(job.solarEvent, job.solarOffsetMinutes) ||
                        'Sun-relative rule incomplete'}
                    </span>
                    <button
                      type="button"
                      ref={(el) => {
                        editButtons.current[job.jobKey] = el;
                      }}
                      onClick={() => handleEditStart(job)}
                      className="text-xs text-plex-gold hover:text-plex-gold/80 ml-1"
                      disabled={isDisabled(job.status)}
                      data-testid={`edit-btn-${job.jobKey}`}
                    >
                      Edit
                    </button>
                  </div>
                  <span className="text-plex-text-secondary">
                    Fallback cron (UTC): <span className="font-mono">{job.cronExpression}</span>{' '}
                    {describeCron(job.cronExpression)}
                  </span>
                  {job.nextFireTime && (
                    <span className="text-plex-text-secondary">
                      Next fire:{' '}
                      <span className="text-plex-text-secondary" data-testid={`solar-next-${job.jobKey}`}>
                        {formatUkDateTime(job.nextFireTime)}
                      </span>
                    </span>
                  )}
                </div>
              )
            ) : job.scheduleType === SCHEDULE_CRON || job.scheduleType === SCHEDULE_FIXED_DELAY ? (
              editingJob === job.jobKey ? (
                <div className="flex flex-col gap-1">
                  <div className="flex items-center gap-2">
                    <input
                      type="text"
                      value={editValue}
                      onChange={(e) => setEditValue(e.target.value)}
                      aria-label={`${job.displayName} schedule`}
                      className="bg-plex-bg border border-plex-border rounded px-2 py-1 text-xs text-plex-text font-mono w-48 max-w-full"
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
                  {job.scheduleType === SCHEDULE_CRON && editValue && (
                    <span className="text-plex-text-muted text-xs">
                      {describeCron(editValue, job.timeZone)}
                    </span>
                  )}
                </div>
              ) : (
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono">
                    {job.scheduleType === SCHEDULE_CRON
                      ? job.cronExpression
                      : describeFixedDelay(job.fixedDelayMs)}
                  </span>
                  {job.scheduleType === SCHEDULE_CRON && (
                    <span className="text-plex-text-muted">
                      {describeCron(job.cronExpression, job.timeZone)}
                    </span>
                  )}
                  <button
                    ref={(el) => {
                      editButtons.current[job.jobKey] = el;
                    }}
                    onClick={() => handleEditStart(job)}
                    className="text-xs text-plex-gold hover:text-plex-gold/80 ml-1"
                    disabled={isDisabled(job.status)}
                    data-testid={`edit-btn-${job.jobKey}`}
                  >
                    Edit
                  </button>
                </div>
              )
            ) : (
              <span className="text-amber-400" data-testid={`unrecognised-${job.jobKey}`}>
                Unrecognised schedule type: {job.scheduleType || '(none)'}. This job cannot be
                edited here.
              </span>
            )}

            {isForecastCycleJob(job.jobKey) &&
              (job.scheduleType === SCHEDULE_CRON || job.scheduleType === SCHEDULE_SOLAR) &&
              editingJob !== job.jobKey && (
                <div className="mt-2">
                  <button
                    type="button"
                    onClick={() => setSwitchingJob(job.jobKey)}
                    disabled={
                      isDisabled(job.status) ||
                      (job.scheduleType === SCHEDULE_CRON && (!previewFor(job.jobKey) || previewFailed))
                    }
                    className="text-xs text-plex-gold hover:text-plex-gold/80 border border-plex-gold/30 rounded px-2 py-0.5 disabled:opacity-50"
                    data-testid={`switch-btn-${job.jobKey}`}
                  >
                    {job.scheduleType === SCHEDULE_CRON
                      ? 'Switch to sun-relative...'
                      : 'Switch back to fixed time...'}
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
