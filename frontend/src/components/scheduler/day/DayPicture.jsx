import { useId, useRef } from 'react';
import PropTypes from 'prop-types';
import useElementWidth from '../../../hooks/useElementWidth.js';
import { useIsMobile } from '../../../hooks/useIsMobile.js';
import { formatClock } from '../../../utils/schedulerView.js';
import {
  ZOOM_DAY,
  fireStartMs,
  fireWidthPx,
  instantMs,
  markShape,
  nightBands,
  onAxis,
  pictureAxis,
  rowAriaLabel,
  rowHeightFor,
  timeToX,
} from '../../../utils/schedulerDay.js';
import {
  fireTip,
  problemTip,
  relatedKeys,
  repeatingAriaLabel,
  repeatingLayout,
  repeatingRowTip,
  rowTip,
  watchdogTip,
  watchdogWindows,
  wouldBeTip,
} from '../../../utils/schedulerDayOverlays.js';
import DayArrows from './DayArrows.jsx';
import { FireMark, RepeatingMarks, WatchdogBand, WatchdogDeadline, WouldBeMark } from './DayMarks.jsx';
import DayTooltip, { DAY_TOOLTIP_ID, useDayTooltip } from './DayTooltip.jsx';

/** The smallest the plot is drawn, px, whatever the container says. */
export const MIN_PLOT_WIDTH = 360;

/** Geometry, px: the space above the first row (the sun labels) and below the last (the axis labels). */
export const TOP_PAD = 36;
export const BOTTOM_PAD = 24;

/** The room between the scheduled rows and the repeating ones, for the "REPEATING THROUGH THE DAY" divider, px. */
export const DIVIDER_HEIGHT = 24;

/** Where a row label's tooltip is anchored in the plot, px from its left edge. */
const LABEL_TIP_X = 8;

/**
 * A row's name, the label that opens the job's panel (a button for the keyboard, Enter or Space). Hover and keyboard
 * focus show the row's tooltip (the focused label names it with {@code aria-describedby}); a selected row is tinted and
 * an unrelated one dims while a job is selected.
 */
function RowLabel({ row, label, heightClass, onSelect, selected, dimmed, described, tip }) {
  const onKeyDown = (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect(row.jobKey, event.currentTarget);
    }
  };
  return (
    <div
      role="button"
      tabIndex={0}
      aria-label={label}
      aria-describedby={described ? DAY_TOOLTIP_ID : undefined}
      onClick={(event) => onSelect(row.jobKey, event.currentTarget)}
      onKeyDown={onKeyDown}
      {...tip}
      className={`flex ${heightClass} cursor-pointer items-center gap-1 truncate pr-2 text-[13px] text-plex-text hover:text-plex-gold-light focus-visible:outline-2 focus-visible:outline-plex-gold ${
        selected ? 'bg-skyeval-version/10' : ''
      } ${dimmed ? 'opacity-25' : ''}`}
      data-testid={`scheduler-row-${row.jobKey}`}
      data-dimmed={dimmed ? 'true' : 'false'}
      data-selected={selected ? 'true' : 'false'}
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

RowLabel.propTypes = {
  row: PropTypes.object.isRequired,
  label: PropTypes.string.isRequired,
  heightClass: PropTypes.string.isRequired,
  onSelect: PropTypes.func.isRequired,
  selected: PropTypes.bool.isRequired,
  dimmed: PropTypes.bool.isRequired,
  described: PropTypes.bool.isRequired,
  tip: PropTypes.object.isRequired,
};

/**
 * The Day's jobs picture (scheduler-redesign-plan.md D12): a label column of one button per job beside an SVG of the
 * UK civil day (00:00 to 24:00, 23 or 25 hours on a clock-change day, with 3-hourly gridlines) or of the 02:00 to
 * 05:00 close-up (every 30 minutes), the night shaded from the served sunrise and sunset, dashed Sunrise and Sunset
 * lines, and a Now line on today.
 *
 * <p>Scheduled jobs come first. Each fire is a bar (or a diamond below 6px, or a tick when it would touch a neighbour)
 * in the job's colour, then a status dot and the time; future fires have a 30% fill and a full outline, a missed fire
 * is an outline only, a running fire on today is a bar growing to Now, and a Claude job carries a gold £. A paused
 * job's would-be fires are dashed outline diamonds with their time ({@code row.wouldBe}); with none it says "Paused".
 * A forecast cycle's watchdog window is a faint band with a red deadline line.
 *
 * <p>Under a "REPEATING THROUGH THE DAY" divider each repeating job has a row: ticks at its served slots (past ones in
 * the repeating ink, future ones faint) or, when there are none to list or they would sit closer than 3px, a band with
 * "every 5 min"; failed and partial runs are coral and amber ticks over it. Dependency arrows run between the rows the
 * catalogue links; a selected job's turn gold and the rows unrelated to it dim to 25%.
 *
 * <p>Hovering a mark, or focusing or hovering a row label, shows a tooltip (an HTML layer, never inside the SVG). The
 * SVG is sized from its container with a {@code ResizeObserver} (never below {@link MIN_PLOT_WIDTH}). Rows are 24px, or
 * 44px on a phone, where the picture sits in a horizontal scroller (about 720px wide) with the label column sticky.
 * The SVG is {@code aria-hidden}; the label buttons carry the information. In the close-up, fires outside the window
 * are not drawn and the night shading, lines and arrows are clipped to it.
 *
 * @param {object} props
 * @param {object} props.day the served day
 * @param {Array<object>} props.rows the scheduled rows from {@code buildRows}
 * @param {Array<object>} [props.repeatingRows] the repeating rows from {@code buildRepeatingRows}
 * @param {Array<{kind: string, from: string, to: string}>} [props.links] the catalogue's declared links between rows
 * @param {boolean} [props.showArrows] whether the dependency arrows are drawn
 * @param {string|null} [props.selectedKey] the job whose panel is open
 * @param {string} [props.zoom] {@code day} (the whole day) or {@code closeup}
 * @param {number} props.nowMs the clock, epoch ms, for the Now line
 * @param {(jobKey: string, opener: HTMLElement|null) => void} props.onSelect opens a job's panel
 * @param {(geometry: {xOf: (ms: number) => number, width: number, height: number, rowHeight: number,
 *   rowCenters: Object<string, number>}) => import('react').ReactNode} [props.renderOverlay] extra SVG above the marks
 */
export default function DayPicture({
  day, rows, repeatingRows = [], links = [], showArrows = true, selectedKey = null, zoom = ZOOM_DAY, nowMs, onSelect,
  renderOverlay = undefined,
}) {
  const isMobile = useIsMobile();
  const plotRef = useRef(null);
  const width = useElementWidth(plotRef, MIN_PLOT_WIDTH);
  const scrollerRef = useRef(null);
  const tooltip = useDayTooltip(selectedKey, zoom, day);
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const picture = pictureAxis(day, zoom);
  if (!picture) return null;
  const { axis, grid } = picture;

  // In the close-up a scheduled job with nothing in the window has no row; the repeating jobs always do.
  const inWindow = (fire) => onAxis(fireStartMs(fire), axis);
  const drawnRows = zoom === ZOOM_DAY ? rows : rows.filter((row) => row.fires.some(inWindow) || row.wouldBe.some(inWindow));

  const rowHeight = rowHeightFor(isMobile);
  const heightClass = isMobile ? 'h-11' : 'h-6';
  const hasRepeating = repeatingRows.length > 0;
  const dividerHeight = hasRepeating ? DIVIDER_HEIGHT : 0;
  const repeatingTop = TOP_PAD + drawnRows.length * rowHeight + dividerHeight;
  const height = repeatingTop + repeatingRows.length * rowHeight + BOTTOM_PAD;
  const xOf = (ms) => timeToX(ms, axis, width);
  const rowCenters = {
    ...Object.fromEntries(drawnRows.map((row, i) => [row.jobKey, TOP_PAD + i * rowHeight + rowHeight / 2])),
    ...Object.fromEntries(repeatingRows.map((row, i) => [row.jobKey, repeatingTop + i * rowHeight + rowHeight / 2])),
  };
  const sunrise = instantMs(day.sunrise);
  const sunset = instantMs(day.sunset);
  const showNow = day.isToday === true && onAxis(nowMs, axis) && nowMs < axis.endMs;
  const plotBottom = height - BOTTOM_PAD;
  const clock = { nowMs, isToday: showNow };

  const allKeys = [...drawnRows, ...repeatingRows].map((row) => row.jobKey);
  const related = relatedKeys(selectedKey, links, allKeys);
  const dimmed = (key) => related != null && !related.has(key);
  // Every window of the day gives the tooltips their words (so a fire says the same on the whole day and the close-up);
  // only the ones that reach the axis are drawn.
  const allWindows = watchdogWindows(day, rows);
  const windows = allWindows.filter((w) => w.deadlineMs >= axis.startMs && w.startMs <= axis.endMs);
  const windowsOf = (key) => windows.filter((w) => w.jobKey === key);
  const allWindowsOf = (key) => allWindows.filter((w) => w.jobKey === key);

  // Every tooltip this render can show, by id: the open one is looked up here, so its content is always built from the
  // data being drawn now, never from a copy taken when it opened.
  const tips = new Map();
  const tipFor = (id, x, y, content) => {
    tips.set(id, { x, y, content });
    return { onMouseEnter: () => tooltip.show(id), onMouseLeave: () => tooltip.hide(id) };
  };
  // A row label's tooltip sits at the left edge of the part of the plot that is visible: the sticky label column covers
  // the first scrolled-off pixels, so on a phone that is the scroller's scrollLeft, not 0.
  const labelAnchor = () => (scrollerRef.current?.scrollLeft ?? 0) + LABEL_TIP_X;
  const labelTip = (id, y, content) => {
    tips.set(id, { x: LABEL_TIP_X, y, content });
    return {
      onFocus: () => tooltip.show(id, labelAnchor()),
      onBlur: () => tooltip.hide(id),
      onMouseEnter: () => tooltip.show(id, labelAnchor()),
      onMouseLeave: () => tooltip.hide(id),
    };
  };
  // A click on a mark opens the panel: the tooltip over it has done its job.
  const selectFromMark = (jobKey, opener) => {
    tooltip.hide();
    onSelect(jobKey, opener);
  };
  const rowId = (row) => `row:${row.jobKey}`;
  const clipId = `${uid}-clip`;

  return (
    <div
      ref={scrollerRef}
      className={`overflow-x-auto ${isMobile ? 'rounded-lg border border-plex-border' : ''}`}
      data-testid="scheduler-day-scroller"
    >
      <div className={`flex ${isMobile ? 'min-w-[720px]' : ''}`} data-testid="scheduler-day-picture" data-zoom={zoom}>
        <div
          className={`shrink-0 pt-9 ${isMobile ? 'sticky left-0 z-10 w-[148px] bg-plex-panel' : 'w-[200px]'}`}
          data-testid="scheduler-day-labels"
        >
          {drawnRows.map((row) => (
            <RowLabel
              key={row.jobKey}
              row={row}
              label={rowAriaLabel(row)}
              heightClass={heightClass}
              onSelect={onSelect}
              selected={selectedKey === row.jobKey}
              dimmed={dimmed(row.jobKey)}
              described={tooltip.id === rowId(row)}
              tip={labelTip(rowId(row), rowCenters[row.jobKey], rowTip(row, allWindowsOf(row.jobKey)))}
            />
          ))}
          {hasRepeating && (
            <div
              className="flex h-6 items-end pb-1 font-mono text-[10px] tracking-wider text-plex-text-muted"
              data-testid="scheduler-day-repeating-divider"
            >
              REPEATING THROUGH THE DAY
            </div>
          )}
          {repeatingRows.map((row) => (
            <RowLabel
              key={row.jobKey}
              row={row}
              label={repeatingAriaLabel(row)}
              heightClass={heightClass}
              onSelect={onSelect}
              selected={selectedKey === row.jobKey}
              dimmed={dimmed(row.jobKey)}
              described={tooltip.id === rowId(row)}
              tip={labelTip(rowId(row), rowCenters[row.jobKey], repeatingRowTip(row, day.isToday === true))}
            />
          ))}
        </div>
        <div ref={plotRef} className="relative min-w-0 flex-1">
          <svg width={width} height={height} className="block" aria-hidden="true" data-testid="scheduler-day-svg" focusable="false">
            <defs>
              <clipPath id={clipId}>
                <rect x="0" y={TOP_PAD - 14} width={width} height={plotBottom - TOP_PAD + 14} />
              </clipPath>
            </defs>
            <g>
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
              {hasRepeating && (
                <line
                  x1={0}
                  x2={width}
                  y1={repeatingTop - 2}
                  y2={repeatingTop - 2}
                  className="stroke-plex-border"
                  data-testid="scheduler-day-repeating-rule"
                />
              )}
            </g>

            <g clipPath={`url(#${clipId})`} data-testid="scheduler-day-content">
              {drawnRows.map((row) => {
                const cy = rowCenters[row.jobKey];
                const shown = row.fires.map((fire, index) => ({ fire, index })).filter(({ fire }) => inWindow(fire));
                const xs = shown.map(({ fire }) => xOf(fireStartMs(fire)));
                const widths = shown.map(({ fire }) => fireWidthPx(fire, axis, width, clock));
                const wouldBe = row.wouldBe.map((fire, index) => ({ fire, index })).filter(({ fire }) => inWindow(fire));
                const wouldBeXs = wouldBe.map(({ fire }) => xOf(fireStartMs(fire)));
                const rowWindows = windowsOf(row.jobKey);
                // A window belongs to one fire (the one inside or just before it), not to every fire on the row.
                const windowFor = (fire) => allWindowsOf(row.jobKey).find((w) => w.ownerAtMs === instantMs(fire.at)) ?? null;
                const dim = dimmed(row.jobKey);
                return (
                  <g
                    key={row.jobKey}
                    className={dim ? 'opacity-25' : undefined}
                    data-testid={`scheduler-day-row-${row.jobKey}`}
                    data-dimmed={dim ? 'true' : 'false'}
                  >
                    {selectedKey === row.jobKey && (
                      <rect x={0} y={cy - rowHeight / 2} width={width} height={rowHeight} className="fill-skyeval-version/10" data-testid={`scheduler-selected-${row.jobKey}`} />
                    )}
                    {rowWindows.map((w, i) => (
                      <WatchdogBand
                        key={`band-${w.startMs}`}
                        span={w}
                        xOf={xOf}
                        width={width}
                        cy={cy}
                        tip={tipFor(`watchdog:${row.jobKey}:${i}`, xOf(w.deadlineMs), cy, watchdogTip(row, w))}
                      />
                    ))}
                    {shown.length === 0 && wouldBe.length === 0 && row.paused && (
                      <text x={8} y={cy + 4} className="fill-plex-text-muted text-[11px]" data-testid={`scheduler-paused-${row.jobKey}`}>
                        Paused
                      </text>
                    )}
                    {wouldBe.map(({ fire, index }, i) => (
                      <WouldBeMark
                        key={`${fire.at}-${index}`}
                        row={row}
                        fire={fire}
                        index={index}
                        x={wouldBeXs[i]}
                        cy={cy}
                        plotWidth={width}
                        gapNextPx={i < wouldBeXs.length - 1 ? wouldBeXs[i + 1] - wouldBeXs[i] : Infinity}
                        onSelect={selectFromMark}
                        tip={tipFor(`wouldbe:${row.jobKey}:${index}`, wouldBeXs[i], cy, wouldBeTip(row, fire))}
                      />
                    ))}
                    {shown.map(({ fire, index }, i) => {
                      const gapPrevPx = i > 0 ? xs[i] - xs[i - 1] : Infinity;
                      const gapNextPx = i < xs.length - 1 ? xs[i + 1] - xs[i] : Infinity;
                      const shape = markShape({
                        widthPx: widths[i], gapPrevPx, gapNextPx, prevWidthPx: i > 0 ? widths[i - 1] : 0,
                      });
                      return (
                        <FireMark
                          key={`${fire.at}-${index}`}
                          row={row}
                          fire={fire}
                          index={index}
                          x={xs[i]}
                          cy={cy}
                          widthPx={widths[i]}
                          shape={shape}
                          gapPrevPx={gapPrevPx}
                          gapNextPx={gapNextPx}
                          plotWidth={width}
                          onSelect={selectFromMark}
                          tip={tipFor(`fire:${row.jobKey}:${index}`, xs[i], cy, fireTip(row, fire, windowFor(fire)))}
                        />
                      );
                    })}
                    {rowWindows.map((w, i) => (
                      <WatchdogDeadline
                        key={`deadline-${w.startMs}`}
                        span={w}
                        xOf={xOf}
                        width={width}
                        cy={cy}
                        tip={tipFor(`watchdog-deadline:${row.jobKey}:${i}`, xOf(w.deadlineMs), cy, watchdogTip(row, w))}
                      />
                    ))}
                  </g>
                );
              })}

              {repeatingRows.map((row) => {
                const cy = rowCenters[row.jobKey];
                const dim = dimmed(row.jobKey);
                return (
                  <g key={row.jobKey} className={dim ? 'opacity-25' : undefined} data-dimmed={dim ? 'true' : 'false'}>
                    {selectedKey === row.jobKey && (
                      <rect x={0} y={cy - rowHeight / 2} width={width} height={rowHeight} className="fill-skyeval-version/10" data-testid={`scheduler-selected-${row.jobKey}`} />
                    )}
                    <RepeatingMarks
                      row={row}
                      layout={repeatingLayout(row, axis, width)}
                      xOf={xOf}
                      width={width}
                      cy={cy}
                      nowMs={nowMs}
                      onSelect={selectFromMark}
                      problemTips={row.problems.map((problem, i) => tipFor(`problem:${row.jobKey}:${i}`, xOf(problem.atMs), cy, problemTip(row, problem)))}
                    />
                  </g>
                );
              })}

              {showArrows && (
                <DayArrows
                  links={links}
                  rows={drawnRows}
                  repeatingRows={repeatingRows}
                  rowCenters={rowCenters}
                  xOf={xOf}
                  axis={axis}
                  width={width}
                  clock={clock}
                  selectedKey={selectedKey}
                  markerId={uid}
                />
              )}

              {renderOverlay?.({ xOf, width, height, rowHeight, rowCenters })}
            </g>

            {showNow && (
              <g data-testid="scheduler-day-now">
                <line x1={xOf(nowMs)} x2={xOf(nowMs)} y1={TOP_PAD - 6} y2={plotBottom} strokeWidth="1.5" className="stroke-tide" />
                <text x={xOf(nowMs) + 4} y={plotBottom - 2} className="fill-tide font-mono text-[10px]">
                  {`Now ${formatClock(new Date(nowMs).toISOString())}`}
                </text>
              </g>
            )}
          </svg>
          {/* Read last: by here every mark and label above has registered its tooltip for this render. */}
          {tooltip.id != null && tips.has(tooltip.id) && (
            <DayTooltip
              tip={{
                id: tooltip.id,
                x: tooltip.anchorX ?? tips.get(tooltip.id).x,
                y: tips.get(tooltip.id).y,
                content: tips.get(tooltip.id).content,
              }}
              width={width}
              height={height}
              compact={isMobile}
            />
          )}
        </div>
      </div>
    </div>
  );
}

DayPicture.propTypes = {
  day: PropTypes.object.isRequired,
  rows: PropTypes.arrayOf(PropTypes.object).isRequired,
  repeatingRows: PropTypes.arrayOf(PropTypes.object),
  links: PropTypes.arrayOf(PropTypes.shape({ kind: PropTypes.string, from: PropTypes.string, to: PropTypes.string })),
  showArrows: PropTypes.bool,
  selectedKey: PropTypes.string,
  zoom: PropTypes.oneOf(['day', 'closeup']),
  nowMs: PropTypes.number.isRequired,
  onSelect: PropTypes.func.isRequired,
  renderOverlay: PropTypes.func,
};
