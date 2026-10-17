import React from 'react';
import PropTypes from 'prop-types';
import { formatRaceTime, raceModel, raceSentence } from '../utils/dawnRace.js';

/** The marker roles drawn with a visual line-and-dot mark — see the render note below. */
const MARKED_ROLES = new Set(['maximum', 'raceEvent', 'horizon']);

/**
 * The dawn/dusk race — a horizontal timeline of the moon racing the brightening (or darkening)
 * sky to the horizon, for one lunar eclipse and one location (`lunar-eclipse-plan.md` §2.7, §5).
 *
 * <h2>The track is decoration; the sentence is the race's answer</h2>
 *
 * <p>The visual track is {@code aria-hidden} throughout — a gradient and a handful of absolutely
 * positioned marks carry no reading. {@link raceSentence} states the race's own narrative from the
 * same model the track draws from (the tide-run chart's own rule, CLAUDE.md), rendered visibly
 * rather than screen-reader-only: enters/leaves shadow, maximum with altitude, the race event
 * (sunrise/sunset), and the horizon transition (moonset/moonrise) — the plan's own five-item label
 * row, minus the bare track-start time, which names no event. ⚠️ <b>The three phase ticks
 * (nautical dawn/civil dawn/golden-hour-end, or their dusk mirror) are NOT in the sentence</b> —
 * they are the design's own secondary annotation layer (mirroring {@code Lunar Eclipse.html} §04's
 * dashed {@code .ph2} markers, subordinate to the label row), so leaving them out of the spoken
 * narrative is a scope choice, not an omission the sentence "should" have covered. An accessibility
 * review flagged this distinction by name — recorded here so it reads as a decision.
 *
 * <p>Renders nothing when {@link raceModel} finds no race to draw — defence in depth alongside the
 * caller's own gate ({@code WindowSheetDialog} mounts this only with a {@code LUNAR_ECLIPSE} topic
 * row present <b>and</b> a served {@code eclipse.race}).
 *
 * @param {object} props
 * @param {?object} props.sight the served {@code BriefingSlot.EclipseSight} for the chosen spot
 * @param {?string} props.spotName the chosen spot's name, named in the caption
 */
export default function DawnRace({ sight, spotName }) {
  const model = raceModel(sight);
  if (!model) return null;

  const dawn = sight.race === 'DAWN';
  const sentence = raceSentence(sight);
  const pct = (t) => `${(model.position(t) ?? 0) * 100}%`;
  const umbraLeft = model.position(model.umbra.from) ?? 0;
  const umbraRight = model.position(model.umbra.to) ?? 0;
  const umbraWidth = Math.max(0, umbraRight - umbraLeft);
  const hatchLeft = model.hatch ? (model.position(model.hatch.from) ?? 0) : 0;
  const hatchRight = model.hatch ? (model.position(model.hatch.to) ?? 0) : 0;
  const hatchWidth = model.hatch ? Math.max(0, hatchRight - hatchLeft) : 0;
  const skyWord = dawn ? 'the brightening sky' : 'the darkening sky';

  return (
    <div data-testid="dawn-race" className="wf-race">
      <div className="wf-race-h">
        <span className="wf-race-title">The dawn race</span>
        <span className="wf-race-cap">
          {`in shadow against ${skyWord}`}
          {spotName ? ` · ${spotName}` : ''}
        </span>
      </div>
      <div
        data-testid="dawn-race-track"
        className="wf-race-track"
        aria-hidden="true"
        style={{ background: model.gradient }}
      >
        {model.ticks.map((tick) => (
          <span
            key={tick.key}
            data-testid="dawn-race-tick"
            className="wf-race-tick"
            style={{ left: pct(tick.t) }}
          >
            <span className="wf-race-tick-label">{tick.label}</span>
          </span>
        ))}
        <span
          data-testid="dawn-race-umbra"
          className="wf-race-umbra"
          style={{ left: `${umbraLeft * 100}%`, width: `${umbraWidth * 100}%` }}
        />
        {model.hatch && (
          <span
            data-testid="dawn-race-hatch"
            className="wf-race-hatch"
            style={{ left: `${hatchLeft * 100}%`, width: `${hatchWidth * 100}%` }}
          />
        )}
        {/* Only three of the five label-row entries get a visual line-and-dot mark — the track
            start and the umbra-boundary label are TEXT-only in the design (Lunar Eclipse.html §04
            draws exactly three `.mk` elements: maximum, the race event and the horizon
            transition). */}
        {model.markers.filter((m) => MARKED_ROLES.has(m.role)).map((m) => (
          <span
            key={m.key}
            data-testid="dawn-race-marker"
            data-role={m.role}
            className="wf-race-mark"
            style={{ left: pct(m.t) }}
          />
        ))}
      </div>
      <div data-testid="dawn-race-labels" className="wf-race-labels" aria-hidden="true">
        {model.markers.map((m) => (
          <span
            key={m.key}
            data-testid="dawn-race-label"
            data-role={m.role}
            data-hide-phone={m.hidePhone ? 'true' : undefined}
            className="wf-race-label"
            style={{ left: pct(m.t) }}
          >
            <b>{formatRaceTime(m.t)}</b>
            {m.label && <span>{m.label}</span>}
          </span>
        ))}
      </div>
      <div className="wf-race-legend" aria-hidden="true">
        <span className="wf-race-legend-item">
          <i className="wf-race-legend-umbra" />
          in the umbra
        </span>
        <span className="wf-race-legend-item">
          <i className="wf-race-legend-hatch" />
          below the horizon
        </span>
      </div>
      {/* The whole accessible answer — visible, not screen-reader-only, since it is short and the
          track beside it says nothing a reader could otherwise use. */}
      <p data-testid="dawn-race-sentence" className="wf-race-sentence">{sentence}</p>
    </div>
  );
}

DawnRace.propTypes = {
  sight: PropTypes.shape({
    race: PropTypes.oneOf(['DAWN', 'DUSK']),
    moonAltAtMax: PropTypes.number,
    moonAzCardinal: PropTypes.string,
    maximum: PropTypes.string,
    umbraStart: PropTypes.string,
    umbraEnd: PropTypes.string,
    moonset: PropTypes.string,
    moonrise: PropTypes.string,
    setsInShadow: PropTypes.bool,
    risesInShadow: PropTypes.bool,
    stops: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string,
      time: PropTypes.string,
    })),
  }),
  spotName: PropTypes.string,
};
