import PropTypes from 'prop-types';
import StatusDot from '../../data/StatusDot.jsx';
import { runsDayHref } from '../../../utils/runsQuery.js';
import { formatClock, formatFireLabel } from '../../../utils/schedulerView.js';
import { dayWord, fireStatusStyle } from '../../../utils/schedulerDay.js';

const LINK = 'text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold';
const NAME_BUTTON = 'cursor-pointer text-left font-medium text-plex-text hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11';

/**
 * "Needs a look": the day's failed, partial and missed fires in time order (the scheduled rows' fires and the repeating
 * jobs' problems), at most eight, then "and n more". Each row's job name opens that job's panel; a job whose runs Runs
 * records adds "Run detail →", to that day in Runs. Nothing is drawn when nothing needs a look.
 *
 * @param {object} props
 * @param {{items: Array<object>, total: number, more: number}} props.look from {@code needsALook}
 * @param {Object<string, object>} props.jobsByKey the served jobs by key, for {@code runGroup}
 * @param {string} props.date the viewed UK date, the day "Run detail" opens
 * @param {(jobKey: string) => void} props.onSelect opens a job's panel
 */
export function NeedsALook({ look, jobsByKey, date, onSelect }) {
  if (look.total === 0) return null;
  return (
    <section
      className="flex flex-col rounded-lg border border-plex-border"
      aria-labelledby="scheduler-look-heading"
      data-testid="scheduler-look"
    >
      <h3 id="scheduler-look-heading" className="px-3 pt-3 font-mono text-[11px] uppercase tracking-[0.08em] text-plex-text-muted">
        {`Needs a look · ${look.total}`}
      </h3>
      <ul>
        {look.items.map((item, i) => {
          const status = fireStatusStyle(item.status);
          const recorded = jobsByKey[item.jobKey]?.runGroup != null;
          return (
            <li
              key={`${item.jobKey}-${item.at}-${i}`}
              className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-t border-plex-border px-3 py-2.5 text-[13px] first:border-t-0"
              data-testid={`scheduler-look-${i}`}
              data-job-key={item.jobKey}
              data-status={item.status}
            >
              <span className="font-mono text-plex-text-secondary" data-testid={`scheduler-look-time-${i}`}>{formatClock(item.at)}</span>
              <button type="button" className={NAME_BUTTON} onClick={() => onSelect(item.jobKey)} data-testid={`scheduler-look-open-${i}`}>
                {item.name}
              </button>
              <StatusDot tone={status.tone} tintText hollow={status.hollow}>{status.label}</StatusDot>
              <span className="min-w-0 flex-1 text-plex-text-secondary" data-testid={`scheduler-look-summary-${i}`}>{item.summary}</span>
              {recorded && (
                <a href={runsDayHref(date)} className={`${LINK} ml-auto flex items-center max-[639px]:min-h-11`} data-testid={`scheduler-look-runs-${i}`}>
                  Run detail →
                </a>
              )}
            </li>
          );
        })}
      </ul>
      {look.more > 0 && (
        <p className="border-t border-plex-border px-3 py-2 text-xs text-plex-text-muted" data-testid="scheduler-look-more">
          {`and ${look.more} more`}
        </p>
      )}
    </section>
  );
}

NeedsALook.propTypes = {
  look: PropTypes.shape({
    items: PropTypes.arrayOf(PropTypes.object).isRequired,
    total: PropTypes.number.isRequired,
    more: PropTypes.number.isRequired,
  }).isRequired,
  jobsByKey: PropTypes.object.isRequired,
  date: PropTypes.string.isRequired,
  onSelect: PropTypes.func.isRequired,
};

/**
 * "Not on this day": the jobs that do not fire on the viewed day (the weekly ones), each with its next fire. Nothing
 * is drawn when every job fires.
 *
 * @param {object} props
 * @param {Array<{jobKey: string, displayName: string, nextFireAt: string|null}>} props.jobs the served list
 * @param {string} props.date the viewed UK date
 * @param {string} props.today today's UK date
 * @param {(jobKey: string) => void} props.onSelect opens a job's panel
 * @param {Date} props.now the clock, for "Today" and "Tomorrow" in a next-fire label
 */
export function NotOnThisDay({ jobs, date, today, onSelect, now }) {
  if (jobs.length === 0) return null;
  const word = dayWord(date, today);
  const name = word === 'Today' || word === 'Tomorrow' || word === 'Yesterday' ? word.toLowerCase() : word;
  return (
    <p className="text-xs text-plex-text-muted" data-testid="scheduler-not-on-day">
      {`Not on ${name}: `}
      {jobs.map((job, i) => (
        <span key={job.jobKey} data-testid={`scheduler-not-on-day-${job.jobKey}`}>
          <button
            type="button"
            className="cursor-pointer text-plex-text-secondary hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
            onClick={() => onSelect(job.jobKey)}
          >
            {job.displayName}
          </button>
          {` (${job.nextFireAt ? `next ${formatFireLabel(job.nextFireAt, now)}` : 'no upcoming fire'})`}
          {i < jobs.length - 1 ? ', ' : '.'}
        </span>
      ))}
    </p>
  );
}

NotOnThisDay.propTypes = {
  jobs: PropTypes.arrayOf(PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    displayName: PropTypes.string.isRequired,
    nextFireAt: PropTypes.string,
  })).isRequired,
  date: PropTypes.string.isRequired,
  today: PropTypes.string.isRequired,
  onSelect: PropTypes.func.isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
};
