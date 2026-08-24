import React from 'react';
import PropTypes from 'prop-types';

/**
 * The Plan pane's score bar — a label, a number, and a filled track.
 *
 * <p>Extracted from {@code WindowSpotPeek}'s original module-private {@code PeekScoreBar} (location
 * sheet superset plan, Phase 1) so {@code LocationFourDaySheet} can render the same bar in its
 * expanded row body. The plan's principle is that each step deeper into the drill-down shows a
 * SUPERSET of what the step above it showed for that location + event, and the peek — the shallower
 * surface — already carried these bars; the sheet did not. {@code WindowSpotPeek}'s own tests pass
 * unedited against this extraction, which is this project's own standard for proving a move was pure
 * (the {@code solarDayGeometry} extraction set it).
 *
 * <p>The two gradients are quoted from {@code MarkerPopupContent.jsx}'s {@code PopupScoreRow} rather
 * than imported — they are module-private there, and importing that ~1,300-line component into the
 * Plan pane's chunk to fetch two strings would pull its whole module graph along.
 *
 * <p><b>The number is not tinted</b>, where the map popup ramps it through a three-stop colour scale.
 * One deviation, carried over from the peek: nothing here is encoded by colour alone (SC 1.4.1) — the
 * bar's length carries the value and the number states it.
 *
 * @param {object}  props
 * @param {string}  props.label the measurement's own name, as the map popup prints it
 * @param {number}  props.score 0–100
 * @param {string}  props.testId per-bar test id
 * @param {string}  props.fill  the track gradient
 * @param {string}  [props.labelClassName] an extra class for the label row, appended rather than
 *        baked in — a caller-scoped dimming hook (`LocationFourDaySheet`'s `.wf-loc-score-label`)
 *        does not belong on this component unconditionally, since the peek is a different context
 *        (a portalled, `aria-hidden` tooltip) with no `.wf-loc-row[data-dim]` ancestor to key off.
 *        Absent by default, so `WindowSpotPeek` renders exactly what it always has.
 */
export default function PlanScoreBar({ label, score, testId, fill, labelClassName = '' }) {
  const pct = Math.min(100, Math.max(0, score));
  return (
    <div data-testid={testId} data-score={score} style={{ marginTop: '6px' }}>
      <div
        className={`flex items-center justify-between font-mono${labelClassName ? ` ${labelClassName}` : ''}`}
        style={{ fontSize: '10px', marginBottom: '3px' }}
      >
        <span className="text-plex-text-secondary">{label}</span>
        <span className="text-plex-text" style={{ fontWeight: 600 }}>{pct}</span>
      </div>
      <div className="wf-peek-bar" style={{ background: fill }}>
        <span className="wf-peek-bar-rest" style={{ width: `${100 - pct}%` }} />
      </div>
    </div>
  );
}

PlanScoreBar.propTypes = {
  label: PropTypes.string.isRequired,
  score: PropTypes.number.isRequired,
  testId: PropTypes.string.isRequired,
  fill: PropTypes.string.isRequired,
  labelClassName: PropTypes.string,
};

/** Quoted from `MarkerPopupContent`'s `FIERY_FILL` / `GOLDEN_FILL` — see the module doc above. */
export const FIERY_FILL = 'linear-gradient(90deg, #B5A06A, #E0A542 45%, #C8452F)';
export const GOLDEN_FILL = 'linear-gradient(90deg, #6B6453, #C88E2E 45%, #F5C518)';
