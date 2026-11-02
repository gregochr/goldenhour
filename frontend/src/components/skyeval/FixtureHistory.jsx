import { useMemo } from 'react';
import PropTypes from 'prop-types';
import { modelLabel } from '../../utils/modelLabels.js';
import {
  CELL_FILL, HISTORY, HISTORY_TICKS, buildFixtureHistory, historyY, modelColour,
} from '../../utils/skyEval.js';

/** Dot fill by kind; 'model' is replaced by the model's own colour. */
const KIND_FILL = { below: CELL_FILL.below, above: CELL_FILL.above, failed: CELL_FILL.failed };

/**
 * One fixture's rating history for one model: the band as a shaded rectangle (band low minus .5 to band high plus
 * .5), the 1 to 5 axis, gold version lines, and one dot per run at the mean with a min to max whisker. The dot is
 * in the model's colour when every rating was in band, tide when mostly below, coral when mostly above. Every
 * point's facts are an SVG title and also in the grid cell above it.
 *
 * @param {object} props
 * @param {{name: string, bandLo: number, bandHi: number}} props.fixture the fixture
 * @param {string} props.model the model enum name
 * @param {Array<object>} props.columns the model's grid columns
 * @param {Array<object>} props.versions the served versions
 */
export default function FixtureHistory({ fixture, model, columns, versions }) {
  const geo = useMemo(() => buildFixtureHistory(fixture, columns, versions), [fixture, columns, versions]);
  const plotRight = HISTORY.width - HISTORY.right;
  const plotBottom = HISTORY.height - HISTORY.bottom;
  const colour = modelColour(model);
  if (geo.points.length === 0) {
    return (
      <p
        className="sticky left-0 py-2 font-mono text-[11.5px] text-plex-text-muted"
        data-testid={`sky-eval-fixture-history-${fixture.name}`}
      >
        Not yet scored on {modelLabel(model)}.
      </p>
    );
  }
  return (
    <div
      className="sticky left-0 w-[640px] max-w-[calc(100vw-4.5rem)] pb-3 pt-1"
      data-testid={`sky-eval-fixture-history-${fixture.name}`}
    >
      <svg
        viewBox={`0 0 ${HISTORY.width} ${HISTORY.height}`}
        className="block h-auto w-full overflow-visible"
        role="img"
        aria-label={`Rating history of ${fixture.name} on ${modelLabel(model)}, band ${fixture.bandLo} to ${fixture.bandHi}`}
        data-testid="sky-eval-fixture-history-svg"
      >
        <rect
          x={geo.band.x} y={geo.band.y} width={geo.band.width} height={geo.band.height}
          fill={CELL_FILL.inband} fillOpacity="0.12" data-testid="sky-eval-history-band"
        />
        <text x={HISTORY.left + 4} y={geo.band.y + 11} className="fill-verdict-go font-mono text-[10px]">
          band {fixture.bandLo}{'–'}{fixture.bandHi}
        </text>
        {HISTORY_TICKS.map((v) => (
          <g key={v}>
            <line x1={HISTORY.left} y1={historyY(v)} x2={plotRight} y2={historyY(v)} stroke="rgba(242,231,211,0.05)" />
            <text x={HISTORY.left - 6} y={historyY(v) + 3} textAnchor="end" className="fill-plex-text-muted font-mono text-[10px]">{v}</text>
          </g>
        ))}
        {geo.markers.map((mk) => (
          <g key={mk.sha} data-testid="sky-eval-history-version-line">
            <line x1={mk.x} y1={HISTORY.top - 6} x2={mk.x} y2={plotBottom} stroke="rgba(235,217,168,0.3)" strokeDasharray="2 3" />
            <text x={mk.x} y={mk.labelY} textAnchor="middle" className="fill-skyeval-version font-mono text-[10px]">
              <title>{mk.title}</title>
              {mk.label}
            </text>
          </g>
        ))}
        {geo.points.map((p) => (
          <g key={p.runId} data-testid={`sky-eval-history-point-${p.runId}`} data-kind={p.kind}>
            {p.yMin != null && p.yMax != null && (
              <line x1={p.x} y1={p.yMin} x2={p.x} y2={p.yMax} stroke={KIND_FILL[p.kind] ?? colour} strokeOpacity="0.6" strokeWidth="1.5" />
            )}
            {p.y != null && (
              <circle cx={p.x} cy={p.y} r="3.5" fill={KIND_FILL[p.kind] ?? colour}>
                <title>{p.title}</title>
              </circle>
            )}
          </g>
        ))}
        {geo.ticks.map((t) => (
          <text key={t.x} x={t.x} y={HISTORY.height - 6} textAnchor={t.anchor} className="fill-plex-text-muted font-mono text-[10px]">
            {t.label}
          </text>
        ))}
      </svg>
      <p className="font-mono text-[11px] text-plex-text-muted">
        Dot = mean rating across the run{'’'}s repeats, line = lowest to highest. {modelLabel(model)} on {fixture.name}.
      </p>
    </div>
  );
}

FixtureHistory.propTypes = {
  fixture: PropTypes.shape({
    name: PropTypes.string.isRequired, bandLo: PropTypes.number.isRequired, bandHi: PropTypes.number.isRequired,
  }).isRequired,
  model: PropTypes.string.isRequired,
  columns: PropTypes.array.isRequired,
  versions: PropTypes.array.isRequired,
};
