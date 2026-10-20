/**
 * `MapCallout`'s two C2 additions (overlay convergence, `docs/engineering/overlay-convergence-plan.md`
 * §3 C2, owner decision D3): the aurora alert-level word beside the stars, and the travel-day notice.
 *
 * What breaks if these fail: the callout stops naming the geomagnetic level the popup named, draws
 * a level it was never served (or one on a solar window), says a night window "was not run" because
 * the operator is away, or overflows the 266px phone card with either line.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

import MapCallout from '../components/map/MapCallout.jsx';
import { EVENT_KIND } from '../utils/mapEvents.js';

const TODAY = '2026-06-15';
const LOCATION = {
  id: 7, name: 'Bamburgh', lat: 55.6, lon: -1.7, regionName: 'North East', bortleClass: 3,
  tideType: ['HIGH'], locationType: ['SEASCAPE'],
};
const night = (kind, eventType) => ({
  id: `${kind}:${TODAY}:${eventType}`, kind, eventType, date: TODAY, label: 'Tonight', time: '23:40', badges: [],
});
const SOLAR = {
  id: `solar:${TODAY}:SUNSET`, kind: 'solar', eventType: 'SUNSET', date: TODAY, label: 'Tonight sunset', time: '21:10', badges: [],
};
const AURORA = night('aur', 'AURORA');
const ASTRO = night('astro', 'ASTRO');

let originalW;
let originalH;
beforeEach(() => {
  originalW = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  originalH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => 286 });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => 260 });
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  currentMap = {
    getZoom: () => 9,
    getSize: () => ({ x: 800, y: 500 }),
    getContainer: () => container,
    latLngToContainerPoint: () => ({ x: 300, y: 200 }),
    panInside() {},
    flyTo() {},
    on() {},
    off() {},
  };
  mockIsMobile = false;
});

afterEach(() => {
  if (originalW) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', originalW);
  else delete HTMLElement.prototype.offsetWidth;
  if (originalH) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', originalH);
  else delete HTMLElement.prototype.offsetHeight;
  currentMap = null;
  document.body.innerHTML = '';
});

async function mount(props = {}) {
  let result;
  await act(async () => {
    result = render(<MapCallout location={LOCATION} event={SOLAR} rating={4} {...props} />);
  });
  // Unmounted by the caller between mounts — never by clearing the body, which would also remove
  // the map container the card places itself in and leave every later absence check vacuous.
  return result;
}

describe('MapCallout — the aurora alert level', () => {
  it.each([
    ['QUIET', 'Quiet'], ['MINOR', 'Minor'], ['MODERATE', 'Moderate'], ['STRONG', 'Strong'],
  ])('names a served %s level on an aurora window', async (level, word) => {
    await mount({ event: AURORA, auroraAlertLevel: level });
    expect(screen.getByTestId('map-callout-aurora-level')).toHaveTextContent(`Alert level: ${word}`);
    // The stars are still the headline — the level is a second line, not a replacement.
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('4★');
  });

  it('draws nothing when no level is served, or when the value is not one it knows', async () => {
    const first = await mount({ event: AURORA });
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
    expect(screen.queryByTestId('map-callout-aurora-level')).not.toBeInTheDocument();
    first.unmount();
    await mount({ event: AURORA, auroraAlertLevel: 'SEVERE' });
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
    expect(screen.queryByTestId('map-callout-aurora-level')).not.toBeInTheDocument();
  });

  it('draws nothing on a solar or astro window even when a level is handed over', async () => {
    const first = await mount({ event: SOLAR, auroraAlertLevel: 'STRONG' });
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
    expect(screen.queryByTestId('map-callout-aurora-level')).not.toBeInTheDocument();
    first.unmount();
    await mount({ event: ASTRO, auroraAlertLevel: 'STRONG' });
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
    expect(screen.queryByTestId('map-callout-aurora-level')).not.toBeInTheDocument();
  });
});

describe('MapCallout — the travel-day notice', () => {
  it('says why a solar window was not forecast on a travel day', async () => {
    await mount({ travelDay: true });
    expect(screen.getByTestId('map-callout-travel-day'))
      .toHaveTextContent('Forecast not run — you\'re away (travel day).');
  });

  it('is absent off a travel day (the default) and on a night window', async () => {
    const a = await mount();
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
    expect(screen.queryByTestId('map-callout-travel-day')).not.toBeInTheDocument();
    a.unmount();
    const b = await mount({ event: AURORA, travelDay: true });
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
    expect(screen.queryByTestId('map-callout-travel-day')).not.toBeInTheDocument();
    b.unmount();
    await mount({ event: ASTRO, travelDay: true });
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
    expect(screen.queryByTestId('map-callout-travel-day')).not.toBeInTheDocument();
  });

  it('keeps the plane out of the accessible reading', async () => {
    await mount({ travelDay: true });
    expect(screen.getByText('✈️', { exact: false })).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('MapCallout — both lines on the 266px phone card', () => {
  // jsdom lays nothing out, so whether these lines FIT at 266px (or a 390px/320px viewport) is NOT
  // tested here: that is a browser-verification claim. What is pinned: the lines mount on the phone
  // card, carry the shared note class (the one the stylesheet makes wrap), and sit outside the
  // verdict row rather than as a fourth item in it.
  it('mounts them on the phone card, as note blocks outside the verdict row', async () => {
    mockIsMobile = true;
    const first = await mount({ event: AURORA, auroraAlertLevel: 'MODERATE' });
    expect(screen.getByTestId('map-callout').style.width).toBe('266px');
    expect(screen.getByTestId('map-callout')).toContainElement(screen.getByTestId('map-callout-aurora-level'));
    expect(screen.getByTestId('map-callout-aurora-level')).toHaveClass('wf-callout-note');
    first.unmount();
    await mount({ travelDay: true });
    expect(screen.getByTestId('map-callout')).toContainElement(screen.getByTestId('map-callout-travel-day'));
    expect(screen.getByTestId('map-callout-travel-day')).toHaveClass('wf-callout-note');
    expect(screen.getByTestId('map-callout-verdict')).not.toContainElement(
      screen.getByTestId('map-callout-travel-day'),
    );
  });
});

describe('MapCallout — both lines re-measure the card when they arrive with it open', () => {
  // The travel-day ranges and the live aurora scoring are fetches that can land after the card is
  // up, adding a whole line with nothing else in the repaint dependency list moving. The repaint
  // path is observed through the map projection every `paint()` reads.
  const projectionCalls = () => {
    const spy = vi.fn(currentMap.latLngToContainerPoint);
    currentMap.latLngToContainerPoint = spy;
    return spy;
  };
  const rerender = async (view, props) => {
    await act(async () => {
      view.rerender(<MapCallout location={LOCATION} event={SOLAR} rating={4} {...props} />);
    });
  };

  it('repaints when travelDay flips on', async () => {
    const view = await mount({ travelDay: false });
    const spy = projectionCalls();
    await rerender(view, { travelDay: false });
    const before = spy.mock.calls.length;
    await rerender(view, { travelDay: true });
    expect(spy.mock.calls.length).toBeGreaterThan(before);
  });

  it('repaints when auroraAlertLevel arrives', async () => {
    const view = await mount({ event: AURORA, auroraAlertLevel: null });
    const spy = projectionCalls();
    const again = async (props) => {
      await act(async () => {
        view.rerender(<MapCallout location={LOCATION} event={AURORA} rating={4} {...props} />);
      });
    };
    await again({ auroraAlertLevel: null });
    const before = spy.mock.calls.length;
    await again({ auroraAlertLevel: 'STRONG' });
    expect(spy.mock.calls.length).toBeGreaterThan(before);
  });
});

describe('MapCallout — the aurora kind literal', () => {
  it("the callout's 'aur' literal is mapEvents.EVENT_KIND.AURORA", () => {
    expect(EVENT_KIND.AURORA).toBe('aur');
  });
});

