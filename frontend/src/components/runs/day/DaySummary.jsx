import PropTypes from 'prop-types';
import { formatClockUk, formatSeconds } from '../../../utils/runsView.js';
import { formatGbp, untilText } from '../../../utils/runsDay.js';

/** One cell of the strip: a label, a mono value and a sub-line. */
function Cell({ label, value, sub = '', valueClass = 'text-plex-text', subClass = 'text-plex-text-muted', testId }) {
  return (
    <div className="min-w-0 bg-plex-surface px-4 py-3 max-[639px]:px-3" data-testid={testId}>
      <p className="text-[11.5px] text-plex-text-muted">{label}</p>
      <p className={`mt-0.5 font-mono text-[20px] leading-tight ${valueClass}`} data-testid={`${testId}-value`}>{value}</p>
      <p className={`mt-0.5 min-h-4 text-[11.5px] ${subClass}`} data-testid={`${testId}-sub`}>{sub}</p>
    </div>
  );
}

Cell.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  sub: PropTypes.string,
  valueClass: PropTypes.string,
  subClass: PropTypes.string,
  testId: PropTypes.string.isRequired,
};

/** The sub-line pieces joined with a middle dot, dropping the empty ones. */
const joined = (...parts) => parts.filter((part) => part).join(' · ');

/** Today's four cells: Finished, Need attention, Spend so far, Next up. */
function todayCells(summary, now) {
  const attention = summary.needAttention > 0;
  const next = summary.nextUp;
  const stillToRun = summary.stillToRun ?? 0;
  const running = summary.running ?? 0;
  const avg = summary.avg30Gbp != null ? `30-day avg ${formatGbp(summary.avg30Gbp)}` : '';
  const projected = summary.projectedGbp != null ? `~${formatGbp(summary.projectedGbp)} by midnight` : '';
  return [
    {
      testId: 'runs-summary-finished',
      label: 'Finished',
      value: String(summary.finished ?? 0),
      sub: joined(running > 0 ? `${running} running` : '', stillToRun > 0 ? `${stillToRun} still to run today` : 'nothing else to run today'),
    },
    {
      testId: 'runs-summary-attention',
      label: 'Need attention',
      value: String(summary.needAttention),
      sub: attention ? (summary.attentionNames ?? []).join(', ') : 'Nothing so far',
      valueClass: attention ? 'text-verdict-marginal' : 'text-plex-text',
    },
    {
      testId: 'runs-summary-spend',
      label: 'Spend so far',
      value: formatGbp(summary.spendGbp),
      sub: joined(summary.spendComplete === false ? 'some costs still accruing' : '', projected, avg),
    },
    {
      testId: 'runs-summary-next',
      label: 'Next up',
      value: next ? `${next.estimated ? '~' : ''}${formatClockUk(next.at)}` : '—',
      sub: next ? joined(next.name, untilText(next.at, now)) : 'Nothing else today',
    },
  ];
}

/** A past day's four cells: Runs, Need attention, Spend, Slowest cycle vs usual. */
function pastCells(summary) {
  const attention = summary.needAttention > 0;
  const vs = summary.vsAvgPercent;
  let spendSub = 'No 30-day average yet';
  let spendSubClass = 'text-plex-text-muted';
  if (vs != null) {
    spendSub = `${vs > 0 ? '+' : ''}${vs}% vs 30-day avg ${summary.avg30Gbp != null ? formatGbp(summary.avg30Gbp) : ''}`.trim();
    if (vs > 10) spendSubClass = 'text-verdict-marginal';
    else if (vs < -10) spendSubClass = 'text-plex-ok';
  }
  const slow = summary.slowestCycle;
  return [
    {
      testId: 'runs-summary-runs',
      label: 'Runs',
      value: String(summary.runs ?? 0),
      sub: `${summary.cycles ?? 0} cycles ${'·'} ${summary.jobs ?? 0} jobs`,
    },
    {
      testId: 'runs-summary-attention',
      label: 'Need attention',
      value: String(summary.needAttention),
      sub: attention
        ? joined(summary.partial > 0 ? `${summary.partial} partial` : '', summary.failed > 0 ? `${summary.failed} failed` : '')
        : 'Nothing needed attention',
      valueClass: attention ? 'text-verdict-marginal' : 'text-plex-text',
    },
    {
      testId: 'runs-summary-spend',
      label: 'Spend',
      value: formatGbp(summary.spendGbp),
      sub: joined(summary.spendComplete === false ? 'some costs not final' : '', spendSub),
      subClass: spendSubClass,
    },
    {
      testId: 'runs-summary-slowest',
      label: 'Slowest cycle vs usual',
      value: slow ? formatSeconds(slow.durationSeconds) : '—',
      sub: slow
        ? joined(slow.name, slow.medianSeconds != null ? `median ${formatSeconds(slow.medianSeconds)}` : '')
        : 'No cycles ran',
    },
  ];
}

/**
 * The Day view's summary strip (the handoff's "Summary strip", four cells in 1px dividers). Every figure is served
 * (R-D1): today fills the today group (Finished, Need attention, Spend so far with the projection and the 30-day
 * average, Next up), a past day the past group (Runs, Need attention, Spend against the average, the slowest cycle).
 * The columns drop to two when the list column is 700px or narrower (a container query: the column shrinks when a
 * panel splits open).
 *
 * @param {object} props
 * @param {object} props.summary the served {@code RunDaySummary}
 * @param {boolean} props.isToday whether the day is today
 * @param {Date} [props.now] the real clock, for "in 3h 14m"
 */
export default function DaySummary({ summary, isToday, now = new Date() }) {
  const cells = isToday ? todayCells(summary, now) : pastCells(summary);
  return (
    <div className="@container">
      <div
        className="grid grid-cols-4 gap-px overflow-hidden rounded-[10px] border border-plex-border bg-plex-border @max-[700px]:grid-cols-2"
        data-testid="runs-summary"
      >
        {cells.map((cell) => <Cell key={cell.testId} {...cell} />)}
      </div>
    </div>
  );
}

DaySummary.propTypes = {
  summary: PropTypes.object.isRequired,
  isToday: PropTypes.bool.isRequired,
  now: PropTypes.instanceOf(Date),
};
