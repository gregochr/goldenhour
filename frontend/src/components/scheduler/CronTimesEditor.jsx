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

/** How long the times field must sit idle before a format message is shown (or on blur): not one per keystroke. */
const MESSAGE_IDLE_MS = 600;

/** Whether two lists hold the same members, in any order. */
const sameMembers = (a, b) => a.length === b.length && a.every((x) => b.includes(x));

/**
 * The editor for a cron job that fires at daily or weekly clock times (scheduler-redesign-plan.md S4b, D8): UK
 * times typed comma-separated, and for a weekly job day toggles. The times go to {@code schedule-preview}; the
 * server builds the cron, and Save sends <em>that</em> cron with {@code timeZone: Europe/London}. The client never
 * converts a UK time to a cron field.
 *
 * <p>A job still on UTC says so above Save: saving moves it to UK time. A job already on UK time with the times and days
 * as stored has nothing to save, so Save is off; a UTC job with unchanged times may save (that is the conversion).
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
  const [saving, setSaving] = useState(false);
  const [messageIdle, setMessageIdle] = useState(true);
  const idleTimer = useRef(null);
  const sending = useRef(false);
  const root = useRef(null);
  const weekly = model.days != null;

  // Opening the editor replaces the row the reader pressed Edit on: put focus in its field.
  useEffect(() => {
    root.current?.querySelector('input')?.focus();
    return () => clearTimeout(idleTimer.current);
  }, []);

  const handleText = (value) => {
    setText(value);
    setMessageIdle(false);
    clearTimeout(idleTimer.current);
    idleTimer.current = setTimeout(() => setMessageIdle(true), MESSAGE_IDLE_MS);
  };
  const handleBlur = () => {
    clearTimeout(idleTimer.current);
    setMessageIdle(true);
  };

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
  const onUtc = job.timeZone !== UK_ZONE;
  const unchanged = parsed.ok && sameMembers(parsed.times, model.times)
    && (!weekly || sameMembers(days, model.days));
  const saveDisabled = body == null || status === 'pending' || refused || saving || (!onUtc && unchanged);

  // The time-format message waits for the field to go idle (or lose focus); the day message answers a toggle at once.
  const shownMessage = !parsed.ok && !messageIdle ? null : clientMessage;
  let serverNotice = null;
  if (!clientMessage && refused) serverNotice = preview.error;
  else if (!clientMessage && status === 'failed') serverNotice = "Couldn't check this schedule.";

  const toggleDay = (code) => {
    setDays((current) => (current.includes(code) ? current.filter((d) => d !== code) : [...current, code]));
  };

  const handleSave = async () => {
    if (sending.current || saveDisabled) return;
    sending.current = true;
    setSaving(true);
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
      setSaving(false);
    }
  };

  const ready = status === 'ready' && preview.valid;
  const inputId = `cron-times-${job.jobKey}`;
  const messageId = `cron-message-${job.jobKey}`;
  const liveId = `cron-live-${job.jobKey}`;
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
          onChange={(e) => handleText(e.target.value)}
          onBlur={handleBlur}
          disabled={saving}
          aria-invalid={clientMessage != null || refused}
          aria-describedby={`${messageId} ${liveId}`}
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
                disabled={saving}
                onClick={() => toggleDay(code)}
                className={`cursor-pointer rounded border px-2.5 py-1 font-mono text-[11px] focus-visible:outline-2 focus-visible:outline-plex-gold disabled:cursor-default disabled:opacity-60 max-[639px]:min-h-11 max-[639px]:min-w-11 ${
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
      <div id={messageId} role="status" className="text-xs text-plex-coral" data-testid={`cron-message-${job.jobKey}`}>
        {shownMessage}
      </div>
      {status === 'pending' && body != null && (
        <p className="text-xs text-plex-text-muted" aria-hidden="true" data-testid={`cron-checking-${job.jobKey}`}>
          Checking&hellip;
        </p>
      )}
      <div id={liveId} aria-live="polite" className="flex flex-col gap-2" data-testid={`cron-live-${job.jobKey}`}>
        {serverNotice && (
          <p
            className={`text-xs ${refused ? 'text-plex-coral' : 'text-verdict-marginal'}`}
            data-testid={`cron-notice-${job.jobKey}`}
          >
            {serverNotice}
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
      </div>
      <p className="text-xs text-plex-text-muted" data-testid={`cron-note-${job.jobKey}`}>
        Saved times are UK time and follow the clocks.
      </p>
      {onUtc && (
        <p className="text-xs text-verdict-marginal" data-testid={`cron-utc-notice-${job.jobKey}`}>
          This job runs on UTC today. Saving moves it to UK time, so its fires follow the clocks.
        </p>
      )}
      <EditorFooter jobKey={job.jobKey} error={error} saveDisabled={saveDisabled} onSave={handleSave} onCancel={onCancel} />
    </div>
  );
}

CronTimesEditor.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    displayName: PropTypes.string,
    timeZone: PropTypes.string,
  }).isRequired,
  model: PropTypes.shape({
    times: PropTypes.arrayOf(PropTypes.string).isRequired,
    days: PropTypes.arrayOf(PropTypes.string),
  }).isRequired,
  onSave: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};
