/**
 * Ask PhotoCast's highlight on the Plan matrix (F5, `docs/engineering/ask-photocast-plan.md` §2.8): the
 * card the selected pick lands on, marked by a gold edge AND the pick's rank circle, scrolled into view,
 * named "Ask pick N" to a screen reader — and distinct from the open card, which it neither opens nor
 * replaces.
 *
 * <p>The field is not asserted (jsdom has no canvas; {@code WindowFirstHeatStrip.test.jsx} says why); the
 * mocks here are that file's, kept to the minimum that lets the strip mount. The two marks' CSS is pinned
 * as text in {@code askPlanCss.test.js}: jsdom computes no cascade worth trusting for a gradient.
 */
import React from 'react';
import {
  describe, it, expect, beforeEach, afterEach, vi,
} from 'vitest';
import {
  act, cleanup, render, screen, fireEvent, within,
} from '@testing-library/react';
import WindowFirstHeatStrip from '../components/WindowFirstHeatStrip.jsx';

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

const card = (date, targetType, over = {}) => ({
  key: `${date}:${targetType}`,
  date,
  targetType,
  dow: date === TODAY ? 'Mon' : 'Tue',
  sunrise: targetType === 'SUNRISE',
  label: `${date} ${targetType}`,
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

const CARDS = [card(TODAY, 'SUNSET'), card(TOMORROW, 'SUNRISE'), card(TOMORROW, 'SUNSET', { away: true })];
const pointSets = () => new Map(CARDS.filter((c) => !c.away).map((c) => [c.key, [{
  id: 1, name: 'Whitby', lat: 54.49, lng: -0.61, rid: 'North York Moors & Coast', r: [4],
}]]));
const POINTS = pointSets();
const SPOTS = [spot];

let scrollIntoView;
let originalGetContext;
let originalScroll;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  originalScroll = Element.prototype.scrollIntoView;
  scrollIntoView = vi.fn();
  Element.prototype.scrollIntoView = scrollIntoView;
});
afterEach(() => {
  cleanup();
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  if (originalScroll) Element.prototype.scrollIntoView = originalScroll;
  else delete Element.prototype.scrollIntoView;
  vi.clearAllMocks();
});

const strip = (props = {}) => (
  <WindowFirstHeatStrip
    cards={CARDS}
    pointSets={POINTS}
    spots={SPOTS}
    todayStr={TODAY}
    onOpenWindow={props.onOpenWindow ?? (() => {})}
    {...props}
  />
);
async function renderStrip(props = {}) {
  let view;
  await act(async () => { view = render(strip(props)); });
  return view;
}
const cardFor = (key) => screen.getAllByTestId('wf-heat-card').find((el) => el.getAttribute('aria-labelledby')?.endsWith(key.replace(/:/g, '-')));
const tonight = () => cardFor(`${TODAY}:SUNSET`);
/** The cards wearing the highlight — read through the cards, not the document. */
const highlightedCards = () => screen.queryAllByTestId('wf-heat-card')
  .filter((card) => card.getAttribute('data-ask-highlight') === 'true');
const rankIn = (card) => within(card).getByTestId('wf-heat-ask-rank');

describe('the highlight', () => {
  it('marks the card whose key is in the map, with the pick’s rank on it', async () => {
    await renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 2]]) });

    expect(tonight()).toHaveAttribute('data-ask-highlight', 'true');
    expect(rankIn(tonight())).toHaveTextContent('2');
    // Only that card.
    expect(screen.getAllByTestId('wf-heat-card').filter((c) => c.hasAttribute('data-ask-highlight'))).toHaveLength(1);
    expect(screen.getAllByTestId('wf-heat-ask-rank')).toHaveLength(1);
  });

  it.each([
    ['an empty map', new Map()],
    ['an absent prop', undefined],
    ['null', null],
    ['a key no card carries', new Map([['2026-10-09:SUNSET', 1]])],
  ])('highlights nothing for %s', async (_name, highlightKeys) => {
    await renderStrip({ highlightKeys });

    expect(highlightedCards()).toHaveLength(0);
    expect(screen.queryByTestId('wf-heat-ask-rank')).toBeNull();
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('highlights nothing on an AWAY cell — a read-out is not a control, and nobody forecast the window', async () => {
    await renderStrip({ highlightKeys: new Map([[`${TOMORROW}:SUNSET`, 1]]) });

    const away = screen.getAllByTestId('wf-heat-card').find((c) => c.hasAttribute('data-away'));
    expect(away).toBeDefined();
    expect(away).not.toHaveAttribute('data-ask-highlight');
    expect(highlightedCards()).toHaveLength(0);
    // ...and no rank circle on it either: nothing about an away cell says "Ask's pick".
    expect(screen.queryByTestId('wf-heat-ask-rank')).toBeNull();
    expect(away).not.toHaveAccessibleName(/Ask pick/);
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('adds "Ask pick N" to the card’s accessible name, after the forecast’s own claims', async () => {
    const first = await renderStrip({
      cards: [card(TODAY, 'SUNSET', { pickKind: 'best' })],
      highlightKeys: new Map([[`${TODAY}:SUNSET`, 3]]),
    });

    expect(tonight()).toHaveAccessibleName(/best bet, Ask pick 3$/);
    // And a card with no highlight has no such words.
    first.unmount();
    await renderStrip({});
    expect(tonight()).not.toHaveAccessibleName(/Ask pick/);
  });

  it('is a DIFFERENT mark from the open card: both attributes can sit on one card, neither implies the other', async () => {
    await renderStrip({
      openKeys: new Set([`${TODAY}:SUNSET`]),
      highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]),
    });

    expect(tonight()).toHaveAttribute('data-open', 'true');
    expect(tonight()).toHaveAttribute('data-ask-highlight', 'true');

    cleanup();
    await renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) });
    expect(tonight()).toHaveAttribute('data-ask-highlight', 'true');
    expect(tonight()).not.toHaveAttribute('data-open');

    cleanup();
    await renderStrip({ openKeys: new Set([`${TODAY}:SUNSET`]) });
    expect(tonight()).toHaveAttribute('data-open', 'true');
    expect(tonight()).not.toHaveAttribute('data-ask-highlight');
  });

  it('does NOT open the popup: nothing is called, and pressing the card still opens it', async () => {
    const onOpenWindow = vi.fn();
    await renderStrip({ onOpenWindow, highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) });

    expect(onOpenWindow).not.toHaveBeenCalled();
    fireEvent.click(tonight());
    expect(onOpenWindow).toHaveBeenCalledWith(`${TODAY}:SUNSET`);
  });

  it('draws the rank circle inside the aria-hidden top row, so the card is named once and the number is not read twice', async () => {
    await renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 2]]) });

    const circle = screen.getByTestId('wf-heat-ask-rank');
    expect(circle.closest('[aria-hidden="true"]')).not.toBeNull();
    // ...in the row's flow beside the sun word (never hung over the card's edge, where it would collide).
    expect(circle.parentElement).toBe(within(tonight()).getByTestId('wf-heat-sun').parentElement);
  });
});

describe('scrolling into view', () => {
  it('scrolls the highlighted card to the nearest edge when the highlight ARRIVES', async () => {
    await renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) });

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.instances[0]).toBe(tonight());
    // Smooth by default (the system's reduced-motion setting is its own test), and only as far as needed.
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
  });

  it('does not scroll again on an unrelated re-render, but does when the highlight MOVES', async () => {
    const view = await renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) });
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    // A new Map with the same content (the shell builds one per memo): not a move.
    await act(async () => { view.rerender(strip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) })); });
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    await act(async () => { view.rerender(strip({ highlightKeys: new Map([[`${TOMORROW}:SUNRISE`, 2]]) })); });
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
    expect(scrollIntoView.mock.instances[1]).toBe(cardFor(`${TOMORROW}:SUNRISE`));
  });

  it('does not scroll when the highlight goes away', async () => {
    const view = await renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) });
    scrollIntoView.mockClear();

    await act(async () => { view.rerender(strip({ highlightKeys: new Map() })); });

    expect(scrollIntoView).not.toHaveBeenCalled();
    expect(highlightedCards()).toHaveLength(0);
  });

  it('is guarded: an environment with no scrollIntoView does not throw', async () => {
    delete Element.prototype.scrollIntoView;

    await expect(renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) })).resolves.toBeDefined();
    expect(tonight()).toHaveAttribute('data-ask-highlight', 'true');
  });

  it('does not animate for a reader who asked the system for less motion', async () => {
    const original = window.matchMedia;
    window.matchMedia = (query) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener() {},
      removeEventListener() {},
      addListener() {},
      removeListener() {},
    });
    try {
      await renderStrip({ highlightKeys: new Map([[`${TODAY}:SUNSET`, 1]]) });
      expect(scrollIntoView).toHaveBeenCalledWith(expect.objectContaining({ behavior: 'auto' }));
    } finally {
      window.matchMedia = original;
    }
  });
});
