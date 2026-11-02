import { useState } from 'react';
import PropTypes from 'prop-types';
import Segmented from '../../data/Segmented.jsx';
import useMeasuredWidth from '../../../hooks/useMeasuredWidth.js';
import { formatDay } from '../../../utils/travelDays.js';
import { formatClockUk, formatSeconds } from '../../../utils/runsView.js';
import {
  PLANNER_BUFFERS, PLANNER_MAX, PLANNER_MIN, PLANNER_STEP, SCHEDULER_HREF, clampOffset, formatMinutes, replayPlanner,
} from '../../../utils/runsTiming.js';

const CARD = 'rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3';
const VIEW_W_FALLBACK = 420;
const HEIGHT = 96;
const LEFT = 6;
const RIGHT = 6;
const TOP = 18;
const BOTTOM = 18;
const DOT_R = 3.5;
/** Pixels between a dot row's top and the first dot, and the prime that scatters the dots down the strip. */
const DOT_INSET = 8;
const JITTER_PRIME = 37;
/** Room after the slowest run, and before the earliest start, in minutes. */
const PAD_AFTER = 8;
const PAD_BEFORE = 10;
const MIN_AFTER = 15;
/** The share of runs in time at or above which the result is green, and below which it is coral. */
const GOOD_SHARE = 0.9;
const BAD_SHARE = 0.75;

const BUFFER_OPTIONS = PLANNER_BUFFERS.map((minutes) => ({
  value: String(minutes), label: minutes === 0 ? 'sunset' : `${minutes}m before`,
}));

/** Which border the result box wears: green at 90% and over, coral under 75%. */
function resultTone(share) {
  if (share == null) return 'border-plex-border';
  if (share >= GOOD_SHARE) return 'border-verdict-go';
  if (share < BAD_SHARE) return 'border-plex-coral';
  return 'border-plex-border';
}

/**
 * The Timing view's "Plan the afternoon refresh around sunset" card (Afternoon refresh only): replays the served runs
 * with a different start. A slider sets how long before sunset the refresh starts (20 minutes to 5 hours, in fives,
 * opening on the served trigger); the buffer says how long before sunset the answers are needed (60, 30, 15 or 0
 * minutes). The strip draws each run at where its answers would have landed relative to sunset (green in time, coral
 * late), the result box says how many would have been in time and what today's start would be, and the suggestion bar
 * gives the latest start that still lands 9 in 10 with Try it, Reset and Open in Scheduler. Open in Scheduler only
 * navigates to the job's panel: it never saves anything and prefills nothing (F10).
 *
 * <p>The replay and the suggestion are the licensed derivation in {@code utils/runsTiming.js} ({@link replayPlanner}),
 * over served durations, the served p90 and median and the served sunset.
 *
 * @param {object} props
 * @param {object} props.timing the served {@code RunTimingResponse} for AFTERNOON, with a sunset trigger
 */
export default function SunsetPlanner({ timing }) {
  const current = clampOffset(timing.trigger.offsetMinutes);
  const [offset, setOffset] = useState(current);
  const [buffer, setBuffer] = useState(30);
  const [widthRef, measured] = useMeasuredWidth();
  const { stats, runs } = timing;
  const replay = replayPlanner({
    runs,
    offsetMinutes: offset,
    bufferMinutes: buffer,
    p90Seconds: stats.p90Seconds,
    medianSeconds: stats.medianSeconds,
    todaySunset: timing.todaySunset,
  });
  const suggestion = replay.suggestionMinutes;

  const viewW = measured > 0 ? measured : VIEW_W_FALLBACK;
  const plotW = viewW - LEFT - RIGHT;
  const plotH = HEIGHT - TOP - BOTTOM;
  const longest = Math.max(0, ...replay.points.map((p) => p.offsetFromSunset));
  const lo = -Math.max(offset, suggestion ?? 0) - PAD_BEFORE;
  const hi = Math.max(MIN_AFTER, longest + PAD_AFTER);
  const x = (minutes) => LEFT + ((minutes - lo) / (hi - lo)) * plotW;
  const hourTicks = [];
  for (let m = Math.ceil(lo / 60) * 60; m <= hi; m += 60) hourTicks.push(m);

  const pct = replay.share == null ? 0 : Math.round(replay.share * 100);
  const bufferText = buffer === 0 ? 'by sunset' : `${buffer} min before sunset`;
  let today = null;
  if (replay.start && replay.sunset) {
    today = (
      <>
        {' '}Today that means starting at <strong className="font-semibold">{formatClockUk(replay.start)}</strong>{' '}
        (sunset {formatClockUk(replay.sunset)})
        {replay.typical && replay.nineInTen
          ? `, with answers around ${formatClockUk(replay.typical)} typically and by ${formatClockUk(replay.nineInTen)} 9 times in 10.`
          : '.'}
      </>
    );
  }

  return (
    <section className={CARD} data-testid="runs-planner">
      <h3 className="text-[13px] font-semibold text-plex-text">Plan the afternoon refresh around sunset</h3>
      <p className="font-mono text-[11px] text-plex-text-secondary" data-testid="runs-planner-meta">
        replays the last {replay.total} runs with a different start
      </p>
      <div className="mt-3 grid grid-cols-[1fr_auto] items-baseline gap-x-3 gap-y-1">
        <label htmlFor="runs-planner-offset" className="text-[13px] text-plex-text">Start before sunset</label>
        <output htmlFor="runs-planner-offset" className="font-mono text-[14px] text-plex-text" data-testid="runs-planner-offset-readout">
          {formatMinutes(offset)}
        </output>
        <input
          id="runs-planner-offset"
          type="range"
          min={PLANNER_MIN}
          max={PLANNER_MAX}
          step={PLANNER_STEP}
          value={offset}
          onChange={(event) => setOffset(Number(event.target.value))}
          className="col-span-2 h-6 w-full cursor-pointer accent-plex-gold max-[639px]:h-11"
          data-testid="runs-planner-offset"
        />
      </div>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <span className="text-[13px] text-plex-text">Answers needed by</span>
        <Segmented
          label="Answers needed by"
          options={BUFFER_OPTIONS}
          value={String(buffer)}
          onChange={(value) => setBuffer(Number(value))}
          testId="runs-planner-buffer"
        />
      </div>
      <div ref={widthRef} className="mt-2" data-testid="runs-planner-chart">
        <svg
          viewBox={`0 0 ${viewW} ${HEIGHT}`}
          className="h-auto w-full"
          role="img"
          aria-label={`Each of ${replay.total} runs placed by when its answers would be ready relative to sunset`}
          data-testid="runs-planner-svg"
        >
          <rect x={x(-buffer)} y={TOP} width={Math.max(0, x(hi) - x(-buffer))} height={plotH} className="fill-plex-coral/8" />
          <line x1={x(-offset)} x2={x(-offset)} y1={TOP - 4} y2={TOP + plotH} strokeDasharray="2 3" className="stroke-plex-text-muted" />
          <text x={x(-offset)} y={TOP - 7} fontSize="10" textAnchor="middle" className="fill-plex-text-muted font-mono">Start</text>
          <line x1={x(-buffer)} x2={x(-buffer)} y1={TOP - 4} y2={TOP + plotH} className="stroke-plex-gold" />
          <text x={x(-buffer)} y={TOP - 7} fontSize="10" textAnchor="end" className="fill-plex-gold font-mono">
            {buffer === 0 ? 'Needed by sunset' : 'Needed by'}
          </text>
          <line x1={x(0)} x2={x(0)} y1={TOP - 4} y2={TOP + plotH} className="stroke-plex-coral" />
          {replay.points.map((point) => (
            <circle
              key={point.index}
              cx={x(point.offsetFromSunset)}
              cy={TOP + DOT_INSET + ((point.index * JITTER_PRIME) % Math.max(1, plotH - 2 * DOT_INSET))}
              r={DOT_R}
              opacity="0.85"
              className={point.inTime ? 'fill-verdict-go' : 'fill-plex-coral'}
              data-in-time={point.inTime ? 'true' : 'false'}
              data-testid="runs-planner-dot"
            >
              <title>
                {`${formatDay(runs[point.index].date)}: took ${formatSeconds(point.durationSeconds)}, ready ${formatMinutes(Math.abs(point.offsetFromSunset))} ${point.offsetFromSunset <= 0 ? 'before' : 'after'} sunset`}
              </title>
            </circle>
          ))}
          {hourTicks.map((m) => (
            <text
              key={m}
              x={x(m)}
              y={HEIGHT - 4}
              fontSize="10"
              textAnchor={m === 0 ? 'end' : 'middle'}
              className={`${m === 0 ? 'fill-plex-coral' : 'fill-plex-text-muted'} font-mono`}
            >
              {m === 0 ? 'Sunset' : `${m < 0 ? '−' : '+'}${formatMinutes(Math.abs(m))}`}
            </text>
          ))}
        </svg>
      </div>
      <p
        className={`mt-2 rounded-lg border p-3 text-[13px] leading-relaxed text-plex-text-secondary ${resultTone(replay.share)}`}
        data-testid="runs-planner-result"
        data-tone={resultTone(replay.share)}
      >
        {replay.total === 0 ? (
          'No runs measured in this range to replay.'
        ) : (
          <>
            <strong className="font-semibold text-plex-text">{replay.inTime} of {replay.total} runs</strong>{' '}
            ({pct}%) would have had answers ready {bufferText}.{today}
          </>
        )}
      </p>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg bg-plex-surface-light p-3" data-testid="runs-planner-suggestion">
        <span className="min-w-0 flex-1 text-[13px] text-plex-text" data-testid="runs-planner-suggestion-text">
          {suggestion == null ? (
            'Not enough runs yet to suggest a start.'
          ) : (
            <>Latest start that still lands 9 in 10: <strong className="font-semibold">{formatMinutes(suggestion)} before sunset</strong></>
          )}
        </span>
        {suggestion != null && offset !== clampOffset(suggestion) && (
          <button
            type="button"
            className="btn-secondary text-xs max-[639px]:min-h-11"
            onClick={() => setOffset(clampOffset(suggestion))}
            data-testid="runs-planner-try"
          >
            Try it
          </button>
        )}
        {offset !== current && (
          <button
            type="button"
            className="cursor-pointer text-xs text-plex-text-secondary underline hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
            onClick={() => setOffset(current)}
            data-testid="runs-planner-reset"
          >
            Reset to current ({formatMinutes(current)})
          </button>
        )}
        <a
          href={SCHEDULER_HREF}
          className="btn-primary inline-flex items-center text-xs max-[639px]:min-h-11"
          data-testid="runs-planner-scheduler"
        >
          Open in Scheduler
        </a>
      </div>
    </section>
  );
}

SunsetPlanner.propTypes = {
  timing: PropTypes.shape({
    trigger: PropTypes.shape({ offsetMinutes: PropTypes.number.isRequired }).isRequired,
    todaySunset: PropTypes.string,
    runs: PropTypes.array.isRequired,
    stats: PropTypes.object.isRequired,
  }).isRequired,
};
