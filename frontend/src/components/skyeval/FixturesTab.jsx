import { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { modelLabel } from '../../utils/modelLabels.js';
import {
  CELL, CELL_FILL, INBAND_OPACITY, LAST4_CORAL_PCT, VERSION_GOLD, cellState, cellTitle, defaultFixtureModel,
  fixtureModels, fixtureRows, formatPct, formatShortDateUk, modelRunColumns,
} from '../../utils/skyEval.js';
import FixtureHistory from './FixtureHistory.jsx';
import { CARD_CLASS, Swatch } from './parts.jsx';

const COLUMN_WIDTH = CELL.border + CELL.width + CELL.gap;
const HEADER_CLASS = 'w-[300px] flex-none max-[639px]:w-[150px]';
const SEG_BASE =
  'min-h-7 cursor-pointer whitespace-nowrap px-[11px] py-1 font-mono text-[11px] focus-visible:outline-2 focus-visible:outline-plex-gold';
const SEG_ON = `${SEG_BASE} bg-plex-surface-light text-plex-text`;
const SEG_OFF = `${SEG_BASE} text-plex-text-secondary`;

/** One grid cell, drawn in SVG so its fill opacity is continuous. A gold strip marks a version change. */
function Cell({ fixture, column }) {
  const rf = column.byFixture.get(fixture.name);
  const state = cellState(rf);
  const label = cellTitle(fixture, column, rf);
  return (
    <svg
      width={COLUMN_WIDTH}
      height={CELL.height}
      viewBox={`0 0 ${COLUMN_WIDTH} ${CELL.height}`}
      className="block flex-none"
      role="img"
      aria-label={label}
      data-testid={`sky-eval-cell-${fixture.name}-${column.runId}`}
      data-kind={state.kind}
    >
      <title>{label}</title>
      {column.versionChanged && (
        <rect x="0" y="0" width={CELL.border} height={CELL.height} fill={VERSION_GOLD} data-testid="sky-eval-cell-version-border" />
      )}
      {state.fill && (
        <rect
          x={CELL.border} y="0" width={CELL.width} height={CELL.height} rx={CELL.radius}
          fill={state.fill} fillOpacity={state.fillOpacity} data-testid="sky-eval-cell-fill"
        />
      )}
      {state.kind === 'absent' && (
        <text x={CELL.border + CELL.width / 2} y={CELL.height / 2 + 4} textAnchor="middle" className="fill-plex-text-faint font-mono text-[11px]">
          {state.text}
        </text>
      )}
      {state.kind !== 'absent' && state.text !== '' && (
        <text x={CELL.border + CELL.width / 2} y={CELL.height / 2 + 4} textAnchor="middle" className="fill-plex-text font-mono text-[11px] font-medium">
          {state.text}
        </text>
      )}
    </svg>
  );
}

Cell.propTypes = {
  fixture: PropTypes.object.isRequired,
  column: PropTypes.object.isRequired,
};

/** A column header: the run's date running up the page, ·m for a manual run, the new version's id above it. */
function ColumnHeader({ column }) {
  return (
    <div
      className={`flex h-[68px] w-[34px] flex-none flex-col items-center justify-end gap-1 border-l-2 pb-1 ${
        column.versionChanged ? 'border-skyeval-version' : 'border-transparent'
      }`}
      data-testid={`sky-eval-column-${column.runId}`}
      data-version-changed={column.versionChanged}
    >
      {column.versionLabel && (
        <em className="font-mono text-[9.5px] font-medium not-italic text-skyeval-version" data-testid="sky-eval-column-version">
          {column.versionLabel}
        </em>
      )}
      <span className="font-mono text-[10px] text-plex-text-muted [writing-mode:vertical-rl] rotate-180" data-testid="sky-eval-column-date">
        {formatShortDateUk(column.startedAt)}{column.manual ? ' ·m' : ''}
      </span>
    </div>
  );
}

ColumnHeader.propTypes = { column: PropTypes.object.isRequired };

/** The legend above the grid. */
function Legend() {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 font-mono text-[11px] text-plex-text-muted" data-testid="sky-eval-fixtures-legend">
      <span className="inline-flex items-center gap-1.5">
        <svg width="12" height="12" aria-hidden="true"><rect width="12" height="12" rx="2" fill={CELL_FILL.inband} fillOpacity={INBAND_OPACITY} /></svg>
        all ratings in band
      </span>
      <span className="inline-flex items-center gap-1.5">
        <svg width="12" height="12" aria-hidden="true"><rect width="12" height="12" rx="2" fill={CELL_FILL.below} fillOpacity="0.6" /></svg>
        {'↓'} rated below band
      </span>
      <span className="inline-flex items-center gap-1.5">
        <svg width="12" height="12" aria-hidden="true"><rect width="12" height="12" rx="2" fill={CELL_FILL.above} fillOpacity="0.6" /></svg>
        {'↑'} rated above band
      </span>
      <span>numbers = misses out of the run{'’'}s repeats</span>
      <span className="inline-flex items-center gap-1.5">
        <svg width="12" height="12" aria-hidden="true"><rect width="2" height="12" fill={VERSION_GOLD} /></svg>
        new version
      </span>
      <span>{'·'} = fixture not in that run</span>
    </div>
  );
}

/**
 * The Fixtures tab: for one model, every fixture against its newest 13 completed runs. Rows are sorted by misses
 * in the last four runs, worst first; clicking a row opens that fixture's rating history under it. Only the grid
 * scrolls sideways, inside its card.
 *
 * @param {object} props
 * @param {object} props.overview the served overview
 * @param {string|null} props.preselected a model chosen by a link, or null
 */
export default function FixturesTab({ overview, preselected }) {
  const { fixtures, models, sessions, runFixtures, modelFixtures, lanes, versions } = overview;
  const available = useMemo(() => fixtureModels(models, sessions, runFixtures), [models, sessions, runFixtures]);
  const [picked, setPicked] = useState(null);
  const [openFixture, setOpenFixture] = useState(null);
  const model = defaultFixtureModel(picked ?? preselected, available, lanes);
  const columns = useMemo(
    () => (model ? modelRunColumns(sessions, runFixtures, model) : []),
    [sessions, runFixtures, model],
  );
  const rows = useMemo(
    () => (model ? fixtureRows(fixtures, modelFixtures, model) : []),
    [fixtures, modelFixtures, model],
  );

  if (!model) {
    return (
      <section className={CARD_CLASS} data-testid="sky-eval-fixtures-tab">
        <p className="text-[13px] text-plex-text-muted" data-testid="sky-eval-fixtures-empty">No completed eval runs yet.</p>
      </section>
    );
  }

  const choose = (next) => {
    setPicked(next);
    setOpenFixture(null);
  };

  return (
    <section className={CARD_CLASS} data-testid="sky-eval-fixtures-tab" data-model={model}>
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h3 className="text-sm font-semibold">Fixture by fixture</h3>
        <span className="font-mono text-[11px] text-plex-text-muted" data-testid="sky-eval-fixtures-caption">
          last {columns.length} run{columns.length === 1 ? '' : 's'} of {modelLabel(model)} {'·'} worst fixtures first {'·'} click a fixture for its rating history
        </span>
      </div>
      <div className="inline-flex w-fit max-w-full overflow-x-auto rounded-lg border border-plex-border" role="group" aria-label="Model">
        {available.map((m, i) => (
          <button
            key={m}
            type="button"
            aria-pressed={model === m}
            onClick={() => choose(m)}
            className={`inline-flex items-center gap-1.5 ${model === m ? SEG_ON : SEG_OFF}${i > 0 ? ' border-l border-plex-border' : ''}`}
            data-testid={`sky-eval-fixtures-model-${m}`}
          >
            <Swatch model={m} />
            {modelLabel(m)}
          </button>
        ))}
      </div>
      <Legend />
      <div className="overflow-x-auto" data-testid="sky-eval-fixtures-scroll">
        <div className="flex min-w-max flex-col">
          <div className="flex items-end">
            <div className={`${HEADER_CLASS} sticky left-0 z-10 bg-plex-surface`} />
            {columns.map((c) => <ColumnHeader key={c.runId} column={c} />)}
            <span className="w-[64px] flex-none pb-1 pl-2.5 text-right font-mono text-[10px] text-plex-text-muted">Last 4</span>
          </div>
          {rows.map(({ fixture, pct }) => {
            const open = openFixture === fixture.name;
            return (
              <div key={fixture.name} data-testid={`sky-eval-fixture-row-${fixture.name}`}>
                <div className="flex items-center py-[2px]">
                  <button
                    type="button"
                    aria-expanded={open}
                    onClick={() => setOpenFixture(open ? null : fixture.name)}
                    className={`${HEADER_CLASS} sticky left-0 z-10 flex min-w-0 cursor-pointer items-center gap-2 bg-plex-surface py-1 pr-2 text-left hover:bg-plex-surface-light focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:flex-col max-[639px]:items-start max-[639px]:gap-0 ${
                      open ? 'bg-plex-surface-light' : ''
                    }`}
                    data-testid={`sky-eval-fixture-toggle-${fixture.name}`}
                  >
                    <span className="min-w-0 flex-1 truncate font-mono text-[12px] max-[639px]:w-full max-[639px]:flex-none" title={fixture.name}>{fixture.name}</span>
                    <span className="flex-none font-mono text-[11px] text-plex-text-muted">band {fixture.bandLo}{'–'}{fixture.bandHi}</span>
                  </button>
                  {columns.map((c) => <Cell key={c.runId} fixture={fixture} column={c} />)}
                  <span
                    className={`w-[64px] flex-none pl-2.5 text-right font-mono text-[12px] ${
                      pct != null && pct < LAST4_CORAL_PCT ? 'text-plex-coral' : 'text-plex-text-secondary'
                    }`}
                    data-testid={`sky-eval-fixture-last4-${fixture.name}`}
                    data-tone={pct != null && pct < LAST4_CORAL_PCT ? 'coral' : 'plain'}
                  >
                    {formatPct(pct)}
                  </span>
                </div>
                {open && <FixtureHistory fixture={fixture} model={model} columns={columns} versions={versions} />}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}

FixturesTab.propTypes = {
  overview: PropTypes.shape({
    fixtures: PropTypes.array.isRequired,
    models: PropTypes.array.isRequired,
    sessions: PropTypes.array.isRequired,
    runFixtures: PropTypes.array.isRequired,
    modelFixtures: PropTypes.array.isRequired,
    lanes: PropTypes.array.isRequired,
    versions: PropTypes.array.isRequired,
  }).isRequired,
  preselected: PropTypes.string,
};
