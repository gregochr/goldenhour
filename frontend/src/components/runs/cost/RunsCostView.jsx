import PropTypes from 'prop-types';
import CostChangeTable from './CostChangeTable.jsx';
import DailySpendChart from './DailySpendChart.jsx';
import { ViewError, ViewSkeleton, ViewStaleNote } from '../RunsViewState.jsx';
import { allTimeBody, allTimeHeadline, verdictBody, verdictHeadline } from '../../../utils/runsCost.js';

const CARD = 'rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3';

/**
 * The verdict card ("have my costs snowballed?"): a serif headline from the served direction and percent, and a body
 * that joins the served fields into the handoff's sentence template, the figures and type names in bold.
 */
function VerdictCard({ data }) {
  const hasSpend = Number(data.totals.lastGbp) > 0 || Number(data.totals.priorGbp) > 0;
  const allTime = data.allTime === true;
  const body = allTime ? allTimeBody(data) : verdictBody(data);
  return (
    <section className={CARD} data-testid="runs-cost-verdict">
      <h3 className="font-serif text-[24px] font-semibold leading-tight text-plex-text max-[639px]:text-[20px]" data-testid="runs-cost-headline">
        {allTime ? allTimeHeadline(data) : verdictHeadline(data.verdict, data.rangeDays, hasSpend)}
      </h3>
      {hasSpend && (
        <p className="mt-2 text-[13.5px] leading-relaxed text-plex-text-secondary" data-testid="runs-cost-body">
          {body.map((run, i) => (
            run.bold ? <strong key={i} className="font-semibold text-plex-text">{run.text}</strong> : <span key={i}>{run.text}</span>
          ))}
        </p>
      )}
      {data.totals.costComplete === false && (
        <p className="mt-2 font-mono text-[11px] text-verdict-marginal" data-testid="runs-cost-incomplete">
          Some runs are still accruing cost, so these figures may rise.
        </p>
      )}
    </section>
  );
}

VerdictCard.propTypes = { data: PropTypes.object.isRequired };

/**
 * Operations → Runs → Cost trends (runs-deep-dive-plan.md §4 D4): the verdict card, the daily spend chart and the
 * "where the change came from" table, for the last 7, 14, 30, 60 or 90 full UK days against the same number before,
 * or for all time (the Runs history up to yesterday, with nothing to compare with). The
 * server serves every figure and the verdict's parts (R-D1); this view words and draws them. Every state of F13:
 * a loading skeleton, an error with Retry (a failed refresh over an answer on screen keeps the answer and says so),
 * and a period that spent nothing.
 *
 * @param {object} props
 * @param {{data: object|null, loading: boolean, error: string|null, refresh: () => void}} props.cost the served data
 * @param {number|'all'} props.range the period in days, or {@code 'all'}
 * @param {(days: number|'all') => void} props.onRangeChange picks a period
 * @param {string|null} props.isolate the isolated type, or null
 * @param {(type: string|null) => void} props.onIsolate isolates a type
 * @param {(date: string) => void} props.onOpenDay opens a day in the Day view
 */
export default function RunsCostView({ cost, range, onRangeChange, isolate, onIsolate, onOpenDay }) {
  const { data } = cost;
  if (cost.error && !data) return <ViewError message={cost.error} onRetry={cost.refresh} testId="runs-cost-error" />;
  if (cost.loading || !data) return <ViewSkeleton testId="runs-cost-loading" />;
  return (
    <div className="flex flex-col gap-4" data-testid="runs-cost-view">
      {cost.error && <ViewStaleNote message={cost.error} onRetry={cost.refresh} testId="runs-cost-stale" />}
      <VerdictCard data={data} />
      <DailySpendChart
        data={data}
        isolate={isolate}
        onIsolate={onIsolate}
        range={range}
        onRangeChange={onRangeChange}
        onOpenDay={onOpenDay}
      />
      <CostChangeTable data={data} isolate={isolate} onIsolate={onIsolate} />
    </div>
  );
}

RunsCostView.propTypes = {
  cost: PropTypes.shape({
    data: PropTypes.object,
    loading: PropTypes.bool.isRequired,
    error: PropTypes.string,
    refresh: PropTypes.func.isRequired,
  }).isRequired,
  range: PropTypes.oneOfType([PropTypes.number, PropTypes.oneOf(['all'])]).isRequired,
  onRangeChange: PropTypes.func.isRequired,
  isolate: PropTypes.string,
  onIsolate: PropTypes.func.isRequired,
  onOpenDay: PropTypes.func.isRequired,
};
