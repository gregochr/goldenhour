import PropTypes from 'prop-types';
import { fireDurationSeconds, fireStartMs, fireWidthPx, onAxis } from '../../../utils/schedulerDay.js';
import {
  collectArrowPath,
  repeatingIntervalMs,
  repeatingLayout,
  staggerArrowPath,
} from '../../../utils/schedulerDayOverlays.js';

/** How far past a fire's end the "results collected by" arrow lands on a band (the design's 10px). */
const BAND_LANDING_PX = 10;

/** Fires an arrow may start from: they ran or will run. Never a SKIPPED or FAILED one (it submitted nothing), a missed slot or a paused would-be. */
const LINKABLE = ['SUCCEEDED', 'PARTIAL', 'RUNNING', 'SCHEDULED'];
const hasRun = (fire) => LINKABLE.includes(fire.status);

/** The declared stagger between two jobs, and how far from it the next fire may be and still be "followed by". */
const STAGGER_MS = 30 * 60_000;
const STAGGER_TOLERANCE_MS = 15 * 60_000;

/**
 * The dependency arrows (scheduler-redesign-plan.md D12): only the links the catalogue declares, between rows that are
 * on the picture, with the design's geometry.
 *
 * <ul>
 *   <li>{@code collect} ("results collected by"), solid: from the foot of each of the submitting job's fires on the
 *       axis down to the collecting poller's row, landing at its next tick (or at the end of the fire plus a few px on
 *       a band). None when the poller is paused, has no later tick, or its next tick is more than one interval plus
 *       the fire's duration away. Never from a SKIPPED or FAILED fire.
 *   <li>{@code stagger} ("followed 30 min later by"), dashed: from the end of each fire of the first job to the next
 *       fire of the second, only when that is within the declared 30 minutes plus a 15 minute tolerance.
 * </ul>
 *
 * <p>A link touching the selected job is gold and thicker; the others fade while something is selected. Decorative
 * ({@code aria-hidden}, no pointer events); every arrow has a test id.
 *
 * @param {object} props
 * @param {Array<{kind: string, from: string, to: string}>} props.links the declared links between rows on the picture
 * @param {Array<object>} props.rows the scheduled rows drawn
 * @param {Array<object>} props.repeatingRows the repeating rows drawn
 * @param {Object<string, number>} props.rowCenters each row's centre y
 * @param {(ms: number) => number} props.xOf time to x
 * @param {{startMs: number, endMs: number}} props.axis the axis shown
 * @param {number} props.width the plot width, px
 * @param {{nowMs: number, isToday: boolean}} props.clock for a running fire's width
 * @param {string|null} props.selectedKey the job whose panel is open
 * @param {string} props.markerId a prefix for the arrowhead marker ids (unique per picture)
 */
export default function DayArrows({ links, rows, repeatingRows, rowCenters, xOf, axis, width, clock, selectedKey = null, markerId }) {
  const scheduled = new Map(rows.map((row) => [row.jobKey, row]));
  const repeating = new Map(repeatingRows.map((row) => [row.jobKey, row]));

  // A scheduled row's fires that did something, each with where it starts and ends on the picture.
  const spans = (row) => row.fires.filter(hasRun).filter((fire) => onAxis(fireStartMs(fire), axis)).map((fire) => {
    const startMs = fireStartMs(fire);
    const x = xOf(startMs);
    const durationMs = (fireDurationSeconds(fire) ?? 0) * 1000;
    return { fire, startMs, endMs: startMs + durationMs, durationMs, x, endX: Math.min(x + fireWidthPx(fire, axis, width, clock), width) };
  });

  const arrows = [];
  links.forEach((link) => {
    const source = scheduled.get(link.from);
    if (!source) return;
    const fromY = rowCenters[link.from];
    const toY = rowCenters[link.to];
    if (fromY == null || toY == null) return;
    const hi = selectedKey != null && (selectedKey === link.from || selectedKey === link.to);
    const faded = selectedKey != null && !hi;
    const sourceSpans = spans(source);
    sourceSpans.forEach(({ endX, endMs, durationMs, startMs }, i) => {
      if (link.kind === 'collect') {
        // Only to a repeating poller: a fire's results are collected at the poller's next tick, never a whole day on.
        const target = repeating.get(link.to);
        if (!target || target.paused) return;
        const layout = repeatingLayout(target, axis, width);
        let landing = null;
        if (layout.shape === 'band') {
          landing = Math.min(endX + BAND_LANDING_PX, width);
        } else {
          const next = layout.ticks.find((ms) => ms >= endMs);
          if (next != null && next - endMs <= repeatingIntervalMs(target) + durationMs) landing = xOf(next);
        }
        if (landing == null) return;
        arrows.push({
          key: `collect-${link.from}-${link.to}-${i}`, kind: link.kind, link, hi, faded, d: collectArrowPath(endX, fromY, toY, landing),
        });
      } else {
        const targetRow = scheduled.get(link.to);
        if (!targetRow) return;
        const next = spans(targetRow).find((s) => s.startMs > startMs);
        if (!next || next.startMs - startMs > STAGGER_MS + STAGGER_TOLERANCE_MS) return;
        arrows.push({
          key: `stagger-${link.from}-${link.to}-${i}`, kind: link.kind, link, hi, faded, d: staggerArrowPath(endX, fromY, next.x, toY),
        });
      }
    });
  });

  return (
    <g aria-hidden="true" pointerEvents="none" data-testid="scheduler-day-arrows">
      <defs>
        <marker id={`${markerId}-ah`} viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" orient="auto">
          <path d="M0 0L6 3L0 6Z" className="fill-plex-text-secondary" />
        </marker>
        <marker id={`${markerId}-ahh`} viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" orient="auto">
          <path d="M0 0L6 3L0 6Z" className="fill-skyeval-version" />
        </marker>
      </defs>
      {arrows.map((arrow) => {
        const collect = arrow.kind === 'collect';
        let tone = collect ? 'stroke-plex-text-secondary' : 'stroke-plex-text-muted';
        if (arrow.hi) tone = 'stroke-skyeval-version';
        let opacity = collect ? 0.55 : 0.8;
        if (arrow.hi) opacity = 1;
        else if (arrow.faded) opacity = 0.12;
        return (
          <path
            key={arrow.key}
            d={arrow.d}
            fill="none"
            strokeWidth={arrow.hi ? 1.6 : 1}
            strokeDasharray={collect ? undefined : '3 3'}
            opacity={opacity}
            markerEnd={`url(#${markerId}-${arrow.hi ? 'ahh' : 'ah'})`}
            className={tone}
            data-testid={`scheduler-arrow-${arrow.key}`}
            data-kind={arrow.kind}
            data-from={arrow.link.from}
            data-to={arrow.link.to}
            data-selected={arrow.hi ? 'true' : 'false'}
          />
        );
      })}
    </g>
  );
}

DayArrows.propTypes = {
  links: PropTypes.arrayOf(PropTypes.shape({ kind: PropTypes.string, from: PropTypes.string, to: PropTypes.string })).isRequired,
  rows: PropTypes.arrayOf(PropTypes.object).isRequired,
  repeatingRows: PropTypes.arrayOf(PropTypes.object).isRequired,
  rowCenters: PropTypes.object.isRequired,
  xOf: PropTypes.func.isRequired,
  axis: PropTypes.shape({ startMs: PropTypes.number, endMs: PropTypes.number }).isRequired,
  width: PropTypes.number.isRequired,
  clock: PropTypes.shape({ nowMs: PropTypes.number, isToday: PropTypes.bool }).isRequired,
  selectedKey: PropTypes.string,
  markerId: PropTypes.string.isRequired,
};
