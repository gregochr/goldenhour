import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import KvList from '../data/KvList.jsx';
import CycleBatches from './CycleBatches.jsx';
import CycleComparison from './CycleComparison.jsx';
import CyclePhases from './CyclePhases.jsx';
import { formatRelativeTimeUk, formatTimestampUk } from '../../utils/conversions.js';
import { cycleMetaLine, cycleName, waitingBatches } from '../../utils/cycleView.js';
import { cycleStatusView } from '../../utils/runStatus.js';
import { formatClockUk, formatSpendUsd, midTruncate, runCostText } from '../../utils/runsView.js';
import { textButtonClass } from '../users/userFormClasses.js';

/** "msgbatch_01JBX…3F and msgbatch_01JBX…2D, submitted 15:03. Checked just now." */
function waitingDetail(waiting, submittedAt, fetchedAt) {
  const ids = waiting.map((b) => midTruncate(b.anthropicBatchId));
  const joined = ids.length > 1 ? `${ids.slice(0, -1).join(', ')} and ${ids.at(-1)}` : ids[0];
  const clock = formatClockUk(submittedAt);
  const checked = fetchedAt ? formatRelativeTimeUk(new Date(fetchedAt).toISOString()) : '';
  return [
    joined ? `${joined}${clock ? `, submitted ${clock}` : ''}.` : null,
    checked ? `Checked ${checked}.` : null,
  ].filter(Boolean).join(' ');
}

/** The tide-tinted box for a running cycle: what it is waiting on. */
function WaitingBox({ detail, fetchedAt }) {
  const { run } = detail;
  const { waiting, total, submittedAt } = waitingBatches(detail);
  if (run.status !== 'RUNNING' || !run.waitingOn) return null;
  const heading = waiting.length > 0 ? `Waiting on ${waiting.length} of ${total} batches` : run.waitingOn;
  return (
    <div className="border-b border-plex-border px-[18px] py-3 max-[639px]:px-4">
      <div className="rounded-lg border border-tide/40 bg-tide/10 px-3 py-2.5" data-testid="pipeline-run-detail-waiting">
        <p className="break-words text-[13px] font-semibold text-plex-text">{heading}</p>
        {waiting.length > 0 && (
          <p className="mt-0.5 break-words text-[12.5px] text-plex-text-secondary [overflow-wrap:anywhere]">
            {waitingDetail(waiting, submittedAt, fetchedAt)}
          </p>
        )}
      </div>
    </div>
  );
}

WaitingBox.propTypes = { detail: PropTypes.object.isRequired, fetchedAt: PropTypes.number };

/** The amber (degraded) or coral (failed) box with the server's failure reason. */
function FailureBox({ run }) {
  if (!run.failureReason || (run.status !== 'DEGRADED' && run.status !== 'FAILED')) return null;
  const failed = run.status === 'FAILED';
  return (
    <div className="border-b border-plex-border px-[18px] py-3 max-[639px]:px-4">
      <div
        className={`rounded-lg border px-3 py-2.5 ${failed ? 'border-plex-coral/50 bg-plex-coral/10' : 'border-verdict-marginal/50 bg-verdict-marginal/10'}`}
        data-testid={failed ? 'pipeline-run-detail-failure' : 'pipeline-run-detail-degraded'}
      >
        <p className={`text-[11px] font-semibold uppercase tracking-wider ${failed ? 'text-plex-coral' : 'text-verdict-marginal'}`}>
          {failed ? 'Failed' : 'Degraded'}
        </p>
        <p className="mt-0.5 break-words text-[13px] text-plex-text [overflow-wrap:anywhere]">{run.failureReason}</p>
      </div>
    </div>
  );
}

FailureBox.propTypes = { run: PropTypes.object.isRequired };

/**
 * The cycle panel (plan §5.2, R2b): everything the old Pipeline Runs detail showed, in sections that appear only when
 * they have data. Title is the cycle's name, then the meta, a waiting box (running) or a failure box (degraded or
 * failed), the comparison (only with one), the phases, the batches, and a footer with the batch cost and the
 * completed time. A batch row opens that batch's job-run panel.
 *
 * <p>The served cycle carries no cost, so the footer reads it from the cycle's list row ({@code item}); a cycle opened
 * by a link whose row is not on the loaded page shows an em dash.
 *
 * @param {object} props
 * @param {object|null} props.detail the cycle detail ({@code run}, {@code phases}, {@code batches}, {@code comparison})
 * @param {object|null} [props.item] the cycle's list row, when it is on the loaded page (name and cost)
 * @param {boolean} props.loading true until the cycle arrives
 * @param {string|null} [props.error] why it could not load
 * @param {boolean} [props.notFound] true when the server has no such cycle
 * @param {number|null} [props.fetchedAt] when the cycle was last read (the real clock), for "Checked {when}"
 * @param {number} props.id the cycle id (the panel's title while loading)
 * @param {boolean} [props.showRefresh] draws the Refresh text button (off on a phone, where the back bar carries it)
 * @param {() => void} props.onRefresh re-reads the cycle
 * @param {() => void} props.onClose closes the panel
 * @param {(jobRunId: number) => void} props.onOpenBatch opens a batch's job-run panel
 */
export default function CyclePanel({
  detail = null, item = null, loading, error = null, notFound = false, fetchedAt = null, id, showRefresh = true,
  onRefresh, onClose, onOpenBatch,
}) {
  if (!detail) {
    let message = 'Loading...';
    if (notFound) message = 'This cycle no longer exists.';
    else if (error) message = error;
    return (
      <PanelShell title={`Cycle #${id}`} onClose={onClose} testIdPrefix="cycle-panel">
        <p
          className={`px-[18px] py-4 text-sm ${error && !loading ? 'text-plex-coral' : 'text-plex-text-muted'}`}
          data-testid="cycle-panel-state"
        >
          {message}
        </p>
      </PanelShell>
    );
  }

  const { run } = detail;
  const status = cycleStatusView(run.status);
  const running = run.status === 'RUNNING';
  const usd = item ? formatSpendUsd(item.costMicroDollars) : '';
  const cost = item ? runCostText(item) : '—';
  const footerItems = [
    {
      label: running ? 'Batch cost so far' : 'Batch cost',
      testId: 'cycle-cost',
      value: (
        <span className="font-mono">
          {cost}
          {cost !== '—' && usd && <span className="ml-2 text-plex-text-muted">{usd}</span>}
        </span>
      ),
    },
    { label: 'Completed', testId: 'cycle-completed', value: run.completedAt ? formatTimestampUk(run.completedAt) : null },
  ];

  return (
    <PanelShell
      title={cycleName(run, item)}
      subtitle={<span data-testid="cycle-panel-meta">{cycleMetaLine(run, status.label)}</span>}
      onClose={onClose}
      testIdPrefix="cycle-panel"
      footer={(
        <div className="px-[18px] py-3 max-[639px]:px-4" data-testid="cycle-footer">
          <KvList items={footerItems} />
        </div>
      )}
    >
      {showRefresh && (
        <div className="flex justify-end border-b border-plex-border px-[18px] py-1.5">
          <button type="button" className={textButtonClass} onClick={onRefresh} data-testid="cycle-panel-refresh">
            Refresh
          </button>
        </div>
      )}
      {error && (
        <p className="border-b border-plex-border px-[18px] py-2 text-[12.5px] text-plex-coral max-[639px]:px-4" data-testid="cycle-panel-error">
          {error}
        </p>
      )}
      <WaitingBox detail={detail} fetchedAt={fetchedAt} />
      <FailureBox run={run} />
      {detail.comparison && <CycleComparison comparison={detail.comparison} />}
      <CyclePhases detail={detail} />
      <CycleBatches batches={detail.batches ?? []} onOpen={onOpenBatch} />
    </PanelShell>
  );
}

CyclePanel.propTypes = {
  detail: PropTypes.shape({
    run: PropTypes.object.isRequired,
    phases: PropTypes.arrayOf(PropTypes.object),
    batches: PropTypes.arrayOf(PropTypes.object),
    comparison: PropTypes.object,
  }),
  item: PropTypes.object,
  loading: PropTypes.bool.isRequired,
  error: PropTypes.string,
  notFound: PropTypes.bool,
  fetchedAt: PropTypes.number,
  id: PropTypes.number.isRequired,
  showRefresh: PropTypes.bool,
  onRefresh: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  onOpenBatch: PropTypes.func.isRequired,
};
