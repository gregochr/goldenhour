import React, { useRef, useState } from 'react';
import PropTypes from 'prop-types';
import ConfirmDialog from './shared/ConfirmDialog.jsx';
import { switchScheduleType, updateJobSchedule } from '../api/schedulerApi.js';
import {
  MAX_OFFSET_MINUTES,
  MIN_OFFSET_MINUTES,
  describeSolarRule,
  formatUkDateTime,
  nextPreviewFire,
  validateOffset,
} from '../utils/schedulerSolar.js';

const INPUT_CLASSES =
  'bg-plex-bg border border-plex-border rounded px-2 py-1 text-xs text-plex-text';

/**
 * The server's own sentence for a rejected request, else a fallback.
 *
 * @param {object} err - the rejected axios error
 * @param {string} fallback - what to say when the response carries no {@code error}
 * @returns {string} the message to show
 */
function errorMessage(err, fallback) {
  return err?.response?.data?.error ?? fallback;
}

/**
 * Solar rule fields: the event and the offset in minutes, each labelled.
 *
 * @param {object} props
 * @param {string} props.idPrefix - unique prefix for the input ids
 * @param {string} props.event - SUNRISE or SUNSET
 * @param {string} props.offset - the offset as typed
 * @param {Function} props.onEvent - receives the new event
 * @param {Function} props.onOffset - receives the new offset text
 * @param {string|null} props.offsetError - the validation message, if any
 */
function RuleFields({ idPrefix, event, offset, onEvent, onOffset, offsetError }) {
  return (
    <div className="flex flex-wrap items-end gap-3">
      <label className="flex flex-col gap-1 text-xs text-plex-text-muted" htmlFor={`${idPrefix}-offset`}>
        Minutes before the event
        <input
          id={`${idPrefix}-offset`}
          type="text"
          inputMode="numeric"
          value={offset}
          onChange={(e) => onOffset(e.target.value)}
          aria-invalid={offsetError ? 'true' : 'false'}
          aria-describedby={offsetError ? `${idPrefix}-offset-error` : undefined}
          className={`${INPUT_CLASSES} w-24 font-mono`}
          data-testid={`${idPrefix}-offset`}
        />
      </label>
      <label className="flex flex-col gap-1 text-xs text-plex-text-muted" htmlFor={`${idPrefix}-event`}>
        Solar event
        <select
          id={`${idPrefix}-event`}
          value={event}
          onChange={(e) => onEvent(e.target.value)}
          className={INPUT_CLASSES}
          data-testid={`${idPrefix}-event`}
        >
          <option value="SUNRISE">Sunrise</option>
          <option value="SUNSET">Sunset</option>
        </select>
      </label>
      <span className="text-xs text-plex-text-muted basis-full">
        Whole minutes, {MIN_OFFSET_MINUTES} to {MAX_OFFSET_MINUTES}.
      </span>
      {offsetError && (
        <span
          id={`${idPrefix}-offset-error`}
          className="text-xs text-red-400 basis-full"
          role="alert"
          data-testid={`${idPrefix}-offset-error`}
        >
          {offsetError}
        </span>
      )}
    </div>
  );
}

RuleFields.propTypes = {
  idPrefix: PropTypes.string.isRequired,
  event: PropTypes.string.isRequired,
  offset: PropTypes.string.isRequired,
  onEvent: PropTypes.func.isRequired,
  onOffset: PropTypes.func.isRequired,
  offsetError: PropTypes.string,
};

/**
 * The confirmation step for switching a forecast cycle job between a fixed time and a
 * sun-relative one. Nothing is sent until the confirm button is pressed; a rejected request keeps
 * the dialog open and shows the server's sentence.
 *
 * <p>The "after" time is quoted from the preview only when the rule being confirmed is the
 * preview's own rule: the preview cannot know a rule the admin has just edited, so for one the
 * dialog says so rather than guess.
 *
 * @param {object} props
 * @param {object} props.job - the scheduler job row
 * @param {object|null} props.previewJob - this job's entry in the solar preview, if loaded
 * @param {string} props.cronWords - the job's stored cron in words (UK time)
 * @param {Function} props.onCancel - closes the dialog
 * @param {Function} props.onSwitched - called after the switch succeeded
 */
export function ScheduleSwitchDialog({ job, previewJob, cronWords, onCancel, onSwitched }) {
  const toCron = job.scheduleType === 'SOLAR_OFFSET';
  const [event, setEvent] = useState(previewJob?.solarEvent ?? 'SUNRISE');
  const [offset, setOffset] = useState(String(previewJob?.offsetMinutes ?? ''));
  const [error, setError] = useState(null);
  const sending = useRef(false);
  // "Now" is read once, when the dialog opens: the after-time is quoted against that moment.
  const [openedAt] = useState(() => Date.now());

  const check = toCron ? { ok: true } : validateOffset(offset);
  const before = job.nextFireTime ? formatUkDateTime(job.nextFireTime) : 'not scheduled';

  let after;
  if (toCron) {
    after = `${cronWords || 'the stored cron time'} (the stored cron expression, UTC)`;
  } else if (
    check.ok &&
    previewJob &&
    event === previewJob.solarEvent &&
    check.value === previewJob.offsetMinutes
  ) {
    const next = nextPreviewFire(previewJob, openedAt);
    after = next
      ? `${formatUkDateTime(next)}, then ${describeSolarRule(event, check.value)} every day`
      : 'cannot be previewed (the calculation falls back to the stored cron)';
  } else {
    after = `${describeSolarRule(event, check.ok ? check.value : 0) || 'a sun-relative rule'}: not previewed for an edited rule`;
  }

  const handleConfirm = async () => {
    if (sending.current) return;
    if (!check.ok) return;
    sending.current = true;
    setError(null);
    try {
      await switchScheduleType(
        job.jobKey,
        toCron
          ? { scheduleType: 'CRON' }
          : { scheduleType: 'SOLAR_OFFSET', solarEvent: event, solarOffsetMinutes: check.value },
      );
      onSwitched();
    } catch (err) {
      setError(errorMessage(err, `Failed to switch ${job.jobKey}.`));
      sending.current = false;
    }
  };

  const title = toCron ? 'Switch back to a fixed time?' : 'Switch to a sun-relative time?';
  return (
    <ConfirmDialog
      title={title}
      message={`This changes when the production forecast cycle "${job.displayName}" runs.`}
      confirmLabel={toCron ? 'Switch to fixed time' : 'Switch to sun-relative'}
      onConfirm={handleConfirm}
      onCancel={onCancel}
    >
      {!toCron && (
        <RuleFields
          idPrefix="switch"
          event={event}
          offset={offset}
          onEvent={setEvent}
          onOffset={setOffset}
          offsetError={check.ok ? null : check.message}
        />
      )}
      <dl className="text-xs text-plex-text-secondary flex flex-col gap-1" data-testid="switch-summary">
        <div>
          <dt className="inline text-plex-text-muted">Next fire before: </dt>
          <dd className="inline" data-testid="switch-before">
            {before}
          </dd>
        </div>
        <div>
          <dt className="inline text-plex-text-muted">Next fire after: </dt>
          <dd className="inline" data-testid="switch-after">
            {after}
          </dd>
        </div>
      </dl>
      <p className="text-xs text-plex-text-muted">
        {toCron
          ? 'The job keeps its sun-relative rule; switching to sun-relative again is the same control.'
          : 'Switching back to a fixed time is the same control, on this job\'s card.'}
      </p>
      {error && (
        <p className="text-xs text-red-400" role="alert" data-testid="switch-error">
          {error}
        </p>
      )}
    </ConfirmDialog>
  );
}

ScheduleSwitchDialog.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    displayName: PropTypes.string,
    scheduleType: PropTypes.string,
    nextFireTime: PropTypes.string,
  }).isRequired,
  previewJob: PropTypes.shape({
    solarEvent: PropTypes.string,
    offsetMinutes: PropTypes.number,
  }),
  cronWords: PropTypes.string,
  onCancel: PropTypes.func.isRequired,
  onSwitched: PropTypes.func.isRequired,
};

/**
 * Inline editor for a sun-relative job: its event, its offset and its fallback cron.
 *
 * <p>⚠️ Never sends {@code fixedDelayMs}: a solar row has no fixed delay, and the scheduler would
 * read {@code 0} as an invalid one. The rule goes through the schedule-type endpoint; the fallback
 * cron, when changed, goes through the schedule endpoint with the cron alone. The cron is saved
 * first so a bad one (a 400) leaves the rule untouched.
 *
 * @param {object} props
 * @param {object} props.job - the scheduler job row (a SOLAR_OFFSET one)
 * @param {Function} props.onCancel - closes the editor
 * @param {Function} props.onSaved - called after a successful save
 */
export function SolarEditForm({ job, onCancel, onSaved }) {
  const [event, setEvent] = useState(job.solarEvent === 'SUNSET' ? 'SUNSET' : 'SUNRISE');
  const [offset, setOffset] = useState(String(job.solarOffsetMinutes ?? 0));
  const [cron, setCron] = useState(job.cronExpression || '');
  const [error, setError] = useState(null);
  const sending = useRef(false);
  const check = validateOffset(offset);

  const handleSave = async () => {
    if (sending.current || !check.ok) return;
    sending.current = true;
    setError(null);
    try {
      if (cron.trim() && cron !== job.cronExpression) {
        await updateJobSchedule(job.jobKey, { cronExpression: cron.trim() });
      }
      if (event !== job.solarEvent || check.value !== job.solarOffsetMinutes) {
        await switchScheduleType(job.jobKey, {
          scheduleType: 'SOLAR_OFFSET',
          solarEvent: event,
          solarOffsetMinutes: check.value,
        });
      }
      onSaved();
    } catch (err) {
      setError(errorMessage(err, `Failed to update ${job.jobKey}.`));
      sending.current = false;
    }
  };

  return (
    <div className="flex flex-col gap-2" data-testid={`solar-edit-${job.jobKey}`}>
      <RuleFields
        idPrefix={`solar-edit-${job.jobKey}`}
        event={event}
        offset={offset}
        onEvent={setEvent}
        onOffset={setOffset}
        offsetError={check.ok ? null : check.message}
      />
      <label
        className="flex flex-col gap-1 text-xs text-plex-text-muted"
        htmlFor={`solar-edit-${job.jobKey}-cron`}
      >
        Fallback cron (UTC), used on any day the solar time cannot be computed
        <input
          id={`solar-edit-${job.jobKey}-cron`}
          type="text"
          value={cron}
          onChange={(e) => setCron(e.target.value)}
          className={`${INPUT_CLASSES} font-mono w-full max-w-xs`}
          data-testid={`solar-edit-${job.jobKey}-cron`}
        />
      </label>
      {error && (
        <p className="text-xs text-red-400" role="alert" data-testid={`solar-edit-${job.jobKey}-error`}>
          {error}
        </p>
      )}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleSave}
          disabled={!check.ok}
          className="text-xs text-green-400 hover:text-green-300 disabled:opacity-50"
          data-testid={`save-btn-${job.jobKey}`}
        >
          Save
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="text-xs text-plex-text-muted hover:text-plex-text"
          data-testid={`cancel-btn-${job.jobKey}`}
        >
          Cancel
        </button>
      </div>
    </div>
  );
}

SolarEditForm.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    solarEvent: PropTypes.string,
    solarOffsetMinutes: PropTypes.number,
    cronExpression: PropTypes.string,
  }).isRequired,
  onCancel: PropTypes.func.isRequired,
  onSaved: PropTypes.func.isRequired,
};
