import PropTypes from 'prop-types';
import { fireStartMs, fireWidthPx, onAxis } from '../../../utils/schedulerDay.js';
import { collectArrowPath, repeatingLayout, staggerArrowPath } from '../../../utils/schedulerDayOverlays.js';

/** How far past a fire's end the "results collected by" arrow lands on a band (the design's 10px). */
const BAND_LANDING_PX = 10;

/** Fires that did something to link from: not a paused would-be, not a missed slot. */
const hasRun = (fire) => fire.status !== 'MISSED' && fire.status !== 'PAUSED';

/**
 * The dependency arrows (scheduler-redesign-plan.md D12): only the links the catalogue declares, between rows that are
 * on the picture, with the design's geometry.
 *
 * <ul>
 *   <li>{@code collect} ("results collected by"), solid: from the foot of each of the submitting job's fires on the
 *       axis down to the collecting job's row, landing at its next tick (or at the end of the fire plus a few px on a
 *       band). A collecting row with no later tick, or paused, gets none.
 *   <li>{@code stagger} ("followed 30 min later by"), dashed: from the end of each fire of the first job to the next
 *       fire of the second.
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
    const x = xOf(fireStartMs(fire));
    return { fire, x, endX: Math.min(x + fireWidthPx(fire, axis, width, clock), width) };
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
    spans(source).forEach(({ endX }, i) => {
      if (link.kind === 'collect') {
        const target = repeating.get(link.to);
        let landing = null;
        if (target) {
          if (target.paused) return;
          const layout = repeatingLayout(target, axis, width);
          if (layout.shape === 'band') {
            landing = Math.min(endX + BAND_LANDING_PX, width);
          } else {
            const next = layout.ticks.map(xOf).find((tx) => tx >= endX);
            if (next != null) landing = next;
          }
        } else if (scheduled.get(link.to)) {
          const next = spans(scheduled.get(link.to)).map((s) => s.x).find((tx) => tx >= endX);
          if (next != null) landing = next;
        }
        if (landing == null) return;
        arrows.push({
          key: `collect-${link.from}-${link.to}-${i}`, kind: link.kind, link, hi, faded, d: collectArrowPath(endX, fromY, toY, landing),
        });
      } else {
        const targetRow = scheduled.get(link.to);
        if (!targetRow) return;
        const sourceStart = spans(source)[i].x;
        const next = spans(targetRow).find((s) => s.x > sourceStart);
        if (!next) return;
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
