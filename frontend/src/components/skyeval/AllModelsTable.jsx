import PropTypes from 'prop-types';
import {
  AMBER_PCT, GREEN_PCT, barWidthPct, formatAgo, formatGbp, formatPct, laneShortLabel,
} from '../../utils/skyEval.js';
import { Bar, CARD_CLASS, ModelName } from './parts.jsx';

const ROW_GRID =
  'grid grid-cols-[minmax(0,1.3fr)_110px_minmax(0,1.3fr)_92px_70px_80px_96px] items-center gap-2.5 max-[899px]:grid-cols-[minmax(0,1.3fr)_minmax(0,1.3fr)_80px_70px]';

/** Bar fills by threshold: green at 97 and up, amber at 92 and up, coral below. */
function barFill(pct) {
  if (pct >= GREEN_PCT) return 'fill-verdict-go';
  if (pct >= AMBER_PCT) return 'fill-verdict-marginal';
  return 'fill-plex-coral';
}

/**
 * All models: fresh models first then by accuracy (the served order), stale rows dimmed. A row is a button that
 * toggles the model in the accuracy chart's multi-select.
 *
 * @param {object} props
 * @param {Array<object>} props.models the served model rows, in served order
 * @param {Array<object>} props.lanes the served lanes, to name each production model's lane
 * @param {object|null} props.head the running build
 * @param {string[]} props.highlighted the models chosen for comparison in the chart
 * @param {(model: string) => void} props.onToggle adds the model to the selection, or removes it
 * @param {Date} props.now the moment ages are measured to
 */
export default function AllModelsTable({ models, lanes, head, highlighted, onToggle, now }) {
  const laneLabel = (runType) => laneShortLabel(lanes.find((l) => l.runType === runType)?.label ?? runType);
  return (
    <section className={CARD_CLASS} data-testid="sky-eval-all-models">
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h3 className="text-sm font-semibold">All models</h3>
        <span className="font-mono text-[11px] text-plex-text-muted">click models to compare them in the chart</span>
      </div>
      <div className="overflow-x-auto">
        <div className="flex min-w-[420px] flex-col">
          <div className={`${ROW_GRID} px-1 font-mono text-[10px] font-medium uppercase tracking-widest text-plex-text-muted`}>
            <span>Model</span>
            <span className="max-[899px]:hidden">Used for</span>
            <span>Last 4 weekly</span>
            <span className="text-right">On {head?.shortSha ?? 'HEAD'}</span>
            <span className="text-right max-[899px]:hidden">Misses {'↓'}/{'↑'}</span>
            <span className="text-right">{'£'}/run</span>
            <span className="text-right max-[899px]:hidden">Last tested</span>
          </div>
          {models.map((m) => (
            <button
              key={m.model}
              type="button"
              aria-pressed={highlighted.includes(m.model)}
              onClick={() => onToggle(m.model)}
              className={`${ROW_GRID} w-full cursor-pointer border-t border-plex-border px-1 py-[9px] text-left text-[13px] hover:bg-[rgba(242,231,211,0.03)] focus-visible:outline-2 focus-visible:outline-plex-gold ${
                highlighted.includes(m.model) ? 'bg-[rgba(242,231,211,0.05)]' : ''
              } ${m.fresh ? '' : 'text-plex-text-muted opacity-70'}`}
              data-testid={`sky-eval-model-row-${m.model}`}
              data-fresh={m.fresh}
            >
              <span className={`flex items-center gap-[7px] ${m.fresh ? 'font-medium' : ''}`}>
                <ModelName model={m.model} />
              </span>
              <span className="max-[899px]:hidden">
                {m.lanes.length > 0 ? (
                  <span className="whitespace-nowrap rounded border border-[rgba(201,162,75,0.5)] px-1 font-mono text-[9.5px] font-medium uppercase tracking-[0.1em] text-skyeval-version">
                    {m.lanes.map(laneLabel).join(' · ')}
                  </span>
                ) : (
                  <span className="text-xs text-plex-text-muted">eval only</span>
                )}
              </span>
              {m.last4WeeklyPct == null ? (
                <span className="font-mono text-[12.5px] text-plex-text-muted">{'—'}</span>
              ) : (
                <span className="flex items-center gap-2 font-mono text-[12.5px]">
                  {formatPct(m.last4WeeklyPct)}
                  <span className="block flex-1"><Bar widthPct={barWidthPct(m.last4WeeklyPct)} fillClass={barFill(m.last4WeeklyPct)} /></span>
                </span>
              )}
              <span className="text-right font-mono text-[12.5px]">{formatPct(m.onHeadPct)}</span>
              <span className="text-right font-mono text-[12.5px] max-[899px]:hidden">
                {m.last4Ratings > 0 ? `${m.last4Below} / ${m.last4Above}` : '—'}
              </span>
              <span className="text-right font-mono text-[12.5px]">{formatGbp(m.costPerRunGbp)}</span>
              <span className="text-right font-mono text-[12.5px] max-[899px]:hidden">
                {m.lastTestedAt ? formatAgo(m.lastTestedAt, now) : '—'}
                {!m.fresh && m.lastTestedAt && <small className="block text-[10.5px]">older version</small>}
              </span>
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

AllModelsTable.propTypes = {
  models: PropTypes.array.isRequired,
  lanes: PropTypes.array.isRequired,
  head: PropTypes.object,
  highlighted: PropTypes.arrayOf(PropTypes.string).isRequired,
  onToggle: PropTypes.func.isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
};
