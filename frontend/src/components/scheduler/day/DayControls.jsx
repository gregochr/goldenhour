import PropTypes from 'prop-types';
import StatusDot from '../../data/StatusDot.jsx';
import { runsDayHref } from '../../../utils/runsQuery.js';
import { dayDateLabel, dayWord, runsLinkLabel } from '../../../utils/schedulerDay.js';

const NAV_BUTTON = 'flex size-8 cursor-pointer items-center justify-center rounded-lg border border-plex-border text-plex-text hover:bg-plex-surface-light disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:size-11';

/**
 * The day navigation: previous, the viewed day ("Today Mon 2 Nov"), next, and a Today button. Bounded by the served
 * date: seven days either side of today, the server's own limit.
 *
 * @param {object} props
 * @param {string} props.date the viewed UK date
 * @param {string} props.today today's UK date (from the served clock)
 * @param {boolean} props.canPrev false at the earliest day
 * @param {boolean} props.canNext false at the latest day
 * @param {() => void} props.onPrev goes back a day
 * @param {() => void} props.onNext goes forward a day
 * @param {() => void} props.onToday returns to today
 */
export function DayNav({ date, today, canPrev, canNext, onPrev, onNext, onToday }) {
  const word = dayWord(date, today);
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="scheduler-day-nav">
      <button type="button" className={NAV_BUTTON} onClick={onPrev} disabled={!canPrev} aria-label="Previous day" data-testid="scheduler-day-prev">
        {'‹'}
      </button>
      <div
        className="flex min-w-[140px] items-baseline justify-center gap-2 rounded-lg border border-plex-border px-3 py-1 text-sm max-[639px]:min-h-11 max-[639px]:items-center"
        aria-live="polite"
        data-testid="scheduler-day-label"
      >
        {word === 'Today' || word === 'Tomorrow' || word === 'Yesterday'
          ? (
            <>
              <b className="font-semibold text-plex-text">{word}</b>
              <span className="font-mono text-xs text-plex-text-muted">{dayDateLabel(date)}</span>
            </>
          )
          : <b className="font-semibold text-plex-text">{word}</b>}
      </div>
      <button type="button" className={NAV_BUTTON} onClick={onNext} disabled={!canNext} aria-label="Next day" data-testid="scheduler-day-next">
        {'›'}
      </button>
      <button
        type="button"
        className="cursor-pointer rounded-lg border border-plex-border px-3 py-1 text-sm text-plex-text hover:bg-plex-surface-light disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
        onClick={onToday}
        disabled={date === today}
        data-testid="scheduler-day-today"
      >
        Today
      </button>
    </div>
  );
}

DayNav.propTypes = {
  date: PropTypes.string.isRequired,
  today: PropTypes.string.isRequired,
  canPrev: PropTypes.bool.isRequired,
  canNext: PropTypes.bool.isRequired,
  onPrev: PropTypes.func.isRequired,
  onNext: PropTypes.func.isRequired,
  onToday: PropTypes.func.isRequired,
};

/** A count and its word in the summary bar, shown only when there is something to count. */
function Count({ n, tone, hollow = false, children, testId }) {
  if (!n) return null;
  return (
    <StatusDot tone={tone} hollow={hollow} testId={testId}>
      <span className="font-medium">{n}</span>
      {' '}
      {children}
    </StatusDot>
  );
}

Count.propTypes = {
  n: PropTypes.number.isRequired,
  tone: PropTypes.string.isRequired,
  hollow: PropTypes.bool,
  children: PropTypes.node.isRequired,
  testId: PropTypes.string.isRequired,
};

/**
 * The outcome summary bar: "12 succeeded, 2 partial, 3 still to run" over the scheduled (non-repeating) jobs' fires,
 * then the repeating jobs' own tally ("8 failed runs across 5 jobs", or "no failures"), then the link into Runs for
 * that day (hidden for a future day).
 *
 * @param {object} props
 * @param {object} props.counts from {@code summaryCounts}
 * @param {{failedRuns: number, jobsWithFailures: number}|null} props.tally the served repeating tally
 * @param {string} props.date the viewed UK date
 * @param {string} props.today today's UK date
 */
export function DaySummaryBar({ counts, tally, date, today }) {
  const failedRuns = tally?.failedRuns ?? 0;
  const jobsWithFailures = tally?.jobsWithFailures ?? 0;
  const linkLabel = runsLinkLabel(date, today);
  return (
    <div
      className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border border-plex-border px-3 py-2 text-[13px]"
      data-testid="scheduler-day-summary"
    >
      <Count n={counts.succeeded} tone="go" testId="scheduler-day-count-succeeded">succeeded</Count>
      <Count n={counts.partial} tone="amber" testId="scheduler-day-count-partial">partial</Count>
      <Count n={counts.failed} tone="coral" testId="scheduler-day-count-failed">failed</Count>
      <Count n={counts.missed} tone="coral" hollow testId="scheduler-day-count-missed">missed</Count>
      <Count n={counts.running} tone="tide" testId="scheduler-day-count-running">running</Count>
      <Count n={counts.skipped} tone="off" testId="scheduler-day-count-skipped">skipped</Count>
      <Count n={counts.toRun} tone="off" hollow testId="scheduler-day-count-to-run">still to run</Count>
      <span className="text-plex-text-secondary" data-testid="scheduler-day-repeating-tally">
        {'Repeating jobs: '}
        {failedRuns > 0
          ? (
            <>
              <span className="text-plex-coral">{`${failedRuns} failed ${failedRuns === 1 ? 'run' : 'runs'}`}</span>
              {` across ${jobsWithFailures} ${jobsWithFailures === 1 ? 'job' : 'jobs'}`}
            </>
          )
          : 'no failures'}
      </span>
      {linkLabel != null && (
        <a
          href={runsDayHref(date)}
          className="flex items-center text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold sm:ml-auto max-[639px]:min-h-11"
          data-testid="scheduler-day-runs-link"
        >
          {`Open ${linkLabel} in Runs →`}
        </a>
      )}
    </div>
  );
}

DaySummaryBar.propTypes = {
  counts: PropTypes.object.isRequired,
  tally: PropTypes.shape({ failedRuns: PropTypes.number, jobsWithFailures: PropTypes.number }),
  date: PropTypes.string.isRequired,
  today: PropTypes.string.isRequired,
};

/**
 * The legend entries. {@code drawn} is false for the marks the Repeating section, watchdog windows and arrows will draw
 * (S5b): they join the legend when they are on the picture, so it never names something the reader cannot see.
 */
const LEGEND = [
  { key: 'sun', drawn: true, swatch: 'bg-scheduler-sun', label: 'sun-relative' },
  { key: 'cron', drawn: true, swatch: 'bg-tide', label: 'cron' },
  { key: 'repeating', drawn: false, swatch: 'bg-scheduler-repeating', label: 'repeating' },
  { key: 'paused', drawn: true, swatch: 'border border-dashed border-plex-text-muted', label: 'paused' },
  { key: 'watchdog', drawn: false, swatch: 'bg-plex-coral', label: 'watchdog deadline' },
];

/** The status dots, in the design's order. */
const LEGEND_DOTS = [
  { key: 'ok', dot: 'bg-plex-ok', label: 'ok' },
  { key: 'partial', dot: 'bg-verdict-marginal', label: 'partial' },
  { key: 'failed', dot: 'bg-plex-coral', label: 'failed' },
  { key: 'missed', dot: 'border border-plex-coral', label: 'missed' },
  { key: 'to-run', dot: 'border border-plex-text-muted', label: 'to run' },
];

/** The legend under the controls: the mark colours, the gold £, and the status dots. Wraps on a phone. */
export function DayLegend() {
  return (
    <ul className="flex flex-wrap items-center gap-x-4 gap-y-1.5 font-mono text-[11px] text-plex-text-secondary" data-testid="scheduler-day-legend">
      {LEGEND.filter((item) => item.drawn).map((item) => (
        <li key={item.key} className="flex items-center gap-1.5" data-testid={`scheduler-legend-${item.key}`}>
          <i className={`inline-block h-2.5 w-3.5 rounded-[2px] ${item.swatch}`} aria-hidden="true" />
          {item.label}
        </li>
      ))}
      <li className="text-skyeval-version" data-testid="scheduler-legend-claude">{'£ uses Claude'}</li>
      {LEGEND_DOTS.map((item) => (
        <li key={item.key} className="flex items-center gap-1.5" data-testid={`scheduler-legend-${item.key}`}>
          <i className={`inline-block size-2 rounded-full ${item.dot}`} aria-hidden="true" />
          {item.label}
        </li>
      ))}
    </ul>
  );
}
