import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstHeatStrip from '../components/WindowFirstHeatStrip.jsx';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

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

/**
 * The Best bets upsell door in the heat strip's key row (docs/engineering/pro-features-gate-plan.md §7.3).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader gets no door where the "Best bet" legend used to be
 * (so the feature is invisible to them), the door carries a link or the word "upgrade" (forbidden by the
 * App Store rule), or a Pro reader sees a door.
 */

const TODAY = '2026-08-04';

const CARD = {
  key: `${TODAY}:SUNSET`,
  date: TODAY,
  targetType: 'SUNSET',
  dow: 'Tue',
  sunrise: false,
  label: 'Tonight Sunset',
  time: '21:11',
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
};

const SPOT = {
  id: 1,
  name: 'Bamburgh Beach',
  lat: 55.61,
  lng: -1.71,
  regionName: 'Northumberland & Tyneside',
  rid: 'Northumberland & Tyneside',
  skySubject: true,
  bortleClass: 3,
  scores: [4],
};

async function renderStrip(props = {}) {
  const cards = props.cards ?? [CARD];
  const pointSets = new Map(cards.map((c) => [c.key, [{
    id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, rid: 'Northumberland & Tyneside', r: [4],
  }]]));
  await act(async () => {
    render(
      <WindowFirstHeatStrip
        spots={[SPOT]}
        todayStr={TODAY}
        onOpenWindow={vi.fn()}
        {...props}
        cards={cards}
        pointSets={pointSets}
      />,
    );
  });
}

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
  vi.clearAllMocks();
});

describe('WindowFirstHeatStrip — the Best bets door', () => {
  it('is absent for a reader with best bets (the default)', async () => {
    await renderStrip();
    expect(screen.queryByTestId('wf-heat-bestbets-upsell-door')).toBeNull();
  });

  it('sits first in the key row for a locked reader', async () => {
    await renderStrip({ picksLocked: true });

    const foot = screen.getByTestId('wf-heat-foot');
    const door = screen.getByTestId('wf-heat-bestbets-upsell-door');
    expect(foot.firstElementChild).toBe(door);
    expect(screen.getByTestId('wf-heat-bestbets-upsell-trigger')).toHaveTextContent('Best bets');
  });

  it('does not itself hide a pick it is handed: the lock lives in the context, which nulls the card', async () => {
    // Documents where the lock is (WindowFirstBriefingPicksLock.test.jsx proves the context nulls pickKind);
    // the strip is a render layer and must not re-derive or suppress a served pick.
    await renderStrip({ picksLocked: true, cards: [{ ...CARD, pickKind: 'best' }] });
    expect(screen.getByTestId('wf-heat-legend')).toHaveTextContent('Best bet');
  });

  it('opens a worded popover with no link and no "upgrade", and closes on Escape and "Not now"', async () => {
    await renderStrip({ picksLocked: true });
    const trigger = screen.getByTestId('wf-heat-bestbets-upsell-trigger');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(trigger);
    const pop = screen.getByTestId('wf-heat-bestbets-upsell-popover');
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('wf-heat-bestbets-upsell-heading')).toHaveTextContent('Best bets');
    expect(screen.getByTestId('wf-heat-bestbets-upsell-body'))
      .toHaveTextContent("See the forecast's two best windows, and where to go for each.");
    expect(pop.querySelector('a')).toBeNull();
    expect(pop.textContent).not.toMatch(/upgrade/i);

    fireEvent.keyDown(pop, { key: 'Escape' });
    expect(screen.queryByTestId('wf-heat-bestbets-upsell-popover')).toBeNull();
    expect(trigger).toHaveFocus();

    fireEvent.click(trigger);
    fireEvent.click(screen.getByTestId('wf-heat-bestbets-upsell-dismiss'));
    expect(screen.queryByTestId('wf-heat-bestbets-upsell-popover')).toBeNull();
  });
});

describe('WindowFirstHeatStrip — guide doors (G3)', () => {
  beforeEach(() => mockOpenGuide.mockClear());

  it('draws a ? at the end of the days-ahead heading that opens reading-the-forecast#fixed', async () => {
    await renderStrip();
    const head = screen.getByTestId('wf-heat-head');
    const link = within(head).getByTestId('guide-link');
    expect(link).toHaveAccessibleName('What fixed and could still move mean');
    expect(head.lastElementChild).toBe(link);
    fireEvent.click(link);
    expect(mockOpenGuide).toHaveBeenCalledWith('reading-the-forecast', 'fixed', expect.any(Object));
  });

  it('puts "What does this do?" in the Best bets popover only for a locked reader, opening at-a-glance#bestbet', async () => {
    await renderStrip({ picksLocked: true });
    const trigger = screen.getByTestId('wf-heat-bestbets-upsell-trigger');
    fireEvent.click(trigger);
    const why = within(screen.getByTestId('wf-heat-bestbets-upsell-popover')).getByTestId('wf-heat-bestbets-upsell-guide');
    fireEvent.click(why);
    expect(mockOpenGuide).toHaveBeenCalledWith('at-a-glance', 'bestbet', expect.any(Object));
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(trigger);
  });

  it('draws no Best bets door, so no guide link, for a reader with best bets', async () => {
    await renderStrip();
    expect(screen.queryByTestId('wf-heat-bestbets-upsell-guide')).toBeNull();
    expect(screen.queryByTestId('guide-why')).toBeNull();
  });
});
