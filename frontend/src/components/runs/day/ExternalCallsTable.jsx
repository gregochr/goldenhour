import PropTypes from 'prop-types';
import StatusDot from '../../data/StatusDot.jsx';
import useRunCalls from '../../../hooks/useRunCalls.js';
import { formatGbp, formatMs } from '../../../utils/runsDay.js';

/** How a service's calls went, as the Runs status vocabulary says it. */
const CALL_STATUS = {
  OK: { label: 'OK', tone: 'go' },
  ERRORS: { label: 'Errors', tone: 'amber' },
  FAILED: { label: 'Failed', tone: 'coral' },
};

/** The Calls cell: the count, or for an Anthropic batch row "5 batches · 216 req". */
function callsText(service) {
  if (service.batches != null) {
    return `${service.batches} ${service.batches === 1 ? 'batch' : 'batches'} · ${service.requests ?? 0} req`;
  }
  return String(service.calls);
}

/** The Cost cell: "free" when nothing was spent, else pounds to the penny. */
function costText(service) {
  return service.costMicroDollars > 0 ? formatGbp(service.costGbp) : 'free';
}

/** A line of table-box text (loading, nothing logged, a failure). */
function Note({ children, testId, tone = 'text-plex-text-secondary' }) {
  return <p className={`px-3 py-2.5 text-[12px] ${tone}`} data-testid={testId}>{children}</p>;
}

Note.propTypes = { children: PropTypes.node.isRequired, testId: PropTypes.string.isRequired, tone: PropTypes.string };

/**
 * An expanded Day row's external-calls table (runs-deep-dive-plan.md DD-6): "EXTERNAL CALLS · 3 SERVICES" over
 * Service, What it's for, Calls, Time, Cost and Status, one row per service and call kind, an error line under the
 * purpose in amber or coral. Fetched when the row opens. A fetch failure (with Retry) is its own state, distinct from a
 * run whose calls errored; a Sky eval run's calls are not logged, so it says that instead of fetching.
 *
 * @param {object} props
 * @param {string} props.runId {@code cycle-N}, {@code job-N} or {@code eval-N}
 * @param {boolean} props.logged false for a run kind that logs no calls (the Sky eval), which is never fetched
 */
export default function ExternalCallsTable({ runId, logged }) {
  const calls = useRunCalls(logged ? runId : null);
  let body;
  if (!logged || calls.data?.logged === false) {
    body = <Note testId="runs-calls-unlogged">Calls are not logged for Sky eval runs.</Note>;
  } else if (calls.loading) {
    body = (
      <Note testId="runs-calls-loading">
        <span className="animate-pulse motion-reduce:animate-none">Loading calls{'…'}</span>
      </Note>
    );
  } else if (calls.error) {
    body = (
      <p className="px-3 py-2.5 text-[12px] text-plex-coral" data-testid="runs-calls-error">
        Couldn&apos;t load the external calls: {calls.error}{' '}
        <button
          type="button"
          className="underline focus-visible:outline-2 focus-visible:outline-plex-gold"
          onClick={calls.retry}
          data-testid="runs-calls-retry"
        >
          Retry
        </button>
      </p>
    );
  } else if ((calls.data?.services ?? []).length === 0) {
    body = <Note testId="runs-calls-empty">No external calls were logged for this run.</Note>;
  } else {
    body = (
      <table className="w-full table-fixed border-collapse text-left text-[12px] @max-[440px]:block" data-testid="runs-calls-table">
        <thead className="sr-only">
          <tr>
            <th scope="col">Service</th>
            <th scope="col">What it&apos;s for</th>
            <th scope="col">Calls</th>
            <th scope="col">Time</th>
            <th scope="col">Cost</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody className="@max-[440px]:block">
          {calls.data.services.map((service) => {
            const status = CALL_STATUS[service.status] ?? CALL_STATUS.OK;
            const errorTone = service.status === 'FAILED' ? 'text-plex-coral' : 'text-verdict-marginal';
            return (
              <tr
                key={`${service.service}-${service.purpose}-${service.batches != null}`}
                className="border-t border-plex-border align-top first:border-t-0 @max-[440px]:flex @max-[440px]:flex-wrap @max-[440px]:items-baseline @max-[440px]:gap-x-3 @max-[440px]:px-3 @max-[440px]:py-2"
                data-testid={`runs-calls-row-${service.service}`}
              >
                <td className="w-[22%] px-3 py-2 font-medium text-plex-text @max-[440px]:w-auto @max-[440px]:flex-1 @max-[440px]:p-0">{service.label}</td>
                <td className="px-3 py-2 text-plex-text-secondary @max-[440px]:order-3 @max-[440px]:mt-0.5 @max-[440px]:w-full @max-[440px]:p-0">
                  {service.purpose}
                  {service.errorSummary && (
                    <span className={`mt-0.5 block break-words ${errorTone}`} data-testid="runs-calls-error-summary">
                      {service.errorSummary}
                    </span>
                  )}
                </td>
                <td className="w-[18%] px-3 py-2 font-mono text-plex-text-secondary @max-[440px]:order-4 @max-[440px]:mt-1 @max-[440px]:w-auto @max-[440px]:p-0">{callsText(service)}</td>
                <td className="hidden w-[11%] px-3 py-2 font-mono text-plex-text-secondary @min-[640px]:table-cell @max-[440px]:order-4 @max-[440px]:mt-1 @max-[440px]:block @max-[440px]:w-auto @max-[440px]:p-0">
                  {formatMs(service.durationMs)}
                </td>
                <td className="w-[11%] px-3 py-2 font-mono text-plex-text-secondary @max-[440px]:order-4 @max-[440px]:mt-1 @max-[440px]:w-auto @max-[440px]:p-0">{costText(service)}</td>
                <td className="w-[11%] px-3 py-2 @max-[440px]:order-2 @max-[440px]:w-auto @max-[440px]:p-0">
                  <StatusDot tone={status.tone} tintText>{status.label}</StatusDot>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    );
  }

  const count = calls.data?.services?.length ?? 0;
  const heading = logged && count > 0 ? `External calls · ${count} ${count === 1 ? 'service' : 'services'}` : 'External calls';
  return (
    <div className="@container mt-2 overflow-hidden rounded-lg border border-plex-border bg-plex-bg/40" data-testid="runs-calls">
      <p className="border-b border-plex-border px-3 py-1.5 font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-secondary">
        {heading}
      </p>
      {body}
    </div>
  );
}

ExternalCallsTable.propTypes = {
  runId: PropTypes.string.isRequired,
  logged: PropTypes.bool.isRequired,
};
