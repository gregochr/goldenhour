import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import StatusDot from '../data/StatusDot.jsx';
import { formatRelativeTimeUk } from '../../utils/conversions.js';
import { totalRequests } from '../../utils/cycleView.js';
import { batchStatusView } from '../../utils/runStatus.js';
import { formatClockUk, midTruncate } from '../../utils/runsView.js';

const ROW_CLASS = 'flex min-h-12 w-full items-center justify-between gap-3 border-t border-plex-border py-2 text-left first:border-t-0';

/** "64 requests · 64 / 0 ok/err · 15:02 (1h 35m ago)". */
function batchLine(batch) {
  const ok = batch.succeededCount != null ? batch.succeededCount : '—';
  const err = batch.erroredCount != null ? batch.erroredCount : '—';
  const clock = formatClockUk(batch.submittedAt);
  const relative = batch.submittedAt ? formatRelativeTimeUk(batch.submittedAt) : '';
  const submitted = clock ? `${clock}${relative ? ` (${relative})` : ''}` : '';
  return [`${batch.requestCount ?? 0} requests`, `${ok} / ${err} ok/err`, submitted].filter(Boolean).join(' · ');
}

/** A batch row's body: id, figures and a RETRY tag on the left, status and (for a button) a chevron on the right. */
function BatchRowBody({ batch, chevron }) {
  const status = batchStatusView(batch.status);
  return (
    <>
      <span className="min-w-0">
        <span className="flex min-w-0 items-center gap-2">
          <span
            className="min-w-0 break-all font-mono text-[12.5px] font-semibold text-plex-text"
            title={batch.anthropicBatchId}
            data-testid={`cycle-batch-id-${batch.id}`}
          >
            {midTruncate(batch.anthropicBatchId)}
          </span>
          {batch.retry && (
            <span
              className="shrink-0 rounded-sm border border-plex-border px-1.5 py-px font-mono text-[9.5px] tracking-wide text-plex-text-secondary"
              title="Retry of transient failures from this cycle's precursor batch(es)"
              data-testid={`pipeline-batch-retry-badge-${batch.id}`}
            >
              RETRY
            </span>
          )}
        </span>
        <span className="mt-0.5 block break-words text-[12px] text-plex-text-secondary" data-testid={`cycle-batch-line-${batch.id}`}>
          {batchLine(batch)}
        </span>
      </span>
      <span className="flex shrink-0 items-center gap-2">
        <StatusDot tone={status.tone} halo={status.halo} testId={`cycle-batch-status-${batch.id}`}>{status.label}</StatusDot>
        {chevron && <span className="text-plex-text-muted" aria-hidden="true">&rsaquo;</span>}
      </span>
    </>
  );
}

BatchRowBody.propTypes = { batch: PropTypes.object.isRequired, chevron: PropTypes.bool.isRequired };

/**
 * The cycle's batches (plan §5.2): rows of at least 48px. A row with a {@code jobRunId} is a button that opens that
 * batch's job-run panel; one without is plain text, since there is nothing to open (a batch whose job-run link failed).
 *
 * @param {object} props
 * @param {Array<object>} props.batches the cycle detail's batches
 * @param {(jobRunId: number) => void} props.onOpen opens a batch
 */
export default function CycleBatches({ batches, onOpen }) {
  return (
    <PanelSection
      label="Batches"
      testId="cycle-batches"
      action={batches.length > 0 ? (
        <span className="font-mono text-[11.5px] text-plex-text-muted" data-testid="cycle-batches-meta">
          {batches.length} {'·'} {totalRequests(batches)} requests
        </span>
      ) : null}
    >
      {batches.length === 0 ? (
        <p className="text-[13px] text-plex-text-muted" data-testid="pipeline-no-batches">
          No batches were submitted. The cycle went straight to the briefing.
        </p>
      ) : (
        <ul>
          {batches.map((batch) => (
            <li key={batch.id}>
              {batch.jobRunId ? (
                <button
                  type="button"
                  className={`${ROW_CLASS} -mx-2 w-[calc(100%+1rem)] rounded px-2 hover:bg-plex-bg-elevated focus-visible:outline-2 focus-visible:outline-plex-gold`}
                  onClick={() => onOpen(batch.jobRunId)}
                  data-job-run-id={batch.jobRunId}
                  data-testid={`pipeline-batch-row-${batch.id}`}
                >
                  <BatchRowBody batch={batch} chevron />
                </button>
              ) : (
                <div className={ROW_CLASS} data-testid={`pipeline-batch-row-${batch.id}`}>
                  <BatchRowBody batch={batch} chevron={false} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </PanelSection>
  );
}

CycleBatches.propTypes = {
  batches: PropTypes.arrayOf(PropTypes.object).isRequired,
  onOpen: PropTypes.func.isRequired,
};
