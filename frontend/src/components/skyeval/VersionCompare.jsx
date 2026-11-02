import { useState } from 'react';
import PropTypes from 'prop-types';
import { changeTone, formatAgo, formatDayUk, formatPct, formatPts } from '../../utils/skyEval.js';
import { CARD_CLASS, ModelName, ProdTag, VersionTag } from './parts.jsx';

const CHANGE_TEXT = { bad: 'text-plex-coral', good: 'text-verdict-go', neutral: '' };
const ROW_GRID =
  'grid grid-cols-[150px_64px_18px_64px_78px_minmax(0,1fr)] items-start gap-2.5 max-[759px]:grid-cols-[minmax(0,1fr)_48px_48px_64px]';

const CHIP_BASE =
  'inline-flex min-h-7 cursor-pointer items-center gap-[7px] whitespace-nowrap rounded-full border px-[11px] py-1 font-mono text-[11px] focus-visible:outline-2 focus-visible:outline-plex-gold';
const CHIP_ON = `${CHIP_BASE} border-[rgba(201,162,75,0.5)] bg-[rgba(201,162,75,0.12)] text-skyeval-version`;
const CHIP_OFF = `${CHIP_BASE} border-plex-border bg-plex-panel text-plex-text-secondary hover:border-plex-border-light hover:text-plex-text`;

/** The "rated too low / high" phrase of a mover that got worse. */
function sideText(missSide) {
  if (missSide === 'BELOW') return ' · rated too low';
  if (missSide === 'ABOVE') return ' · rated too high';
  return '';
}

/**
 * "Did a change make ratings worse?": version chips, then for the selected version each model's before and after,
 * the change in points and the fixtures that moved by 20 points or more. Served rows, formatted here.
 *
 * @param {object} props
 * @param {Array<object>} props.comparisons the served comparisons, HEAD first
 * @param {Date} props.now the moment ages are measured to
 */
export default function VersionCompare({ comparisons, now }) {
  const [picked, setPicked] = useState(null);
  const current = comparisons.find((c) => c.version.sha === picked) ?? comparisons[0] ?? null;

  return (
    <section className={CARD_CLASS} data-testid="sky-eval-compare">
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h3 className="text-sm font-semibold">Did a change make ratings worse?</h3>
        <span className="font-mono text-[11px] text-plex-text-muted">runs on a version vs the last runs before it</span>
        <span className="ml-auto flex flex-wrap gap-1.5">
          {comparisons.map((c) => (
            <button
              key={c.version.sha}
              type="button"
              aria-pressed={current?.version.sha === c.version.sha}
              onClick={() => setPicked(c.version.sha)}
              className={current?.version.sha === c.version.sha ? CHIP_ON : CHIP_OFF}
              data-testid={`sky-eval-version-chip-${c.version.shortSha}`}
            >
              {c.version.shortSha} <span className="opacity-60">{formatAgo(c.version.committedAt, now)}</span>
            </button>
          ))}
        </span>
      </div>

      {current == null ? (
        <p className="text-[13px] text-plex-text-muted" data-testid="sky-eval-compare-empty">
          No version has runs both on it and before it yet.
        </p>
      ) : (
        <>
          <div className="flex flex-wrap items-baseline gap-2 text-[13px] text-plex-text-secondary" data-testid="sky-eval-compare-version">
            <VersionTag shortSha={current.version.shortSha} />
            {current.version.message && <span>{current.version.message}</span>}
            {current.version.committedAt && (
              <span className="text-plex-text-muted">{'·'} {formatDayUk(current.version.committedAt)}</span>
            )}
          </div>
          <div className="flex flex-col" data-testid="sky-eval-compare-rows">
            <div className={`${ROW_GRID} font-mono text-[10px] font-medium uppercase tracking-widest text-plex-text-muted`}>
              <span>Model</span>
              <span className="text-right">Before</span>
              <span className="max-[759px]:hidden" />
              <span className="text-right">After</span>
              <span className="text-right">Change</span>
              <span className="max-[759px]:col-span-full">Fixtures that moved</span>
            </div>
            {current.rows.map((r) => (
              <div
                key={r.model}
                className={`${ROW_GRID} border-t border-plex-border px-1 py-[9px] text-[13px] ${r.notRun ? 'text-plex-text-muted' : ''}`}
                data-testid={`sky-eval-compare-row-${r.model}`}
              >
                <span className="flex flex-wrap items-center gap-[7px] font-medium">
                  <ModelName model={r.model} />
                  {r.lanes.length > 0 && <ProdTag />}
                </span>
                {r.notRun ? (
                  <>
                    <span className="text-right font-mono text-[12.5px]">{'—'}</span>
                    <span className="max-[759px]:hidden" />
                    <span className="text-right font-mono text-[12.5px]">{'—'}</span>
                    <span />
                    <span className="text-xs max-[759px]:col-span-full" data-testid="sky-eval-compare-notrun">Not run on this version</span>
                  </>
                ) : (
                  <>
                    <span className="text-right font-mono text-[12.5px]">{formatPct(r.beforePct)}</span>
                    <span className="text-plex-text-muted max-[759px]:hidden">{'→'}</span>
                    <span className="text-right font-mono text-[12.5px]">{formatPct(r.afterPct)}</span>
                    <span
                      className={`text-right font-mono text-[12.5px] ${CHANGE_TEXT[changeTone(r.deltaPts)]}`}
                      data-testid="sky-eval-compare-change"
                      data-tone={changeTone(r.deltaPts)}
                    >
                      {formatPts(r.deltaPts)}
                    </span>
                    <span className="flex flex-col gap-0.5 font-mono text-[11.5px] text-plex-text-secondary max-[759px]:col-span-full">
                      {r.moved.length === 0 ? (
                        <span className="text-plex-text-muted">No fixture moved by 20 pts or more</span>
                      ) : r.moved.map((mv) => (
                        <span key={mv.fixture} className="flex flex-wrap gap-2" data-testid="sky-eval-compare-moved">
                          <b className={`font-medium ${mv.direction === 'WORSE' ? 'text-plex-coral' : 'text-verdict-go'}`}>
                            {mv.direction === 'WORSE' ? 'worse' : 'better'}
                          </b>
                          {mv.fixture}
                          <span className="text-plex-text-muted">
                            {Math.round(mv.beforePct)}% {'→'} {Math.round(mv.afterPct)}% in band
                            {mv.direction === 'WORSE' ? sideText(mv.missSide) : ''}
                          </span>
                        </span>
                      ))}
                    </span>
                  </>
                )}
              </div>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

VersionCompare.propTypes = {
  comparisons: PropTypes.arrayOf(PropTypes.shape({
    version: PropTypes.shape({
      sha: PropTypes.string.isRequired,
      shortSha: PropTypes.string.isRequired,
      message: PropTypes.string,
      committedAt: PropTypes.string,
    }).isRequired,
    rows: PropTypes.array.isRequired,
  })).isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
};
