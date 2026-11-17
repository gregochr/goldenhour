/**
 * Met Office warnings on the Plan strip (weather-warnings-plan.md W3): the lane first in the days-ahead stack, the
 * text marker on a window card and the card's spoken sentence.
 *
 * <p>What breaks if these fail: a warning is drawn on a card it does not cover, a withdrawn warning lingers, a card
 * marker uses an icon, the spoken sentence omits the warning the sighted reader sees, an away (travel) cell gets a
 * marker, the stale admission is lost, the feature leaks while the flag is off, or a warning changes what the card
 * says about its rating.
 */
import React from 'react';
import {
  describe, it, expect, beforeEach, afterEach, vi,
} from 'vitest';
import {
  act, cleanup, render, screen, fireEvent, within,
} from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
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

import { getWarnings } from '../api/warningsApi.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import WindowFirstHeatStrip from '../components/WindowFirstHeatStrip.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

const TODAY = '2026-10-05';
const TOMORROW = '2026-10-06';

const card = (date, targetType, over = {}) => ({
  key: `${date}:${targetType}`,
  date,
  targetType,
  dow: 'Mon',
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
  eventIso: `${date}T${targetType === 'SUNRISE' ? '06:00' : '17:00'}:00`,
  warningIds: [],
  placeIds: [1],
  ...over,
});

const CARDS = [
  card(TODAY, 'SUNSET'),
  card(TOMORROW, 'SUNRISE'),
  card(TOMORROW, 'SUNSET', { away: true, placeIds: [] }),
];
const POINTS = new Map(CARDS.filter((c) => !c.away).map((c) => [c.key, [{
  id: 1, name: 'Whitby', lat: 54.49, lng: -0.61, rid: 'North York Moors & Coast', r: [4],
}]]));
const SPOTS = [{
  id: 1, name: 'Whitby', lat: 54.49, lng: -0.61, regionName: 'North York Moors & Coast',
  rid: 'North York Moors & Coast', skySubject: true, bortleClass: 3, scores: [4],
}];

const SNOW = warning({
  warningId: 'snow', level: 'AMBER', weatherTypes: ['SNOW'], locationIds: [1], updated: true,
  validFrom: `${TODAY}T00:00:00Z`, validTo: `${TOMORROW}T23:00:00Z`,
});
const WIND = warning({
  warningId: 'wind', level: 'YELLOW', weatherTypes: ['WIND'], locationIds: [1],
  validFrom: `${TOMORROW}T00:00:00Z`, validTo: `${TOMORROW}T23:00:00Z`,
});

let originalGetContext;
beforeEach(() => {
  getWarnings.mockReset();
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  cleanup();
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  vi.mocked(useIsMobile).mockReturnValue(false);
});

const mount = async (served, props = {}) => {
  getWarnings.mockResolvedValue(served);
  await act(async () => {
    render(
      <WarningsProvider>
        <WindowFirstHeatStrip
          cards={CARDS}
          pointSets={POINTS}
          spots={SPOTS}
          todayStr={TODAY}
          onOpenWindow={() => {}}
          onGoToPlanWindow={vi.fn()}
          {...props}
        />
      </WarningsProvider>,
    );
  });
};
const cardFor = (key) => screen.getAllByTestId('wf-heat-card-button')
  .find((el) => el.getAttribute('aria-labelledby')?.endsWith(key.replace(/:/g, '-')))
  ?.closest('[data-testid="wf-heat-card"]');
const sentenceOf = (key) => within(cardFor(key)).getByTestId('wf-heat-card-button').textContent;

describe('the warning lane', () => {
  it('draws one lane over the columns the warning covers, with the level word and the Updated label', async () => {
    await mount(response([SNOW]));
    const lane = screen.getByTestId('wf-warning-lane');
    expect(lane).toHaveTextContent('Amber warning of snow');
    expect(within(lane).getByTestId('wf-warning-lane-updated')).toBeInTheDocument();
    const item = lane.closest('li');
    expect(item.style.getPropertyValue('--ls')).toBe('1');
    expect(item.style.getPropertyValue('--le')).toBe('3');
  });

  it('draws separate lanes per warning, most severe on the top row', async () => {
    await mount(response([WIND, SNOW]));
    const items = screen.getAllByTestId('wf-warning-lane-item');
    expect(items.map((li) => li.querySelector('[data-testid="wf-warning-lane"]').dataset.level)).toEqual(['AMBER', 'YELLOW']);
    expect(items.map((li) => li.style.getPropertyValue('--lr'))).toEqual(['1', '2']);
  });

  it('opens the first covered window when pressed', async () => {
    const onGoToPlanWindow = vi.fn();
    await mount(response([WIND]), { onGoToPlanWindow });
    fireEvent.click(screen.getByTestId('wf-warning-lane'));
    expect(onGoToPlanWindow).toHaveBeenCalledWith(`${TOMORROW}:SUNRISE`);
  });

  it('is not a button when the strip has no window route', async () => {
    await mount(response([WIND]), { onGoToPlanWindow: undefined });
    expect(screen.getByTestId('wf-warning-lane').tagName).toBe('DIV');
  });

  it('is not a button when the window is not openable', async () => {
    await mount(response([WIND]), { openableKeys: new Set([`${TODAY}:SUNSET`]) });
    expect(screen.getByTestId('wf-warning-lane').tagName).toBe('DIV');
    cleanup();
    await mount(response([WIND]), { openableKeys: new Set([`${TOMORROW}:SUNRISE`]) });
    expect(screen.getByTestId('wf-warning-lane').tagName).toBe('BUTTON');
  });

  it('carries the attribution and Met Office link once, under the lanes', async () => {
    await mount(response([SNOW]));
    expect(screen.getAllByTestId('warning-attribution')).toHaveLength(1);
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
  });

  it('draws nothing for a warning that covers none of the windows\' places', async () => {
    await mount(response([warning({ warningId: 'far', locationIds: [99], validFrom: `${TODAY}T00:00:00Z`, validTo: `${TOMORROW}T23:00:00Z` })]));
    expect(screen.queryByTestId('wf-warning-lane')).toBeNull();
    expect(screen.queryByTestId('wf-heat-warning')).toBeNull();
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
  });

  it('takes a warning the briefing already named on the window', async () => {
    const named = CARDS.map((c) => (c.key === `${TODAY}:SUNSET` ? { ...c, warningIds: ['snow'], placeIds: [] } : c));
    await mount(response([SNOW]), { cards: named });
    expect(within(cardFor(`${TODAY}:SUNSET`)).getByTestId('wf-heat-warning')).toHaveTextContent('Amber warning');
  });

  it('drops the lane and the marker when the poll no longer holds the warning', async () => {
    const named = CARDS.map((c) => ({ ...c, warningIds: ['snow'] }));
    await mount(response([]), { cards: named });
    expect(screen.queryByTestId('wf-warning-lane')).toBeNull();
    expect(screen.queryByTestId('wf-heat-warning')).toBeNull();
  });

  it('prints nothing while the feature is off, not even the attribution', async () => {
    await mount({ enabled: false, warnings: [SNOW], stale: true });
    expect(screen.queryByTestId('wf-warning-lane')).toBeNull();
    expect(screen.queryByTestId('wf-heat-warning')).toBeNull();
    expect(screen.queryByTestId('warning-footer')).toBeNull();
  });

  it('prints the stale admission, never an all-clear, when the feed is stale and nothing applies', async () => {
    await mount(response([], { stale: true }));
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Warnings last checked');
    expect(screen.queryByTestId('wf-warning-lane')).toBeNull();
    expect(document.body.textContent).not.toMatch(/no warnings/i);
  });

  it('prints the stale admission beside lanes that are shown', async () => {
    await mount(response([SNOW], { stale: true }));
    expect(screen.getByTestId('wf-warning-lane')).toBeInTheDocument();
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
  });

  it('is the same for every role (no role is read)', async () => {
    await mount(response([SNOW]));
    expect(screen.getByTestId('wf-warning-lane')).toBeInTheDocument();
  });
});

describe('the card marker', () => {
  it('is text in the level colour on exactly the covered cards, with no icon', async () => {
    await mount(response([WIND]));
    const marker = within(cardFor(`${TOMORROW}:SUNRISE`)).getByTestId('wf-heat-warning');
    expect(marker).toHaveTextContent('Yellow warning');
    expect(marker.className).toContain('bg-[#ffe923]');
    expect(marker.querySelector('svg, img')).toBeNull();
    expect(marker).toHaveAttribute('aria-hidden', 'true');
    expect(within(cardFor(`${TODAY}:SUNSET`)).queryByTestId('wf-heat-warning')).toBeNull();
  });

  it('counts the others and keeps every name in its title', async () => {
    await mount(response([WIND, SNOW]));
    const marker = within(cardFor(`${TOMORROW}:SUNRISE`)).getByTestId('wf-heat-warning');
    expect(marker).toHaveTextContent('Amber warning +1');
    expect(marker).toHaveAttribute('title', 'Amber warning of snow, updated; Yellow warning of wind');
  });

  it('puts every warning in the card\'s spoken sentence, so the visible words are in the name', async () => {
    await mount(response([WIND, SNOW]));
    const sentence = sentenceOf(`${TOMORROW}:SUNRISE`);
    expect(sentence).toContain('Amber warning of snow, updated, Yellow warning of wind');
    expect(sentenceOf(`${TODAY}:SUNSET`)).toContain('Amber warning of snow, updated');
    expect(sentenceOf(`${TODAY}:SUNSET`)).not.toContain('Yellow warning');
  });

  it('prints an unknown level neutral, with its own word', async () => {
    await mount(response([warning({
      warningId: 'p', level: 'PURPLE', weatherTypes: [], locationIds: [1], validFrom: `${TODAY}T00:00:00Z`, validTo: `${TOMORROW}T23:00:00Z`,
    })]));
    const marker = within(cardFor(`${TODAY}:SUNSET`)).getByTestId('wf-heat-warning');
    expect(marker).toHaveTextContent('Purple warning');
    expect(marker.className).toContain('bg-plex-surface-light');
  });

  it('gives an away (travel) cell no marker and no sentence clause', async () => {
    await mount(response([SNOW]), { cards: CARDS.map((c) => (c.away ? { ...c, placeIds: [1] } : c)) });
    const away = screen.getAllByTestId('wf-heat-card').find((c) => c.hasAttribute('data-away'));
    expect(within(away).queryByTestId('wf-heat-warning')).toBeNull();
    expect(away.textContent).not.toContain('warning');
  });

  it('leaves the card\'s rating words exactly as they were', async () => {
    await mount(response([]));
    const before = screen.getAllByTestId('wf-heat-verdict').map((e) => e.textContent);
    const spoken = sentenceOf(`${TODAY}:SUNSET`);
    cleanup();
    await mount(response([SNOW, WIND]));
    expect(screen.getAllByTestId('wf-heat-verdict').map((e) => e.textContent)).toEqual(before);
    expect(sentenceOf(`${TODAY}:SUNSET`).replace(', Amber warning of snow, updated', '')).toBe(spoken);
  });
});

describe('the phone', () => {
  it('draws each day\'s warning lane in its own day cell, and the stale line on the first day only', async () => {
    vi.mocked(useIsMobile).mockReturnValue(true);
    await mount(response([WIND], { stale: true }));
    const cells = screen.getAllByTestId('wf-glance-lanes-day');
    expect(cells).toHaveLength(2);
    expect(within(cells[0]).getByTestId('warning-stale')).toBeInTheDocument();
    expect(within(cells[0]).queryByTestId('wf-warning-lane')).toBeNull();
    expect(within(cells[1]).getByTestId('wf-warning-lane')).toHaveTextContent('Yellow warning of wind');
    expect(within(cells[1]).queryByTestId('warning-stale')).toBeNull();
    expect(screen.getAllByTestId('warning-stale')).toHaveLength(1);
  });
});
