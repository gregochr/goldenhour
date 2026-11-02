import { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import { appNow } from '../../utils/rewind.js';
import {
  RUNS_OPTIONS, estimateCheck, formatAgo, formatGbp, formatSeconds,
} from '../../utils/skyEval.js';
import { CARD_CLASS, ModelName, ProdTag, VersionTag } from './parts.jsx';

const CHIP_BASE =
  'inline-flex min-h-7 cursor-pointer items-center gap-[7px] whitespace-nowrap rounded-full border px-[11px] py-1 font-mono text-[11px] focus-visible:outline-2 focus-visible:outline-plex-gold';
const CHIP_ON = `${CHIP_BASE} border-[rgba(201,162,75,0.5)] bg-[rgba(201,162,75,0.12)] text-skyeval-version`;
const CHIP_OFF = `${CHIP_BASE} border-plex-border bg-plex-panel text-plex-text-secondary hover:border-plex-border-light hover:text-plex-text`;
const SEG_BASE =
  'min-h-7 cursor-pointer whitespace-nowrap px-[11px] py-1 font-mono text-[11px] focus-visible:outline-2 focus-visible:outline-plex-gold disabled:cursor-default disabled:opacity-40';
const SEG_ON = `${SEG_BASE} bg-plex-surface-light text-plex-text`;
const SEG_OFF = `${SEG_BASE} text-plex-text-secondary`;

/** The label cell of one panel row. */
function RowLabel({ children }) {
  return <span className="text-xs text-plex-text-muted max-[699px]:-mb-1">{children}</span>;
}

RowLabel.propTypes = { children: PropTypes.node.isRequired };

/**
 * The Run a check panel: pick models, repeats per fixture and fixtures, see the estimate, start the check. The
 * check always runs on the current build (HEAD). Submitting is disabled while the request is in flight, so a
 * double click cannot start (and bill) two checks.
 *
 * @param {object} props
 * @param {object} props.overview the served overview
 * @param {string[]} props.selectableModels the models a check may name, in display order
 * @param {string[]|null} props.initialModels the models to open with, or null for the served defaults
 * @param {boolean} props.submitting true while the POST is in flight
 * @param {string|null} props.error the last submit error, or null
 * @param {(body: object) => void} props.onSubmit called with the POST body
 * @param {() => void} props.onClose closes the panel
 */
export default function RunCheckPanel({
  overview, selectableModels, initialModels, submitting, error, onSubmit, onClose,
}) {
  const { checkDefaults, fixtures, head, lanes, models: stats } = overview;
  const [selected, setSelected] = useState(initialModels ?? checkDefaults.models);
  const [runs, setRuns] = useState(checkDefaults.runsPerFixture);
  const [fixtureMode, setFixtureMode] = useState('all');
  const missed = checkDefaults.missedLastTime;
  const prodModels = useMemo(() => [...new Set(lanes.map((l) => l.model))], [lanes]);
  // Production models lead, as in the design; the rest keep the served selectable order.
  const chipModels = useMemo(() => [
    ...prodModels.filter((m) => selectableModels.includes(m)),
    ...selectableModels.filter((m) => !prodModels.includes(m)),
  ], [prodModels, selectableModels]);

  const fixtureCount = fixtureMode === 'all' ? fixtures.length : missed.length;
  const estimate = estimateCheck(selected, stats, fixtureCount, runs);
  const canRun = selected.length > 0 && fixtureCount > 0 && !submitting;

  const toggle = (model) => setSelected((prev) => (
    prev.includes(model) ? prev.filter((m) => m !== model) : [...prev, model]
  ));

  const submit = () => onSubmit({
    models: selected,
    runsPerFixture: runs,
    fixtureNames: fixtureMode === 'missed' ? missed : null,
  });

  return (
    <section className={`${CARD_CLASS} border-plex-border-light`} data-testid="sky-eval-check-panel" aria-label="Run a check">
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h3 className="text-sm font-semibold">Run a check</h3>
        <span className="font-mono text-[11px] text-plex-text-muted">results land in Runs and update every chart</span>
        <button
          type="button"
          onClick={onClose}
          className="ml-auto cursor-pointer text-[13px] text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="sky-eval-check-close"
        >
          Close
        </button>
      </div>

      <div className="grid grid-cols-[130px_minmax(0,1fr)] items-center gap-x-4 gap-y-3 max-[699px]:grid-cols-[minmax(0,1fr)]">
        <RowLabel>Models</RowLabel>
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Models">
          {chipModels.map((m) => (
            <button
              key={m}
              type="button"
              aria-pressed={selected.includes(m)}
              onClick={() => toggle(m)}
              className={selected.includes(m) ? CHIP_ON : CHIP_OFF}
              data-testid={`sky-eval-check-model-${m}`}
            >
              <ModelName model={m} />
              {prodModels.includes(m) && <ProdTag />}
            </button>
          ))}
          <button
            type="button"
            onClick={() => setSelected(prodModels)}
            className="cursor-pointer text-xs text-tide hover:text-tide-light focus-visible:outline-2 focus-visible:outline-plex-gold"
            data-testid="sky-eval-check-production-pair"
          >
            Production pair
          </button>
        </div>

        <RowLabel>Runs per fixture</RowLabel>
        <div className="inline-flex w-fit overflow-hidden rounded-lg border border-plex-border" role="group" aria-label="Runs per fixture">
          {RUNS_OPTIONS.map((n, i) => (
            <button
              key={n}
              type="button"
              aria-pressed={runs === n}
              onClick={() => setRuns(n)}
              className={`${runs === n ? SEG_ON : SEG_OFF}${i > 0 ? ' border-l border-plex-border' : ''}`}
              data-testid={`sky-eval-check-runs-${n}`}
            >
              {n}{n === 3 ? ' · quick' : ''}{n === 8 ? ' · same as weekly' : ''}
            </button>
          ))}
        </div>

        <RowLabel>Fixtures</RowLabel>
        <div className="inline-flex w-fit overflow-hidden rounded-lg border border-plex-border" role="group" aria-label="Fixtures">
          <button
            type="button"
            aria-pressed={fixtureMode === 'all'}
            onClick={() => setFixtureMode('all')}
            className={fixtureMode === 'all' ? SEG_ON : SEG_OFF}
            data-testid="sky-eval-check-fixtures-all"
          >
            All {fixtures.length}
          </button>
          <button
            type="button"
            aria-pressed={fixtureMode === 'missed'}
            disabled={missed.length === 0}
            onClick={() => setFixtureMode('missed')}
            className={`border-l border-plex-border ${fixtureMode === 'missed' ? SEG_ON : SEG_OFF}`}
            data-testid="sky-eval-check-fixtures-missed"
          >
            Missed last time {'·'} {missed.length}
          </button>
        </div>

        <RowLabel>Version</RowLabel>
        <div className="flex flex-wrap items-baseline gap-2 text-[13px]" data-testid="sky-eval-check-version">
          {head?.sha ? (
            <>
              <VersionTag shortSha={head.shortSha} />
              {head.message && <span>{head.message}</span>}
              <span className="text-xs text-plex-text-muted">
                current{head.committedAt ? `, pushed ${formatAgo(head.committedAt, appNow())}` : ''}
              </span>
            </>
          ) : (
            <span className="text-plex-text-secondary">version unknown</span>
          )}
        </div>
      </div>

      <ErrorBanner message={error} data-testid="sky-eval-check-error" />

      <div className="flex flex-wrap items-center gap-3 border-t border-plex-border pt-3">
        <span className="font-mono text-[12.5px] text-plex-text-secondary" data-testid="sky-eval-check-estimate">
          Estimate{' '}
          <b className="font-medium text-plex-text" data-testid="sky-eval-check-cost">
            {estimate.costGbp == null ? 'no estimate' : `~${formatGbp(estimate.costGbp)}`}
          </b>
          {' · '}
          <b className="font-medium text-plex-text" data-testid="sky-eval-check-time">
            {estimate.seconds == null ? 'no estimate' : `~${formatSeconds(estimate.seconds)}`}
          </b>
          {' · '}
          <span data-testid="sky-eval-check-ratings">{estimate.ratings} ratings</span>
        </span>
        <span className="flex-1" />
        <button
          type="button"
          onClick={onClose}
          className="min-h-8 cursor-pointer rounded-lg border border-plex-border bg-plex-surface-light px-3 py-1.5 text-[13px] font-medium hover:border-plex-border-light focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="sky-eval-check-cancel"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={submit}
          disabled={!canRun}
          className="min-h-8 cursor-pointer rounded-lg bg-plex-text px-3 py-1.5 text-[13px] font-medium text-[#1B1411] focus-visible:outline-2 focus-visible:outline-plex-gold disabled:cursor-default disabled:opacity-40"
          data-testid="sky-eval-check-run"
        >
          {submitting ? 'Starting…' : 'Run check'}
        </button>
      </div>
    </section>
  );
}

RunCheckPanel.propTypes = {
  overview: PropTypes.shape({
    checkDefaults: PropTypes.shape({
      models: PropTypes.arrayOf(PropTypes.string).isRequired,
      runsPerFixture: PropTypes.number.isRequired,
      missedLastTime: PropTypes.arrayOf(PropTypes.string).isRequired,
    }).isRequired,
    fixtures: PropTypes.array.isRequired,
    head: PropTypes.object,
    lanes: PropTypes.array.isRequired,
    models: PropTypes.array.isRequired,
  }).isRequired,
  selectableModels: PropTypes.arrayOf(PropTypes.string).isRequired,
  initialModels: PropTypes.arrayOf(PropTypes.string),
  submitting: PropTypes.bool.isRequired,
  error: PropTypes.string,
  onSubmit: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
};
