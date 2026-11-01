import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import KvList from '../data/KvList.jsx';
import { SolarEditForm } from '../SolarScheduleControls.jsx';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import {
  describeSolarRule,
  formatReferencePoint,
  formatUkDateTime,
  isForecastCycleJob,
} from '../../utils/schedulerSolar.js';
import {
  SCHEDULE_CRON,
  SCHEDULE_FIXED_DELAY,
  SCHEDULE_SOLAR,
  STATUS_OFF,
  describeCron,
  describeFixedDelay,
} from '../../utils/schedulerView.js';
import { inputClass, textButtonClass } from '../users/userFormClasses.js';

/** The inline editor for a cron or fixed-delay job: one field, and under it the cron's reading, live as it is typed. */
function InlineScheduleForm({ job, onSave, onCancel }) {
  const isCron = job.scheduleType === SCHEDULE_CRON;
  const [value, setValue] = useState(isCron ? job.cronExpression : String(job.fixedDelayMs));
  const [error, setError] = useState(null);
  const sending = useRef(false);
  const root = useRef(null);

  // Opening the editor replaces the row the reader pressed Edit on: put focus in its field.
  useEffect(() => {
    root.current?.querySelector('input')?.focus();
  }, []);

  const handleSave = async () => {
    if (sending.current) return;
    sending.current = true;
    setError(null);
    try {
      await onSave(job, value);
    } catch {
      setError(`Failed to update schedule for ${job.jobKey}`);
      sending.current = false;
    }
  };

  return (
    <div ref={root} className="flex flex-col gap-2" {...{ [PANEL_EDIT_FORM_ATTR]: '' }} data-testid={`inline-edit-${job.jobKey}`}>
      <label className="flex flex-col gap-1 text-xs text-plex-text-secondary" htmlFor={`edit-input-${job.jobKey}`}>
        {isCron ? 'Cron expression (UTC unless the job says otherwise)' : 'Delay in milliseconds'}
        <input
          id={`edit-input-${job.jobKey}`}
          type="text"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          aria-label={`${job.displayName} schedule`}
          className={`${inputClass} font-mono`}
          data-testid={`edit-input-${job.jobKey}`}
        />
      </label>
      {isCron && value && (
        <span className="text-xs text-plex-text-muted" data-testid={`edit-reading-${job.jobKey}`}>
          {describeCron(value, job.timeZone)}
        </span>
      )}
      {error && (
        <p className="text-xs text-plex-coral" role="alert" data-testid={`inline-edit-error-${job.jobKey}`}>
          {error}
        </p>
      )}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleSave}
          className="btn-primary text-xs max-[639px]:min-h-11"
          data-testid={`save-btn-${job.jobKey}`}
        >
          Save
        </button>
        <button
          type="button"
          onClick={onCancel}
          className={textButtonClass}
          data-testid={`cancel-btn-${job.jobKey}`}
        >
          Cancel
        </button>
      </div>
    </div>
  );
}

InlineScheduleForm.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    displayName: PropTypes.string,
    scheduleType: PropTypes.string,
    cronExpression: PropTypes.string,
    fixedDelayMs: PropTypes.number,
    timeZone: PropTypes.string,
  }).isRequired,
  onSave: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};

/** The Reference row: the solar preview's reference point and when it was computed. */
function referenceValue(preview) {
  if (!preview) return null;
  return (
    <span data-testid="solar-reference">
      <span className="font-mono text-[12.5px]">{formatReferencePoint(preview.referencePoint)}</span>{' '}
      (Durham unless configured otherwise). Computed {formatUkDateTime(preview.generatedAt)}.
    </span>
  );
}

/**
 * The panel's Schedule section: the rule in words, the fallback cron of a sun-relative job, the next fire, an Edit that
 * swaps in {@code SolarEditForm} or the inline cron/delay field, and (for a forecast cycle job) the switch between a
 * fixed time and a sun-relative one.
 *
 * <p>⚠️ A job that is off in config can neither be edited nor switched. A sun-relative job's editor never sends a fixed
 * delay (see {@code SolarEditForm}); an unrecognised schedule type can be neither edited nor switched, and says so.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {object|null} props.preview the solar preview, or null while it is unavailable
 * @param {boolean} props.previewFailed true when the preview could not be loaded
 * @param {boolean} props.editing whether this job's editor is open
 * @param {() => void} props.onEditStart opens the editor
 * @param {() => void} props.onEditCancel closes it without saving
 * @param {() => void} props.onSolarSaved called after a sun-relative rule was saved
 * @param {() => void} props.onPartial called when a solar edit saved the cron but not the rule (reload the job)
 * @param {(job: object, value: string) => Promise<void>} props.onInlineSave saves the inline cron or delay; rejects on
 *   failure
 * @param {() => void} props.onSwitch opens the schedule switch dialog
 */
export default function JobSchedule({
  job, preview = null, previewFailed = false, editing, onEditStart, onEditCancel, onSolarSaved, onPartial,
  onInlineSave, onSwitch,
}) {
  const off = job.status === STATUS_OFF;
  const isSolar = job.scheduleType === SCHEDULE_SOLAR;
  const isInline = job.scheduleType === SCHEDULE_CRON || job.scheduleType === SCHEDULE_FIXED_DELAY;
  const recognised = isSolar || isInline;
  const previewEntry = preview?.jobs?.find((p) => p.jobKey === job.jobKey) ?? null;

  // Leaving the editor unmounts it, and with it the Edit button the reader pressed: hand focus to the new one.
  const editButton = useRef(null);
  const wasEditing = useRef(false);
  useEffect(() => {
    if (wasEditing.current && !editing) editButton.current?.focus();
    wasEditing.current = editing;
  }, [editing]);

  const canSwitch = isForecastCycleJob(job.jobKey)
    && (job.scheduleType === SCHEDULE_CRON || isSolar) && !editing;
  const switchDisabled = off || (job.scheduleType === SCHEDULE_CRON && (!previewEntry || previewFailed));

  const editAction = recognised && !editing ? (
    <button
      type="button"
      ref={editButton}
      onClick={onEditStart}
      className={textButtonClass}
      disabled={off}
      data-testid={`edit-btn-${job.jobKey}`}
    >
      Edit
    </button>
  ) : null;

  let body;
  if (!recognised) {
    body = (
      <p className="text-[13px] text-verdict-marginal" data-testid={`unrecognised-${job.jobKey}`}>
        Unrecognised schedule type: {job.scheduleType || '(none)'}. This job cannot be edited here.
      </p>
    );
  } else if (editing && isSolar) {
    body = (
      <div {...{ [PANEL_EDIT_FORM_ATTR]: '' }}>
        <SolarEditForm job={job} onCancel={onEditCancel} onSaved={onSolarSaved} onPartial={onPartial} />
      </div>
    );
  } else if (editing) {
    body = <InlineScheduleForm job={job} onSave={onInlineSave} onCancel={onEditCancel} />;
  } else {
    const rows = [];
    if (isSolar) {
      rows.push({
        label: 'Rule',
        testId: `solar-line-${job.jobKey}`,
        value: (
          <>
            <span data-testid={`solar-rule-${job.jobKey}`}>
              {describeSolarRule(job.solarEvent, job.solarOffsetMinutes) || 'Sun-relative rule incomplete'}
            </span>
            , never before 00:05 UK
          </>
        ),
      });
      rows.push({ label: 'Reference', value: referenceValue(preview) });
      rows.push({
        label: 'Fallback cron',
        testId: `solar-fallback-${job.jobKey}`,
        value: (
          <>
            <span className="font-mono text-[12.5px]">{job.cronExpression}</span> (UTC){' '}
            <span className="text-plex-text-secondary">{describeCron(job.cronExpression)}</span>
          </>
        ),
      });
    } else if (job.scheduleType === SCHEDULE_CRON) {
      rows.push({
        label: 'Schedule',
        value: (
          <>
            <span className="font-mono text-[12.5px]">{job.cronExpression}</span>{' '}
            <span className="text-plex-text-secondary">{describeCron(job.cronExpression, job.timeZone)}</span>
          </>
        ),
      });
    } else {
      rows.push({ label: 'Schedule', value: describeFixedDelay(job.fixedDelayMs) });
    }
    rows.push({
      label: 'Next fire',
      testId: `schedule-next-${job.jobKey}`,
      value: job.nextFireTime ? formatUkDateTime(job.nextFireTime) : null,
      mono: true,
    });
    body = <KvList items={rows} testId={`schedule-kv-${job.jobKey}`} />;
  }

  return (
    <PanelSection label="Schedule" action={editAction} testId="scheduler-panel-schedule">
      {body}
      {canSwitch && (
        <button
          type="button"
          onClick={onSwitch}
          disabled={switchDisabled}
          className={`${textButtonClass} mt-3 -ml-1 text-plex-gold disabled:opacity-50`}
          data-testid={`switch-btn-${job.jobKey}`}
        >
          {job.scheduleType === SCHEDULE_CRON ? 'Switch to sun-relative…' : 'Switch back to a fixed time…'}
        </button>
      )}
    </PanelSection>
  );
}

JobSchedule.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    scheduleType: PropTypes.string,
    status: PropTypes.string,
    cronExpression: PropTypes.string,
    timeZone: PropTypes.string,
    solarEvent: PropTypes.string,
    solarOffsetMinutes: PropTypes.number,
    fixedDelayMs: PropTypes.number,
    nextFireTime: PropTypes.string,
  }).isRequired,
  preview: PropTypes.shape({
    referencePoint: PropTypes.object,
    generatedAt: PropTypes.string,
    jobs: PropTypes.array,
  }),
  previewFailed: PropTypes.bool,
  editing: PropTypes.bool.isRequired,
  onEditStart: PropTypes.func.isRequired,
  onEditCancel: PropTypes.func.isRequired,
  onSolarSaved: PropTypes.func.isRequired,
  onPartial: PropTypes.func.isRequired,
  onInlineSave: PropTypes.func.isRequired,
  onSwitch: PropTypes.func.isRequired,
};
