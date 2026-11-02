import { useState } from 'react';
import { useIsMobile } from '../../../hooks/useIsMobile.js';
import PropTypes from 'prop-types';
import useMeasuredWidth from '../../../hooks/useMeasuredWidth.js';
import useRovingSlots from '../../../hooks/useRovingSlots.js';
import { signedPercent } from '../../../utils/runsCost.js';
import { typeStyle } from '../../../utils/runsDay.js';
import { ukDateStr } from '../../../utils/mapDates.js';
import { formatClockUk, formatSeconds } from '../../../utils/runsView.js';
import { durationAxis, rollingMedian } from '../../../utils/runsTiming.js';
import { formatDay, formatDayMonth } from '../../../utils/travelDays.js';

const CARD = 'rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3';
const VIEW_W_FALLBACK = 420;
const HEIGHT = 200;
const LEFT = 52;
const RIGHT = 8;
const TOP = 8;
const BOTTOM = 22;
const TOOLTIP_W = 176;
/** The hit radius of the transparent circle that carries a press on a phone. */
const HIT_R = 10;
const MS_PER_DAY = 86_400_000;
const X_TICKS = 4;
/** Headroom above the slowest dot. */
const HEADROOM = 1.05;

/**
 * The Timing view's "Is it getting slower?" card: each measured run as a dot over the range (runs above the served
 * 9-in-10 line in amber), a 7-run rolling median line, and the served first-half and second-half medians as a note.
 * A dot shows a tooltip on hover and focus; pressing it (or Enter) opens that run's day in the Day view. Hand-rolled
 * SVG sized to the card, tooltips inside it (no positioned overlay).
 *
 * <p>The rolling median is the licensed timing geometry ({@code utils/runsTiming.js}); "above 9 in 10" compares with the
 * served p90 and the half medians and their change are served.
 *
 * @param {object} props
 * @param {object} props.timing the served {@code RunTimingResponse}
 * @param {(date: string) => void} props.onOpenDay opens a day in the Day view
 * @param {Date} [props.now] the real clock: the right edge of the time axis
 */
export default function SlowerScatter({ timing, onOpenDay, now = new Date() }) {
  const isMobile = useIsMobile();
  const [hover, setHover] = useState(null);
  const [widthRef, measured] = useMeasuredWidth();
  const { runs, stats, type, rangeDays } = timing;
  const { containerRef, slotProps } = useRovingSlots(runs.length);
  const half = rangeDays / 2;
  let trend = '';
  if (stats.firstHalfMedianSeconds != null && stats.secondHalfMedianSeconds != null) {
    trend = `Median ${formatSeconds(stats.firstHalfMedianSeconds)} in the first ${half} days, ${formatSeconds(stats.secondHalfMedianSeconds)} in the last ${half}`;
    trend += stats.changePercent != null ? ` (${signedPercent(stats.changePercent)}).` : '.';
    if (type === 'ASK_READY') trend += ' Ask ready answers scales with the number of locations it covers.';
  }
  const heading = (
    <div>
      <h3 className="text-[13px] font-semibold text-plex-text">Is it getting slower?</h3>
      <p className="font-mono text-[11px] text-plex-text-secondary">each dot is a run {'·'} line is the rolling median</p>
    </div>
  );
  if (runs.length === 0) {
    return (
      <section className={CARD} data-testid="runs-scatter-card">
        {heading}
        <p className="mt-4 text-[13px] text-plex-text-secondary" data-testid="runs-scatter-empty">No runs measured in this range.</p>
      </section>
    );
  }

  const viewW = measured > 0 ? measured : VIEW_W_FALLBACK;
  const plotW = viewW - LEFT - RIGHT;
  const plotH = HEIGHT - TOP - BOTTOM;
  const t1 = now.getTime();
  const t0 = t1 - rangeDays * MS_PER_DAY;
  const x = (ms) => LEFT + Math.min(1, Math.max(0, (ms - t0) / (t1 - t0))) * plotW;
  const axis = durationAxis(Math.max(...runs.map((run) => run.durationSeconds)) * HEADROOM);
  const y = (seconds) => TOP + plotH - (seconds / axis.max) * plotH;
  const gridlines = Array.from({ length: Math.round(axis.max / axis.step) + 1 }, (_, i) => i * axis.step);
  const rolling = rollingMedian(runs.map((run) => run.durationSeconds));
  const p90 = stats.p90Seconds;
  const hovered = hover != null ? runs[hover] : null;
  let tooltip = null;
  if (hovered) {
    const slow = p90 != null && hovered.durationSeconds > p90;
    const left = x(Date.parse(hovered.startedAt)) > LEFT + plotW * 0.55;
    const rawX = left ? x(Date.parse(hovered.startedAt)) - TOOLTIP_W - 8 : x(Date.parse(hovered.startedAt)) + 8;
    const tx = Math.max(LEFT, Math.min(rawX, viewW - TOOLTIP_W - 2));
    const ty = Math.min(Math.max(TOP, y(hovered.durationSeconds) - 24), HEIGHT - BOTTOM - 58);
    tooltip = (
      <g transform={`translate(${tx} ${ty})`} pointerEvents="none" data-testid="runs-scatter-tooltip">
        <rect width={TOOLTIP_W} height="54" rx="6" className="fill-plex-bg stroke-plex-border-light" />
        <text x="10" y="16" fontSize="11" className="fill-plex-text font-semibold">{formatDay(hovered.date)} {formatClockUk(hovered.startedAt)}</text>
        <text x="10" y="31" fontSize="11" className="fill-plex-text font-mono">
          {formatSeconds(hovered.durationSeconds)}{slow ? ' · slower than 9 in 10' : ''}
        </text>
        <text x="10" y="46" fontSize="10" className="fill-plex-text-muted">click to open the day</text>
      </g>
    );
  }

  return (
    <section className={CARD} data-testid="runs-scatter-card">
      {heading}
      <div ref={widthRef} className="mt-2" data-testid="runs-scatter-chart">
        <svg
          ref={containerRef}
          viewBox={`0 0 ${viewW} ${HEIGHT}`}
          className="h-auto w-full"
          role="group"
          aria-label={`Duration of each of ${runs.length} runs over the last ${rangeDays} days`}
          data-testid="runs-scatter-svg"
        >
          {gridlines.map((seconds) => (
            <g key={seconds}>
              <line x1={LEFT} x2={LEFT + plotW} y1={y(seconds)} y2={y(seconds)} className="stroke-plex-border" strokeWidth="1" />
              <text x={LEFT - 6} y={y(seconds) + 3} fontSize="10" textAnchor="end" className="fill-plex-text-muted font-mono">{formatSeconds(seconds)}</text>
            </g>
          ))}
          {runs.map((run, i) => {
            const slow = p90 != null && run.durationSeconds > p90;
            const roving = slotProps(i);
            const cx = x(Date.parse(run.startedAt));
            const cy = y(run.durationSeconds);
            const interactive = {
              role: 'button',
              'data-slow': slow ? 'true' : 'false',
              'aria-label': `${formatDay(run.date)} ${formatClockUk(run.startedAt)}: ${formatSeconds(run.durationSeconds)}${slow ? ', slower than 9 in 10' : ''}. Open this day.`,
              'data-slot': roving['data-slot'],
              tabIndex: roving.tabIndex,
              'data-testid': `runs-scatter-dot-${run.id}`,
              onClick: () => onOpenDay(run.date),
              onKeyDown: (event) => {
                roving.onKeyDown(event);
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onOpenDay(run.date);
                }
              },
              onFocus: () => {
                roving.onFocus();
                setHover(i);
              },
              onBlur: () => setHover(null),
              onMouseEnter: () => setHover(i),
              onMouseLeave: () => setHover(null),
            };
            const dotClass = `${slow ? 'fill-verdict-marginal' : typeStyle(type).fill}`;
            // On a phone the 3px dot is far under a fingertip: a transparent 10px circle carries the press instead.
            return isMobile ? (
              <g key={run.id}>
                <circle cx={cx} cy={cy} r="3" opacity="0.85" className={dotClass} data-slow={slow ? 'true' : 'false'} pointerEvents="none" />
                <circle cx={cx} cy={cy} r={HIT_R} className="cursor-pointer fill-transparent focus-visible:stroke-plex-gold" {...interactive} />
              </g>
            ) : (
              <circle
                key={run.id}
                cx={cx}
                cy={cy}
                r="3"
                opacity="0.85"
                className={`cursor-pointer focus-visible:stroke-plex-gold ${dotClass}`}
                {...interactive}
              />
            );
          })}
          <polyline
            fill="none"
            strokeWidth="1.5"
            className="stroke-plex-text"
            points={runs.map((run, i) => `${x(Date.parse(run.startedAt))},${y(rolling[i])}`).join(' ')}
            pointerEvents="none"
            data-testid="runs-scatter-rolling"
          />
          {Array.from({ length: X_TICKS + 1 }, (_, i) => {
            const ms = t0 + ((t1 - t0) * i) / X_TICKS;
            let anchor = 'middle';
            if (i === 0) anchor = 'start';
            else if (i === X_TICKS) anchor = 'end';
            return (
              <text key={ms} x={x(ms)} y={HEIGHT - 5} fontSize="10" textAnchor={anchor} className="fill-plex-text-muted font-mono">
                {formatDayMonth(ukDateStr(new Date(ms)), now)}
              </text>
            );
          })}
          {tooltip}
        </svg>
      </div>
      {trend !== '' && <p className="mt-2 text-[12px] text-plex-text-secondary" data-testid="runs-scatter-trend">{trend}</p>}
    </section>
  );
}

SlowerScatter.propTypes = {
  timing: PropTypes.shape({
    type: PropTypes.string.isRequired,
    rangeDays: PropTypes.number.isRequired,
    runs: PropTypes.array.isRequired,
    stats: PropTypes.object.isRequired,
  }).isRequired,
  onOpenDay: PropTypes.func.isRequired,
  now: PropTypes.instanceOf(Date),
};
