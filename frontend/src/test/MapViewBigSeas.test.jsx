/**
 * `MapView` + the real `BigSeasLine`, `WindowControl` and `buildMapEvents`: the big-seas line and
 * the week menu's bolt, end to end through the `bigSeas` prop (big-seas plan F1).
 *
 * <p>Every link is invisible to a test that stubs the next one: the prop into `buildMapEvents`
 * (the stamp), the prop into the line, the line's action into `selectEvRow` and out through
 * `onSelectDate`. Not mocked: `MapView`, `BigSeasLine`, `WindowControl`, `buildMapEvents`. Mocked:
 * Leaflet and the layers that need a real Leaflet pane.
 *
 * <p>Thursday 22 Oct 2026; the storm is Saturday 24 Oct, still BST. Fixed dates, the wall clock
 * frozen.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';

import { mapViewState } from './mapViewMocks.jsx';

import MapView from '../components/MapView.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { SATURDAY } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const SAT = '2026-10-24';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[windowKey(TODAY, 'SUNSET'), [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
    }]]]),
    windows: [{
      key: windowKey(TODAY, 'SUNSET'),
      date: TODAY,
      targetType: 'SUNSET',
      label: 'Tonight sunset',
      time: '18:12',
      bestRating: 5,
      conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: new Map(),
  };
}

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: SPOT.bortleClass,
    locationType: ['LANDSCAPE'],
    tideType: ['HIGH'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 5, solarEventTime: `${TODAY}T17:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

/** The pane owns the date; this stands in for it, so a forwarded pick moves the map. */
function Pane({ bigSeas, onDate, forecastDates = [TODAY, SAT] }) {
  const [date, setDate] = React.useState(TODAY);
  return (
    <MapView
      locations={[makeLocation()]}
      date={date}
      forecastDates={forecastDates}
      autoEventType="SUNSET"
      heat={heatProp()}
      bigSeas={bigSeas}
      onSelectDate={(d, opts) => { onDate?.(d, opts); setDate(d); }}
    />
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(<Pane bigSeas={[SATURDAY]} {...props} />);
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  mapViewState.role = 'PRO_USER';
  mapViewState.isMobile = false;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView — the big-seas line', () => {
  it('shows the compact line in the chrome group, under the window control', async () => {
    await renderMap();
    const chrome = screen.getByTestId('wf-map-chrome-tl');
    const line = within(chrome).getByTestId('wf-big-seas');
    expect(line.dataset.form).toBe('compact');
    expect(within(line).getByTestId('wf-big-seas-l1').textContent)
      .toBe('Saturday 15:30–18:15 · high water near sunset');
    // DOM order: the control first, the line after it, in the one group.
    const kids = Array.from(chrome.children).map((el) => el.getAttribute('data-testid') ?? el.className);
    expect(kids.indexOf('wf-big-seas')).toBeGreaterThan(0);
    expect(chrome.contains(screen.getByTestId('wf-win-pill'))).toBe(true);
  });

  it('draws nothing when the briefing serves no big-seas day', async () => {
    await renderMap({ bigSeas: undefined });
    expect(screen.queryByTestId('wf-big-seas')).toBeNull();
  });

  it('names no day the Map has no row for (the Map ends at T+3; the backend serves big seas a day further)', async () => {
    await renderMap({ forecastDates: [TODAY] });
    expect(screen.queryByTestId('wf-big-seas')).toBeNull();
  });

  it('shows to a LITE reader too (owner Q8: all roles)', async () => {
    mapViewState.role = 'LITE_USER';
    await renderMap();
    expect(screen.getByTestId('wf-big-seas')).toBeInTheDocument();
  });

  it('"See Saturday" jumps to the Saturday sunset, where the line turns full, aligned, with no action', async () => {
    const onDate = vi.fn();
    await renderMap({ onDate });
    await act(async () => { fireEvent.click(screen.getByTestId('wf-big-seas-go')); });

    expect(onDate).toHaveBeenCalledWith(SAT, expect.anything());
    const line = screen.getByTestId('wf-big-seas');
    expect(line.dataset.form).toBe('full');
    expect(screen.getByTestId('wf-big-seas-l1').textContent)
      .toBe('15:30–18:15 · high water 16:58, 49 min before sunset');
    expect(screen.queryByTestId('wf-big-seas-go')).toBeNull();
    // Focus was parked on the line's root before the jump and stayed there.
    expect(document.activeElement).toBe(line);
  });
});

describe('MapView — the chrome height reaches the frame blocks that hang under it', () => {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  beforeEach(() => {
    // jsdom has no layout: the chrome group is 101px tall once the line is in it.
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() { return this.dataset?.testid === 'wf-map-chrome-tl' ? 101 : 0; },
    });
  });
  afterEach(() => {
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  it('the chrome group\'s parent carries --wf-chrome-tl-h', async () => {
    await renderMap();
    const chrome = screen.getByTestId('wf-map-chrome-tl');
    const frame = chrome.parentElement;
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('101px');
  });

  it('publishes it on a day with no big seas too (the hook is unconditional since the topic chips)', async () => {
    await renderMap({ bigSeas: undefined });
    const frame = screen.getByTestId('wf-map-chrome-tl').parentElement;
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('101px');
  });
});

describe('MapView — the week menu\'s bolt', () => {
  it('marks the Saturday sunset row, and only that row, in words and in the topics cell', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    const marked = rows.filter((r) => r.querySelector('[data-testid="wf-win-row-bigseas"]'));
    expect(marked.map((r) => r.getAttribute('data-ev-id'))).toEqual([`solar:${SAT}:SUNSET`]);
    expect(marked[0].textContent).toContain(', big seas');
  });
});

describe('MapView — on a phone (owner Q11: the line shows, the menu bolt stays out)', () => {
  it('draws no line for a served day with no row, so nothing there can suppress the topic chips', async () => {
    mapViewState.isMobile = true;
    await renderMap({ forecastDates: [TODAY] });
    // `topicsSuppressed` is `isMobile && bigSeasSelection.form != null`; with no line, no form.
    expect(screen.queryByTestId('wf-big-seas')).toBeNull();
  });

  it('shows the line, but the peek sheet\'s window rows carry no bolt and no ", big seas" clause', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    expect(screen.getByTestId('wf-big-seas')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-map-peek-win-row');
    expect(rows.some((r) => r.getAttribute('data-ev-id') === `solar:${SAT}:SUNSET`)).toBe(true);
    expect(screen.queryByTestId('wf-win-row-bigseas')).toBeNull();
    expect(rows.every((r) => !r.querySelector('.wf-bolt') && !/big seas/i.test(r.textContent))).toBe(true);
  });
});
