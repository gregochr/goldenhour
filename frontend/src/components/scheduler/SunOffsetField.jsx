import { useMemo } from 'react';
import PropTypes from 'prop-types';
import useSchedulePreview from '../../hooks/useSchedulePreview.js';
import { INTRADAY_JOB, describeSolarRuleCompact } from '../../utils/schedulerSolar.js';
import { sliderBounds } from '../../utils/schedulerEdit.js';
import { formatFireLabel } from '../../utils/schedulerView.js';
import { runsTimingHref } from '../../utils/runsQuery.js';

const INPUT_CLASSES =
  'bg-plex-bg border border-plex-border rounded px-2 py-1 text-xs text-plex-text max-[639px]:min-h-11';

/** The sentence under the slider, from the served preview. */
function previewLine(status, preview) {
  if (status === 'idle') return null;
  if (status === 'pending') return { text: 'Checking…', tone: 'muted' };
  if (status === 'failed') return { text: "Couldn't check this schedule.", tone: 'warn' };
  if (!preview.valid) return { text: preview.error, tone: 'error' };
  const first = preview.nextFires?.[0];
  const label = first ? formatFireLabel(first) : '';
  const today = label.startsWith('Today') ? `Today that's ${label.slice('Today '.length)}.` : null;
  const parts = [today ?? (label ? `Next fire ${label}.` : null)];
  const range = preview.yearRange;
  if (range) parts.push(`Across the year it moves between ${range.earliest} and ${range.latest} UK time.`);
  return { text: parts.filter(Boolean).join(' '), tone: 'muted' };
}

/**
 * The sun-relative rule's inputs (scheduler-redesign-plan.md S4b): a sunrise or sunset select, an offset slider
 * (30 minutes to 8 hours in 5-minute steps, widened to hold a stored offset that is outside that or off the grid) and
 * the served preview of what the rule means: today's fire and the year's earliest and latest, debounced. For the
 * intraday refresh only, a link to Runs → Timing, where the offset can be chosen from real run times.
 *
 * @param {object} props
 * @param {string} props.idPrefix unique prefix for the ids and test ids
 * @param {string} props.jobKey the job being edited
 * @param {string} props.event SUNRISE or SUNSET
 * @param {number} props.offset the offset in minutes
 * @param {number|null} props.storedOffset the job's stored offset, which the slider must be able to hold
 * @param {(event: string) => void} props.onEvent receives the new event
 * @param {(offset: number) => void} props.onOffset receives the new offset
 */
export default function SunOffsetField({ idPrefix, jobKey, event, offset, storedOffset = null, onEvent, onOffset }) {
  const bounds = sliderBounds(storedOffset);
  const body = useMemo(
    () => ({ scheduleType: 'SOLAR_OFFSET', solarEvent: event, solarOffsetMinutes: offset }),
    [event, offset],
  );
  const { status, preview } = useSchedulePreview(jobKey, body);
  const line = previewLine(status, preview);
  const words = describeSolarRuleCompact(event, offset);
  const lineClass = { muted: 'text-plex-text-secondary', warn: 'text-verdict-marginal', error: 'text-red-400' };

  return (
    <div className="flex flex-col gap-2" data-testid={`${idPrefix}-rule`}>
      <div className="flex flex-col gap-1">
        <div className="flex items-baseline justify-between gap-3">
          <label htmlFor={`${idPrefix}-offset`} className="text-xs text-plex-text-secondary">
            Minutes before the event
          </label>
          <output
            htmlFor={`${idPrefix}-offset`}
            className="font-mono text-[13px] text-plex-text"
            data-testid={`${idPrefix}-offset-readout`}
          >
            {words}
          </output>
        </div>
        <input
          id={`${idPrefix}-offset`}
          type="range"
          min={bounds.min}
          max={bounds.max}
          step={bounds.step}
          value={offset}
          onChange={(e) => onOffset(Number(e.target.value))}
          aria-valuetext={words}
          className="h-6 w-full cursor-pointer accent-plex-gold max-[639px]:h-11"
          data-testid={`${idPrefix}-offset`}
        />
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-xs text-plex-text-secondary" htmlFor={`${idPrefix}-event`}>
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
      </div>
      {line && (
        <p
          className={`text-xs ${lineClass[line.tone]}`}
          aria-live="polite"
          data-testid={`${idPrefix}-preview`}
        >
          {line.text}
        </p>
      )}
      {jobKey === INTRADAY_JOB && (
        <a
          href={runsTimingHref()}
          className="flex items-center text-[13px] text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
          data-testid={`${idPrefix}-timing-link`}
        >
          Choose the offset from real run times (Runs &rarr; Timing) <span aria-hidden="true">&rarr;</span>
        </a>
      )}
    </div>
  );
}

SunOffsetField.propTypes = {
  idPrefix: PropTypes.string.isRequired,
  jobKey: PropTypes.string.isRequired,
  event: PropTypes.string.isRequired,
  offset: PropTypes.number.isRequired,
  storedOffset: PropTypes.number,
  onEvent: PropTypes.func.isRequired,
  onOffset: PropTypes.func.isRequired,
};
