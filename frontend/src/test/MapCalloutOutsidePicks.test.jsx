import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));

import MapCallout from '../components/map/MapCallout.jsx';

/**
 * The callout's verdict slot for a place outside a Lite reader's sub-regions (sub-regions-plan §11 S5a-3,
 * decision 12): the browser check of S5a-1 saw "Not rated" here.
 *
 * <p><b>What breaks if these fail:</b> the Map's callout tells a reader a place is "Not rated" or "Not scored
 * yet" when it is outside their picks, or the flag-off callout changes.
 */

const TODAY = '2026-06-15';
const LOCATION = {
  id: 7, name: 'Malham Cove', lat: 54.07, lon: -2.16, regionName: 'The Yorkshire Dales', bortleClass: 3,
  tideType: [], locationType: ['LANDSCAPE'],
};
const SOLAR = {
  id: `solar:${TODAY}:SUNSET`, kind: 'solar', eventType: 'SUNSET', date: TODAY, label: 'Tonight sunset', time: '21:10', badges: [],
};

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
  await act(async () => {
    render(
      <MapCallout
        location={LOCATION}
        event={SOLAR}
        rating={null}
        scoresKnown
        ratingKnown
        {...props}
      />,
    );
  });
}

describe('MapCallout: a place outside the reader\'s sub-regions', () => {
  it('says Outside your sub-regions where the verdict would be', async () => {
    await mount({ outsidePicks: true });

    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Outside your sub-regions');
  });

  it('says it even before the ratings request has answered, never "Loading…"', async () => {
    await mount({ outsidePicks: true, ratingKnown: false });

    const word = screen.getByTestId('map-callout-score').textContent;
    expect(word).toBe('Outside your sub-regions');
  });

  it('never says "Not rated" or "Not scored yet", whatever verdict the slot was served', async () => {
    await mount({
      outsidePicks: true,
      slotIndex: new Map(),
      scoreIndex: new Map(),
    });

    expect(screen.getByTestId('map-callout-score').textContent).not.toMatch(/not rated|not scored|poor/i);
  });

  it('keeps the route into the four-day sheet when there is no prose, and presses through', async () => {
    const onOpenSheet = vi.fn();
    await mount({ outsidePicks: true, onOpenSheet });

    const route = screen.getByTestId('map-callout-four-days');
    expect(route).toHaveTextContent('Four days here');
    route.click();
    expect(onOpenSheet).toHaveBeenCalledTimes(1);
  });

  it('draws no such extra route for anyone else', async () => {
    await mount({ outsidePicks: false });

    expect(screen.queryByTestId('map-callout-four-days')).toBeNull();
  });

  it('is unchanged for everyone else: an unrated place keeps its served word', async () => {
    await mount({ outsidePicks: false });

    expect(screen.getByTestId('map-callout-score').textContent).toBe('Not scored yet');
  });

  it('is unchanged when the prop is absent', async () => {
    await mount();

    expect(screen.getByTestId('map-callout-score').textContent).toBe('Not scored yet');
  });

  it('a rated place keeps its stars even if the flag were set (the stars win)', async () => {
    await mount({ outsidePicks: true, rating: 4 });

    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('4★');
  });
});
