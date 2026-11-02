import PropTypes from 'prop-types';
import { modelLabel } from '../../utils/modelLabels.js';
import {
  comparedTone, formatAgo, formatCostMultiple, formatGbp, formatPct, formatPts, laneBarState,
} from '../../utils/skyEval.js';
import { CARD_CLASS, ModelName, ProdTag, Swatch, VersionTag } from './parts.jsx';

const COMPARED_TONE_TEXT = {
  better: 'text-verdict-go',
  worse: 'text-verdict-marginal',
  neutral: 'text-plex-text-secondary',
};

const BAR_BASE = 'flex flex-wrap items-center gap-2.5 rounded-lg border px-[11px] py-2 text-[13px]';
const BAR_CLASS = {
  held: `${BAR_BASE} border-[rgba(138,174,114,0.35)] bg-[rgba(138,174,114,0.08)]`,
  dropped: `${BAR_BASE} border-[rgba(232,89,63,0.4)] bg-[rgba(232,89,63,0.08)]`,
  unchecked: `${BAR_BASE} border-[rgba(224,165,66,0.4)] bg-[rgba(224,165,66,0.08)]`,
  neutral: `${BAR_BASE} border-plex-border bg-plex-panel`,
};

const LINK_CLASS =
  'ml-auto cursor-pointer text-[12.5px] text-tide hover:text-tide-light focus-visible:outline-2 focus-visible:outline-plex-gold';

/** One cell of the four-cell stat strip. */
function Stat({ label, value, sub, testId }) {
  return (
    <div className="flex min-w-0 flex-col bg-plex-surface px-3 py-[9px]" data-testid={testId}>
      <span className="text-[11.5px] leading-snug text-plex-text-muted">{label}</span>
      <b className="font-mono text-[19px] font-medium">{value}</b>
      <span className="text-[11.5px] leading-snug text-plex-text-muted">{sub}</span>
    </div>
  );
}

Stat.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.node.isRequired,
  sub: PropTypes.node.isRequired,
  testId: PropTypes.string.isRequired,
};

/**
 * The verdict card: the served headline, then the served per-model before and after, the commit's message and age,
 * and a note when a model not used in production dropped.
 *
 * @param {object} props
 * @param {object} props.verdict the served verdict
 * @param {object|null} props.head the running build
 * @param {Array<object>} props.models the served model rows
 * @param {Date} props.now the moment ages are measured to
 */
export function VerdictCard({ verdict, head, models, now }) {
  const lines = verdict.lines.filter((l) => l.afterPct != null);
  const nonProdDropped = verdict.droppedNonProduction
    ? models.filter((m) => m.status === 'DROPPED' && m.lanes.length === 0)
    : [];
  return (
    <section className={`${CARD_CLASS} gap-3`} data-testid="sky-eval-verdict" data-kind={verdict.kind}>
      <h2 className="font-serif text-[22px] font-semibold leading-tight tracking-[-0.01em] text-pretty" data-testid="sky-eval-verdict-headline">
        {verdict.headline}
      </h2>
      <p className="max-w-[800px] text-[13.5px] text-plex-text-secondary text-pretty" data-testid="sky-eval-verdict-body">
        {head?.sha && (
          <>
            {head.message && <b className="font-medium text-plex-text">{head.message}</b>}
            {' '}({head.shortSha}{head.committedAt ? `, ${formatAgo(head.committedAt, now)}` : ''}).{' '}
          </>
        )}
        {lines.map((l, i) => (
          <span key={l.model}>
            {i > 0 && ', '}
            {l.label}
            {l.beforePct != null && ` ${formatPct(l.beforePct)} →`}
            {' '}<b className="font-medium text-plex-text">{formatPct(l.afterPct)}</b>
          </span>
        ))}
        {lines.length > 0 && '.'}
        {nonProdDropped.map((m) => (
          <span key={m.model}>
            {' '}{m.label} dropped from {formatPct(m.previousVersionPct)} to{' '}
            <b className="font-medium text-plex-text">{formatPct(m.onHeadPct)}</b>, but it isn&apos;t used in
            production.
          </span>
        ))}
      </p>
    </section>
  );
}

VerdictCard.propTypes = {
  verdict: PropTypes.shape({
    kind: PropTypes.string.isRequired,
    headline: PropTypes.string.isRequired,
    lines: PropTypes.array.isRequired,
    droppedNonProduction: PropTypes.bool.isRequired,
  }).isRequired,
  head: PropTypes.object,
  models: PropTypes.array.isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
};

/** The lane card's status bar: held, dropped, not checked on HEAD, or nothing to compare. */
function StatusBar({ stats, head, onRunCheck }) {
  const state = laneBarState(stats);
  const tag = head?.sha ? <VersionTag shortSha={head.shortSha} /> : null;
  if (!head?.sha) {
    return <div className={BAR_CLASS.neutral} data-testid="sky-eval-lane-status" data-state="unknown">This build has no version recorded.</div>;
  }
  if (state === 'unchecked') {
    return (
      <div className={BAR_CLASS.unchecked} data-testid="sky-eval-lane-status" data-state="unchecked">
        <span>Not checked on {tag} yet.</span>
        <button type="button" className={LINK_CLASS} onClick={() => onRunCheck([stats.model])} data-testid="sky-eval-lane-run-check">
          Run a check
        </button>
      </div>
    );
  }
  if (state === 'dropped') {
    return (
      <div className={BAR_CLASS.dropped} data-testid="sky-eval-lane-status" data-state="dropped">
        <span>
          Dropped {formatPts(stats.deltaPts)} after {tag} ({formatPct(stats.previousVersionPct)} {'→'} {formatPct(stats.onHeadPct)}).
        </span>
      </div>
    );
  }
  if (state === 'held') {
    return (
      <div className={BAR_CLASS.held} data-testid="sky-eval-lane-status" data-state="held">
        <span>
          Held after {tag}: {formatPct(stats.previousVersionPct)} {'→'} {formatPct(stats.onHeadPct)} over {stats.onHeadRuns} run{stats.onHeadRuns === 1 ? '' : 's'}.
        </span>
      </div>
    );
  }
  return (
    <div className={BAR_CLASS.neutral} data-testid="sky-eval-lane-status" data-state="baseline">
      <span>Nothing to compare {tag} with yet: no earlier version of this model was checked.</span>
    </div>
  );
}

StatusBar.propTypes = {
  stats: PropTypes.object,
  head: PropTypes.object,
  onRunCheck: PropTypes.func.isRequired,
};

/**
 * One production lane: label, model, purpose, the stat strip, the status bar, the "Compared with" rows and the
 * stale-models footnote. Everything shown is served; this only lays it out.
 *
 * @param {object} props
 * @param {object} props.lane the served lane
 * @param {object|undefined} props.stats the lane model's row in {@code models}
 * @param {Array<object>} props.models every served model row (for the stale footnote)
 * @param {object|null} props.head the running build
 * @param {number} props.fixtureCount the fixture count
 * @param {Date} props.now the moment ages are measured to
 * @param {(models: string[]) => void} props.onRunCheck opens the Run a check panel prefilled
 */
export function LaneCard({ lane, stats, models, head, fixtureCount, now, onRunCheck }) {
  const stale = models.filter((m) => !m.fresh && m.lastTestedAt != null && m.model !== lane.model);
  const hasHead = head?.sha != null;
  return (
    <section className={CARD_CLASS} data-testid={`sky-eval-lane-${lane.runType}`}>
      <div className="flex flex-col gap-0.5">
        <span className="font-mono text-[10px] font-medium uppercase tracking-[0.12em] text-plex-text-muted">{lane.label}</span>
        <span className="flex items-center gap-[9px] font-serif text-[22px] font-semibold leading-tight">
          <Swatch model={lane.model} large />
          {modelLabel(lane.model)}
        </span>
        <span className="text-[12.5px] text-plex-text-muted">{lane.purpose}</span>
      </div>

      <div className="grid grid-cols-4 gap-px overflow-hidden rounded-[10px] border border-plex-border bg-plex-border max-[559px]:grid-cols-2" data-testid="sky-eval-lane-stats">
        <Stat
          label="Today's version"
          value={formatPct(stats?.onHeadPct)}
          sub={stats && stats.onHeadRuns > 0 && hasHead ? `${stats.onHeadRuns} run${stats.onHeadRuns === 1 ? '' : 's'} · ${head.shortSha}` : 'not run yet'}
          testId="sky-eval-lane-stat-head"
        />
        <Stat label="Last 4 weekly" value={formatPct(stats?.last4WeeklyPct)} sub="in band" testId="sky-eval-lane-stat-weekly" />
        <Stat
          label="Misses, last 4"
          value={stats && stats.last4Ratings > 0 ? (
            <>
              {stats.last4Below} <small className="text-[11px] text-plex-text-muted">below</small>{' '}
              {stats.last4Above} <small className="text-[11px] text-plex-text-muted">above</small>
            </>
          ) : '—'}
          sub={stats && stats.last4Ratings > 0 ? `of ${stats.last4Ratings} ratings` : 'no weekly runs yet'}
          testId="sky-eval-lane-stat-misses"
        />
        <Stat label="Per eval run" value={formatGbp(stats?.costPerRunGbp)} sub={`weekly, ${fixtureCount} fixtures`} testId="sky-eval-lane-stat-cost" />
      </div>

      <StatusBar stats={stats} head={head} onRunCheck={onRunCheck} />

      <div className="flex flex-col" data-testid="sky-eval-lane-compared">
        <span className="pb-1 font-mono text-[10px] font-medium uppercase tracking-widest text-plex-text-muted">
          Compared with, last 4 weekly
        </span>
        {lane.compared.map((c) => (
          <div
            key={c.model}
            className="grid grid-cols-[minmax(0,1fr)_64px_76px_76px] items-center gap-2.5 border-t border-plex-border py-1.5 text-[12.5px] max-[479px]:grid-cols-[minmax(0,1fr)_52px_64px]"
            data-testid={`sky-eval-compared-${lane.runType}-${c.model}`}
          >
            <span className="flex min-w-0 flex-wrap items-center gap-[7px]">
              <ModelName model={c.model} />
              {c.lanes.length > 0 && <ProdTag />}
            </span>
            <span className="text-right font-mono text-xs">{formatPct(c.last4WeeklyPct)}</span>
            <span className={`text-right font-mono text-xs ${COMPARED_TONE_TEXT[comparedTone(c.deltaPts)]}`} data-testid="sky-eval-compared-pts">
              {formatPts(c.deltaPts)}
            </span>
            <span className="text-right font-mono text-xs max-[479px]:hidden">{formatCostMultiple(c.costMultiple)}</span>
            {c.fellToOnHeadPct != null && (
              <span className="col-span-full -mt-0.5 pl-[15px] text-[11.5px] text-verdict-marginal" data-testid="sky-eval-compared-fell">
                Fell to {formatPct(c.fellToOnHeadPct)} on {head?.shortSha ?? 'this version'}
              </span>
            )}
          </div>
        ))}
      </div>

      {stale.length > 0 && (
        <p className="text-xs text-plex-text-muted text-pretty" data-testid="sky-eval-lane-stale">
          {stale.map((m) => modelLabel(m.model)).join(' and ')} last tested {formatAgo(stale[0].lastTestedAt, now)}.{' '}
          <button
            type="button"
            className="cursor-pointer text-xs text-tide hover:text-tide-light focus-visible:outline-2 focus-visible:outline-plex-gold"
            onClick={() => onRunCheck(stale.map((m) => m.model))}
            data-testid="sky-eval-lane-check-stale"
          >
            Check {stale.length > 1 ? 'them' : 'it'}{hasHead ? ` on ${head.shortSha}` : ''}
          </button>
        </p>
      )}
    </section>
  );
}

LaneCard.propTypes = {
  lane: PropTypes.shape({
    runType: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    purpose: PropTypes.string.isRequired,
    model: PropTypes.string.isRequired,
    compared: PropTypes.array.isRequired,
  }).isRequired,
  stats: PropTypes.object,
  models: PropTypes.array.isRequired,
  head: PropTypes.object,
  fixtureCount: PropTypes.number.isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
  onRunCheck: PropTypes.func.isRequired,
};
