import PropTypes from 'prop-types';
import {
  cycleName, elapsedText, phaseLabel, phaseRows, phaseShort, waitingBatches,
} from '../../utils/cycleView.js';
import { formatClockUk } from '../../utils/runsView.js';

/** One segment of the bar: done is go, the current one a tide gradient, later ones the border colour. */
function segmentClass(status) {
  if (status === 'COMPLETED') return 'bg-plex-ok';
  if (status === 'RUNNING') return 'bg-gradient-to-r from-tide to-plex-border';
  if (status === 'FAILED') return 'bg-plex-coral';
  return 'bg-plex-border';
}

/**
 * The live strip (plan §5.2, R2b): shown above the summary only while the server names a running cycle
 * ({@code liveCycleId}). Line 1 is a pulse dot, "{name} is running", "Cycle #{id} · started {HH:MM}" and Open; line 2
 * a five-segment phase bar (decorative); line 3 "{phase} · waiting on {n} of {m} batches · {elapsed} so far", the
 * waiting clause only during the wait phase. Reduced motion stops the pulse.
 *
 * @param {object} props
 * @param {object} props.detail the cycle detail ({@code run}, {@code phases}, {@code batches})
 * @param {object|null} [props.item] the cycle's list row when loaded, for its served name
 * @param {() => void} props.onOpen opens the cycle panel
 */
export default function RunsLiveStrip({ detail, item = null, onOpen }) {
  const { run } = detail;
  const rows = phaseRows(detail);
  const { waiting, total } = waitingBatches(detail);
  const inWait = run.currentPhase === 'FORECAST_BATCH_WAIT';
  const elapsed = elapsedText(run.triggerTime);
  const line = [
    run.currentPhase ? phaseLabel(run.currentPhase) : null,
    inWait ? `waiting on ${waiting.length} of ${total} batches` : null,
    elapsed ? `${elapsed} so far` : null,
  ].filter(Boolean).join(' · ');
  return (
    <div
      className="rounded-[10px] border border-tide/40 bg-tide/10 px-4 py-3 max-[639px]:px-3"
      data-testid="runs-live-strip"
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span
          className="size-2 shrink-0 rounded-full bg-tide shadow-[0_0_0_3px_rgba(111,168,176,0.28)] animate-pulse motion-reduce:animate-none"
          aria-hidden="true"
          data-testid="runs-live-pulse"
        />
        <span className="text-[13px] font-semibold text-plex-text" data-testid="runs-live-title">
          {cycleName(run, item)} is running
        </span>
        <span className="font-mono text-[12px] text-plex-text-secondary" data-testid="runs-live-meta">
          Cycle #{run.id} {'·'} started {formatClockUk(run.triggerTime)}
        </span>
        <button
          type="button"
          className="ml-auto rounded px-2 text-[13px] font-medium text-plex-text hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
          onClick={onOpen}
          data-testid="runs-live-open"
        >
          Open
        </button>
      </div>
      <div className="mt-2 flex gap-2" aria-hidden="true" data-testid="runs-live-bar">
        {rows.map((p) => (
          <div key={p.phase} className="min-w-0 flex-1">
            <div className={`h-[3px] rounded-full ${segmentClass(p.status)}`} data-testid={`runs-live-seg-${p.phase}`} />
            <span className="mt-1 block truncate font-mono text-[10.5px] text-plex-text-muted">{phaseShort(p.phase)}</span>
          </div>
        ))}
      </div>
      {line && (
        <p className="mt-2 break-words text-[12.5px] text-plex-text-secondary" data-testid="runs-live-line">{line}</p>
      )}
    </div>
  );
}

RunsLiveStrip.propTypes = {
  detail: PropTypes.shape({
    run: PropTypes.object.isRequired,
    phases: PropTypes.arrayOf(PropTypes.object),
    batches: PropTypes.arrayOf(PropTypes.object),
  }).isRequired,
  item: PropTypes.shape({ name: PropTypes.string }),
  onOpen: PropTypes.func.isRequired,
};
