import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import { elapsedText, phaseLabel, phaseRows, phaseShort } from '../../utils/cycleView.js';
import { formatClockUk, formatSeconds } from '../../utils/runsView.js';

/** The dot per phase state: done is a filled go dot, current a tide dot with a halo, failed coral, not yet an outline. */
function dotClass(status) {
  if (status === 'COMPLETED') return 'bg-plex-ok';
  if (status === 'RUNNING') return 'bg-tide shadow-[0_0_0_3px_rgba(111,168,176,0.28)]';
  if (status === 'FAILED') return 'bg-plex-coral';
  return 'border border-plex-text-faint bg-transparent';
}

/** The row's second line: "{HH:MM} · {detail}", or "Runs after {phase}" for one not yet run. */
function detailLine(phase, runningShort) {
  if (phase.status === 'PENDING') return `Runs after ${runningShort}`;
  const detail = phase.detail || (phase.phase === 'RETRY_FAILED' && phase.status === 'COMPLETED' ? 'Nothing to retry' : '');
  return [formatClockUk(phase.startedAt), detail].filter(Boolean).join(' · ');
}

/** The right-hand figure: the duration, "{elapsed} so far" in tide while running, an em dash otherwise. */
function Duration({ phase }) {
  if (phase.durationSeconds == null && phase.status === 'RUNNING') {
    const elapsed = elapsedText(phase.startedAt);
    if (elapsed) {
      return (
        <span className="shrink-0 font-mono text-[12.5px] text-tide" data-testid={`cycle-phase-elapsed-${phase.phase}`}>
          {elapsed} <span className="text-[11px] text-plex-text-muted">so far</span>
        </span>
      );
    }
  }
  return (
    <span className="shrink-0 font-mono text-[12.5px] text-plex-text-secondary" data-testid={`cycle-phase-duration-${phase.phase}`}>
      {formatSeconds(phase.durationSeconds)}
    </span>
  );
}

Duration.propTypes = { phase: PropTypes.object.isRequired };

/**
 * The cycle's phases (plan §5.2): a vertical timeline in {@code sequenceOrder} with {@code PHASE_LABEL[p] || p}. The
 * section meta is "{done} of {n}" while running and the total duration once finished.
 *
 * @param {object} props
 * @param {object} props.detail the cycle detail
 */
export default function CyclePhases({ detail }) {
  const rows = phaseRows(detail);
  if (rows.length === 0) return null;
  const running = detail.run.status === 'RUNNING';
  const done = rows.filter((p) => p.status === 'COMPLETED').length;
  const current = rows.find((p) => p.status === 'RUNNING') ?? [...rows].reverse().find((p) => p.status !== 'PENDING');
  const meta = running ? `${done} of ${rows.length}` : formatSeconds(detail.run.durationSeconds);
  return (
    <PanelSection
      label="Phases"
      testId="cycle-phases"
      action={<span className="font-mono text-[11.5px] text-plex-text-muted" data-testid="cycle-phases-meta">{meta}</span>}
    >
      <ol className="flex flex-col gap-3.5">
        {rows.map((phase, index) => {
          const pending = phase.status === 'PENDING';
          const line = detailLine(phase, current ? phaseShort(current.phase) : 'the previous phase');
          return (
            <li
              key={`${phase.phase}-${phase.sequenceOrder}`}
              className={`relative pl-7 ${index < rows.length - 1 ? 'after:absolute after:bottom-[-14px] after:left-[4.5px] after:top-4 after:w-px after:bg-plex-border' : ''}`}
              data-testid={`cycle-phase-${phase.phase}`}
              data-state={pending ? 'pending' : phase.status.toLowerCase()}
            >
              <span
                className={`absolute left-0 top-[5px] size-2.5 rounded-full ${dotClass(phase.status)}`}
                aria-hidden="true"
              />
              <div className="flex items-baseline justify-between gap-3">
                <span className={`min-w-0 break-words text-[13.5px] font-medium ${pending ? 'text-plex-text-muted' : 'text-plex-text'}`}>
                  {phaseLabel(phase.phase)}
                </span>
                <Duration phase={phase} />
              </div>
              {line && (
                <p className="mt-0.5 break-words text-[12px] text-plex-text-secondary" data-testid={`cycle-phase-detail-${phase.phase}`}>
                  {line}
                </p>
              )}
            </li>
          );
        })}
      </ol>
    </PanelSection>
  );
}

CyclePhases.propTypes = {
  detail: PropTypes.shape({
    run: PropTypes.object.isRequired,
    phases: PropTypes.arrayOf(PropTypes.object),
  }).isRequired,
};
