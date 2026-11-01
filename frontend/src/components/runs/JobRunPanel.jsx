import { useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import PanelSection from '../data/PanelSection.jsx';
import KvList from '../data/KvList.jsx';
import StatusDot from '../data/StatusDot.jsx';
import DispositionBreakdown from '../DispositionBreakdown.jsx';
import { CallsSection, DiagnosticsSection, EvaluationsSection } from './JobRunSections.jsx';
import useJobRunCalls from '../../hooks/useJobRunCalls.js';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { evaluationRows, scopeItems } from '../../utils/jobRunPanel.js';
import { batchStatusView, noteToneClass, runStatusView } from '../../utils/runStatus.js';
import { anthropicBatchId, formatClockUk, groupLabel, midTruncate } from '../../utils/runsView.js';

/** A batch id sets in mono 18px (not the serif title) so it reads as an id. */
const BATCH_TITLE_CLASS = 'break-all font-mono text-[18px] font-semibold leading-tight text-plex-text';

/** The meta line: "{group} · job run #{id} · {status} · {HH:MM} → {HH:MM}". */
function metaLine(detail) {
  const { run } = detail;
  const start = formatClockUk(run.startedAt);
  const end = formatClockUk(run.completedAt);
  const span = start && end ? `${start} → ${end}` : start;
  return [groupLabel(detail.group), `job run #${run.id}`, runStatusView(detail.status).label, span]
    .filter(Boolean).join(' · ');
}

/**
 * The job-run panel (plan §5.2): everything the old job-run row's expansion and the grid row showed, in sections that
 * appear only when they have data: Scope, Dispositions, Evaluations, Calls and Diagnostics. The same panel opens for
 * a standalone job run and for a cycle's batch (loaded by id, so a deep link works).
 *
 * <p>While this run is the one {@code RunProgressPanel} follows, the panel shows a "Progress" note pointing at that
 * panel instead of opening a second subscription.
 *
 * @param {object} props
 * @param {object|null} props.detail the job-run detail ({@code run}, {@code status}, {@code group}, {@code name},
 *   {@code note}, {@code noteTone}, {@code triggeredManually}, {@code batch}), or null while it loads
 * @param {boolean} props.loading true until the detail arrives
 * @param {string|null} props.error why the detail could not load
 * @param {boolean} props.notFound true when the server has no such job run
 * @param {boolean} props.isActiveRun true when the live progress panel is following this run
 * @param {() => void} props.onClose closes the panel
 * @param {string} [props.backLabel] a batch opened from its cycle: the cycle's name ("Cycle #412") for the "‹" row
 * @param {() => void} [props.onBack] returns to that cycle; with {@code backLabel} draws the row above the title on
 *   split and overlay (on a phone the layout's own back bar carries the same label, so no second back row is drawn)
 */
export default function JobRunPanel({
  detail = null, loading, error = null, notFound = false, isActiveRun = false, onClose, backLabel = undefined,
  onBack = undefined,
}) {
  const isMobile = useIsMobile();
  const backRow = onBack && backLabel && !isMobile ? (
    <button
      type="button"
      className="flex min-h-11 shrink-0 items-center gap-1 border-b border-plex-border px-[18px] text-left text-[13px] font-medium text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
      onClick={onBack}
      data-testid="job-run-back"
    >
      <span aria-hidden="true">&lsaquo;</span>
      {backLabel}
    </button>
  ) : null;
  const run = detail?.run ?? null;
  // The calls reload quietly when the run's own figures move (a running run finishing, a batch landing).
  const reloadKey = run ? `${detail.status}|${run.completedAt}|${run.succeeded}|${run.failed}` : '';
  const calls = useJobRunCalls(run?.id ?? null, run?.runType ?? null, reloadKey);
  const [dispositions, setDispositions] = useState({ id: null, count: null });

  if (!detail) {
    let message = 'Loading...';
    if (notFound) message = 'This job run no longer exists.';
    else if (error) message = error;
    return (
      <>
      {backRow}
      <PanelShell title="Job run" onClose={onClose} testIdPrefix="job-run-panel">
        <p
          className={`px-[18px] py-4 text-sm ${error && !loading ? 'text-plex-coral' : 'text-plex-text-muted'}`}
          data-testid="job-run-panel-state"
        >
          {message}
        </p>
      </PanelShell>
      </>
    );
  }

  const status = runStatusView(detail.status);
  const batchId = anthropicBatchId(detail);
  const isBatch = batchId != null;
  const title = isBatch ? midTruncate(batchId) : (detail.name || run.runType || 'Job run');
  const { items, strategies } = scopeItems(detail);
  const rows = items.map((item) => (item.batchStatus ? {
    ...item,
    value: <StatusDot tone={batchStatusView(item.value).tone} halo={batchStatusView(item.value).halo}>{batchStatusView(item.value).label}</StatusDot>,
  } : item));
  if (strategies.length > 0) {
    rows.push({
      label: 'Strategies',
      testId: 'job-run-strategies',
      value: (
        <span className="flex flex-wrap gap-1">
          {strategies.map((tag) => (
            <span
              key={tag.name}
              className={`rounded-sm border px-1.5 py-px font-mono text-[10.5px] ${
                tag.jfdi ? 'border-verdict-marginal/60 text-verdict-marginal' : 'border-plex-border text-plex-text-secondary'
              }`}
              data-testid={`job-run-strategy-${tag.name}`}
            >
              {tag.label}
            </span>
          ))}
        </span>
      ),
    });
  }

  const dispositionCount = dispositions.id === run.id ? dispositions.count : null;
  const nothingToShow = !calls.loading && !calls.error && rows.length === 0 && calls.apiCalls.length === 0
    && calls.batchSummary == null && dispositionCount === 0 && !isActiveRun;

  return (
    <>
    {backRow}
    <PanelShell
      title={title}
      titleClassName={isBatch ? BATCH_TITLE_CLASS : undefined}
      subtitle={<span data-testid="job-run-meta">{metaLine(detail)}</span>}
      onClose={onClose}
      testIdPrefix="job-run-panel"
      testId="job-run-panel"
    >
      {isActiveRun && (
        <PanelSection label="Progress" testId="job-run-progress-note">
          <p className="text-[13px] text-plex-text-secondary">Following this run live in the progress panel on the Runs list.</p>
        </PanelSection>
      )}

      {detail.note && (
        <div className="border-b border-plex-border px-[18px] py-3 max-[639px]:px-4" data-testid="job-run-note">
          <p className={`flex items-center gap-2 text-[13px] ${noteToneClass(detail.noteTone)}`}>
            <StatusDot tone={status.tone} halo={status.halo}>{status.label}</StatusDot>
            <span className="min-w-0 break-words [overflow-wrap:anywhere]">{detail.note}</span>
          </p>
        </div>
      )}

      {(rows.length > 0) && (
        <PanelSection label="Scope" testId="job-run-scope">
          <KvList items={rows} />
        </PanelSection>
      )}

      <DispositionBreakdown
        key={run.id}
        jobRunId={run.id}
        onLoaded={(count) => setDispositions({ id: run.id, count })}
      />

      <EvaluationsSection rows={evaluationRows(calls.apiCalls)} />

      <CallsSection
        apiCalls={calls.apiCalls}
        batchSummary={calls.batchSummary}
        rate={run.exchangeRateGbpPerUsd ?? null}
        loading={calls.loading}
        error={calls.error}
      />

      <DiagnosticsSection apiCalls={calls.apiCalls} />

      {nothingToShow && (
        <p className="px-[18px] py-4 text-sm italic text-plex-text-muted" data-testid="job-run-no-detail">
          No detail available
        </p>
      )}
    </PanelShell>
    </>
  );
}

JobRunPanel.propTypes = {
  detail: PropTypes.shape({
    run: PropTypes.shape({
      id: PropTypes.number.isRequired,
      runType: PropTypes.string,
      exchangeRateGbpPerUsd: PropTypes.number,
    }).isRequired,
    status: PropTypes.string,
    group: PropTypes.string,
    name: PropTypes.string,
    note: PropTypes.string,
    noteTone: PropTypes.string,
    triggeredManually: PropTypes.bool,
    batch: PropTypes.object,
  }),
  loading: PropTypes.bool.isRequired,
  error: PropTypes.string,
  notFound: PropTypes.bool,
  isActiveRun: PropTypes.bool,
  onClose: PropTypes.func.isRequired,
  backLabel: PropTypes.string,
  onBack: PropTypes.func,
};
