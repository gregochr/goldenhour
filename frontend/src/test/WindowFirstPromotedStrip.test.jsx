import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstPromotedStrip from '../components/WindowFirstPromotedStrip.jsx';
import { buildPromotedStrip } from '../utils/windowFirstPromoted.js';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildPaneItems } from '../utils/windowFirstAway.js';

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

/** A badge as `BriefingWindow.Badge` serialises one. */
function badge(overrides = {}) {
  return {
    type: 'SNOW_TOPS',
    label: 'Snow on the fells',
    detail: 'The tops held their snow overnight.',
    facts: [{ key: 'snow line', value: '~850 m', dir: null, emphasis: true, optional: false }],
    eventTime: '05:31',
    rarityRank: 8,
    ...overrides,
  };
}

/**
 * The shape `AuroraHotTopicStrategy` really emits — label "Aurora possible", headline fact
 * `HotTopicFact.metric(null, …)`, i.e. **keyless**. An invented `{ key: 'Kp' }` here is what let a
 * duplicate-label defect through the whole suite once; fixtures stay shaped like their producer.
 */
const AURORA = badge({
  type: 'AURORA',
  label: 'Aurora possible',
  rarityRank: 4,
  facts: [{
    key: null,
    value: 'Kp 5 · glow reaches ~57°N and north',
    dir: null,
    emphasis: true,
    optional: false,
  }],
});

const KING_TIDE = badge({
  type: 'KING_TIDE',
  label: 'King tide',
  rarityRank: 3,
  facts: [{ key: 'high water', value: '5.8 m', dir: null, emphasis: true, optional: false }],
});

/**
 * A strip descriptor built through the real deriver, so this file's fixture can never drift from
 * `buildPromotedStrip`'s output — the idiom `WindowAttributeRow.test.jsx` established.
 *
 * <p>Two windows by default, with the coincidence on the SECOND, because that is the state the
 * strip exists for: a promoted window that is not the one directly beneath it. Callers wanting the
 * adjacent case pass a single window.
 */
function stripFor(spec) {
  const byDate = new Map();
  for (const [date, targetType, window] of spec) {
    if (!byDate.has(date)) byDate.set(date, []);
    byDate.get(date).push({
      targetType,
      regions: [],
      unregioned: [],
      window: { verdict: 'WORTH_IT', badges: [], ...window },
    });
  }
  const days = [...byDate.entries()].map(([date, eventSummaries]) => ({ date, eventSummaries }));
  const upcoming = spec.map(([date, targetType]) => ({ date, targetType }));
  const cards = buildWindowCards(upcoming, days, TODAY, TOMORROW, new Set(), new Map());
  return buildPromotedStrip(buildPaneItems(upcoming, cards, new Set(), []));
}

/**
 * The rich fixture: a promoted window that is NOT adjacent, carrying a time, three topics and a
 * figure on each.
 *
 * <p>Deliberately separate from the plain one below, and deliberately the one every naming
 * assertion runs against. A bare fixture is the shape where a label can delete nothing — that is
 * exactly how a real regression got through review on `HeatmapGrid`, where naming the cells
 * silently dropped the star rating and the confidence marker because the default fixture had
 * neither.
 */
const RICH = () => stripFor([
  [TODAY, 'SUNSET', { badges: [] }],
  [TOMORROW, 'SUNRISE', {
    badges: [badge(), AURORA, KING_TIDE],
    topRarityRank: 3,
    eventTime: `${TOMORROW}T05:14:00`,
  }],
]);

/** The adjacent case: one window, the coincidence on it, so no route is offered. */
const ADJACENT = () => stripFor([
  [TODAY, 'SUNSET', { badges: [badge(), AURORA], topRarityRank: 4 }],
]);

describe('WindowFirstPromotedStrip — the pair it names', () => {
  it('names every topic of the coincidence, rarest first', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    expect(screen.getAllByTestId('window-first-promo-topic').map((n) => n.textContent))
      .toEqual(['King tide', 'Aurora possible', 'Snow on the fells']);
  });

  // The `×` is punctuation between two named things, not a word. Marked decorative for the same
  // reason as the verdict badge's `◎` and the expander's caret — a screen reader announcing "times"
  // between two topic names would be reading a symbol as a relationship.
  it('marks the separator decorative rather than announcing it', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    const separators = screen.getAllByTestId('window-first-promo-sep');
    expect(separators).toHaveLength(2);
    separators.forEach((sep) => expect(sep).toHaveAttribute('aria-hidden', 'true'));
  });

  // ⚠️ The fix this pins is one character of whitespace, and it is invisible to `textContent`.
  // JSX drops the newline-only whitespace between sibling elements, so while ` × ` lived WHOLLY
  // inside the `aria-hidden` span it was the only boundary between two topic names — prune the
  // hidden subtree, as assistive tech does, and a browse-mode buffer reads "King tideAurora
  // possible". Two assertions, because either alone passes with the defect restored: the separator's
  // own text must carry no padding (proving the spaces are OUTSIDE it), and the kicker must still
  // read with spaces (proving they were not simply deleted).
  it('keeps the space between topic names outside the decorative separator', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    screen.getAllByTestId('window-first-promo-sep')
      .forEach((sep) => expect(sep.textContent).toBe('\u00d7'));
    expect(screen.getByTestId('window-first-promo-kicker').textContent)
      .toBe('King tide \u00d7 Aurora possible \u00d7 Snow on the fells');
  });

  it('renders no separator before the first topic', () => {
    render(<WindowFirstPromotedStrip strip={ADJACENT()} />);
    // Two topics, one separator — not two, and not one before each.
    expect(screen.getAllByTestId('window-first-promo-topic')).toHaveLength(2);
    expect(screen.getAllByTestId('window-first-promo-sep')).toHaveLength(1);
  });

  it('names the window and its time', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    expect(screen.getByTestId('window-first-promo-when')).toHaveTextContent('Tomorrow sunrise');
    expect(screen.getByTestId('window-first-promo-time')).toHaveTextContent('06:14');
  });

  it('omits the time element entirely when the window carries none', () => {
    const strip = stripFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [badge(), AURORA] }],
    ]);
    render(<WindowFirstPromotedStrip strip={strip} />);
    expect(screen.queryByTestId('window-first-promo-time')).toBeNull();
  });
});

describe('WindowFirstPromotedStrip — the channel accent', () => {
  // Both halves. A one-sided assertion goes green with the channel derivation deleted, because
  // every strip would then carry the default tide accent and the tide case would still pass.
  it('publishes the rarest topic\'s channel, and not another topic\'s', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    const strip = screen.getByTestId('window-first-promo');
    expect(strip).toHaveAttribute('data-channel', 'tide');
    expect(strip).not.toHaveAttribute('data-channel', 'snow');
  });

  it('publishes the snow channel when snow is the rarest of the pair', () => {
    const nlc = badge({ type: 'NLC', label: 'NLC', rarityRank: 14 });
    render(<WindowFirstPromotedStrip strip={stripFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [nlc, badge()], topRarityRank: 8 }],
    ])} />);
    expect(screen.getByTestId('window-first-promo'))
      .toHaveAttribute('data-channel', 'snow');
  });

  // An unrecognised kind takes the neutral frame rather than the nearest channel — a channel colour
  // names a claim, so a wrong one is a wrong claim.
  it('publishes the neutral channel for a topic kind it does not recognise', () => {
    const mystery = badge({ type: 'MYSTERY', label: 'Something new', rarityRank: 1 });
    render(<WindowFirstPromotedStrip strip={stripFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [mystery, badge()], topRarityRank: 1 }],
    ])} />);
    expect(screen.getByTestId('window-first-promo'))
      .toHaveAttribute('data-channel', 'plain');
  });
});

describe('WindowFirstPromotedStrip — the measured figures', () => {
  it('draws one figure per topic, each labelled and valued', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    const figures = screen.getAllByTestId('window-first-promo-figure');
    expect(figures.map((f) => f.textContent))
      .toEqual(['high water5.8 m', 'Kp 5 · glow reaches ~57°N and north', 'snow line~850 m']);
  });

  // Aurora and NLC both emit a keyless headline fact. Falling back to the topic's own label printed
  // that label twice on one small element — once in the kicker, once as the figure's lead-in.
  it('gives a keyless fact no lead-in, so no topic name is printed twice', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    const figures = screen.getAllByTestId('window-first-promo-figure');
    const aurora = figures.find((f) => f.textContent.includes('glow reaches'));
    expect(aurora.textContent).toBe('Kp 5 \u00b7 glow reaches ~57\u00b0N and north');
    expect(aurora.textContent).not.toContain('Aurora possible');
    // And the topic is still named — in the kicker, exactly once.
    expect(screen.getAllByTestId('window-first-promo-topic').map((n) => n.textContent))
      .toContain('Aurora possible');
  });

  it('draws no figure for a topic carrying no facts, while still naming it', () => {
    const factless = badge({ type: 'EQUINOX', label: 'Equinox', rarityRank: 2, facts: [] });
    render(<WindowFirstPromotedStrip strip={stripFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [factless, badge()], topRarityRank: 2 }],
    ])} />);
    expect(screen.getAllByTestId('window-first-promo-topic').map((n) => n.textContent))
      .toEqual(['Equinox', 'Snow on the fells']);
    expect(screen.getAllByTestId('window-first-promo-figure')).toHaveLength(1);
  });

  // The band is omitted, not emptied: an empty flex row would still spend its padding, and a strip
  // with a blank band under its header reads as a figure that failed to load.
  it('omits the figure band entirely when no topic carries a fact', () => {
    const a = badge({ type: 'EQUINOX', label: 'Equinox', rarityRank: 2, facts: [] });
    const b = badge({ type: 'SUPERMOON', label: 'Supermoon', rarityRank: 1, facts: [] });
    render(<WindowFirstPromotedStrip strip={stripFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [a, b], topRarityRank: 1 }],
    ])} />);
    expect(screen.queryByTestId('window-first-promo-figures')).toBeNull();
  });

  it('never renders a null, undefined or NaN into a figure', () => {
    const keyless = badge({
      facts: [{ key: null, value: '120 m below the tops', dir: null, emphasis: true, optional: true }],
    });
    render(<WindowFirstPromotedStrip strip={stripFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [keyless, AURORA], topRarityRank: 4 }],
    ])} />);
    expect(screen.getByTestId('window-first-promo').textContent)
      .not.toMatch(/null|undefined|NaN/);
  });
});

describe('WindowFirstPromotedStrip — the route into the list', () => {
  it('offers a route naming the window, and hands back that window\'s key', () => {
    const onOpenWindow = vi.fn();
    render(<WindowFirstPromotedStrip strip={RICH()} onOpenWindow={onOpenWindow} />);
    const go = screen.getByTestId('window-first-promo-go');
    fireEvent.click(go);
    expect(onOpenWindow).toHaveBeenCalledWith(`${TOMORROW}:SUNRISE`);
  });

  // No `aria-label` anywhere on this component, so the name IS the visible text and WCAG 2.5.3's
  // label-in-name holds by construction. The caret is decorative and excluded rather than spoken.
  it('names the route with exactly the words it displays', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} onOpenWindow={vi.fn()} />);
    const go = screen.getByRole('button', { name: 'Go to Tomorrow sunrise' });
    expect(go).toHaveTextContent('Go to Tomorrow sunrise');
    expect(go).toHaveAccessibleName('Go to Tomorrow sunrise');
  });

  // A control that scrolls to the element immediately beneath it has no visible effect, which is
  // the demo control §6 bans.
  it('offers no route when the promoted window is already the next thing on the pane', () => {
    render(<WindowFirstPromotedStrip strip={ADJACENT()} onOpenWindow={vi.fn()} />);
    expect(screen.queryByTestId('window-first-promo-go')).toBeNull();
    expect(screen.queryByTestId('window-first-promo-foot')).toBeNull();
  });

  it('offers no route when no handler was supplied', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} />);
    expect(screen.queryByTestId('window-first-promo-go')).toBeNull();
  });
});

describe('WindowFirstPromotedStrip — what it deliberately does not draw', () => {
  // The mock's right-hand meta is "61 coastal locations, 2 regions" — a count of our own data, which
  // §6 bans and which two other components in this arm have already dropped for the same reason.
  // Asserted as an absence of digits outside the figures, because the ban is on the CLAIM, not on a
  // particular string.
  it('states no count of our own data', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} onOpenWindow={vi.fn()} />);
    const head = screen.getByTestId('window-first-promo-head');
    // The header carries the pair, the window and its clock time, and nothing enumerable.
    expect(head.textContent).toBe('King tide × Aurora possible × Snow on the fellsTomorrow sunrise06:14');
  });

  // No chart. `BriefingWindowTide` carries exactly one extreme and no per-extreme x position, so the
  // design's four-label band is not derivable from the window projection — and the curve alone would
  // be the fourth tide chart in this arm on one pane. See the component's Javadoc.
  it('draws no chart', () => {
    const { container } = render(
      <WindowFirstPromotedStrip strip={RICH()} onOpenWindow={vi.fn()} />,
    );
    expect(container.getElementsByTagName('svg')).toHaveLength(0);
  });

  it('carries no verdict, so it makes no quality claim about the window it names', () => {
    render(<WindowFirstPromotedStrip strip={RICH()} onOpenWindow={vi.fn()} />);
    const strip = screen.getByTestId('window-first-promo');
    expect(within(strip).queryByTestId('window-card-verdict')).toBeNull();
    expect(strip.textContent).not.toMatch(/Worth it|Maybe|Poor|Awaiting/);
  });
});
