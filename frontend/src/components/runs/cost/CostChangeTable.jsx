import PropTypes from 'prop-types';
import { bucketSeries, costSelection, signedGbp, signedPercent, sinceLabel, typeSeries, whyText } from '../../../utils/runsCost.js';
import { toggleType } from '../../../utils/runsQuery.js';
import { formatGbp, formatUsd, typeStyle } from '../../../utils/runsDay.js';

const CARD = 'rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3';
/**
 * The row grid: Type, Last, Prior, Change, Why, Daily. Under 900px of the list column (a container query) the
 * right-hand columns (Prior, Why, Daily) go and only Type, Last and Change stay.
 */
const GRID = 'grid grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,0.9fr)_minmax(0,2fr)_130px] items-center gap-x-3 @max-[640px]:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)_minmax(0,1fr)]';
const SPARK_W = 130;
const SPARK_H = 28;
/** The all-time grid: Type, Spend and the sparkline (under 900px of the list column the sparkline goes). */
const GRID_ALL = 'grid grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_130px] items-center gap-x-3 @max-[640px]:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]';
/** A sparkline bar is at least this wide, so a long history is summed into at most SPARK_W / this many bars. */
const SPARK_MIN_BAR = 2;
/** The prior period's half of a sparkline is drawn at this opacity. */
const PRIOR_OPACITY = 0.35;

/** A change's colour: amber for a rise, green for a fall, quiet for under five pence either way. */
function deltaClass(delta) {
  const n = Number(delta);
  if (n > 0.05) return 'text-verdict-marginal';
  if (n < -0.05) return 'text-plex-ok';
  return 'text-plex-text';
}

/**
 * One type's daily sparkline: 2N bars, the prior half faint. In all time there is no prior half, and a history too
 * long for 2px bars is summed into equal buckets first.
 */
function Sparkline({ values: daily, type, allTime }) {
  const values = bucketSeries(daily, Math.floor(SPARK_W / SPARK_MIN_BAR));
  const peak = Math.max(...values, 0) || 1;
  const bar = SPARK_W / values.length;
  const half = allTime ? 0 : values.length / 2;
  return (
    <svg width={SPARK_W} height={SPARK_H} viewBox={`0 0 ${SPARK_W} ${SPARK_H}`} aria-hidden="true" className="@max-[640px]:hidden" data-testid={`runs-cost-spark-${type.toLowerCase()}`}>
      {values.map((value, i) => (
        <rect
          key={i}
          x={i * bar}
          y={SPARK_H - (value / peak) * SPARK_H}
          width={Math.max(1, bar - 1)}
          height={(value / peak) * SPARK_H}
          className={typeStyle(type).fill}
          opacity={i < half ? PRIOR_OPACITY : 1}
        />
      ))}
    </svg>
  );
}

Sparkline.propTypes = {
  values: PropTypes.arrayOf(PropTypes.number).isRequired,
  type: PropTypes.string.isRequired,
  allTime: PropTypes.bool,
};

/** The change cell: "+£7.10" with its percent beneath. */
function Change({ delta, percent, testId }) {
  return (
    <span className={`text-right font-mono text-[12.5px] ${deltaClass(delta)}`} data-testid={testId}>
      {signedGbp(delta)}
      <small className="block text-[10px] text-plex-text-muted">{percent == null ? '' : signedPercent(percent)}</small>
    </span>
  );
}

Change.propTypes = { delta: PropTypes.oneOfType([PropTypes.number, PropTypes.string]), percent: PropTypes.number, testId: PropTypes.string.isRequired };

/**
 * The all-time table: no prior period, so only Type, Spend and the daily sparkline; the total row is the spend and its
 * dollar equivalent. The rows are served biggest spend first.
 */
function AllTimeTable({ data, selected, onSelect }) {
  const picked = costSelection(selected);
  const { totals } = data;
  return (
    <section className={CARD} data-testid="runs-cost-table-card">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h3 className="text-[13px] font-semibold text-plex-text">Where the spend came from</h3>
        <span className="font-mono text-[11px] text-plex-text-secondary">
          since {sinceLabel(data.firstDay)} {'\u00b7'} click rows to pick types in the chart
        </span>
      </div>
      <div className="mt-3" role="table" aria-label="Spend by type since the first recorded run" data-testid="runs-cost-table">
        <div className={`${GRID_ALL} border-b border-plex-border pb-2 font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted`} role="row">
          <span role="columnheader">Type</span>
          <span role="columnheader" className="text-right">Spend</span>
          <span role="columnheader" className="@max-[640px]:hidden">Daily, {data.rangeDays} days</span>
        </div>
        {data.types.map((row) => {
          const on = picked.includes(row.type);
          const key = row.type.toLowerCase();
          return (
            <div
              key={row.type}
              role="row"
              className={`${GRID_ALL} relative border-b border-plex-border/60 py-2.5 text-[12.5px] hover:bg-plex-surface-light/40 ${on ? 'bg-plex-gold/8' : ''}`}
              data-testid={`runs-cost-row-${key}`}
            >
              <span role="cell" className="flex min-w-0 items-center gap-2">
                <i className={`size-2 shrink-0 rounded-xs ${typeStyle(row.type).swatch}`} aria-hidden="true" />
                <button
                  type="button"
                  aria-pressed={on}
                  className="min-w-0 cursor-pointer text-left font-semibold text-plex-text after:absolute after:inset-0 focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
                  onClick={() => onSelect(toggleType(selected, row.type))}
                  data-testid={`runs-cost-row-${key}-toggle`}
                >
                  {row.label}
                </button>
              </span>
              <span role="cell" className="text-right font-mono" data-testid={`runs-cost-row-${key}-last`}>
                {formatGbp(row.lastGbp)}
                {row.costComplete === false && <small className="block text-[10px] text-verdict-marginal">so far</small>}
              </span>
              <span role="cell" className="@max-[640px]:hidden"><Sparkline values={typeSeries(data, row.type)} type={row.type} allTime /></span>
            </div>
          );
        })}
        <div className={`${GRID_ALL} py-2.5 text-[12.5px] font-semibold`} role="row" data-testid="runs-cost-total">
          <span role="cell">Total</span>
          <span role="cell" className="text-right font-mono" data-testid="runs-cost-total-last">
            {formatGbp(totals.lastGbp)}
            {totals.costComplete === false && <small className="block text-[10px] font-normal text-verdict-marginal">so far</small>}
          </span>
          <span role="cell" className="font-mono text-[12px] font-normal text-plex-text-secondary @max-[640px]:hidden" data-testid="runs-cost-total-usd">
            {formatUsd(totals.lastUsd)}{Number(totals.fxRate) > 0 ? ` \u00b7 £1 = $${(1 / Number(totals.fxRate)).toFixed(2)}` : ''}
          </span>
        </div>
      </div>
    </section>
  );
}

AllTimeTable.propTypes = {
  data: PropTypes.object.isRequired,
  selected: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
};

/**
 * The Cost view's "Where the change came from" table (the handoff's table): one row per type that ran, biggest rise
 * first as served, with the last period, the prior period, the change, why ({@code Runs +0% · £/run +31%}) and a
 * daily sparkline over both periods (the prior half faint); then a total row with the dollar equivalent at the served
 * exchange rate. A row's name is a toggle that adds the type to, or removes it from, the types picked in the chart above,
 * and the button stretches over the whole row, so a pointer anywhere on it does the same.
 *
 * @param {object} props
 * @param {object} props.data the served {@code RunCostResponse}
 * @param {string|null} props.selected the picked types as a comma-separated list, or null
 * @param {(types: string|null) => void} props.onSelect picks types (the new list; null clears)
 */
export default function CostChangeTable({ data, selected, onSelect }) {
  const n = data.rangeDays;
  const { totals } = data;
  const picked = costSelection(selected);
  if (data.allTime === true) return <AllTimeTable data={data} selected={selected} onSelect={onSelect} />;
  return (
    <section className={CARD} data-testid="runs-cost-table-card">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h3 className="text-[13px] font-semibold text-plex-text">Where the change came from</h3>
        <span className="font-mono text-[11px] text-plex-text-secondary">
          last {n} days vs the {n} before {'·'} click rows to pick types in the chart
        </span>
      </div>
      <div className="mt-3" role="table" aria-label="Spend by type, last period against the one before" data-testid="runs-cost-table">
        <div className={`${GRID} border-b border-plex-border pb-2 font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted`} role="row">
          <span role="columnheader">Type</span>
          <span role="columnheader" className="text-right">Last {n}d</span>
          <span role="columnheader" className="text-right @max-[640px]:hidden">Prior {n}d</span>
          <span role="columnheader" className="text-right">Change</span>
          <span role="columnheader" className="@max-[640px]:hidden">Why</span>
          <span role="columnheader" className="@max-[640px]:hidden">Daily, {2 * n} days</span>
        </div>
        {data.types.map((row) => {
          const on = picked.includes(row.type);
          const key = row.type.toLowerCase();
          return (
            <div
              key={row.type}
              role="row"
              className={`${GRID} relative border-b border-plex-border/60 py-2.5 text-[12.5px] hover:bg-plex-surface-light/40 ${on ? 'bg-plex-gold/8' : ''}`}
              data-testid={`runs-cost-row-${key}`}
            >
              <span role="cell" className="flex min-w-0 items-center gap-2">
                <i className={`size-2 shrink-0 rounded-xs ${typeStyle(row.type).swatch}`} aria-hidden="true" />
                <button
                  type="button"
                  aria-pressed={on}
                  className="min-w-0 cursor-pointer text-left font-semibold text-plex-text after:absolute after:inset-0 focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
                  onClick={() => onSelect(toggleType(selected, row.type))}
                  data-testid={`runs-cost-row-${key}-toggle`}
                >
                  {row.label}
                </button>
              </span>
              <span role="cell" className="text-right font-mono" data-testid={`runs-cost-row-${key}-last`}>
                {formatGbp(row.lastGbp)}
                {row.costComplete === false && <small className="block text-[10px] text-verdict-marginal">so far</small>}
              </span>
              <span role="cell" className="text-right font-mono text-plex-text-secondary @max-[640px]:hidden">{formatGbp(row.priorGbp)}</span>
              <span role="cell" className="text-right"><Change delta={row.deltaGbp} percent={row.deltaPercent} testId={`runs-cost-row-${key}-change`} /></span>
              <span role="cell" className="text-[12px] text-plex-text-secondary @max-[640px]:hidden" data-testid={`runs-cost-row-${key}-why`}>{whyText(row)}</span>
              <span role="cell" className="@max-[640px]:hidden"><Sparkline values={typeSeries(data, row.type)} type={row.type} /></span>
            </div>
          );
        })}
        <div className={`${GRID} py-2.5 text-[12.5px] font-semibold`} role="row" data-testid="runs-cost-total">
          <span role="cell">Total</span>
          <span role="cell" className="text-right font-mono" data-testid="runs-cost-total-last">
            {formatGbp(totals.lastGbp)}
            {totals.costComplete === false && <small className="block text-[10px] font-normal text-verdict-marginal">so far</small>}
          </span>
          <span role="cell" className="text-right font-mono @max-[640px]:hidden">{formatGbp(totals.priorGbp)}</span>
          <span role="cell" className="text-right"><Change delta={totals.deltaGbp} percent={totals.deltaPercent} testId="runs-cost-total-change" /></span>
          <span role="cell" className="font-mono text-[12px] font-normal text-plex-text-secondary @max-[640px]:hidden" data-testid="runs-cost-total-usd">
            {formatUsd(totals.lastUsd)}{Number(totals.fxRate) > 0 ? ` \u00b7 £1 = $${(1 / Number(totals.fxRate)).toFixed(2)}` : ''}
          </span>
          <span role="cell" className="@max-[640px]:hidden" />
        </div>
      </div>
    </section>
  );
}

CostChangeTable.propTypes = {
  data: PropTypes.shape({
    rangeDays: PropTypes.number.isRequired,
    allTime: PropTypes.bool,
    types: PropTypes.array.isRequired,
    totals: PropTypes.object.isRequired,
  }).isRequired,
  selected: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
};
