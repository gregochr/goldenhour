import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { useIsMobile } from '../../../hooks/useIsMobile.js';
import { formatClockUk } from '../../../utils/runsView.js';
import { runStatusView } from '../../../utils/runStatus.js';
import {
  LANES, rowCostText, rowDurationText, rowStartText, stripBlock, stripFraction, stripScale, stripTicks, typeStyle,
} from '../../../utils/runsDay.js';

// The strip's drawing space. The viewBox is as wide as the card is in pixels (measured, below), so text and the 5-unit
// minimum block are real pixels; VIEW_W_FALLBACK is used before the first measure and where nothing measures (jsdom).
const VIEW_W_FALLBACK = 1000;
const PHONE_MIN_W = 720;
const GUTTER = 128;
const RIGHT_PAD = 8;
const TOP = 24;
const LANE_H = 30;
const BLOCK_H = 18;
const MIN_BLOCK_W = 5;
const AXIS_H = 22;
const VIEW_H = TOP + LANES.length * LANE_H + AXIS_H;
const TOOLTIP_W = 236;

/**
 * A block's classes, exactly one fill and one stroke (Tailwind orders same-property utilities by its stylesheet, never
 * by className order): dashed and unfilled when scheduled, an amber ring when partial, a coral one when failed, a bone
 * one when it is the open row.
 */
function blockClasses(row, selected) {
  const style = typeStyle(row.type);
  if (row.status === 'SCHEDULED') return `fill-transparent ${selected ? 'stroke-plex-gold' : style.stroke}`;
  let stroke = 'stroke-transparent';
  if (selected) stroke = 'stroke-plex-gold';
  else if (row.status === 'FAILED') stroke = 'stroke-plex-coral';
  else if (row.status === 'PARTIAL') stroke = 'stroke-verdict-marginal';
  return `${style.fill} ${stroke}`;
}

/** One block's words for its tooltip and accessible name. */
function blockLines(row) {
  const cost = rowCostText(row);
  const status = runStatusView(row.status);
  return [
    status.label !== 'Done' ? `${row.name} (${status.label})` : row.name,
    `${rowStartText(row)} · ${rowDurationText(row)}${cost !== '—' ? ` · ${cost}` : ''}`,
    row.note ?? (row.status === 'SCHEDULED' ? 'Scheduled' : ''),
  ].filter(Boolean);
}

/**
 * The Day view's 24-hour strip (the handoff's "Day strip"): a hand-rolled SVG with a {@code viewBox}, five lanes
 * (Cycles, Ask ready answers, Daily briefing, Sky eval, Other jobs), the served day from midnight to midnight (a 23-
 * or 25-hour day stretches the axis, B10), night shaded either side of the served sunrise and sunset, and on today a
 * "Now" line with the part still to come lightly tinted. Each run is a block at its start, as wide as its duration
 * (never under 5 units); a scheduled one is a dashed outline, a partial one has an amber ring and a failed one a coral
 * ring. A block shows a tooltip on hover and focus, and pressing it opens that row in the list below. Tooltips live
 * inside the SVG, so there is no positioned overlay (no inline style).
 *
 * <p>On a phone the chart keeps a readable width and scrolls horizontally inside its card.
 *
 * @param {object} props
 * @param {object} props.day the served {@code RunDayResponse}
 * @param {string|null} [props.selectedId] the open row's id
 * @param {(id: string) => void} props.onSelect called with a row id when its block is pressed
 */
export default function DayStrip({ day, selectedId = null, onSelect }) {
  const isMobile = useIsMobile();
  const [hover, setHover] = useState(null);
  const scrollRef = useRef(null);
  const [measured, setMeasured] = useState(0);
  // The card's pixel width, so the viewBox is 1:1 with the screen and fontSize 10-11 stays 10-11px.
  useEffect(() => {
    const node = scrollRef.current;
    if (!node || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => setMeasured(Math.floor(entry.contentRect.width)));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  let viewW = measured > 0 ? measured : VIEW_W_FALLBACK;
  if (isMobile) viewW = Math.max(viewW, PHONE_MIN_W);
  const PLOT_W = viewW - GUTTER - RIGHT_PAD;
  const scale = stripScale(day);
  const nowMs = day.now ? Date.parse(day.now) : scale.end;
  const x = (fraction) => GUTTER + fraction * PLOT_W;
  const sunrise = day.sunriseAt ? stripFraction(Date.parse(day.sunriseAt), scale) : null;
  const sunset = day.sunsetAt ? stripFraction(Date.parse(day.sunsetAt), scale) : null;
  const nowFraction = day.isToday && day.now ? stripFraction(nowMs, scale) : null;
  const blocks = day.runs.map((row) => stripBlock(row, scale, nowMs)).filter(Boolean);
  const hovered = blocks.find((block) => block.id === hover) ?? null;
  const title = day.isToday ? 'Today so far, and what’s still to come' : 'The day at a glance';

  let tooltip = null;
  if (hovered) {
    const lines = blockLines(hovered.row);
    const left = hovered.x0 > 0.6;
    const tx = left ? x(hovered.x0) - TOOLTIP_W - 6 : x(hovered.x1) + 6;
    const ty = Math.min(TOP + hovered.lane * LANE_H, VIEW_H - AXIS_H - lines.length * 15 - 12);
    tooltip = (
      <g transform={`translate(${tx} ${ty})`} pointerEvents="none" data-testid="runs-strip-tooltip">
        <rect width={TOOLTIP_W} height={lines.length * 15 + 12} rx="6" className="fill-plex-bg stroke-plex-border-light" />
        {lines.map((line, i) => (
          <text
            key={line}
            x="10"
            y={20 + i * 15}
            fontSize="11"
            className={i === 0 ? 'fill-plex-text font-semibold' : 'fill-plex-text-secondary font-mono'}
          >
            {line.length > 34 ? `${line.slice(0, 33)}…` : line}
          </text>
        ))}
      </g>
    );
  }

  return (
    <section className="rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3" data-testid="runs-strip">
      <div className="mb-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h3 className="text-[13px] font-semibold text-plex-text" data-testid="runs-strip-title">{title}</h3>
        <span className="font-mono text-[11px] text-plex-text-secondary" data-testid="runs-strip-meta">
          sunrise {day.sunrise} {'·'} sunset {day.sunset}
        </span>
      </div>
      <div
        ref={scrollRef}
        className={isMobile ? 'overflow-x-auto focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold' : ''}
        {...(isMobile ? { tabIndex: 0, role: 'region', 'aria-label': 'Runs timeline, scrolls sideways' } : {})}
        data-testid="runs-strip-scroll"
      >
        <svg
          viewBox={`0 0 ${viewW} ${VIEW_H}`}
          className={`h-auto ${isMobile ? 'min-w-[720px]' : 'w-full'}`}
          width={isMobile ? viewW : undefined}
          role="group"
          aria-label={`${title}. ${day.runs.length} runs.`}
          data-testid="runs-strip-svg"
        >
          {/* Lanes and their labels. */}
          {LANES.map((lane, i) => (
            <g key={lane.key}>
              <rect
                x={GUTTER}
                y={TOP + i * LANE_H}
                width={PLOT_W}
                height={LANE_H}
                className={i % 2 === 0 ? 'fill-white/2' : 'fill-transparent'}
              />
              <line x1={GUTTER} x2={GUTTER + PLOT_W} y1={TOP + (i + 1) * LANE_H} y2={TOP + (i + 1) * LANE_H} className="stroke-plex-border" strokeWidth="1" />
              <text x="0" y={TOP + i * LANE_H + LANE_H / 2 + 3.5} fontSize="10" className="fill-plex-text-muted font-mono">
                {lane.label}
              </text>
            </g>
          ))}
          {/* Night: before sunrise and after sunset. */}
          {sunrise != null && (
            <rect x={GUTTER} y={TOP} width={sunrise * PLOT_W} height={LANES.length * LANE_H} className="fill-black/28" data-testid="runs-strip-night-am" />
          )}
          {sunset != null && (
            <rect x={x(sunset)} y={TOP} width={(1 - sunset) * PLOT_W} height={LANES.length * LANE_H} className="fill-black/28" data-testid="runs-strip-night-pm" />
          )}
          {/* What is still to come today. */}
          {nowFraction != null && (
            <rect x={x(nowFraction)} y={TOP} width={(1 - nowFraction) * PLOT_W} height={LANES.length * LANE_H} className="fill-white/3" />
          )}
          {/* The x axis. */}
          {stripTicks(scale).filter((tick, i, all) => PLOT_W >= 560 || i % 2 === 0 || i === all.length - 1).map((tick) => (
            <g key={tick.ms}>
              <line x1={x(tick.fraction)} x2={x(tick.fraction)} y1={TOP} y2={TOP + LANES.length * LANE_H} className="stroke-plex-border" strokeWidth="1" />
              <text
                x={x(tick.fraction)}
                y={VIEW_H - 6}
                fontSize="10"
                textAnchor={tick.fraction === 1 ? 'end' : 'middle'}
                className="fill-plex-text-muted font-mono"
              >
                {tick.label}
              </text>
            </g>
          ))}
          {/* Sunrise, sunset and now. */}
          {sunrise != null && (
            <g>
              <line x1={x(sunrise)} x2={x(sunrise)} y1={TOP} y2={TOP + LANES.length * LANE_H} strokeDasharray="3 3" className="stroke-plex-gold" />
              <text x={x(sunrise)} y={TOP - 8} fontSize="10" textAnchor="middle" className="fill-plex-gold font-mono">
                Sunrise {day.sunrise}
              </text>
            </g>
          )}
          {sunset != null && (
            <g>
              <line x1={x(sunset)} x2={x(sunset)} y1={TOP} y2={TOP + LANES.length * LANE_H} strokeDasharray="3 3" className="stroke-plex-coral" />
              <text x={x(sunset)} y={TOP - 8} fontSize="10" textAnchor="middle" className="fill-plex-coral font-mono">
                Sunset {day.sunset}
              </text>
            </g>
          )}
          {nowFraction != null && (
            <g data-testid="runs-strip-now">
              <line x1={x(nowFraction)} x2={x(nowFraction)} y1={TOP} y2={TOP + LANES.length * LANE_H} strokeWidth="1.5" className="stroke-tide" />
              <text x={x(nowFraction) + 4} y={TOP + 10} fontSize="10" className="fill-tide font-mono">
                Now {formatClockUk(day.now)}
              </text>
            </g>
          )}
          {/* The runs. */}
          {blocks.map((block) => {
            const bx = x(block.x0);
            const width = Math.max(MIN_BLOCK_W, (block.x1 - block.x0) * PLOT_W);
            const by = TOP + block.lane * LANE_H + (LANE_H - BLOCK_H) / 2;
            const scheduled = block.row.status === 'SCHEDULED';
            const selected = block.id === selectedId;
            return (
              <g
                key={block.id}
                role="button"
                tabIndex={0}
                aria-label={`${blockLines(block.row).join(', ')}. Show in the list.`}
                className="cursor-pointer outline-none"
                onMouseEnter={() => setHover(block.id)}
                onMouseLeave={() => setHover(null)}
                onFocus={() => setHover(block.id)}
                onBlur={() => setHover(null)}
                onClick={() => onSelect(block.id)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onSelect(block.id);
                  }
                }}
                data-testid={`runs-strip-block-${block.id}`}
                data-status={block.row.status}
              >
                <rect
                  x={bx}
                  y={by}
                  width={width}
                  height={BLOCK_H}
                  rx="2"
                  strokeWidth={scheduled || block.row.status === 'DONE' || block.row.status === 'RUNNING' ? 1 : 2}
                  strokeDasharray={scheduled ? '3 2' : undefined}
                  className={blockClasses(block.row, selected)}
                />
                {hover === block.id && (
                  <rect
                    x={bx - 2}
                    y={by - 2}
                    width={width + 4}
                    height={BLOCK_H + 4}
                    rx="3"
                    strokeWidth="1"
                    pointerEvents="none"
                    className="fill-none stroke-plex-text"
                  />
                )}
              </g>
            );
          })}
          {tooltip}
        </svg>
      </div>
    </section>
  );
}

DayStrip.propTypes = {
  day: PropTypes.object.isRequired,
  selectedId: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
};
