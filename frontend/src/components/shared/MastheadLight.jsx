import React from 'react';
import PropTypes from 'prop-types';

/**
 * The golden-hour amber, as a literal rather than a token, and deliberately.
 *
 * <p>`#E0A542` already has two names in `index.css` — `--color-verdict-marginal` and its alias
 * `--color-marginal` — and both are *verdict* semantics, whose own declaration comment calls the
 * duplication "a smell that dies with v1". Golden hour is not a verdict, so borrowing either name
 * would give the hex a third meaning and outlive the cleanup that is meant to remove the second.
 * The handoff calls for no new tokens, so the accent stays local to the light — this file names two
 * rule stops with it and {@code MastheadTickLine} paints the two golden clock times, and the tick
 * line IMPORTS it rather than holding a second copy.
 */
export const GOLDEN = '#E0A542';

/**
 * Colours for the light rule, by the stop key the backend names.
 *
 * <p><b>Positions are data; these are not.</b> The server computes where each stop falls from the
 * day's real solar times, which is what makes the rule genuinely narrow in winter and widen in
 * summer; the palette never travels over the wire. A key with no entry here is dropped rather than
 * drawn in a default colour, so a future backend stop cannot paint the rule grey by surprise.
 *
 * <p><b>Three of these are byte-copies of live design tokens, and they stay literals deliberately.</b>
 * SUNRISE/SUNSET are `--color-plex-coral` and SOLAR_NOON is `--color-plex-text` — the design spec
 * names those tokens for those stops, so the duplication is real and a retune of either would
 * otherwise leave one band wearing two corals. The obvious fix, `var(--color-plex-coral)` inside the
 * gradient, is worse than the problem: both tokens live in the PRUNABLE `@theme` block (only
 * `@theme static` at index.css:80 is exempt), they survive today only because unrelated
 * `text-plex-*` classes keep them emitted, and a `linear-gradient` containing one unresolved colour
 * is invalid *in its entirety* — so a future prune would delete the whole rule rather than shift one
 * stop. `mastheadColours.test.js` parses index.css and fails if a token moves away from the literal
 * here, which buys the drift protection without putting the feature on a token that can vanish.
 */
const RULE_COLOURS = {
  NIGHT_START: '#26313F',
  NAUTICAL_DAWN: '#4A3550',
  CIVIL_DAWN: '#B4553C',
  SUNRISE: '#E8593F',
  GOLDEN_MORNING_END: GOLDEN,
  SOLAR_NOON: '#F2E7D3',
  GOLDEN_EVENING_START: GOLDEN,
  SUNSET: '#E8593F',
  CIVIL_DUSK: '#7C4A56',
  NAUTICAL_DUSK: '#2E3446',
  NIGHT_END: '#26313F',
};

/**
 * The rule with no day behind it — a flat, unlit bar. Never a fabricated gradient: an unlit rule is
 * the honest picture of "we do not know where you are", and it is what the nudge beneath explains.
 */
const DIM_RULE = 'linear-gradient(90deg,rgba(74,58,46,0.72),rgba(74,58,46,0.2))';

/**
 * Builds the rule's CSS gradient from the served stops.
 *
 * @param {Array<{key: string, position: number}>} stops the day's stops, ascending
 * @returns {string} a `linear-gradient(...)`, or the dim rule when there is not enough to draw
 */
export function buildRuleGradient(stops) {
  const known = (stops ?? [])
    .filter((s) => RULE_COLOURS[s?.key] && Number.isFinite(s?.position));
  if (known.length < 2) return DIM_RULE;
  return `linear-gradient(90deg,${known.map((s) => `${RULE_COLOURS[s.key]} ${s.position}%`).join(',')})`;
}

/**
 * The masthead's light rule — a day of light at the reader's home, drawn left to right.
 *
 * <p>It is what gives the masthead a job: the top of the screen becomes the first piece of forecast
 * rather than ornament. What labels it is the <b>tick line</b> beneath (M3) — the origin button
 * names the place at home and {@link MastheadTickLine} draws the light's own label when the reader
 * is planning from somewhere else. This component was that row's host until M3 split the two, and
 * the split is why: the row grew an origin control, a search affordance and a home button, none of
 * which is about light.
 *
 * <p><b>Three states, and `light` distinguishes them by itself.</b> `undefined` means the answer has
 * not arrived: the rule is dim, because flashing a claim at a reader on the strength of a dropped
 * request is worse than a moment of nothing. `null` means the answer arrived and there is no home
 * saved: dim rule, and the tick line's own empty state resolves it. An object is the day. Encoding
 * "unresolved" as a distinct value rather than a second `ready` prop keeps the distinction
 * impossible to drop at a call site.
 *
 * <p>⚠️ <b>The rule alone says nothing out loud.</b> It is `aria-hidden`, and it stays that way —
 * a gradient has no reading. Everything a non-sighted reader gets about today's light is in the
 * tick line's time row, which is why that row keeps its per-time event names.
 *
 * @param {object} props
 * @param {object|null} [props.light] the day's light; null when no home is saved, undefined while
 *   the answer is outstanding
 */
export default function MastheadLight({ light }) {
  return (
    <div
      data-testid="masthead-light-rule"
      aria-hidden="true"
      className="mt-[11px] sm:mt-[14px] h-1 rounded-[2px]"
      style={{ background: light ? buildRuleGradient(light.stops) : DIM_RULE }}
    />
  );
}

MastheadLight.propTypes = {
  light: PropTypes.shape({
    stops: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string.isRequired,
      position: PropTypes.number.isRequired,
    })).isRequired,
  }),
};
