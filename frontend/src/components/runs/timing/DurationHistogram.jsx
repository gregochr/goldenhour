import PropTypes from 'prop-types';
import useMeasuredWidth from '../../../hooks/useMeasuredWidth.js';
import { typeStyle } from '../../../utils/runsDay.js';
import { formatSeconds } from '../../../utils/runsView.js';
import { durationTickStep, histogram, kdeCurve, niceScale } from '../../../utils/runsTiming.js';

const CARD = 'rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3';
const VIEW_W_FALLBACK = 800;
const HEIGHT = 230;
const LEFT = 32;
const RIGHT = 10;
const TOP = 44;
const BOTTOM = 22;
/** Headroom above the tallest bar. */
const HEADROOM = 1.15;
/** A marker's label flips to the left of its line when it is this close to the right edge. */
const FLIP_PX = 130;
/** The vertical stagger between the marker labels. */
const MARKER_STAGGER = 13;

/**
 * The Timing view's "How long {type} takes" card: a histogram of the served durations (bin width chosen from 3s to
 * 60m for at most 24 bins, bars at 55%), a smoothed Gaussian density curve over it (the data is right-skewed, so the
 * real bars stay underneath), dashed Median, 9 in 10 and Slowest markers staggered so their labels never overlap
 * (flipped to the left of the line near the right edge), and the "leans right" note when the server says the shape is
 * skewed. Hand-rolled SVG sized to the card.
 *
 * <p>The bins, the curve and the rolling lines are the licensed timing geometry ({@code utils/runsTiming.js});
 * the median, 9-in-10, slowest and the skew flag are served. With no runs there is no chart; with one run there are
 * bars and markers but no curve (nothing to smooth) and no 9-in-10 line (the server serves none).
 *
 * @param {object} props
 * @param {object} props.timing the served {@code RunTimingResponse}
 */
export default function DurationHistogram({ timing }) {
  const [widthRef, measured] = useMeasuredWidth();
  const { runs, stats, type, typeLabel, measure } = timing;
  const values = runs.map((run) => run.durationSeconds);
  const what = measure === 'ready' ? 'start to answers ready' : 'run duration';
  const heading = (
    <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
      <h3 className="text-[13px] font-semibold text-plex-text" data-testid="runs-hist-title">How long {typeLabel} takes</h3>
      <span className="font-mono text-[11px] text-plex-text-secondary">
        {what} {'·'} each bar counts runs {'·'} curve is the smoothed shape
      </span>
    </div>
  );
  if (values.length === 0) {
    return (
      <section className={CARD} data-testid="runs-hist-card">
        {heading}
        <p className="mt-4 text-[13px] text-plex-text-secondary" data-testid="runs-hist-empty">
          No runs measured in this range.
        </p>
      </section>
    );
  }

  const viewW = measured > 0 ? measured : VIEW_W_FALLBACK;
  const plotW = viewW - LEFT - RIGHT;
  const plotH = HEIGHT - TOP - BOTTOM;
  const { width, start, bins } = histogram(values);
  const from = start - width;
  const to = start + (bins.length + 1) * width;
  const x = (seconds) => LEFT + ((seconds - from) / (to - from)) * plotW;
  const curve = kdeCurve(values, from, to, width);
  // The curve can peak above the tallest bar (a tight cluster across two bins), so the scale covers both.
  const tallest = Math.max(...bins.map((bin) => bin.count), ...curve.map((p) => p.y)) * HEADROOM;
  const y = (count) => TOP + plotH - (count / tallest) * plotH;
  const countStep = Math.max(1, Math.ceil(niceScale(tallest, 4).step));
  const gridlines = [];
  for (let count = 0; count <= tallest; count += countStep) gridlines.push(count);
  const markers = [
    { key: 'median', label: 'Median', seconds: stats.medianSeconds, line: 'stroke-plex-text', text: 'fill-plex-text' },
    { key: 'p90', label: '9 in 10', seconds: stats.p90Seconds, line: 'stroke-verdict-marginal', text: 'fill-verdict-marginal' },
    { key: 'slowest', label: 'Slowest', seconds: stats.slowest?.seconds ?? null, line: 'stroke-plex-coral', text: 'fill-plex-coral' },
  ];
  const tickStep = durationTickStep(to - from);
  const ticks = [];
  for (let t = Math.ceil(Math.max(0, from) / tickStep) * tickStep; t <= to; t += tickStep) ticks.push(t);

  return (
    <section className={CARD} data-testid="runs-hist-card">
      {heading}
      <div ref={widthRef} className="mt-2" data-testid="runs-hist-chart">
        <svg
          viewBox={`0 0 ${viewW} ${HEIGHT}`}
          className="h-auto w-full"
          role="img"
          aria-label={`Histogram of ${runs.length} ${typeLabel} durations, bin width ${formatSeconds(width)}`}
          data-testid="runs-hist-svg"
        >
          {gridlines.map((count) => (
            <g key={count}>
              <line x1={LEFT} x2={LEFT + plotW} y1={y(count)} y2={y(count)} className="stroke-plex-border" strokeWidth="1" />
              <text x={LEFT - 6} y={y(count) + 3} fontSize="10" textAnchor="end" className="fill-plex-text-muted font-mono">{count}</text>
            </g>
          ))}
          {bins.map((bin) => (
            <rect
              key={bin.from}
              x={x(bin.from) + 1}
              y={y(bin.count)}
              width={Math.max(1, x(bin.to) - x(bin.from) - 2)}
              height={TOP + plotH - y(bin.count)}
              className={typeStyle(type).fill}
              opacity="0.55"
              data-testid="runs-hist-bar"
            >
              <title>{`${formatSeconds(bin.from)} – ${formatSeconds(bin.to)}: ${bin.count} run${bin.count === 1 ? '' : 's'}`}</title>
            </rect>
          ))}
          {curve.length > 0 && (
            <polyline
              fill="none"
              strokeWidth="1.5"
              className="stroke-plex-text"
              points={curve.map((p) => `${x(p.x)},${y(p.y)}`).join(' ')}
              pointerEvents="none"
              data-testid="runs-hist-kde"
            />
          )}
          {markers.map((marker, i) => {
            if (marker.seconds == null) return null;
            const mx = x(marker.seconds);
            const flip = mx > viewW - FLIP_PX;
            return (
              <g key={marker.key} data-testid={`runs-hist-marker-${marker.key}`}>
                <line x1={mx} x2={mx} y1={TOP - 30 + i * MARKER_STAGGER} y2={TOP + plotH} strokeDasharray="3 3" className={marker.line} />
                <text
                  x={flip ? mx - 4 : mx + 4}
                  y={TOP - 22 + i * MARKER_STAGGER}
                  fontSize="11"
                  textAnchor={flip ? 'end' : 'start'}
                  className={`${marker.text} font-mono`}
                >
                  {marker.label} {formatSeconds(marker.seconds)}
                </text>
              </g>
            );
          })}
          {ticks.map((t) => (
            <text key={t} x={x(t)} y={HEIGHT - 5} fontSize="10" textAnchor="middle" className="fill-plex-text-muted font-mono">
              {formatSeconds(t)}
            </text>
          ))}
        </svg>
      </div>
      {stats.skewed && (
        <p className="text-[12px] text-plex-text-secondary" data-testid="runs-hist-skew">
          The shape leans right: most runs land near the median, a minority take much longer. The 9-in-10 line is the safer number to plan around.
        </p>
      )}
    </section>
  );
}

DurationHistogram.propTypes = {
  timing: PropTypes.shape({
    type: PropTypes.string.isRequired,
    typeLabel: PropTypes.string.isRequired,
    measure: PropTypes.string.isRequired,
    runs: PropTypes.array.isRequired,
    stats: PropTypes.object.isRequired,
  }).isRequired,
};
