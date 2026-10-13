import React from 'react';
import PropTypes from 'prop-types';
import { VERDICT_LABEL, VERDICT_LABEL_SHORT } from '../../utils/windowFirstCards.js';

/**
 * A window/region verdict word in its SHORT FORM where its cell cannot hold the full one, with the
 * full word kept in the accessible name (unrated-verdict-plan.md U2, adversarial-review fixes F1,
 * F3 and F4).
 *
 * <p>Only {@code UNRATED} has a short form ("Too few" for "Too few rated" —
 * {@link VERDICT_LABEL_SHORT}, the one home of the pair); every other tier renders its plain word as
 * bare text, so a WORTH_IT cell is byte for byte what it was. The short text is
 * {@code aria-hidden} and the full word is spoken, so the shortening changes the picture and never
 * the claim.
 *
 * <p>Two modes, because two cell shapes:
 * <ul>
 *   <li>{@code "short"} — the cell is too narrow at EVERY width (the drilldown row's 62px grid
 *       track, the peek sheet's 78px flex-none cell): the short form is always the drawn one and
 *       the full word is visually hidden ({@code sr-only}).</li>
 *   <li>{@code "swap"} — the cell only runs short below a width (the window pill at 811px and
 *       below): both spans render and {@code index.css} swaps them on {@code max-width: 811px}
 *       ({@code .wf-vw-short} / {@code .wf-vw-full}), so there is no script and no measurement.</li>
 * </ul>
 *
 * @param {object} props
 * @param {string} props.tier the verdict tier ({@code WORTH_IT} / {@code MAYBE} / {@code STAND_DOWN} /
 *   {@code UNRATED} / {@code AWAITING}); an unknown tier reads as {@code AWAITING}
 * @param {'short'|'swap'} [props.mode] see above; defaults to {@code "short"}
 */
export default function VerdictWord({ tier, mode = 'short' }) {
  const full = VERDICT_LABEL[tier] || VERDICT_LABEL.AWAITING;
  const short = VERDICT_LABEL_SHORT[tier] || full;
  if (short === full) return full;
  if (mode === 'swap') {
    return (
      <>
        <span className="wf-vw-short" aria-hidden="true" data-testid="wf-vw-short">{short}</span>
        <span className="wf-vw-full" data-testid="wf-vw-full">{full}</span>
      </>
    );
  }
  return (
    <>
      <span aria-hidden="true" data-testid="wf-vw-short">{short}</span>
      <span className="sr-only" data-testid="wf-vw-full">{full}</span>
    </>
  );
}

VerdictWord.propTypes = {
  tier: PropTypes.string.isRequired,
  mode: PropTypes.oneOf(['short', 'swap']),
};
