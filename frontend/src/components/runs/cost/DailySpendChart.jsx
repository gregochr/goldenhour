import { useState } from 'react';
import PropTypes from 'prop-types';
import { Chip, ChipGroup, ChipRow } from '../../data/Chips.jsx';
import Segmented from '../../data/Segmented.jsx';
import useMeasuredWidth from '../../../hooks/useMeasuredWidth.js';
import useRovingSlots from '../../../hooks/useRovingSlots.js';
import { COST_ORDER, chartSeries, costTypeLabel } from '../../../utils/runsCost.js';
import { formatGbp, typeStyle } from '../../../utils/runsDay.js';
import { niceScale } from '../../../utils/runsTiming.js';
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
/** How faint the types other than the isolated one are drawn. */
const DIM = 0.12;
/** The most labels on the x axis, roughly. */
const X_LABELS = 8;
const RANGES = [14, 30, 60].map((days) => ({ value: String(days), label: `${days} days` }));

/** The y axis label for a gridline: pence only while the scale is small. */
const axisLabel = (value, step) => `£${value.toFixed(step < 1 ? 2 : (step % 1 !== 0 ? 1 : 0))}`;

/**
 * The Cost view's "Daily spend by type" card (the handoff's Daily spend chart): a hand-rolled SVG of one stacked bar
 * per full UK day (today is left out: it is incomplete), in the handoff's stack order, with a 7-day average line, the
 * legend chips that isolate one type (the others drop to 12%, and the line follows the isolated type), a hover and
 * focus tooltip with each type's pounds and the total, and a click or Enter on a day that opens that day in the Day
 * view. Tooltips live inside the SVG (no positioned overlay, no inline style).
 *
 * <p>The bars, their order and every figure are served; the 7-day average is the licensed derivation in
 * {@code utils/runsTiming.js}.
 *
 * @param {object} props
 * @param {object} props.data the served {@code RunCostResponse}
 * @param {string|null} props.isolate the isolated type, or null for all
 * @param {(type: string|null) => void} props.onIsolate isolates a type (null clears)
 * @param {number} props.range the period in days (14, 30 or 60)
 * @param {(days: number) => void} props.onRangeChange picks a period
 * @param {(date: string) => void} props.onOpenDay opens a day in the Day view
 */
export default function DailySpendChart({ data, isolate, onIsolate, range, onRangeChange, onOpenDay }) {
  const [hover, setHover] = useState(null);
  const [widthRef, measured] = useMeasuredWidth();
  const { shown, average } = chartSeries(data, isolate);
  const { containerRef, slotProps } = useRovingSlots(shown.length);
  const viewW = measured > 0 ? measured : VIEW_W_FALLBACK;
  const plotW = viewW - LEFT - RIGHT;
  const plotH = HEIGHT - TOP - BOTTOM;
  const n = shown.length;
  const peak = Math.max(0, ...shown.map((day) => Number(day.totalGbp)));
  const scale = niceScale(peak * 1.05);
  const slot = n > 0 ? plotW / n : plotW;
  const gap = Math.max(1, slot * GAP_SHARE);
  const y = (value) => TOP + plotH - (value / scale.max) * plotH;
  const everyNth = Math.ceil(n / X_LABELS);
  const empty = peak === 0;

  const hovered = hover != null ? shown[hover] : null;
  let tooltip = null;
  if (hovered) {
    const lines = COST_ORDER.filter((type) => Number(hovered.byType?.[type] ?? 0) > 0);
    const height = (lines.length + 2) * LINE_H + 12;
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
        <text x="10" y={18 + (lines.length + 1) * LINE_H} fontSize="11" className="fill-plex-text font-semibold">Total</text>
        <text x={TOOLTIP_W - 20} y={18 + (lines.length + 1) * LINE_H} fontSize="11" textAnchor="end" className="fill-plex-text font-mono font-semibold">
          {formatGbp(hovered.totalGbp)}{hovered.costComplete === false ? ' so far' : ''}
        </text>
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
            options={RANGES}
            value={String(range)}
            onChange={(value) => onRangeChange(Number(value))}
            testId="runs-cost-range"
          />
        </span>
      </div>
      <div className="mt-3">
        <ChipRow testId="runs-cost-legend">
          <ChipGroup label="Isolate a type">
            <Chip label="All types" nowrap on={isolate == null} onClick={() => onIsolate(null)} testId="runs-cost-iso-all" />
            {COST_ORDER.map((type) => (
              <Chip
                key={type}
                nowrap
                label={costTypeLabel(type, data.types)}
                swatchClass={typeStyle(type).swatch}
                on={isolate === type}
                onClick={() => onIsolate(isolate === type ? null : type)}
                testId={`runs-cost-iso-${type.toLowerCase()}`}
              />
            ))}
          </ChipGroup>
        </ChipRow>
      </div>
      <div ref={widthRef} className="mt-3" data-testid="runs-cost-chart">
        <svg
          ref={containerRef}
          viewBox={`0 0 ${viewW} ${HEIGHT}`}
          className="h-auto w-full"
          role="group"
          aria-label={`Daily spend by type over the last ${range} days`}
          data-testid="runs-cost-svg"
        >
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
                      x={LEFT + i * slot + gap / 2}
                      y={top}
                      width={Math.max(1, slot - gap)}
                      height={(amount / scale.max) * plotH}
                      className={typeStyle(type).fill}
                      opacity={isolate && isolate !== type ? DIM : 1}
                      data-type={type}
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
            return (
              <rect
                key={day.date}
                x={LEFT + i * slot}
                y={TOP}
                width={slot}
                height={plotH}
                className="cursor-pointer fill-transparent focus-visible:stroke-plex-gold"
                role="button"
                aria-label={`${formatDay(day.date)}: ${formatGbp(day.totalGbp)}${day.costComplete === false ? ' so far' : ''}. Open this day.`}
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
              {formatDayMonth(day.date)}
            </text>
          ) : null))}
          {tooltip}
        </svg>
        {empty && (
          <p className="mt-1 text-[12px] text-plex-text-secondary" data-testid="runs-cost-empty">
            No spend in the last {range} days.
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
  }).isRequired,
  isolate: PropTypes.string,
  onIsolate: PropTypes.func.isRequired,
  range: PropTypes.number.isRequired,
  onRangeChange: PropTypes.func.isRequired,
  onOpenDay: PropTypes.func.isRequired,
};
