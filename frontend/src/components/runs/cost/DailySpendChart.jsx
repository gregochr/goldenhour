import { useId, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { Chip, ChipDivider, ChipGroup, ChipRow } from '../../data/Chips.jsx';
import Segmented from '../../data/Segmented.jsx';
import useMeasuredWidth from '../../../hooks/useMeasuredWidth.js';
import useRovingSlots from '../../../hooks/useRovingSlots.js';
import {
  COST_ORDER, TEST_SUITES, TEST_SWATCH, chartSeries, costSelection, costTypeLabel, selectionSummary, sinceLabel,
  testingDayMap, testingOf,
} from '../../../utils/runsCost.js';
import { toggleType } from '../../../utils/runsQuery.js';
import { formatGbp, typeStyle } from '../../../utils/runsDay.js';
import { COST_RANGES, niceScale } from '../../../utils/runsTiming.js';
import { useIsMobile } from '../../../hooks/useIsMobile.js';
import { formatDay, formatDayMonth } from '../../../utils/travelDays.js';

const CARD = 'rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3';
// The drawing space. The viewBox is as wide as the card in pixels (measured), so the text stays real pixels.
const VIEW_W_FALLBACK = 800;
const HEIGHT = 260;
const LEFT = 46;
const RIGHT = 8;
const TOP = 10;
const BOTTOM = 24;
const TOOLTIP_W = 196;
const LINE_H = 15;
/** The share of a slot left empty between two bars. */
const GAP_SHARE = 0.2;
/** Below this slot width (px) the gap goes and the bars touch, so a long history keeps its bars. */
const MIN_GAP_SLOT = 5;
/** The widest a bar is drawn (px), so a short range does not read as blocks. */
const MAX_BAR = 40;
/** How faint the types that are not selected are drawn. */
const DIM = 0.12;
/** The most labels on the x axis, roughly. */
const X_LABELS = 8;
/** The period buttons: the long labels, and the short ones a phone has room for (the accessible name stays long). */
const rangeOptions = (short) => COST_RANGES.map((range) => {
  const long = range === 'all' ? 'All time' : `${range} days`;
  const brief = range === 'all' ? 'All' : `${range}d`;
  return { value: String(range), label: short ? brief : long, ariaLabel: long };
});

/**
 * The testing layer's pattern fills and strokes, one class set per suite. Whole class names, so Tailwind's scanner
 * keeps each colour token (a name built at runtime would be pruned).
 */
const TEST_FILL = { SKY: 'fill-test-sky', ASK: 'fill-test-ask', SMOKE: 'fill-test-smoke' };
const TEST_TINT = { SKY: 'fill-test-sky/20', ASK: 'fill-test-ask/20', SMOKE: 'fill-test-smoke/20' };
const TEST_STROKE = { SKY: 'stroke-test-sky', ASK: 'stroke-test-ask', SMOKE: 'stroke-test-smoke' };
const TEST_LABEL = { SKY: 'CI tests · sky', ASK: 'CI tests · ask', SMOKE: 'CI tests · smoke' };

/** One suite's hatch: a faint tint under 2.5px diagonal stripes, in the suite's own colour token. */
function HatchPattern({ id, tint, stripe }) {
  return (
    <pattern id={id} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
      <rect width="6" height="6" className={tint} />
      <rect width="2.5" height="6" className={stripe} />
    </pattern>
  );
}

HatchPattern.propTypes = { id: PropTypes.string.isRequired, tint: PropTypes.string.isRequired, stripe: PropTypes.string.isRequired };

/** The y axis label for a gridline: pence only while the scale is small. */
const axisLabel = (value, step) => `£${value.toFixed(step < 1 ? 2 : (step % 1 !== 0 ? 1 : 0))}`;

/**
 * The Cost view's "Daily spend by type" card (the handoff's Daily spend chart): a hand-rolled SVG of one stacked bar
 * per full UK day (today is left out: it is incomplete), in the handoff's stack order, with a 7-day average line, the
 * legend chips that pick any number of types (the others drop to 12%, the line follows the picked types' sum, and a
 * line under the chips totals them), a hover and focus tooltip with each type's pounds, the picked types' subtotal
 * (when two or more are picked) and the total, and a click or Enter on a day that opens that day in the Day view. Tooltips live inside the SVG (no positioned overlay, no inline style).
 *
 * <p>The bars, their order and every figure are served; the 7-day average is the licensed derivation in
 * {@code utils/runsTiming.js}.
 *
 * @param {object} props
 * @param {object} props.data the served {@code RunCostResponse}
 * @param {string|null} props.selected the picked types as a comma-separated list, or null for all
 * @param {(types: string|null) => void} props.onSelect picks types (the new list; null clears)
 * @param {number|'all'} props.range the period in days (7, 14, 30, 60 or 90), or {@code 'all'}
 * @param {(days: number|'all') => void} props.onRangeChange picks a period
 * @param {(date: string) => void} props.onOpenDay opens a day in the Day view
 */
export default function DailySpendChart({
  data, selected, onSelect, range, onRangeChange, onOpenDay, showTesting = false, onShowTestingChange = () => {},
}) {
  const [hover, setHover] = useState(null);
  const patternId = useId().replace(/:/g, '');
  const testing = testingOf(data);
  // The testing layer is drawn only while it is asked for and there is something to draw; it never follows the
  // picked app types (those live in the address) and is always full strength, on top of the day's whole app stack.
  const withTesting = testing != null && showTesting;
  const testingDays = useMemo(() => testingDayMap(data), [data]);
  const suiteLabel = (suite) => testing?.types.find((t) => t.suite === suite)?.label ?? TEST_LABEL[suite];
  const [widthRef, measured] = useMeasuredWidth();
  const isMobile = useIsMobile();
  const allTime = data.allTime === true;
  const since = allTime ? sinceLabel(data.firstDay) : null;
  const picked = costSelection(selected);
  const { shown, average } = chartSeries(data, picked);
  const summary = selectionSummary(data, picked);
  const subtotal = (day) => picked.reduce((sum, type) => sum + Number(day.byType?.[type] ?? 0), 0);
  const { containerRef, slotProps } = useRovingSlots(shown.length);
  const viewW = measured > 0 ? measured : VIEW_W_FALLBACK;
  const plotW = viewW - LEFT - RIGHT;
  const plotH = HEIGHT - TOP - BOTTOM;
  const n = shown.length;
  const peak = Math.max(0, ...shown.map((day) => Number((withTesting && testingDays.get(day.date)?.appPlusTestingGbp) || day.totalGbp)));
  const scale = niceScale(peak * 1.05);
  const slot = n > 0 ? plotW / n : plotW;
  const gap = slot >= MIN_GAP_SLOT ? Math.max(1, slot * GAP_SHARE) : 0;
  const barW = Math.min(MAX_BAR, Math.max(1, slot - gap));
  const now = new Date();
  const y = (value) => TOP + plotH - (value / scale.max) * plotH;
  const everyNth = Math.ceil(n / X_LABELS);
  const empty = peak === 0;

  const hovered = hover != null ? shown[hover] : null;
  let tooltip = null;
  if (hovered) {
    const lines = COST_ORDER.filter((type) => Number(hovered.byType?.[type] ?? 0) > 0);
    const showSelected = picked.length > 1;
    const hoveredTesting = withTesting ? testingDays.get(hovered.date) ?? null : null;
    const testLines = hoveredTesting ? TEST_SUITES.filter((suite) => Number(hoveredTesting.byType?.[suite] ?? 0) > 0) : [];
    // Below the type lines: [Selected], [App], Total. The App line appears only with the testing layer, where the
    // Total is the app and the CI tests together.
    const footer = [];
    if (showSelected) {
      footer.push({ key: 'selected', label: 'Selected', value: formatGbp(subtotal(hovered)), testId: 'runs-cost-tooltip-selected' });
    }
    if (withTesting) {
      footer.push({ key: 'app', label: 'App', value: formatGbp(hovered.totalGbp), testId: 'runs-cost-tooltip-app' });
    }
    footer.push({
      key: 'total',
      label: 'Total',
      value: `${formatGbp(withTesting && hoveredTesting ? hoveredTesting.appPlusTestingGbp : hovered.totalGbp)}${hovered.costComplete === false ? ' so far' : ''}`,
      testId: 'runs-cost-tooltip-total',
      strong: true,
    });
    const rows = 1 + lines.length + testLines.length + footer.length;
    const height = rows * LINE_H + 12;
    const footerY = (k) => 18 + (1 + lines.length + testLines.length + k) * LINE_H;
    const left = hover > n * 0.6;
    const rawX = left ? LEFT + hover * slot - TOOLTIP_W - 4 : LEFT + (hover + 1) * slot + 4;
    const tx = Math.max(LEFT, Math.min(rawX, viewW - TOOLTIP_W - 2));
    const ty = Math.max(TOP, Math.min(TOP + 20, HEIGHT - BOTTOM - height));
    tooltip = (
      <g transform={`translate(${tx} ${ty})`} pointerEvents="none" data-testid="runs-cost-tooltip">
        <rect width={TOOLTIP_W} height={height} rx="6" className="fill-plex-bg stroke-plex-border-light" />
        <text x="10" y="18" fontSize="11" className="fill-plex-text font-semibold">{formatDay(hovered.date)}</text>
        {lines.map((type, i) => (
          <g key={type} transform={`translate(10 ${18 + (i + 1) * LINE_H})`}>
            <rect y="-8" width="8" height="8" rx="1" className={typeStyle(type).fill} />
            <text x="14" fontSize="11" className="fill-plex-text-secondary">{costTypeLabel(type, data.types)}</text>
            <text x={TOOLTIP_W - 20} fontSize="11" textAnchor="end" className="fill-plex-text font-mono">{formatGbp(hovered.byType[type])}</text>
          </g>
        ))}
        {testLines.map((suite, i) => (
          <g key={suite} transform={`translate(10 ${18 + (lines.length + i + 1) * LINE_H})`} data-testid={`runs-cost-tooltip-${suite.toLowerCase()}`}>
            <rect y="-8" width="8" height="8" rx="1" fill={`url(#${patternId}-${suite.toLowerCase()})`} className={TEST_STROKE[suite]} strokeWidth="1" />
            <text x="14" fontSize="11" className="fill-plex-text-secondary">{suiteLabel(suite)}</text>
            <text x={TOOLTIP_W - 20} fontSize="11" textAnchor="end" className="fill-plex-text font-mono">{formatGbp(hoveredTesting.byType[suite])}</text>
          </g>
        ))}
        {footer.map((line, k) => (
          <g key={line.key}>
            <text x="10" y={footerY(k)} fontSize="11" className={line.strong ? 'fill-plex-text font-semibold' : 'fill-plex-text-secondary'}>{line.label}</text>
            <text
              x={TOOLTIP_W - 20}
              y={footerY(k)}
              fontSize="11"
              textAnchor="end"
              className={`fill-plex-text font-mono ${line.strong ? 'font-semibold' : ''}`}
              data-testid={line.testId}
            >
              {line.value}
            </text>
          </g>
        ))}
      </g>
    );
  }

  return (
    <section className={CARD} data-testid="runs-cost-chart-card">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h3 className="text-[13px] font-semibold text-plex-text">Daily spend by type</h3>
        <span className="font-mono text-[11px] text-plex-text-secondary">
          through yesterday {'·'} line is the 7-day average {'·'} click a day to open it
        </span>
        <span className="ml-auto">
          <Segmented
            label="Period"
            options={rangeOptions(isMobile)}
            value={String(range)}
            onChange={(value) => onRangeChange(value === 'all' ? 'all' : Number(value))}
            testId="runs-cost-range"
          />
        </span>
      </div>
      <div className="mt-3">
        <ChipRow testId="runs-cost-legend">
          <ChipGroup label="Show types" wrap>
            <Chip label="All app types" nowrap on={picked.length === 0} onClick={() => onSelect(null)} testId="runs-cost-iso-all" />
            {COST_ORDER.map((type) => (
              <Chip
                key={type}
                nowrap
                label={costTypeLabel(type, data.types)}
                swatchClass={typeStyle(type).swatch}
                on={picked.includes(type)}
                onClick={() => onSelect(toggleType(selected, type))}
                testId={`runs-cost-iso-${type.toLowerCase()}`}
              />
            ))}
          </ChipGroup>
          {testing && (
            <>
              <ChipDivider testId="runs-cost-testing-divider" />
              <ChipGroup label="CI tests">
                <Chip
                  label="CI tests"
                  nowrap
                  swatchClass={TEST_SWATCH.SKY}
                  on={showTesting}
                  onClick={() => onShowTestingChange(!showTesting)}
                  testId="runs-cost-iso-testing"
                />
              </ChipGroup>
            </>
          )}
        </ChipRow>
        {summary && (
          <p className="mt-2 text-[12px] text-plex-text-secondary" aria-live="polite" data-testid="runs-cost-selection-total">
            {summary.labels.join(' + ')}: <span className="font-mono text-plex-text">{formatGbp(summary.gbp)}</span>
            {allTime ? ` since ${since}` : ` over the last ${range} days`}{summary.costComplete ? '' : ' so far'}
          </p>
        )}
      </div>
      <div ref={widthRef} className="mt-3" data-testid="runs-cost-chart">
        <svg
          ref={containerRef}
          viewBox={`0 0 ${viewW} ${HEIGHT}`}
          className="h-auto w-full"
          role="group"
          aria-label={`${allTime ? `Daily spend by type since ${since}` : `Daily spend by type over the last ${range} days`}${withTesting ? ', including CI tests' : ''}`}
          data-testid="runs-cost-svg"
        >
          {withTesting && (
            <defs>
              {TEST_SUITES.map((suite) => (
                <HatchPattern key={suite} id={`${patternId}-${suite.toLowerCase()}`} tint={TEST_TINT[suite]} stripe={TEST_FILL[suite]} />
              ))}
            </defs>
          )}
          {Array.from({ length: 5 }, (_, i) => scale.step * i).map((value) => (
            <g key={value}>
              <line x1={LEFT} x2={LEFT + plotW} y1={y(value)} y2={y(value)} className="stroke-plex-border" strokeWidth="1" />
              <text x={LEFT - 6} y={y(value) + 3} fontSize="10" textAnchor="end" className="fill-plex-text-muted font-mono">
                {axisLabel(value, scale.step)}
              </text>
            </g>
          ))}
          {shown.map((day, i) => {
            let stacked = 0;
            return (
              <g key={day.date} data-testid={`runs-cost-bar-${day.date}`}>
                {COST_ORDER.map((type) => {
                  const amount = Number(day.byType?.[type] ?? 0);
                  if (amount <= 0) return null;
                  const top = y(stacked + amount);
                  stacked += amount;
                  return (
                    <rect
                      key={type}
                      x={LEFT + i * slot + (slot - barW) / 2}
                      y={top}
                      width={barW}
                      height={(amount / scale.max) * plotH}
                      className={typeStyle(type).fill}
                      opacity={picked.length > 0 && !picked.includes(type) ? DIM : 1}
                      data-type={type}
                    />
                  );
                })}
                {withTesting && TEST_SUITES.map((suite) => {
                  const amount = Number(testingDays.get(day.date)?.byType?.[suite] ?? 0);
                  if (amount <= 0) return null;
                  const top = y(stacked + amount);
                  stacked += amount;
                  return (
                    <rect
                      key={suite}
                      x={LEFT + i * slot + (slot - barW) / 2}
                      y={top}
                      width={barW}
                      height={(amount / scale.max) * plotH}
                      fill={`url(#${patternId}-${suite.toLowerCase()})`}
                      className={TEST_STROKE[suite]}
                      strokeWidth="1"
                      data-testing={suite}
                    />
                  );
                })}
              </g>
            );
          })}
          <polyline
            fill="none"
            strokeWidth="1.5"
            className="stroke-plex-text"
            points={average.map((v, i) => `${LEFT + i * slot + slot / 2},${y(v)}`).join(' ')}
            pointerEvents="none"
            data-testid="runs-cost-average"
          />
          {shown.map((day, i) => {
            const roving = slotProps(i);
            const dayTesting = withTesting ? testingDays.get(day.date) ?? null : null;
            const soFar = day.costComplete === false ? ' so far' : '';
            const label = dayTesting
              ? `${formatDay(day.date)}: ${formatGbp(dayTesting.appPlusTestingGbp)} in total${soFar}. `
                + `App ${formatGbp(day.totalGbp)}. `
                + `${TEST_SUITES.map((suite) => `${suiteLabel(suite)} ${formatGbp(dayTesting.byType?.[suite] ?? 0)}`).join('. ')}. `
                + 'Open this day.'
              : `${formatDay(day.date)}: ${formatGbp(day.totalGbp)}${soFar}. Open this day.`;
            return (
              <rect
                key={day.date}
                x={LEFT + i * slot}
                y={TOP}
                width={slot}
                height={plotH}
                className="cursor-pointer fill-transparent focus-visible:stroke-plex-gold"
                role="button"
                aria-label={label}
                data-slot={roving['data-slot']}
                tabIndex={roving.tabIndex}
                onClick={() => onOpenDay(day.date)}
                onKeyDown={(event) => {
                  roving.onKeyDown(event);
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onOpenDay(day.date);
                  }
                }}
                onFocus={() => {
                  roving.onFocus();
                  setHover(i);
                }}
                onBlur={() => setHover(null)}
                onMouseEnter={() => setHover(i)}
                onMouseLeave={() => setHover(null)}
                data-testid={`runs-cost-day-${day.date}`}
              />
            );
          })}
          {shown.map((day, i) => ((n - 1 - i) % everyNth === 0 ? (
            <text key={day.date} x={LEFT + i * slot + slot / 2} y={HEIGHT - 6} fontSize="10" textAnchor="middle" className="fill-plex-text-muted font-mono">
              {formatDayMonth(day.date, now)}
            </text>
          ) : null))}
          {tooltip}
        </svg>
        {empty && (
          <p className="mt-1 text-[12px] text-plex-text-secondary" data-testid="runs-cost-empty">
            {allTime ? `No spend since ${since}.` : `No spend in the last ${range} days.`}
          </p>
        )}
        {testing && (
          <p className="mt-1 font-mono text-[10.5px] text-plex-text-muted" data-testid="runs-cost-testing-note">
            Hatched = CI tests, counted separately from the app. Off by default.
          </p>
        )}
      </div>
    </section>
  );
}

DailySpendChart.propTypes = {
  data: PropTypes.shape({
    days: PropTypes.array.isRequired,
    types: PropTypes.array.isRequired,
    rangeDays: PropTypes.number.isRequired,
    firstDay: PropTypes.string,
    allTime: PropTypes.bool,
    testing: PropTypes.object,
  }).isRequired,
  selected: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
  range: PropTypes.oneOfType([PropTypes.number, PropTypes.oneOf(['all'])]).isRequired,
  onRangeChange: PropTypes.func.isRequired,
  onOpenDay: PropTypes.func.isRequired,
  showTesting: PropTypes.bool,
  onShowTestingChange: PropTypes.func,
};
