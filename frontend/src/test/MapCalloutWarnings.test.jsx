/**
 * Met Office warning chips on the Map tab's callout (weather-warnings-plan.md W2).
 *
 * <p>What breaks if these fail: the callout shows no warning for a place that has one (including an unscored or
 * stood-down window); a chip is drawn inside the rating badge's own row, where it could read as part of the rating;
 * a past or night window grows a chip the server never keyed to it; the licence's attribution goes missing; or the
 * feature shows while the flag is off.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import MapCallout from '../components/map/MapCallout.jsx';
import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import { buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

function withMeasuredCard(width, height) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  return () => {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  };
}

function makeMap() {
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: 800, configurable: true });
  return {
    zoom: 9,
    getZoom() { return this.zoom; },
    getSize: () => ({ x: 800, y: 500 }),
    getContainer: () => container,
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
    panInside() {},
    flyTo() {},
    on() {},
    off() {},
  };
}

const NOW = new Date('2026-06-15T10:00:00Z');
const TODAY = '2026-06-15';

const YELLOW = warning({ warningId: 'y', weatherTypes: ['WIND'], validFrom: '2026-06-15T00:00:00Z', validTo: '2026-06-17T00:00:00Z' });
const RED = warning({ warningId: 'r', level: 'RED', weatherTypes: ['RAIN'], updated: true, validFrom: '2026-06-15T00:00:00Z', validTo: '2026-06-17T00:00:00Z' });

const LOCATION = {
  id: 7,
  name: 'Bamburgh',
  lat: 55.6,
  lon: -1.7,
  regionName: 'North East',
  locationType: ['SEASCAPE'],
  forecastsByDate: new Map([[TODAY, { sunset: { solarEventTime: `${TODAY}T20:10:00`, warningIds: ['y', 'r'] } }]]),
};

const EVENT = {
  id: `solar:${TODAY}:SUNSET`,
  kind: 'solar',
  eventType: 'SUNSET',
  date: TODAY,
  past: false,
  served: true,
  label: 'Tonight sunset',
  dayLabel: 'Tonight',
  time: '21:10',
  badges: [],
};

let frames = [];
let originalRaf;
let originalCancel;
let restore;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  frames = [];
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  global.cancelAnimationFrame = (id) => { frames[id - 1] = null; };
  currentMap = makeMap();
  restore = withMeasuredCard(286, 260);
  getWarnings.mockReset();
});
afterEach(() => {
  restore();
  vi.useRealTimers();
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  currentMap = null;
  document.body.innerHTML = '';
});

async function mount(served, props = {}) {
  getWarnings.mockResolvedValue(served);
  await act(async () => {
    render(
      <WarningsProvider>
        <MapCallout location={LOCATION} event={EVENT} rating={4} ratingKnown scoresKnown {...props} />
      </WarningsProvider>,
    );
  });
}

describe('MapCallout warnings', () => {
  it('draws the window\'s warnings, most severe first, with the level word printed', async () => {
    await mount(response([YELLOW, RED]));
    const chips = screen.getAllByTestId('warning-chip');
    expect(chips.map((c) => c.dataset.level)).toEqual(['RED', 'YELLOW']);
    expect(chips[0]).toHaveTextContent('Red warning of rain');
    expect(chips[0]).toHaveTextContent('Updated');
    expect(chips[1]).toHaveTextContent('Yellow warning of wind');
  });

  it('prints the attribution and the Met Office link under the chips', async () => {
    await mount(response([YELLOW, RED]));
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
  });

  it('keeps the chips out of the rating row and leaves the rating as served', async () => {
    await mount(response([YELLOW, RED]));
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('4★');
    expect(screen.getByTestId('map-callout-verdict')).not.toContainElement(screen.getByTestId('map-callout-warnings'));
  });

  it('keeps a stood-down window\'s warning, and the verdict still says it was stood down', async () => {
    const stoodDown = buildScoreIndex([
      { locationId: 7, locationName: 'Bamburgh', date: TODAY, targetType: 'SUNSET', displayVerdict: 'STAND_DOWN' },
    ]);
    await mount(response([YELLOW, RED]), { rating: null, scoreIndex: stoodDown });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Stood down');
    expect(screen.getAllByTestId('warning-chip')).toHaveLength(2);
  });

  it('keeps an unscored window\'s warning', async () => {
    await mount(response([YELLOW, RED]), { rating: null, scoreIndex: buildScoreIndex([]) });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Not scored yet');
    expect(screen.getAllByTestId('warning-chip')).toHaveLength(2);
  });

  it('draws nothing for a place the polled warnings do not cover, whatever its stored row names', async () => {
    const quiet = { ...LOCATION, id: 99 };
    await mount(response([YELLOW, RED]), { location: quiet });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.queryByTestId('warning-footer')).toBeNull();
  });

  it('matches a place with no stored row for the window against the served list, at the slot\'s event time', async () => {
    const covering = warning({ warningId: 'cover', level: 'AMBER', weatherTypes: ['SNOW'], locationIds: [7], validFrom: `${TODAY}T18:00:00Z`, validTo: `${TODAY}T23:00:00Z` });
    const slots = buildSlotIndex([{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'North East', confidence: 'high', slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: `${TODAY}T20:10:00` }] }],
      }],
    }]);
    await mount(response([covering]), { location: { ...LOCATION, forecastsByDate: new Map() }, slotIndex: slots });
    expect(screen.getByTestId('warning-chip')).toHaveTextContent('Amber warning of snow');
  });

  it('falls back to the score row\'s event instant when there is no briefing slot', async () => {
    const covering = warning({ warningId: 'cover', locationIds: [7], validFrom: `${TODAY}T18:00:00Z`, validTo: `${TODAY}T23:00:00Z` });
    const scores = buildScoreIndex([{
      locationId: 7, locationName: 'Bamburgh', date: TODAY, targetType: 'SUNSET', rating: 4,
      goldenHourStart: `${TODAY}T19:30:00`, blueHourEnd: `${TODAY}T20:50:00`, goldenHourEnd: `${TODAY}T20:10:00`, blueHourStart: `${TODAY}T20:10:00`,
    }]);
    await mount(response([covering]), { location: { ...LOCATION, forecastsByDate: new Map() }, scoreIndex: scores });
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('shows a warning the stored row does not name yet once the polled list covers the place (no /api/forecast refetch)', async () => {
    const unnamed = { ...LOCATION, forecastsByDate: new Map([[TODAY, { sunset: { solarEventTime: `${TODAY}T20:10:00` } }]]) };
    await mount(response([YELLOW]), { location: unnamed });
    expect(screen.getByTestId('warning-chip')).toHaveTextContent('Yellow warning of wind');
  });

  it('drops a warning the stored row still names once the polled list no longer covers the place', async () => {
    await mount(response([warning({ warningId: 'y', locationIds: [99], validFrom: `${TODAY}T00:00:00Z`, validTo: `${TODAY}T23:59:00Z` })]));
    expect(screen.queryByTestId('warning-chip')).toBeNull();
  });

  it('keeps the chip on a travel day (what is happening is never silenced)', async () => {
    await mount(response([YELLOW, RED]), { travelDay: true });
    expect(screen.getAllByTestId('warning-chip')).toHaveLength(2);
  });

  it('draws nothing for a past window', async () => {
    await mount(response([YELLOW, RED]), { event: { ...EVENT, past: true } });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.queryByTestId('warning-strip')).toBeNull();
  });

  it('draws nothing for a night window', async () => {
    await mount(response([YELLOW, RED]), { event: { ...EVENT, id: `astro:${TODAY}:ASTRO`, kind: 'astro', eventType: 'ASTRO' } });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
  });

  it('shows the same chips to every role (the callout reads no role for them)', async () => {
    await mount(response([YELLOW, RED]), { userRole: 'LITE_USER' });
    expect(screen.getAllByTestId('warning-chip')).toHaveLength(2);
  });

  it('shows only the stale line, never a "none" claim, when the feed is stale and the window has no warning', async () => {
    const quiet = { ...LOCATION, id: 99 };
    await mount(response([YELLOW], { stale: true, lastCheckedAt: `${TODAY}T07:05:00Z` }), { location: quiet });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Last checked 2 h ago. These may be out of date.');
  });

  it('shows nothing while the feature is off', async () => {
    await mount(response([YELLOW, RED], { enabled: false, stale: true }));
    expect(screen.queryByTestId('warning-strip')).toBeNull();
    expect(screen.queryByTestId('warning-chip')).toBeNull();
  });

  it('re-places the card when a poll adds a chip (the strip changes its height)', async () => {
    getWarnings.mockResolvedValueOnce(response([]));
    getWarnings.mockResolvedValue(response([YELLOW]));
    await act(async () => {
      render(
        <WarningsProvider>
          <MapCallout location={LOCATION} event={EVENT} rating={4} ratingKnown scoresKnown />
        </WarningsProvider>,
      );
    });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const before = spy.mock.calls.length;
    // The next visible-page poll lands: past the minimum gap, the window regains focus.
    vi.setSystemTime(new Date(NOW.getTime() + 3 * 60 * 1000));
    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
    expect(spy.mock.calls.length).toBeGreaterThan(before);
  });

  it('re-places the card when the stale line appears', async () => {
    getWarnings.mockResolvedValueOnce(response([YELLOW]));
    getWarnings.mockResolvedValue(response([YELLOW], { stale: true }));
    await act(async () => {
      render(
        <WarningsProvider>
          <MapCallout location={LOCATION} event={EVENT} rating={4} ratingKnown scoresKnown />
        </WarningsProvider>,
      );
    });
    const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const before = spy.mock.calls.length;
    vi.setSystemTime(new Date(NOW.getTime() + 3 * 60 * 1000));
    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
    expect(spy.mock.calls.length).toBeGreaterThan(before);
  });

  it('re-places an open card when the chrome group changes height (chromeKey), and not otherwise (W4)', async () => {
    let rerender;
    await act(async () => {
      ({ rerender } = render(
        <MapCallout location={LOCATION} event={EVENT} rating={4} ratingKnown scoresKnown chromeKey={1} />,
      ));
    });
    const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const calls = () => spy.mock.calls.length;
    const still = calls();
    await act(async () => {
      rerender(<MapCallout location={LOCATION} event={EVENT} rating={4} ratingKnown scoresKnown chromeKey={1} />);
    });
    expect(calls()).toBe(still);
    await act(async () => {
      rerender(<MapCallout location={LOCATION} event={EVENT} rating={4} ratingKnown scoresKnown chromeKey={2} />);
    });
    expect(calls()).toBeGreaterThan(still);
  });

  it('renders as before outside any provider', async () => {
    await act(async () => {
      render(<MapCallout location={LOCATION} event={EVENT} rating={4} ratingKnown scoresKnown />);
    });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('4★');
    expect(screen.queryByTestId('warning-strip')).toBeNull();
  });
});
