import PropTypes from 'prop-types';
import DurationHistogram from './DurationHistogram.jsx';
import ReadyByCard from './ReadyByCard.jsx';
import SlowerScatter from './SlowerScatter.jsx';
import SunsetPlanner from './SunsetPlanner.jsx';
import { Chip, ChipDivider, ChipGroup, ChipRow } from '../../data/Chips.jsx';
import Segmented from '../../data/Segmented.jsx';
import { ViewError, ViewSkeleton, ViewStaleNote } from '../RunsViewState.jsx';
import { costTypeLabel } from '../../../utils/runsCost.js';
import { isCycleType, typeStyle } from '../../../utils/runsDay.js';
import { TIMING_TYPES } from '../../../utils/runsTiming.js';
import { formatDay } from '../../../utils/travelDays.js';
import { formatSeconds } from '../../../utils/runsView.js';

const RANGES = [30, 60, 120].map((days) => ({ value: String(days), label: `${days} days` }));
const MEASURES = [
  { value: 'cycle', label: 'Cycle only' },
  { value: 'ready', label: 'Until answers ready' },
];

/** One cell of the stat strip: a label, a mono value and a sub-line. */
function Cell({ label, value, sub = '', valueClass = 'text-plex-text', testId }) {
  return (
    <div className="min-w-0 bg-plex-surface px-4 py-3 max-[639px]:px-3" data-testid={testId}>
      <p className="text-[11.5px] text-plex-text-secondary">{label}</p>
      <p className={`mt-0.5 font-mono text-[20px] leading-tight ${valueClass}`} data-testid={`${testId}-value`}>{value}</p>
      <p className="mt-0.5 min-h-4 text-[11.5px] text-plex-text-secondary" data-testid={`${testId}-sub`}>{sub}</p>
    </div>
  );
}

Cell.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  sub: PropTypes.string,
  valueClass: PropTypes.string,
  testId: PropTypes.string.isRequired,
};

/** The five-cell stat strip: Median, 9 in 10 within, Slowest, Fastest, Runs measured (all served). */
function StatStrip({ timing }) {
  const { stats, rangeDays, measure } = timing;
  const dash = (seconds) => (seconds == null ? '—' : formatSeconds(seconds));
  const measured = measure === 'ready' ? 'start to answers ready' : 'run duration';
  return (
    <div className="@container">
      <div
        className="grid grid-cols-5 gap-px overflow-hidden rounded-[10px] border border-plex-border bg-plex-border @max-[700px]:grid-cols-2"
        data-testid="runs-timing-stats"
      >
        <Cell testId="runs-timing-median" label="Median" value={dash(stats.medianSeconds)} sub={stats.count > 0 ? 'half of runs are faster' : ''} />
        <Cell testId="runs-timing-p90" label="9 in 10 within" value={dash(stats.p90Seconds)} valueClass="text-verdict-marginal" sub={stats.p90Seconds != null ? '90th percentile' : ''} />
        <Cell testId="runs-timing-slowest" label="Slowest" value={dash(stats.slowest?.seconds)} sub={stats.slowest ? formatDay(stats.slowest.date) : ''} />
        <Cell testId="runs-timing-fastest" label="Fastest" value={dash(stats.fastest?.seconds)} sub={stats.fastest ? formatDay(stats.fastest.date) : ''} />
        <Cell testId="runs-timing-count" label="Runs measured" value={String(stats.count)} sub={`last ${rangeDays} days · ${measured}`} />
      </div>
    </div>
  );
}

StatStrip.propTypes = { timing: PropTypes.object.isRequired };

/**
 * Operations → Runs → Timing (runs-deep-dive-plan.md §4 D4): how long one run type takes. Type chips (each with its
 * median over the range), the range (30, 60 or 120 days) and, for the three cycles only, the measure (the cycle on its
 * own, or until the Ask ready answers that follow are done); the stat strip; the duration histogram with its density
 * curve and markers; then the sunset planner (Afternoon refresh with a sunset trigger) or the ready-by card, beside
 * the "is it getting slower?" scatter. The server serves every statistic (R-D1); the charts' geometry and the planner
 * replay are the licensed derivations in {@code utils/runsTiming.js}. Every state of F13: loading, error with Retry,
 * no runs, one run (no curve, no 9-in-10), and Afternoon without a sunset trigger.
 *
 * <p>The type chips stay on screen while a type loads, so a reader can keep moving; their medians come with the answer.
 *
 * @param {object} props
 * @param {{data: object|null, loading: boolean, error: string|null, refresh: () => void}} props.timing the served data
 * @param {string} props.type the chosen type
 * @param {(type: string) => void} props.onTypeChange picks a type
 * @param {number} props.range the range in days
 * @param {(days: number) => void} props.onRangeChange picks a range
 * @param {'cycle'|'ready'} props.measure the measure (always {@code cycle} for a non-cycle type)
 * @param {(measure: string) => void} props.onMeasureChange picks a measure
 * @param {(date: string) => void} props.onOpenDay opens a day in the Day view
 * @param {Date} [props.now] the real clock, for the scatter's time axis
 */
export default function RunsTimingView({
  timing, type, onTypeChange, range, onRangeChange, measure, onMeasureChange, onOpenDay, now = undefined,
}) {
  const { data } = timing;
  const medians = data?.typeMedians ?? {};
  const cycle = isCycleType(type);

  const controls = (
    <div className="flex flex-col gap-2" data-testid="runs-timing-controls">
      <ChipRow>
        <ChipGroup label="Run type">
          {TIMING_TYPES.map((t) => (
            <Chip
              key={t}
              label={costTypeLabel(t)}
              count={medians[t] != null ? formatSeconds(medians[t]) : undefined}
              swatchClass={typeStyle(t).swatch}
              on={type === t}
              onClick={() => onTypeChange(t)}
              testId={`runs-timing-type-${t.toLowerCase()}`}
            />
          ))}
        </ChipGroup>
        <ChipDivider />
        <Segmented label="Range" options={RANGES} value={String(range)} onChange={(value) => onRangeChange(Number(value))} testId="runs-timing-range" />
      </ChipRow>
      {cycle && (
        <Segmented label="Measure" options={MEASURES} value={measure} onChange={onMeasureChange} testId="runs-timing-measure" />
      )}
    </div>
  );

  let body;
  if (timing.error && !data) {
    body = <ViewError message={timing.error} onRetry={timing.refresh} testId="runs-timing-error" />;
  } else if (timing.loading || !data) {
    body = <ViewSkeleton testId="runs-timing-loading" />;
  } else {
    const planner = data.type === 'AFTERNOON' && data.trigger?.solarEvent === 'SUNSET';
    body = (
      <>
        {timing.error && <ViewStaleNote message={timing.error} onRetry={timing.refresh} testId="runs-timing-stale" />}
        <StatStrip timing={data} />
        {measure === 'ready' && data.withoutAnswersLink > 0 && (
          <p className="font-mono text-[11px] text-plex-text-secondary" data-testid="runs-timing-unlinked">
            {data.withoutAnswersLink} {data.withoutAnswersLink === 1 ? 'cycle has' : 'cycles have'} no linked answers run and {data.withoutAnswersLink === 1 ? 'is' : 'are'} left out of this measure.
          </p>
        )}
        <DurationHistogram timing={data} />
        <div className="grid grid-cols-2 gap-4 @max-[1000px]:grid-cols-1" data-testid="runs-timing-lower">
          {planner ? <SunsetPlanner key={`${data.trigger.offsetMinutes}`} timing={data} /> : <ReadyByCard timing={data} />}
          <SlowerScatter timing={data} onOpenDay={onOpenDay} now={now} />
        </div>
      </>
    );
  }

  return (
    <div className="@container flex flex-col gap-4" data-testid="runs-timing-view">
      {controls}
      {body}
    </div>
  );
}

RunsTimingView.propTypes = {
  timing: PropTypes.shape({
    data: PropTypes.object,
    loading: PropTypes.bool.isRequired,
    error: PropTypes.string,
    refresh: PropTypes.func.isRequired,
  }).isRequired,
  type: PropTypes.string.isRequired,
  onTypeChange: PropTypes.func.isRequired,
  range: PropTypes.number.isRequired,
  onRangeChange: PropTypes.func.isRequired,
  measure: PropTypes.oneOf(['cycle', 'ready']).isRequired,
  onMeasureChange: PropTypes.func.isRequired,
  onOpenDay: PropTypes.func.isRequired,
  now: PropTypes.instanceOf(Date),
};
