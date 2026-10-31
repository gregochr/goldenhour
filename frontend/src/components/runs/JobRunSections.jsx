import { useState } from 'react';
import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import StatusDot from '../data/StatusDot.jsx';
import { formatCostGbp, formatCostUsd, formatTokens } from '../../utils/formatCost.js';
import { modelLabel } from '../../utils/modelLabels.js';
import { cacheBadge, parseCacheDiagnostics, tallyCacheDiagnostics } from '../../utils/cacheDiagnostics.js';
import { batchStatusView } from '../../utils/runStatus.js';
import { MAX_DIAGNOSED_ROWS, failedCallHeading, serviceStats } from '../../utils/jobRunPanel.js';

/** "Tokens: 100 in / 50 out / 25 cache write / 30 cache read", the optional parts only when above zero. */
function TokenLine({ input, output, cacheWrite, cacheRead }) {
  return (
    <p className="mt-0.5 text-xs text-plex-text-muted">
      Tokens: {formatTokens(input)} in
      {' / '}{formatTokens(output)} out
      {cacheWrite > 0 && <> / {formatTokens(cacheWrite)} cache write</>}
      {cacheRead > 0 && <> / {formatTokens(cacheRead)} cache read</>}
    </p>
  );
}

TokenLine.propTypes = {
  input: PropTypes.number.isRequired,
  output: PropTypes.number.isRequired,
  cacheWrite: PropTypes.number,
  cacheRead: PropTypes.number,
};

/** The cost as GBP with the USD small beside it. */
function Cost({ microDollars, rate }) {
  return (
    <>
      <span className="font-mono text-[12.5px] text-plex-text">{formatCostGbp(microDollars, rate)}</span>
      {microDollars > 0 && (
        <span className="ml-1.5 font-mono text-[11px] text-plex-text-muted">{formatCostUsd(microDollars)}</span>
      )}
    </>
  );
}

Cost.propTypes = { microDollars: PropTypes.number, rate: PropTypes.number };

/** One service's row: its name and figures on the left, its cost and outcome on the right. */
function ServiceRow({ stats, rate }) {
  const failRate = stats.count > 0 ? stats.errorCount / stats.count : 0;
  const failTone = failRate < 0.05 ? 'text-verdict-marginal' : 'text-plex-coral';
  return (
    <div className="flex items-start justify-between gap-3 border-b border-plex-border/60 py-2.5 text-[13px] last:border-b-0" data-testid={`job-run-service-${stats.service}`}>
      <div className="min-w-0">
        <p className="font-medium text-plex-text">{stats.service}</p>
        <p className="mt-0.5 text-xs text-plex-text-muted">
          {stats.count} calls, avg {stats.avgMs} ms
        </p>
        {stats.service === 'ANTHROPIC' && stats.inputTokens > 0 && (
          <TokenLine
            input={stats.inputTokens}
            output={stats.outputTokens}
            cacheWrite={stats.cacheWriteTokens}
            cacheRead={stats.cacheReadTokens}
          />
        )}
        {stats.errorCount > 0 && (
          <p className={`mt-0.5 text-xs ${failTone}`}>
            {stats.errorCount} failures ({(failRate * 100).toFixed(2)}%)
          </p>
        )}
      </div>
      <div className="shrink-0 text-right">
        <Cost microDollars={stats.costMicroDollars} rate={rate} />
        {stats.errorCount > 0
          ? <p className={`mt-0.5 text-xs ${failTone}`}>{stats.errorCount} errors</p>
          : <p className="mt-0.5 text-xs text-plex-text-muted">all ok</p>}
      </div>
    </div>
  );
}

ServiceRow.propTypes = { stats: PropTypes.object.isRequired, rate: PropTypes.number };

/** The batch's token summary row, for a batch run with no per-call rows. */
function BatchRow({ summary, rate }) {
  const status = batchStatusView(summary.status);
  const hasTokens = summary.totalInputTokens > 0 || summary.totalOutputTokens > 0;
  return (
    <div className="flex items-start justify-between gap-3 border-b border-plex-border/60 py-2.5 text-[13px]" data-testid="job-run-batch-row">
      <div className="min-w-0">
        <p className="font-medium text-plex-text">ANTHROPIC (Batch)</p>
        <p className="mt-0.5 text-xs text-plex-text-muted">
          {summary.requestCount} requests: {summary.succeededCount ?? 0} succeeded, {summary.erroredCount ?? 0} errored
        </p>
        {hasTokens ? (
          <TokenLine
            input={summary.totalInputTokens}
            output={summary.totalOutputTokens}
            cacheWrite={summary.totalCacheCreationTokens}
            cacheRead={summary.totalCacheReadTokens}
          />
        ) : (
          <p className="mt-0.5 text-xs text-plex-text-muted">No token data — batch failed before processing</p>
        )}
      </div>
      <div className="shrink-0 text-right">
        <Cost microDollars={summary.estimatedCostMicroDollars} rate={rate} />
        <p className="mt-0.5">
          <StatusDot tone={status.tone} halo={status.halo} testId="job-run-batch-row-status">{summary.status}</StatusDot>
        </p>
      </div>
    </div>
  );
}

BatchRow.propTypes = { summary: PropTypes.object.isRequired, rate: PropTypes.number };

/**
 * The Calls section: one row per service (calls, average duration, tokens, errors, cost), or the batch's token
 * summary for a batch run with no per-call rows, then the total cost. Shows "No API calls recorded" for none.
 *
 * @param {object} props
 * @param {Array<object>} props.apiCalls the run's call rows
 * @param {object|null} props.batchSummary the batch's token and cost summary, for a batch run
 * @param {number|null} props.rate GBP per USD, stored with the run
 * @param {boolean} props.loading true until the first load lands
 * @param {string|null} props.error the load error, if any
 */
export function CallsSection({ apiCalls, batchSummary, rate = null, loading, error = null }) {
  if (loading) {
    return (
      <PanelSection label="Calls" testId="job-run-calls">
        <p className="text-sm text-plex-text-muted">Loading...</p>
      </PanelSection>
    );
  }
  if (error) {
    return (
      <PanelSection label="Calls" testId="job-run-calls">
        <p className="text-sm text-plex-coral" data-testid="job-run-calls-error">Error: {error}</p>
      </PanelSection>
    );
  }
  const stats = serviceStats(apiCalls);
  const batchOnly = batchSummary != null && stats.length === 0;
  // The batch's own estimate stands in when there are no per-call rows to add up.
  const total = batchSummary?.estimatedCostMicroDollars
    || stats.reduce((sum, s) => sum + s.costMicroDollars, 0);
  return (
    <PanelSection label="Calls" testId="job-run-calls">
      {batchOnly && <BatchRow summary={batchSummary} rate={rate} />}
      {!batchOnly && stats.length === 0 && (
        <p className="text-sm text-plex-text-muted">No API calls recorded</p>
      )}
      {!batchOnly && stats.map((s) => <ServiceRow key={s.service} stats={s} rate={rate} />)}
      {total > 0 && (
        <div className="mt-2.5 flex items-center justify-between border-t border-plex-border pt-2.5" data-testid="job-run-total-cost">
          <span className="text-[13px] font-medium text-plex-text">Total cost</span>
          <span className="text-right">
            <span className="font-mono text-base font-semibold text-plex-text">{formatCostGbp(total, rate)}</span>
            <span className="block font-mono text-[11px] text-plex-text-muted">{formatCostUsd(total)}</span>
          </span>
        </div>
      )}
    </PanelSection>
  );
}

CallsSection.propTypes = {
  apiCalls: PropTypes.arrayOf(PropTypes.object).isRequired,
  batchSummary: PropTypes.object,
  rate: PropTypes.number,
  loading: PropTypes.bool.isRequired,
  error: PropTypes.string,
};

/**
 * The Evaluations section: the Anthropic calls counted by date, event and model, in that order. Hidden when there
 * is nothing to count.
 *
 * @param {object} props
 * @param {Array<{date: string, event: string, model: string, count: number}>} props.rows the breakdown rows
 */
export function EvaluationsSection({ rows }) {
  if (rows.length === 0) return null;
  return (
    <PanelSection label="Evaluations" testId="job-run-evaluations">
      <table className="w-full text-left text-[13px]">
        <thead>
          <tr className="text-xs text-plex-text-muted">
            <th scope="col" className="pb-1.5 pr-2 font-normal">Date</th>
            <th scope="col" className="pb-1.5 pr-2 font-normal">Event</th>
            <th scope="col" className="pb-1.5 pr-2 font-normal">Model</th>
            <th scope="col" className="pb-1.5 text-right font-normal">Count</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.date}|${row.event}|${row.model}`} className="border-t border-plex-border/60">
              <td className="py-1.5 pr-2 font-mono text-[12.5px] text-plex-text">{row.date}</td>
              <td className="py-1.5 pr-2 text-plex-text">{row.event}</td>
              <td className="py-1.5 pr-2 text-plex-text">{modelLabel(row.model)}</td>
              <td className="py-1.5 text-right font-mono text-[12.5px] text-plex-text">{row.count}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </PanelSection>
  );
}

EvaluationsSection.propTypes = {
  rows: PropTypes.arrayOf(PropTypes.shape({
    date: PropTypes.string.isRequired,
    event: PropTypes.string.isRequired,
    model: PropTypes.string.isRequired,
    count: PropTypes.number.isRequired,
  })).isRequired,
};

/** A disclosure row: a label, its count and a chevron; the content shows only while it is open. */
function Disclosure({ id, label, count, children }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="border-b border-plex-border/60 last:border-b-0" data-testid={`diagnostics-${id}`}>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        className="flex min-h-9 w-full cursor-pointer items-center gap-2 py-2 text-left text-[13px] text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
        data-testid={`diagnostics-${id}-toggle`}
      >
        <span className="min-w-0 flex-1">{label}</span>
        <span className="font-mono text-[12.5px] text-plex-text-secondary">{count}</span>
        <span className="w-3 text-center text-plex-text-muted" aria-hidden="true">{open ? '▾' : '▸'}</span>
      </button>
      {open && <div className="pb-2.5">{children}</div>}
    </div>
  );
}

Disclosure.propTypes = {
  id: PropTypes.string.isRequired,
  label: PropTypes.string.isRequired,
  count: PropTypes.number.isRequired,
  children: PropTypes.node.isRequired,
};

/** The failed calls, each headed by its service and what is known about why. */
function FailedCalls({ calls }) {
  return (
    <div className="space-y-1">
      {calls.map((call) => (
        <div key={call.id} className="rounded-sm bg-plex-coral/10 p-2 text-xs text-plex-coral">
          <div className="font-medium" data-testid="failed-call-heading">{failedCallHeading(call)}</div>
          <div>{call.errorMessage}</div>
        </div>
      ))}
    </div>
  );
}

FailedCalls.propTypes = { calls: PropTypes.arrayOf(PropTypes.object).isRequired };

/** The prompt-cache diagnostics: a tally across every diagnosed call, then the first rows. */
function CacheDiagnostics({ calls }) {
  return (
    <div data-testid="cache-diagnostics">
      <p className="text-xs text-plex-text-muted">
        Where each call&apos;s prompt diverged from the previous message it was compared with.
        {' '}messages_changed alone means the cached system prompt matched.
      </p>
      <div className="mt-2 flex flex-wrap gap-1" data-testid="cache-diagnostics-tally">
        {tallyCacheDiagnostics(calls).map(({ label, count }) => (
          <span
            key={label}
            className="rounded-sm border border-plex-border bg-plex-surface px-2 py-0.5 text-xs text-plex-text"
          >
            {label} &times;{count}
          </span>
        ))}
      </div>
      <ul className="mt-2 space-y-1">
        {calls.slice(0, MAX_DIAGNOSED_ROWS).map((call) => (
          <li
            key={call.id ?? call.customId}
            data-testid="cache-diagnostics-row"
            className="flex items-center justify-between gap-2 rounded-sm border border-plex-border bg-plex-surface px-2 py-1"
          >
            <span className="font-mono text-xs text-plex-text">
              {call.customId ?? `${call.targetDate ?? ''} ${call.targetType ?? ''}`.trim()}
            </span>
            <span className="text-xs text-plex-text-muted" data-testid="cache-badge">{cacheBadge(call)}</span>
          </li>
        ))}
      </ul>
      {calls.length > MAX_DIAGNOSED_ROWS && (
        <div className="mt-1 text-xs text-plex-text-muted" data-testid="cache-diagnostics-more">
          +{calls.length - MAX_DIAGNOSED_ROWS} more
        </div>
      )}
    </div>
  );
}

CacheDiagnostics.propTypes = { calls: PropTypes.arrayOf(PropTypes.object).isRequired };

/** The responses whose JSON needed the regex fallback, each opening to its raw text. */
function FallbackParses({ calls }) {
  const [open, setOpen] = useState(() => new Set());
  const toggle = (key) => setOpen((prev) => {
    const next = new Set(prev);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  });
  return (
    <div data-testid="fallback-parses">
      <p className="text-xs text-plex-text-muted">
        Responses whose JSON needed the regex fallback (possible over-capture). Raw text captured for diagnosis.
      </p>
      <ul className="mt-2 space-y-1">
        {calls.map((call) => {
          const key = call.id ?? call.customId;
          const isOpen = open.has(key);
          return (
            <li key={key} className="rounded-sm border border-plex-border bg-plex-surface">
              <button
                type="button"
                onClick={() => toggle(key)}
                aria-expanded={isOpen}
                data-testid="fallback-parse-toggle"
                className="flex w-full items-center justify-between px-2 py-1 text-left max-[639px]:min-h-11"
              >
                <span className="font-mono text-xs text-plex-text">{call.customId}</span>
                <span className="text-xs text-plex-text-muted">{isOpen ? '▲' : '▼'}</span>
              </button>
              {isOpen && (
                <pre
                  data-testid="fallback-parse-raw"
                  className="overflow-x-auto whitespace-pre-wrap border-t border-plex-border px-2 py-1 font-mono text-xs text-plex-text-muted"
                >
                  {call.responseBody}
                </pre>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

FallbackParses.propTypes = { calls: PropTypes.arrayOf(PropTypes.object).isRequired };

/**
 * The Diagnostics section: shown only when a count is above zero. One disclosure row each for failed calls, prompt-cache
 * diagnostics and fallback-parsed responses, each opening to its content.
 *
 * @param {object} props
 * @param {Array<object>} props.apiCalls the run's call rows
 */
export function DiagnosticsSection({ apiCalls }) {
  const failed = apiCalls.filter((call) => !call.succeeded);
  const diagnosed = apiCalls.filter((call) => parseCacheDiagnostics(call.cacheDiagnostics) !== null);
  // Responses whose JSON needed the regex fallback (possible over-capture), marked error_type=regex_fallback even
  // when the call otherwise succeeded. Admin-only screen, so no extra role gating here.
  const fallback = apiCalls.filter((call) => call.errorType === 'regex_fallback');
  if (failed.length === 0 && diagnosed.length === 0 && fallback.length === 0) return null;
  return (
    <PanelSection label="Diagnostics" testId="job-run-diagnostics">
      {failed.length > 0 && (
        <Disclosure id="failed" label="Failed calls" count={failed.length}>
          <FailedCalls calls={failed} />
        </Disclosure>
      )}
      {diagnosed.length > 0 && (
        <Disclosure id="cache" label="Prompt-cache diagnostics" count={diagnosed.length}>
          <CacheDiagnostics calls={diagnosed} />
        </Disclosure>
      )}
      {fallback.length > 0 && (
        <Disclosure id="fallback" label="Fallback-parsed responses" count={fallback.length}>
          <FallbackParses calls={fallback} />
        </Disclosure>
      )}
    </PanelSection>
  );
}

DiagnosticsSection.propTypes = { apiCalls: PropTypes.arrayOf(PropTypes.object).isRequired };
