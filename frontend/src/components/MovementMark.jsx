import React from 'react';
import PropTypes from 'prop-types';

/**
 * One served movement, as three nodes: the glyph, the period, and the sentence a reader hears.
 *
 * <h2>It exists because the same three nodes were hand-spelled twice in one commit</h2>
 *
 * <p>The popup's header and its prose slot both state a movement, and both got the identical
 * arrangement written out by hand at M2 — a {@code <b>} carrying the glyph and its tone, an
 * {@code aria-hidden} period beside it, and an {@code sr-only} sentence carrying the whole claim.
 * Three nodes is not a lot of markup, but the ARRANGEMENT is the accessible contract: the glyph is
 * the visible value and is hidden, so the words beside it are what a non-visual reader gets, and
 * M5's copy sweep over the movement vocabulary would otherwise have had two sites to keep in step.
 *
 * <p>⚠️ <b>The preposition is "at", never "since".</b> The delta is measured from the build BEFORE
 * the last one, so "since the last forecast run" names the one interval in which almost none of the
 * movement happened — {@code movement.js} records the reasoning, and the change line under the
 * matrix carries the age separately (one age per screen, plan §3 rule 7).
 *
 * <p>The caller decides <b>whether</b> there is anything to say: a null delta yields no chip from
 * {@code movementChip}, and a null is silence rather than a {@code —}, which would claim a measured
 * stillness where there is no basis.
 *
 * @param {object} props
 * @param {object} props.chip   a chip from {@code movementChip} — never null
 * @param {string} props.testId the test id for the wrapper
 * @param {string} [props.className] the wrapper's class
 */
export default function MovementMark({ chip, testId, className }) {
  return (
    <span data-testid={testId} className={className}>
      <b data-testid={`${testId}-mark`} data-tone={chip.tone} aria-hidden="true">{chip.mark}</b>
      <span aria-hidden="true"> at last run</span>
      <span className="sr-only">{`${chip.shortSpoken} at last run`}</span>
    </span>
  );
}

MovementMark.propTypes = {
  chip: PropTypes.shape({
    mark: PropTypes.string.isRequired,
    tone: PropTypes.string.isRequired,
    shortSpoken: PropTypes.string.isRequired,
  }).isRequired,
  testId: PropTypes.string.isRequired,
  className: PropTypes.string,
};
