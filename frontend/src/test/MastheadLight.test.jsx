import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import MastheadLight, { buildRuleGradient } from '../components/shared/MastheadLight.jsx';

/**
 * The masthead's light rule — the band that turns the top of the screen into the first piece of
 * forecast rather than ornament.
 *
 * <p>Two claims carry what is left of it after M3's split, and neither is "it renders". The rule's
 * positions must come from the served day rather than from a fixed palette, or the gradient is
 * decoration wearing data's clothes. And the component must stay ONE element: the labelled row, the
 * clock times and the postcode nudge all moved to {@code MastheadTickLine} — which is where their
 * tests went too — because the row grew an origin control and a search affordance, and none of
 * those is about light. The three-state contract itself is unchanged and is asserted on both sides.
 */

/** Alnwick in midsummer, near enough — the handoff's own worked example. */
const LIGHT = {
  label: 'Home · NE66 1NG',
  shortLabel: 'NE66 1NG',
  civilDawn: '05:32',
  sunrise: '06:04',
  sunset: '19:58',
  civilDusk: '20:31',
  stops: [
    { key: 'NIGHT_START', position: 0 },
    { key: 'NAUTICAL_DAWN', position: 9.2 },
    { key: 'CIVIL_DAWN', position: 19.3 },
    { key: 'SUNRISE', position: 25.28 },
    { key: 'GOLDEN_MORNING_END', position: 33.1 },
    { key: 'SOLAR_NOON', position: 50.2 },
    { key: 'GOLDEN_EVENING_START', position: 66.4 },
    { key: 'SUNSET', position: 74.86 },
    { key: 'CIVIL_DUSK', position: 84.1 },
    { key: 'NAUTICAL_DUSK', position: 93.5 },
    { key: 'NIGHT_END', position: 100 },
  ],
};

const renderLight = (props = {}) => render(<MastheadLight {...props} />);

const ruleBackground = () => screen.getByTestId('masthead-light-rule').style.background;

describe('buildRuleGradient', () => {
  it('places each stop at the position the server computed, not at a fixed one', () => {
    // The whole point of the split: colours are a design decision, positions are the day. A
    // gradient built from constants would render identically on 21 June and 21 December.
    const gradient = buildRuleGradient(LIGHT.stops);

    expect(gradient).toContain('#E8593F 25.28%'); // sunrise
    expect(gradient).toContain('#E8593F 74.86%'); // sunset
    expect(gradient).toContain('#F2E7D3 50.2%'); // solar noon
    expect(gradient.startsWith('linear-gradient(90deg,')).toBe(true);
  });

  it('draws a genuinely narrower lit band for a winter day', () => {
    // Same colours, different positions — the claim the feature makes about itself. Asserted by
    // comparing two gradients rather than by matching a literal, so it fails only if the positions
    // have stopped reaching the CSS.
    const winter = buildRuleGradient([
      { key: 'NIGHT_START', position: 0 },
      { key: 'SUNRISE', position: 34.7 },
      { key: 'SUNSET', position: 65.1 },
      { key: 'NIGHT_END', position: 100 },
    ]);

    expect(winter).toContain('#E8593F 34.7%');
    expect(winter).not.toBe(buildRuleGradient(LIGHT.stops));
  });

  it('drops a stop it has no colour for rather than painting it a default', () => {
    // A backend that grows a twelfth stop must not be able to introduce an unreviewed colour, or
    // paint a hole in the rule, just by shipping first.
    const gradient = buildRuleGradient([
      { key: 'SUNRISE', position: 25 },
      { key: 'SOMETHING_NEW', position: 40 },
      { key: 'SUNSET', position: 75 },
    ]);

    expect(gradient).toContain('25%');
    expect(gradient).toContain('75%');
    expect(gradient).not.toContain('40%');
  });

  it.each([
    ['nothing', undefined],
    ['an empty list', []],
    ['a single stop', [{ key: 'SUNRISE', position: 25 }]],
    ['positions that are not numbers', [
      { key: 'SUNRISE', position: null }, { key: 'SUNSET', position: undefined },
    ]],
  ])('falls back to the dim rule given %s', (_label, stops) => {
    // One stop is not a gradient, and a malformed payload must degrade to the honest empty picture
    // rather than to a CSS string the browser silently discards.
    expect(buildRuleGradient(stops)).toBe(
      'linear-gradient(90deg,rgba(74,58,46,0.72),rgba(74,58,46,0.2))',
    );
  });
});

describe('MastheadLight — the three states', () => {
  /**
   * ⚠️ The rule draws the SAME dim bar for `undefined` and for `null`, so this pair is the whole of
   * what this component can say about the difference — and the difference still matters, because a
   * failed fetch resolves to `undefined` and must never be read as "no postcode saved". What acts
   * on it is `MastheadTickLine`, whose own file pins the nudge. Both halves are asserted here so
   * this component cannot start collapsing them: the rule is dim in each state, and it carries no
   * nudge of its own to collapse them WITH.
   */
  it.each([
    ['unresolved', undefined],
    ['no home saved', null],
  ])('renders nothing beyond the rule itself when the day is %s', (_label, light) => {
    const { container } = renderLight({ light });

    expect(screen.getByTestId('masthead-light-rule')).toBeInTheDocument();
    // The M3 split: the times, the label and the nudge all moved to the tick line, so this
    // component is one element and a regression that re-grows a row here fails immediately.
    expect(container.firstChild).toBe(screen.getByTestId('masthead-light-rule'));
    expect(container.children).toHaveLength(1);
  });

  it.each([
    ['unresolved', undefined],
    ['no home saved', null],
  ])('leaves the rule unlit when the day is %s', (_label, light) => {
    // Never a fabricated gradient. An unlit rule is the honest picture of "we do not know where
    // you are", and it is the thing the nudge underneath explains.
    renderLight({ light });
    expect(ruleBackground()).toContain('rgba(74, 58, 46, 0.72)');
  });

  it('lights the rule from the served stops once the day resolves', () => {
    renderLight({ light: LIGHT });
    expect(ruleBackground()).toContain('25.28%');
  });

  it('hides the rule from assistive technology, since the time row is the whole answer', () => {
    // A gradient has no reading. Everything a non-sighted reader gets about today's light is in
    // the tick line's time row, which is why that row keeps its per-time event names.
    renderLight({ light: LIGHT });
    expect(screen.getByTestId('masthead-light-rule')).toHaveAttribute('aria-hidden', 'true');
  });
});
