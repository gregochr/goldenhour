/**
 * The Plan heat card's control is a stretched button holding only its spoken name
 * (`docs/engineering/a11y-heat-card-plan.md`, WCAG 2.5.3 Label in Name).
 *
 * <p>The card used to be a `<button>` whose visible spans were all `aria-hidden`; axe's
 * `label-content-name-mismatch` counts such children as visible text, glues them together and wants the
 * run inside the name, which no sentence a person would want read aloud does. The card is now a `div`
 * holding one transparent `.wf-hc-hit` button whose ONLY content is the name sentence. This file pins the
 * parts of that split a screenshot cannot: axe's own rule, the conformance the rule stands for (axe finds
 * the rule INAPPLICABLE once the control has no visible text of its own, so the words are checked here
 * directly), the single owner of the name's id, and the keyboard and pointer routes into the control.
 *
 * <p>jsdom applies no stylesheet, so the `sr-only` text counts as visible to axe here and the rule runs
 * (and passes) rather than being inapplicable; the assertion is on VIOLATIONS either way.
 */
import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeEach, afterEach, vi,
} from 'vitest';
import {
  act, cleanup, render, screen, fireEvent, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import WindowFirstHeatStrip from '../components/WindowFirstHeatStrip.jsx';
import { labelInNameResult } from './labelInNameCheck.js';

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => null),
  };
});
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

const TODAY = '2026-10-05';
const TOMORROW = '2026-10-06';
const SUNSET = `${TODAY}:SUNSET`;

const card = (date, targetType, over = {}) => ({
  key: `${date}:${targetType}`,
  date,
  targetType,
  dow: 'Mon',
  sunrise: targetType === 'SUNRISE',
  label: `Tonight ${targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset'}`,
  time: '17:41',
  verdict: 'WORTH_IT',
  verdictLabel: 'Worth it',
  bestRating: 4,
  pickKind: null,
  away: false,
  confidence: 'high',
  pool: [],
  reachMeasured: true,
  bestReach: null,
  badges: [],
  ...over,
});

const spot = {
  id: 1, name: 'Whitby', lat: 54.49, lng: -0.61, regionName: 'North York Moors & Coast',
  rid: 'North York Moors & Coast', skySubject: true, bortleClass: 3, scores: [4],
};
const SPOTS = [spot];
const pointsFor = (cards) => new Map(cards.filter((c) => !c.away).map((c) => [c.key, [{
  id: 1, name: 'Whitby', lat: 54.49, lng: -0.61, rid: 'North York Moors & Coast', r: [4],
}]]));

let originalGetContext;
let originalScroll;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  originalScroll = Element.prototype.scrollIntoView;
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  cleanup();
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  if (originalScroll) Element.prototype.scrollIntoView = originalScroll;
  else delete Element.prototype.scrollIntoView;
  vi.clearAllMocks();
});

async function renderStrip(cards, props = {}) {
  let view;
  await act(async () => {
    view = render(
      <WindowFirstHeatStrip
        cards={cards}
        pointSets={pointsFor(cards)}
        spots={SPOTS}
        todayStr={TODAY}
        onOpenWindow={() => {}}
        {...props}
      />,
    );
  });
  return view;
}

/** The variants the plan names: a rated card, both picks, an unscored card and an Ask-highlighted one. */
const VARIANTS = [
  ['a rated card', [card(TODAY, 'SUNSET')], {}],
  ['a sunrise card', [card(TOMORROW, 'SUNRISE', { time: '07:12' })], {}],
  ['a best pick', [card(TODAY, 'SUNSET', { pickKind: 'best' })], {}],
  ['an also-good pick', [card(TODAY, 'SUNSET', { pickKind: 'also' })], {}],
  ['an unscored card', [card(TODAY, 'SUNSET', { bestRating: null })], {}],
  ['an Ask-highlighted card', [card(TODAY, 'SUNSET', { pickKind: 'best' })], { highlightKeys: new Map([[SUNSET, 2]]) }],
];

describe('WCAG 2.5.3, by axe\'s own rule (label-content-name-mismatch)', () => {
  it.each(VARIANTS)('reports no violation on %s', async (_name, cards, props) => {
    await renderStrip(cards, props);
    // The CARD, not the button: a regression that puts the visible spans back inside one control makes the
    // card's own button the offender, and the card is the element that contains whichever it is.
    const result = await labelInNameResult(screen.getByTestId('wf-heat-card'));
    expect(result.violations).toBe(0);
  });
});

describe('WCAG 2.5.3, by the words (the rule is inapplicable in a browser, so this keeps what it stands for)', () => {
  const nameOf = (button) => document.getElementById(button.getAttribute('aria-labelledby')).textContent.toLowerCase();

  it.each(VARIANTS)('names the visible time, verdict, sun word and pick word of %s', async (_name, cards, props) => {
    await renderStrip(cards, props);
    const control = screen.getByTestId('wf-heat-card-button');
    const name = nameOf(control);
    const visible = ['wf-heat-time', 'wf-heat-verdict', 'wf-heat-sun', 'wf-heat-legend']
      .map((id) => screen.queryByTestId(id))
      .filter(Boolean)
      .map((el) => el.textContent.trim().toLowerCase());
    // The time, the verdict word and the sun word are on EVERY card; the pick word only on a pick.
    expect(visible.length).toBeGreaterThanOrEqual(3);
    visible.forEach((text) => expect(name, `"${text}"`).toContain(text));
    // And the same through the accessibility tree, not only the IDREF's text.
    expect(control).toHaveAccessibleName(new RegExp(visible[0].replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
  });
});

describe('the name has one owner', () => {
  it('puts the sentence only in the button: exactly one element carries its id, and the card has no other text', async () => {
    await renderStrip([card(TODAY, 'SUNSET', { pickKind: 'best' }), card(TOMORROW, 'SUNRISE')]);
    const cards = screen.getAllByTestId('wf-heat-card');
    expect(cards).toHaveLength(2);
    cards.forEach((el) => {
      const button = within(el).getByTestId('wf-heat-card-button');
      const id = button.getAttribute('aria-labelledby');
      expect(document.querySelectorAll(`[id="${id}"]`)).toHaveLength(1);
      expect(button.contains(document.getElementById(id)), 'the sentence is inside the button').toBe(true);
      // Nothing is read outside the button and the `aria-hidden` spans: take both away and no text is left.
      const rest = el.cloneNode(true);
      rest.querySelectorAll('[data-testid="wf-heat-card-button"], [aria-hidden="true"]').forEach((n) => n.remove());
      expect(rest.textContent.trim()).toBe('');
      // The button's own content is the sentence and nothing else.
      expect(button.textContent).toBe(document.getElementById(id).textContent);
      expect(button.children).toHaveLength(1);
    });
  });

  it('keeps an away cell exactly as it was: its sentence in the body, no button, no control mark', async () => {
    await renderStrip([card(TODAY, 'SUNSET', { away: true })]);
    const away = screen.getByTestId('wf-heat-card');
    expect(away).toHaveAttribute('data-away', 'true');
    expect(away).not.toHaveAttribute('data-control');
    expect(within(away).queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryByTestId('wf-heat-card-button')).toBeNull();
    expect(away.querySelectorAll('.sr-only[id^="wf-heat-name-"]')).toHaveLength(1);
  });
});

describe('the routes into the control', () => {
  it('one Tab stop per control card, never the away cell, and Enter and Space each open the window once', async () => {
    const onOpenWindow = vi.fn();
    const cards = [card(TODAY, 'SUNSET'), card(TOMORROW, 'SUNRISE'), card(TOMORROW, 'SUNSET', { away: true })];
    await renderStrip(cards, { onOpenWindow });
    const user = userEvent.setup();

    // One stop per control card: tabbable things inside a card are its one button and nothing else, and the
    // away cell holds none. (The strip's own lens bar and tiles are tab stops too, so this counts by card.)
    const tabbable = (el) => [...el.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')]
      .filter((n) => n.getAttribute('tabindex') !== '-1');
    const rendered = screen.getAllByTestId('wf-heat-card');
    expect(rendered).toHaveLength(3);
    rendered.forEach((el) => {
      if (el.hasAttribute('data-away')) expect(tabbable(el)).toHaveLength(0);
      else expect(tabbable(el)).toEqual([within(el).getByTestId('wf-heat-card-button')]);
    });
    const buttons = screen.getAllByTestId('wf-heat-card-button');
    expect(buttons).toHaveLength(2);
    // And Tab really does walk onto them, in order, with the card `div`s skipped.
    const stops = [];
    for (let i = 0; i < 80 && stops.length < 2; i += 1) {
      await user.tab();
      if (buttons.includes(document.activeElement)) stops.push(document.activeElement);
    }
    expect(stops).toEqual(buttons);
    const first = buttons[0];
    first.focus();
    await user.keyboard('{Enter}');
    expect(onOpenWindow).toHaveBeenCalledTimes(1);
    await user.keyboard(' ');
    expect(onOpenWindow).toHaveBeenCalledTimes(2);
  });

  it('a pointer click on the card focuses its button before opening the window, so focus has an address to return to', async () => {
    const onOpenWindow = vi.fn(() => { expect(document.activeElement).toBe(screen.getByTestId('wf-heat-card-button')); });
    await renderStrip([card(TODAY, 'SUNSET')], { onOpenWindow });
    const user = userEvent.setup();

    expect(document.body).toHaveFocus();
    await user.click(screen.getByTestId('wf-heat-time'));

    expect(onOpenWindow).toHaveBeenCalledTimes(1);
    expect(onOpenWindow).toHaveBeenCalledWith(SUNSET);
    expect(screen.getByTestId('wf-heat-card-button')).toHaveFocus();
  });

  it('a click on the card itself opens it once', async () => {
    const onOpenWindow = vi.fn();
    await renderStrip([card(TODAY, 'SUNSET')], { onOpenWindow });
    fireEvent.click(screen.getByTestId('wf-heat-card'));
    expect(onOpenWindow).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('wf-heat-card-button')).toHaveFocus();
  });
});

describe('the hit button\'s stylesheet (jsdom applies none, so it is read as text, like askPlanCss.test.js)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const body = (selector) => {
    const at = css.indexOf(`\n${selector} {`);
    expect(at, `${selector} must have a rule`).toBeGreaterThanOrEqual(0);
    return css.slice(at, css.indexOf('}', at));
  };

  it('stretches over the card, transparent, and never takes a pointer (the card keeps its tooltips, legend and cursor)', () => {
    const rule = body('.wf-hc-hit');
    expect(rule).toMatch(/position:\s*absolute/);
    expect(rule).toMatch(/inset:\s*0\b/);
    expect(rule).toMatch(/pointer-events:\s*none/);
    expect(rule).toMatch(/background:\s*transparent/);
    expect(rule).toMatch(/cursor:\s*inherit/);
  });

  it('draws the focus ring itself, in gold at 2px', () => {
    const rule = body('.wf-hc-hit:focus-visible');
    expect(rule).toMatch(/outline:\s*2px solid var\(--color-plex-gold\)/);
  });

  it('leaves no `button.wf-hc` selector behind: the control card is a `div`', () => {
    expect(css).not.toMatch(/button\.wf-hc/);
  });
});
