import { useMemo } from 'react';
import PropTypes from 'prop-types';
import { modelLabel } from '../../utils/modelLabels.js';
import {
  CHART, Y_TICKS, buildAccuracySeries, chartWindow, chartY, modelColour, pointTitle, timeTicks, versionMarkers,
} from '../../utils/skyEval.js';
import { CARD_CLASS, Swatch } from './parts.jsx';

const NEUTRAL_STROKE = 'rgba(242,231,211,0.3)';
const POINT_HOLLOW_FILL = '#221A15';

const CHIP_BASE =
  'inline-flex min-h-7 cursor-pointer items-center gap-[7px] whitespace-nowrap rounded-full border px-[11px] py-1 font-mono text-[11px] focus-visible:outline-2 focus-visible:outline-plex-gold';
const CHIP_ON = `${CHIP_BASE} border-[rgba(201,162,75,0.5)] bg-[rgba(201,162,75,0.12)] text-skyeval-version`;
const CHIP_OFF = `${CHIP_BASE} border-plex-border bg-plex-panel text-plex-text-secondary hover:border-plex-border-light hover:text-plex-text`;

/**
 * Accuracy over time: one line per model, one point per session, gold dashed version lines. Hand-rolled SVG on a
 * fixed viewBox that scales with the card; the scales live in utils/skyEval.js. Production models are drawn in
 * colour at 2px, others grey at 1px and 60%; a highlighted model replaces that emphasis. Hollow points are manual
 * runs. Tooltips are SVG titles; the same facts are in the Runs tab and the version comparison.
 *
 * @param {object} props
 * @param {Array<object>} props.sessions the served sessions
 * @param {Array<object>} props.models the served model rows
 * @param {Array<object>} props.versions the served versions
 * @param {string|null} props.highlight the highlighted model
 * @param {(model: string|null) => void} props.onHighlight sets the highlight (null clears)
 * @param {Date} props.now the right edge of the time axis
 */
export default function AccuracyChart({ sessions, models, versions, highlight, onHighlight, now }) {
  const { startMs, endMs } = useMemo(() => chartWindow(now), [now]);
  const series = useMemo(
    () => buildAccuracySeries(sessions, models, highlight, startMs, endMs),
    [sessions, models, highlight, startMs, endMs],
  );
  const markers = useMemo(() => versionMarkers(versions, startMs, endMs), [versions, startMs, endMs]);
  const ticks = useMemo(() => timeTicks(startMs, endMs), [startMs, endMs]);
  const plotRight = CHART.width - CHART.right;
  const plotBottom = CHART.height - CHART.bottom;
  const chipModels = models.filter((m) => sessions.some((s) => (s.byModel ?? []).some((b) => b.model === m.model)));

  return (
    <section className={CARD_CLASS} data-testid="sky-eval-accuracy">
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h3 className="text-sm font-semibold">Accuracy over time</h3>
        <span className="font-mono text-[11px] text-plex-text-muted">
          % of fixture ratings in band {'·'} production models in colour {'·'} gold lines are versions
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Highlight a model">
        {chipModels.map((m) => (
          <button
            key={m.model}
            type="button"
            aria-pressed={highlight === m.model}
            onClick={() => onHighlight(highlight === m.model ? null : m.model)}
            className={highlight === m.model ? CHIP_ON : CHIP_OFF}
            data-testid={`sky-eval-chart-chip-${m.model}`}
          >
            <Swatch model={m.model} />
            {modelLabel(m.model)}
          </button>
        ))}
      </div>
      <svg
        viewBox={`0 0 ${CHART.width} ${CHART.height}`}
        className="block h-auto w-full overflow-visible"
        role="img"
        aria-label="Accuracy over time, percent of fixture ratings in band per model"
        data-testid="sky-eval-accuracy-svg"
      >
        {Y_TICKS.map((v) => (
          <g key={v}>
            <line x1={CHART.left} y1={chartY(v)} x2={plotRight} y2={chartY(v)} stroke="rgba(242,231,211,0.07)" />
            <text x={CHART.left - 6} y={chartY(v) + 3} textAnchor="end" className="fill-plex-text-muted font-mono text-[10px]">{v}%</text>
          </g>
        ))}
        {markers.map((mk) => (
          <g key={mk.sha} data-testid="sky-eval-version-line">
            <line
              x1={mk.x} y1={CHART.top - 14} x2={mk.x} y2={plotBottom}
              stroke="rgba(235,217,168,0.35)" strokeDasharray="2 3"
            />
            <text x={mk.x} y={mk.labelY} textAnchor="middle" className="fill-skyeval-version font-mono text-[10px]">
              <title>{mk.title}</title>
              {mk.label}
            </text>
          </g>
        ))}
        {series.map((s) => (
          <g key={s.model} data-testid={`sky-eval-series-${s.model}`} data-emphasised={s.emphasised}>
            <polyline
              fill="none"
              stroke={s.emphasised ? modelColour(s.model) : NEUTRAL_STROKE}
              strokeWidth={s.emphasised ? 2 : 1}
              opacity={s.emphasised ? 1 : 0.6}
              points={s.polyline}
            />
            {s.points.map((p) => (
              <circle
                key={`${p.startedAt}`}
                cx={p.x}
                cy={p.y}
                r={s.emphasised ? 3.5 : 2.5}
                fill={p.manual ? POINT_HOLLOW_FILL : (s.emphasised ? modelColour(s.model) : NEUTRAL_STROKE)}
                stroke={s.emphasised ? modelColour(s.model) : NEUTRAL_STROKE}
                strokeWidth="1.5"
                data-manual={p.manual}
              >
                <title>{pointTitle(s.model, p)}</title>
              </circle>
            ))}
          </g>
        ))}
        {ticks.map((t) => (
          <text key={t.x} x={t.x} y={CHART.height - 5} textAnchor={t.anchor} className="fill-plex-text-muted font-mono text-[10px]">
            {t.label}
          </text>
        ))}
        <text x={plotRight} y={plotBottom - 6} textAnchor="end" className="fill-plex-text-muted font-mono text-[10px]">
          hollow = manual
        </text>
      </svg>
    </section>
  );
}

AccuracyChart.propTypes = {
  sessions: PropTypes.array.isRequired,
  models: PropTypes.array.isRequired,
  versions: PropTypes.array.isRequired,
  highlight: PropTypes.string,
  onHighlight: PropTypes.func.isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
};
