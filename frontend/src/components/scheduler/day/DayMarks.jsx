import PropTypes from 'prop-types';
import { formatClock } from '../../../utils/schedulerView.js';
import { DIAMOND_HALF_PX, fireStatusStyle, isFuture, kindStyle } from '../../../utils/schedulerDay.js';

/** Where the status dot sits beyond a mark, its size, and the room a time label needs, px. */
const DOT_OFFSET = 6;
const DOT_RADIUS = 3.5;
const LABEL_ROOM = 52;
const LABEL_WIDTH = 34;

/** A hollow (paused or missed) mark's dashes. */
const HOLLOW_DASH = '2 2';

/** The share of a future fire's fill that shows; its outline stays at full strength. */
const FUTURE_FILL_OPACITY = 0.3;

/** The share of a future repeating tick that shows (the design's 35%). */
const FUTURE_REPEATING_OPACITY = 0.35;

/** The band's strength over the repeating ink (which is itself half strength): about the design's 45%. */
const BAND_OPACITY = 0.9;

/** A watchdog window's band and deadline half-height, px. */
const WATCHDOG_HALF_HEIGHT = 9;

/**
 * One fire's mark: bar, diamond or tick; a status dot kept inside the plot; and the time beside it when there is room
 * (flipped to the left of the mark when the right would run off the plot). Decorative; hovering it shows its tooltip.
 */
export function FireMark({ row, fire, index, x, cy, widthPx, shape, gapPrevPx, gapNextPx, plotWidth, onSelect, tip = undefined }) {
  const kind = kindStyle(row.scheduleType);
  const status = fireStatusStyle(fire.status);
  const future = isFuture(fire);
  // A paused job's marks, and a fire that never ran, are outlines: they must not read as a run.
  const hollow = row.paused || fire.status === 'MISSED';
  const common = {
    className: hollow ? `fill-none ${kind.stroke}` : `${kind.fill} ${kind.stroke}`,
    fillOpacity: future && !hollow ? FUTURE_FILL_OPACITY : undefined,
    strokeDasharray: hollow ? HOLLOW_DASH : undefined,
    'data-testid': `scheduler-mark-${row.jobKey}-${index}`,
  };
  let mark;
  let right;
  let left;
  if (shape === 'tick') {
    mark = <rect {...common} x={x - 1} y={cy - 6} width="2" height="12" />;
    right = x + 1;
    left = x - 1;
  } else if (shape === 'diamond') {
    const h = DIAMOND_HALF_PX;
    mark = <path {...common} d={`M${x} ${cy - h}L${x + h} ${cy}L${x} ${cy + h}L${x - h} ${cy}Z`} />;
    right = x + h;
    left = x - h;
  } else {
    const w = Math.max(1, Math.min(widthPx, plotWidth - x));
    mark = <rect {...common} x={x} y={cy - 7} width={w} height="14" rx="3" />;
    right = x + w;
    left = x;
  }
  const dotX = Math.min(right + DOT_OFFSET, plotWidth - DOT_RADIUS - 1);
  const flip = dotX + 7 + LABEL_WIDTH > plotWidth;
  const showTime = flip ? gapPrevPx > LABEL_ROOM : gapNextPx > LABEL_ROOM;
  return (
    <g
      aria-hidden="true"
      className="cursor-pointer"
      data-testid={`scheduler-fire-${row.jobKey}-${index}`}
      data-status={fire.status}
      data-shape={shape}
      data-future={future ? 'true' : 'false'}
      onClick={() => onSelect(row.jobKey, null)}
      {...tip}
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
        <text
          x={flip ? left - DOT_OFFSET : dotX + 7}
          y={cy + 3.5}
          textAnchor={flip ? 'end' : 'start'}
          className="fill-plex-text-muted font-mono text-[10px]"
          data-testid={`scheduler-time-${row.jobKey}-${index}`}
        >
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
  shape: PropTypes.oneOf(['tick', 'diamond', 'bar']).isRequired,
  gapPrevPx: PropTypes.number.isRequired,
  gapNextPx: PropTypes.number.isRequired,
  plotWidth: PropTypes.number.isRequired,
  onSelect: PropTypes.func.isRequired,
  tip: PropTypes.object,
};


/**
 * A paused job's would-be fire: a dashed outline diamond with its time beside it (the design's Dormant Account Sweep).
 * No fill and no status dot, so it can never read as a run. Decorative; hovering it shows its tooltip.
 */
export function WouldBeMark({ row, fire, index, x, cy, plotWidth, gapNextPx, onSelect, tip = undefined }) {
  const kind = kindStyle(row.scheduleType);
  const h = DIAMOND_HALF_PX;
  const flip = x + h + DOT_OFFSET + LABEL_WIDTH > plotWidth;
  const showTime = flip ? true : gapNextPx > LABEL_ROOM;
  return (
    <g
      aria-hidden="true"
      className="cursor-pointer"
      data-testid={`scheduler-wouldbe-${row.jobKey}-${index}`}
      data-status="PAUSED"
      onClick={() => onSelect(row.jobKey, null)}
      {...tip}
    >
      <path
        d={`M${x} ${cy - h}L${x + h} ${cy}L${x} ${cy + h}L${x - h} ${cy}Z`}
        className={`fill-none ${kind.stroke}`}
        strokeDasharray={HOLLOW_DASH}
        data-testid={`scheduler-wouldbe-mark-${row.jobKey}-${index}`}
      />
      {showTime && (
        <text
          x={flip ? x - h - DOT_OFFSET : x + h + DOT_OFFSET}
          y={cy + 3.5}
          textAnchor={flip ? 'end' : 'start'}
          className="fill-plex-text-muted font-mono text-[10px]"
          data-testid={`scheduler-wouldbe-time-${row.jobKey}-${index}`}
        >
          {formatClock(fire.at)}
        </text>
      )}
    </g>
  );
}

WouldBeMark.propTypes = {
  row: PropTypes.object.isRequired,
  fire: PropTypes.object.isRequired,
  index: PropTypes.number.isRequired,
  x: PropTypes.number.isRequired,
  cy: PropTypes.number.isRequired,
  plotWidth: PropTypes.number.isRequired,
  gapNextPx: PropTypes.number.isRequired,
  onSelect: PropTypes.func.isRequired,
  tip: PropTypes.object,
};


/**
 * A repeating row's marks. Ticks (2px by 12px, the repeating ink, full strength once past and faint while still to
 * come) when the server listed the slots and they are at least 3px apart; otherwise one band across the axis with its
 * "every 5 min" words. A paused job says "Paused" instead of drawing
 * its slots. Problems are overlaid at their times in coral (failed) or amber (partial), whichever the layout.
 */
export function RepeatingMarks({ row, layout, xOf, width, cy, nowMs, onSelect, problemTips }) {
  const past = (ms) => ms <= nowMs;
  const band = layout.shape === 'band';
  let drawn;
  if (row.paused) {
    drawn = (
      <text x={8} y={cy + 4} className="fill-plex-text-muted text-[11px]" data-testid={`scheduler-paused-${row.jobKey}`}>
        Paused
      </text>
    );
  } else if (band) {
    // One continuous band, as the design draws it (about 45% ink: the token is already half strength).
    drawn = (
      <g data-testid={`scheduler-band-${row.jobKey}`}>
        <rect
          x={0}
          y={cy - 3}
          width={width}
          height="6"
          rx="3"
          className="fill-scheduler-repeating"
          fillOpacity={BAND_OPACITY}
          data-testid={`scheduler-band-rect-${row.jobKey}`}
        />
        <text
          x={width - 4}
          y={cy - 6}
          textAnchor="end"
          className="fill-plex-text-muted font-mono text-[10px]"
          data-testid={`scheduler-band-label-${row.jobKey}`}
        >
          {row.cadence}
        </text>
      </g>
    );
  } else {
    drawn = (
      <g data-testid={`scheduler-ticks-${row.jobKey}`}>
        {layout.ticks.map((ms, i) => (
          <rect
            key={ms}
            x={xOf(ms) - 1}
            y={cy - 6}
            width="2"
            height="12"
            className="fill-scheduler-repeating"
            fillOpacity={past(ms) ? undefined : FUTURE_REPEATING_OPACITY}
            data-testid={`scheduler-tick-${row.jobKey}-${i}`}
            data-future={past(ms) ? 'false' : 'true'}
          />
        ))}
      </g>
    );
  }
  return (
    <g
      className="cursor-pointer"
      data-testid={`scheduler-day-row-${row.jobKey}`}
      data-layout={row.paused ? 'paused' : layout.shape}
      onClick={() => onSelect(row.jobKey, null)}
    >
      <rect x={0} y={cy - 12} width={width} height="24" className="fill-transparent" />
      {drawn}
      {row.problems.map((problem, i) => {
        const x = xOf(problem.atMs);
        if (x < 0 || x > width) return null;
        const partial = problem.status === 'PARTIAL';
        const style = fireStatusStyle(problem.status);
        return (
          <rect
            key={`${problem.at}-${i}`}
            x={x - 1.5}
            y={cy - 9}
            width="3"
            height="18"
            className={partial ? 'fill-verdict-marginal' : 'fill-plex-coral'}
            data-testid={`scheduler-problem-${row.jobKey}-${i}`}
            data-status={problem.status}
            data-tone={style.tone}
            {...problemTips[i]}
          />
        );
      })}
    </g>
  );
}

RepeatingMarks.propTypes = {
  row: PropTypes.object.isRequired,
  layout: PropTypes.shape({ shape: PropTypes.oneOf(['ticks', 'band']).isRequired, ticks: PropTypes.array.isRequired }).isRequired,
  xOf: PropTypes.func.isRequired,
  width: PropTypes.number.isRequired,
  cy: PropTypes.number.isRequired,
  nowMs: PropTypes.number.isRequired,
  onSelect: PropTypes.func.isRequired,
  problemTips: PropTypes.arrayOf(PropTypes.object).isRequired,
};

/** A watchdog window's faint band, from when its check opens to its deadline (clamped to the axis). Hover shows it. */
export function WatchdogBand({ span, xOf, width, cy, tip = undefined }) {
  const x0 = Math.max(0, xOf(span.startMs));
  const x1 = Math.min(width, xOf(span.deadlineMs));
  if (x1 <= x0) return null;
  return (
    <rect
      x={x0}
      y={cy - WATCHDOG_HALF_HEIGHT}
      width={x1 - x0}
      height={WATCHDOG_HALF_HEIGHT * 2}
      className="fill-plex-coral/10"
      data-testid={`scheduler-watchdog-${span.jobKey}`}
      data-budget-only={span.budgetOnly ? 'true' : 'false'}
      aria-hidden="true"
      {...tip}
    />
  );
}

WatchdogBand.propTypes = {
  span: PropTypes.object.isRequired,
  xOf: PropTypes.func.isRequired,
  width: PropTypes.number.isRequired,
  cy: PropTypes.number.isRequired,
  tip: PropTypes.object,
};


/** A watchdog window's red deadline line (2px by 18px), drawn over the marks; only when the deadline is on the axis. */
export function WatchdogDeadline({ span, xOf, width, cy, tip = undefined }) {
  const x = xOf(span.deadlineMs);
  if (x < 0 || x > width) return null;
  return (
    <rect
      x={x - 1}
      y={cy - WATCHDOG_HALF_HEIGHT}
      width="2"
      height={WATCHDOG_HALF_HEIGHT * 2}
      className="fill-plex-coral"
      data-testid={`scheduler-watchdog-deadline-${span.jobKey}`}
      aria-hidden="true"
      {...tip}
    />
  );
}

WatchdogDeadline.propTypes = {
  span: PropTypes.object.isRequired,
  xOf: PropTypes.func.isRequired,
  width: PropTypes.number.isRequired,
  cy: PropTypes.number.isRequired,
  tip: PropTypes.object,
};

