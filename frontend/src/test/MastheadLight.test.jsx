import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import MastheadLight, { buildRuleGradient } from '../components/shared/MastheadLight.jsx';

/**
 * The masthead's light rule — the band that turns the top of the screen into the first piece of
 * forecast rather than ornament.
 *
 * <p>Three claims carry the feature and none of them is "it renders". The rule's positions must
 * come from the served day rather than from a fixed palette, or the gradient is decoration wearing
 * data's clothes. The row must never be unlabelled, for the same reason. And the three states —
 * unresolved, no-home, resolved — must stay distinguishable, because collapsing the first two
 * flashes "set a postcode" at every reader who already has one.
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

const renderLight = (props = {}) => render(
  <MastheadLight onSetPostcode={vi.fn()} {...props} />,
);

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
  it('holds the row height and says nothing while the answer is outstanding', () => {
    // `undefined` is "not asked yet". Showing the nudge here would flash "set a postcode" at every
    // reader who has one, on every load — which is why this is a distinct state and not just
    // "falsy light".
    renderLight({ light: undefined });

    expect(screen.getByTestId('masthead-light-pending')).toBeInTheDocument();
    expect(screen.queryByTestId('masthead-light-nudge')).toBeNull();
    expect(screen.queryByTestId('masthead-light-times')).toBeNull();
  });

  it('offers the nudge once the answer is in and there is no home saved', () => {
    renderLight({ light: null });

    expect(screen.getByTestId('masthead-light-nudge')).toBeInTheDocument();
    expect(screen.queryByTestId('masthead-light-pending')).toBeNull();
    expect(screen.getByText(/Set your home postcode for your light times/)).toBeInTheDocument();
  });

  it('renders the labelled time row once the day resolves', () => {
    renderLight({ light: LIGHT });

    expect(screen.getByTestId('masthead-light-times')).toBeInTheDocument();
    expect(screen.queryByTestId('masthead-light-nudge')).toBeNull();
    expect(screen.queryByTestId('masthead-light-pending')).toBeNull();
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
});

describe('MastheadLight — the labelled row', () => {
  it('always names whose light it is drawing', () => {
    // The label is mandatory: the UK spread between Cornwall and Northumberland is 20–30 minutes,
    // which is honest at this precision only for as long as the row says whose day it is. Both
    // forms render — the widths choose between them in CSS — so both must be present.
    renderLight({ light: LIGHT });
    const row = screen.getByTestId('masthead-light-times');

    expect(within(row).getByText('Home · NE66 1NG')).toBeInTheDocument();
    expect(within(row).getByText('NE66 1NG')).toBeInTheDocument();
  });

  /**
   * Every long/short pair in this component, and why presence alone does not cover them.
   *
   * These three pairs each render BOTH forms and let CSS pick one. `getByText` reads text nodes and
   * no class attribute, so emptying, deleting or SWAPPING the two visibility classes changes
   * nothing any presence assertion can see — an adversarial review found all three unpinned. The
   * concrete result of the swap: at 375px the label renders "NE66 1NGHome · NE66 1NG" inside a
   * `whitespace-nowrap` span beside two clock times, and the desktop gets the bare postcode.
   *
   * jsdom resolves no media query, so the class pair IS the observable. Asserted as an exact,
   * complementary pair — one hidden below `sm`, one hidden from `sm` up — because asserting only
   * that both carry "some visibility class" is what let this through.
   */
  it.each([
    ['location label', () => screen.getByText('NE66 1NG'), () => screen.getByText('Home · NE66 1NG'), LIGHT],
    ['nudge sentence',
      () => screen.getByText('Set a postcode for light and drive times.'),
      () => screen.getByText(/Set your home postcode for your light times/), null],
    ['nudge link text', () => screen.getByText('Set'), () => screen.getByText('Set postcode'), null],
  ])('shows exactly one of the %s pair at any width', (_label, phoneForm, wideForm, light) => {
    renderLight({ light });

    // The phone form must be hidden from `sm` up, and carry no class that hides it below `sm`.
    expect(phoneForm().className).toContain('sm:hidden');
    expect(phoneForm().className.split(/\s+/)).not.toContain('hidden');

    // The wide form must be hidden below `sm`, and shown from `sm` up.
    expect(wideForm().className.split(/\s+/)).toContain('hidden');
    expect(wideForm().className).toContain('sm:inline');
  });

  it('shows the two golden times and the two blue ones', () => {
    renderLight({ light: LIGHT });

    expect(screen.getAllByTestId('masthead-light-golden').map((n) => n.textContent))
      .toEqual(['06:04 sunrise golden', '19:58 sunset golden']);
    expect(screen.getAllByTestId('masthead-light-blue').map((n) => n.textContent))
      .toEqual(['05:32 dawn blue', '20:31 dusk blue']);
  });

  it('drops only the blue pair at narrow widths, keeping the goldens at every size', () => {
    // The design shows four times in a browser and two on a tablet and a phone. jsdom resolves no
    // media query, so the responsive classes are the only place that is observable — and asserting
    // them is what stops the pair being dropped outright or the goldens being hidden by mistake.
    renderLight({ light: LIGHT });

    screen.getAllByTestId('masthead-light-blue')
      .forEach((n) => expect(n.className).toContain('hidden lg:inline'));
    screen.getAllByTestId('masthead-light-golden')
      .forEach((n) => expect(n.className).not.toContain('hidden'));
  });

  it('announces the EVENT, not just the kind — the row is the whole accessible answer', () => {
    // The rule above is aria-hidden, so this row is all a screen reader gets. The kind alone does
    // not answer it: "golden" is the same word for sunrise and for sunset, so the announcement was
    // "05:32 blue, 06:04 golden, 19:58 golden, 20:31 blue" and the only thing separating morning
    // from evening was DOM order — exactly the positional cue the hidden gradient was carrying.
    renderLight({ light: LIGHT });

    const named = ['dawn', 'sunrise', 'sunset', 'dusk'].map((event) => {
      const span = screen.getByText(event);
      expect(span.className, `${event} must reach AT at every width`).toContain('sr-only');
      expect(span.className, `${event} must not be width-gated`).not.toContain('sm:not-sr-only');
      return event;
    });
    expect(named).toHaveLength(4);
  });

  it('keeps the visible kind word out of the accessible name, so it cannot double up', () => {
    // The visible label stays the KIND — on screen the amber and the left-to-right order already
    // say which is which. It is aria-hidden so a screen reader hears "06:04 sunrise", not
    // "06:04 sunrise golden".
    renderLight({ light: LIGHT });
    const [morning] = screen.getAllByTestId('masthead-light-golden');
    const kindWord = within(morning).getByText('golden');

    expect(kindWord).toHaveAttribute('aria-hidden', 'true');
    expect(kindWord.className).toContain('hidden lg:inline'.split(' ')[0]);
    expect(kindWord.className).toContain('sm:inline');
  });

  it('paints the golden times amber and weights them, so they read before the blues', () => {
    // The row's only visual hierarchy. Asserted on the resolved colour rather than on a class,
    // because the amber is an inline style — deliberately, so the gradient and the row cannot
    // drift onto two different literals for the same accent.
    renderLight({ light: LIGHT });

    screen.getAllByTestId('masthead-light-golden').forEach((n) => {
      expect(n).toHaveStyle({ color: 'rgb(224, 165, 66)' });
      expect(n.className).toContain('font-medium');
    });
    screen.getAllByTestId('masthead-light-blue').forEach((n) => {
      expect(n.style.color).toBe('');
      expect(n.className).not.toContain('font-medium');
    });
  });

  it('reserves the row height from the same constant the row is built from', () => {
    // The placeholder's only job is that the page does not shift when the answer lands, and the two
    // rows measure equal at 28.5px in the browser. Asserted as SET CONTAINMENT rather than as a
    // literal checklist: the earlier version looked for seven named classes in both, which cannot
    // see a layout-affecting eighth added to one of them — the exact drift its own comment claimed
    // to prevent. Every class the placeholder carries must also be on the time row, whatever they
    // are, which is what the shared ROW_METRICS constant guarantees.
    const { rerender } = render(<MastheadLight light={LIGHT} onSetPostcode={vi.fn()} />);
    const rowClasses = new Set(screen.getByTestId('masthead-light-times').className.split(/\s+/));

    rerender(<MastheadLight light={undefined} onSetPostcode={vi.fn()} />);
    const pendingClasses = screen.getByTestId('masthead-light-pending').className.split(/\s+/);

    expect(pendingClasses.length).toBeGreaterThan(4);
    pendingClasses.forEach((cls) => {
      expect(rowClasses, `the time row must also carry "${cls}"`).toContain(cls);
    });
  });

  it('never labels solar noon', () => {
    // The pale band in the middle of the gradient already says midday, and the row's one line is
    // not spent on the least useful light of the day. The stop still exists — the label does not.
    renderLight({ light: LIGHT });

    expect(screen.getByTestId('masthead-light-times').textContent).not.toMatch(/noon/i);
    expect(buildRuleGradient(LIGHT.stops)).toContain('#F2E7D3 50.2%');
  });

  it('hides the rule from assistive technology, since the row is the whole answer', () => {
    renderLight({ light: LIGHT });
    expect(screen.getByTestId('masthead-light-rule')).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('MastheadLight — the nudge', () => {
  it('opens the postcode setting when the link is pressed', () => {
    const onSetPostcode = vi.fn();
    renderLight({ light: null, onSetPostcode });

    fireEvent.click(screen.getByTestId('masthead-set-postcode'));
    expect(onSetPostcode).toHaveBeenCalledTimes(1);
  });

  it('keeps one accessible name at both widths, though the visible text shortens', () => {
    // The phone renders "Set", which is not a self-explanatory control. The label is the long
    // form everywhere and contains the short one, so nothing visible is missing from what is
    // announced — and the name stays stable for anyone locating it by voice.
    renderLight({ light: null });
    const button = screen.getByRole('button', { name: 'Set postcode' });

    expect(within(button).getByText('Set postcode')).toBeInTheDocument();
    expect(within(button).getByText('Set')).toBeInTheDocument();
  });

  it('shortens the sentence for a phone as well as the link', () => {
    renderLight({ light: null });
    expect(screen.getByText('Set a postcode for light and drive times.')).toBeInTheDocument();
  });
});
