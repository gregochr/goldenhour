import { useState } from 'react';
import PropTypes from 'prop-types';
import { modelLabel } from '../../utils/modelLabels.js';
import {
  formatAgo, formatClockUk, formatDayUk, formatGbp, formatPct, formatSeconds, paginateSessions, passTone,
  triggerLabel,
} from '../../utils/skyEval.js';
import { Bar, CARD_CLASS, ModelName, Swatch, TONE_TEXT, VersionTag } from './parts.jsx';

const SESSION_GRID =
  'grid grid-cols-[96px_74px_minmax(0,1.3fr)_minmax(0,2.2fr)_64px_82px] items-start gap-3 max-[899px]:grid-cols-[minmax(0,1fr)_auto_auto]';
const RUN_GRID =
  'grid grid-cols-[minmax(0,1fr)_70px_90px_70px_70px] items-center gap-2.5 max-[759px]:grid-cols-[minmax(0,1fr)_60px_60px]';

/** Status words, dot colours and text colours by session or run status (whole literal strings). */
const STATUS_VIEW = {
  DONE: { label: 'Done', dot: 'bg-verdict-go', text: '' },
  COMPLETED: { label: 'Done', dot: 'bg-verdict-go', text: '' },
  FAILED: { label: 'Failed', dot: 'bg-plex-coral', text: 'text-plex-coral' },
  RUNNING: { label: 'Running', dot: 'bg-tide animate-pulse motion-reduce:animate-none', text: 'text-tide' },
};

/** The status dot and word. */
function Status({ status }) {
  const view = STATUS_VIEW[status] ?? STATUS_VIEW.DONE;
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap text-[12.5px] ${view.text}`} data-testid="sky-eval-session-status" data-status={status}>
      <span aria-hidden="true" className={`h-1.5 w-1.5 flex-none rounded-full ${view.dot}`} />
      {view.label}
    </span>
  );
}

Status.propTypes = { status: PropTypes.string.isRequired };

/** The per-run table shown beside an expanded session. */
function SessionRuns({ session }) {
  return (
    <div
      className="mt-1 flex flex-col overflow-hidden rounded-lg border border-plex-border"
      data-testid={`sky-eval-session-runs-${session.id}`}
    >
      <div className={`${RUN_GRID} bg-plex-panel px-2.5 py-[7px] font-mono text-[10px] font-medium uppercase tracking-widest text-plex-text-muted`}>
        <span>Run</span>
        <span className="text-right">In band</span>
        <span className="text-right max-[759px]:hidden">Below / above</span>
        <span className="text-right">Cost</span>
        <span className="text-right max-[759px]:hidden">Time</span>
      </div>
      {session.runs.map((r) => (
        <div key={r.id} className={`${RUN_GRID} border-t border-plex-border px-2.5 py-[7px] text-[12.5px]`} data-testid={`sky-eval-run-${r.id}`}>
          <span className="flex min-w-0 flex-wrap items-center gap-[7px]">
            <ModelName model={r.model} />
            <span className="font-mono text-[11px] text-plex-text-muted">#{r.id} {'·'} {r.fixtureCount}{'×'}{r.runsPerFixture}</span>
          </span>
          {r.status === 'RUNNING' && (
            <>
              <span className="col-span-2 max-[759px]:col-span-1" data-testid={`sky-eval-run-progress-${r.id}`}>
                {r.progress == null ? (
                  <span className="text-xs text-tide">Running{'…'}</span>
                ) : (
                  <Bar widthPct={Math.round(r.progress * 100)} fillClass="fill-tide" />
                )}
              </span>
              <span className="text-right font-mono text-xs">~{formatGbp(r.costGbp)}</span>
              <span className="max-[759px]:hidden" />
            </>
          )}
          {r.status === 'FAILED' && (
            <>
              <span className="text-right font-mono text-xs text-plex-coral">{'—'}</span>
              <span className="text-right text-[11.5px] text-plex-coral max-[759px]:hidden" data-testid={`sky-eval-run-error-${r.id}`}>
                {r.errorMessage ?? 'Failed'}
              </span>
              <span className="text-right font-mono text-xs">{'—'}</span>
              <span className="max-[759px]:hidden" />
            </>
          )}
          {r.status === 'COMPLETED' && (
            <>
              <span className="text-right font-mono text-xs">{formatPct(r.inBandPct)}</span>
              <span className="text-right font-mono text-xs max-[759px]:hidden">{r.below} / {r.above}</span>
              <span className="text-right font-mono text-xs">{formatGbp(r.costGbp)}</span>
              <span className="text-right font-mono text-xs max-[759px]:hidden">
                {r.durationMs == null ? '—' : formatSeconds(r.durationMs / 1000)}
              </span>
            </>
          )}
        </div>
      ))}
    </div>
  );
}

SessionRuns.propTypes = {
  session: PropTypes.shape({ id: PropTypes.number.isRequired, runs: PropTypes.array.isRequired }).isRequired,
};

/** One session: its summary button, then (when open) its runs, beside the button rather than inside it. */
function SessionRow({ session, open, onToggle, versionMessages, now }) {
  const running = session.status === 'RUNNING';
  return (
    <div className="border-t border-plex-border" data-testid={`sky-eval-session-${session.id}`}>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => onToggle(session.id)}
        className={`${SESSION_GRID} w-full cursor-pointer px-1.5 py-2.5 text-left text-[13px] hover:bg-[rgba(242,231,211,0.03)] focus-visible:outline-2 focus-visible:outline-plex-gold ${
          open ? 'bg-[rgba(242,231,211,0.05)] shadow-[inset_2px_0_0_var(--color-plex-text)]' : ''
        }`}
        data-testid={`sky-eval-session-toggle-${session.id}`}
      >
        <span className="flex flex-col">
          <b className="font-medium">{formatAgo(session.startedAt, now)}</b>
          <span className="font-mono text-[11px] text-plex-text-muted">{formatDayUk(session.startedAt)} {formatClockUk(session.startedAt)}</span>
        </span>
        <span>
          <span className="whitespace-nowrap rounded border border-plex-border-light px-1 font-mono text-[9.5px] font-medium uppercase tracking-[0.1em] text-plex-text-muted" data-testid="sky-eval-session-trigger">
            {triggerLabel(session.trigger)}
          </span>
        </span>
        <span className="flex min-w-0 flex-col gap-px max-[899px]:hidden">
          {session.version ? (
            <>
              <VersionTag shortSha={session.version.slice(0, 7)} />
              <span className="truncate text-xs text-plex-text-secondary">{versionMessages.get(session.version) ?? ''}</span>
            </>
          ) : (
            <span className="text-xs text-plex-text-muted">unknown version</span>
          )}
        </span>
        <span className="flex flex-wrap gap-1.5 max-[899px]:order-last max-[899px]:col-span-3" data-testid="sky-eval-session-models">
          {session.byModel.map((b) => (
            <span key={b.model} className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-md border border-plex-border px-[7px] py-px font-mono text-[11.5px]">
              <Swatch model={b.model} />
              {modelLabel(b.model)}
              {b.runCount > 1 && <em className="not-italic text-plex-text-muted">{'×'}{b.runCount}</em>}
              <b className={`font-medium ${TONE_TEXT[passTone(b.inBandPct)]}`} data-tone={passTone(b.inBandPct)}>{formatPct(b.inBandPct)}</b>
            </span>
          ))}
        </span>
        <span className="text-right font-mono text-[12.5px] max-[899px]:hidden">{running ? '~' : ''}{formatGbp(session.costGbp)}</span>
        <Status status={session.status} />
      </button>
      {open && <div className="px-1.5 pb-2.5"><SessionRuns session={session} /></div>}
    </div>
  );
}

SessionRow.propTypes = {
  session: PropTypes.object.isRequired,
  open: PropTypes.bool.isRequired,
  onToggle: PropTypes.func.isRequired,
  versionMessages: PropTypes.instanceOf(Map).isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
};

/**
 * The Runs tab: sessions (runs started together), ten at a time, each expandable to its per-run table with
 * progress while running and the error text when failed.
 *
 * @param {object} props
 * @param {Array<object>} props.sessions the served sessions, newest first
 * @param {Array<object>} props.versions the served versions (for the commit message column)
 * @param {number|null} props.openId the expanded session's id
 * @param {(id: number) => void} props.onToggle expands or collapses a session
 * @param {Date} props.now the moment ages are measured to
 */
export default function RunsTab({ sessions, versions, openId, onToggle, now }) {
  const [showAll, setShowAll] = useState(false);
  const { shown, hidden } = paginateSessions(sessions, showAll);
  const versionMessages = new Map(versions.map((v) => [v.sha, v.message]));
  const runCount = sessions.reduce((n, s) => n + s.runs.length, 0);
  return (
    <section className={CARD_CLASS} data-testid="sky-eval-runs-tab">
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h3 className="text-sm font-semibold">Eval sessions</h3>
        <span className="font-mono text-[11px] text-plex-text-muted">
          runs started together are grouped {'·'} {runCount} runs in {sessions.length} sessions
        </span>
      </div>
      {sessions.length === 0 ? (
        <p className="text-[13px] text-plex-text-muted" data-testid="sky-eval-runs-empty">No eval runs yet.</p>
      ) : (
        <div className="flex flex-col">
          <div className={`${SESSION_GRID} px-1.5 pb-2 font-mono text-[10px] font-medium uppercase tracking-widest text-plex-text-muted max-[899px]:hidden`}>
            <span>When</span>
            <span>Trigger</span>
            <span>Version</span>
            <span>Models {'·'} in band</span>
            <span className="text-right">Cost</span>
            <span>Status</span>
          </div>
          {shown.map((s) => (
            <SessionRow key={s.id} session={s} open={openId === s.id} onToggle={onToggle} versionMessages={versionMessages} now={now} />
          ))}
        </div>
      )}
      {hidden > 0 && (
        <button
          type="button"
          onClick={() => setShowAll(true)}
          className="min-h-8 w-fit cursor-pointer rounded-lg border border-plex-border bg-plex-surface-light px-3 py-1.5 text-[13px] font-medium hover:border-plex-border-light focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="sky-eval-sessions-more"
        >
          Show {hidden} older session{hidden === 1 ? '' : 's'}
        </button>
      )}
    </section>
  );
}

RunsTab.propTypes = {
  sessions: PropTypes.array.isRequired,
  versions: PropTypes.array.isRequired,
  openId: PropTypes.number,
  onToggle: PropTypes.func.isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
};
