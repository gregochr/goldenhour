import { useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import EditorFooter from './EditorFooter.jsx';
import useSchedulePreview from '../../hooks/useSchedulePreview.js';
import { previewSchedule } from '../../api/schedulerApi.js';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import { DAY_CODES, DAY_LABELS, neighboursText, parseTimesInput } from '../../utils/schedulerEdit.js';
import { UK_ZONE } from '../../utils/mapDates.js';
import { describeCron } from '../../utils/schedulerView.js';
import { inputClass } from '../users/userFormClasses.js';

/**
 * The editor for a cron job that fires at daily or weekly clock times (scheduler-redesign-plan.md S4b, D8): UK
 * times typed comma-separated, and for a weekly job day toggles. The times go to {@code schedule-preview}; the
 * server builds the cron, and Save sends <em>that</em> cron with {@code timeZone: Europe/London}. The client never
 * converts a UK time to a cron field.
 *
 * <p>Save is off while the proposal is mistyped, not yet checked, or refused by the server (its sentence is shown,
 * for instance for times that do not share one minute). If the check itself failed ("Couldn't check this schedule.")
 * Save stays on: pressing it asks the server once more, and the save goes ahead with the cron that answer builds.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {{times: string[], days: string[]|null}} props.model the stored schedule in UK time ({@code days} null
 *   for a daily job, which shows no day toggles)
 * @param {(job: object, cron: string, options: {timeZone: string}) => Promise<void>} props.onSave saves the cron;
 *   rejects on failure
 * @param {() => void} props.onCancel closes the editor
 */
export default function CronTimesEditor({ job, model, onSave, onCancel }) {
  const [text, setText] = useState(model.times.join(', '));
  const [days, setDays] = useState(model.days ?? []);
  const [error, setError] = useState(null);
  const sending = useRef(false);
  const root = useRef(null);
  const weekly = model.days != null;

  // Opening the editor replaces the row the reader pressed Edit on: put focus in its field.
  useEffect(() => {
    root.current?.querySelector('input')?.focus();
  }, []);

  const parsed = useMemo(() => parseTimesInput(text), [text]);
  let clientMessage = null;
  if (!parsed.ok) clientMessage = parsed.message;
  else if (weekly && days.length === 0) clientMessage = 'Choose at least one day.';

  const body = useMemo(() => {
    if (clientMessage) return null;
    const proposal = { scheduleType: 'CRON', times: parsed.times, timeZone: UK_ZONE };
    if (weekly) proposal.daysOfWeek = DAY_CODES.filter((d) => days.includes(d));
    return proposal;
  }, [clientMessage, parsed, weekly, days]);

  const { status, preview } = useSchedulePreview(job.jobKey, body);
  const refused = status === 'ready' && !preview.valid;
  const saveDisabled = body == null || status === 'pending' || refused;

  let notice = null;
  if (clientMessage) notice = clientMessage;
  else if (refused) notice = preview.error;
  else if (status === 'failed') notice = "Couldn't check this schedule.";

  const toggleDay = (code) => {
    setDays((current) => (current.includes(code) ? current.filter((d) => d !== code) : [...current, code]));
  };

  const handleSave = async () => {
    if (sending.current || saveDisabled) return;
    sending.current = true;
    setError(null);
    try {
      let cron = status === 'ready' ? preview.cronExpression : null;
      if (!cron) {
        // The check itself failed: ask the server once more, so the cron saved is still the one it builds.
        const fresh = await previewSchedule(job.jobKey, body);
        if (!fresh.valid) {
          setError(fresh.error);
          return;
        }
        cron = fresh.cronExpression;
      }
      await onSave(job, cron, { timeZone: UK_ZONE });
    } catch {
      setError(`Couldn't save ${job.displayName}'s schedule.`);
    } finally {
      sending.current = false;
    }
  };

  const ready = status === 'ready' && preview.valid;
  const inputId = `cron-times-${job.jobKey}`;
  return (
    <div
      ref={root}
      className="flex flex-col gap-2"
      {...{ [PANEL_EDIT_FORM_ATTR]: '' }}
      data-testid={`cron-edit-${job.jobKey}`}
    >
      <label className="flex flex-col gap-1 text-xs text-plex-text-secondary" htmlFor={inputId}>
        Times (UK time), comma-separated
        <input
          id={inputId}
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          aria-label={`${job.displayName} times`}
          aria-invalid={notice != null && (clientMessage != null || refused)}
          className={`${inputClass} font-mono`}
          data-testid={`cron-times-${job.jobKey}`}
        />
      </label>
      {weekly && (
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Days" data-testid={`cron-days-${job.jobKey}`}>
          {DAY_CODES.map((code) => {
            const on = days.includes(code);
            return (
              <button
                key={code}
                type="button"
                aria-pressed={on}
                onClick={() => toggleDay(code)}
                className={`cursor-pointer rounded border px-2.5 py-1 font-mono text-[11px] focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11 max-[639px]:min-w-11 ${
                  on ? 'border-plex-gold bg-plex-gold/10 text-plex-text' : 'border-plex-border text-plex-text-secondary hover:text-plex-text'
                }`}
                data-testid={`cron-day-${job.jobKey}-${code.toLowerCase()}`}
              >
                {DAY_LABELS[code]}
              </button>
            );
          })}
        </div>
      )}
      {notice && (
        <p
          className={`text-xs ${status === 'failed' && !clientMessage ? 'text-verdict-marginal' : 'text-plex-coral'}`}
          role="alert"
          data-testid={`cron-notice-${job.jobKey}`}
        >
          {notice}
        </p>
      )}
      {ready && (
        <>
          <p className="text-xs text-plex-text-muted" data-testid={`cron-reading-${job.jobKey}`}>
            {describeCron(preview.cronExpression, preview.timeZone)}
          </p>
          <p className="text-xs text-plex-text-secondary" data-testid={`cron-neighbours-${job.jobKey}`}>
            {neighboursText(preview.neighbours)}
          </p>
        </>
      )}
      {status === 'pending' && body != null && (
        <p className="text-xs text-plex-text-muted" data-testid={`cron-checking-${job.jobKey}`}>Checking&hellip;</p>
      )}
      <p className="text-xs text-plex-text-muted" data-testid={`cron-note-${job.jobKey}`}>
        Saved times are UK time and follow the clocks.
      </p>
      <EditorFooter jobKey={job.jobKey} error={error} saveDisabled={saveDisabled} onSave={handleSave} onCancel={onCancel} />
    </div>
  );
}

CronTimesEditor.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    displayName: PropTypes.string,
  }).isRequired,
  model: PropTypes.shape({
    times: PropTypes.arrayOf(PropTypes.string).isRequired,
    days: PropTypes.arrayOf(PropTypes.string),
  }).isRequired,
  onSave: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};
