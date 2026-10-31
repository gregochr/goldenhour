import PropTypes from 'prop-types';
import { formatSeconds, formatSpendGbp, formatSpendUsd } from '../../utils/runsView.js';

/** One cell of the strip: a label, a mono value and a sub-line. */
function Cell({ label, value, sub = '', valueClass = 'text-plex-text', testId }) {
  return (
    <div className="min-w-0 bg-plex-surface px-4 py-3 max-[639px]:px-3" data-testid={testId}>
      <p className="text-[11.5px] text-plex-text-muted">{label}</p>
      <p className={`mt-0.5 font-mono text-[20px] leading-tight ${valueClass}`} data-testid={`${testId}-value`}>{value}</p>
      <p className="mt-0.5 min-h-4 text-[11.5px] text-plex-text-muted" data-testid={`${testId}-sub`}>{sub}</p>
    </div>
  );
}

Cell.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  sub: PropTypes.string,
  valueClass: PropTypes.string,
  testId: PropTypes.string.isRequired,
};

/** "1 degraded · 1 partial · 1 failed", with a zero part dropped. */
function attentionSub(summary) {
  return [
    summary.degraded > 0 ? `${summary.degraded} degraded` : null,
    summary.partial > 0 ? `${summary.partial} partial` : null,
    summary.failed > 0 ? `${summary.failed} failed` : null,
  ].filter(Boolean).join(' · ');
}

/**
 * The Runs summary strip (plan §5.2): Runs, Need attention, Spend and Median overnight cycle, four cells in a 1px-gap
 * grid, two columns at 640px and under. Every figure is the server's aggregate, so none moves as more pages load.
 *
 * @param {object} props
 * @param {object} props.summary the response's {@code summary}
 */
export default function RunsSummary({ summary }) {
  const attention = summary.needAttention > 0;
  const usd = formatSpendUsd(summary.spendMicroDollars);
  const hasMedian = summary.medianOvernightSeconds != null;
  return (
    <div className="@container">
    <div
      className="grid grid-cols-4 gap-px overflow-hidden rounded-[10px] border border-plex-border bg-plex-border @max-[640px]:grid-cols-2"
      data-testid="runs-summary"
    >
      <Cell
        label="Runs"
        value={String(summary.runs)}
        sub={`${summary.cycles} cycles · ${summary.other} other`}
        testId="runs-summary-runs"
      />
      <Cell
        label="Need attention"
        value={String(summary.needAttention)}
        sub={attentionSub(summary)}
        valueClass={attention ? 'text-verdict-marginal' : 'text-plex-text'}
        testId="runs-summary-attention"
      />
      <Cell
        label="Spend"
        value={formatSpendGbp(summary.spendMicroDollars, summary.spendGbpRate)}
        sub={usd}
        testId="runs-summary-spend"
      />
      <Cell
        label="Median overnight cycle"
        value={hasMedian ? formatSeconds(summary.medianOvernightSeconds) : '—'}
        sub={hasMedian ? `last ${summary.medianOvernightSample}` : ''}
        testId="runs-summary-median"
      />
    </div>
    </div>
  );
}

RunsSummary.propTypes = {
  summary: PropTypes.shape({
    runs: PropTypes.number,
    cycles: PropTypes.number,
    other: PropTypes.number,
    needAttention: PropTypes.number,
    degraded: PropTypes.number,
    partial: PropTypes.number,
    failed: PropTypes.number,
    spendMicroDollars: PropTypes.number,
    spendGbpRate: PropTypes.number,
    medianOvernightSeconds: PropTypes.number,
    medianOvernightSample: PropTypes.number,
  }).isRequired,
};
