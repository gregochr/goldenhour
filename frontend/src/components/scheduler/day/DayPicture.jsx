import { useRef } from 'react';
import PropTypes from 'prop-types';
import useElementWidth from '../../../hooks/useElementWidth.js';
import { useIsMobile } from '../../../hooks/useIsMobile.js';
import { formatClock } from '../../../utils/schedulerView.js';
import {
  DIAMOND_HALF_PX,
  dayAxis,
  fireStatusStyle,
  fireWidthPx,
  gridlines,
  instantMs,
  isFuture,
  kindStyle,
  markShape,
  nightBands,
  onAxis,
  rowAriaLabel,
  timeToX,
} from '../../../utils/schedulerDay.js';

/** The smallest the plot is drawn, px, whatever the container says. */
export const MIN_PLOT_WIDTH = 360;

/** Geometry, px: a row's height, the space above the first row (sun labels) and below the last (axis labels). */
export const ROW_HEIGHT = 24;
export const TOP_PAD = 36;
export const BOTTOM_PAD = 24;

/** Where the status dot sits beyond a mark, and the room a time label needs after it, px. */
const DOT_OFFSET = 6;
const DOT_RADIUS = 3.5;
const LABEL_ROOM = 52;

/** A mark's dashes when its job is paused. */
const PAUSED_DASH = '2 2';

/** One fire's mark: bar, diamond or tick; a status dot; and the time beside it when there is room. Decorative. */
function FireMark({ row, fire, index, x, cy, widthPx, gapPx, plotWidth, onSelect }) {
  const kind = kindStyle(row.scheduleType);
  const status = fireStatusStyle(fire.status);
  const future = isFuture(fire);
  const shape = markShape({ widthPx, gapPx });
  const paint = row.paused ? `fill-none ${kind.stroke}` : `${kind.fill} ${kind.stroke}`;
  const common = {
    className: paint,
    opacity: future ? 0.3 : 1,
    strokeDasharray: row.paused ? PAUSED_DASH : undefined,
    'data-testid': `scheduler-mark-${row.jobKey}-${index}`,
  };
  let mark;
  if (shape === 'tick') {
    mark = <rect {...common} x={x - 1} y={cy - 6} width="2" height="12" />;
  } else if (shape === 'diamond') {
    const h = DIAMOND_HALF_PX;
    mark = <path {...common} d={`M${x} ${cy - h}L${x + h} ${cy}L${x} ${cy + h}L${x - h} ${cy}Z`} />;
  } else {
    mark = <rect {...common} x={x} y={cy - 7} width={Math.max(1, Math.min(widthPx, plotWidth - x))} height="14" rx="3" />;
  }
  const dotX = x + Math.max(shape === 'bar' ? widthPx : 0, DIAMOND_HALF_PX) + DOT_OFFSET;
  const showTime = gapPx > LABEL_ROOM && dotX + LABEL_ROOM < plotWidth;
  return (
    <g
      aria-hidden="true"
      className="cursor-pointer"
      data-testid={`scheduler-fire-${row.jobKey}-${index}`}
      data-status={fire.status}
      data-shape={shape}
      data-future={future ? 'true' : 'false'}
      onClick={() => onSelect(row.jobKey)}
    >
      {mark}
      {!(row.paused && future) && (
        <circle
          cx={dotX}
          cy={cy}
          r={DOT_RADIUS}
          className={`${status.fill} ${status.stroke}`}
          data-testid={`scheduler-dot-${row.jobKey}-${index}`}
        />
      )}
      {showTime && (
        <text x={dotX + 7} y={cy + 3.5} className="fill-plex-text-muted font-mono text-[10px]">
          {formatClock(fire.at)}
        </text>
      )}
    </g>
  );
}

FireMark.propTypes = {
  row: PropTypes.object.isRequired,
  fire: PropTypes.object.isRequired,
  index: PropTypes.number.isRequired,
  x: PropTypes.number.isRequired,
  cy: PropTypes.number.isRequired,
  widthPx: PropTypes.number.isRequired,
  gapPx: PropTypes.number.isRequired,
  plotWidth: PropTypes.number.isRequired,
  onSelect: PropTypes.func.isRequired,
};

/** A row's name, the label that opens the job's panel (a button for the keyboard, Enter or Space). */
function RowLabel({ row, onSelect }) {
  const open = () => onSelect(row.jobKey);
  const onKeyDown = (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      open();
    }
  };
  return (
    <div
      role="button"
      tabIndex={0}
      aria-label={rowAriaLabel(row)}
      onClick={open}
      onKeyDown={onKeyDown}
      className="flex h-6 cursor-pointer items-center gap-1 truncate pr-2 text-[13px] text-plex-text hover:text-plex-gold-light focus-visible:outline-2 focus-visible:outline-plex-gold"
      data-testid={`scheduler-row-${row.jobKey}`}
    >
      <span className="truncate">{row.name}</span>
      {row.usesClaude && (
        <span className="shrink-0 text-skyeval-version" aria-hidden="true" data-testid={`scheduler-pound-${row.jobKey}`}>
          {'£'}
        </span>
      )}
    </div>
  );
}

RowLabel.propTypes = { row: PropTypes.object.isRequired, onSelect: PropTypes.func.isRequired };

/**
 * The Day's jobs picture (scheduler-redesign-plan.md D12): a label column of one button per scheduled job beside an SVG
 * of the UK civil day, 00:00 to 24:00 (23 or 25 hours on a clock-change day) with 3-hourly gridlines, the night shaded
 * from the served sunrise and sunset, dashed Sunrise and Sunset lines, and a Now line on today. Each fire is a bar (or a
 * diamond below 6px, or a tick when crowded) in the job's colour, then a status dot and the time. Future fires are drawn
 * at 30%, a paused job's marks have a dashed outline, and a Claude job carries a gold £.
 *
 * <p>The SVG is sized from its container with a {@code ResizeObserver} (never below {@link MIN_PLOT_WIDTH}). On a phone
 * the picture sits in a horizontal scroller (about 720px wide) with the label column sticky. Marks are decorative
 * ({@code aria-hidden}); the label buttons carry the information for a keyboard or screen-reader reader.
 *
 * <p>Extension points for the repeating section, watchdog windows and dependency arrows (S5b): the row geometry is the
 * one place a row's y comes from, and {@code renderOverlay} draws inside the SVG, above the marks, with the same
 * {@code xOf}.
 *
 * @param {object} props
 * @param {object} props.day the served day
 * @param {Array<object>} props.rows the rows from {@code buildRows}
 * @param {number} props.nowMs the clock, epoch ms, for the Now line
 * @param {(jobKey: string) => void} props.onSelect opens a job's panel
 * @param {(geometry: {xOf: (ms: number) => number, width: number, height: number, rowCenters: Object<string, number>}) =>
 *   import('react').ReactNode} [props.renderOverlay] extra SVG drawn above the marks (S5b)
 */
export default function DayPicture({ day, rows, nowMs, onSelect, renderOverlay = undefined }) {
  const isMobile = useIsMobile();
  const plotRef = useRef(null);
  const width = useElementWidth(plotRef, MIN_PLOT_WIDTH);
  const axis = dayAxis(day);
  if (!axis) return null;

  const height = TOP_PAD + rows.length * ROW_HEIGHT + BOTTOM_PAD;
  const xOf = (ms) => timeToX(ms, axis, width);
  const rowCenters = Object.fromEntries(rows.map((row, i) => [row.jobKey, TOP_PAD + i * ROW_HEIGHT + ROW_HEIGHT / 2]));
  const sunrise = instantMs(day.sunrise);
  const sunset = instantMs(day.sunset);
  const showNow = day.isToday === true && onAxis(nowMs, axis) && nowMs < axis.endMs;
  const plotBottom = height - BOTTOM_PAD;
  const grid = gridlines(axis);

  return (
    <div
      className={`overflow-x-auto ${isMobile ? 'rounded-lg border border-plex-border' : ''}`}
      data-testid="scheduler-day-scroller"
    >
      <div className={`flex ${isMobile ? 'min-w-[720px]' : ''}`} data-testid="scheduler-day-picture">
        <div
          className={`shrink-0 pt-9 ${isMobile ? 'sticky left-0 z-10 w-[148px] bg-plex-panel' : 'w-[200px]'}`}
          data-testid="scheduler-day-labels"
        >
          {rows.map((row) => <RowLabel key={row.jobKey} row={row} onSelect={onSelect} />)}
        </div>
        <div ref={plotRef} className="min-w-0 flex-1">
          <svg width={width} height={height} className="block" data-testid="scheduler-day-svg" focusable="false">
            <g aria-hidden="true">
              {nightBands(axis, day.sunrise, day.sunset).map((band) => (
                <rect
                  key={band.startMs}
                  x={xOf(band.startMs)}
                  y={TOP_PAD - 6}
                  width={xOf(band.endMs) - xOf(band.startMs)}
                  height={plotBottom - TOP_PAD + 6}
                  className="fill-black/30"
                  data-testid="scheduler-day-night"
                />
              ))}
              {grid.map((line, i) => (
                <g key={line.label}>
                  <line x1={xOf(line.ms)} x2={xOf(line.ms)} y1={TOP_PAD - 6} y2={plotBottom} className="stroke-plex-text/10" />
                  <text
                    x={xOf(line.ms)}
                    y={height - 6}
                    textAnchor={i === 0 ? 'start' : i === grid.length - 1 ? 'end' : 'middle'}
                    className="fill-plex-text-muted font-mono text-[10px]"
                    data-testid={`scheduler-day-grid-${line.label}`}
                  >
                    {line.label}
                  </text>
                </g>
              ))}
              {sunrise != null && onAxis(sunrise, axis) && (
                <g data-testid="scheduler-day-sunrise">
                  <line x1={xOf(sunrise)} x2={xOf(sunrise)} y1={TOP_PAD - 14} y2={plotBottom} strokeDasharray="2 3" className="stroke-skyeval-version/60" />
                  <text x={xOf(sunrise)} y={TOP_PAD - 18} textAnchor="middle" className="fill-skyeval-version font-mono text-[10px]">
                    {`Sunrise ${formatClock(day.sunrise)}`}
                  </text>
                </g>
              )}
              {sunset != null && onAxis(sunset, axis) && (
                <g data-testid="scheduler-day-sunset">
                  <line x1={xOf(sunset)} x2={xOf(sunset)} y1={TOP_PAD - 14} y2={plotBottom} strokeDasharray="2 3" className="stroke-plex-coral/60" />
                  <text x={xOf(sunset)} y={TOP_PAD - 18} textAnchor="middle" className="fill-plex-coral font-mono text-[10px]">
                    {`Sunset ${formatClock(day.sunset)}`}
                  </text>
                </g>
              )}
            </g>

            {rows.map((row) => {
              const cy = rowCenters[row.jobKey];
              const xs = row.fires.map((fire) => xOf(instantMs(fire.at)));
              return (
                <g key={row.jobKey} data-testid={`scheduler-day-row-${row.jobKey}`}>
                  {row.fires.map((fire, index) => (
                    <FireMark
                      key={`${fire.at}-${index}`}
                      row={row}
                      fire={fire}
                      index={index}
                      x={xs[index]}
                      cy={cy}
                      widthPx={fireWidthPx(fire, axis, width)}
                      gapPx={index < xs.length - 1 ? xs[index + 1] - xs[index] : Infinity}
                      plotWidth={width}
                      onSelect={onSelect}
                    />
                  ))}
                </g>
              );
            })}

            {renderOverlay?.({ xOf, width, height, rowCenters })}

            {showNow && (
              <g aria-hidden="true" data-testid="scheduler-day-now">
                <line x1={xOf(nowMs)} x2={xOf(nowMs)} y1={TOP_PAD - 6} y2={plotBottom} strokeWidth="1.5" className="stroke-tide" />
                <text x={xOf(nowMs) + 4} y={plotBottom - 2} className="fill-tide font-mono text-[10px]">
                  {`Now ${formatClock(new Date(nowMs).toISOString())}`}
                </text>
              </g>
            )}
          </svg>
        </div>
      </div>
    </div>
  );
}

DayPicture.propTypes = {
  day: PropTypes.object.isRequired,
  rows: PropTypes.arrayOf(PropTypes.object).isRequired,
  nowMs: PropTypes.number.isRequired,
  onSelect: PropTypes.func.isRequired,
  renderOverlay: PropTypes.func,
};
